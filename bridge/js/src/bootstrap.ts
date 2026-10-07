/// <reference types="node" />

import net from 'node:net'
import { BridgeEnvelope, BridgeRequest, BridgeResponse, InvokeHandler } from './bridge'
import { AutojsError, ErrCode, errFromThrown } from './errors'
import { runtimeBridge } from './runtime'

/**
 * bootstrap loader（docs §7.5 / §12.4）：
 * 把 [RuntimeBridgeImpl.install] 的投递回调和 [handleResponse] 结算接上真实传输。
 *
 * 两个接入面：
 * 1. 嵌入式宿主（Node 内置 Android 进程）：[attachNative] / [NativeBootstrap] ——
 *    fd 由宿主完成 hello/ACK 后经 kBootstrap 注入（addon 不自连，本面**不碰** `setSocketFd`），
 *    `setup(onFrame)` 按 id 结算回包、`addon.invoke` 直接作 [InvokeHandler] 注入
 *    （addon 侧导出即为 InvokeHandler 形），经 JNI→Kotlin Router 走全异步。
 * 2. 开发/回退面（桌面/CI 无 addon）：[SocketBootstrap] unix socket + newline-delimited
 *    JSON frame 直连。先 hello 认证，再传业务 request/response；requestId 仅在本连接内关联。
 *
 * 背压：队列 + drain 续写（§7.5 背压可控）；不阻塞脚本事件循环。
 */

export interface SocketBootstrapOptions {
  /** socket 路径；默认 AUTOSCRIPT_HOST_SOCKET。 */
  socketPath?: string
  /** 宿主签发的一次性凭据；默认 AUTOSCRIPT_BRIDGE_TOKEN。不用 runNonce 代替。 */
  token?: string
  maxFrameBytes?: number
  /** 建连与身份握手共享的总期限。 */
  connectTimeout?: number
}

const DEFAULT_MAX_FRAME = 8 * 1024 * 1024
const FALLBACK_CONNECT_TIMEOUT = 10_000
const MAX_HELLO_BYTES = 1024

/** 只有 helloAck 才代表 READY；TCP/unix connect 本身不是认证成功。 */
export class SocketBootstrap {
  readonly socketPath: string
  readonly maxFrameBytes: number
  readonly connectTimeout: number
  private token: string | undefined
  private socket: net.Socket | null = null
  private state: 'NEW' | 'CONNECTING' | 'AUTHENTICATING' | 'READY' | 'CLOSED' = 'NEW'
  private connectPromise: Promise<void> | null = null
  private resolveConnect: (() => void) | null = null
  private rejectConnect: ((e: Error) => void) | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private buffer: Buffer = Buffer.alloc(0)
  private queue: BridgeRequest[] = []
  private draining = false

  constructor(opts: SocketBootstrapOptions = {}) {
    const p = opts.socketPath ?? process.env.AUTOSCRIPT_HOST_SOCKET
    if (!p) throw new Error('SocketBootstrap 需要 socketPath 或 AUTOSCRIPT_HOST_SOCKET')
    this.socketPath = p
    this.token = opts.token ?? process.env.AUTOSCRIPT_BRIDGE_TOKEN
    this.maxFrameBytes = opts.maxFrameBytes ?? DEFAULT_MAX_FRAME
    this.connectTimeout = opts.connectTimeout ?? FALLBACK_CONNECT_TIMEOUT
    if (this.maxFrameBytes <= 0 || this.connectTimeout <= 0) throw new Error('桥帧上限/连接期限必须 > 0')
  }

  onSocketError: ((e: Error) => void) | null = null
  get connected(): boolean { return this.state === 'READY' }

  connect(): Promise<void> {
    if (this.state === 'CLOSED') return Promise.reject(this.stopped('桥 socket 已关闭'))
    if (this.connectPromise) return this.connectPromise
    if (!this.token || !/^[0-9a-f]{64}$/.test(this.token)) {
      this.state = 'CLOSED'
      return Promise.reject(new AutojsError({ code: 'ERR_PERMISSION_DENIED', detail: '缺有效桥身份凭据' }))
    }
    this.state = 'CONNECTING'
    this.connectPromise = new Promise((resolve, reject) => {
      this.resolveConnect = resolve
      this.rejectConnect = reject
    })
    this.timer = setTimeout(() => this.fail(this.stopped('桥连接/身份握手超时')), this.connectTimeout)
    try {
      const sock = net.connect(this.socketPath)
      this.socket = sock
      sock.on('connect', () => {
        if (this.state !== 'CONNECTING') return
        this.state = 'AUTHENTICATING'
        sock.write(JSON.stringify({ t: 'hello', v: 1, token: this.token }) + '\n')
        if (process.env.AUTOSCRIPT_BRIDGE_TOKEN === this.token) delete process.env.AUTOSCRIPT_BRIDGE_TOKEN
        this.token = undefined
      })
      sock.on('data', (chunk: Buffer) => this.onData(chunk))
      sock.on('error', (e) => this.fail(e))
      sock.on('close', () => this.fail(this.stopped('桥 socket 已断开')))
    } catch (e) { this.fail(e instanceof Error ? e : this.stopped('桥建连失败')) }
    return this.connectPromise
  }

  readonly handler: InvokeHandler = (ns, method, payload, reqId, ttl) => {
    this.checkConnected()
    this.queue.push(BridgeEnvelope.encodeRequest({ id: reqId, ns, m: method, payload, ttl, side: null }))
    this.drain()
    return undefined
  }

  private checkConnected(): void {
    if (!this.connected) throw this.stopped('桥尚未通过身份认证')
  }

  install(): void { this.checkConnected(); runtimeBridge.install(this.handler) }

  private drain(): void {
    if (this.draining || !this.connected || !this.socket) return
    const sock = this.socket
    this.draining = true
    while (this.queue.length > 0 && this.connected) {
      // write(false) 也已接收这帧，必须先出队，等 drain 只能续写下一帧。
      const req = this.queue.shift()!
      if (!sock.write(JSON.stringify(req) + '\n')) {
        sock.once('drain', () => { this.draining = false; this.drain() })
        return
      }
    }
    this.draining = false
  }

  private onData(chunk: Buffer): void {
    if (this.state === 'CLOSED') return
    this.buffer = Buffer.concat([this.buffer, chunk])
    for (;;) {
      const limit = this.state === 'READY' ? this.maxFrameBytes : MAX_HELLO_BYTES
      const nl = this.buffer.indexOf(0x0a)
      if ((nl < 0 && this.buffer.length > limit) || nl > limit) {
        this.fail(new Error(`桥帧超过上限 ${limit} 字节`))
        return
      }
      if (nl < 0) return
      const line = this.buffer.subarray(0, nl)
      this.buffer = this.buffer.subarray(nl + 1)
      let frame: Record<string, unknown>
      try { frame = JSON.parse(line.toString('utf8')) as Record<string, unknown> }
      catch { this.fail(new Error('桥响应帧非法 JSON')); return }
      if (this.state === 'AUTHENTICATING') {
        if (!frame || frame.t !== 'helloAck' || frame.v !== 1 || Object.keys(frame).some(k => k !== 't' && k !== 'v')) {
          this.fail(new AutojsError({ code: 'ERR_PERMISSION_DENIED', detail: '桥身份认证被拒绝或握手版本不符' }))
          return
        }
        this.state = 'READY'
        if (this.timer) clearTimeout(this.timer)
        this.timer = null
        this.resolveConnect?.()
        this.resolveConnect = null
        this.rejectConnect = null
      } else if (this.state === 'READY' && frame && (frame.t === 'ok' || frame.t === 'err')) {
        runtimeBridge.handleResponse(frame as unknown as BridgeResponse)
      } else if (this.state !== 'READY') {
        this.fail(this.stopped('桥握手顺序错误'))
        return
      }
    }
  }

  private stopped(detail: string): AutojsError { return new AutojsError({ code: 'ERR_ENGINE_STOPPED', detail }) }

  private fail(e: Error): void {
    if (this.state === 'CLOSED') return
    this.terminate(e)
    this.onSocketError?.(e)
  }

  private terminate(e: Error): void {
    this.state = 'CLOSED'
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
    this.rejectConnect?.(e)
    this.rejectConnect = null
    this.resolveConnect = null
    this.token = undefined
    this.queue = []
    this.buffer = Buffer.alloc(0)
    this.socket?.destroy()
    this.socket = null
  }

  close(): void { if (this.state !== 'CLOSED') this.terminate(this.stopped('桥 socket 已关闭')) }
}

/** 便捷入口：按 opts 创建 + 连接 + 安装。 */
export async function connectBootstrap(opts: SocketBootstrapOptions = {}): Promise<SocketBootstrap> {
  const b = new SocketBootstrap(opts)
  await b.connect()
  b.install()
  return b
}

/** N-API addon 的 JS 面（`bridge_addon.cc` 导出；结构型契约 —— 单测可注入假实现）。 */
export interface BridgeNativeAddon {
  /** 宿主注入已连 socket fd（**本面不调** —— §7.5 建连归宿主，见 [NativeBootstrap] KDoc）。 */
  setSocketFd(fd: number): void
  /** 一次性建 data 面 TSF：回包行文本回调（二次调用 addon 侧抛 ERR_INVALID_PARAM）。 */
  setup(onFrame: (line: string) => void): void
  /** 投递请求帧（InvokeHandler 同形）；未注 fd / 写失败 → 同步抛 `ERR_*`（`.code` 在普通 Error 上）。 */
  invoke(ns: string, method: string, payloadJson: string | null, reqId: number, ttl: number): void
}

export interface NativeBootstrapOptions {
  /** addon 模块实例；缺省 `require(AUTOSCRIPT_BRIDGE_ADDON)`（宿主 spawn 注入的路径）。 */
  addon?: BridgeNativeAddon
}

/**
 * 嵌入式宿主接入面（§7.8 启动序③「JS 侧 setup 由 facade 接入时调」的落地）：
 * - [setup]：`addon.setup(onFrame)` 一次性接结算面 —— ok/err 行 → [runtimeBridge.handleResponse]
 *   按在途 id 结算；**负 id / 未知 id 静默丢**（kBootstrap 心跳 `-seq` 与跨代迟到响应的合同：
 *   查不到即丢，绝不撞别的在途调用）；非法 JSON 走 [onFrameError] 钩子，不炸在途；
 * - [install]：`addon.invoke` 直接作 [InvokeHandler] 注入（返回 undefined = 已投递、等结算；
 *   同步抛错经 `errFromThrown` 折叠 —— NAPI 的 `ERR_ENGINE_STOPPED` 等真码原样保留）；
 * - **不碰 `setSocketFd`**：fd 注入归宿主 kBootstrap（§7.5「宿主注入已连 fd、addon 不自连」），
 *   本面接的是"fd 已就位"之后的 JS 半边；
 * - 装配顺序 setup → install：TSF 先就位，任何回包都有人收（install 前到达的按未知 id 丢，
 *   与 `droppedData()` 的未 setup 记账互补）。
 *
 * 与 [SocketBootstrap] 同纪律：install 单例、重复安装由 [runtimeBridge] 拒绝。
 */
export class NativeBootstrap {
  readonly addon: BridgeNativeAddon

  /** 响应帧非法 JSON 时的诊断钩子（缺省 null：与 `onSocketError` 同款"默认不抛"）。 */
  onFrameError: ((e: Error) => void) | null = null

  private setupDone = false

  constructor(opts: NativeBootstrapOptions = {}) {
    if (opts.addon) {
      this.addon = opts.addon
      return
    }
    const p = process.env.AUTOSCRIPT_BRIDGE_ADDON
    if (!p) {
      throw new AutojsError({
        code: ErrCode.ENGINE_STOPPED,
        detail: '缺 AUTOSCRIPT_BRIDGE_ADDON（宿主未预载 addon：离线/未接线，快速拒绝不悬挂）',
      })
    }
    // eslint-disable-next-line @typescript-eslint/no-require-imports -- 动态路径 require N-API 模块（CJS 产物）
    this.addon = require(p) as BridgeNativeAddon
  }

  /**
   * 接结算面（幂等：本实例二次调用 no-op —— addon 侧 `setup` 是一次性的，
   * 重复 attach 场景由 [runtimeBridge.install] 的单例拒绝兜底，两层各管各的）。
   */
  setup(): void {
    if (this.setupDone) return
    this.addon.setup((line) => this.onFrame(line))
    this.setupDone = true
  }

  private onFrame(line: string): void {
    let frame: BridgeResponse
    try {
      frame = JSON.parse(line) as BridgeResponse
    } catch (e) {
      this.onFrameError?.(new Error(`桥响应帧非法 JSON: ${e instanceof Error ? e.message : String(e)}`))
      return
    }
    if (frame && (frame.t === 'ok' || frame.t === 'err')) {
      runtimeBridge.handleResponse(frame)
    }
    // 其他 t 值（事件帧 P1）由事件订阅层处理；本层忽略 —— 与 SocketBootstrap.onData 同口径
  }

  /** [InvokeHandler]：addon.invoke 直接注入；undefined = 已投递，等 onFrame 结算。 */
  readonly handler: InvokeHandler = (ns, method, payload, reqId, ttl) => {
    try {
      this.addon.invoke(ns, method, payload, reqId, ttl)
    } catch (e) {
      throw errFromThrown(e)
    }
    return undefined
  }

  /** 安装到单例桥（重复 install 由 runtimeBridge 拒绝，与 SocketBootstrap 同口径）。 */
  install(): void {
    runtimeBridge.install(this.handler)
  }
}

/**
 * 嵌入式宿主便捷接线（同步 —— 无 IO：fd 已由宿主注入，本函数只做 setup + install）。
 * 打包入口/脚本首行调一次；返回的实例挂 [NativeBootstrap.onFrameError] 诊断钩子。
 * 可 `await`（值被包成已兑现 Promise），与 `connectBootstrap` 的用法对称。
 */
export function attachNative(opts: NativeBootstrapOptions = {}): NativeBootstrap {
  const b = new NativeBootstrap(opts)
  b.setup()      // 先接结算面：TSF 就位后任何回包都有人收
  b.install()
  return b
}

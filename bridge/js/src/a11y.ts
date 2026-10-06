/**
 * 无障碍选择器（docs §9.1 / §12.3 a11y 选择器）：
 * Promise + 超时；findOne 无匹配抛 NotFoundError。
 * 类型面对应 :domain:automation。P0 实现走 RuntimeBridge.invoke 到 :main Router。
 */

import { runtimeBridge } from './runtime'
import { AutojsError, ErrCode, NotFoundError } from './errors'

/** 选择器条件（与 Kotlin UiSelector 语义对齐：全部条件 AND）。 */
export interface UiSelector {
  text?(v: string): this
  desc?(v: string): this
  id?(v: string): this
  className?(v: string): this
  packageName?(v: string): this
  clickable?(v: boolean): this
  time?(ms: number): this
  findOne(opts?: FindOneOptions): Promise<UiObject>
  findOneOrNull(opts?: FindOneOptions): Promise<UiObject | null>
  findAll(opts?: FindAllOptions): Promise<UiObject[]>
}

/** 每次查找的超时/频率；失败抛 NotFoundError（对齐 Pro v9）。 */
export interface FindOneOptions {
  timeout?: number
  interval?: number
  signal?: AbortSignal
}

/** findAll 的完整选项（max 返回上限）。 */
export interface FindAllOptions {
  timeout?: number
  max?: number
  signal?: AbortSignal
}

/**
 * 输入通道（§9.3，与 :domain `InputChannel` 逐字对齐）。
 *
 * **不是降级顺序，是三个平级通道**：`auto` 无障碍 / `adb` Shizuku / `root` su。
 * 指定哪条走哪条；指定的那条不可用就抛 `ERR_PERMISSION_DENIED`（带引导），
 * **绝不改用别的通道** —— 三者可观测后果不同（无障碍注入会被前台应用看出、
 * root 注入在系统层不留无障碍痕迹、adb 注入的进程身份是 shell），
 * 静默换通道等于让脚本以为在测 A 实际在测 B。
 */
export type InputChannelInput = 'auto' | 'adb' | 'root'

/**
 * 带通道选择的调用选项。
 *
 * **通道必须显式指定**（2026-10-06 用户口径「必须显式传，无默认」）：要么在这里给
 * `channel`，要么先用 [a11y.setInputChannel] 设过本脚本的会话通道。**两者都没有时
 * 宿主回 `ERR_INVALID_PARAM`**，不会替你选一条。
 *
 * **为什么 `channel` 在类型上仍是可选的**：设过会话值之后，省略就是**合法**的写法
 * （那也是一次显式选择，宿主认它）—— 类型系统看不见会话状态，把它标成必填会在
 * 会话值已设时逼调用方重复写一遍。所以判据只有一处：**宿主 handler**（与
 * `engines.exec` 的 `timeoutMillis` 同一条「校验不写两遍」纪律）。
 *
 * **为什么不像别处那样给个 `auto` 缺省**：三者可观测后果不同（见 [InputChannelInput]），
 * 缺省等于「什么都没说 = 走了无障碍」，而那正是这套机制要消灭的静默。
 */
export interface ChannelOptions {
  /** 走哪条通道（或先调 [a11y.setInputChannel]）；只作用于这一次调用，不改变会话值。 */
  channel?: InputChannelInput
  timeout?: number
  signal?: AbortSignal
}

/** 滚动方向（与 :domain ScrollDirection 对齐；小写 wire 名，Kotlin 侧大小写不敏感）。 */
export type ScrollDirectionInput = 'forward' | 'backward' | 'up' | 'down' | 'left' | 'right'

/**
 * 显式通道 → payload 片段：**没给就一个键都不加**，让宿主侧去判（会话值 or 报错）。
 *
 * **为什么不在 JS 侧预检**：与 `engines.exec` 的 `timeoutMillis` 同一条纪律 ——
 * 同一份校验写两遍必然漂移，缺通道的判据只该有一处（宿主 handler），JS 只负责
 * 把脚本说的原样发出去。所以这里**不**替调用方补 `auto`、也不在这里抛。
 */
function withChannel(opts: ChannelOptions | undefined): Record<string, unknown> {
  return opts?.channel === undefined ? {} : { channel: opts.channel }
}

/** 控件句柄代理（§7.4 gen/id）：JS 侧持 HandleRef，操作携带 generation 校验。 */
export interface UiObject {  readonly ref: { refId: number; generation: number }
  /**
   * 点击。`auto` 通道走节点语义 `ACTION_CLICK`；`adb`/`root` 通道**没有节点语义可用**，
   * 改为解出节点 bounds 再按坐标注入（点中心）—— 那是「点这个控件所在的位置」，
   * 不是「对这个控件发 action」。
   */
  click(opts?: ChannelOptions): Promise<boolean>
  longClick(opts?: ChannelOptions): Promise<boolean>
  /**
   * 滚动（§9.1 scroll；缺省向前；不可滚动容器回 false）。
   *
   * `auto` 走无障碍 Action；`adb`/`root` 解 bounds 后按方向在节点内划一条
   * （手指方向与内容方向相反，见 §9.3）。
   */
  scroll(direction?: ScrollDirectionInput, opts?: ChannelOptions): Promise<boolean>
  /**
   * 复制节点文本到剪贴板（§9.1 copy 走无障碍 Action；text ?? desc，皆空记空串）。
   * 粘贴剪贴板到可编辑节点（不可编辑/空剪贴板回 false，不抛错）。
   */
  copy(opts?: { timeout?: number; signal?: AbortSignal }): Promise<boolean>
  paste(opts?: { timeout?: number; signal?: AbortSignal }): Promise<boolean>
  setText(text: string, opts?: { timeout?: number; signal?: AbortSignal }): Promise<boolean>
  get bounds(): Promise<{ left: number; top: number; right: number; bottom: number }>
  get text(): Promise<string | null>
  get desc(): Promise<string | null>
  children(opts?: { timeout?: number }): Promise<UiObject[]>
  parent(opts?: { timeout?: number }): Promise<UiObject | null>
  dispose(): void
}

/** 无障碍命名空间（auto.a11y）。 */
export const a11y = {
  /** 创建选择器：链式条件，findOne 失败抛 NotFoundError。 */
  selector(): UiSelector {
    return new UiSelectorBuilder()
  },

  /** 等待某条件出现（一定次数内触发则成功；§12.3 waitFor）。载荷键见下：`conditions`。 */
  async waitFor(sel: UiSelector, opts: { timeout?: number; interval?: number } = {}): Promise<boolean> {
    // 载荷键是 conditions（与 findOne 同构）：Kotlin A11yNamespaceHandler 的
    // waitFor 复用同一条选择器解析路径，只认这一个键；发 selector 会被拒为
    // ERR_INVALID_PARAM（白名单外字段，诚实失败，不静默变全量匹配）。
    const result = await runtimeBridge.invoke('a11y', 'waitFor', {
      conditions: conditionsOf(sel),
      timeout: opts.timeout,
      interval: opts.interval,
    })
    return result === true
  },

  /**
   * 事件流拉取（§9.1 节流拉取式，seq 游标；对偶 Kotlin `a11y.events`）。
   * 空增量回 `{first:sinceSeq,last:sinceSeq,events:[]}`——调用方以前进游标为准。
   */
  async events(opts: { sinceSeq?: number; batch?: number; timeout?: number } = {}): Promise<UiEventBatch> {
    return (await runtimeBridge.invoke('a11y', 'events', {
      sinceSeq: opts.sinceSeq ?? 0,
      batch: opts.batch ?? 32,
    }, { ttl: opts.timeout ?? 10_000 })) as UiEventBatch
  },

  /** 手势能力门（§9.1 canPerformGestures；false 时走能力中心引导，不发手势）。 */
  /**
   * 当前通道能不能发手势（问的是**会话通道**，可用 `{channel}` 单次覆盖）。
   *
   * `auto` 问的是无障碍服务的 `CAPABILITY_CAN_PERFORM_GESTURES` 能力位；
   * `adb`/`root` 通道**没有对应的开关** —— 能不能用取决于进程身份，而那正是该通道
   * 被接线的前提，所以到得了这里的调用恒为 `true`。真正的失败（`su` 没了、Shizuku
   * 服务死了）以命令退出码的形式出现在动作调用里。
   */
  async canPerformGestures(opts: ChannelOptions = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('a11y', 'canPerformGestures', withChannel(opts), {
      ttl: opts.timeout ?? 5_000,
    })) === true
  },

  /**
   * 手势派发（§9.1 dispatchGesture；对偶 Kotlin `a11y.gesture`）。
   * 关门回 false（不抛错）；非法手势（空笔画/负坐标/非正 duration）抛 ERR_INVALID_PARAM。
   *
   * **经 `adb`/`root` 通道时实际是直线**：shell 面只有 `input tap`/`input swipe` 两个
   * 原语、没有轨迹，所以多笔画会被逐条串行注入、每条只取首尾两点。要真轨迹得走
   * `sendevent`（§9.3 提过，未落地）。
   */
  async gesture(input: GestureInput, opts: ChannelOptions = {}): Promise<boolean> {
    return (await runtimeBridge.invoke('a11y', 'gesture', { ...input, ...withChannel(opts) }, {
      ttl: opts.timeout ?? 10_000,
      signal: opts.signal,
    })) === true
  },

  /**
   * 设**本脚本**的会话输入通道（§9.3）：`await auto.a11y.setInputChannel('root')`。
   *
   * 会话态住宿主侧的**每连接**上下文（不是全局 handler 字段）—— 一个脚本设的通道
   * 不会漏给另一个脚本。单次调用可用 `{channel}` 覆盖，不改变会话值。
   *
   * **没有「缺省通道」**：设过会话值之后，本脚本后续的 `click`/`gesture`/… 可以省掉
   * `channel`；**没设过又没传**则抛 `ERR_INVALID_PARAM`。指定了 `adb`/`root` 而不可用时
   * 抛 `ERR_PERMISSION_DENIED`，**不会悄悄回落到无障碍**。
   */
  async setInputChannel(channel: InputChannelInput, opts: { timeout?: number } = {}): Promise<void> {
    await runtimeBridge.invoke('a11y', 'setInputChannel', { channel }, { ttl: opts.timeout ?? 5_000 })
  },
}

/** 手势点（逻辑像素；非负）。 */
export interface GesturePointInput {
  readonly x: number
  readonly y: number
}

/** 手势笔画（一起点 + 持续时长；与 :domain GestureStroke 对齐）。 */
export interface GestureStrokeInput {
  readonly points: readonly GesturePointInput[]
  readonly startDelayMillis?: number
  readonly durationMillis?: number
}

/** 手势输入（至少一个笔画；与 :domain GestureInput 对齐）。 */
export interface GestureInput {
  readonly strokes: readonly GestureStrokeInput[]
}

/** 无障碍事件（对偶 :domain UiEvent：seq/type/node/payload）。 */
export interface UiEvent {
  readonly seq: number
  readonly type: string
  readonly node: { refId: number; generation: number } | null
  readonly payload: string | null
}

/** 事件批次（对偶 :domain UiEventBatch：游标 first/last + 事件数组）。 */
export interface UiEventBatch {
  readonly first: number
  readonly last: number
  readonly events: UiEvent[]
}

/** 条件提取（module 层 JSON 序列化的最小形态；运行时实现负责还原）。 */
export function conditionsOf(sel: UiSelector): Record<string, unknown> {
  // UiSelector 实现自持条件表；此函数作为兼容入口，运行时走 invoke 时无需调用方提取。
  return (sel as unknown as { __conditions__: Record<string, unknown> }).__conditions__
}

class UiSelectorBuilder implements UiSelector {
  readonly __conditions__: Record<string, unknown> = {}
  private ttl = 5_000
  private interval = 300

  text(v: string): this { this.__conditions__.text = v; return this }
  desc(v: string): this { this.__conditions__.desc = v; return this }
  id(v: string): this { this.__conditions__.id = v; return this }
  className(v: string): this { this.__conditions__.className = v; return this }
  packageName(v: string): this { this.__conditions__.packageName = v; return this }
  clickable(v: boolean): this { this.__conditions__.clickable = v; return this }
  time(ms: number): this { this.ttl = ms; return this }

  async findOne(opts: FindOneOptions = {}): Promise<UiObject> {
    const found = await this.findOneOrNull(opts)
    if (!found) throw new NotFoundError('选择器无匹配')
    return found
  }

  async findOneOrNull(opts: FindOneOptions = {}): Promise<UiObject | null> {
    let result: unknown
    try {
      result = await runtimeBridge.invoke('a11y', 'findOne', {
        conditions: this.__conditions__,
        timeout: opts.timeout ?? this.ttl,
        interval: opts.interval ?? this.interval,
      }, { ttl: opts.timeout ?? this.ttl, signal: opts.signal })
    } catch (e) {
      // 无匹配是正常控制流（Kotlin 回 ERR_NOT_FOUND），不是异常：折叠为 null。
      // 其他错误（TTL/ENGINE_STOPPED/INVALID_PARAM）如实上抛。
      if (e instanceof AutojsError && e.code === ErrCode.NOT_FOUND) return null
      throw e
    }
    const node = result as { ref: { refId: number; generation: number } }
    return node == null ? null : wrapUiObject(node.ref)
  }

  async findAll(opts: FindAllOptions = {}): Promise<UiObject[]> {
    const result = await runtimeBridge.invoke('a11y', 'findAll', {
      conditions: this.__conditions__,
      timeout: opts.timeout ?? this.ttl,
      max: opts.max,
    }, { ttl: opts.timeout ?? this.ttl, signal: opts.signal })
    const nodes = ((result as Array<{ ref: { refId: number; generation: number } }>) ?? [])
    return nodes.map((n) => wrapUiObject(n.ref))
  }
}

/** 句柄代理：Kotlin 回包是 {ref} 纯数据，动作经 invoke 回桥（携带 generation 校验）。 */
function wrapUiObject(ref: { refId: number; generation: number }): UiObject {
  const call = (method: string, params: unknown, ttl = 10_000): Promise<unknown> =>
    runtimeBridge.invoke('a11y', method, { ref, ... (params as Record<string, unknown>) }, { ttl })
  return {
    ref,
    click: async (opts = {}) => (await call('click', withChannel(opts))) === true,
    longClick: async (opts = {}) => (await call('longClick', withChannel(opts))) === true,
    scroll: async (direction = 'forward', opts: ChannelOptions = {}) =>
      (await runtimeBridge.invoke('a11y', 'scroll', { ref, direction, ...withChannel(opts) }, {
        ttl: opts.timeout ?? 10_000,
        signal: opts.signal,
      })) === true,
    setText: async (text: string) => (await call('setText', { text })) === true,
    copy: async () => (await call('copy', null)) === true,
    paste: async () => (await call('paste', null)) === true,
    get bounds(): Promise<{ left: number; top: number; right: number; bottom: number }> {
      return call('bounds', null) as Promise<{ left: number; top: number; right: number; bottom: number }>
    },
    get text(): Promise<string | null> {
      return call('text', null) as Promise<string | null>
    },
    get desc(): Promise<string | null> {
      return call('desc', null) as Promise<string | null>
    },
    children: async () => {
      const kids = (await call('children', null)) as Array<{ ref: { refId: number; generation: number } }>
      return (kids ?? []).map((k) => wrapUiObject(k.ref))
    },
    parent: async () => {
      const p = (await call('parent', null)) as { ref: { refId: number; generation: number } } | null
      return p == null ? null : wrapUiObject(p.ref)
    },
    dispose: (): void => {
      // fire-and-forget（幂等释放，不阻塞脚本；失败走 console queueError 面，不抛）。
      runtimeBridge.invoke('a11y', 'dispose', { ref }, { ttl: 2_000 }).catch(() => undefined)
    },
  }
}
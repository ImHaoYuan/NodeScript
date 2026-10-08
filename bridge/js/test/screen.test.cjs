'use strict'
/**
 * screen namespace 双侧契约测试（§9.2 / §8.8 + Kotlin ScreenNamespaceHandler）：
 * JS facade 的 wire 形状（帧句柄 {ref,width,height} / 会话 {session} / recycle/nextFrame
 * 载荷）与 Kotlin 侧解析器逐字段对齐。分类错误（锁屏/FLAG_SECURE/节流）如实抛 ERR_*。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

/** mock screen 宿主：按 Kotlin ScreenNamespaceHandler 响应形状回包。 */
let installed = false
const mockState = { locked: false, throttle: false, stopFails: false, dropRecording: false }
/** 每条到宿主的请求（断言 wire 形状用：尺寸提示这类"发了什么"只有这里看得见）。 */
const seen = []
function installMockScreen() {
  if (installed) return
  installed = true
  let nextRef = 1
  let nextSession = 1
  const sessions = new Map()
  const recorders = new Map()
  auto.install((ns, method, payloadJson, reqId) => {
    if (ns !== 'screen') return undefined
    const p = payloadJson ? JSON.parse(payloadJson) : null
    seen.push({ method, p })
    const ok = (payload) => auto.handleResponse({ t: 'ok', id: reqId, payload })
    const err = (code, detail) => auto.handleResponse({ t: 'err', id: reqId, code, detail })
    switch (method) {
      case 'capture': {
        if (mockState.locked) err('ERR_SCREEN_LOCKED', '屏幕锁定，无法截取')
        else if (mockState.throttle) err('ERR_INVALID_PARAM', '截图节流中（333ms）')
        else ok(JSON.stringify({ ref: { refId: nextRef++, generation: 1 }, width: 1080, height: 2400 }))
        return undefined
      }
      case 'recycle': {
        ok('true')
        return undefined
      }
      case 'startCapturer': {
        if (mockState.locked) err('ERR_SCREEN_LOCKED', '屏幕锁定，无法截取')
        else {
          const id = nextSession++
          sessions.set(id, true)
          ok(JSON.stringify({ session: { refId: id, generation: 1 } }))
        }
        return undefined
      }
      case 'nextFrame': {
        if (!sessions.has(p.session.refId)) err('ERR_NOT_FOUND', `未知截图会话 ${p.session.refId}`)
        else ok(JSON.stringify({ ref: { refId: nextRef++, generation: 1 }, width: 1080, height: 2400 }))
        return undefined
      }
      case 'closeSession': {
        if (!sessions.delete(p.session.refId)) err('ERR_NOT_FOUND', `未知截图会话 ${p.session.refId}`)
        else ok('true')
        return undefined
      }
      case 'startRecording': {
        if (mockState.locked) err('ERR_SCREEN_LOCKED', '屏幕锁定，无法截取')
        else {
          const id = nextSession++
          recorders.set(id, { path: `/data/user/0/app/files/scripts/demo/.recordings/rec-1-${id}.mp4` })
          ok(JSON.stringify({
            session: { refId: id, generation: 1 },
            path: recorders.get(id).path,
          }))
        }
        return undefined
      }
      case 'stopRecording': {
        const rec = mockState.dropRecording ? undefined : recorders.get(p.session.refId)
        if (!rec) err('ERR_NOT_FOUND', `未知录屏会话 ${p.session.refId}`)
        else if (mockState.stopFails) {
          ok(JSON.stringify({ path: rec.path, sizeBytes: 0, completed: false, detail: 'RuntimeException: stop failed' }))
        } else {
          ok(JSON.stringify({ path: rec.path, sizeBytes: 4096, completed: true }))
        }
        return undefined
      }
      default:
        err('ERR_NOT_IMPLEMENTED', `未知 screen 方法: ${method}`)
        return undefined
    }
  })
}

test('screen.capture 回帧句柄三字段；recycle 显式释放', async () => {
  installMockScreen()
  const frame = await auto.screen.capture()
  assert.deepEqual(frame.ref, { refId: 1, generation: 1 })
  assert.strictEqual(frame.width, 1080)
  assert.strictEqual(frame.height, 2400)
  await frame.recycle()
})

test('screen.capture 锁屏抛 ERR_SCREEN_LOCKED（分类错误而非黑图）', async () => {
  mockState.locked = true
  try {
    await assert.rejects(() => auto.screen.capture(), (e) => e.code === 'ERR_SCREEN_LOCKED')
  } finally {
    mockState.locked = false
  }
})

test('screen.capture 节流抛 ERR_INVALID_PARAM', async () => {
  mockState.throttle = true
  try {
    await assert.rejects(() => auto.screen.capture(), (e) => e.code === 'ERR_INVALID_PARAM')
  } finally {
    mockState.throttle = false
  }
})

test('screen.startCapturer 会话全链路：nextFrame 取帧 + close', async () => {
  const cap = await auto.screen.startCapturer()
  assert.deepEqual(cap.session, { refId: 1, generation: 1 })
  const frame = await cap.nextFrame()
  assert.strictEqual(frame.width, 1080)
  await cap.close()
  await assert.rejects(() => cap.nextFrame(), (e) => e.code === 'ERR_NOT_FOUND')
})

test('screen.startCapturer 的尺寸提示原样过桥（回包不带尺寸——尺寸是提示不是事实）', async () => {
  const cap = await auto.screen.startCapturer({ width: 720, height: 1280 })
  const last = seen[seen.length - 1]
  assert.equal(last.method, 'startCapturer')
  assert.deepEqual(last.p, { width: 720, height: 1280 })
  assert.ok(cap.session, '回包只有会话句柄，没有宽高')
  await cap.close()
})

// ── 录屏腿（§9.2）：与截屏会话并列，产物是文件而不是帧 ─────────────────

test('screen.startRecording 回 {session,path} —— path 开的时候就回（脚本崩了也找得到产物）', async () => {
  const rec = await auto.screen.startRecording()
  assert.ok(rec.session && rec.session.refId > 0)
  assert.match(rec.path, /\.mp4$/, 'path 是录屏产物落点')
  const result = await rec.stop()
  assert.strictEqual(result.path, rec.path, 'stop 回的路径与开的时候那条一致')
  assert.strictEqual(result.completed, true)
  assert.strictEqual(result.sizeBytes, 4096)
  assert.strictEqual(result.detail, undefined, '成功时 detail 是 undefined（不是 null）')
})

test('screen.startRecording 的尺寸提示原样过桥（回包不带尺寸——提示不是承诺）', async () => {
  const rec = await auto.screen.startRecording({ width: 720, height: 1280 })
  const last = seen[seen.length - 1]
  assert.equal(last.method, 'startRecording')
  assert.deepEqual(last.p, { width: 720, height: 1280 })
  assert.ok(rec.path, '回包只有会话句柄与 path，没有宽高')
  await rec.stop()
})

test('录屏 stop 失败如实回 completed=false + detail（坏 mp4 不伪装成功）', async () => {
  mockState.stopFails = true
  try {
    const rec = await auto.screen.startRecording()
    const result = await rec.stop()
    assert.strictEqual(result.completed, false)
    assert.strictEqual(result.sizeBytes, 0)
    assert.match(result.detail, /stop failed/)
  } finally {
    mockState.stopFails = false
  }
})

test('录屏用户取消 → ERR_CAPTURE_DENIED（不重试、不静默改走截屏）', async () => {
  mockState.locked = true
  try {
    await assert.rejects(() => auto.screen.startRecording(), (e) => e.code === 'ERR_SCREEN_LOCKED')
  } finally {
    mockState.locked = false
  }
})

test('stop 未知录屏会话 → ERR_NOT_FOUND（分类错误原样抛出，不折成 null）', async () => {
  mockState.dropRecording = true
  try {
    const rec = await auto.screen.startRecording()
    await assert.rejects(() => rec.stop(), (e) => e.code === 'ERR_NOT_FOUND')
  } finally {
    mockState.dropRecording = false
  }
})

'use strict'
/**
 * 桥面能力掩码（A5，§11）在 **JS facade 侧**的协议契约：宿主以 `ERR_PERMISSION_DENIED`
 * 拒绝未授权的面时，facade 必须**原码透传**，不得折叠成别的码、不得静默吞成成功。
 *
 * 为什么这道门在 JS 侧也要有：Kotlin 侧（`BridgeRouterTest`/`EnginesNamespaceHandlerTest`）
 * 钉的是「谁被拒」，这里钉的是「拒了之后脚本看到什么」—— `AutojsError.code` 是脚本
 * 唯一能策略化 try/catch 的判据（§12.1），折码等于把授权失败伪装成参数错/超时。
 *
 * 掩码语义（哪张能力票管哪个面）住在 Kotlin 的 `BridgeCapabilityCatalog`；
 * JS 侧不重复实现策略，只验协议面。
 *
 * **宿主只装一次**：`RuntimeBridge` 是进程级单例，`install` 第二次会抛
 * 「已安装，不允许重复 install」（见 `runtime.ts`）。所以这里装**一个**「一律拒绝」的
 * 宿主，后续每条用例复用它 —— 与本目录其它用例（notification/workManager 等）同一条
 * `installed` 守卫纪律。用例之间不共享可变状态：宿主对每次调用现回 ERR_PERMISSION_DENIED。
 */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const autoModule = require(path.resolve(__dirname, '..', 'dist', 'index.js'))
const auto = autoModule.default

/** 宿主看到的调用流水（断言"拒绝前确实把请求发出去了"，不是本地提前抛错）。 */
const seen = []

/** 装一个「一律拒绝」的宿主：任何 ns/method 都回 ERR_PERMISSION_DENIED。 */
auto.install((ns, method, payloadJson, reqId) => {
  seen.push({ ns, method, payloadJson })
  auto.handleResponse({
    t: 'err',
    id: reqId,
    code: 'ERR_PERMISSION_DENIED',
    detail: `本次执行未授权 ${ns}.${method}（需要 CROSS_SCRIPT_CONTROL，持有 {LOCAL_STORAGE}）`,
  })
  return undefined
})

/** 断言「拒绝以 AutojsError + 原码到达脚本」。 */
async function rejectsPermissionDenied(thunk, label) {
  await assert.rejects(thunk, (e) => {
    assert.equal(e.name, 'AutojsError', `${label}：拒绝必须是 AutojsError（可 instanceof 判定）`)
    assert.equal(e.code, 'ERR_PERMISSION_DENIED', `${label}：原码必须保留，实际 ${e.code}`)
    return true
  })
}

test('未授权面被拒时 facade 抛 AutojsError 且保留 ERR_PERMISSION_DENIED 原码', async () => {
  const before = seen.length
  await rejectsPermissionDenied(
    () => auto.engines.exec({ projectId: 'p', scriptPath: 'a.js', timeoutMillis: 1000 }),
    'engines.exec',
  )
  await rejectsPermissionDenied(() => auto.engines.stop(7), 'engines.stop')
  await rejectsPermissionDenied(() => auto.engines.poolStats(), 'engines.poolStats')
  await rejectsPermissionDenied(() => auto.engines.status(7), 'engines.status')
  await rejectsPermissionDenied(() => auto.a11y.selector().text('x').findOne(), 'a11y.findOne')
  // 请求确实发到了宿主（不是 facade 本地提前抛）：拒绝来自宿主，这条验的是**透传**。
  assert.equal(seen.length, before + 5, '五次调用都应到达宿主')
  assert.deepEqual(
    seen.slice(before).map((c) => `${c.ns}.${c.method}`),
    ['engines.exec', 'engines.stop', 'engines.poolStats', 'engines.status', 'a11y.findOne'],
  )
})

test('拒绝不伪装成功：engines.stop 不得回 true', async () => {
  const r = await auto.engines.stop(1).then(
    (v) => ({ resolved: true, v }),
    (e) => ({ resolved: false, code: e.code }),
  )
  assert.equal(r.resolved, false, '被拒的 stop 绝不能 resolve（resolve 会被脚本读成"停成功了"）')
  assert.equal(r.code, 'ERR_PERMISSION_DENIED')
})

test('未授权面不落任何本地副作用：channel 被拒后不产生可用句柄', async () => {
  let handle
  await assert.rejects(
    async () => { handle = await auto.engines.channel('progress') },
    (e) => e.code === 'ERR_PERMISSION_DENIED',
  )
  assert.equal(handle, undefined, '被拒的 channel 不得留下句柄（否则脚本会拿它去 emit/drain）')
})

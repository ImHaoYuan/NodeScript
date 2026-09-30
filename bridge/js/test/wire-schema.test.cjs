'use strict'
/**
 * wire 四向对账门（审查步骤 7，schema 单源版）。
 *
 * 取代 `wire-reconcile.test.cjs` 的「正则啃 Kotlin `when` 块」方案：那道门的底账是
 * 源码文本（花括号计数解析、全局方法名集合、不认 ns→handler 归属），改一处 handler
 * 结构就可能假绿/假红。现在底账是 `bridge/schema/wire.schema.json`（wire 面单一事实
 * 来源），四向各拿各的地面真相：
 *
 * 1. **生成物同步**：`generate.mjs --check` —— 入库的 wire-types.ts / WireMethods.kt
 *    与 schema 逐字一致（CI 另有 `npm run gen:wire && git diff --exit-code`）；
 * 2. **JS facade → schema**：`invoke('<ns>','<m>')` 字面量 + dynamicSinks（schema 登记，
 *    按 form 映射正则）—— facade 发的每个 ns 必须在 schema、每个方法必须在该 ns 表内；
 * 3. **Kotlin register ↔ schema**：生产源 `register("<ns>")` 集合与 schema 键**双向相等**
 *    （没 register = 整段命名空间 404；schema 列了没人挂 = 表在说谎）；
 * 4. **Kotlin handler 申报 ↔ schema**：每个 handler 的
 *    `override fun methods() = WireMethods.BY_NS.getValue("<ns>")` 汇出的 ns 集合与
 *    schema 键双向相等 —— 申报即接线，缺申报/挂错 ns 都红；
 * 5. **死分支与 aliases**：schema 方法必须被 facade 发或登记在 aliases（宿主收一个
 *    没人发的 wire 名 = 死分支）；aliases 本身不许虚报（必须真在表内、确实无人发、
 *    理由写清）—— 表与理由都在 schema，同文件同改。
 *
 * 不做的（与旧门同口径）：动态构造的方法名/命名空间不在 JS 解析范围（本仓目前没有
 * 这种写法，新增即红，逼着改回字面量或更新 dynamicSinks）；不验 §12.2 愿望清单
 * （`auto.ui.*` 等设计面，未实现是常态）。
 *
 * 有牙自检在最后一组：解析器被写空（读不到 when/invoke/register/申报）必须先红，
 * 不许四条检查一起假绿。
 */
const assert = require('node:assert/strict')
const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

/** 仓库根（向上找 settings.gradle.kts；不依赖 CWD）。 */
function repoRoot() {
  let p = path.resolve(__dirname, '..', '..')
  for (let i = 0; i < 8; i += 1) {
    if (fs.existsSync(path.join(p, 'settings.gradle.kts'))) return p
    p = path.dirname(p)
  }
  throw new Error('找不到仓库根（settings.gradle.kts）')
}
const ROOT = repoRoot()

const SCHEMA_PATH = path.join(ROOT, 'bridge/schema/wire.schema.json')
const GENERATE = path.join(ROOT, 'bridge/schema/generate.mjs')
const schema = JSON.parse(fs.readFileSync(SCHEMA_PATH, 'utf8'))

/** dynamicSinks 的 form → 方法名字面量正则（schema 登记；新 form 先来这里登记）。 */
const SINK_RES = {
  call: /\bcall\(\s*'([A-Za-z0-9_]+)'/g,
}

function walk(dir, out, skip = new Set(['node_modules', 'build', '.git', 'dist', 'generated', 'intermediates', '.gradle', '.kotlin'])) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (skip.has(e.name) || e.name === 'module-stubs') continue
      walk(path.join(dir, e.name), out, skip)
    } else out.push(path.join(dir, e.name))
  }
  return out
}

/** ② JS 侧：facade 发的 ns → 方法（invoke 字面量 + schema 登记的 dynamicSinks）。 */
function jsSide() {
  const byNs = new Map()
  const add = (ns, m) => {
    if (!byNs.has(ns)) byNs.set(ns, new Set())
    byNs.get(ns).add(m)
  }
  const sinksByFile = new Map()
  for (const s of schema.dynamicSinks) {
    const re = SINK_RES[s.form]
    assert.ok(re, `dynamicSinks.form="${s.form}" 没有对应正则（先在 SINK_RES 登记再进 schema）`)
    if (!sinksByFile.has(s.file)) sinksByFile.set(s.file, [])
    sinksByFile.get(s.file).push({ ns: s.ns, re })
  }
  for (const f of walk(path.join(ROOT, 'bridge/js/src'), [])) {
    if (!f.endsWith('.ts')) continue
    const t = fs.readFileSync(f, 'utf8')
    for (const [, ns, m] of t.matchAll(/invoke\(\s*['"]([A-Za-z0-9_.]+)['"]\s*,\s*['"]([A-Za-z0-9_]+)['"]/g)) add(ns, m)
    for (const s of sinksByFile.get(path.basename(f)) ?? []) {
      for (const [, m] of t.matchAll(s.re)) add(s.ns, m)
    }
  }
  return byNs
}

/** ③ Kotlin 侧：生产源 register("<ns>") 集合（测试里的假注册器不计）。 */
function registeredNamespaces() {
  const out = new Set()
  for (const f of walk(ROOT, [])) {
    if (!f.endsWith('.kt') || !f.includes(path.sep + 'main' + path.sep)) continue
    const t = fs.readFileSync(f, 'utf8')
    for (const m of t.matchAll(/(?:\.|(?<![\w.]))register\(\s*"([A-Za-z_]\w*)"/g)) out.add(m[1])
  }
  return out
}

/**
 * ④ Kotlin 侧：handler 申报的 ns 集合 —— 只认单源写法
 * `override fun methods(): Set<String> = WireMethods.BY_NS.getValue("<ns>")`。
 */
function declaredHandlerNamespaces() {
  const out = new Set()
  const files = []
  walk(path.join(ROOT, 'domain'), files)
  walk(path.join(ROOT, 'bridge'), files)
  walk(path.join(ROOT, 'app-service'), files)
  walk(path.join(ROOT, 'platform'), files)
  walk(path.join(ROOT, 'app'), files)
  const re = /override fun methods\(\): Set<String> = WireMethods\.BY_NS\.getValue\("([A-Za-z_]\w*)"\)/g
  for (const f of files) {
    if (!f.endsWith('.kt') || !f.includes(path.sep + 'main' + path.sep)) continue
    const t = fs.readFileSync(f, 'utf8')
    for (const [, ns] of t.matchAll(re)) out.add(ns)
  }
  return out
}

const JS = jsSide()
const SCHEMA_NS = new Set(Object.keys(schema.namespaces))
const REGISTERED = registeredNamespaces()
const DECLARED = declaredHandlerNamespaces()

/** 每 ns 的 facade 已发送集合。 */
function sentTo(ns) {
  return JS.get(ns) ?? new Set()
}

test('生成物与 schema 同步（generate.mjs --check，入库产物不许手改）', () => {
  execFileSync(process.execPath, [GENERATE, '--check'], { stdio: 'pipe' })
})

test('facade 发的每个命名空间 schema 都有（没列 = 这段 wire 无处对账）', () => {
  const missing = [...JS.keys()].filter((ns) => !SCHEMA_NS.has(ns)).sort()
  assert.deepStrictEqual(missing, [], `这些命名空间 facade 在调但 schema 没有：${missing.join(', ')}`)
})

test('facade 发的每个方法都在该 ns 的 schema 表内（否则运行期 NOT_IMPLEMENTED = 类型面在说谎）', () => {
  const missing = []
  for (const [ns, methods] of JS) {
    const table = new Set(schema.namespaces[ns]?.methods ?? [])
    for (const m of methods) if (!table.has(m)) missing.push(`${ns}/${m}`)
  }
  assert.deepStrictEqual(missing.sort(), [], `schema 不认识这些 wire 方法：${missing.join(', ')}`)
})

test('Kotlin register 集合与 schema 键双向相等（挂了没列 / 列了没挂 都红）', () => {
  const unlisted = [...REGISTERED].filter((ns) => !SCHEMA_NS.has(ns)).sort()
  const unmounted = [...SCHEMA_NS].filter((ns) => !REGISTERED.has(ns)).sort()
  assert.deepStrictEqual(unlisted, [], `宿主已 register 但 schema 没列：${unlisted.join(', ')}`)
  assert.deepStrictEqual(unmounted, [], `schema 列了但宿主没 register：${unmounted.join(', ')}`)
})

test('handler methods() 申报集合与 schema 键双向相等（缺申报 / 挂错 ns 都红）', () => {
  const undeclared = [...SCHEMA_NS].filter((ns) => !DECLARED.has(ns)).sort()
  const unknown = [...DECLARED].filter((ns) => !SCHEMA_NS.has(ns)).sort()
  assert.deepStrictEqual(undeclared, [], `schema 有、无 handler 申报 methods()：${undeclared.join(', ')}`)
  assert.deepStrictEqual(unknown, [], `handler 申报了 schema 没有的 ns（BY_NS.getValue 会当场抛）：${unknown.join(', ')}`)
})

test('schema 方法都有人发（死分支须登记 aliases 并写清理由）', () => {
  const orphan = []
  for (const [ns, v] of Object.entries(schema.namespaces)) {
    for (const m of v.methods) {
      if (sentTo(ns).has(m)) continue
      const a = schema.aliases[m]
      if (a && a.ns === ns) continue
      orphan.push(`${ns}/${m}`)
    }
  }
  assert.deepStrictEqual(orphan.sort(), [], `schema 认、facade 不发的 wire 方法（要么 facade 漏调，要么登记 aliases）：${orphan.join(', ')}`)
})

test('aliases 不许虚报：真在该 ns 表内、确实无人发、理由写清（过期白名单比没规则更坏）', () => {
  for (const [m, a] of Object.entries(schema.aliases)) {
    assert.ok(a.why && a.why.length > 10, `aliases.${m} 要写清为什么`)
    assert.ok(
      schema.namespaces[a.ns]?.methods.includes(m),
      `aliases.${m} 已过期：${a.ns} 的方法表里没有它（ns 字段 = ${a.ns}）`,
    )
    assert.ok(!sentTo(a.ns).has(m), `aliases.${m} 已过期：facade 现在真发它了，从 schema 删掉`)
  }
})

test('schema facade 列的文件都真在 bridge/js/src/（表里点名的门面必须存在）', () => {
  const missing = []
  for (const [ns, v] of Object.entries(schema.namespaces)) {
    if (!fs.existsSync(path.join(ROOT, 'bridge/js/src', v.facade))) missing.push(`${ns} → ${v.facade}`)
  }
  assert.deepStrictEqual(missing, [], `schema 点名了不存在的 facade：${missing.join(', ')}`)
})

test('解析有牙：四路任一读空，上面的对账必须先红（反证自检）', () => {
  assert.ok(SCHEMA_NS.has('a11y') && schema.namespaces.a11y.methods.includes('findOne'), 'schema 读空')
  assert.ok(JS.get('a11y')?.has('findOne'), 'JS invoke 解析失效')
  assert.ok(REGISTERED.has('npm') && REGISTERED.size >= 19, `register 解析失效（${REGISTERED.size}）`)
  assert.ok(DECLARED.has('a11y') && DECLARED.size >= 19, `handler 申报解析失效（${DECLARED.size}）`)
  assert.ok(Object.keys(schema.aliases).length >= 2, 'aliases 读空')
})

# AutoScript 待办池（backlog）

> **本文件不是契约，也不是台账。** 契约在 [`docs/design/`](design/)（入口 [`framework-design.md`](framework-design.md)），
> 落地状态在 [`design-status.md`](design-status.md)，口径变更在 [`design-decisions.md`](design-decisions.md)。
> 这里只放**尚未排期的待做项** —— 是收件箱，不是承诺。三条纪律：
>
> 1. **排期了就从这里移走**（或就地标 `已排期（谁/何时）`）；**做完了**去 `design-status.md` 记流水；
>    **裁定不做了**去 `design-decisions.md` 记口径 —— 本文件不留"历史结论"。
> 2. **「核实」列是硬要求**：`✅（日期）` = 在当前 tip 上真读过代码/文件确认过；
>    `待核实` = 只是外部审查的原始陈述（静态阅读、不构建不运行），**动手前先自己验一遍**。
> 3. **一次只做一批**：按 §F 的批次走，每批做完跑 CI 同源门（见 `CLAUDE.md` 构建节）。

**来源**：2026-10-01 外部审查（静态阅读约 15 个文件，未构建未运行）：优化 / 文档 / 结构三部分，
已逐条落进下表并标了核实状态。成本记号沿用外审：**S** < 1 天、**M** 1–3 天、**L** > 1 周。

---

## A. 安全与正确性

| # | 事项 | 证据位置 | 核实 | 影响 | 成本 |
|---|---|---|---|---|---|
| **A2b** | **shell 捕获输出无上限**（2026-10-01 新增）：`PipeReader` 把 stdout/stderr 全量读进内存，`cat` 一个大文件就能把宿主撑爆。加 cap 属**契约口径**（§9.6 写的是「双流并发读干」）：截断=静默有损、超限报错=合法大输出被拒，两条都要先拍板取哪个数、哪种语义 —— 故本次 A2 只修超时/收尸，不夹带 | `AndroidShellExecutor.PipeReader` | ✅ 2026-10-01（病灶属实） | S（定了口径就快） |
| **A5** | 桥没有 per-engine 身份：同一 uid 的任何进程可达全部命名空间（`SECURITY.md` 已承认是有意为之），无法按脚本/按 run 归因与审计 | 同 uid 门禁 `BridgeSocketListener.kt:117-122` 是 fail-closed；abstract 名可预测 | ✅ 2026-10-01 | 以后想加 per-run 权限会很贵 | M（协议变更，须与 `main.cpp` + JS bootstrap 同批） |
| **A6** | **vendored npm 版本低于契约脊梁**（2026-10-01 A1 收口时暴露）：素材取自 Node 24.21.0 源码树的 `deps/npm` = **npm 11.19.0**，而 §10.1 脊梁写的是「npm 12.x 系」，§10.12 风险表明写「降级 npm11 会恢复『脚本默认执行』使护栏静默消失」。**护栏没有静默消失，但只剩一层**：`HostNodeExecutor` 对每条命令硬编码 `--ignore-scripts`（§11.1 T1 主控，与版本无关）；npm 12 的 `allowScripts=none`「官方默认」这层不在位，且**非脚本** spawn 路径的第二层兜底（§10.12 末行 child_process 拦截 shim）本就未落。升级路径二选一：换素材来源（另下 registry tarball / 自建裁剪）或等 Node 线携带 12.x；改 `NPM_CLI_VERSION` 即触发 `node-slice` 全链回归（~2h20m）。**能不能靠「等 Node 线携带」升**——2026-10-01 用 `nodejs.org/dist/index.json` 实测否掉了：**868 条官方发布里一条 npm 12.x 都没有**（最新 v26.10.0 / 2026-09-21 携带的是 npm 11.19.1），即走「Node 源码树 `deps/npm`」这条路**原理上拿不到 12.x**，要升必须换素材来源（另下 registry tarball / 自建裁剪）；**npm 12 本身是否存在、其 `allowScripts` 默认语义是否如 §10 所述仍未核实**（本机 `registry.npmjs.org` 不可达，WebFetch 亦被网络策略挡住） | `node-runtime-build/VERSIONS.env`（`NPM_CLI_VERSION=11.19.0`）、`fetch-and-build.sh` §9、`docs/design/10-npm.md` §10.1/§10.12、§11.3 第 8 条 | ✅ 2026-10-01（版本号实读素材 `package.json`；契约落差逐字比对过） | S（若只是换素材源）/ M（若要自建裁剪或改调用链） |

## B. CI / 工程基建

| # | 事项 | 证据位置 | 核实 | 影响 | 成本 |
|---|---|---|---|---|---|
| **B1** | **CI 跳过最危险的路径**：`-PskipNpmE2E` 恒开 → `HostNodeNpmE2ETest` / `NpmCacheSeedDeployerTest` / `P0LoopbackTest` 在 CI **永不执行**；没有 `assembleDebug`；没有 lint / detekt / ktlint；没有覆盖率 | `ci.yml:41`；`app/build.gradle.kts:58` | ✅ 2026-10-01 | APK 构建与真 npm 路径可无声回归 | M（建议：nightly 或 `workflow_dispatch` 跑不带 skip 的那条；单独 assembleDebug job；Android Lint） |
| **B3** | 无设备/仪器化测试道；`libs.versions.toml` 里的 `espresso` / `androidx-test-junit` **零引用**（要么用起来要么删目录项）；16KB 页 / SELinux / targetSdk exec 三条真机检查仍空白 | 全仓无 `androidTest` 目录；`libs.versions.toml:15,31,32` | ✅ 2026-10-01 | native exec/dlopen/a11y 只在一台设备上验过 | L |
| **B4** | 无依赖漏洞扫描 / SBOM / `dependency-review-action`；`bridge/js/package.json` 无 `engines` 字段 | `package.json`；CI 无相关 job | 待核实（`engines` 未逐字读） | 升级债与漏洞看不见 | M |

## C. 文档

| # | 事项 | 证据位置 | 核实 | 成本 |
|---|---|---|---|---|
| **C6** | `design-status.md` 122KB / 737 行、单元格极长，`design-decisions.md` 46KB —— 外审建议拆「当前状态页 + 按日期的日志文件」并加 `docs/README.md` 索引。**注意**：拆分要保住 § 锚点与「只追加」纪律（§号是唯一权威锚） | `docs/design-status.md` | ✅ 2026-10-01 | M |
| **C7** | JS facade 没有**用户向** API 参考（`12-js-api.md` 是设计文档）→ 可从 `bridge/js` 生成 typedoc | `bridge/js/src` | 待核实（未评估 typedoc 覆盖度） | M |

> **C8（文档数字改成生成片段）已裁定：保持现状、不动作** —— 2026-10-01 提案人本人撤回，
> 理由与两条附带观察记在 [`design-decisions.md`](design-decisions.md) 第 15 项。**不再作为待办。**

## D. 结构 / 重构

| # | 事项 | 核实 | 成本 |
|---|---|---|---|
| **D1** | `:app-service:permission-center` main 只有 **91 行**（独立模块偏重）：并回现有模块，或明确"等它长" | ✅ 2026-10-01 | S |
| **D3** | `:platform:system` 4041 行 / ~45 文件**扁平单包**，而 `:platform:capabilities` 已用子包 → 对齐成 `clipboard/` `sensors/` `notification/` `images/` …（改包名要同步 ArchUnit 包模式 + `ModuleGraphTest` 允许集） | ✅ 2026-10-01 | M |
| **D5** | 测试包不镜像 main（`platform/capabilities` 测试是扁平的） | 待核实 | S |
| **D6** | 命名不一致：仓 `NodeScript` / 产品 `AutoScript` / npm 包 `@autoscript/bridge-js` 而描述写 `@autojs/*` / 目录 `.autojs` / 签名前缀 `autojs-lock-v1`。**顺带确认发布用的 npm scope 是自己拥有的** | ✅ 2026-10-01（命名面） | S–M |
| **D7** | 大文件：`InstallCoordinator.kt` **996 行**、`AppShellApplication.kt` 606、`Scheduler.kt` 521、`NativeImageAnalyzer.kt` 567、`AppShellKit.kt` 442、`imgnative.cpp` 88KB 单文件（host 测试已按算子族分，可按同一刀口拆） | ✅ 2026-10-01 | M |
| **D8** | 无第三方许可声明（`LICENSE` 只有 MIT，但随包带了 Node / OpenCV / libc++）→ 从 `node-runtime-build/VERSIONS.env` 生成 `THIRD_PARTY_NOTICES` | 待核实（未逐项核对打包内容） | S |

## E. 需要拍板（产品面，不是工程顺手能做）

| # | 事项 | 现状 | 核实 |
|---|---|---|---|
| **E1** | APK 体积：§15 预算 ≤ 40MB，实测 **≈81MB** —— 重定基还是修（ABI split / strip / 裁 OpenCV 模块）？注意 `useLegacyPackaging=true` 是 exec 的前提，会抬安装体积 | `design-status.md` 接口期表已记「已超支」 | ✅ |
| **E2** | 图像算子预算：A4 933.6ms、A2 计算段 1912.8ms 均 ❌（契约口径不达）—— 修还是改口径？ | `design-status.md` 流水 2026-09-30 实测段 | ✅ |
| **E3** | 设备/仪器化测试道（含 16KB 页镜像）值不值得投入 L 级成本 | B3 | ✅ |
| **E4** | npm 素材还能再剪：Node 源码树 `deps/npm` 原树里 `test/`（1.9MB 表观）+ `tap-snapshots/`（816K）是纯测试件，设备上永远用不到 —— 现在 §9 只剪 `docs/` `man/`。剪掉约省 2.7MB 表观（压缩后更少），**属 §15 体积预算那笔账**（已超支 81MB，见 E1）。改一行剪裁列表即可，但会触发 `node-slice` 全链回归（~2h20m；ccache 命中时短些），故与其它 `node-runtime-build` 改动合并成一次 | `node-runtime-build/scripts/fetch-and-build.sh` §9；2026-10-01 本机对 v24.21.0 tarball 实测的目录体量 | ✅ 2026-10-01（体量为本机实测，非估算） |

---

## F. 建议批次（一次一批，每批跑完整 CI 同源门）

1. ~~**批 1（S）**：A2 / A3 / A1b / A4~~ —— **2026-10-01 已完成**，流水见 [`design-status.md`](design-status.md)（A2b 是修 A2 时露出的新口子，留在这里）。
2. ~~**批 2（S）**：B2（CI 卫生）+ C3（失效引用）+ D4（README 归位）+ D2（删 sandbox 目录）~~ —— **2026-10-01 全部完成**，流水见 [`design-status.md`](design-status.md)。D2 当时因与 design-decisions 2026-09-30「目录留盘」裁定冲突而暂缓，经拍板后执行，口径变更追加在 design-decisions 同批。
3. ~~**批 3（S）**：C1/C5（人类 README + CONTRIBUTING）+ C4（只剩维护者开通上报入口）~~ —— **2026-10-01 完成**，流水见 [`design-status.md`](design-status.md)。C4 当时因「只剩维护者动作」留在池里 —— **该动作 2026-10-01 已由维护者完成**（GitHub 私密上报入口开通，`private-vulnerability-reporting` 复核为 `enabled:true`），仓库侧四处照实写法同批改掉，C4 随之出池。
4. ~~**批 4（M）**：A1/A1c~~ —— **2026-10-01 全部完成**，流水见 [`design-status.md`](design-status.md)：A1c 接缝形状 `secretKey(): SecretKey` + 实现落 `:app` 装配层；A1 四个子缺口按依赖序全补（素材出库 → 随包任务 → `AssetTreeCliSource` → 启动期落位 + 注入 `HostNodeExecutor`），素材来源拍板「Node 源码树 `deps/npm`」（口径追加在 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)）。**收口时露出一个新口子**：素材版本 npm 11.19.0 ≠ §10 脊梁的 npm 12.x —— 登记为 **A6**，要不要升是独立的产品判断。
5. **批 5（M）**：B1（CI 覆盖：nightly + assembleDebug + lint）。
6. **批 6（M）**：D3/D5（platform 子包对齐）、D7（大文件拆分）—— 结构性改动，一次一个 PR。
7. **批 7（L/产品）**：E1/E2/E3 + B3 —— 需要人拍板后再排。

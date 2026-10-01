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
| **A1** | **npm 生产装配没接线**：`executor` 与 `lockKey` 都走缺省 —— 真机上安装返回 `ERR_NOT_IMPLEMENTED`，且 `lock.sig` 不生成、`npm ci` 不验签、快照不导出（§10.5-1 的带外信任锚在生产装配里缺席） | `AppShellKit.kt:403` 调 `NpmShellKit.assembleHandler(filesDir, cacheDir)` 用缺省；`NpmShellKit.kt:49` `executor` 缺省 `HeavyOpExecutor.Unavailable`、`:53` `lockKey` 缺省 `null`；`HostNodeExecutor` 在 `app/src/main` **零引用**（只在 `P0LoopbackTest` / `HostNodeNpmE2ETest` 里注入） | ✅ 2026-10-01 | 头等功能不可用 + 安全文档与代码不符 | M（接线）/ S（只改文档） |
| **A1c** | 若真接 Keystore：`LockSigner.KeyProvider.keyBytes(): ByteArray` 这个形状**与不可导出的 Keystore 密钥冲突**，接缝可能要改成 `sign(bytes)/verify(bytes)`（密钥不出 Keystore） | `LockSigner.kt:35` | 待核实（外审推论，未核实 Keystore 细节） | 影响 A1 的接缝设计，先定形状再动手 | S（调研） |
| **A2b** | **shell 捕获输出无上限**（2026-10-01 新增）：`PipeReader` 把 stdout/stderr 全量读进内存，`cat` 一个大文件就能把宿主撑爆。加 cap 属**契约口径**（§9.6 写的是「双流并发读干」）：截断=静默有损、超限报错=合法大输出被拒，两条都要先拍板取哪个数、哪种语义 —— 故本次 A2 只修超时/收尸，不夹带 | `AndroidShellExecutor.PipeReader` | ✅ 2026-10-01（病灶属实） | S（定了口径就快） |
| **A5** | 桥没有 per-engine 身份：同一 uid 的任何进程可达全部命名空间（`SECURITY.md` 已承认是有意为之），无法按脚本/按 run 归因与审计 | 同 uid 门禁 `BridgeSocketListener.kt:117-122` 是 fail-closed；abstract 名可预测 | ✅ 2026-10-01 | 以后想加 per-run 权限会很贵 | M（协议变更，须与 `main.cpp` + JS bootstrap 同批） |

## B. CI / 工程基建

| # | 事项 | 证据位置 | 核实 | 影响 | 成本 |
|---|---|---|---|---|---|
| **B1** | **CI 跳过最危险的路径**：`-PskipNpmE2E` 恒开 → `HostNodeNpmE2ETest` / `NpmCacheSeedDeployerTest` / `P0LoopbackTest` 在 CI **永不执行**；没有 `assembleDebug`；没有 lint / detekt / ktlint；没有覆盖率 | `ci.yml:41`；`app/build.gradle.kts:58` | ✅ 2026-10-01 | APK 构建与真 npm 路径可无声回归 | M（建议：nightly 或 `workflow_dispatch` 跑不带 skip 的那条；单独 assembleDebug job；Android Lint） |
| **B2** | CI 卫生：无 `permissions:` / `timeout-minutes` / `concurrency`（另两个 workflow 都有）；actions 全按 tag 固定；`gradle-version: "8.9"` 与 wrapper 重复；无 Dependabot | `ci.yml:1-45`；`.github/` 下无 `dependabot.yml` | ✅ 2026-10-01 | token 权限过宽、挂死 run、供应链面、版本漂移 | S |
| **B3** | 无设备/仪器化测试道；`libs.versions.toml` 里的 `espresso` / `androidx-test-junit` **零引用**（要么用起来要么删目录项）；16KB 页 / SELinux / targetSdk exec 三条真机检查仍空白 | 全仓无 `androidTest` 目录；`libs.versions.toml:15,31,32` | ✅ 2026-10-01 | native exec/dlopen/a11y 只在一台设备上验过 | L |
| **B4** | 无依赖漏洞扫描 / SBOM / `dependency-review-action`；`bridge/js/package.json` 无 `engines` 字段 | `package.json`；CI 无相关 job | 待核实（`engines` 未逐字读） | 升级债与漏洞看不见 | M |

## C. 文档

| # | 事项 | 证据位置 | 核实 | 成本 |
|---|---|---|---|---|
| **C1** | `README.md` 只有 **744 B**，没有前置/构建/测试/运行命令 —— 真正的步骤在面向 agent 的 `CLAUDE.md` 里，人类读者进不来 | `README.md` | ✅ 2026-10-01 | S |
| **C2** | `CLAUDE.md` 构建节硬编码 `/root/android-sdk`、`/root/develop/claude/tools/jdk-17.0.17+10`、`/root/ndk/android-ndk-r28c` → 换成 `ANDROID_HOME` / `JAVA_HOME` / `ANDROID_NDK_HOME` 约定，私人路径别进跟踪文件 | `CLAUDE.md` 构建 / NDK 节 | ✅ 2026-10-01 | S |
| **C3** | 失效引用：`ForegroundOps.kt:174`（明写 `tools/jvm-test.sh`）、`AndroidPermissionGatesTest.kt:22`、`PlatformWiringTest.kt:363` 仍拿**已删的**旁路当**现役理由**（docs 里提到它是历史记录，不算漂移）；`CLAUDE.md` 仓库地图有悬空行「`module-stubs` 之外的模块」；`ci.yml:70-71`（assemble 走 Docker）与 `node-slice.yml` 的说法互斥。外审建议：加一个**文档路径/链接检查门**（它就是这样扫出这四条的） | 三处 .kt + `CLAUDE.md:14` + 两个 workflow | ✅ 2026-10-01 | S |
| **C4** | `SECURITY.md` 上报渠道：**仓库侧已改成实话**（2026-10-01，删掉 `TODO@example.invalid` 这类看起来像真地址的占位，直说「本仓当前没有生效的私密上报渠道」）。**只剩维护者动作**：在 GitHub 仓库设置里开通 **Security → Report a vulnerability**（私密漏洞上报），开通后把入口写回该节 | `SECURITY.md` 报告一节 | ✅ 2026-10-01 | 维护者 5 分钟 |
| **C5** | 无 `CONTRIBUTING.md` / `CHANGELOG` / PR、issue 模板；`versionName` 硬编码 `0.1.0` | `app/build.gradle.kts:19` | ✅ 2026-10-01 | S |
| **C6** | `design-status.md` 122KB / 737 行、单元格极长，`design-decisions.md` 46KB —— 外审建议拆「当前状态页 + 按日期的日志文件」并加 `docs/README.md` 索引。**注意**：拆分要保住 § 锚点与「只追加」纪律（§号是唯一权威锚） | `docs/design-status.md` | ✅ 2026-10-01 | M |
| **C7** | JS facade 没有**用户向** API 参考（`12-js-api.md` 是设计文档）→ 可从 `bridge/js` 生成 typedoc | `bridge/js/src` | 待核实（未评估 typedoc 覆盖度） | M |

> **C8（文档数字改成生成片段）已裁定：保持现状、不动作** —— 2026-10-01 提案人本人撤回，
> 理由与两条附带观察记在 [`design-decisions.md`](design-decisions.md) 第 15 项。**不再作为待办。**

## D. 结构 / 重构

| # | 事项 | 核实 | 成本 |
|---|---|---|---|
| **D1** | `:app-service:permission-center` main 只有 **91 行**（独立模块偏重）：并回现有模块，或明确"等它长" | ✅ 2026-10-01 | S |
| **D2** | `engine/sandbox/` 目录仍在盘上（settings 里已注释），删掉即可 —— git 历史留着 | ✅ 2026-10-01 | S |
| **D3** | `:platform:system` 4041 行 / ~45 文件**扁平单包**，而 `:platform:capabilities` 已用子包 → 对齐成 `clipboard/` `sensors/` `notification/` `images/` …（改包名要同步 ArchUnit 包模式 + `ModuleGraphTest` 允许集） | ✅ 2026-10-01 | M |
| **D4** | `README.md` 住在 `src/main/kotlin/...` 里（`platform/system`、`platform/capabilities` 各一份）→ 移到模块根 | ✅ 2026-10-01 | S |
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

---

## F. 建议批次（一次一批，每批跑完整 CI 同源门）

1. ~~**批 1（S）**：A2 / A3 / A1b / A4~~ —— **2026-10-01 已完成**，流水见 [`design-status.md`](design-status.md)（A2b 是修 A2 时露出的新口子，留在这里）。
2. **批 2（S）**：B2（CI 卫生）+ C3（失效引用）+ D2（删 sandbox 目录）+ D4（README 归位）。
3. **批 3（S）**：C1/C5（人类 README + CONTRIBUTING）+ C4（只剩维护者开通上报入口）。
4. **批 4（M，需先拍板）**：A1/A1c —— npm executor 与 lock 签名的**接线决策**（谁提供 `KeyProvider`、接缝形状、密钥生命周期）。
5. **批 5（M）**：B1（CI 覆盖：nightly + assembleDebug + lint）。
6. **批 6（M）**：D3/D5（platform 子包对齐）、D7（大文件拆分）—— 结构性改动，一次一个 PR。
7. **批 7（L/产品）**：E1/E2/E3 + B3 —— 需要人拍板后再排。

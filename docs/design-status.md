# AutoScript 落地状态台账

> **本文件不是契约。** 契约在 [`docs/design/`](design/) 12 卷（入口 [`framework-design.md`](framework-design.md) 索引）。
> 这里只记「哪些已落地、哪些还是接口期、哪次实测推翻了什么」——即原
> 框架设计 §19 那条 9,584 字符的流水账，以及散在各卷里的 `**已落地**` 块。
>
> **锚定规则**：条目一律以 `§X.Y` 锚回契约条款；§号是唯一权威锚，在本文件与
> 设计各卷里同义（§号不随文件位置变化）。
>
> **只追加，不改写历史结论**：新事实加在 `## 流水` 顶部；被推翻的记账不删除，
> 原地标 `~~作废（日期 + 原因）~~`。
>
> **读契约时不要读这里**：正文里的落地注记是「实现注记」，不是规则本身。
> 判一条规则，看 `docs/design/` 对应卷；判它实现没有，看这里。

---

## 接口期（未落地，写了就是撒谎）

| 声明处 | 东西 | 现状 |
|---|---|---|
| §18 | 开放决策点 | **已全部拍板**（第 8/9 项 2026-09-25，第 1–7 项 2026-09-26，见 [`design-decisions.md`](design-decisions.md)；§18 保留作决策台账） |
| §14 P1 | MediaProjection 高清会话 | 未落（授权 UI + FGS；换 producer 即插，语义面不动） |
| §14 P1 | QuickJS `:sandbox` 进程 | 未落；模块壳 **2026-09-30 已从 settings 注释摘除**（不计入模块数），**空壳目录与 settings 注释行 2026-10-01 已一并删除**（复活 = 重建模块目录 + include 行加回 + ModuleGraphTest 登记） |
| §14 P1 | `ui` 原生 XML UI 宿主 / `ui_web` | 未落 |
| §9.7 | OCR（P1）/ 插件（P2） | 未落 |
| §10.5 | 生物特征二次确认 | 未落（`BiometricPrompt` 全仓零引用） |
| §8.5/§8.6 | 引擎侧 `waitCompletion` 超时不发起（无人 await 的 run 没人收尾） | **已覆盖（2026-10-01）**：`TimeoutEnforcer{WATCHDOG}` + 看门狗期限线（`KillCause.TIMEOUT`）—— 残余边界见 §8.6（期限只覆盖声明了期限的 run + 轮转须在跑） |
| §8.5 | 意图日志的 **SQLite 实现**（契约写的是「append-only（SQLite，启动即回放）」） | **未落，且分歧已如实标注**：今天全平台生产（含 Android）跑的都是 `JournalFileStore`（jsonl 追加 + fsync + 流式回放，`AppShellKit` 装的就是它）。卡点是接口住 `:app-service:scheduler`（纯 JVM、零 `import android.`），而依赖铁律 `:platform:*` → `:domain` 不反向 —— SQLite 实现要么把 `IntentStore` 搬到 `:domain`（跨模块契约变更，待裁），要么给该模块加 Android 依赖（丢掉纯 JVM 可测）。另：日志**只追加、从不清理**，体积随 run 数线性增长（每次 run 恒定两条行，已无冗余可压），**保留期策略**（老终态行 / 老 nonce 能否丢）会动到 §8.5 的幂等锚点，同样待裁；`JournalFileStore` 的类注释与 `IntentStore` 接口注释已按此改写 |
| §11.2 T2 / §10.2 | **npm 生产装配接线**（`lockKey` / `executor` / `scriptExecutor`） | **部分落地（2026-10-01）**：`executor` **已接线** —— 素材随包（`assets/npm/**` ← gradle `prepareNpmCliAssets` ← `node-runtime-build` 出口）→ 启动期 `AssetTreeCliSource` 幂等落位 `files/npm/` → 注入 `HostNodeExecutor`（宿主 = `nativeLibraryDir/libnoden.so`）；两条同时成立才注入（部署就位 + 有宿主），否则保持 `Unavailable` 且原因原文进 `AssembledShell.npmCliFailure`。**仍缺**：`lockKey`（`lock.sig` 既不签也不验，全仓无 `KeyProvider` 实现；接缝形状 A1c 已就位）与 `scriptExecutor`（T1 spawn 属 P1）、快照导出。另：素材版本 = **npm 11.19.0 ≠ §10 脊梁的 npm 12.x 系**（落差登记在 [`backlog.md`](backlog.md)）。契约侧已如实标注（§10.1 接线现状 + §10.12 风险表 + §11.2 T2 + §11.3 第 8 条 + `SECURITY.md`） |
| §15 | APK ≤ 40MB | **已超支**（实测 ≈81MB，见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)） |
| — | 真机红测：exec/dlopen + 桥全链 | **已做**（2026-09-29，见下「流水」；非 root、Android 13/arm64、生产布局） |
| — | 真机红测：16KB 页机 / SELinux enforcing / `nativeLibraryDir` 提取路径 / targetSdk36 exec 策略 | 未做（设备 PAGE_SIZE=4096，这几项该机**原理上测不到**） |
| — | 真机红测：性能数字（冷启/帧往返/图像算子） | 部分（冷启 158ms→新件 181–206ms；桥往返 p95=1ms；引擎 RSS≈46MB；`Intl` zh/en 运行期**已验**；图像算子 A2–A4 **已量 2026-09-30**：A3 契约口径 0.88ms ✅、A4 933.6ms ❌、A2 计算段 1912.8ms ❌，见流水） |

---

## 已推翻 / 已改口径

见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径) —— 口径变更属决策侧，
只在那里写一份（本文件不复制，避免两处漂移）。

## 流水（最新在上）

### 2026-10-01 —— 批 5：**B1 CI 覆盖收口**（Android Lint / APK 构建进 PR 门；真 npm E2E 进 nightly + 验尸门；分支 `hellish-shrimp`）

B1 记的是「CI 跳过最危险的路径」：`-PskipNpmE2E` 恒开 → 三条真 npm 路径在 CI **永不执行**，
且 APK 构建与 lint 不在任何 workflow 里。本批把三件事分开做完：

- **① Android Lint 先修干净（`lintDebug` 第一次跑就抓出 15 处 error —— 全是 minSdk 26 上的真崩）**：
  - **`Path.of` 在 Android 上 since=34**（`api-versions.xml` 实查；`Paths.get` 才是 since=26），
    minSdk 26 下 `NewApi` 直接红。全仓 main 源 **6 处 / 5 文件**统一回 `Paths.get`：
    `AppShellApplication`（`nativeLibraryDir`）、`ProcessMonitor`（`/proc/<pid>/{stat,status}`）、
    `ZipNamespaceHandler`、`NpmSnapshot`、`InstallCoordinator`（后两者是 `Uri → Path`）。
    注意这与「设备上跑得动」不矛盾：真机是 **API 33**，`Path.of` 在 34 才出现 —— 设备上
    能不能跑是**另一回事**，lint 抓的是「minSdk 26 的机器上会 `NoSuchMethodError`」。
  - **`NotificationPermission`：`app` 的 manifest 从没声明过 `POST_NOTIFICATIONS`**。
    targetSdk 35 下这不是 lint 洁癖而是**功能缺口**：不声明时 `areNotificationsEnabled()`
    在 API 33+ 恒为 false，`SystemDialogOps` 每次都在 `requireNotificationsEnabled` 抛
    `ERR_PERMISSION_DENIED`，且系统设置里连开关都不给 —— `dialogs` 的 prompt/choose 成了
    **永久不可用**（而不是"用户没开"）。补 `uses-permission` + 就地注释；**声明 ≠ 可用**：
    门禁仍在 SPI（`NotificationContracts`「未开则抛 `ERR_PERMISSION_DENIED`，不是回 false」），
    授予路径仍是能力中心 → `GrantPage.NOTIFICATIONS`。
  - **把门开到全模块后又抓出 13 处**（`:app:lintDebug` 一个模块看不见库模块的 NewApi ——
    AGP 的 `checkDependencies` 缺省 false，而真要崩的恰好在库模块里）。逐条都是
    「声明 minSdk 26 与实际调用面不符」= 老设备上的 `NoSuchMethodError`：
    - **`:app-service:script-repo`（4 处）**：`Stream#toList()` 是 **API 34** ×2 —— 在
      `AtomicDeployer` 的 stage 清理路径上（**素材部署每走一次就到**），改
      `collect(Collectors.toList())`（API 24）；顺手 `.use{}` 收口目录流（`Files.list` 不 close
      = 每次清理漏一个 fd，原写法靠 GC 收尾）。注意 Kotlin 的 `kotlin.streams.toList` 扩展
      **救不了这个**：Java 的成员函数优先于扩展函数。`URLEncoder/URLDecoder#(String,Charset)`
      是 **API 33** ×2（`DeployPath.FieldCodec`，行式 journal 的字段编码），回落
      `(String, "UTF-8")` 重载（API 1，语义逐字相同：UTF-8 百分号编码、空格编成 `+`）。
    - **`:platform:capabilities`（9 处）**：a11y 截图的 API30/34 面（`takeScreenshot` API30、
      `takeScreenshotOfWindow` API34、`TakeScreenshotCallback` API30、`hardwareBuffer` /
      `colorSpace` API30、`wrapHardwareBuffer` API29）+ `ContextWrapper#getMainExecutor`
      **API 28** ×2 + 清单里 `BIND_ACCESSIBILITY_SERVICE` 的 `ProtectedPermissions`。
      **根因是 lint 不追踪 `val sdk = Build.VERSION.SDK_INT` 的局部分支**（原写法
      `if (sdk >= 34) … else …` 被逐行报 error）。改法：公开入口照旧按 SDK_INT 分流，版本面
      收进带 `@TargetApi` 的私有方法（`screenshotApi30Plus`(30) / `requestWindowScreenshot`(34)
      / `requestDisplayScreenshot`(30) / `frameOf`(30) / `ScreenshotCallback`(30)）—— 行为逐字
      不变（同一个 deferred、同一个回调、同一套失败分类），`@TargetApi` 同时是**给人看的守卫
      证据**（它就在 `if (sdk < R) throw` 的下一行）。清单那处用 `tools:ignore` 就地说明
      「本模块就是该 signature 级权限的合法持有者」（与 `:app` 的 `PACKAGE_USAGE_STATS` 同处理）。
  - 修完：**全模块 `lintDebug` 0 error / 11 warning**（`:app` 6 + `:platform:capabilities` 1 +
    `:platform:system` 4），余下见下方「留着没修的」。
- **② PR 门新增 `android-build` job**（`ci.yml`）：`setup SDK` → `npm --prefix bridge/js ci && build`
  （`prepareBridgeDistAssets` 缺件即红，是前置不是优化）→ `./gradlew :app:lintDebug` →
  `./gradlew :app:assembleDebug`，APK 与 lint 报告 `always()` 上传。
  **assemble 与 lint 合一个 job**：两者共用同一套冷启动开销，拆两个等于付两遍；
  步骤名分开红，信号不混。**这个 APK 里没有引擎二进制**（noden/libnode/libopencv/npm 素材
  都不在 git，装配期按「缺位只 warn」放行）—— 这是如实交付不是漏项，补齐路径登记为 backlog **B5**。
- **③ 真 npm E2E 进 nightly**（新 workflow `.github/workflows/e2e-nightly.yml`，
  `schedule` + `workflow_dispatch`）：跑的是**与 ci.yml 逐字同一条 `./gradlew` 命令、只是不带
  `-PskipNpmE2E`** —— `HostNodeNpmE2ETest`（真装 lodash）、`NpmCacheSeedDeployerTest`
  （仅凭种子 cache 的离线 `npm ci`）、`P0LoopbackTest`（`:app` 全链路）。与 ci.yml 分开而不是
  加个 job：nightly 要拉真 registry 网络装包（分钟级、受上游可用性影响），不该把噪声灌进每个 PR。
- **④ 「跑完了但没跑」这个坑才是 B1 的真身**，所以 nightly 有两道额外收口：
  - **`HostNpm` 宿主 npm 发现**（测试源集，`:app-service:npm` 与 `:app` 各一份、算法同源）：
    原先三处测试都写死 `/usr/lib/node_modules/npm`（Debian 系布局）—— 在 GitHub runner 上
    （`setup-node` 装到 `/opt/hostedtoolcache/node/<ver>/x64/`）`assumeTrue` 会**静默跳过**，
    nightly 一片绿而真路径一次没走。改成三来源现查：`npm root -g` → 从 `node -p process.execPath`
    推 `<prefix>/lib/node_modules/npm` → 老静态位兜底；探不到才 null（仍不假扮通过）。
  - **`.github/scripts/check-e2e-ran.sh` 验尸门**（nightly 内 `if: always()` 跑）：逐类查
    Gradle 的 JUnit XML —— **缺 XML（整类没跑）/ `tests=0` / `skipped>0` 都红**。
    「Gradle 绿 ≠ 真跑过」在这三条路径上是**默认状态**（`assumeTrue` 诚实跳过的契约在本机
    成立、在 CI 上退化成假通过），这道门是唯一能证明它们真跑过的证据面。
  - 同批**修掉一处静默跳过**：`NpmCacheSeedDeployerTest` 的金标准用例原本是
    `realTarball() ?: return@runBlocking` + `if (npmCli == null) return@runBlocking` ——
    **无声的通过**（连 skipped 都不算，TestGuard 也看不见）。改成 `assumeTrue`（如实中止），
    并把 `NpmCacheSeedDeployerTest` / `P0LoopbackTest` 登记进 `TestGuard.ENV_GATED`
    （跳过是其契约）；登记 ≠ 可以不跑 —— nightly 的验尸门负责证明。
- **留着没修的（如实记）**：全模块 11 条 warning 都是「知道且认了」类，lint 门默认只对 **error**
  红，它们不挡门（要收成 error 级需先逐条裁定，未排期）：
  `:app` 6 条 —— `InlinedApi`（`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 取值内联，调用点已有
  `sdkInt >= S` 分支）、`DataExtractionRules`（`allowBackup=false` 是有意为之，见 §11.2 T9）、
  `ObsoleteSdkInt` ×2、`MissingApplicationIcon`（无图标资源，非发行向）、`UseTomlInstead`
  （`app/build.gradle.kts` 的 `testRuntimeOnly` 字面量 —— 改它要动 libs.versions.toml，协调者冻结）；
  `:platform:system` 4 条 —— `WakelockTimeout`（**有意不设超时**：`WakeLockLedger` 的取/放就是
  §8.7 的账本，超时会让"谁没还"这件事消失）与 `ObsoleteSdkInt` ×3；
  `:platform:capabilities` 1 条 —— `UnusedAttribute`（`canTakeScreenshot` 是 API30 属性，
  minSdk 26 的机器上忽略它正是设计：低版本走 `ERR_NOT_IMPLEMENTED` 分支）。
- **覆盖率不在本批**：jacoco 要改**根 `build.gradle.kts`**（协调者冻结），登记为 backlog **B6** 待批。
- **生效面如实说**：`ci.yml` 的 `android-build` 是 PR 门，推分支开 PR 即跑（本轮可远端验证）。
  但 **`e2e-nightly.yml` 的 `schedule` / `workflow_dispatch` 只在默认分支上生效**（Actions 的
  workflow 列表读默认分支）—— 合入 `main` 前它不会自己跑，本批只能证明「本机同一条命令 + 验尸脚本
  都通」；真正的 nightly 从合入后的第一个 03:23 起算。
- **门**：CI 同源 13 任务 `./gradlew` 全绿（本机，含不带 `-PskipNpmE2E` 的那条 —— 三条真 npm
  E2E 本机实测真跑过）；`bash .github/scripts/check-e2e-ran.sh` 本机同一条命令可跑；
  全模块 `./gradlew lintDebug` 0 error（`:app` + `:platform:{capabilities,system}` + `:ui` +
  `:engine:node-process` + `:app-service:script-repo` + `:bridge:{native,image}`）；`yaml` 两个 workflow 解析通过。
### 2026-10-01 —— 批 4 后半：**A1 npm 生产装配接线收口**（素材随包 → 启动期落位 → 注入执行体；分支 `hellish-shrimp`）

四个子缺口按依赖序全补，链路今天在生产路径上是通的：

- **①素材出库**（`129e6f3`）：`fetch-and-build.sh` 新增 §9/§10 —— 从 Node 源码树 `deps/npm`
  收敛到 `OUT/npm`（剪裁 `docs/` `man/`、**清掉全部点条目**、`cp -RL` 解引用符号链接），
  版本与 `NPM_CLI_VERSION` 逐字比对（漂移即死），并出逐文件基表 `OUT/npm-manifest.sha256`；
  `node-slice.yml` 的产物审计与 artifact 清单同行带上（`out/npm` 是唯一**要随 APK** 的产物）。
- **②随包**（`e540b4b`）：`:app` 约定插件新增 `prepareNpmCliAssets` —— 素材树 →
  `generated/npmCliAssets/npm/`（`assets.srcDir` 取**父目录**，资产键 = `npm/<rel>`；
  候选序 = `NPM_CLI_ROOT` env → `node-runtime-build/out/npm`），缺锚（`bin/{npm,npx}-cli.js`）
  即**红**（半瘫 CLI 比没交付更糟）、无货只 warn（本机构建的 APK 常态）。
- **③`CliSource` 实现**（`e540b4b`）：`AssetTreeCliSource`（`:app-service:npm`）——
  纯逻辑 + 两个 lambda（`listDir`/`openFile`），生产两行接 `AssetManager`；
  惰性 BFS（不把整棵树的路径列表在开机时全展开）。
- **④启动期落位 + 注入执行体**（`55727cf`）：`AppShellKit` 收 `npmCliSource` / `npmNodeBin`
  两个缝，`npmHandler ?: run { deploy → 有宿主才 `HostNodeExecutor` }`；`AppShellApplication`
  喂 `AssetTreeCliSource("npm", assets::list/open)` + `nativeDir/libnoden.so`。
- **三个刻意的边界**（都由测试钉住，`AppShellNpmCliTest` 4 例 0 skipped）：判定在装配层一处做完；
  `npmCli == null ⟺ 落位没成`（"部署成了、执行体没接上"是两者皆非 null 的中间态，原因原文说清）；
  异常不外抛（素材缺失不该掀翻整个壳，而 `deploy` 对缺失/半瘫是 loud 的，必须接住并记账）；
  调用方自带 `npmHandler` 时本配方**不碰素材**。
- **验的是行为不是字段**：「素材齐 + 有宿主」那例真把 `npm.install` 打到桥上，断言错误码
  **不是** `ERR_NOT_IMPLEMENTED` 且详情指向故意不存在的假宿主路径 —— 证明真走到了 exec，
  而不是停在某条前置（`HostNodeExecutor` 自己的头一道前置是项目 `package.json`，
  测试先摆好它，免得测出的是另一件事）。
- **落差如实记**：素材版本 **npm 11.19.0**（Node 24.21.0 的 `deps/npm`），**低于 §10 脊梁写的
  「npm 12.x 系」**。npm 12 的 `allowScripts=none`「官方默认语义」当前不在位，护栏由
  `HostNodeExecutor` 硬编码的 `--ignore-scripts`（§11.1 T1 主控，与版本无关）单独承担；
  非脚本 spawn 路径的第二层兜底（child_process 拦截 shim）仍未落。口径追加在
  [`design-decisions.md`](design-decisions.md#已推翻--已改口径)，升级路径登记在 `backlog.md`。
  **同日实测否掉了「等 Node 线携带」这条升级路**：`nodejs.org/dist/index.json` 的 **868 条**
  官方发布里**一条 npm 12.x 都没有**（最新 v26.10.0 / 2026-09-21 携带 npm 11.19.1）——
  要 12.x 只能另找素材来源（npm 12 本身是否存在仍未核实：本机 `registry.npmjs.org` 不可达）。
- **门**：CI 同源 13 任务 `./gradlew` 全绿（**1208 tests / 0 skipped**，本机闭环含真 npm 的
  E2E 与 `NpmCliDeployerTest` 那例「部署出的 CLI 真能跑起来」）。
- **素材来源本体也实测过（不等 CI）**：本机下载 `node-v24.21.0.tar.xz`（**sha256 与
  `VERSIONS.env` 的 `NODE_SHA256` 逐字相符**）→ 解出 `deps/npm`：**官方源码树确实带
  `node_modules`**（含 `node_modules/@npmcli/arborist`），版本 **11.19.0** ✓ —— 即 §9 的锚断言
  （`node_modules/@npmcli/arborist/package.json`）成立。按 §9 原样预演一遍：`cp -RL` +
  删 `docs/` `man/` + 清点条目 → **1846 文件 / 11.9MB 表观（18M 占盘）/ 点条目 0 / 三个锚齐**；
  这棵树真跑：`--version` → 11.19.0、`npm ls`（arborist 真载入）、`npm install lodash@4.17.21`
  （与 `HostNodeExecutor` 同参数）→ 装出来真能 `require`。**至此「素材能不能用」不再有推断成分**；
  CI 那次跑（`node-slice`）保留作 artifact 出库与真机件来源。（顺带量到原树 `test/` 1.9M +
  `tap-snapshots/` 816K 是纯测试件，可再剪 —— 记在 `backlog.md` 的 E4 行。）
- **剪裁口径实测过，不是推的**：把 `prepareNpmCliAssets` 用 `NPM_CLI_ROOT=/usr/lib/node_modules/npm`
  跑出来的树（1668 文件 / 9MiB / **点条目 0**，剪掉了 npm 自己树里的 `.npmrc`、
  `node_modules/.bin`、`node_modules/.package-lock.json`）拿去真跑：`npm ls --json`（**装进
  `@npmcli/arborist`，整棵依赖树的真载入**）与 `npm install lodash@4.17.21 --ignore-scripts
  --no-audit --no-fund --prefer-offline`（与 `HostNodeExecutor` 同参数）都通过，装出来的
  `node_modules/lodash` 真能 `require` 出版本号。「AssetManager 看不到点条目会不会把 npm 弄瘸」
  这一条至此是**实测结论**；`NpmCliDeployerTest` 里那两记探针（`--version` + `ls`）是给
  以后改剪裁口径留的回归哨。
- **随包键形状在真 APK 里核过**：`NPM_CLI_ROOT=/usr/lib/node_modules/npm ./gradlew :app:assembleDebug`
  → 解包 APK 实见 `assets/npm/bin/npm-cli.js`、`assets/npm/bin/npx-cli.js`（两个锚），
  `assets/npm/` 下 **1643 个条目、点条目 0** —— 与 `AssetTreeCliSource("npm", assets::list/open)`
  认的键形状逐字对上（不是「有 bridge-dist 先例所以应该行」）。**注意**：这个探针 APK
  已删、素材已清（`prepareNpmCliAssets` 回到「未交付」态）—— 它里面装的是**宿主机** npm
  10.9.8 树，留着会变成一份来源不明的可发布产物。
- **未验**：真机（无设备）—— 真机 AssetManager 对 `assets/npm/**` 的实际可见性
  （尤其 `bin/node-gyp-bin/` 这类深路径与无扩展名文件）、`libnoden.so` 作为 `nodeBin`
  的 exec 权限，都只有本机同形逻辑 + 宿主 npm 树代跑的证据，不是设备实证。

### 2026-10-01 —— 批 4 前半：**A1c 接缝形状**（`secretKey(): SecretKey`；分支 `hellish-shrimp`）

- **拍板（2026-10-01）**：`LockSigner.KeyProvider` 由 `keyBytes(): ByteArray` 改成 **`secretKey(): SecretKey`**；
  Keystore 实现的落点定为 **`:app` 装配层内联**（Composition Root 已依赖 `:app-service:npm`，零契约变更）。
  口径追加在 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)。
- **为什么必须改形状**：Keystore 里的密钥材料**不出库**（`getEncoded()` 拿不到字节），签名只能在库内完成 ——
  原形状**接不上** Keystore，而 Keystore 正是 §11.3 第 3 条写的密钥存放处。给句柄则两边都成立：
  Keystore 的 HMAC 密钥（`KeyProperties.KEY_ALGORITHM_HMAC_SHA256`，API 23+；minSdk 26 ✓）与测试用的
  `SecretKeySpec` 都能直接喂 `Mac.init(SecretKey)`。**语义一字不变**：仍 HMAC-SHA256、落盘仍 `v1 <hex>`、
  原子写不动。注：Keystore 那一半是按 Android 文档语义推的，本机无设备可实测。
- **改动面**（改形状便宜的实证）：`LockSigner.kt:116` 与 `NpmSnapshot.kt:209` 两个用点
  （`mac.init(SecretKeySpec(key.keyBytes(), …))` → `mac.init(key.secretKey())`）+ 三份测试夹具共 6 处
  （`LockSignerTest` / `NpmSnapshotTest` / `InstallCoordinatorTest`）+ 两处 KDoc。`git grep keyBytes` 现已零命中。
- **这不是接线**：生产装配的 `lockKey` 仍是 `null` —— `lock.sig` 依然既不签也不验；`SECURITY.md` 密钥表与
  §11.3 第 3/8 条的「目标形态，非现状」口径只补了一句「接缝形状已就位」，结论不变。
- **A1（真接线）仍未做，且体量比外审估计的大** —— 深挖出四个子缺口（按依赖序）：① 随包 npm CLI 素材根本不存在
  （`assets/npm/**` 无目录、无产出任务）；② `NpmCliDeployer.CliSource` 零实现（KDoc 说的 assets 版没人写）；
  ③ 无人调用 `deploy`（`:app` 零引用）；④ 才轮到注入 `HostNodeExecutor`。证据已写进 `backlog.md` 的 A1 行
  （~~该行~~ **作废（2026-10-01 同日晚些）**：A1 四项已收口，行随出池，见上一条流水）。
  下一步卡在**「npm CLI 素材从哪来」（CI 产 / 入库 / 取本机 npm 目录）**这个构建管线决定上。
- 门：`:app-service:npm:test` 全绿（6 处夹具全跑）。

### 2026-10-01 —— backlog **C2** 收口（机器路径出跟踪文件；分支 `hellish-shrimp`）

> 编号提醒：这里的 C2 是**外审待办池的 C2**，与 2026-09-30 那批 A/B/C 编号（C1/C2/C3 = 实测回填项）无关 —— 同名不同批。

- **病灶**：跟踪文件里写死了这台开发机的路径。`CLAUDE.md` 构建节（`/root/android-sdk`、JDK 路径）与 NDK 节
  （`/root/ndk/android-ndk-r28c`）、契约侧 `docs/design/13-roadmap-budget.md` 一处（`/root/android-sdk`），
  外加**测试代码里的一处**（`NpmCacheSeedDeployerTest` 的 `Path.of("/root/.npm/_cacache/content-v2")`）——
  最后一处外审没点名，是本次按同一口径顺带扫出来的：私人路径进跟踪文件是同一个病灶，不分文档还是代码。
- **改法**：
  - `CLAUDE.md` 构建节与 NDK 节改成**环境变量约定**（`JAVA_HOME` / `ANDROID_HOME` 或 `local.properties` 的
    `sdk.dir` / `ANDROID_NDK_HOME`），并写明「别再往跟踪文件里写 `/root/…`」；
  - **具体值挪到 `CLAUDE.local.md`**（新建，已加进 `.gitignore`）—— 本机 agent 照样能拿到路径，跟踪文件干净；
  - `13-roadmap-budget.md` 那处去掉路径（句子其余不动）；测试那处改成从 `System.getProperty("user.home")`
    拼 `~/.npm/_cacache/content-v2`（换台机器同样命中，语义不变：仍是「取本机 npm 缓存里真实存在的 tarball」）。
- **门**：13 任务 ./gradlew 全绿（含改过的那条 npm 缓存用例）+ 文档链接门。

### 2026-10-01 —— C4 收口（维护者已开通 GitHub 私密上报；分支 `hellish-shrimp`）

- **开关翻面**：`gh api repos/Ventus-Pluviam/NodeScript/private-vulnerability-reporting` 由 `{"enabled":false}`
  变为 **`{"enabled":true}`** —— 维护者在仓库设置里开通了 GitHub 私密漏洞上报（批 3 留下的那件维护者动作）。
- **四处照实写法同批改掉**（渠道从「没有」变成「有」，凡写着「没有渠道」的地方不改就是文档撒谎）：
  - `SECURITY.md` 报告一节 → 入口链接（`…/security/advisories/new`）+「不要公开 issue」+「**没有响应时限承诺**」，
    并把 `.github/ISSUE_TEMPLATE/` 指给非安全类问题；
  - `SECURITY.md` 已知限制第 3 条：「上报流程缺失」→「**没有响应时限承诺**」（渠道有了，缺的是时限）；
  - `docs/design/11-security.md` §11.3 第 7 条：同义改写（契约侧的残余风险登记，原条目「上报流程缺失」同日改写）——
    `SECURITY.md` 末尾那句「以上每一条在 §11.3 都有对应登记」因此仍然成立；
  - `CONTRIBUTING.md` 安全问题一节 + `.github/ISSUE_TEMPLATE/config.yml`（`contact_links` 直指上报入口）
    与 `bug_report.md` 顶注。
- **为什么 `design-decisions.md` 不加行**：这次没有任何口径被拍板或推翻 —— 开关是维护者点的，仓库侧只是把**事实**跟上。
  口径变更才进决策记录；状态变化进本文件的流水。
- 门：13 任务 ./gradlew 全绿 + 文档链接门（本次改动全在文档 / 模板面，无代码）。

### 2026-10-01 —— 待办池**批 3**（C1 / C5；C4 复核；分支 `hellish-shrimp`）

- **C1 人类 README**：`README.md` 从 744 B 扩到 116 行 —— 补齐外审点名的四件事（**前置 / 构建 / 测试 / 运行**）与仓库地图，并把「以完整权限、无进程隔离运行脚本」这条安全前提提到正文最前。三条口径：
  - **构建的硬前置是 facade dist**：`prepareBridgeDistAssets` 有 `require(copied > 0)` 且必须见到 `bootstrap.js`/`index.js`
    ⇒ 不先 `npm --prefix bridge/js ci && run build`，`:app:assembleDebug` 必红。README 把这一步排成第一条命令。
  - **引擎二进制缺失不挡 assemble**（「三件齐 / 半套红 / 全无警」照抄 `autoscript.engine-natives` 的语义）：README 如实写明
    本机能出「**没有引擎的 APK**」，并给出 `LIBNODE` / `LIBOPENCV` / `ANDROID_NDK_HOME` 的候选位**指针**，不复制机器路径。
  - **不写会漂的数字**：正文不出现模块数 / 测试任务数（派生门只盯 `CLAUDE.md` 与 §6，但 README 里写个「15 个模块」同样是漂移面）；
    要引用就指向 `settings.gradle.kts` 与 `ci.yml`。
  - **本轮实测（README 的构建节逐条跑过）**：`:app:assembleDebug` → `BUILD SUCCESSFUL`，产物
    `app/build/outputs/apk/debug/app-debug.apk`（10.4 MB，**无引擎二进制**的那一种）；`prepareEngineNativeLibs` 三条
    `[engine-natives] 未交付 …` 警告与「装配照过」语义一致 —— README 里那句「本机没有它们时装配照过」是**跑出来的**，不是照抄注释。
- **C5 工程基建**：新建 `CONTRIBUTING.md`（文档即契约的四份分工表、冻结文件清单、提交信息格式与 trailer 约定、
  提交前必跑的门与「skipped ≠ 绿」、一次一批、分支/PR 纪律、安全问题不开公开 issue）+ `.github/pull_request_template.md`
  + `.github/ISSUE_TEMPLATE/{bug_report,design_proposal}.md` + `ISSUE_TEMPLATE/config.yml`（`contact_links` 指
  `SECURITY.md` 与设计入口；只用了仓库**已有**的默认 label，`bug`/`enhancement` —— 不存在的 label 是 Dependabot 那次的教训）。
  两条**裁定**追加在 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)：
  - **不设 `CHANGELOG`**：变更流水已在本文「流水」段（只追加、带 § 锚），第二份必漂成第二个事实来源；
  - **`versionName` 保留硬编码占位**：本仓不发行正式版（§18 第 3 项），此刻把版本号接到 `gradle.properties` 只会多一处漂移面；
    改为在 `app/build.gradle.kts` 该行上方写明「这是占位 + 真要发版怎么改（两数同改、`versionCode` 单调递增、同步 §13/§14）」。
- **C4 复核（仍未完成，留在池里）**：`gh api repos/Ventus-Pluviam/NodeScript/private-vulnerability-reporting` 如实回
  `{"enabled":false}` —— GitHub 私密上报入口确未开通。该条自始至终是**维护者动作**（写入需仓库 admin，本机 token 未试）；
  开通后 `SECURITY.md` 报告一节改成两行字（把「没有生效渠道」换成入口链接 + 请勿公开 issue）即可。

### 2026-10-01 —— 待办池**批 2**（B2 / C3 / D4 / D2；分支 `hellish-shrimp`）

- **B2 CI 卫生**：三个 workflow 一起收口 —— `permissions: contents: read`（三个都没有，默认令牌过宽；
  只用 artifact 运行时令牌与缓存，不需要写权限，已核**无**任何 `git push`/release 步骤）、`ci.yml` 补
  `concurrency`（`ci-${{ github.ref }}` + cancel-in-progress，与另两个同款）与逐 job `timeout-minutes`
  （jvm-tests 45 / js-tests 15 / docs-check 5；另两个 workflow 的 job 本就有）。**actions 全部改钉 commit SHA**
  （checkout/setup-java/setup-node/upload-artifact/cache，注释保留跟踪 tag），并新建 `.github/dependabot.yml`
  —— 钉 SHA 的代价是不会自动跟上安全修复，两者是一套。Dependabot **只开 github-actions 一个生态**（版本升级
  AGP/Kotlin/Compose/targetSdk 是显式推迟的决策，开了只会产出注定要关的 PR；依赖面另见 backlog B4），理由写在
  该文件头注释里。删掉 `gradle-version: "8.9"`：版本的事实来源是 `gradle-wrapper.properties` 的 8.9，写死两处必漂。
- **C3 失效引用**（外审「加一个文档路径检查门」的建议一并落地）：
  - **代码里 10 处把 2026-09-30 已删的 `tools/jvm-test*` 旁路当现役理由**的注释改正 —— `ForegroundOps`
    （不碰 androidx 的真理由：minSdk 26 + API 34 类型参数要求调用点显式给）、`AssetsWalk`（纯逻辑要能喂假实现）、
    `HostSummary`、`BridgeSocketListener`/`AndroidBridgeBinder`（**改对了事实**：`:app` 单测的 android.jar 是
    mock 桩、调了就抛，不是「classpath 不带」）、`AndroidPermissionGatesTest`（**改对了事实**：桩字段
    `Build.VERSION.SDK_INT` 读出来是 **0** —— 已用临时探针实测并删除探针；缺省求值 = 静默把断言钉在 SDK 0 分支、
    还不报错，比抛异常更难查）、`PlatformWiringTest`、`NodeProcessEngineRealSpawnTest`、`FixtureAxml`、
    `HomeStateTest`、`RegistrationForm`、`SocketE2EHostTest`（3 处）。docs 里作为**历史记录**提到它的地方
    （design-status / design-decisions 已推翻表 / `06-modules` 删除说明 / `TestGuard` 的「承…纪律」）保留不动。
  - `CLAUDE.md` 仓库地图悬空行「`module-stubs` 之外的模块」删除；`ci.yml` 结尾与 `CLAUDE.md` 里
    「Android assemble 走 `node-runtime-build/Dockerfile`」的**旧口径改正**（该镜像产出 libnode.so/OpenCV.so，
    与 APK 无关；~~assemble 目前不在任何 workflow 里，搬上 CI 记在 backlog B1~~ **作废（2026-10-01 批 5）：已进 `ci.yml` 的 `android-build` job，B1 出池**）。
  - **新增文档链接门**（`docs-check` job + `.github/scripts/check-doc-links.sh`，零依赖、本机同一条命令）：
    扫 git 跟踪的全部 `*.md`，markdown 相对链接按**文件所在目录**解析，目标不存在即红。**只查链接不查反引号
    里的路径**是有意的 —— 实测反引号候选 92 条里真引用是少数（斜杠词表/分支名/别仓路径/相对另一基准的子路径），
    那种门只能靠一张几十条的放行表维持，等于没门；链接的基准与意图都唯一，实测 27 个 md 零噪音。
    **门第一次跑就抓出两条真缺口**：`11-security.md` 的 `[design-decisions.md](design-decisions.md)`
    （应为 `../`）与 `[SECURITY.md](../SECURITY.md)`（应为 `../../`）—— 同目录其余分卷都是 `../` 写法，
    这两处是漏网的。反向验证过：临时插坏链接 + 围栏内示例链接，门报前者 1 条、放过后者。
- **D4 README 归位**：`platform/{system,capabilities}/src/main/kotlin/.../README.md` → 模块根
  （`git mv`，内容不变）。`06-modules.md` 里「见 `platform/system/README.md`」的引用**因此才成立**（原来指的是
  一个不存在的路径）。
- **D2 删 `engine/sandbox/`**（**先暂缓、后经拍板执行**）：首轮发现它与 `design-decisions.md` 2026-09-30 的裁定行
  「空壳从 settings 注释摘除…**目录留盘**，复活 = 注释回 + 登记」冲突（`CLAUDE.md` 模块表同口径），按协作纪律
  停下来问；拍板后删除（目录里只有 1 个 `build.gradle.kts`，`git rm` 后仍可从历史取回）。裁撤口径本身不变
  （§18 第 1 项「不要沙箱」），改的只是「壳要不要留在盘上」这一条附带口径 —— 口径变更追加在
  [`design-decisions.md`](design-decisions.md) 已推翻表（原行不删），`CLAUDE.md` 模块表、`06-modules.md`
  模块行、`13-roadmap-budget.md` P1 段、本文件接口期表同批同步；复活现在多一步「重建模块目录」。
  `settings.gradle.kts` 里 `// include(":engine:sandbox")` 那行属协调者冻结文件，当时未动 —— **同日经协调者拍板后一并删掉**
  （口径追加在 design-decisions 已推翻表下一行；settings 是冻结文件，改它按纪律先问后动）。

### 2026-10-01 —— 待办池**批 1**（A2 / A3 / A1b / A4；分支 `hellish-shrimp`）
- **A2 `AndroidShellExecutor` 超时真修**（`platform/system`）：病灶是「超时抛错后 `coroutineScope` 要等两条
  `readBytes()` 子协程结束才传播异常，而 `destroyForcibly()` 在那个作用域**外**的 `finally` 里」——
  阻塞读**不响应取消**，子进程把管道写端交给孙进程（`su -c …` / `sh -c "… &"`）时杀掉直接子进程也换不来
  EOF，于是「设 1s 超时、实际卡 100s 且没杀」。**修法**：读流改跑在**可弃的守护线程**（新 `PipeReader`，
  不占 `Dispatchers.IO` 共享池），协程只 await 一个可中断的闩；超时路径**先杀再抛**；成功路径等 EOF
  设 250ms 有界宽限（正常形态退出即 EOF，宽限只兜「写端被孙进程继承」这一种例外 —— 铁律 3 不允许无限等待）。
  **真进程实测（临时探针，跑完即删）**：真 `sleep 5` + 300ms 超时，修前 **5005ms**（超时形同虚设）→ 修后
  **309ms**；`sh -c "echo hi; sleep 5 &"`（孙进程持有管道）修前 **5003ms** → 修后 **502ms**（= 两次宽限）。
  单测新增 3 条 `HeldStream`（吐完字节后永不 EOF）用例复现该形态，**对旧实现逐条验证过是红的**。
  **未做且不假装做了**：捕获输出**无上限**（`cat` 大文件可撑爆内存）——加不加 cap、超限报错还是截断是**契约口径**
  （§9.6 写的是「双流并发读干」），已记在 [`backlog.md`](backlog.md) A2b，等拍板，不夹带进本次修复。
- **A3 关备份**：`app/src/main/AndroidManifest.xml` 置 `android:allowBackup="false"` —— `files/.autojs/`
  下的信任锚与审计面（`lock.sig`、审批台账、安装 journal/history、意图日志）不再进 Auto Backup / 换机迁移。
  契约侧同步登记为新威胁行 **§11.2 T9**（口径记录见 [`design-decisions.md`](design-decisions.md) 第 16 项，
  依 §11.4「新增防线走决策台账」）。
- **A1b + C4 安全文档改实话**：`SECURITY.md` 密钥表原写「生产走 Android Keystore」，实际**没有任何
  `KeyProvider` 实现、生产传 `null`** —— 改成现状（不签不验），并把 `executor`/`scriptExecutor` 同批缺省
  一并写明；上报渠道一节删掉 `TODO@example.invalid` 这类**看起来像真地址**的占位，直说「本仓当前没有生效的
  私密上报渠道」。契约侧 §11.2 T2 加「接线现状」、§11.3 第 3/8 条同步改。
- **A4 `LockSigner` 代码与文档对齐**：KDoc 写「前缀不识一律拒」，实现却是 `removePrefix("v1")` —— 裸 hex
  （恰好等于正确 HMAC、只是没有版本标签）照样放行，等于把「换 ECDSA 的兼容开关」废掉。改为严格形态
  `v1 <64 位小写 hex>`（`matchEntire`，其余一律 `ERR_PERMISSION_DENIED`）；`sign` 改**原子落位**
  （写 `lock.sig.tmp` → `force(true)` → `ATOMIC_MOVE` → 目录 fsync；目录 fsync 失败方向是 fail-closed）。
  新增 3 条用例，其中「裸 hex 被拒」**对旧实现验证过是红的**（旧实现放行）。
- **门禁**：CI 同源 13 个测试任务本机全绿（`./gradlew …` 逐字同源；含 `:app:testDebugUnitTest` —— manifest 改动
  过了 `processDebugManifest` 与打包面）。**未上远端 CI**：本批不改 `ci.yml`。

### 2026-10-01 —— 第二次外审：建议落进新建的 **[`docs/backlog.md`](backlog.md)**（待办池）
- **不是台账条目，是收件箱**：本轮外审（优化 / 文档 / 结构三部分约 30 条）**逐条落进 [`docs/backlog.md`](backlog.md)**，
  每条带证据位置、优先级、成本、**核实状态**与建议批次 —— 排期/做完/裁定不做了都要从那里移走（做完来本文件记流水）。
- **落表时核实过的、与本台账直接相关的两条**：① **npm 生产装配未接线** —— `AppShellKit` 调 `NpmShellKit.assembleHandler`
  用缺省，`executor` = `HeavyOpExecutor.Unavailable`、`lockKey` = null，`HostNodeExecutor` 在 `app/src/main` 零引用；
  即真机安装走 `ERR_NOT_IMPLEMENTED`、`lock.sig` 不生成、`ci` 不验签 —— 而 `SECURITY.md` 的密钥表写「生产走 Android Keystore」。
  ② **`AndroidShellExecutor` 超时形同虚设**：超时抛错后 `coroutineScope` 要等不可取消的 `readBytes()` 子协程结束才传播，
  `destroyForcibly()` 在该作用域**外**的 finally 里 —— 子进程把管道传给孙进程时就是「超时 1s、实际卡住且没杀」，测试假流是
  `ByteArrayInputStream` 故测不到。两条都进 backlog 的 A 组，**本文件不预判排期**。
- **本文件与 backlog 的边界**：本文件的「接口期」表 = **契约已写、实现未落**的功能面（锚 §X.Y）；backlog = **没进契约**的工程/文档/结构事项。
  一处内容只写一处，不复制。
- **同日收口一条（只追加）**：backlog 的 **C8**（外审建议把文档里的模块数/测试任务数改成「生成片段」）
  **已裁定：保持现状、不动作** —— 提案人本人 2026-10-01 撤回（该测试只在「散文写了数字且与派生值不等」时红，
  改散文或不写数字都不会红）。按 backlog 第 1 条纪律，条目已从池中移出、口径记在
  [`design-decisions.md`](design-decisions.md) 第 15 项；**本批不产生代码改动**。

### 2026-10-01 —— 外审整改·文档侧收尾 + 四处稳健性修复（5+1+4；`0e42ed3`…`08e89a6`，分支 `hellish-shrimp`）
- **5 文档**：① 八个分卷里 >500 字节的长行折短 67 处（`0e42ed3`；纯折行，去行首 `>` 与全部空白后两侧逐字符比对过）。
  ② 实现注记**外迁**：§9.2 截图管线 + §8.3–8.7 + §9.5/§9.6 共 33KB **逐字**搬进本文件「实现注记（自各分卷外迁，逐字保留）」段，分卷只留契约 + 指针（`a9a4df6`；
  `09-capabilities.md` 43,955→14,870 B、`08-execution.md` 23,559→14,382 B）。**没迁的那 14KB 是判断后不迁**：与所在卷主题绑定（13-roadmap 是进度/预算卷、
  18-19-ledger 是历史卷、§7.7 是一次测量记录），搬走那几卷读不成句 —— 见「各分卷实现注记的搬迁状态」。`12-js-api.md` 的 wiring 表**严格未动**
  （`wiring-table.test.cjs` 读它：表头→首个空行、≥15 行、不许空行/折行）。③ 顺手修 `c6814cc` 自己引入的生成物漂移（`generate.mjs` 的 Kotlin 模板还留着「审查步骤 7」，
  生成物已删 → `gen:wire` 门红，`d977c94`）。
- **1 计数**：「15 个模块」「13 个测试任务」改成**派生值**校验 —— `ModuleGraphTest` 真数 `settings.gradle.kts` 的 include、正则抽 `ci.yml` 的 `./gradlew` 任务名，
  两者互等 + 文档里出现的数字必须等于派生值（`fe27efa`）。关键一步是把这几个文件声明成 `:domain:test` 的 `inputs.files` —— 不声明则改文档 Gradle 判 UP-TO-DATE，
  **门在本机是哑的**（正反两向都验过才算数）。
- **4 稳健性**：① 帧服务（`8381a9a`）：解码失败若 `probeRequestId` 还捞得到 id 就回 ERR（原先静默丢弃，对端要干等满 TTL）；在途帧数 `Semaphore` 有界
  （默认 256，配额在起协程**前**取 = 背压）；单帧上限 64MB→8MB（Kotlin/JS 两侧同批）。② `InstallCoordinator`（`8381a9a`）：`handles`/`projectLocks` 终态逐出；
  `projectLocks` 改 `compute` 原子入表（原 `getOrPut` 并发下会各造一把锁，per-project 串行当场失效）。③ `AppShellApplication` 三处 `GlobalScope`（`87d01bb`）：
  换成自有 `appScope`（SupervisorJob + IO，`onTerminate` cancel）。**代价与补法**：域可取消后多出「域已取消 → 协程体根本不跑」，而广播 `goAsync()` 窗口必须回执
  （漏调挂到超时、双调 = 崩），故新增 `launchGuaranteed`（一次性闩：协程体结尾 + `invokeOnCompletion` 各试一次）；反向验证过，去掉兜底「域已取消」一例即红。
  ④ 意图日志（`08e89a6`）：回放改**流式**（原 `readAllBytes` = 启动按文件大小要内存，改为按块读 + 整行 UTF-8 解码，多字节字符跨块不解坏）；如实标注 §8.5「SQLite」
  与现状的分歧（见上面接口期表）；`node_modules` 尺寸预检加 60s TTL 缓存（原每次安装全量遍历，且跑在拿全局会话锁之前）。
- **证据**：13 模块 CI 同源门**单次 `--rerun-tasks` 全跑**：域 71 / 桥 53 / 运行时 109 / 调度 113 / 脚本库 41 / 权限 11 / 打包 74 / npm 160 / 能力 104 / 系统 177 /
  引擎 18 / UI 50 / App 199 = **1180 例 0 败 0 skip**（`-PskipNpmE2E`；逐模块只取本期任务那个结果目录，不把 `test/` 与 `testDebugUnitTest/` 相加）；
  `bridge/js` `npm test` 193 例（192 过 / 1 skip / 0 败）+ `gen:wire` 后 `git diff --exit-code` 零漂移。
- **仍不在本批范围**（等裁，未动）：版本升级（AGP/Kotlin/Compose/targetSdk36）、`runHeavy` 重复记账 bug、贡献者文档、`autojspro-docs.txt` 归位、拆 `InstallCoordinator`、
  native 宿主测试 + ASan、CI assemble+lint、真安全上报渠道。
### 2026-10-01 —— npm P1 T1 放行门禁的**存盘移植**（`node-slice` 两提交 → `feat/npm-t1-lifecycle`）
- **背景**：`dcc548b`（T1 门禁三段式）+ `7fe9246`（取消路径分流）2026-09-29 写在本地 `node-slice` 分支、**从未推送**；
  次日 `eae38fc`（审查步骤 5）把 npm 从 `:app-service:packager` 拆成 `:app-service:npm`，两条提交的路径与包名全部落到旧位置。
  本次是**移植**（非重写、非新拍口径）：逻辑逐字保留，只做四处适配。
- **适配四处**：① 路径 `app-service/packager/src/main/kotlin/com/autoscript/appservice/packager/npm/…` → `app-service/npm/src/main/kotlin/com/autoscript/appservice/npm/…`（`git mv` 等价）；
  ② 包名 `…appservice.packager.npm` → `…appservice.npm`（`NpmScriptResolver` 的 `package` 行、测试里的 import）；
  ③ `NpmBridgeJson` → `DomainJson`（步骤 3a `f585c97` 已把前者并入后者，`Value.Obj.fields`/`decodeObject`/`Value.S` 三处同名）；
  ④ `NpmShellKit` 的 `probe` 形参名 → 上游改名后的 `freeSpaceProbe`（`defaultFreeSpaceProbe` 缺省值调用），并去掉随 npm 壳一起删掉的 `.mount()`。
- **文档同批搬家**：`dcc548b` 当年写进 `framework-design.md` 的 T1 落地追记（步骤 8 拆分后该文件只剩索引）逐段搬进 `docs/design/10-npm.md` §10.3，
  并把「`:app-service:packager`」改成「`:app-service:npm`；拆模块前属 `:app-service:packager`」；§10.7 的 `runScript`/`exec` 行同批更新
  （`12-js-api.md` 契约面当时已随步骤 8 同步，无需再动）。`13-roadmap-budget.md` §14 的 npm P1 行 + `10-npm.md` §10.11 的 P1 行补门禁面已落、缺 spawn 桥的现状。
- **口径未动**：本次没有新的决策点 —— 放行门禁的判据（宿主重算哈希 → ledger APPROVED → 纯 JS bin 白名单 → 自请入队）与 §18 第 7 项 2026-09-26 拍板逐字一致，故 `design-decisions.md` 只补一条「实现进度」注记，不进决策表。
- **证据**：`:app-service:npm:test` **167 例 0 败 0 skip**（不带 `-PskipNpmE2E` 的本机全量跑，`InstallCoordinatorTest` 58 例含两条取消竞态用例、`NpmShellKitTest` 3 例含「注入的执行体零触发」；
  CI 同源门带 `-PskipNpmE2E` 时排除 `HostNodeNpmE2ETest`/`NpmCacheSeedDeployerTest` 两个真 npm 进程用例）；
  **13 模块 CI 同源门同批绿**（`-PskipNpmE2E`，与本条 npm 分开跑的两次）：域 70 / 桥 51 / 运行时 109 / 调度 111 / 脚本库 41 / 权限 11 / 打包 74 / npm 157 / 能力 104 / 系统 177 / 引擎 18 / UI 50 / App 195 = **1168 例 0 败 0 skip**。
  ⚠ 本仓 `build/test-results/` 下同时留着 `test` 与 `testDebugUnitTest` 两套目录（AGP 变体 + 历史跑），**逐目录相加会把同一次跑数两遍** —— 数例只认**本期 Gradle 任务实际执行的那个目录**。
- **仍未落**：spawn 桥本体（child_process shim / stdio 假管道 / pgrp 杀树 / `detached` 拒绝 / node-shim PIE + PATH 注入）—— 见 §10.3 T1 落地追记的「仍未落」段。

### 2026-10-01 —— §8.5/§8.6 收口：无人 await 的 run 自带期限（`feat/fastpath-16x`）
- **治的病**：§8.6 自己记的诚实边界 —— 桥 `engines.exec` 拿句柄即返回、没人 await 终结，
  而看门狗三路判据全看进程表现（心跳/CPU/RSS）：**心跳正常、CPU 空闲、RSS 很低的长跑脚本三路都判健康，谁也收不住它**。
- **判据**：`PoolAcquireRequest.timeoutEnforcer: TimeoutEnforcer{AWAITER(缺省), WATCHDOG}` —— 看**发起方等不等**，不是"有没有声明超时"
  （调度链路也声明 `timeoutMillis` 但它自己 `awaitCompletion` 超时强杀走 `REQUESTED`；看门狗再收一次就是两套口径）。
  选 `WATCHDOG` 而不给 `scriptTimeoutMillis` → 构造期 `require` 响亮失败。
- **落点**：`RuntimeController.WatchAnchor.deadlineMillis`（= `startedAt + scriptTimeoutMillis`，纯函数）→ `EngineWatchdog.tick()` **期限线判在 pid/心跳两条「量不到」分支之前**
  （期限不依赖度量，排后面会让「宿主不给 pid / 心跳未接线」的 run 连期限都够不着）→ `KillCause.TIMEOUT`（归 `CRASHED`，`Tick.timeoutKilled` 单列）。
- **接口收紧**：`engines.exec` 的 `timeoutMillis` 由可选改**必填**，缺席/`null`/`<= 0` → `ERR_INVALID_PARAM`（缺省值没有诚实来源）；
  JS 侧不预检（同 payload 发出去、宿主回错，与空事件名同一条纪律）。`docs/design/08-execution.md` §8.1/§8.6、`12-js-api.md` §12.2/§12.3.1/§12.3.2 同批改。
- **证据**：`:app-service:runtime:test` 109 例 0 败（`EngineWatchdogTest` 18 含 3 新增：到期收账 / 期限先于 pid+心跳两路 / `AWAITER` 不越权；`EnginesNamespaceHandlerTest` 20 含 3 新增：缺 `timeoutMillis` 拒 / 非正拒 / 期限随锚点交给看门狗）；
  `bridge/js` `node --test` 193 例（`engines.test.cjs` 19 含新增「缺 timeoutMillis → ERR_INVALID_PARAM」）；`:app` 三处 `engines.exec` 字面载荷补 `timeoutMillis`。
  本机全量门 13 模块 1765 例 0 败 0 skip。

### 2026-10-01 —— 三大形态修复：matchTemplate 大模板 / findFeature 恒假 / findColor 全帧（commits `a78515c`/`34fd80e`/`f6cb926`，`feat/fastpath-16x` 叠在 `8d20500` 之上）
- **过程**：本轮治的不是"慢"，是三条**判据口径之外、真机上真会发生**的形态。
  每条都先量病灶机制再动手，三方（生产 / 探针 / host 夹具）互证。
- **① matchTemplate 大模板全帧 1.4s（§7.7）**：病灶与直觉相反 —— 粗筛假 miss 不是
  粗模板"太好"，是它**只对住了一个相位**。真机各相位粗分 `1.0000/0.9007/0.6926/0.8943`，
  thr=0.9 带宽 0.75，0.6926 够不着 → 回精确路径。修法 = 对 nuisance 参数**平均**
  （16 相位 / 0.25×、4 相位 / 0.5× 的粗图按内容原点对齐取平均），最差相位
  0.6926 → **0.8086**；相位探针同步改成量平均模板（门与实跑粗模板同源）。
  同时**移除 headroom**（`floor = min(thr − 带宽, 0.97)`，调参口一并删）—— 带宽之上
  再留余量是重复上保险。实测：300×150 **1467 → 13.33ms**、200×150 **1360 → 10.78ms**、
  370×80 20.23 → 11.72ms，全部 found=1、Δpos=(0,0)、Δconf=0.0000（对照精确 498~767ms）。
- **② findFeature 真机 UI 恒 found=0（§9.2）**：四修，逐条都有真机实测依据 ——
  (a) **场景侧 ORB 配额按短边分档**（>640 抬到 nfeatures=8000/et=10；整屏 1000 个点
  会被高对比区吃光，模板那片一个不剩），代价 29.1 → 46.0ms；(b) **内点铺开度门**
  （任一边跨度 <10% 判未匹配 —— 真机假阳实测报错 208.7px 而模板侧只占 5%×3%）；
  (c) **命中位置 = 模板中心**（原「内点质心」真机偏 **82.4px**，改后 0.0~0.8px）；
  (d) **薄条/小模板零关键点垫边重试**（ORB 的 `runByImageBorder` 在某边 ≤62 时清空
  整层关键点，与内容无关；反射复制 32px 后短边 +64 越过门槛）。重试路径挂**像素复核**
  （TM_CCOEFF_NORMED ≥ 0.6，探针实测真命中恒 ≥0.909 / 纯编 ≤0.501）。
  修后真机：300×150 err 0.00 / 200×150 err 0.00 / 540×600 err 0.00 conf 0.583；
  **370×80 与 48×48 仍 found=0 —— 真·无特征，诚实的未命中，不是缺陷**。
- **③ findColor 全帧大命中面 47ms（§7.7）**：`findNonZero` 先把整张命中点表物化
  （1.99M 点 ≈16MB）再取 `points[0]`。改行主序自己扫、第一个非零即返回：
  **取首点段 22.12ms → 0.003ms**，答案口径不变（`findNonZero` 就是行主序，同一答案
  更早拿到）。**整调用 4.33ms**（全帧 `cv::inRange` ≈4.3ms 是地板；判据 <10ms 的口径是
  300×150 ROI，真机 0.88ms）—— 三档命中面分段表见 §7.7 第六次实测块。
- **④ 探针 `ops.c` 头偏置**：`/tmp/imgbench/ops.c` 三处 `p_ingest` 直接把文件头
  16 字节喂进 `ingest`，命中 x 整体偏 4（时间不受影响）。**仅 /tmp 层面**，已修
  （`buf+16`，`gcc -fsyntax-only` 干净）。
- **门禁**：`host_feature_test` 10 → **51 例**（新增第 7/8/9 段：整屏配额分档、铺开度门、
  薄条垫边重试 —— 第 9 段实测拆掉重试即红）；全套 9 个 host 语义套件 **422 检查**全绿（2026-10-01 复跑逐套计数：26/36/21/124/28/46/45/45/51）。
- **诚实交代三条**：① 整屏档的 640 门槛是按**整屏截图**形态调的，中等裁剪（540×600、
  600×800）找 200×150 在缺省档下仍会漏（真机消费方是整屏截图，暂不加第三档）；
  ② 垫边重试有 2/30 的错答案落在"场景里真有重复区"处（与既有链同类，现状在 300×150
  上也有 1 个）—— 像素复核把纯编那类挡掉，重复区那类留给脚本自己判；③ **特征链的
  可达率不是 100%** —— 1080×2400 真机截图 33 窗口实测命中 14（13 例在真值 3px 内），
  唯一错位置那例经 `matchTemplate` 复查真值处 ccoeff=1.0000 且是全图 argmax；真值处
  ccoeff 1.0 却零特征的窗口恒 found=0（配额抬到 40000 也救不回），要"总能找到"用
  `matchTemplate`（同批 33 窗口命中 33/33；但位置唯一只有 25/33 —— 其余 8 例经复查
  是**像素级重复副本**，逐像素最大差 ≤1，argmax 挑了另一份）。恒 found=0 的窗口
  分三类（逐窗交叉核对，19 例 = 6+5+8）：**A 真值处场景侧零关键点 6 例**（配额
  8000→40000 后仍有 5/4 例为 0，而真值处 ccoeff 实测最小 0.99993 —— 图上确实有）；
  **B 模板侧缺省 ORB 零点 5 例**（370×80、1080×60、三个 200×150；垫边后 6~57 点仍
  found=0）；**C 两侧都有点仍配不上 8 例**（缺省 7~124 点、垫边后 61~486 点）。
  A 是特征匹配的地板，B/C 是 ORB 在低纹理 UI 上的可重复性极限。

### 2026-10-01 —— FastPath 12a + 场景端粗筛缓存（commits `a22fbfd`/`8d20500`，PR #13）
- **过程**：先做「25.37ms 花在哪一截」的分段实测（region 扫掠线性拟合 + 组件直测 +
  `refine_probe` HIT/miss 同面积对消），账拆成 场景 cvtColor ≈5.9ms（25%）+ resize
  ≈1.6ms（7%）+ 粗筛 ≈11.4ms（48%）+ 提名/精配 ≈4.8ms（20%，pad=14）→ 才决定动手。
  两处改动：**12a FastPath**（提名唯一 + 主峰 ≥thr+0.05 → pad 14→6）与**场景端
  `g_scene_prep` 缓存**（全帧 cvtColor+resize 按 (帧, sc) memoize，与 needle 缓存
  同锁/同锁序/release 同钩子；region 不缓存）。host 差分门 391 → **396 检查绿**
  （case6d/6d2 唯一高置信与重复副本破唯一、case9 缓存四检查）→ NDK 双过 →
  image-native CI 出 so（sha256 `d461aaad…d3a5`）→ 真机两跑。
- **数字（五次 A = 12a+场景缓存 vs 四次 A = 相位门基线，×100 中位）**：A4 全帧
  25.37 → **19.82ms**（−5.55，−22%）✅；A4-region 370×80 10.64 → **8.57ms** ✅；
  A2 计算段 95.43 → **88.86ms**（−6.57）✅；48×48 867.5ms†（形态同判 ❌，两处改动
  都不进该路径）；FORCE_EXACT 对拍 1863.1 / 910.5ms（四次 B 1869.8 / 887.6，
  同量级 = 无漂移 ✓）。
- **同会话三方 A/B/C（决定性归因）**：同一 bench 二进制、交替顺序
  main→12a→两者→两者→12a→main（A4 每档两跑），so = 三个 commit 各自的 CI 产物
  （sha 前缀 `2b0578af` / `c4013e90` / `d461aaad`）：
  | 项 | main | `a22fbfd`（仅 12a） | `8d20500`（12a+场景缓存） |
  |---|---|---|---|
  | A4 370×80 全帧 | 25.76ms（25.74/25.78） | 23.96（23.73/24.18） | **20.23**（20.17/20.30） |
  | A4-region 370×80 | 10.78ms | **8.68ms** | 9.21ms‡ |
  | A2 decode+match×2 | 96.74ms | 92.73ms | **89.26ms** |
  逐项归因：**12a 单独** = A4 −1.80 / region −2.10 / A2 −4.01；**场景缓存再叠**
  = A4 −3.73 / A2 −3.47。合计 A4 −5.53 / A2 −7.48，与跨会话「五次 A」差
  （−5.55/−6.57）方向量级一致。场景缓存实际账面 **≈3.7ms/次重复 match**
  （= 生产 cvtColor+resize 联合实测；估算表 5.9 那一行是上界，量的 `imgnative_gray`
  含 4 通道 scatter，生产粗筛只出 1 通道）。‡region 不走场景缓存，两 so 在该路径
  代码逐字相同，9.21 vs 8.68 为 run 噪声（±0.5ms）。
- **①（1/16× 粗筛）数据否掉**：host 端到端探针 1/16 粗筛 0.41ms（vs 0.25× 6.43ms）
  但提名爆量 `ncand=8`（唯一性被 23×5 的糊模板摧毁，K 名额被假峰占满）→ 端到端
  48.1 / 29.6ms，**全部劣于现行 0.25× 的 10.6ms**；且 370×80 短边 80 < 192 根本
  不进 1/16 守卫。**不做**，记录在案。
- §7.7 第五次实测块 + design-decisions 第 13 项追加同批（只追加）。

### 2026-09-30 —— 相位探针门 + 自适应 K（评审二轮原型移植，commit `0ec6ef4`，PR #12）
- **过程**：Python 原型（相位探针 + 自适应 K + 覆盖率脚本）评审通过后按老流程 ——
  真机模板 host 诊断（门值实测）→ `imgnative.cpp` 五处改造 → host **377 检查全绿**
  （新增 case6 高阈值 0.99 差分锁）→ NDK 双过 → 临时分支 `verify/phase-gate` +
  image-native CI 出 so（sha256 `d1962315…`，strings 含 HEADROOM 调参口）→ 真机
  A/B 两跑。PR #10 同日已合（`da63759`），本改动随后续 docs 提交进 PR #12。
- **数字（A=相位门默认 / B=FORCE_EXACT，对照三次实测）**：A4 370×80 全帧 24.47 →
  **25.37ms**（B 887.60 ≈ 三次 B 888.97，**归因干净**）✅；A2 94.61 → **95.43ms** ✅；
  region 两行 16.53 / 10.64 稳 ✅；A3 ROI 0.905 不变 ✅；A4-small 855.6ms 恒精确
  （std 6.29<12 + phase 0.794 双拦，同判 ❌，出路 region 不变）；探针 conf=1.0000。
- **门值与旋钮**：370×80 `phase=0.846 ≥ floor 0.800` 过门保形态；48×48 `phase=0.794`
  拦。`floor = min(thr − 粗带宽 + headroom, 0.97)` 绑带宽，闭静态 0.8 门高阈值洞；
  新 knob `AUTOSCRIPT_MATCH_HEADROOM`（0.05）。候选带宽/地板单源 `coarse_margin_of`。
- §7.7 第四次实测块 + decisions 第 13 项追加同批（只追加）。


### 2026-09-30 —— 评审 patch 验证轮 → 采纳（commit `852fb45`；粗筛下限 48px + kMinCoarseSide 12 + needle 缓存）
- **过程**：外部 `vision-optimized.patch` 按「先只验证不落地」裁定走完整验证链 ——
  套用 → host 372 检查绿 → NDK 双过 → 临时分支 `verify/vision-opt` + image-native CI
  出 so（sha256 `24a3ebb8…e05d`）→ 真机 A/B 两跑 → 诊断 → 用户裁定**采纳**（中途
  一次「全不落」与判据转绿冲突，澄清后改裁保留）。临时分支与 PR #11 已删/自动关。
- **数字（A=patch 默认 / B=FORCE_EXACT，对照 `7d92faf`）**：A4 370×80 全帧
  62.98 → **24.47ms**（B 888.97 = 旧行为，归因=0.5×→0.25× 粗筛）**判据行转绿
  （形态注明，判据原文不改）**；A2 171.75 → **94.61ms**；A3 ROI 0.854 不变；
  region 行 16.88/9.77 稳。
- **48×48 形态如实挂 ❌**：真机模板双门拦截（std 6.29<12、频率门 0.69<0.8）→ 恒
  精确路径 948ms（≈基线噪声）；诊断证明 0.69 < 候选带宽 0.75 → 硬放宽必假漏，
  **门是保精度的**。出路 = region 16.9ms 推荐姿势（不变）。
- **精度面**：坐标/置信度仍全在原 4 通道重算（粗筛只提名）；差分 372 例 + 真机
  探针 conf=1.0000 同位 + FORCE_EXACT 回退阀四层压假漏检。needle 缓存收益
  <1ms 真机不可测，随 patch 采纳（锁序已核）。§7.7 三次实测块 + decisions 第 13
  项同批追加。

### 2026-09-30 —— `images` 匹配提速：金字塔粗筛 + `region` + 计算出锁（评审拍板案，两提交）
- **背景**：A2–A4 实测 ❌（A4 933.6ms / A2 1912.8ms，见下条）之后的出路裁决 ——
  评审否掉「帧内缓存频谱」（同帧**同**模板才免费、频谱不可跨调用复用）与「灰度直配」
  （阈值/置信度语义会漂），拍板四件套全文见 `design-decisions.md` **第 13 项**；
  §7.7 出路块与 matchTemplate 行同批追加（原选项原文不动，只追加）。
- **commit 1（ABI 不动）**：金字塔粗筛 —— 灰度 0.25×/0.5× 只提名 ≤K 候选（thr−margin
  带宽 + NMS），坐标/置信度回**原 4 通道**小窗重算，语义一字不动；计算出锁（`g_mu`
  只盖帧表查找，帧入表后不可变、浅拷贝出锁安全）；**频率门**（模板「缩小→放大」自检
  <0.8 → 精确路径 —— 差分门首跑抓到的 i.i.d. 噪声假 miss 的修法）；
  `AUTOSCRIPT_MATCH_FORCE_EXACT=1` 回退阀 + `MIN_TEMPL_SIDE`/`MARGIN`/`MAX_CANDIDATES`
  环境变量调参口（imgbench 真机扫参免重编，缺省与原 constexpr 逐字相同）。
- **commit 2（本条所在）**：`region` 五层穿透 —— `imgnative.cpp` → `images_jni.cc` →
  `NativeImageAnalyzer` → `:domain` `ImageAnalyzer` SPI → `ImagesNamespaceHandler` →
  `images.ts`（判据复用 findColor 的 `resolve_region`：越界 → `ERR_INVALID_PARAM`、
  **region 比模板小 → `ERR_IO`**、命中坐标恒全帧口径）+ §7.7 region 契约句 +
  imgbench 决定性行（48×48 @ region 300×150，小模板出路就是缩窗）。
- **验证**：host 语义门 9 文件 **361 检查 0 失败**（match 文件 82 检查：差分双跑 ——
  强制精确 vs 金字塔同位置 + 置信度 ≤2e-3 或同未命中；高频反例锁；12 枚等价图标格
  >K=8 压 NMS；region 四态含粗筛×ROI×全帧坐标）；`npm test` 192 例 0 失败（1 例
  env 门禁 skip = CI 同跳）；`gen:wire` diff 空；gradle 13 任务同源行 BUILD SUCCESSFUL
  （含 archUnit / ModuleGraphTest / TestGuard）；NDK arm64 `-fsyntax-only` 双文件过。
- ~~**未完（下一次真机）**：A4 / A4-region / A2 复测 —— image-native CI 产物推设备 +
  imgbench 新行 + `FORCE_EXACT` A/B + 三常数扫参。**判据 <40ms 不改**（拍板项），
  region 数字回来再谈口径。~~ **同日已做**，见下条。

### 2026-09-30 —— A2–A4 优化后真机复测（同日第二次；run1 金字塔 vs run2 强制精确 A/B）
- **产物链**：image-native run `36718494804` 两 job 全绿（host 语义门 CI 侧复跑 ✓ +
  `libopencv.so` arm64 出包，sha256 校验 `0f23441…34246`，strings 可见四个调参口）
  → 推云手机（Android 13/arm64/4KB，同机同 `scr.raw`）→ imgbench 新行 ×100 中位。
- **数字（首测 → run1 金字塔 / run2 强制精确）**：
  - A2 计算段 1912.8ms → **171.75ms** / 1851.3ms —— **判据转绿**（match×2 ≈125ms <700ms）。
  - A4 370×80 全帧 933.6 → **62.98ms** / 892.3ms（14.2×，回退阀 ≈ 旧行为）—— 仍 ❌ 差 1.6×。
  - A4 48×48 全帧 862.4 → 855.5 / 866.2ms —— ❌ 预期（短边<80 恒精确路径）。
  - **A4-region 48×48 @300×150 = 16.57ms ✅；370×80 @540×190 = 11.77ms ✅**（新行）。
  - A3 ROI 0.870 / 0.906ms ✅ 不变。
- **判据 <40ms 仍不改**（只追加）：全帧 63ms ❌ vs region 11.8ms ✅ 的口径之争现在
  有数字了 —— **改判据与否待拍板**（§7.7 出路 ③ 保持原状）。表全文见 §7.7 复测块。

### 2026-09-30 —— A2–A4 真机性能实测（恢复自挂起；云手机 Android 13/API 33/arm64/4KB，OpenCV 4.14）

恢复点「下载 artifact → 推设备 → 跑 imgbench」按挂起注记原样执行：image-native **228ab11**
artifact（`SHASUMS256` 校验过）替换设备上**kleidicv 断符号旧件**（`cannot locate symbol
"kleidicv_saturating_add_u8"`——即挂起注记点名的 HAL 悬空件，旧件 7.3MB / 新件 8.9MB）→ dlopen 通。
driver = `imgbench.c`（本机 NDK r28c 交叉编译推设备，`dlopen` 计算核直连，**无桥/JNI 开销**，
已是最好情况），输入 1080×2400 真机截图 `scr.raw/png`，每项 ×100 取中位。

**先修两处 driver 缺陷，测出的数才有效**：① A2 段复用的模板 `tpl` 在 A4 段末已被 `release` ——
use-after-free，首轮 A2 全 `FAIL match2 rc=1`；② A3 probe 传 `target={0,0,0,0}/tol=0` 找「透明黑」
恒 miss —— 首轮 100 次测的全是 **miss 路径**（6.45ms 是无效场景数字），改取帧内真实像素色后
才是命中路径（probe 回 `(0,0)` rgba=(245,245,245,255)）。

| # | 项 | 实测 median（×100） | 判据 | 判定 |
|---|---|---|---|---|
| — | `ingest`（1080×2400 RGBA→帧表） | 6.07ms | （无判据，参考值） | — |
| A3 | `findColor` ROI 300×150（**契约口径「单人独立子图」**） | **0.878ms**（mean 0.889 / max 1.064） | < 10ms | **✅ 达标**（余量 11×） |
| A3 | `findColor` 全帧（口径外参考） | 61.42ms | —（判据非全帧） | 口径外；host 30.2ms |
| A4 | `matchTemplate` 370×80 UI 切片 | **933.6ms** | < 40ms | **❌ 超 23×** |
| A4 | `matchTemplate` 48×48 小模板（尺寸对照） | **862.4ms** | < 40ms | **❌ 超 21×**；与 370×80 同量级 → **耗时与模板尺寸弱相关** |
| A2 | 计算段 `decode(46.2ms) + match×2(1827ms)` | **1912.8ms** | 一次截图两次匹配 < 700ms；端到端 < 1s | **❌ 双判据均超**；截屏段未测（无生产 APK/a11y），计算段既超、端到端必超 |

**host 对照（判因，x86_64 + OpenCV 4.10，同 `scr.raw`，本机实测）**：match 370×80 = **565.8ms**、
48×48 = **614.1ms**、findColor 全帧 = 30.2ms、ROI = 0.194ms —— match 的 ~0.6–0.9s 是**平台无关
量级**（两平台同序、两尺寸同序）：`cv::matchTemplate` 每次调用重算图像频谱，固定开销主导。
**结论：A4/A2 未达标不是设备慢、也不是模板选大了，是「裸 `cv::matchTemplate` 全图搜索」的
实现量级本身** —— §7.7 的 <40ms 判据自记入起从未真机量过，本次首测即超（判据**不改**，改口径
是拍板动作；三条出路选项已记 §7.7 实测记账块）。A3 按契约口径达标销账。

过程记账：远程 adb（frp 隧道）断流三轮（含一次半死 `echo` 不回），最终改**设备端 `setsid nohup`
落 `out.txt` + 轮询自动重连拉取**，结果零丢失。C2 回填已执行（数字进 §7.7）；B 组四项不变（等
16KB 页机 / enforcing 设备）；A1/A5/A6/C1 已测结论不受本条影响。


### 2026-09-30 —— 外部审查整改·步骤 8：framework-design.md 拆 12 卷 + 机器读者/文档漂移修缮

（四提交推进；§号与标题文本逐字保留，只移动文件）

- **8-1（04b230a）纯拆分**：1,380 行 / 252,896 B 单体按 § 号切 `docs/design/` 12 卷
  （`00-overview(§0–2) / 03-technology(§3) / 04-architecture(§4–5) / 06-modules(§6) / 07-bridge(§7) /
  08-execution(§8) / 09-capabilities(§9) / 10-npm(§10) / 11-security(§11) / 12-js-api(§12) /
  13-roadmap-budget(§13–17) / 18-19-ledger(§18–19+文档边界)`），`framework-design.md` 换薄索引
  （分卷导航 + 文档边界表）。核验：12 卷按文件名序拼接 == 拆分前原文**逐字节**（156,995 字符）。
- **8-2（b5af6e6）机器读者改造**：`wiring-table.test.cjs` 改读 `docs/design/12-js-api.md`（§12.2 表）；
  `docs-surface`/`err-catalog` 改读 `docs/design/*.md` 按名排序全拼接；全仓 **97 个**代码/构建文件的
  `docs/framework-design.md §X.Y` KDoc/注释机械 sed 成 `docs §X.Y`（§ 号是唯一权威锚）。
- **8-3（12cee90）§18 漂移修复 + 死链**：design-decisions「已拍板」追加 2026-09-26 preamble +
  原 §18 第 **1–7 项**（3,947 字符原文整段照抄，编号不变）——接口期表「§18 已全部拍板」的断言自此
  真实成立；「仍待拍板」段标题加删除线 + 订正块（原表按只追加纪律保留）；18 卷 §18 导语改
  「决策台账——九项全部已拍板」；**拆分死链 9 处**（卷内 `](design-*.md)` 相对链接补 `../`，
  否则解析进 `docs/design/` 内不存在的路径）+ design-status/design-decisions 头部互链改指分卷。
- **8-4（af64b77）CLAUDE.md/README 终稿**：首段与仓库地图改「12 卷 + 薄索引」双行、decisions 行改
  「原 §18 全部九项」、构建段 12→13 任务、协作纪律 1 改按 § 号读分卷、尾注 `Claude Opus 5` →
  `Claude Code`（对齐现行）；根 README 与 platform 两份 README 设计章节行改指分卷；4 个 C++ 源头注
  补漏（8-2 白名单漏 `*.cpp/*.cc`）。**豁免记明**：`gradle/libs.versions.toml` L2 一处引用不改 ——
  冻结文件（计划承诺零改动），链接有效（指向索引入口）。
- **验证**：CI 同源 13 任务 BUILD SUCCESSFUL（17 executed / 153 up-to-date，编译与受影响链全过）；
  `npm test` 190 tests（189 pass / 1 env-gated skip / 0 fail —— 四门 docs-surface/err-catalog/
  wiring-table/wire-schema 在新读口上全绿）；`gen:wire` 重生成两产物零 diff。


### 2026-09-30 —— 外部审查整改·步骤 7：单一 schema 生成契约 + dist 出库

（wire 面单源 + 双发射 + 三门换基 + CI/dist/过时配置；五提交推进）

- **7a（3424cbe）schema 单源**：`bridge/schema/wire.schema.json` —— 19 ns × 97 方法 + 2 aliases（带理由）+ 1 dynamicSinks + 逐 ns facade；底稿 = 步骤 4 后各 handler `when` 标签全量提取 + register 站点核对（19↔19），JS 侧预对账零偏差。`generate.mjs`（零依赖，`--check` 模式）双发射 `wire-types.ts`（`WIRE`/`WireMethodOf<N>`/aliases/sinks）与 `:domain` `WireMethods.kt`（BY_NS/ALIASES），生成物入库。
  package.json：`gen:wire`/`gen:wire:check` + 过时配置三件（删空 `workspaces`、`UNLICENSED`→`MIT`、`@types/node` ^22→^24）。
- **7b（777ab89）handler 显式申报**：`NamespaceHandler`（fun interface）加缺省 `methods() = emptySet()`（未申报 = 对账层按未接入处理），`RpcNamespaceHandler` 撤重复声明；19 个 handler（18 Rpc + `ConsoleCollector`）逐个 `override fun methods() = WireMethods.BY_NS.getValue("<ns>")` —— 申报只接线不抄表。
- **7c（f642565）新门并行**：`wire-schema.test.cjs` 9 例上线（生成物 --check、facade→schema、register↔schema 双向、申报↔schema 双向、死分支/aliases 真伪、facade 在场、解析自检），与旧门同跑 195 tests 全绿。
- **7d（d2e6073）旧门退役**：删 `wire-reconcile.test.cjs`（163 行，正则啃 `when` 块）；`wiring-table.test.cjs` 换表↔schema 基（facade 列与 schema `facade` 字段一致、行覆盖↔schema 键双向、状态列「已挂」∈ schema；register 解析器移交 wire-schema）。`jni-names`/`docs-surface` 保留。§12.2 门禁说明段与 event-wire 注释同批改。
- **7e（2ba5aea）dist 出库 + CI**：`.gitignore` + `git rm -r --cached`（38 文件）；ci.yml 三处 —— js-tests 加 `npm run gen:wire && git diff --exit-code`（git diff 在仓根跑）、两 job node 22→24、jvm-tests 加 setup-node + `npm --prefix bridge/js ci && run build` 前置；四处「git 跟踪」文案改「构建产物」（build-logic require、BridgeDistPackagingEntryTest、NodeProcessSpawnE2ETest 注释、§12.4 随包段）；
  CLAUDE.md 模块行与 CI 段补前置句。
- **验证**：npm test 190 tests 全绿（189 pass / 1 env-gated skip）；`gen:wire` 重生成后两份产物零 diff；13 任务 BUILD SUCCESSFUL（收尾同批）。


### 2026-09-30 —— 外部审查整改·步骤 6：platform 按能力重组 + Wm/Power 移出 `:app`（零新模块，模块表 15 不动）

（§6 模块表两行 + §12.2 接线表与两段散文 + §8.7 位置声明；ModuleGraphTest allowed **零改** 是本方案选型收益 —— 「每能力一模块」方案因要动 settings/allowed/§6/CI/计数五处且与 §6「薄模块已合并」口径冲突，明确否，见 design-decisions）。四个子步各自一提交：

- **6b（572e453）handler 归位**：系统面 handler 七件 + `SystemNamespaces.kt` 五内迁 `:platform:system`；capabilities 收敛为 a11y/screen/dialogs 三面（子包 `capabilities/{a11y,screen,dialogs,device}/`，`android..` 按包豁免线 `..capabilities.device` 不破）；`CapabilityNamespaces` 只剩三工厂；`PlatformWiring` 系统面前缀换 `SystemNamespaces.`。
  测试安置：7 个 handler 测试随迁；`SystemNamespacesTest` 拆出 `DialogsNamespaceHandlerTest`（6 例）；**images×screen 跨命名空间帧表互认测试迁 `:app` `PlatformWiringTest`**（跨 system×capabilities 只有共同依赖方 `:app` 能住，复用其 `FakeImageAnalyzer`）。
- **6a（fd5c190）契约搬移判据（单独提交）**：全仓 grep `import com.autoscript.domain.system|storage.` + 生产读面 src/main 扫描 —— **随迁**五契约文件（Clipboard/Notification/Sensor/Zip/SystemSettings）+ `SystemContracts.kt` 四段（shell/device/app/floating → 新 `SystemHostContracts.kt`），判据「仅 handler+impl 消费，`:app`/`:ui`/app-service 生产读面零引用」写进提交信息；
  **留 `:domain`** 反例两条：`DialogHost` 六型（`PlatformWiring.kt:6` 生产 import —— 装配层参数面在读）、`DataStore` 系（`:app` 测试在读共享替身、非能力专用）。测试同批拆（system 新 `SystemHostContractsTest` 4 例 / domain `SystemContractsTest` 留对话框 3 例）；system 模块 81 行同包 import 删除、`PlatformWiringTest` 16 个 import 重指、契约 KDoc「为什么住 :domain」改写为迁移判据。
- **6c（aebc2a1）`WorkManagerNamespaceHandler` → `:app-service:scheduler`**（与 `EnginesNamespaceHandler` 住 runtime 同形态）；同批 scheduler `ArchitectureTest` 撤黑名单 `domain.bridge..` 并量化注释（仅 BridgeRequest/Response/RpcNamespaceHandler 转接面三型）；装配层测试「装配壳恒挂 workManager」回迁 `:app` 新 `WorkManagerMountTest`（scheduler 见不到 `AppShell`/引擎假件）。
- **6d（c53dd5c）`PowerManagerNamespaceHandler` + `WakeLock.kt` → `:platform:system`**；`:domain` 新增 `fun interface KeepAliveRenew`（`renew(): List<String>`，`ForegroundKeeper` 签名吻合直接 `override`）收窄 handler 的 keepalive 缝 —
  — 平台模块不反向见 `:app`；根包三处构造点走 shell 工厂缝（`PlatformWiring.wakeLockLedger/powerManagerHandler` + `ForegroundKeeper.lockHeld()` 读口），**根包字节码零 platform 方法属主**（ArchitectureTest 是依赖级门禁）；
  并删根包 6 条死 platform import（与「不 import 任何 com.autoscript.platform..」注释自洽）。PM 测试随迁改用 `KeepAliveRenew` 假件，框架席位字面量 `framework:keepalive` 与 `:app` `ForegroundKeeperTest` 跨模块对钉。
- **文档收尾（同批）**：framework-design §6 capabilities/system 两行整段重写 + §12.2 表 11 处 handler 列改模块/工厂 + 两段散文口径反转 + §8.7/§9/§19 六处位置声明（含 `WakeLockLedger::isHeld` → `keeper::lockHeld`）；design-decisions「已推翻」表加 §12.2 反转行 + **被反转原文照抄引用块**；CLAUDE.md 模块表三行（scheduler/capabilities/system）。
  顺手修 `pull-wire.test.cjs` 两条硬编码路径（6b 子包重组与 sensors 迁移后失效 —— 6b 当时未跑 npm test 的欠账，本步收尾抓回）。
- **验证**：CI 同源 13 任务 BUILD SUCCESSFUL + `bridge/js` npm test **185 pass / 1 env-gated skip / 0 fail**（wiring-table 三向对账在新表上过）；四处 ArchitectureTest + ModuleGraphTest 随各任务绿。


### 2026-09-30 —— 外部审查整改·步骤 5：拆 `:app-service:npm`（模块 14 → 15）

（§6 模块表 + §10 npm 面；CI 任务行 12 → 13 模块）切分依据是零耦合：`packager/npm/` 对父包零 import、共享编排（`PackagerCollector`/`PackagerPipeline`/`TestIo`）也零反向引用 —— `git mv` 即净，编译器兜底：

- **迁出 31 个 `.kt`**：`packager/npm/` 16 件 + `NpmShellKit` + npm 测试 13 件 + `NpmShellKitTest` → `app-service/npm/`（包名 `…appservice.packager.npm`/`…appservice.packager` → `…appservice.npm`）；fixture APK 资源属 Apk 测试面，留 packager。
- **新模块骨架**：`app-service/npm/build.gradle.kts`（`autoscript.jvm` + `:domain` + coroutines；`-PskipNpmE2E` exclude 块随迁——`HostNodeNpmE2ETest`/`NpmCacheSeedDeployerTest`）；新 `ArchitectureTest`（黑名单同 packager + 同级 `appservice.packager..`）。packager 的 `tasks.test` exclude 块随之整删，其 ArchitectureTest 黑名单补 `appservice.npm..`。
- **三角同改**：settings `include(":app-service:npm")`；ModuleGraphTest allowed 两处（`:app` 集 + 独立行 `setOf(":domain")`）+ 断言消息 14→15；framework-design §6 计数 14→15 + packager 行收窄 + npm 新行（`:app` 允许列本就是 `:app-service:*` 通配，零改）。
- **CI**：job 名模块列举加 npm、L32 任务行加 `:app-service:npm:test`（13 任务）；`event-wire.test.cjs` 硬编码路径随迁；`TestGuard.ENV_GATED` 两条按类名匹配本就不动（注释路径更新）。
- **消费方**：`:app` 加 `implementation(project(":app-service:npm"))`；`AppShellKit`/`AppShellProductionWiringTest`/`P0LoopbackTest` import 改；`AppShell`/`AppShellCapabilityMountTest`/`P0LoopbackTest`/`ScriptPaths`/`ZipContracts` 的 KDoc 归属句改（`NpmProjectLayout`/`NpmSnapshot`/npm 真实现住 `:app-service:npm`）；§6 同段散文与 §12.2 npm 行路径改（该行 `mount(): NamespaceHandler` 残留一并清 —— 步骤 4 已删壳，文字不留谎）。
- **验证**：`:app-service:npm:test` + `:app-service:packager:test` 首跑绿；收尾 CI 同源 13 任务 + `bridge/js` npm test 全绿（2026-09-30，同批核验）。


### 2026-09-30 —— 外部审查整改·步骤 3：JSON 只留一个（DomainJson 合一）

（§7.5 编解码面；选型拍板见 [`design-decisions.md`](design-decisions.md) 已拍板第 11 项）五子步各一提交：

- **3a（f585c97）**：`NpmBridgeJson`（256 行）并入 `DomainJson` —— 字段读取族 `reqStr/reqObj/optStr/optLong/optBool/optStrList` 落位 `:domain`（KDoc 注明与 `bridge.Decode` 扩展同口径）；npm handler 30 处 + 测试 2 处调用换 `DomainJson.`。
- **3b（da39278）**：`EngineBridgeJson`（221 行）并入 —— engines handler 22 处 + 测试 41 处 + A11y KDoc 提及随迁。
- **3c（9ee1b19）**：`WorkManagerNamespaceHandler` 内联 `WmJson`（~180 行）并入 —— WM 27 处 + `quote(`→`encode(`；`:app:testDebugUnitTest`（带 env）绿。
- **3d（8f9f885）**：`TinyJson`（152 行）删除 —— `JsonTransport` 编解码走 `DomainJson.encode(mapOf…)`/`decodeObject`，新增 `internal decodeFlat(text, allowed)` 承接原白名单语义（未知字段如实拒绝 = 传输层协议纪律，非 codec 能力）；`payloadOrNull`/`detailOrNull` 补 else 分支（六值族下裸对象/布尔照样整帧 IAE，「字符串|null」契约逐字不变）；`ConsoleCollector` 及其测试同步换 `decodeFlat`/`encode`。
- **3e（d295ee9）**：`JsonLine`（149 行递归下降 Parser 整删，薄件 ~80 行）—— `parse` = `DomainJson.decodeObject` + 逐值 unwrap 到冻结行格式四型（布尔/嵌套对象照旧响亮拒绝），codec 的 IAE 一律包 IOException（「行损坏」错误类型与消费方 try/catch 口径不变）；`quote/quoteAll` → `DomainJson.encode`（转义族是超集，多 `\b` 与 `\uXXXX`）；
  字段强类型读取族（`str/long/optLong/optStr/optStrList`）原样保留。`:app-service:scheduler:test` 金样全绿。
- **收尾断言**：5 个 codec 对象（`TinyJson|A11yBridgeJson|NpmBridgeJson|EngineBridgeJson|WmJson`）全仓 `grep` 清零；`JsonLine` 保留为协议薄件（拍板第 11 项边界，其 `Parser` 内类已删）；`:domain` 新增 `DomainJsonTest`（6 用例：转义族 round-trip 含超集 b/f/u 与裸控制符、结构 round-trip、读者族宽容/拒绝面、非法输入 IAE 面、`encodeParsed` 数字原文透传——`1.50`/大整数不被 Double 化）。
- **兼容边（本步骤唯一行为收紧）**：`DomainJson.parseString` 拒未转义控制字符、`decode` 查尾部多余字符 —— 存量含裸控制符的 journal 行、带尾随垃圾的帧从「被接受」变「IAE/IOException 响亮拒绝」；测试面无依赖旧行为的用例。
- **验证**：`:bridge:java:test`、`:app-service:scheduler:test`、`:domain:test` 各子步绿；收尾 CI 同源 12 任务 + `bridge/js` npm test 全绿（2026-09-30，同批核验）。


### 2026-09-30 —— 外部审查整改·步骤 1：机器路径 18 处清零 + 原生暂存入约定 + `:engine:sandbox` 空壳摘除

（审查断言「硬编码路径 18 处」按 kts 6 + build-native.sh 6 + run-host-tests.sh 6 精确核实后全清；§4.1/§6 构建面）

- **app 原生/资产随包迁入约定**：`prepareEngineNativeLibs` + `prepareBridgeDistAssets` + preBuild wiring 从 `app/build.gradle.kts`（266 行 → 106 行）整段迁入 `build-logic/src/main/kotlin/autoscript.engine-natives.gradle.kts`（`autoscript.engine-natives`，:app 应用）；`import java.io.File` 随迁。
- **机器路径三处手术**：① libnode/libopencv 第三候选（`/tmp/nrb-out7`、`/tmp/img-opencv-out`）删除，dev 复现改 `export LIBNODE=/path/to/…`；② `ANDROID_NDK_HOME` 的 `/root/ndk/…` 兜底删除，且求值刻意留在 doLast「三件齐」分支 —— **全无 → 警告 分支没 NDK 也要能 assemble**（语义三分支逐字保留，本机实测：三件齐 env 绿 / 半套红带 export 指引 / 挪开交付件后全无警绿）；
  ③ 半套报错补 `export LIBNODE` 出口指引。
- **两脚本默认值 → 必填 env**：`build-native.sh` 的 `ANDROID_NDK_HOME`/`NODE_SRC`/`LIBNODE` 无缺省（缺即 fail 带 export 样例，`bash -n` 过 + 缺 env 实测报错形态）；`run-host-tests.sh` 的 `OCV_SRC`（参数或 env 二选一必填）、`OCV_HOST_BUILD` 必填（缺即 usage exit 2），构建日志 `/tmp/ocvhost-build.log` ×3 改入 `$BUILD/opencv-host-build.log`。
  `image-native.yml` L72 注释同步（L103 本就显式传 `/tmp/ocvpin`，CI 已显式 `OCV_HOST_BUILD`）。
- **`:engine:sandbox` 空壳摘除**（QuickJS 已裁不变，推翻「壳保留」旧口径见 design-decisions）：settings `include` 注释（注释块写复活步骤）；ModuleGraphTest 三改 —— `include` 正则锚行首 `(?m)^\s*`（注释行不计，机器口径）、allowed 删行、断言消息 15→14。`grep "/tmp/\|/root/" --include=*.kts` 仅剩 `platform/capabilities` 注释里「无障碍/root/adb」字样（非路径）；两脚本 0 命中。
- **验证**：`bash -n` ×2；缺 env 报错形态实测；`assembleDebug` 三件齐绿（env：`LIBNODE`+`ANDROID_NDK_HOME`）；CI 同源 12 任务绿；`bridge/js` npm test 185 pass / 1 env-gated skip。
- **本机注意**：worktree 的 `engine/node-process/build/native-local` 留有半套交付件（stale noden）→ 本机跑 `:app:*` 任务需 `export LIBNODE=… ANDROID_NDK_HOME=…`（或清掉 native-local 回「全无 → 警告」）；**干净 checkout = CI 同源零 env**（全无分支不要 NDK）。


### 2026-09-30 —— 外部审查整改·步骤 4：删 \*Lite + RpcNamespaceHandler 基类承接解码与错误映射

（§7.5 handler 形状 / §4.1 缝；拍板见 [`design-decisions.md`](design-decisions.md) 已拍板第 10 项）三点推进：

- **4a（eb84984）**：codec 落位 `:domain` —— `DomainJson.kt`（原 `A11yBridgeJson` 迁入改名）、`Decode.kt`（解码 helpers 上移，public 抛 IAE）、`RpcNamespaceHandler.kt`（基类：`handle` final、fold 两类、`methods()` 留位步骤 7）。
- **4b（4eec65e）**：`:platform:capabilities` 14 个 handler 全部 `: RpcNamespaceHandler()`，`BridgeRequestLite`/`ResponseLite` 与 a11y/screen 嵌套 `Request`/`Response` 删除，装配工厂束（`CapabilityNamespaces`）直返 handler，净 −682 行。
- **4c**：`Engines`/`Npm`/`WorkManager`/`PowerManager` 四 handler 转基类（Engines 展开 12 处内联 IAE 折叠、删嵌套形状与本地 `ok/err`）；`AppShell.handleLike` 适配扩展删除（`router.register("engines", enginesHandler)` 直挂）；
  `.mount()` 4 处调用点去壳（`NpmShellKit`/`AppShell`/`AppShellApplication`/`AppShellCapabilityMountTest`）；Engines 测试 49 处 Request → `enginesReq` 工厂（缺省 TTL 30s——仅即时启动路径用缺省，
  排队用例全部显式 TTL，语义不漂移），`.code` → `.errorCode`。
- 收敛核验：`handleLike`/`.mount()`/`*Lite` 全仓代码清零（Lite 仅剩 `CapabilityNamespaces` KDoc 一句退役记录）；`catch (e: AutojsException)` = 8 处（基类 1 + 桥外业务 7），桥折叠全归一。
- 12 任务（CI 同源行）+ `bridge/js` npm test 全绿（2026-09-30）。

### 2026-09-30 —— 外部审查整改·步骤 2/4 先行：build-logic 约定插件 + JVM 插件纠偏 + 共享架构门

外部审查（8 步重构顺序）核实后按「2/4 先行、8 步全做、冲突项全盘照做」推进。本条记步骤 2 落地（§4.1/§6 构建面）：

- **`build-logic/` 约定插件**（`includeBuild`，不入模块图、不计 15 模块）：`autoscript.jvm`（kotlin.jvm + JDK17 + JUnit5 + 共享架构门源 + 守卫）、`autoscript.android-library`（收编 12 处逐字重复的 `android{}` 公共段，namespace 留各模块）、`autoscript.test-guard`（:app 单独应用）。
- **纯 JVM 插件纠偏**：`:app-service:{scheduler,permission-center,packager}` 从 `android.library` 改 `kotlin.jvm`（三者 `import android.` = 0，插件错配）；`:domain`/`:bridge:java`/`:app-service:runtime` 同步走 `autoscript.jvm`。**`:app-service:script-repo` 不转**（`AndroidAssetsSource` 有 `import android.`）。ci.yml L32 对应任务名 `testDebugUnitTest` → `test`。
- **共享架构门 `ArchGate`**：约定注入 test 源集；8 个 `ArchitectureTest` 改薄壳（只声明本模块包 + 黑名单）；`:domain`/`:bridge:java`（`@ArchTest` 形）与 `:app`（例外量化形）保留自写。
- **删 `tools/jvm-test.sh` + `tools/jvm-test-all.sh`**（裁定全盘照做）：本机快速门 = CI 同源 `./gradlew`；「aborted ≠ 绿」由 `TestGuard` 承接（skipped 即红；`ENV_GATED` 只放行设计上环境门禁的 `NpmCliDeployerTest`/`HostNodeNpmE2ETest`）。口径变更见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)。
- **A2–A4 真机性能实测挂起**（原主线）：重构优先；image-native 新 `libopencv` artifact 已就绪（HAL 悬空符号修复 73e2ba0 后 CI 绿），恢复时从「下载 artifact → 推设备 → 跑 imgbench」续。

12 任务（CI 同源行）本机全绿（2026-09-30）。


### 2026-09-30 —— A 组第一批实测（云手机，Android 13 / API 33 / arm64 / PAGE_SIZE=4096）

生产布局（`e2e/lib/arm64-v8a/` + `e2e/files/bridge-addon/` + `files/node_modules/auto`，`LD_LIBRARY_PATH` 清空）复跑 + 三项新数。dist 与设备件同源（`console.js` md5 一致，未推新包）。

| # | 项 | 结果 |
|---|---|---|
| A1 | 桥往返（`auto.console.log` 200 次，脚本侧 `Date.now` 打点，host 真处理） | `min=0 p50=0 **p95=1** p99=2 max=2`（ms）；host 侧 205 帧对上（200 console + 5 预热）。§7.7 空 RPC p95 < 2ms 口径下有余量（注：这是 console 整调用往返，非裸 RPC） |
| A5 | `Intl.*` zh/en 运行期 | **已测 9-30（新件复测）**：`Intl` 在位（`icu_small=true`/`icu_locales=en,root,zh`/ICU 78）；zh-CN 与 en-US 数字/日期与桌面对照**逐字一致**；ar-EG 回落 latin 数字（**预期**非缺陷，见表下）。详见表下 A5 细节 |
| A6 | 引擎进程 RSS（脚本存活窗内 `smaps_rollup`） | `Rss≈46.6MB Pss≈44.1MB`（其中文件页 ~32MB、匿名 ~12MB）；§15 80–160MB 区间内，偏下限（注：host 宿主进程同法测得 Rss≈47.6MB，同量级） |
| C1 | `.node` 经 `process.dlopen` 路径的 `require` | 与 9-29 同结论：**无 `DT_NEEDED libnode.so` 的件在 `require` 下仍 `ERR_DLOPEN_FAILED`（`cannot locate symbol "napi_add_env_cleanup_hook"`）**—— 即使宿主自身已 NEEDED libnode（启动闭包里有 libnode）。装载器按 SONAME 找依赖，不是全局作用域续期。`§10.12 不引入第三方 .node` 口径不变 |

- **A5 细节（自上表单元格原样外移）**：node-slice 36609817687 绿后取新 `libnode.so` 推设备，`Intl` 在位（`icu_small=true`/`icu_locales=en,root,zh`/ICU 78）。zh-CN → `1,234,567.89`、`2026年9月30日星期三`，与桌面对照**逐字一致**；en-US 同。ar-EG 回落 latin 数字（small-icu 只带 zh,en 走 root 数据，是**预期**非缺陷——契约拍板的就是 small-icu zh,en）。
  全链复跑 rc=0、6 帧；新件冷启 181–206ms（旧件 158ms，ICU 数据加载 +~30ms）；桥往返复测 p95=1ms 仍达标。

A2–A4（截图→找图/找色/模板）仍欠：`libopencv.so` 不在设备上（`find` 无命中），等 image-native 件或随包后再测。

### 2026-09-30 —— 等设备的那笔账：真机红测可执行清单（未执行，只列账）

源头：§19「下一步 (a)」与本表接口期三行真机红测。原则：**设备不到位就不写数字进契约**
（§7.7/§15 的数字仍是验收口径），本条只把"要什么设备、跑什么、判什么"钉死，
设备一到按单执行、回填数字。

**A. 性能数字（§7.7 表 + §15 启动预算）—— 要一台能跑生产 APK 的真机即可（现有云手机已够）**

| # | 量什么 | 契约锚 | 怎么量（adb 可脚本化） | 判据 |
|---|---|---|---|---|
| A1 | 桥往返（JS→:main→回） | §7.7 空 RPC p95 < 2ms | `auto.console.log` 200 次脚本侧打点（host 真处理） | **已测 9-30：p95=1ms**（console 整调用往返；见本文件流水 9-30 条） |
| A2 | `captureScreen → findImage` 端到端 | §7.7 < 1s；一次截图两次匹配 < 700ms | 真机截屏（a11y 路径）→ `ingest` → `findImage` 两次，打点三段（截图 / ingest / 匹配×2） | **已量计算段 9-30：1912.8ms ❌**（decode 46.2 + match×2 1827；截屏段未测——无生产 APK/a11y，计算段已超则端到端必超）；driver 见流水 A2–A4 条 |
| A3 | `findColor` 1080p | §7.7 < 10ms | 生产布局下落一张 1080p 真机截图，`findColor` 单人独立子图计时（`adb shell` 循环 100 次取中位） | **已量 9-30：ROI 300×150 median 0.878ms ✅**（全帧 61.4ms 是口径外参考；host ROI 0.19ms） |
| A4 | `matchTemplate` 1080p | §7.7 < 40ms | 同 A3，模板用真机 UI 切片（非合成图） | **已量 9-30：370×80 = 933.6ms ❌ / 48×48 = 862.4ms ❌**（host 同输入 566/614ms —— 平台无关量级，非设备慢） |
| A5 | `Intl.*` zh/en 运行期 | §18 第 4 项跟进 | `node -e "console.log(new Intl.NumberFormat('zh-CN').format(1234567.89))"` + `DateTimeFormat('zh-CN')` 在真机 noden 跑 | **已测 9-30：与桌面逐字一致**（新件，ICU 78 small-icu zh,en；见本文件流水 9-30 条） |
| A6 | 引擎进程 RSS | §15 80–160MB | 脚本存活窗内 `smaps_rollup` 采样 | **已测 9-30：Rss≈46.6MB/Pss≈44MB**，区间内偏下限（见本文件流水 9-30 条） |

**B. 平台策略（现有云手机原理上测不到 —— 要 16KB 模拟器镜像或 Pixel 8+）**

| # | 量什么 | 契约锚 | 为什么现有设备不行 | 要什么 |
|---|---|---|---|---|
| B1 | 16KB 页机装载 | §7.8 / §16 | 该机 PAGE_SIZE=4096，`p_offset ≡ p_vaddr` 错了也照样装载 | 16KB 模拟器镜像（CI 门禁只保 ELF 形状，不保内核真装载） |
| B2 | SELinux enforcing 上下文 | §19 台账 | 该机 Permissive + root shell，域转换/拒绝测不到 | 非 root + enforcing 的真机或模拟器 |
| B3 | `nativeLibraryDir` 提取路径 | §19 交付轨 | 真机红测走的是 adb push 落位，非 PM 提取路径 | 装生产 APK 走 PM 安装后验 |
| B4 | targetSdk36 app 数据区 exec 策略 | §19 台账 | 同 B3，且 targetSdk 行为随版本变 | targetSdk36 的 APK 在 Android 15+ 设备上验 |

**C. 语义细节（host 测不了的最后一公里）**

| # | 量什么 | 契约锚 | 说明 |
|---|---|---|---|
| C1 | `.node` 经 `process.dlopen` 路径的 `require` | §10.12 / §19 台账 9-29 | **已验 9-30**：`require` 路径同样 `ERR_DLOPEN_FAILED`（`cannot locate symbol "napi_add_env_cleanup_hook"`），§10.12 口径不变 |
| C2 | `captureScreen → findImage` 实测数字回填 §7.7 | §7.7 / §7.7 实测记账块 | **已执行 2026-09-30**：数字进 §7.7 表下实测记账块（判据保留、出路三选项未拍板）+ 上方接口期行销账 |

执行顺序建议：A（现有设备即刻可做）→ B（等设备）→ C1（可与 A 同批）→ C2（A2 的回填）。
本条是清单，不改任何契约数字；数字只在实测后按"只追加"纪律另起流水回填。


### 2026-09-29 —— 真机垂直切片红测（非 root shell，Android 13 / API 33 / arm64-v8a / PAGE_SIZE=4096）

设备：云手机（`Tianyi1Hao2021` / Android 13，内核 5.15.94-android13）。**本机 host 无 `/dev/kvm`**，
故走 adb 上的真机而非本地模拟器；设备 PAGE_SIZE=4096，16KB 相关结论该机**不能**背书。

**已验（均在生产布局 `lib/arm64-v8a/` + `files/bridge-addon/` + `files/node_modules/auto`，且
`LD_LIBRARY_PATH` 清空）：**

| 项 | 结果 |
|---|---|
| exec + `dlopen` + `dlsym node::Start` | 通，`node v24.21.0`，冷启 158ms |
| unix abstract socket + `SO_PEERCRED` uid 门禁 | 通（`net.createServer` 侧绑定成功、引擎连入） |
| facade → addon → 桥 → 宿主 | 通，单次执行 6 帧（3× `console.log` + 3× 心跳，reqId `-seq` 负数命名空间按设计） |
| 生产形态 20 连跑 | 20/20 `rc=0` |
| addon 缺位（选填件） | 按设计降级：脚本照跑 `rc=0`，桥调用点才 `ERR_ENGINE_STOPPED` |
| `AUTOSCRIPT_HOST_SOCKET` 给了但没人听 | 按设计硬失败 `exit 3`，不静默降级 |
| libnode 缺位 | 链接期即败（`CANNOT LINK EXECUTABLE`，`rc=1`）——**宿主改为 NEEDED `libc++_shared` 后的形态变化，见下** |

**推翻的三条口径**（详见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)）：
① 「`RTLD_GLOBAL` 让 addon 的 `napi_*` 从 libnode 动态表解析」——bionic **不给**后做的
`dlopen` 续期全局作用域（glibc 会）；② 「DT_NEEDED + `$ORIGIN` 就够」——RUNPATH **不作用于
被依赖库自己的传递依赖**，链接形宿主三处摆放一致死在 `libc++_shared`；③ 「宿主静态 STL」——
正是它造成 ②。**最终形态**：宿主 = dlopen 形 + 自己 NEEDED `libc++_shared.so` + `$ORIGIN`；
addon = NEEDED `libnode.so`（SONAME 命中）+ 静态 STL；两者产物 strip 后 1.05MB / 37KB
（未 strip 时 addon 8.27MB）。断言已进 `build-native.sh`（宿主/addon 各一套 NEEDED 契约 +
RUNPATH + 16KB align + `p_offset ≡ p_vaddr (mod align)`，四条负向校验均实测变红）。

**顺带否掉一条外部推测**：曾被猜测「libnode 进了启动闭包后，第三方 `.node` 也能装载了，
§10.12 的『不引入第三方 `.node`』理由因此变弱」。实测**不成立**：宿主自身 NEEDED libnode
（libnode 确在启动闭包里）时，`dlopen` 一个**没有** `DT_NEEDED libnode.so` 的 `.node`
仍然报 `cannot locate symbol "napi_add_env_cleanup_hook"`。解析的机制是**装载器按 SONAME
找依赖**（addon 自己声明了 libnode.so 才命中），不是"全局作用域对后续 dlopen 生效"——
第三方 `.node` 必须自己链 libnode 才有戏。§10.12 的口径不变。

**仍未验**：16KB 页机（设备 4KB）、SELinux enforcing 上下文（设备 Permissive + root shell）、
`nativeLibraryDir` 提取路径与 targetSdk36 的 app 数据区 exec 策略（真机红测只有 16KB 模拟器镜像
或 Pixel 8+ 能给）、`.node` 的 `require` 在 `process.dlopen` 路径上的细节。

## 归档

- [2026-09-25 及更早流水（自 §19 结语整段外迁，逐字保留）](archive/status-2026-09-25.md) —— 2026-10-01 归档，原 14,625 字节单行随之搬走。


## 各分卷实现注记的搬迁状态

口径：分卷正文只留**契约**（是什么 / 口径 / 纪律），实现状态与实测数字搬进本文件
（见下节）。搬迁一律**逐字**，不做压缩 —— 行内判据与实测数字是台账的正文，不是摘要。

### 已迁（2026-10-01）

| 源 | 规模 | 内容 |
|---|---|---|
| §9.2 截图与图像管线 | 23.6 KB | 截图链路实现、八个算子逐条记账、真机实测与四修链 |
| §8.6 调度系统 | 8.4 KB | 排队分级、deadline、停止收口、急停、任务中心读口/操作面 |
| §8.4 看门狗 | 3.5 KB | 三路纯判定、`ProcessMonitor` 采样、调度循环、心跳账本 |
| §8.7 保活与电源 | 2.8 KB | FGS + 唤醒锁账本 + 前台账本 + 屏幕门禁收口 |
| §8.3 生命周期状态机 | 1.6 KB | P0 收敛子集、drift 裁决、P0 不存在的态 |
| §8.5 崩溃恢复 | 0.9 KB | `JournalFileStore`/`FileRunArchive` 持久形态与孤儿结算 |
| §9.5 权限与能力中心 | 2.0 KB | `AndroidPermissionGates` 生产接线、能力中心读口 |
| §9.6 数据与存储 | 2.3 KB | 五个系统命名空间落地清单、`ScriptPaths`/部署恢复 |

正文侧留一句结论 + 指回下节锚点的链接。

### 未迁（本节即待办清单）

按 `grep -c '已落地|已接线|已通桥面|已接生产|已全部落地|实现已接|已落'` **重新测量**
（下表数字为 2026-10-01 实测，行号一律不写 —— 旧表的行号在折行与外迁后已全部失效）：

| 文件 | 命中行 | 命中字节 | 备注 |
|---|---|---|---|
| `13-roadmap-budget.md` | 15 | 4,649 | 该卷主题本就是**进度/预算**，「已落地」是其正文而非错位 |
| `18-19-ledger.md` | 14 | 3,425 | §18/§19 沿革卷，同理 |
| `07-bridge.md` | 12 | 1,401 | §7.7 实测记账（「第六次实测」类）自带只追加纪律 |
| `09-capabilities.md` | 6 | 1,604 | §9.1/§9.4/§9.6 散句，与所在契约条目同段 |
| `08-execution.md` | 4 | 681 | §8.1/§8.5 残句 |
| `10-npm.md` | 3 | 770 | §10.5 审批机制与裁决处置 |
| `12-js-api.md` | 3 | 670 | §12.2 接线散文（表格第四列另有专项口径） |
| `06-modules.md` | 1 | 474 | 模块表一行 |

**判断**：这 ~14 KB 与所在节的主题绑定（进度卷、沿革卷、实测记账卷），逐行抠出会
把契约散文切碎，收益低于风险，故**本轮不动**。真要迁应先给这三卷定性（它们是不是
「契约卷」），那是文档架构决策，不是清理。

`§12.2` 表格第四列（挂载状态）是另一个问题：它是**状态**却住在契约卷里。
换成一列「见台账」需要同时改 `bridge/js/test/wiring-table.test.cjs` 的读表口径
（该门读 `12-js-api.md` 表头→首个空行、要求 ≥15 行），故留待有意改门时一并做。

## 实现注记（自各分卷外迁，逐字保留）

各分卷正文原先内嵌的「已落地 / 实测」叙事整段搬来此处，正文侧只留结论 + 指回本节的链接。搬迁**逐字**（不做压缩），行内判据与实测数字一字未改；只删掉了搬运处的空行。

### §9.2 截图与图像管线（自 `09-capabilities.md` 外迁）

- **已落地（Kotlin 侧）**：`ScreenshotSource`（333ms 节流 / generation=1 单帧句柄 / 会话 open-close；`recycle` 已升为 `:domain` `FrameSource` SPI 方法）+ `ScreenNamespaceHandler`（构造只收 `FrameSource` SPI，
  `capture/recycle/startCapturer/nextFrame/closeSession`）。**Android 真实现已接（§9.2 a11y 截图路径）**：`AndroidFrameProducer` 经 `A11yBridge.{screenSnapshot,takeScreenshot}`（`AutoScriptAccessibilityService` 设备面实现—
  —`ScreenshotResult` HardwareBuffer→软位图→**紧密 RGBA 像素**（2026-09-26 起不再压 JPEG —— 那段压缩是死重且有损），**实际尺寸随帧走**（`ProducedFrame`，曾经固定 1080×2400 回包是对 JS 报假尺寸，
  已除）；配置 `canTakeScreenshot=true` 进 res/xml（AOSP 明示缺它两法都不可用）；失败码分类映射 SECURE→`ERR_BLACK_FRAME`、系统限频→`ERR_INVALID_PARAM`、通道失效/
  无效窗口→`ERR_SERVICE_DISABLED`、内部错→`ERR_IO`；API34+ `takeScreenshotOfWindow`、API30–33 `takeScreenshot`、API<30 如实 `ERR_NOT_IMPLEMENTED`）。生产装配 `PlatformWiring.screenHandler = CapabilityNamespaces.screen(ScreenshotSource(AndroidFrameProducer(), analyzer = images))`（**同一个** analyzer 也喂给 `images` 缝，
  §18-8(b) 一张表；analyzer 为 null 即退回本地帧表） → `AppShellApplication.installWithFiles`，与 a11y 同底（`SystemA11yBridge`，服务未连 = `ERR_SERVICE_DISABLED`）；锁屏/无窗口由 `ScreenPolicy` 预检分类，
  安全窗由回调码兜底（无障碍读不到窗口 FLAG_SECURE，`secureForeground` 预检位恒 false —— 不伪造预检能力，分类结果殊途同归）。**仍缺**：MediaProjection 高清会话（授权 UI + FGS + ImageReader→libopencv.so）—
  —换 producer 即插，语义面不动；P0 会话由同一 a11y 帧源连续截图承接。

- **图像分析面（`images`，§12.2 第七条独立缝）已通桥面、已通 native 实现（2026-09-25）**：`:domain` `ImageAnalyzer` SPI（**十方法**：`decode`/`matchTemplate`/`findImage`/
  `findColor`/`release`/`toGrayscale`/`crop`/`resize`/`rotate`/`findFeature` —— 后五个 2026-09-29 随 P1 桥消费方开通；`ImageFrame{HandleRef,width,height}` / `ImageMatch{x,y,width,height,confidence}` /
  `ColorHit{x,y,r,g,b,a}` / `FeatureHit{x,y,confidence}`）+ `:platform:capabilities` `ImagesNamespaceHandler`（**无状态转接**：帧表/发号/在场性全在 SPI，2026-09-26 起不再自管第二张表 —
  — §18 第 8 项 (b) 发号侧归一）+ JS facade `images.ts`。**P0 刻意不提供内存分析器**：看不见像素的替身只能靠自报坐标假装匹配成功，那比没有更坏 —— so 缺位时 `imagesHandler` 为 null，
  桥对 `images.*` 如实 `ERR_NOT_IMPLEMENTED`（2026-09-25 起 native 已接，见下）。**`findColor` 已落地（2026-09-25，P1 第一个算子）**：单色 + 逐分量容差 + 可选区域 + 回第一个命中，
  四层同改（`imgnative_color` → `colorNative` → `NativeImageAnalyzer.findColor` → `images.findColor`）。两条口径在该层钉死：**未命中是答案**（`x = -1` 哨兵回裸 `null` —
  — (0,0) 是合法首像素，拿 0 当“没有”会把左上角的命中静悄悄吃掉）；**“扫过 0 像素”是参数错**（`ERR_INVALID_PARAM`，那不是“没有”而是“根本没找”）。
  P1 native 面的八个算子已全落（**灰度、裁剪、缩放、旋转与特征**：计算核 `imgnative_gray`/`imgnative_crop`/`imgnative_resize`/`imgnative_rotate`/`imgnative_feature` 已落（28 + 46 + 45 + 45 + 51 例 host 断言），
  **桥面同批开通 2026-09-29** —— 见本节末各算子的「桥面已开」追记与 §12.3.3）。**宿主机语义门禁**：计算核零 JNI（见 imgnative.cpp 文件头），于是 host 侧用**同 commit** 的 OpenCV 4.14.0 静态库直链它、
  以 x86_64 跑像素断言（`bridge/image/test/cpp/`：`run-host-tests.sh` 一条命令装+编+跑）。这道门存在的理由很实：`imgnative_color` 里有三处判读是「编得过但译反了照样出结论」（**已覆盖**，
  `host_color_test` 36 例）——Vec4b 回读的通道序（px[2]→r/px[1]→g/px[0]→b，译反只是回包 r/b 互换）、ROI 内坐标+roi 左上角=全帧坐标、以及「扫过了、没有」与「扫过 0 像素」的区分；
  JVM 522 例与 JS 128 例全绿时它照样能错，而违约金是 hook 不到。**2026-09-25 已实证**：NDK `-fsyntax-only`、JVM、JS 三门全绿之际，这道门抓出 `cv::imread(IMREAD_COLOR)` 把任何来源压成 3 通道 BGR、
  `at<Vec4b>` 静默读进下一行首字节 —— alpha 分量从来没参与过判定（ASan 也不报）。修法=IMREAD_UNCHANGED + decode 归一成 4 通道 + `imgnative_color` 内通道/深度兜底守卫，
  `host_decode_norm_test.cpp` 21 例钉死。 **`imgnative_match` 的判读也补上了 host 断言**（`host_match_test.cpp`，2026-09-25 引入时 24 例，随后续各轮一路加到当前 **124 例**）：
  命中坐标 = 模板左上角（matchTemplate 的 maxloc 是结果面左上角，照搬即模板位置，**不**再叠加 ROI 偏移）、w/h = 模板尺寸、**未匹配是答案不是异常**（out_match=0 且 x/
  y/w/h/confidence 全 0，status 仍是 0 —— 与 findColor 的 `x = -1` 同一条纪律；若把 `w == 0` 当"未命中"判，一张 0 宽模板就能让脚本把命中读成未命中）、阈值域 `maxv == threshold` 判命中（"≥ 阈值即命中"是契约原话）、
  模板比画面大 → ERR_IO(3)。补它的理由与 decode 归一同源：**模板匹配此前只被 JVM mock 覆盖，mock 不碰像素、永远回一个编好的 ImageMatch**，于是"坐标译反了""未命中时把 x/
  y/w/h 也回出去"在 JVM/JS 两门全绿时照样能溜过去。**灰度也落进计算核了**（`imgnative_gray`，2026-09-25，`host_gray_test.cpp` 28 例）：它是 decode 之外**第一个产出新帧的算子**，
  所以这道门额外钉了"产出新帧"的边界 ——产出帧号 > 原帧号且不顶掉原帧、原帧不被就地改灰、两帧各自独立 release、放掉产出帧后原帧照常可用（这四条 JVM/
  JS 两门**结构上看不见**：mock 只回一个自报的 ref）。产出帧仍是 4 通道 BGRA（帧表不变式，为此提了具名谓词 `frame_is_normalized`，`imgnative_color` 里原先手写的那处判据一并换过去），
  **alpha 原样带过去不抹 255**（灰度压掉的是色彩信息，透明与否不是色彩；decode 用 IMREAD_UNCHANGED 保住 A 就是让 a 分量参与判定，在灰度这步抹平等于把那次事故引回来）。
  灰度权重用库的 `COLOR_BGRA2GRAY` （0.299R+0.587G+0.114B），不自己写系数（抄一份就多一个漂移面）；host 断言钉纯红 76 / 纯绿 150 / 纯蓝 29，并正面否认平均法（找 85 必须未命中）。
  **桥面已开**（2026-09-29，`images.toGrayscale`）：当初「脚本侧没有消费方就不开桥面」的判据，在 P1 图像桥消费方这一批里被消费方（预处理 + 取亮度面）兑现了 —
  — 于是 `:domain ImageAnalyzer` 与 handler 同批认它。原判据本身不撤销（没有消费方的算子仍不开桥面），只是这条的消费方到了。**裁剪也落进计算核了**（`imgnative_crop`，
  2026-09-25，`host_crop_test.cpp` 46 例）：它是第一个**尺寸会变**的产出算子，比灰度多三处判断，每一处都在 host 上钉住。（1）**区域判据复用 `resolve_region`**（找色那同一个函数，
  不是抄一份）—— 于是"越界"在两处是同一个码、同一个边界口径（`rx + rw == cols` 贴边合法，越界一律 `ERR_INVALID_PARAM`）。（2）**`region == nullptr` 在本算子是拒收**，
  不按 `resolve_region` 的缺省解释成"整帧"：裁剪的语义就是"取一个子区域"，缺区域时唯一自洽的解释是"整帧拷贝"—— 想要整帧副本就明写整帧区域（那也有断言）。
  （3）**产出必须是拷贝（`clone()`），不能是 `(*f)(roi)` 视图**：帧表是**所有权表**不是视图表，源帧一 release，脚本手里的"子图"就悬垂。第（3）条值得单独记一笔，
  因为**它的判据不是直觉能给的**：`(*f)(roi)` 是浅视图，而 OpenCV 的 ROI **会把父缓冲的引用计数带住**（实测 `src.u->refcount` 1→2），所以"放掉源帧后视图会读到垃圾"**不成立** —
  — 缓冲根本没被释放，像素照常读出正确值（host 侧实测：放掉源帧后再读产出帧，视图实现同样正确，64 轮同尺寸重分配也没能把它分出来）。别名唯一可观测的后果是**内存归属**：
  视图共享同一个 `UMatData`，引用计数不为零就释放不掉，于是一张 1×1 的产出帧能把源帧整块缓冲钉在常驻内存里（4000×4000×4 实测：拷贝实现放源帧后 RSS 回落到基线，
  视图实现留 ~61MB）。这条判据因此写成了 **RSS 断言**（`host_crop_test.cpp` 第 8 段，大源帧裁 1×1 后放源帧、阈值 16MB 取在两种实现中间；§7.7 本来就没有为 crop 承诺 0 拷贝，
  而"0.3MB 的产出帧钉住 64MB 常驻"正是它在脚本侧的代价）。顺带钉住：产出帧号 > 源帧号且不顶掉源帧、宽高 = region 的 w/h（不是源帧尺寸）、区域坐标是**源帧坐标系**（子图 (0,0) 逐分量 == 源帧 (rx,ry)，
  非零起点也钉了）、产出帧仍是 4 通道 BGRA 且 **alpha 原样带过去**（裁出的单像素 a=210 不抹 255）、产出帧可以**再裁**、句柄已死 → `STALE`、出参指针为 null → `ERR_INVALID_PARAM`，
  且**拒收一律早退不写出参**（与 match/gray 同口径）。**裁剪的桥面已开**（2026-09-29，`images.crop`，region 必填四元组）—— 当初那条「没有消费方（找色能在 `region` 上限定范围、
  读路径不产出帧；只有要把子图当独立一帧反复用/当模板时才需要 crop）」已被 P1 桥消费方兑现；region 缺省**不开**口（想要整帧副本就明写整帧区域）。
  **缩放也落进计算核了**（`imgnative_resize`，2026-09-25，`host_resize_test.cpp` 45 例）：它是第一个**像素值要重算**的产出算子（crop 是搬像素、逐点相等；resize 按插值重算，
  逐点不等是正常的）。入参是**目标尺寸**（`dst_w` × `dst_h`）不是倍数（倍数是调用方算的浮点；与 gray/crop 的"宽高随帧回"同一条纪律：尺寸真值只有产出帧的地方知道，
  让下游自己推是猜）。插值**固定 `INTER_LINEAR`，不做入参**：NEAREST 放大是块状马赛克（UI 细线条/文字边缘丢信息），CUBIC/LANCZOS 更贵且在截图/PNG 这类非照片输入上无可证增益 —
  — 多一个入参就多一个"选错静默换答案"的漂移面。门禁把 LINEAR 的可观测行为钉了三条：纯色帧任意缩放值不变（杀通道丢失/alpha 被抹）、2×2 四角帧放 4×4 四角守恒（杀几何对错/
  行列互换）、中心 (1,1) r=101 混合值**专杀 NEAREST**（NEAREST 给 100；已用故意 NEAREST 实现验过变红 —— 但诚实起见：CUBIC/LANCZOS 在此处同样给混合值，这条钉的是"不是 NEAREST"而非"只能是 LINEAR"）。
  另有 4×4 象限→2×2 的均值行为、同尺寸合法拷贝（不早退，调用方不用先判要不要调）、非对称尺寸（宽高各自独立）、**16384 单边配额**（16384²×4≈1GB，
  再往上是笔误把字节数当宽高；配额拒收与尺寸不合法同码，注释里分开写）。产出 4 通道 BGRA（resize 逐通道，归一进归一出，显式再断言一次防将来换后端静默掉通道），
  拒收一律早退，**桥面已开**（2026-09-29，`images.resize`，入参是目标尺寸、域 (0,16384] 整数）—— 当初那条「只有同一套模板跑多分辨率设备时才需要」已被 P1 桥消费方兑现。
  **旋转也落进计算核了**（`imgnative_rotate`，2026-09-25，`host_rotate_test.cpp` 45 例）：它是第一个**画布尺寸要算**的产出算子（resize 的尺寸是入参直给，rotate 的画布是包络公式 bw=round(|w·cosθ|+|h·sinθ|)、
  bh=round(|w·sinθ|+|h·cosθ|) 算出来的 —— 4×2 转 90°→2×4、5×5 转 30°→7×7 都钉了）。入参是**逆时针角度**（与 `getRotationMatrix2D` 正方向一致；转反了整行对不上 —
  — 5×5 数字帧 90° 首行 {5,10,15,20,25}，顺时针会是 {21,21,16,11,6}）。画布是 **expand**（包住整图不静默裁像素；想要"旋转裁剪"先 rotate 再 crop —— 两个算子都在了）。
  中心是**帧中心** ((w-1)/2,(h-1)/2)：奇尺寸下恰落中心像素，90° 倍角采样点落整数格点、敢写整行精确相等（相邻行采样到同一行是**行复制** —— warpAffine 逆映射的精确行为，
  host 实测五组全是整行相等，不是 bug；实现注释里写明了）。插值固定 LINEAR、填充固定 REPLICATE（黑边是找色的假阳性源 —— 见实现注释），30° 中心 3×3 混合值**专杀 NEAREST**（已用故意 NEAREST 实现验过变红；
  诚实线与 resize 同一条：CUBIC/LANCZOS 同样给混合值）。NaN/Inf → INVALID_PARAM（三角函数吃掉它们不报错，矩阵是垃圾 —— 入口拒比 warpAffine 断言变 IO 错更诚实），
  0°/360° 恒等（360° 先归一，不因浮点余数差一像素），**桥面已开**（2026-09-29，`images.rotate`，角度必须有限）—— 「把画面转正再匹配」这个消费方到了；
  展开画布 + REPLICATE 填充的口径不变（想要旋转裁剪就再 crop）。**特征也落进计算核了**（`imgnative_feature`，2026-09-25，`host_feature_test.cpp` 51 例）：P1 native 面的收官算子，
  也是第一个**不产出帧、只回坐标**的算子（回模板中心在场景中的 (x,y,confidence) —— **「中心」是字面口径**：报出的是模板几何中心在
   场景里的坐标（中位偏移 + 模板半宽高），不是内点质心（2026-10-01 修，见下），不是新帧号 —— 与 matchTemplate 的 ImageMatch 不同：特征匹配没有"模板尺寸"的概念，模板在场景里多大是未知的；回中心让脚本直接点下去）。链全固定：ORB → BFMatcher(HAMMING) knn k=2 → Lowe ratio 0.75 → 中位数偏移 ±3px 几何一致性计数 → **内点铺开度门**（任一边跨度 ≥10% 模板边长）。
   **2026-10-01 四修**（真机 UI 上恒 found=0 的病灶链，实测与探针见 §7.7 第六次实测块）：
   **成因（2026-10-01 逐窗交叉核对）**：33 例里 19 例恒 found=0，经 `scenekp`（场景侧
   逐窗数点）+ `featwin2`（模板侧缺省/垫边两档数点）交叉分开成三类 ——
   **A 真值处场景侧零关键点 6 例**（48×48 纯色图标 + 5 个 200×150 平坦窗；
   场景配额 8000→20000→40000 后仍有 5/4 例为 0；它们的真值处 ccoeff 实测最小
   **0.99993**，即"图上确实有"）；**B 模板侧缺省 ORB 零点 5 例**（370×80 纯文字行、
   1080×60 工具行、360×900 与 660×1400 与 60×2200 的 200×150 —— 场景侧有点
   （26/91/31/69/2），模板侧缺省档 0 点、垫边后 6~57 点，**仍** found=0）；
   **C 两侧都有点仍配不上 8 例**（模板侧缺省 7~124 点、垫边后 61~486 点，场景侧
   2~253 点）。A 是**结构性的地板**（场景侧一个点都没有，任何参数都无从谈起）；
   B/C 是 ORB 描述子在这类低纹理 UI 上的**可重复性极限**（两侧都有点、垫边后
   模板侧动辄数百点，仍凑不齐几何一致的内点）。三类都别当缺陷修，别的地方修不掉。
   ① **场景侧配额按形态分档** —— 短边 >640（整屏截图）才抬到 `nfeatures=8000 / edgeThreshold=10`，
   小场景保持缺省 1000/31（既有 host 夹具因此逐字不变）；模板侧永远缺省。
   ② **内点铺开度门** —— 内点挤在模板一小块里（任一边跨度 <10%）判未匹配（假阳的形状：
   真机实测报错 208.7px 而 inl=7/conf=0.64，模板侧坐标只占 5%×3%）。
   ③ **命中位置 = 模板中心**（中位偏移 + 模板半宽高）—— 原「内点质心」只在均匀铺开时成立，
   真机偏 82.4px（540×600）/ 60.8px（300×150），改后同批样本 0.0~0.8px。
   ④ **薄条/小模板零关键点垫边重试** —— ORB 的 `runByImageBorder` 在某边 ≤ 2·et(=62) 时
   清空该层全部关键点（与内容无关，1080×60 工具行、120×90 图标面板全中），反射复制 32px
   后重试；这条路另挂一道像素复核（报出位置严格坐标处 TM_CCOEFF_NORMED ≥ 0.6）。
   修后真机：300×150 err 0.00 / 200×150 err 0.00 / 540×600 err 0.00；**可达率不是
   100%** —— 1080×2400 真机截图上 200×150 网格 24 窗 + 文档引用窗口共 33 例，
   命中 14（13 例落在真值 3px 内）；370×80 纯文字行与 48×48 纯色图标恒 found=0
   （成因按下面的 A/B/C 三类分：370×80 是模板侧缺省档零点、48×48 是场景侧零点；
   两者真值处 ccoeff=1.0，配额抬到 40000/et10 也救不回 —— 特征匹配对无纹理
   区域的固有边界，该用 `matchTemplate`；后者在同批 33 窗口命中 33/33，但位置唯一
   只有 25/33（其余 8 例是像素级重复副本，argmax 口径））。**耗时分段**（1080×2400
   整屏，探针 `tiercost`）：场景侧 ORB 是整条链的地板 —— 缺省档 26.8ms、整屏档
   42.4ms（+15.6ms 就是分档的代价；跨会话复跑 27.7~28.7 / 42.9~44.4ms，
   **绝对值随机器浮动、稳定的是这 ~15ms 的差**），模板 ORB + BF 匹配只占几毫秒。每个固定点都有 host 实测依据：nfeatures 500/1000 同一子图描述子逐字节一致（参数只截断、
   不换答案）；BGRA 直喂与手转灰一致（ORB 内部按第一通道取灰，转灰是冗余步骤）；ratio 0.7~0.8 不换答案（good=29/30/33、几何正确都是 19），0.75 取 Lowe 原论文值。
   门禁钉了：子块命中（中心 ±10px、conf≈0.63±0.15 —— conf 指纹把 ratio 链钉住，ratio=0.99 会把它拉到 0.47，已用变体验过变红）、棋盘格误报 → 未匹配（host 实测误报 top 距离 60+，
   ratio 后 good 寥寥）、纯色模板空描述子 → 未匹配（不是 IO 错）、旋转 30° 后**未匹配**（描述子对得上但中位数偏移假设不再成立 —— 这是设计不是 bug，
   真要转着找得接 findHomography/calib3d，那是另一个算子；断言把这个边界钉死，免得被脑补成"旋转容忍=转着也找到"）、拒收早退、两帧不消耗。**构建轨代价**：
   features2d/flann 进 BUILD_LIST（`VERSIONS.env` 三模块 → 五模块；`build-opencv.sh` 链接行同步；host 门禁用同 commit 另配的五模块缓存过渡，本机重配一次即可合一）。
   so 体积增量待下一次 device 构建实测（host 静态库实测 features2d 1.8MB + flann 1.3MB —— 那是 x86_64 未 strip 的 .a，不是 arm64 so 增量，写在这里免得被当成结论引用）。
   **桥面已开**（2026-09-29，`images.findFeature`）—— 同一条纪律：刚性匹配（matchTemplate）与特征匹配（feature）是两种找图语义，脚本侧有了消费方（容忍缩放/
   轻微旋转的找图）才开第二个。P1 native 面至此收官：八算子（decode/match/release/color/gray/crop/resize/rotate/feature）全在计算核 + host 门禁里；**桥面也已在 2026-09-29 全部开通**（十方法：
   decode/matchTemplate/findImage/findColor/release/toGrayscale/crop/resize/rotate/findFeature —— 见 §12.2/§12.3.3）。**附带修掉一处判据主语**：`imgnative_color` 里的 `frame_is_normalized` 原先问的是 **ROI 视图**，
   而视图的 `channels()/depth()` 与父矩阵**同解**（实测），所以那既不是漏判也不构成事故；改问源帧、且挪到 `resolve_region` **之前**，是因为谓词的名字与注释谈的都是"帧"—
   — 判据该写在它自己声称的主语上，顺序本身也是判据的一部分（非归一帧上"某个 region 合不合法"是另一套尺寸语义）。这道门附带钉住一条此前没写明的口径：
   `imgnative_match` 的拒收分支是**早退**——不写出参，所以调用方**不能拿 out_match 当"没命中"判**（可能还是上次调用的残留），判据只有 status；真实调用链正是这么做的（`images_jni.cc` 的 `matchNative` 只看 rc，
   `NativeImageAnalyzer.match` 同样 rc 优先）。NDK 交叉 `-fsyntax-only` 与这道 host 门**互不替代**（前者管 aarch64 能编、后者管判读对），真机红测仍是最后一关。它已接进 `.github/workflows/image-native.yml`（job `host-image-semantics`，
   与 build-opencv 同文件、paths 同源，且**排在构建前面**：判读先红一个 5–10 分钟的，不占满 15–30 分钟的构建槽；OpenCV 按 VERSIONS.env 同 commit 拉取并对表，
   tarball 会让对表退化成口号）。**native 侧已接**：`libopencv.so` = OpenCV 4.14.0（`core+imgproc+imgcodecs+features2d+flann`，`BUILD_JPEG/BUILD_PNG/BUILD_ZLIB=ON` 树内源码、`WITH_KLEIDICV` 默认 ON）静态链接进我们自己的桥面 C++（`imgnative.cpp` 纯计算核：
   `extern "C"` 十入口、帧表自管、`cv::Exception` 就地折叠）；装载面 `images_jni.cc`（全仓图像侧唯一 `#include <jni.h>`）+ Kotlin `NativeImageAnalyzer`/`JniOps`（住 `:platform:system`，
   零 android import；`System.loadLibrary` 失败即不构造）。构建在 `node-runtime-build/scripts/build-opencv.sh`（按 commit SHA 固定、kleidicv pin 对表、16KB LOAD/NEEDED 白名单门禁、
   SHASUMS256 旁 kleidicv ON/OFF 审计行），由 `.github/workflows/image-native.yml` 在 Actions 跑；`PlatformWiring.of` 一行接上（so 缺位 → `ERR_NOT_IMPLEMENTED`，不塞内存替身）。

  - `:domain` `ImageAnalyzer` 加第 6 方法 `ingest(width, height, rgba)` —— 把**已在内存里**
    的紧密打包 RGBA（`width*height*4`，R,G,B,A 序）登记进它自己那张帧表，回句柄。
    `decode` 与 `ingest` 共用同一个 `nextRefId`，这就是"两帧互认"的结构证据；
  - `:platform:capabilities` `ImagesNamespaceHandler` **去掉自管的三张本地表**（`ids`/`live`/`sizes`）
    退成无状态转接 —— 曾经它靠"两个计数器各自从 1 起、每次 decode 各加一"的隐式不变式
    与 SPI 对齐，那种对齐是漂移面不是契约；
  - `ScreenshotSource` 收一个可选 `analyzer`：给了就把截出的帧 `ingest` 进 SPI 的表、
    `recycle` 转 `ImageAnalyzer.release`；没给（so 缺位）才退回本地表 —— 此时 `images`
    命名空间根本没注册，两个号段不可能相撞；
  - `AutoScriptAccessibilityService.frameOf` 从 **JPEG 压缩改成原样 RGBA 像素**。
    曾经那段 `Bitmap.compress(JPEG, 90)` 是死重：`ProducedFrame.bytes` 全仓只被
    `isEmpty()` 看过一眼，而有损 JPEG 一旦真进 `findColor`，"按分量精确判定"的承诺
    就吃到压缩伪影。改走 `Bitmap.getPixels(int[])` → 打包 `0xAARRGGBB` → 拆 RGBA：
    **不猜 `copyPixelsToBuffer` 的字节序**（那是 Skia 缓冲的原样拷贝，文档没承诺通道序；
    猜反了就是 r/b 互换，四道门全绿照样错）；
  - 像素契约：ingest 收 RGBA、native 进帧表时做一次 `cvtColor(RGBA→BGRA)` swizzle 并
    **拷出自有缓冲**（不持有调用方的 `ByteArray`）—— 帧表不变式仍是 4 通道 BGRA。
  钉子：`host_ingest_test.cpp`（通道序 + **跨来源同表**：`imgnative_decode` 出的模板帧与
  `imgnative_ingest` 出的截屏帧共用 `g_next_ref`，release 一个另一个照常可用）、
  `ImagesNamespaceHandlerTest` 的跨命名空间用例、`NativeImageAnalyzerTest` 的 ingest 三例、
  `images.test.cjs` 的互认用例。§7.7 表里 `captureScreen → findImage` 的数字仍是待实测口径。
  三条落选出路留档：(a) `screen.save` 有损、(c) `decodeBytes` 让 bytes 过桥 —— 都切掉被契约
  承诺的性质（见 §18 第 8 项）。

### §8.3 （自 `08-execution.md` 外迁）

**P0 已落地的收敛子集**（代码是事实来源，别按上图臆造）：
- `:domain` 的 `EngineStateMachine`（合法转移表 + `kill()` 归因：`REQUESTED → STOPPED`，其余原因 → `CRASHED`）**已真正驱动池侧**：`PoolSlot` 持一个状态机实例，与「占槽/
  回收」同生共死 —— 夺槽 `markBusy` → `BOOTING`，`execute` 拿到 `EngineRunReceipt` → `RUNNING`（`PoolSlot.markRunning`），`quiesce` → `QUIESCING → STOPPED`（stop 超时兜底杀掉则归 `REQUESTED`），
  `recycle`/`forceFree` 按传入 `KillCause` 归因（watchdog/OOM → `CRASHED`）后回收回 `IDLE`。槽位侧的三态投影 `SlotState{FREE, BUSY, QUIESCING}` 仍在（§8.2 记账要用），但不再与 `EngineStatus` 脱节。
- 为什么状态机挂 `PoolSlot` 而不是另建一张表：状态机必须与占槽/回收同生共死，分表就要处理「表里有行、槽位已 FREE」的孤儿；`FixedEnginePool` 的 stateLock 已保证读写与记账原子。非法转移抛 `IllegalStateTransition`（响亮失败，不静默修状态）。
- **状态对照已落地**（`RuntimeController.statusOf(runId)` / `runStatuses()`）：同时读宿主自报（`ScriptEngine.status()`）与池侧投影（`PoolSlot.status()`），分歧如实进 `RunStatus.drift`；`EngineWatchdog.Tick.drift` 把它带进每轮监督清单。**只报分歧、不改状态** —— 校准不是替某一侧抹平差异，而是让差异先可见。
  合法组合白名单：池 IDLE ↔ 宿主任意（已回收，宿主说什么都不算异常）、BOOTING ↔ 宿主 IDLE/BOOTING（execute 未返回）、RUNNING ↔ RUNNING、QUIESCING ↔ QUIESCING/STOPPED、池侧 STOPPED/CRASHED ↔ 宿主任意。宿主读不到（探针抛错/引擎已死）→ `host = null` 且**不算 drift**：那是「量不到」，不是「不一致」，混在一起会让真分歧被噪声埋掉。
- **裁决已落地**（`EngineWatchdog` 的 drift 连段 + `KillCause.DRIFT`）：单轮分歧只是真机的窗口期常态（宿主刚推 STOPPED、池还没 quiesce 完），连续 `driftKillThreshold` 轮（缺省 3 轮 ≈ 1.5s）还对不上才是真分裂 —— 此时经 `RuntimeController.killRun(runId, DRIFT)` 杀掉重来，不猜哪一侧对（校准不是替某一侧抹平差异）。
  `Tick.drift` 照常每轮记账（谁看见谁处理），`Tick.driftKilled` 单独列出分歧杀供诊断区分"病死"（三路判定）与"分歧杀"；连段中间弥合一轮即从头数，失踪/被杀的 run 清零（防 runId 复用背旧账）。`DRIFT` 归 `CRASHED`（`EngineStateMachine.onKill`：非 REQUESTED 一律 CRASHED），与"管理者主动停"（REQUESTED → STOPPED）区分"自杀"与"他杀"。
  阈值是 `EngineWatchdog` 构造参数（`DEFAULT_DRIFT_KILL_THRESHOLD`），装配层可配。
- 上图里的 `SUSPENDED`（多源计数）与 `PENDING` 在 P0 **均不存在**：`EngineStatus` 枚举里没有 SUSPENDED，`awaitCompletion` 只把 RUNNING 判活（看门狗口径一致，见 §8.4）。**任何依赖 SUSPENDED 的设计（暂停恢复、诊断暂停计数）仍然没有代码基础。**

### §8.4 （自 `08-execution.md` 外迁）

**P0 已落地**：`:app-service:runtime` 的 `WatchdogPolicy` 是三路纯判定（`WatchdogSample{pid,status,heartbeatMillis,cpuPercent,rssBytes} → Healthy | Kill(cause, reason)`），默认阈值：心跳 500ms × 连失 3 次、CPU ≥95% 持续 30s、RSS ≥512MB；只对 `RUNNING` 判活；`RuntimeController.judge()` 委托它，裁决落点 = `killRun`/`killAll`（kill 权威 §4.1）。
**采样已落地**（`ProcessMonitor`，`:app-service:runtime`）：`/proc/<pid>/stat` 与 `/proc/<pid>/status` 的读取 + 折算，产出同一份 `WatchdogSample`，交给 `WatchdogPolicy` 判定。诚实口径写死在实现与单测里：CPU 分子 = 两次 `utime+stime` 差（jiffies → ms），分母 = 调用方给的墙钟间隔，**不折算单核**（多核满载必须看着就 >100%，否则漏杀 Promise 风暴）；
`comm` 含空格括号时从**最后一个 `)`** 之后切字段；首采样 / 换 pid / 时钟回拨 / 计数回绕一律回 0.0%，不给假差分；`/proc` 不可读 → 整份样本回 null（按「无法度量」处理，不猜健康），`status` 读不到只丢 RSS 这一路，不作废 CPU 样本。**本类只采样不裁决**（裁决仍归 `WatchdogPolicy`，kill 仍归 `RuntimeController`，runId→pid 归属表仍归 `:app`）。

**调度循环已落地**（`EngineWatchdog`，`:app-service:runtime`）：§8.4 的三路分工终于有了周期性调度者 —— `RuntimeController.watchAnchors()` 交出在途执行的 `WatchAnchor{runId, receipt.pid}`（**pid 取 `EngineRunReceipt.pid` 快照，
不是 `ScriptEngine.pid` 当前值**；槽位复用后两者会分叉），`ProcessMonitor` 按 pid 分别记账采样本，`RuntimeController.judge()` 裁决，`Kill` 落 `killRun(runId, cause)` —
— 判据、采样、执行三方仍是三个类，调度者只负责「到点把三者接起来」，不自己长判定口径。`AppShell.assemble` 交出 watchdog 实例与 `ProcessMonitor`/`heartbeatMillis` 注入缝，
`startWatchdog(scope)` 之前不转；生产路径由 `AppShellKit.assemble` 在装壳时就转起来（§4.1 的真实调用点）——域缺省是壳自己持有的 `SupervisorJob`（`AssembledShell.close` 先停轮转再关池与持久句柄），
调用方也可传自己的域（那就自己负责停：`EngineWatchdog.start` 的 KDoc 写死了这条所有权规则）。
- **采样周期 = `heartbeatIntervalMillis`**（`WatchdogPolicy` 那条因此从 private 变 public）：周期长于心跳阈值会把活引擎判死（500ms×3 的阈值配 3s 轮询 = 假阳性），controller 的这份 policy 经 `RuntimeController.watchdogPolicy()` 原样交给 watchdog —— **只有一份阈值**，不在装配层另造一个。
- **pid 终结即忘**：无论正常结束、被 watchdog 杀掉还是本轮采样后不在途，`EngineWatchdog` 都调 `ProcessMonitor.forget(pid)`。Linux 复用 pid，不遗忘等于让新进程背旧 CPU 基线（虚高 → 误杀）；pid 落到别的 runId 时 CPU 历史整段清零，同理。
- **不另建 pid→runId 表**（§8.4 原口径不变）：归属表只有一份，就在 `RuntimeController` 的在途账里。`:app` 侧再抄一份必然漂移（stop/kill 路径不止一条），`watchAnchors()` 是它的只读投影。

**心跳一路已接线**（§8.4 缺口② 补齐）：`HeartbeatLedger`（`:app-service:runtime`）是心跳的宿主侧收单方，`RuntimeController.heartbeat(runId, seq)` 是它的桥侧入口，`RuntimeController.heartbeatMillis(runId)` 是看门狗的问讯口 —— `EngineWatchdog` 缺省就问 controller 那份账本（`AppShell.assemble` 的 `heartbeatMillis` 缺省 null = 装配时接真账本；
显式传 `{ null }` = 明示这一路不接，看门狗如实记 `Tick.noHeartbeat`）。JS 侧 `engines.heartbeat {runId,seq}` + `startHeartbeat(runId)` 定时打点（`unref` 定时器，不保活事件循环）。
- **序号即真伪**：账本只认**递增** `seq`。同/旧 seq 一律拒收（只累计 `staleBeats()`，不刷时间戳）—— 否则宿主张力下积压的旧心跳会把一个**已经死了**的 run 一直喂成活的，那正是心跳这一路要抓的形态。
- **从未打点回 null，不回 0**：0 会被当成「刚刚打过」，失联判定永不触发；null = 量不到，看门狗据此记 `noHeartbeat`。
- **与 run 同生共死**：`stop`/`killRun`/`killAll`/`settleDone`/`settleKilled` 五条终结路径全部 `forget(runId)`。不遗忘 = runId 复用时新 run 背上一段「假年轻」，失联判定被推迟到下一次自然打点。
- **无主心跳不建账**：`RuntimeController.heartbeat` 先验在途再落账本 —— 不在途 runId（已结算/从未存在）回 `false` 且不记账。否则迟到/重发的心跳会在复用 runId 上复活旧账，或把活 run 的账顶出记账上限；桥侧未知 runId 仍 Ok `false`（不是调用方错误，不 4xx），JS mock 宿主复刻同一口径。
- **仍不伪造**：拿 watchdog 自己的轮转周期当心跳依旧是禁止的 —— `while(true)`（心跳活着、CPU 打满）只靠外带差分抓得到。

至此 §8.4 三路判据、采样、调度、心跳打点全部闭环。**pid 半边已接线**：`NodeProcessEngine`（`:engine:node-process`，Kotlin spawn）的 `EngineRunReceipt.pid` = spawn 瞬间真子进程 pid（`NodeProcessEngineRealSpawnTest` 实测 >0 且非自身；`:app` E2E 经 `watchAnchors` 锚点 pid>0 复验），看门狗 `/proc` 采样锚点在 spawn 路径上是活的。
**心跳的生产链已备**：spawn env 下传 `AUTOSCRIPT_RUN_ID` → kBootstrap 500ms 自动打点 / 桌面脚本显式 `startHeartbeat`，E2E 实测 `heartbeatMillis(runId)` 落账非 null；桥监听 `BridgeSocketListener` 亦已接（abstract 绑定 + uid 门禁 + `NewlineFrameServer` serve，JVM 假缝单测；bind 失败 = 离线降级不注入名，见 §7.5/§7.8）。
addon 的 JS 消费面亦已落（facade `attachNative()`，§7.8）。**设备面只剩真机联调**（jniLibs 三件套 + addon 资产落位 + facade dist 随包与打包入口 attach 接线 2026-09-24 均已落，见 §12.4/§19 切片路线）未落；在那之前，真机上当前生效的是「未 spawn / 宿主不给 pid → noPid」「宿主不打点 → noHeartbeat」两条量不到路径 —— 这由 `Tick` 的三个清单如实区分，不是一个笼统的 `unmeasurable`。

### §8.5 （自 `08-execution.md` 外迁）

**持久形态已落地**：`JournalFileStore`（意图日志）+ `FileRunArchive`（运行档案）同用一套 jsonl 行格式（共享 `JsonLine`），`force(true)` + 启动 replay + 半行容忍；
两文件分开存是刻意的 —— 键不同（intentRunId vs engineRunId）、只写一侧的孤儿在格式上才可见。

孤儿结算两条路（都只在 `recoverUncommitted` 里跑，运行期绝不扫——那会把正在跑的执行误判成孤儿）：
- **逐 intent**（`settleOrphanArchive`）：本次要 reopen 的遗留意向，其关联档案里还没终态的记录 → 如实 `CRASHED`（宿主死时没结算）；
- **全档空档**（`settleVoidArchive`）：`log.commit` 与 `recordLink` 不同事务，中间崩溃会留下「意图行已终态、档案停在 RUNNING」的孤儿 —— 它挂不到任何未 COMMIT 行上，逐 intent 那条路永远看不见。恢复刚起来时引擎池必空，档案里所有非终态记录都只能是上一进程遗物，此时按 `unfinished()` 自报统一补 `CRASHED`（无 link 的记录跳过：独立执行不属意图日志管辖）；
  link 原样保留，任务中心仍可按 IntentRun 追到这条失联记录。

### §8.6 （自 `08-execution.md` 外迁）

- **P0 已落地**：满池排队**默认有界**，不再有"默认无限等"这条路。`queueTimeoutMillis` 显式覆盖优先；不传则按触发源分级取默认上限（`ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS`）：
  `ENGINE_INTERNAL` 15s（满池下的跨引擎调用是"持有者等后来者"的嵌套形态，必须最先爆，否则变跨引擎死锁）、`USER_CLICK` 10s（人盯 UI，等不及就如实 Cancelled，
  不让按钮原地转圈）、`INTENT_BROADCAST`/`EVENT` 60s（外部涌入本应容忍排队）、`TIMED` 120s（守时任务已承诺"亮屏+解锁保底 + 可能偏差"，2 分钟只为满足铁律 3，
  不追求抢跑）。分级表是**注入缝**（构造函数参数），装配层可换成自己的口径；`queueTimeoutMillis` 传 0 视为漏配，构造即 `IllegalArgumentException`——0 等于"永不允许排队"，
  与"绝不静默丢任务"相反。
- **P0 已落地（deadline 记账）**：`PendingRun.deadlineMillis` + `isExpired(now)` 记下"本次投递的到期时刻"，与 dispatcher 的排队上限**同源但不同职**——dispatcher 那侧管"在途排队等不等得起"，deadline 管"宿主重启之后这条意向还值不值得投"（崩溃恢复面对的是另一件事：进程死过一次，用户早走了/外部事件早凉了）。
  期限写在 `IntentRun.deadlineMillis` 上随 RUN_START 行落盘，`reopen` 原样带到新行（恢复重投不得变期限，否则同一意向两套到期口径）；`Scheduler.onTrigger` 按 `排期时刻 + deadlineFor(触发源)` 填，`recoverUncommitted` 遇过期意向照样 `reopen` 封口记账，但**不再 dispatch**，直接 COMMIT [RunOutcome.Cancelled]（`RecoveryRecord.expired` 标出，任务中心按 runId 读到"为何没跑"——绝不在恢复路径里静默跳过）。
  两处口径同源由装配层保证：`Scheduler` 的 `deadlineFor` 与 `ControllerRunDispatcher.queueTimeout` 默认同喂一张分级表（`DefaultDeadlines` 与 `DEFAULT_QUEUE_TIMEOUTS` 数字一致，前者是缺省值不是契约，装配层可各自覆盖）。
- **P0 已落地（调度侧停止与收口）**：`Scheduler.lastHandle`（`EngineStopHandle{idLink, name, runNonce, stop}`，§12.3 engines.exec 的调度侧投影）只在 dispatcher 真的产生了引擎执行（`DispatchReport.link != null`）时持有 —
  — 门禁拒绝/排队超时/启动失败没有可停的东西，不持有假句柄；停止入口由 dispatcher 经 `DispatchReport.stop` 填权（`:app` 的 `ControllerRunDispatcher` 在 start 成功时绑定 `RuntimeController.stop` → 池四步 quiesce；
  三条未产生执行的早退分支回 null stop；已结算后调用落 AlreadyGone 幂等 no-op，永不升级为 kill）；`stopLastRun()` 转发句柄的 stop（成功不清句柄，停止幂等），
  `canStopLastRun()` 从句柄现算；`sink()` 撤销全部触发器并置位（此后 `onTrigger` 早退）；`quiesceThenStop()` = sink → 停最近一次 run → 返回句柄供归档（§13 铁律 4 的调度侧部分）。
  线程契约诚实声明：lastHandle/sinking 读并发安全，写仅发生在 `onTrigger` 内（装配层负责把五类触发源串行化）。
- **P0 已落地（执行侧急停）**：`RuntimeController.forceStopAll(cause)` —— 进程级急停的显式入口（应用被杀/系统回收/测试收口），与请求驱动的 `killAll` 区分（killAll 是裁决/
  停全部的落点，在途表经 guard 串行收走；forceStopAll 只做杀全部 + 清在途表 + 忘心跳，不走请求语义）。**装配层有序收口已落地**：`AppShell.shutdown(cause)` = `scheduler.quiesceThenStop()`（先 sink 拒收新投递、
  再停最近 run）→ `controller.forceStopAll(cause)`（再杀全部槽位并复用）。顺序不可反：先杀后停会在调度不知情窗口继续投递。两步都幂等；返回调度侧被停句柄供归档（无句柄时为空，
  不假装停过）。
- **P0 已落地（无人 await 的 run 自带期限，2026-10-01）**：
  原先的诚实边界是「引擎侧 `waitCompletion` 超时不发起的场景还没人管——在途 run 没人收尾时 watchdog 是唯一兜底」，而看门狗三路判据全看**进程表现**（心跳/CPU/RSS）：一个心跳正常、CPU 空闲、RSS 很低的长跑脚本三路都判它健康，**没有任何一路收得住它**。收口判据不是「有没有声明超时」而是**发起方等不等**（`PoolAcquireRequest.timeoutEnforcer`，见 §8.1）：
  - `AWAITER`（缺省）：调度链路的 `ControllerRunDispatcher` 自己 `awaitCompletion`，超时 → `killRun(REQUESTED)`（归 STOPPED）——这条本来就有人收尾；
  - `WATCHDOG`：桥 `engines.exec` 拿到句柄即返回、没人 await 终结 —— 期限 `startedAt + scriptTimeoutMillis` 经 `RuntimeController.WatchAnchor.deadlineMillis` 交给看门狗，到点落 `KillCause.TIMEOUT`（归 CRASHED：期限到期是强制收账，不是调用方主动停）。
  期限线在 `EngineWatchdog.tick()` 里**先于** pid/心跳那两条「量不到」分支判 —— 期限不依赖任何度量，它是发起方声明的事实；排在后面会让「宿主不给 pid」或「心跳未接线」的 run 连期限都够不着，
  恰好退回「没人收尾」那一类。同时 `engines.exec` 的 `timeoutMillis` 由可选改为**必填**：缺席/`null`/`<= 0` → `ERR_INVALID_PARAM`（构造期 `require` 同款守卫）——
  缺省值在这里没有诚实来源，编一个（30s？5min？）等于替脚本静默决定它能跑多久（与 `queueTimeoutMillis` 传 0 视为漏配同一条纪律：响亮失败）。
  **仍待覆盖（诚实边界）**：期限只覆盖**声明了期限**的 run，且只在看门狗轮转真的在跑时有效（生产轮转挂 `AppShellApplication` 的 SupervisorJob 域）——轮转没起 = 没有期限线；
  池空退避（`idlePollMillis`）期间新起的 run 最坏晚一个退避周期才被看到。另外 `runNonce` 幂等只保证**外部副作用**不重复，不保证「期限内跑完」——期限到点即杀，
  写到一半的副作用仍由执行体自己的幂等键兜底。
- **P0 已落地（Android 触发侧）**：`AlarmSchedulerProvider`（`:app` 装配层）把 `SchedulerProvider.registerTrigger` 翻译成闹钟——预拉提前量 `wakeAheadMillis` 是契约字段（60s，
  测试与调用方同一份值，不藏常量）；**提前量只向前推、不向后扯**（排期已到即夹到当前时刻，ROM 对负延迟处置不一）；`canScheduleExact` 为假时降级 `setWindow` 且**记账**（`degradedTasks()`，
  `taskId → 排期时刻`），能力中心据此标注「可能偏差」——**不静默降级**。框架调用（真 `AlarmManager`）在 `AndroidAlarmPort`，本类**无判断**：taskId → `KeyStableHash` 定 requestCode（同 taskId 恒同，
  重复 arm 是替换）、`setExactAndAllowWhileIdle`/`setWindow` 两个调用点、取消 = `alarmManager.cancel` + `pendingIntent.cancel`（两个都要）。`PendingIntent` 在 API 31+ 必须 `FLAG_MUTABLE`（系统要填 `EXTRA_ALARM_*`）。
  回投侧是**静态注册**的接收器 `AlarmReceiver`（精确闹钟响时进程可能已被 ROM 杀掉，`registerReceiver` 收不到）走 `goAsync()` 在广播窗口内把 taskId 经 `AlarmDispatch` → `SchedulerAlarmRoute` 送回 `Scheduler.onTrigger`（TIMED 来源 + 闹钟真实排期），
  于是 runNonce/意图日志/dispatcher 口径与手动触发完全一致。**装配前/后的漏投不静默丢弃**：没接路线的闹钟进 `AlarmDispatch.missed()`（同一 taskId 只留最新一条，
  `drainMissed()` 清账），`AppShellApplication.missedAlarms()` 供能力中心如实呈现「闹钟已响但调度未就绪」。
- **P0 已落地（屏幕门禁的生产实现）**：`AndroidScreenGate`（`:app`）——`SCREEN_ON` 在两个系统查询缝（`interactive` = `PowerManager.isInteractive`，`deferWakeLock` = 持锁方）任一为假时**如实 `Deny`**，不降级成「锁屏也跑」（那条路径的表现是「任务成功、实际什么都没发生」）；`SCREEN_OFF` 先经 `ScreenOffGuard` 收起画面类能力再放行（无障碍 + 网络在锁屏下真实可用）；
  `ANY` 放行。两条缝的值由 JVM 单测注入，判断逻辑因此可测而不必 Mock 框架对象。**`AllowAll` 与 `AndroidScreenGate` 在 `ANY` 上必须同结论**（`AndroidScreenGateTest` 有断言守着），否则同一条任务在单测里放行、真机上被拒，差别只在现场暴露。

- **P0 已落地（任务中心读口与屏，2026-09-24）**：`HostSummary` 增 `taskCenter()`（挂起：读任务注册表 + 运行档案两个持久寄存器；**读失败抛** —— `:ui` 如实显示「读任务失败」，
  而不是冒充「一条任务都没有」，那是「读成功且真没登记过」的另一种事实）→ 拼装 `TaskCenterRead.snapshot`（`:app` 壳装配包，纯 JVM 可测，**不自带 IO**：
  取数由调用方注入）：`TimedSchedule` 三态 → `ScheduleSpec` 三态**逐字段**映射不聚合（Cron 也保留成行 —— 不可能日期/坏行算不出下一跳时留名不续排，任务不从列表凭空消失）、
  `ScreenGuarantee` → `ScreenRequirement` **按名对表**（不用 `ordinal`；`TaskCenterReadTest` 有两边枚举同集断言，调度器加值先红再谈映射）、停用任务**不问下一跳**（问了也会得到答案，
  而那个答案会让人以为停用任务还会跑）、恢复账 `total`/`expired`/`failureText` 三笔分开且**失败时 `retried=0`**（`total-expired` 会把失败报成成功）→ DTO 住 `:domain` 的 `TaskCenter.kt`（`TaskCenterSnapshot`/
  `ScheduledTaskRow`/`RunRow`/`RecoveryRow` —— 调度器类型只在 `:app`，呈现层照旧只依赖 `:domain`）→ `:ui` 的 `TaskCenterScreen` + `TaskCenterState`（纯状态 DTO，JVM 可测）：
  三页签之二、**没读到 ≠ 一条任务都没有**（`NOT_LOADED` 与 `failed` 分开且保留原异常文案）、下一跳为 null **不编时间**（停用与 Cron 算不出两义由 `enabled`/
  `schedule` 分辨）、未结算执行文案点破「**不是此刻正在跑**」（`unfinished()` 只增不减，这栏只可能是上一进程遗物）、降级任务标「可能偏差」。刷新与能力中心同构：
  回前台/切页签现取一次，读失败不自激重读。读切片落地后，登记/取消/立即执行随操作面接上（见下条）。
- **P0 已落地（任务操作面：登记/取消/立即执行，2026-09-24）**：写口走 `HostSummary` 三方法 —— `registerTask(TaskRegistration): String`（回分配到的 id；`:domain` 的 `TaskRegistration` 是**纯数据** DTO，
  校验不在 DTO 里）/ `cancelTask(taskId)`（幂等：tombstone 先行，ghost id 照样返回成功 —— 用户视角"它已经不在了"）/ `runTaskNow(taskId)`（`TriggerSource.USER_CLICK`，
  **挂起到本次执行结算**再返回）。两个实现方都收口：`AppShellApplication` 壳未装配即抛（不冒充成功），`FakeHost` 同步长出 UOE（替身纪律：HostSummary 长一个成员，
  替身同批跟上，否则 `:ui` 单测编译断）。语义闸门唯一落点是 `:app` 的 `TaskCenterOps.toScheduledTask` —— 与桥侧 `WorkManagerNamespaceHandler` **同规则两侧各测**：
  空串三字段点名拒绝、`Once.delaySeconds<0` 拒、`Daily` 靠 `TimedSchedule` 的 0..23/0..59 require 拒、`Cron` 经调度器 `CronTab.parse` 校验（非法点名哪一段，两侧非法样本同源）、
  timeout≤0 拒、非法时区拒、`ScreenRequirement` **按名对表** `ScreenGuarantee`（同集断言先红）。形状解析（整数文本、trim）收在 `:ui` 的 `RegistrationForm`，语义规则不复述一份（两处规则必然漂移）。
  诚实边界三条：(1) **`runTaskNow` 不哑火** —— `onTrigger` 对 sinking/缺席任务静默 return，所以 `AssembledShell.runTaskNow` 先查再触发，查不过就抛（检查与触发间的 TOCTOU 窗口已接受并写进 KDoc）；
  不改 `onTrigger` 签名（~28 处调用点全是语句位，改返回型会让非 void `@Test` 静默跳过）；(2) **回执不说脚本成败** —— `onTrigger` 只到结算，成败在意图日志/
  控制台，回执写「执行成败见控制台」；UI 侧 `TaskCenterState.opInFlight` 挂起期间禁用全部操作按钮（防双击双投）；(3) **Once 立即执行即出册** —— 调度器 `finally` 对 Once 终态化，
  卡片事前标「一次性任务：执行过后自动移出注册表」，回执点破「已执行并出册」，刷新后卡片消失是排期语义不是被取消。操作失败**不清任务清单**（`opError` ≠ `loadError`，
  `copy` 保留 `tasks` —— 抹掉会让用户以为任务全没了）；`opNotice` 只由成功写入，`of()`/`failed()` 归零（现取纪律），`performTaskOp` 刷完表再盖回。取消走 `AlertDialog` 一次性确认（误点的代价 = 手工重登记）。
  登记表单**缺省全空**（cron 格缺省 `0 9 * * *`）—— 误提交过不了 `:app` 闸门，不产生幽灵任务。UI 侧操作是 `HostSummary` 的**独立写口**，不经 `workManager` 桥（桥是脚本侧命名空间；
  宿主自己的操作面不该绕进程一匝）。

### §8.7 （自 `08-execution.md` 外迁）

- **P0 已落地（`:main` 侧 FGS + 真唤醒锁，2026-09-23）**：三个可分离的缝，判断全在 JVM 可测面，系统接触面各收在一个类里。
  - **`WakeLockOps` / `WakeLockLedger`**（`platform/system/.../system/WakeLock.kt`，步骤 6d 自 `:app` 迁入；`:app` 经 `ForegroundKeeper`/`lockHeld()` 读）：`AndroidWakeLockOps` = 真 `PARTIAL_WAKE_LOCK`（`setReferenceCounted(false)`，
    acquire/release 异常一律吞成 `false` + 日志）；`WakeLockLedger` = **token 引用计数 + 超时自动释放**——`hold(token, timeoutMillis)` 只在**首次**（账本为空）时真取锁，
    **取锁失败不记账**（"记了账却没锁"是假绿之源）；`release(token)` 用"先查存在再删"（超时 token 的值为 null，照样能释放）；`sweep()` 释放到期项并返回名单；
    `isHeld()` = **账本非空 ∧ `ops.held`**——两侧任一说"没有"就一律算没锁。token/超时的形状就是给 P1 `power_manager` 预留的插口（引擎进程请求 → FGS 加锁走同一账本）—
    —该命名空间已落地（见上条），账本语义零改，兑现了预留时的承诺。
  - **`ForegroundOps` / `ForegroundServiceBase` / `ForegroundKeeper`**（`app/.../shell/ForegroundOps.kt`、`ForegroundKeeper.kt`）：`AndroidForegroundOps` 管 `startForeground`/`stopForeground`，API 34+ 传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，
    通知走 `ForegroundNotifications`（渠道 `autoscript.foreground`）；`ForegroundServiceBase` 是**服务不自装配**的落点——服务的 `onStartCommand` 按 `ACTION_START`/`ACTION_STOP` 分支，
    Keeper 从进程级邮箱 `ForegroundHost.keeper` 现取（服务**不持有** Keeper/账本，避免 service → 根包成环；`START_NOT_STICKY`：重启路径没有 Keeper 上下文，续期统一走 `Application.onCreate` 的同一条装配路径，
    与 `BootReceiver` 同纪律）；`ForegroundKeeper.isActive()` = **`ops.foregroundRunning` ∧ `wakeLocks.isHeld()`**——"请求过" ≠ "生效了"；`stop()` 在系统仍报前台时返回 `false`（保持 drain 路径活着，
    不假装停干净），随后由 `renew()` 兜；`renew()` = sweep 到期锁 → 账本还持着而服务掉了就补拉 → 账本空了但服务还开着就停掉；守护 ticker（15 分钟）只做这两件事，
    `Throwable` 全吞（`scheduleWithFixedDelay` 一旦抛出就静默停摆）。
  - **屏幕门禁收口**：`AndroidScreenGate.of(...)` 的 `deferWakeLock` 生产实参已是 `ForegroundKeeper::lockHeld`（= 账本 `isHeld`；`AppShellApplication.screenGateOf`，根包经此读口不碰 platform 类型）。**§8.7 原来那条"恒真 = 明写的待接"就此作废**：现在熄屏 + `SCREEN_ON` 的真实表现是「锁没拿到就 `Deny`」，且 `Deny` 的判词与账本一致——不是靠恒真放行后再指望系统。
  - **如实呈现**：`ShellSummary.keepAliveActive`（`:domain`，**不带默认值**——每个产出方必须显式回答"保活到底生效没有"，漏填编译期就炸）由 `AppShellApplication` 填 `keepAliveActive()`；`:ui` 首屏据此直说「保活已生效」/「保活未生效：熄屏的亮屏任务会被拒绝」——这是"任务为什么没跑"的直接答案，不藏在二级页。
  - **诚实边界**：`onTerminate()` 在真机上**从不被调用**（进程死时系统自行回收 wakelock），它存在只为测试/模拟器收口 + 给"谁来停"一个代码落点；保活未生效时 `AppShellApplication` 记 `Log.w` 并在 UI 上显示红色，**不降级成"锁屏也跑"**。

### §9.5 （自 `09-capabilities.md` 外迁）

- **已接生产（`:app` 侧）**：`AndroidPermissionGates`（`AndroidCapabilityProbes`(6 事实真查询) → `AndroidSystemStateReader`(事实→三态映射，判据唯一出处) + `AndroidGrantLauncher`(`pageFor` 能力→页 + `specFor` 页→Intent 规格，
  字面量单测锁死) → `permissionCenterOf`(纯拼装，不判断)；住 `com.autoscript.shell` 装配包，`android..` 直连与 `AndroidAlarmPort` 同例）→ `AppShellApplication.permissionCenter()`（懒建缓存，
  查询本身不缓存）→ 能力中心 UI；降级账本读口 `degradedAlarmTasks()`（`AlarmSchedulerProvider.degradedTasks` 只读视图）。门禁从"编排可测"推进到"生产有人问系统"。
- **能力中心读口已接（2026-09-23）**：`HostSummary` 增 `capabilityCenter()`（挂起：三态是**现问系统**的结论，含 root 探测的 IO 切换）与 `openCapabilitySettings(capability)`（**无判断**，
  转给 `PermissionFacade.openSystemSettings` —— 呈现层因此不必也不许碰 `Settings`/`Intent`）。快照 DTO 住 `:domain`（`CapabilityCenterSnapshot`/`CapabilityRow`，后者带 `canRequestGrant` = `CapabilityLifecycle.canRequestGrant` 的投影：
  **按钮显隐的判据唯一出处仍在 :domain**，呈现层不许自己写 `state != GRANTED`）。拼装在 `:app` 的 `CapabilityCenterRead.snapshot(facade, degradedAlarmTaskIds)`（**纯 JVM 可测** —
  — `Application` 在 JVM 里构造不出来，而"全量枚举能力 + 逐项问三态 + 配同一份 guideText + 带上降级任务账"这段判断是那一屏的全部事实来源）。**全量枚举**（`Capability.entries`）不是"只列异常项"：
  能力中心要能回答"我到底有哪些能力"；**读失败抛**，`PermissionCenter.state` 只把 reader 查询崩收敛成 `DEGRADED`（可用性未知即受限），更外层的抛穿到 `:ui` 如实显示 —
  — 吞成"全 DENIED"会让用户以为授权全丢了。呈现侧 `:ui` 的能力中心（`CapabilityScreen` + `CapabilityCenterState`，纯状态 DTO，JVM 可测）：三态各有中文说法（可用/
  降级可用/被拒绝 —— "用户该做什么"逐态不同）、引导文案**原样透传**（改写过的文案会与系统里的真实路径漂移）、降级中的定时任务**单列一段**（§8.6 承诺的「可能偏差」标注，
  不是权限问题）、**没读到 ≠ 一个能力都没有**（`NOT_LOADED` 与 `CapabilityCenterState.failed` 分开，失败时保留原异常文案）。刷新时机：回前台/切到该页签时经 `LaunchedEffect` 重问一次 —
  — 用户从系统设置页授完权回来看到的是**刚问过**的结论，而不是离开时那份缓存（后者正是"授权了但界面还说没授权"的来源）；读失败不反过来触发重读（不自激）。

### §9.6 （自 `09-capabilities.md` 外迁）

- **五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）已全部落地语义层**：SPI 与 DTO 住 `:platform:system`（`SystemHostContracts.kt` + 五份能力契约，2026-09-30 步骤 6a 自 `:domain` 迁入；
  `DialogHost` 六型留 `:domain`），handler 住 `:platform:system`（`SystemNamespaces.kt`，`dialogs` 例外在 `:platform:capabilities` 的 `DialogsNamespaceHandler`），`AppShell.assemble` 的 `systemHandlers` 束五个字段各自可空 —
  — 未接线的那一个如实回 `ERR_NOT_IMPLEMENTED`，不伪造可用。`app.launch` 返回 `false` / `app.currentPackage` 返回 `null` 是**诚实答案**（前台无包名、启动被系统拒），
  不抛异常；`dialogs` 的 `mode` 三态 `auto`/`overlay`/`notification` 由 handler 解析并**原样交给 `DialogHost`**，BAL 降级选路（overlay 可见则弹窗，否则通知回调）是 `DialogHost` 实现的事 —
  — handler 看不到 overlay 实况，不做这个判断；取消语义统一：prompt 用 `{value:null, confirmed:false}`，choose 用裸索引 `-1`（与 `extras.ts` 逐字对齐）。 **SPI 侧进度**：
  `shell`/`device`/`app`/`floatingWindow` 四件已有 `:platform:system` 真实现（`Runtime.exec`/`Build`/`PackageManager`+`UsageStatsManager`/`WindowManager`；`SystemSpis.of(context)` 是实现入口），
  `dialogs` 的 `DialogHost` **已落地**（实现按 domain KDoc 约定住 `:platform:capabilities`：`AndroidDialogHost` 纯 JVM 编排——AUTO 按 `overlayAvailable` 选路、OVERLAY 强制不可用即 `ERR_PERMISSION_DENIED` 不静默降级、
  通知路径先登记后 post、`finally` 注销+撤幽灵通知、晚到答案丢弃；设备面 `SystemDialogOps` 在 `…capabilities.device` 子包——AlertDialog overlay 弹窗（BadToken→`ERR_PERMISSION_DENIED`）+ 独立通道通知（prompt 回复动作挂 RemoteInput、
  choose 每项一个动作、`DialogActionReceiver` 按 key 回投；requestCode 分槽位防 filterEquals 撞 PendingIntent）；构造在 `PlatformWiring.of`（同 `overlayAvailable` 喂悬浮窗与对话框），
  `inject` 缺省 null 仍如实 `ERR_NOT_IMPLEMENTED`）。
- **已落地（项目目录与装配期补部署）**：`:domain` 的 `ScriptPaths`（项目根 `files/scripts/<projectId>` 的单一事实来源 —— 纯路径计算、无 IO；拼错目录名编译期即可见）被三处复用（`:app-service:npm` 的 `NpmProjectLayout`、
  `AppShellKit` 装配、调度侧补部署）。`:app-service:scheduler` 的 `ScriptDeployRecovery` 在 `AppShellKit.assemble` 时跑一次：**只补缺、绝不覆盖**（用户手改的脚本原样留着）、
  空清单如实为空（`deployReport.changed == false`，不粉饰成"已恢复"）、单文件失败不带走整批（`deployFailures()` 列路径+原因）。来源合并（`scriptSources` 显式优先 + `assets/scripts/<projectId>/` 按项目补缺，
  单项目读失败跳过不炸整批）：配方经 `scriptProjects` + `assetReader` 两缝拿资产（配方本身不直连 AssetManager，保持纯 JVM 可测），`AppShellApplication.installWithFiles` 喂真实现（`assets.list("scripts")` 枚举 + `AndroidAssetsSource.readScripts()` 按需读）。
  **诚实边界**：本类不是部署器 —— 覆盖/版本/审批仍归 `script-repo` 的 `AtomicDeployer` 与 npm `InstallCoordinator`（sha256/journal/审批链路）；框架也没有内置脚本模板，
  来源给多少补多少。

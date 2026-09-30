# AutoScript 落地状态台账

> **本文件不是契约。** 契约在 [`framework-design.md`](framework-design.md)。
> 这里只记「哪些已落地、哪些还是接口期、哪次实测推翻了什么」——即原
> `framework-design.md` §19 那条 9,584 字符的流水账，以及散在各节里的 `**已落地**` 块。
>
> **锚定规则**：条目一律以 `§X.Y` 锚回契约条款；§号是唯一权威锚，在本文件与
> `framework-design.md` 里同义（§号不随文件位置变化）。
>
> **只追加，不改写历史结论**：新事实加在 `## 流水` 顶部；被推翻的记账不删除，
> 原地标 `~~作废（日期 + 原因）~~`。
>
> **读契约时不要读这里**：正文里的落地注记是「实现注记」，不是规则本身。
> 判一条规则，看 `framework-design.md`；判它实现没有，看这里。

---

## 接口期（未落地，写了就是撒谎）

| 声明处 | 东西 | 现状 |
|---|---|---|
| §18 | 开放决策点 | **已全部拍板**（第 8/9 项 2026-09-25，第 1–7 项 2026-09-26，见 [`design-decisions.md`](design-decisions.md)；§18 保留作决策台账） |
| §14 P1 | MediaProjection 高清会话 | 未落（授权 UI + FGS；换 producer 即插，语义面不动） |
| §14 P1 | QuickJS `:sandbox` 进程 | 未落（仅模块骨架 `engine/sandbox/build.gradle.kts`） |
| §14 P1 | `ui` 原生 XML UI 宿主 / `ui_web` | 未落 |
| §9.7 | OCR（P1）/ 插件（P2） | 未落 |
| §10.5 | 生物特征二次确认 | 未落（`BiometricPrompt` 全仓零引用） |
| §8.5 | 引擎侧 `waitCompletion` 超时不发起 | 未覆盖（§8.6 自己记的诚实边界） |
| §15 | APK ≤ 40MB | **已超支**（实测 ≈81MB，见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径)） |
| — | 真机红测：exec/dlopen + 桥全链 | **已做**（2026-09-29，见下「流水」；非 root、Android 13/arm64、生产布局） |
| — | 真机红测：16KB 页机 / SELinux enforcing / `nativeLibraryDir` 提取路径 / targetSdk36 exec 策略 | 未做（设备 PAGE_SIZE=4096，这几项该机**原理上测不到**） |
| — | 真机红测：性能数字（冷启/帧往返） | 部分（冷启 158ms→新件 181–206ms；桥往返 p95=1ms；引擎 RSS≈46MB；`Intl` zh/en 运行期**已验**） |

---

## 已推翻 / 已改口径

见 [`design-decisions.md`](design-decisions.md#已推翻--已改口径) —— 口径变更属决策侧，
只在那里写一份（本文件不复制，避免两处漂移）。

## 流水（最新在上）

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
| A5 | `Intl.*` zh/en 运行期 | **已测 9-30（新件复测）**：node-slice 36609817687 绿后取新 `libnode.so` 推设备，`Intl` 在位（`icu_small=true`/`icu_locales=en,root,zh`/ICU 78）。zh-CN → `1,234,567.89`、`2026年9月30日星期三`，与桌面对照**逐字一致**；en-US 同。ar-EG 回落 latin 数字（small-icu 只带 zh,en 走 root 数据，是**预期**非缺陷——契约拍板的就是 small-icu zh,en）。全链复跑 rc=0、6 帧；新件冷启 181–206ms（旧件 158ms，ICU 数据加载 +~30ms）；桥往返复测 p95=1ms 仍达标 |
| A6 | 引擎进程 RSS（脚本存活窗内 `smaps_rollup`） | `Rss≈46.6MB Pss≈44.1MB`（其中文件页 ~32MB、匿名 ~12MB）；§15 80–160MB 区间内，偏下限（注：host 宿主进程同法测得 Rss≈47.6MB，同量级） |
| C1 | `.node` 经 `process.dlopen` 路径的 `require` | 与 9-29 同结论：**无 `DT_NEEDED libnode.so` 的件在 `require` 下仍 `ERR_DLOPEN_FAILED`（`cannot locate symbol "napi_add_env_cleanup_hook"`）**—— 即使宿主自身已 NEEDED libnode（启动闭包里有 libnode）。装载器按 SONAME 找依赖，不是全局作用域续期。`§10.12 不引入第三方 .node` 口径不变 |

A2–A4（截图→找图/找色/模板）仍欠：`libopencv.so` 不在设备上（`find` 无命中），等 image-native 件或随包后再测。

### 2026-09-30 —— 等设备的那笔账：真机红测可执行清单（未执行，只列账）

源头：§19「下一步 (a)」与本表接口期三行真机红测。原则：**设备不到位就不写数字进契约**
（§7.7/§15 的数字仍是验收口径），本条只把"要什么设备、跑什么、判什么"钉死，
设备一到按单执行、回填数字。

**A. 性能数字（§7.7 表 + §15 启动预算）—— 要一台能跑生产 APK 的真机即可（现有云手机已够）**

| # | 量什么 | 契约锚 | 怎么量（adb 可脚本化） | 判据 |
|---|---|---|---|---|
| A1 | 桥往返（JS→:main→回） | §7.7 空 RPC p95 < 2ms | `auto.console.log` 200 次脚本侧打点（host 真处理） | **已测 9-30：p95=1ms**（console 整调用往返；见本文件流水 9-30 条） |
| A2 | `captureScreen → findImage` 端到端 | §7.7 < 1s；一次截图两次匹配 < 700ms | 真机截屏（a11y 路径）→ `ingest` → `findImage` 两次，打点三段（截图 / ingest / 匹配×2） | 端到端 < 1s；当前：**待实测**（链路 9-26 已通，数字空） |
| A3 | `findColor` 1080p | §7.7 < 10ms | 生产布局下落一张 1080p 真机截图，`findColor` 单人独立子图计时（`adb shell` 循环 100 次取中位） | < 10ms；当前：宿主 36 例只保语义，设备耗时空 |
| A4 | `matchTemplate` 1080p | §7.7 < 40ms | 同 A3，模板用真机 UI 切片（非合成图） | < 40ms |
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
| C2 | `captureScreen → findImage` 实测数字回填 §7.7 | §7.7 / §9.2 | = A2 落地后的回填动作（数字进契约表 + 本表接口期行销账） |

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

### 2026-09-25 及更早 —— 自 §19 结语整段外迁（逐字保留）

**本仓库的推进顺序（已落地的按 §12.2 接线现状表为准，勿按上表臆造）**：契约与纯 JVM 层（`:domain` / `:bridge:java` / 各 app-service / `:platform:capabilities` 的 handler）已逐块落地并有单测；`AppShellApplication` 已从 11 行桩变成**闹钟/门禁的装配入口**（`AlarmSchedulerProvider` + `AndroidAlarmPort` + `AndroidScreenGate` + 静态注册的 `AlarmReceiver` → `AlarmDispatch` → `Scheduler.onTrigger`，漏投记账不静默丢弃），`AppShell.assemble` 的**生产调用方已落地**：`AppShellKit.assemble(filesDir, cacheDir, schedulerProvider, screenGate)`（`:app` 装配包，纯 JVM 可测）是那条路径的单一落点 —— 目录约定（`files/.autojs` 两个持久寄存器 + `files/scripts` 项目根 + `cacheDir/npm-cache`）与持久句柄的成对释放都收在它里面，`AppShellApplication.onCreate` 在 IO 域调它（`installWithFiles`），装配失败如实降级成"壳保持 null + 闹钟继续漏投记账"而不是半装冒充就绪；引擎工厂**生产已换 `NodeProcessEngine`**（`AppShellApplication.installWithFiles` 注入，`nativeLibraryDir/libnoden.so`+`libnode.so` 候选位；socket 名 = 桥监听 `BridgeSocketListener` **绑定成功才注入**（失败离线降级），`addonPath = ScriptPaths.bridgeAddonFile(filesDir)`（§19 交付轨 2026-09-24 接线：`assets/bridge-addon/` → `BridgeAddonDeploy` 落位，文件缺位即降级不注入 —— 与 bridgeDistPath 同一条选填纪律；jniLibs 三件套 `libnoden.so`/`libnode.so`/`libc++_shared.so` 由 `prepareEngineNativeLibs` 三件齐才落包、半套红，`extractNativeLibs=true` 保证 exec 有真文件）；缺件由 execute 预检**点名绝对路径**——比笼统"未接入"更可操作）；`AppShellKit` 缺省仍是 `UnavailableEngine`（`:app-service:runtime`，见其 KDoc）——**JVM 配方/测试不经 Application 装配时每次执行如实 `CRASHED` + 真原因进意图日志**，而不是开机后什么都不发生。开机恢复的接线点（`AppShell.bootRecover` → `Scheduler.recoverUncommitted`，`AppShellApplication.install` 在 IO 域触发；持久形态 `JournalFileStore` + `PersistentIntentLog` 已有 `AppShellProductionWiringTest` 覆盖），npm 侧已有生产装配（`NpmShellKit.assembleHandler(filesDir, cacheDir)` → `assemble(npmHandler = …)`，`NpmShellKitTest` + 同一接线测试覆盖）；归档侧意图日志与运行档案双持久（`JournalFileStore` + `FileRunArchive`，同一 `JsonLine` 行格式，`FileRunArchiveTest` 与 `InMemoryRunArchiveTest` 同语义锚点），`AssembledShell` 同时是任务中心的**读口**（`taskCenter()` = `scheduler.tasks()` + `archive.unfinished()`/`link()` + 恢复账经参数给入；`runsOf`/`runRecord`/`unfinishedRuns` 保留为窄读口，避免 UI 自开第二个 `FileRunArchive` 造成写侧两份视图）兼**操作面**（`registerTask`/`cancelTask`/`runTaskNow` 直通壳持有的同一个 `Scheduler` —— store-first 先落盘后动内存/闹钟，绝不另开第二个 `FileTaskStore`）；**任务中心全链已接上（2026-09-24）**：`AppShellApplication.taskCenter()`（壳未装配即抛，不冒充空清单）→ `:domain` 的 `TaskCenter.kt` 呈现 DTO → `:ui` 的 `TaskCenterScreen`（三页签之二：任务行 + 未结算执行 + 恢复账；2026-09-24 再接**操作面** —— 登记/取消/立即执行三写口 + `TaskCenterOps` 语义闸门 + `runTaskNow` 先查后触发的不哑火边界，见 §8.6）；**控制台全链也已接上（2026-09-24）**：`AppShellApplication.console()`（壳未装配即抛，不冒充「暂无日志」）→ `:domain` 的 `Console.kt` 呈现 DTO → `:app` 的 `ConsoleRead` + `AssembledShell.consoleView`（读壳持有的收集器与在途表，不另开第二份）→ `:ui` 的 `ConsoleScreen`（页签之三：行累积 + 丢包/拉满/在途两端对照，见 §7.3 末）；`AppShellKitTest` 覆盖自装配全路径（目录落位、门禁拒绝不投递、启动失败不写孤儿档案、真起引擎落终态记录、落盘遗留经 `bootRecover` 重投）。a11y 的 Android 真实现注入**已接**（`PlatformWiring` → `a11yHandler`：`AndroidUiTree`/`AndroidGestureInput` 经 `SystemA11yBridge`，服务未连如实 `ERR_SERVICE_DISABLED`），screen 的生产注入**同批已接**（`PlatformWiring.screenHandler`，§9.2 a11y 截图路径）；dialogs 的生产注入**也已接**（`PlatformWiring.of` 构造 `AndroidDialogHost`，AUTO 选路/强制降级拒绝/通知回调回投 + TTL 双清）；仍待的是 MediaProjection 高清会话（授权 UI + FGS，换 producer 即插）；脚本内容侧装配期补部署已接上（`ScriptDeployRecovery` 在 `AppShellKit.assemble` 时跑一次：只补缺不覆盖、空清单如实为空、失败不投毒，`deployReport`/`deployFailures()` 随壳暴露给能力中心）；§8.4 已闭环（判据/采样/`EngineWatchdog` 调度/`HeartbeatLedger` 心跳打点；pid 归属表仍归在途账不另建），Kotlin spawn 半边已送 pid 与心跳、桥监听 `BridgeSocketListener` 已接、addon JS 消费面 `attachNative` 已接（见 §8.4 末），设备面只剩真机联调（facade dist 随包 + 打包入口 attach 接线与 jniLibs 三件套/addon 落位 2026-09-24 均已落 —— assets 构建拷贝 → `BridgeDistDeploy` 落位 `filesDir/node_modules/auto` → env 注入 → kBootstrap `attachNative`，全链有 `BridgeDistPackagingEntryTest`；二进制侧 `prepareEngineNativeLibs` → `lib/arm64-v8a/{libnoden,libnode,libc++_shared}.so` + addon 走 assets → `BridgeAddonDeploy` → `addonPath`，APK 条目已实测）一道；§8.3 的 drift 已有裁决方（`EngineWatchdog` drift 连段 + `KillCause.DRIFT`：连续 3 轮对不上杀掉重来）；§8.6 已闭环（dispatcher 排队默认上限按触发源分级 + `PendingRun` deadline 记账与过期不重投；注册表持久 `TaskStore`/`FileTaskStore`（`tasks.jsonl`，upsert+tombstone，与意图日志同一 `.autojs` 目录、同一追加纪律）：`schedule`/`cancel` 先落盘后动内存/闹钟，`bootRecover` 先 `restoreTasks` 续排再重投意向，`AppShellKit` 建第三持久并随壳释放），**Android 触发侧也已接上**（预拉/Exact/降级记账 + 静态接收器回投 + 屏幕门禁生产实现） + 开机续排（`RECEIVE_BOOT_COMPLETED` + 静态 `BootReceiver`：重启清掉全部闹钟，没有它持久注册表再完整也没人续排；receiver 无判断只记日志，续排/重投走 `Application.onCreate` 正常装配路径，避免与 `install` 的恢复并发撞车）。**§8.7 保活与电源（`:main` 侧）也已接上**：`AutoScriptForegroundService`（specialUse FGS，`PROPERTY_SPECIAL_USE_FGS_SUBTYPE="automation"`，清单静态声明、`exported=false`）+ `ForegroundKeeper`（start/stop/renew + 15 分钟守护 ticker）+ `WakeLockLedger`（token 引用计数 + 超时自动释放，**取锁失败不记账**）+ `AndroidWakeLockOps`（真 `PARTIAL_WAKE_LOCK`，`setReferenceCounted(false)`）；**屏幕门禁的持锁判定就此收口**——`AppShellApplication.screenGateOf` 传 `WakeLockLedger::isHeld`，§8.7 原「恒真 = 明写的待接」作废；保活事实经 `ShellSummary.keepAliveActive`（`:domain`，无默认值）透到 `:ui` 首屏（「保活已生效」/「保活未生效：熄屏的亮屏任务会被拒绝」，不藏二级页）。服务经进程级邮箱 `ForegroundHost` 现取 Keeper（**服务不自装配**，避 service → 根包成环）、`START_NOT_STICKY`（续期统一走 `Application.onCreate` 装配路径，与 `BootReceiver` 同纪律）；`onTerminate()` 真机上从不被调用，只为测试收口 + 给「谁来停」一个落点。引擎侧 `power_manager` **已落地（2026-09-24）**：`PowerManagerNamespaceHandler` 直驱 `foregroundKeeper()` 的同一本账（`hold(token, timeoutMillis)` 插口当年就是照这个形状留的，账本零改）+ `powerManagerHandler` 独立缝 + `auto.power` 双侧契约（见 §8.7 与 §12.2 接线表）。**§9.5 能力中心的全链也已接上（2026-09-23）**：`AndroidCapabilityProbes`(6 事实) → `AndroidSystemStateReader`(判据唯一出处) + `AndroidGrantLauncher`(去向唯一出处) → `AppShellApplication.permissionCenter()` → **读口** `HostSummary.capabilityCenter()`/`openCapabilitySettings()`（`:domain`，`CapabilityCenterSnapshot`/`CapabilityRow`，`canRequestGrant` 是 `CapabilityLifecycle` 的投影）→ 拼装 `CapabilityCenterRead.snapshot`（`:app` 壳装配包，纯 JVM 可测：全量枚举 + 逐项现问三态 + 同一份 `guideText` + 降级任务账）→ `:ui` 的 `CapabilityScreen`（纯状态 DTO，JVM 可测）：三态各自的中文说法、引导文案原样透传、降级任务单列一段（§8.6「可能偏差」）、**没读到 ≠ 一个能力都没有**（`NOT_LOADED` 与 `failed` 分开且保留原异常文案）；刷新走「回前台/切页签」重问一次（授完权回来看到的是刚问过的结论，不是离开时的缓存；读失败不自激重读）。§9.4/§9.6 的五个系统命名空间（`dialogs`/`shell`/`device`/`app`/`floatingWindow`）已落地到**语义层**：`:domain` 的 `SystemContracts.kt`（`ShellExecutor`/`DeviceInfoProvider`/`AppLauncher`/`DialogHost`/`FloatingWindowHost` + DTO）、`:platform:capabilities` 的 `SystemNamespaces.kt`（五个 handler）、`AppShell.assemble` 的 `systemHandlers` 束 + `AppShellKit.assemble` 的透传（五个字段各自可空，未注入即如实 `ERR_NOT_IMPLEMENTED`）与 `bridge/js` 的 `extras.test.cjs` 双侧契约测试，三者串成一条线且都有单测；SPI 的 Android 实现**已落四件**（`:platform:system` 的 `AndroidShellExecutor`/`AndroidDeviceInfoProvider`/`AndroidAppLauncher`/`AndroidFloatingWindowHost`，入口 `SystemSpis.of(context)`，27 契约测试并进了 CI 测试任务表），`dialogs` 的 `DialogHost` 亦已落地（`AndroidDialogHost` 编排 + `…capabilities.device` 设备面，构造在 `PlatformWiring.of`，按 domain KDoc 住 :platform:capabilities）；**那次把 `SystemSpis` + `CapabilityNamespaces` 拼进 `AppShellKit.assemble` 的生产调用已落地**（`com.autoscript.shell.PlatformWiring`：`of(context)` = `SystemSpis.of` → `inject` → `systemHandlers` + `datastore`/`zip`/`settings`/`notification`/`clipboard`/`sensors`/`images` 七独立缝，`AppShellApplication.installWithFiles` 调用；拓扑靠 §6 **包级例外二**放行——仅 shell 装配包可依赖 `:platform:capabilities`/`:platform:system`，`ArchitectureTest`「平台实现只许装配包碰」+ `ModuleGraphTest` 允许集量化执行）。**`a11y` 的生产调用已接**（无障碍服务本体 `AutoScriptAccessibilityService` + `PlatformWiring` 注入，服务未连桥如实 `ERR_SERVICE_DISABLED`）；**`screen` 也已接**（§9.2 a11y 截图路径，与 a11y 同底），MediaProjection 高清会话是后续升级（换 producer 即插），不再是接线缺口。**`images` 桥面与 native 真实现均已接，且 P1 第一个算子 `findColor` 已落地**（单色+逐分量容差+可选区域+回第一个命中，四层同改；宿主机语义门禁 109 例附上（见 §9.2 末），真机红测待补）—— §12.2 第七条独立缝：`:domain` `ImageAnalyzer` + `ImagesNamespaceHandler` + `images.ts` 双侧契约齐全；native 侧 `:bridge:image` 的 `libopencv.so`（OpenCV 4.14 静态链接）+ `:platform:system` 的 `NativeImageAnalyzer`/`JniOps` 也齐了，`PlatformWiring.of` 构造（so 缺位 → null → 桥回 `ERR_NOT_IMPLEMENTED`，看不见像素的内存分析器只能假装匹配成功，那比没有更坏 —— 这条防线保留）。**`auto.npm` 的 wire 形状漂移已修**（与 `a11y.waitFor` 同一类事故：JS facade 读一个宿主从不发的键，两侧各自的测试都没抓到，因为 JS mock 自己回的那个形状）：`install` 曾被 JS 声明成 `Promise<InstallResult>{name,version,integrity,linkedBins}`，而宿主回的是字面量 `true`——现宿主回 `:domain` 的 `InstallHandle`（`{handleId,projectId,enqueuedAtMillis}`），facade 改成 `InstallQueued`，并在两侧注释里钉死「门面此刻还不知道会装出什么版本，回猜的版本号就是伪造」（§10.8/§12.3 文档里 `install → {name,version,integrity}` 的示例同批改掉：`InstallResult`/`ResolvedPkg` 两个 DTO 至今没有任何实现方产出）；`audit` 的键名 `vulnerabilities` → `vulns`（§10.8 与 `AuditReport.vulns` 都读它）；`list` 不再发恒 0 的 `sizeBytes`（lockfile 量不到尺寸，尺寸的两条真来源是 `offlineGap` 与 `storage`）；`offlineGap` 补上 JS 漏声明的 `version`；`requestApprove` 新增 `scripts` 校验 + 回显（与 `setRegistry` 的 scope 同一条纪律：宿主不认的字段被静默丢弃比报错更糟）；`ApprovalRequest` 的 JS 侧形状改与 `:domain` 逐字段对齐（`scripts` 是入参不是宿主字段）；`InstallEvent.phase` 从 `unpack/link/failed` 改到 `:domain` 六个阶段（`queued/resolve/download/reify/post-check/done`，失败由 `InstallFailure` 表达）。钉子：Kotlin +4 / JS `npm-contract.test.cjs` +9，反证过任一侧单独漂移立刻红。native/NDK 侧已出空壳：`:bridge:native` addon 控制面（`invoke`/`setSocketFd`/`setup`/`droppedData` + 读线程 + TSF 接线）与 `:engine:node-process` 宿主 `main.cpp`（§7.8 启动序）均已落地，经本机 NDK r28c 交叉编译验证（`engine/node-process/scripts/build-native.sh`：AArch64 ELF、`node::Start` 三方符号对表、LOAD≥16KB）；`:bridge:image` 也已落地 C++ 面（`imgnative.cpp` 计算核 + `images_jni.cc` 装载面），OpenCV 构建轨在 Actions（`image-native.yml`），本机不编译。**Kotlin spawn 执行链已落并本机验证**（`NodeProcessEngine` 16 单测 + `:app` 垂直切片 E2E：spawn → unix 桥 → console/心跳 → `SUCCEEDED` 归档；main.cpp abstract 连接 + `SO_PEERCRED` uid 门禁 + kBootstrap 自动心跳；addon invoke payload 字符串化金样；生产桥监听 `BridgeSocketListener`：abstract 绑定 + uid 门禁 + `NewlineFrameServer` serve，JVM 假缝单测 6 例；facade addon 消费面 `attachNative()`：setup(onFrame) 按 id 结算 + invoke 注入 + `errFromThrown` 保留真码，mock 6 例 + env 门禁真 addon 全环），仍待真机：设备侧 exec/dlopen 红测（16KB 页机 + targetSdk 提取策略；jniLibs 三件套与 addon 落位、facade dist 随包与打包入口 attach 接线均已落，见第 2 条切片路线）。

---

## 散在各节的实现注记（待续迁）

以下各节正文里仍留有 `**已落地**` 块，**尚未外迁**（本轮只做骨架）。迁移时按
`§号 + 一句话结论 + 证据（类名/测试名/实测数字）` 三段式收进本文件，正文侧只留
一句 `> 实现注记见 [design-status.md](design-status.md#...)`：

| 节 | 载体 | 规模 | 备注 |
|---|---|---|---|
| §9.2 | 行 616 单行 | 8,262 字符 | 八算子逐条记账，最大单块；建议先拆成 8 个算子小节再迁 |
| §8.6 | 行 565–574 九块 | 6,226 字符 | 调度/任务中心/操作面 |
| §9.6 | 行 648–655 三块 | 2,142 字符 | datastore/settings/zip 生产装配 |
| §8.7 | 行 581 | 2,057 字符 | 保活与电源 |
| §9.1 | 行 601 | 1,963 字符 | a11y 真实现与桥面 |
| §9.5 | 行 648 等两块 | 1,612 字符 | 能力中心全链 |
| §8.3 | 两块 | 983 字符 | 状态机 |
| §7.3 | 行 253 | 938 字符 | 控制台消费侧 |
| §12.2 | 表格「挂载状态」列 | 4 行 | 第四列整体属状态，建议换成一列「见台账 §X.Y」 |
| §12.1/§9.4/§10.1/§10.6/§8.4/§12.3.3 | 各一块 | 1,200 字符 | 零散 |

合计待迁 ≈ **28,000 字符**（占全文 20%）。

# AutoScript 决策记录

> **本文件收两类东西**：① 已拍板的开放决策点（从框架设计 §18 外迁，
> §号与编号保持原样）；② 被实测推翻/改过的口径（原口径与新口径并列，**不删旧**）。
>
> 契约在 [`docs/design/`](design/) 12 卷（入口 [`framework-design.md`](framework-design.md) 索引）；落地状态见
> [`design-status.md`](design-status.md)。三者分工：
> **契约说「是什么」，台账说「实现到哪」，本文件说「为什么这么定、什么被改过」。**
>
> **只追加**：新决策按日期加在「已拍板」顶部；推翻旧口径时在「已推翻」加一行，
> 原文照抄，不编辑历史。

---

## 已拍板（原 §18 全部九项：第 1–7 项 2026-09-26、第 8/9 项 2026-09-25；外加后续新增编号项）

2026-09-30 拍板（外部审查整改步骤 7；非 §18 编号项，原口径不涉）：

13. **`images` 匹配链路提速方案**（2026-09-30 评审拍板；A2–A4 实测 ❌ 后的出路裁决）：
   背景 = §7.7 实测 A4 933.6ms / A2 1912.8ms 双 ❌（判据 <40ms / <700ms）。评估中
   被**否掉**的两条：
   - **帧内缓存频谱**：bench 的 A2 是同帧**同**模板 match×2，看着第二次免费 —— 真实
     脚本多半是同帧**不同**模板，零收益；且 `cv::matchTemplate` 的内部频谱不暴露、
     不可跨调用复用，要真缓存得手写 DFT 相关 + 积分图，数值风险不值。
   - **灰度直配（P0a）**：阈值/置信度语义会漂（灰度化把色差折进亮度加权）——
     「报出去的数字与精确路径一字不动」这条守不住，放弃。
   **已拍板（2026-09-30）四件套**：
   - (1) **金字塔粗筛 + 原像素精配**：灰度 0.25×/0.5× 上只提名 ≤K 候选（thr−margin
     带宽 + NMS 压重复），逐候选回原 4 通道小窗重算 —— 坐标/置信度全来自原像素，
     语义不动；小模板（短边 <80px）/纯色/平噪走精确路径。差分双跑门（host
     `host_match_test`）对拍两条路径；`AUTOSCRIPT_MATCH_FORCE_EXACT=1` 现场回退。
   - (2) **两匹配方法加可选 `region`**（复用 findColor 的 `resolve_region` 判据；
     **region 比模板小 → `ERR_IO`**、越界 → `ERR_INVALID_PARAM`、坐标恒全帧口径）——
     小模板进不了粗筛，缩窗是它唯一的提速出路（bench 决定性行 = 48×48 @ 300×150）。
   - (3) **计算出锁**：`g_mu` 只盖帧表查找（浅拷贝两个 Mat 头即放锁）—— 帧入表后
     不可变（十算子产出新帧不改原帧），900ms 级 match 不再把 release/findColor 关在锁后。
   - (4) **调参口走环境变量**：`MIN_TEMPL_SIDE`/`MARGIN`/`MAX_CANDIDATES` 进程起始
     读入（真机扫参免重编 so），缺省与原 constexpr 逐字相同。
   **判据 <40ms 不改**：region 实测数字回来之前不谈口径（改判据是拍板动作，不混在
   实现里）。附护栏 **频率门**（模板「缩小→放大」自检 <0.8 → 精确路径）是差分门
   首跑抓到的 i.i.d. 噪声假 miss 的修法 —— 方向保守：挡错只损失速度。
   挂起不排期：多核条带切分（独立实验）、4→3 通道、`WITH_OPENCL`（两道门 + 体积代价）。
   **同日真机复测落点（只追加）**：A2 计算段 1912.8 → **171.75ms ✅**；A4 全帧 933.6 →
   **62.98ms**（仍差 1.6×）；**region 两行 11.77 / 16.57ms ✅**；`FORCE_EXACT` A/B 验证
   回退阀 ≈ 旧行为（892.3ms）。**判据 <40ms 改不改 = 待拍板**（全帧 ❌ vs region ✅ 的
   口径之争，数字在 §7.7 复测块）—— 本项只记方案拍板，口径另议。

12. **wire 面单一事实来源 = `bridge/schema/wire.schema.json`；与 §12.4 的 d.ts 分工**：
    - **schema 管 wire 面**（每 ns 的方法表 + aliases + dynamicSinks + facade 归属），`generate.mjs` 双发射 `bridge/js/src/generated/wire-types.ts` 与 `:domain` `WireMethods.kt`（生成物入库、`--check` + CI `git diff --exit-code` 双门）；19 个 handler 的 `methods()` 申报单源指 `BY_NS.getValue(ns)` —— 表不手抄，杜绝「申报与 `when` 两份手抄互相漂移」。对账三门分工：`wire-schema.test.cjs` 四向（生成物同步 / facade→schema / register↔schema / 申报↔schema + 死分支 aliases 真伪）、`wiring-table.test.cjs` 表↔schema、`pull-wire`/`event-wire`/`err-catalog` 各管自己的拉取环与错误目录。
    - **d.ts 仍是对外 API 评审面（§12.4 口径不动）**：d.ts 描述脚本作者看得见的 TS 形状（参数/返回/重载），schema 描述桥线上跑的 wire 名 —— 对象不同，不合并：把参数形状塞进 schema，生成器就得长出第二套类型系统；把 wire 名塞进 d.ts，内部协议就变成了公共 API 承诺。两份都入库、各有一道门。
    - **正则测试处置**：`wire-reconcile`（花括号计数啃 `when` 块、全局方法名集合）删 —— 底账脆且不认 ns 归属；`wiring-table` 换表↔schema 基；`jni-names`/`docs-surface` **保留**（JNI 编译面 / 文档表面积门，不是镜像测试，偏差与理由在此记明）。
    - **`bridge/js/dist` 出库**：tsc 产物改构建产物口径 —— 每次 build 弄脏工作树的 38 文件消失，代价是 CI jvm-tests job 与本机都要先 `npm --prefix bridge/js run build`（两处报错文案已点名命令）。

2026-09-30 拍板（外部审查整改步骤 3；非 §18 编号项，原口径不涉）：

11. **JSON codec 合一 = `:domain` 手写值族 `DomainJson`；kotlinx.serialization 评估后不采纳**：
    仓内 5 个手写 codec（`A11yBridgeJson` 步骤 4 迁入、`NpmBridgeJson`、`EngineBridgeJson`、`TinyJson`、内联 `WmJson`）与 `JsonLine` 的递归下降 Parser 全部收敛到唯一 codec `DomainJson`（六值族 `Value` + `decode/decodeObject/encode/encodeParsed` + `reqStr`/`opt*` 字段读取族），调用点只换名不改形状 —— `Map<String, Value>` + 抛 IAE → `RpcNamespaceHandler` 折 `ERR_INVALID_PARAM` 的口径不变。不选 kotlinx.serialization 的理由：① 全仓零该依赖，选它 = catalog/插件面 churn（根 `libs.versions.toml` 冻结，审查亦要求零改动）；② 现网 60+ 调用点已是 `Map<String, Value>` + `requiredStr` 语义，迁移近似改包名，却要引入 `@Serializable` 注解 + `Json {}` 解码器配置两套心智；③ 动态 wire payload（桥载荷、jsonl 行）的目标是 `Map`/值域裁剪，不是 data class，@Serializable 的强项用不上。备选评估到此为止。
    边界两处不计入「多头」：`JsonLine`（scheduler persist）保留为**薄件协议垫片**（~80 行：冻结行格式四型值域裁剪 + IOException 保型），编解码本体走 `DomainJson` —— 行 framing 是协议不是 codec；`ConsoleCollector` 保留自写 `handle`（非 RPC 形的 sink 面，不经错误折叠契约）。
    兼容边（同步记 design-status 流水）：`DomainJson.parseString` 拒未转义控制字符（老 `JsonLine.quote` 会把控制符直塞裸字节），且 `decode` 查尾部多余字符（老 JsonLine/TinyJson 忽略尾随）—— 存量含裸控制符的 journal 行将被拒，落在 IOException 保型内，表现仍是「行损坏」响亮失败；方向是收紧（响亮失败不静默）。

2026-09-30 拍板（外部审查整改步骤 4；非 §18 编号项，原口径不涉）：

10. **RPC 处理器基类收口 `:domain`；`*Lite` 自定义形状废除**：
    全部桥命名空间 handler（`:platform:capabilities` 14 个、`:app-service:runtime` 的 `EnginesNamespaceHandler`、`:app-service:packager` 的 `NpmBridgeHandler`、`:app` 装配包的 `WorkManager`/`PowerManager`）统一继承 `:domain` 的 `RpcNamespaceHandler` —— `handle` 为 **final**，只折叠两类：`AutojsException`（原码透传）与 `IllegalArgumentException`（→ `ERR_INVALID_PARAM`）；未知异常照穿（bug 显形，不伪造参数错）。子类只剩 `dispatch`（`when (request.method)` 分发 + 业务 + 参数校验）。解码 helpers（`decodeObject`/`requiredStr`/…，原 `SystemNamespaces.kt` internal 泛化）与唯一 codec `DomainJson`（原 `A11yBridgeJson` 迁入）同批入 `:domain`；`methods()` 留位步骤 7 的 schema 方法表。
    废除的旧口径：① 每个 handler 自带一份 `try { Ok } catch (Autojs) catch (IAE)` 折叠（全仓 ~60 处 `catch (e: AutojsException)`）；② `BridgeRequestLite`/`ResponseLite`（capabilities 为绕「禁直连 `:bridge:java`」自造的桥形状）与 a11y/screen/engines 的嵌套自定义 `Request`/`Response` —— 类型本就住 `:domain`，门禁理由不成立，签名换 `BridgeRequest`/`BridgeResponse`；③ 各 handler 的 `mount(): NamespaceHandler` 包装（类本身即 `NamespaceHandler`，调用点直挂，`AppShell.handleLike` 适配扩展随之删除）。收敛后残留的 `catch (e: AutojsException)` 只剩基类一处 + 与桥无关的业务面（ApkRepacker/InstallCoordinator/FloatingWindow/SensorSource/Zip）；保留自写 `handle` 的只有 `ConsoleCollector`（非 RPC 形的 sink 面，不经错误折叠契约）。

2026-09-26 拍板（原 §18 第 1–7 项；2026-09-30 自 §18 迁入，原文整段保留，§号与编号不变——迁移前这些条目写在契约 §18 里、标注「已拍板（2026-09-26）」）：

1. **引擎路线：先 Node-only，还是 P0 就并行 QuickJS 沙箱？**
   推荐「P0 只 Node；QuickJS 沙箱 P1」——沙箱牵扯独立进程、白名单、双引擎 API 对齐三件大事，混进 P0 会把最小闭环拖垮。
   **已拍板（2026-09-26）：不要沙箱**——QuickJS 整条轨撤出排期，**不只是推迟到 P1**：引擎只剩 Node 一条轨，`:engine:sandbox` 不进排期（原记「模块壳保留、模块表冻结不动」；**2026-09-30 壳已注释摘除**，模块表 15→14，ModuleGraphTest 同批），§14 的「QuickJS `:sandbox` 进程」与 §16 的两条相关风险随之作废。
   **这条改的是安全边界，不是排期**：原先「第三方/市场脚本 → `:sandbox` 白名单子集」的隔离（§11 来源分级表）不再存在，第三方脚本与自写脚本**同在 Node 进程、同权**（无障碍/截屏/点击/网络全开）。防线因此只剩两条：**安装时的用户选择**（见第 7 项）与 §11 的来源提示——「靠能力授予而非进程隔离」要写在给用户的提示里，不能让人以为装来的脚本是沙箱跑的。
2. **进程模型：P0 就用「每脚本一进程」，还是先单引擎进程后扩？**
   推荐**一步到位**：反正脚本绝不能进主进程，单引擎进程的边界与多引擎池完全同构，代价只是「池容量先写死为 1」。避免二次重构。
   **已拍板（2026-09-26）：采纳推荐，一步到位**——现状即如此（`FixedEnginePool` 池容量 1，`:nodeN` 每脚本一进程），本项只是把"将来扩到 1-3"那条路确认成默认方向，无代码改动。
3. **分发定位与 Play 态度？**
   推荐完全避开 Play Store（specialUse FGS / SCHEDULE_EXACT_ALARM / MANAGE_EXTERNAL_STORAGE 政策冲突），官网/F-Droid/APK 直下。若你仍想上 Play，需砍掉 specialUse 保活与精确闹钟，P0 范围要变。
   **已拍板（2026-09-26）：不发行**——非商业化项目、不分发，故 Play 政策冲突面（specialUse FGS / 精确闹钟 / 全盘存储）**根本不存在**，上面那组"若上 Play 要砍什么"的代价不用付，保活与 `SCHEDULE_EXACT_ALARM` 原样保留。落地形态 = 本机自装 APK；Play/F-Droid/官网分发轨不进排期。
4. **ICU 取舍：全量 ICU（完整 Unicode/时区/国际化，体积 +20MB 级）还是配 `--with-intl=none`（体积小但字符串/时区残缺，自动化和 UI 场景产物不友好）？**
   推荐**全量 ICU + 裁剪为所需 subset**（也可放 assets 按需加载），自动化 app 大量依赖正则/时区/日期格式化。
   **已拍板（2026-09-26）：只要中文 + 英文**——即 locale 面收成 `{zh, en}`，既不停在 `none`（那样连 `zh-CN` 的 `Intl.*`/`toLocaleString` 都不可用，等于还是残缺），也不背全量的 +20MB。落点是 Node 构建旗标 **`--with-intl=small-icu --with-icu-locales=zh,en`**。**旗标已改（2026-09-26，同日）**：`node-runtime-build/scripts/fetch-and-build.sh` 由 `--with-intl=none` 换成上述两行；数据源是仓内 canned ICU（`deps/icu-small/` 带 `README-FULL-ICU.txt` → `configure.py` 走 `canned_is_full`），**不联网下载 icu4c**，`root` 由 configure 自动并入。
   跟进（构建轨，Actions 跑，本机不编）：旗标已改 → **重编已过（run `36185853302`，success，2026-09-25T23:01Z，约 2h34m）** → **体积差已量并回填 §15**：`libnode.so` 70,725,976 → **81,950,376 B（+11,224,400 B = +10.70 MiB = +15.87%）**，取证两个 `node-slice` artifact（基线 `36153816811` intl=none / 新 `36185853302`）+ `config.gypi`（`icu_small=true`、`icu_locales=en,root,zh`、`icu_path=deps/icu-small`、`icu_ver_major=78`）。旧估数 "~10MB+" 是 small-icu **默认面**，`zh,en` 实测比它还略高一点（ICU 数据不是按 locale 线性摊的）。`RISKS.md` §3 同批改成「已改 + 已量」。**仍待设备**：`Intl.DateTimeFormat`/`Collator` 在 zh/en 上的运行期实测（arm64 二进制本机跑不了，归 §8b 那批真机账）。副作用要写明：`toLocaleString('ja_JP')` 之类非 zh/en locale 会回落 en —— 脚本作者该知道这不是 bug。
5. **无障碍服务与脚本进程共享与否的极限形态**：本设计定案「a11y 在 `:main`、脚本在 `:nodeN`」。若未来遇到「无障碍回调海量 + 脚本高频读树」压垮 `:main`，可演进出
   `:accessibility` 第三进程（§9.1 的接口已留好接缝）。P0 不做——保持最少进程数。
   **已拍板（2026-09-26）：采纳推荐，P0 不做**（接口缝照留；真出现压垮证据再切）。
6. **UI 宿主策略**：脚本 UI 用「`:main` 渲染原生 View」还是「脚本自带 WebView（ui_web）」为主？
   推荐**两者都留、原生优先**（原生 View 桥链短、性能好；WebView 桥用于复杂富交互）。若优先做 Web 方案更快出 demo，可调整为 Web 优先。你在意的 demo 速度可以决定这个顺序。
   **已拍板（2026-09-26）：采纳推荐，两者都留、原生优先**——两个 UI 面都还在 P1 排期里（§14），本项只是定下先后：原生 XML UI 先，`ui_web` 桥后。
7. **npm 默认镜像与脚本审批严苛度**（§10 已定案技术路线，这两项是面向用户的策略）：
   - 默认 registry：推荐 `registry.npmmirror.com`（国内实测存活）——若你的目标用户全球分布则改 `npmjs.org` + 可切换。种子缓存与「离线秒装」文案都要绑定默认镜像。
   - 脚本审批默认值：推荐出厂 **global-deny**（全部 install 脚本默认拒绝，人工逐个批准）。代价是与 AutoJsPro 既有的「默认跑脚本」用户习惯不同，新旧用户需要文档/示例适配；若你更看重无缝迁移，可出厂 allow-listed 常用安全包 + 黑名单模式。
   **已拍板（2026-09-26）**：
   - **默认 registry = 官方 `registry.npmjs.org`**——不分发、不面向陌生用户（第 3 项），镜像加速不是开箱前提；用户要快自己 `setRegistry` 切 npmmirror。`HostNodeExecutor`（实际装包用的那家）与 `NpmRegistryVerifier`（交叉校验的首选）**两处缺省同批改**，§10.2/§10.4 的"默认 npmmirror"同批改。**第二意见的规则同时改写**：交叉校验要的是**两个运营主体**，不是"官方那一家"——首选官方时镜像做第二意见，首选任意别家时官方做第二意见（`secondary` 缺省跟着 `primary` 走），否则会出现 primary 与 secondary 同站、自己跟自己比也算通过。
   - **lifecycle 脚本不做出厂卡口，安装时让用户自己选**——既不是 global-deny 也不是白名单：装包时按包如实告知 `hasInstallScript`（**禁止静默**，§10.12 那条保留），跑不跑由这次安装的使用者当场决定；`requestApprove`/审批接口保留为这条选择的落点。
   - **实现落差（明写，不当已办）**：现网 T0 是 `--ignore-scripts` 全程 + npm12 `allowScripts=none`，lifecycle 脚本**一个都没跑过**，安装回执显式发 `scripts-skipped`（禁止静默那条就是为这个静默面立的）。"用户选择跑"要先有 spawn 桥（`child_process` 真执行），那是 §14 的 **P1 项**——**口径在此定死，实现排 P1**，P1 落地时按本条写交互，不重新拍。

2026-09-25 拍板。编号与 §18 原编号一致（第 8、9 项），原文整段保留：

8. **截屏帧与 images 帧的通路**（§9.2 记账的缺口，决定 §7.7 表里 `captureScreen → findImage < 1s` 这条链路什么时候能兑现）：
   `screen.capture()` 出的帧与 `images.decode` 出的帧**互不通用**（两缝各发各的号，§12.2），且 screen 面既不给 `save()` 也不给 `pixel()`（字节出不了 `:main`），脚本目前**只能自己先落盘再 decode**（§12.3 示例这么写）。三条出路，入口在同一处（换 producer 或加一个 `screen.save`），代价不同：
   - (a) **`screen` 面加 `save(path)`**：把 a11y 已产出的 JPEG 字节原样落盘。设备面已经在压 JPEG 了，最小改动；代价是**有损**——`findColor` 的分量判定会吃到压缩伪影（§9.2 的契约是按分量精确夹的），"屏幕上这个色还在吗"这类判读会变钝。
   - (b) **两缝共用一个帧表**（producer 直接把帧写进 `images` 的帧表）：收益是真正的 0 拷贝直连（§7.4 所有权边界仍是每个句柄一份 Mat，变的是**发号那一侧**归谁）；代价是"帧不通用"这条纪律取消，`screen`/`images` 两个命名空间的释放语义要重新对齐（谁 release 谁背 STALE）。
   - (c) **`images` 面加 `decodeBytes(byte[])`**：屏幕字节不落盘直进 native；代价是 bytes 要过桥，§7.7 的"屏幕帧→native 0 拷贝"这条在**两个维度上**都要重新记账，且 §7.4 的多一路径 = 多一处规格要守。
   **已拍板 (b)**（2026-09-25）：只有它同时保住了"0 拷贝"与"按分量精确判定"两条被契约明确承诺的性质，(a) 切掉的是判读精度、(c) 切掉的是性能口径。(a) 不作为过渡 —— 过渡方案一旦进示例就会被抄成正式用法，而带 JPEG 往返的链路不叫「屏幕帧→native 0 拷贝」，§7.7 的买单口径不为它改。落地时动的是 §7.4 所有权边界（发号侧归一），`screen`/`images` 的句柄纪律届时同批重写，"帧不通用"那条纪律取消。

9. **`images.decode` 的相对路径口径**（2026-09-25 实测记账，影响 §9.2/§12.3 的示例写法）：
   `:domain` 的 `ImageAnalyzer.decode` KDoc 写着「路径解析（相对项目根 or filesDir）由实现定」，但**四层里没有任何一层解析路径**（计算核 `std::fopen`/`cv::imread` 直取、装载面与 `NativeImageAnalyzer` 原样透传、handler 只挡空白串）。host 侧实测把这条钉死了：传相对路径时按**进程 CWD** 解析——同一个文件，绝对写法与「chdir 到该目录 + 相对写法」都回 `ERR_IO(3)`（说明相对写法确实命中到了文件），而不存在的相对路径回 `ERR_FILE_NOT_FOUND(2)`。`libopencv.so` 载在 `:main` 进程里，那个进程的 CWD 是 `/`（Android 对 zygote 后代的固定行为），于是脚本写 `images.decode('part.png')` 会在根目录找一个并不存在的文件——**回的是 `ERR_FILE_NOT_FOUND`，且报的路径是对的**，所以看起来像"文件真的不在"，不像"口径没定"。
   两条出路，代价不同：
   - (a) **就在契约里写明"路径必须是绝对的"**（示例改成 `/sdcard/...` 或让脚本自己拼 `filesDir`）。零实现改动，代价是 v9 的 `fromFile('part.png')` 这种相对用法在 AutoScript 直接不成立，脚本要改写法。
   - (b) **在 handler 层加一层基准解析**（相对路径按项目根 / `filesDir` 拼绝对再往下传）。保住 v9 的写法，代价是要定"基准是谁"（项目根？脚本所在目录？filesDir？）——**三选一本身又是一个要拍板的策略**，且 §9.2 的「不做路径策略」那条边界要重画。
   **已拍板 (a)**（2026-09-25）：路径必须是绝对的 —— 把"相对路径"从契约里去掉而不是猜一个基准。(b) 不给：基准三选一本身又是一个策略，且 §9.2「不做路径策略」的边界不重画。**§12.3 已按 (a) 改写**：示例路径一律绝对（`fromFile('/sdcard/part.png')`），并写明了相对写法为什么回 `ERR_FILE_NOT_FOUND`。跟进动作：`:domain` `ImageAnalyzer.decode` KDoc 里「路径解析由实现定」那句要收紧为"只收绝对路径"（见下）。

---

## ~~仍待拍板（原 §18 第 1–7 项）~~ **已全部拍板（2026-09-26），见上方「已拍板」第 1–7 项**

> **2026-09-30 订正（只追加，原表不动）**：本段原写「以下七项尚未拍板」是拍板前的原貌 ——
> 七项已于 2026-09-26 全部拍板，条目已迁入上方「已拍板」（编号不变、原文整段保留）；
> 台账正文在 [`design/18-19-ledger.md` §18](design/18-19-ledger.md)。原表按下保留：

以下七项尚未拍板，原文保留在 §18（现居 `design/18-19-ledger.md`），
契约正文按「推荐默认值」编写；一旦拍板，**逐项搬到这里**并在 §18 留一行指针。

| 编号 | 议题 | 推荐默认值 |
|---|---|---|
| 1 | 引擎路线：P0 只 Node vs 并行 QuickJS | P0 只 Node，QuickJS 沙箱 P1 |
| 2 | 进程模型：一步到位 vs 先单引擎进程 | 一步到位（池容量先写死 1） |
| 3 | 分发定位与 Play 态度 | 完全避开 Play（官网/F-Droid/APK 直下） |
| 4 | ICU 取舍 | 全量 ICU + 裁剪 subset（**注**：构建管线当前是 `--with-intl=none`，见 RISKS §3） |
| 5 | a11y 与脚本进程的极限形态 | P0 不做第三进程，接口已留接缝 |
| 6 | UI 宿主策略 | 两者都留、原生优先 |
| 7 | npm 默认镜像与审批严苛度 | `registry.npmmirror.com` + 出厂 global-deny |

---

## 已推翻 / 已改口径

| 原口径 | 出处 | 处置 | 日期 |
|---|---|---|---|
| `TM_CCORR_NORMED` | §7.7 表 + `:domain` KDoc | 改 `TM_CCOEFF_NORMED`（CCORR 在「画面里没有模板」时 max 仍 +0.955，阈值 0.9 直接误判命中） | 2026-09-25 |
| APK ≤ 40MB | §15 表 | 实测推翻：jniLibs 三件套未压缩 ≈81MB；OpenCV 面仅 7.0 MiB、不是超支原因。三条出路见本文件「仍待拍板」第 7 项旁的 §15 记账 | 2026-09-25 |
| 「屏幕帧→native 0 拷贝」链路可走 | §7.7 表 | 两缝帧不通用，脚本当前走不通；数字是「链路通了之后」的口径。出路已拍板 (b) 两缝共用帧表 | 2026-09-25 |
| `images.decode` 相对路径 | §12.3 示例 | 四层无一层解析路径，相对写法在 `:main`（CWD=`/`）恒 `ERR_FILE_NOT_FOUND`。已拍板 (a)：**路径必须绝对** | 2026-09-25 |
| §8.7「恒真 = 明写的待接」 | §8.7 | 作废：`AppShellApplication.screenGateOf` 传 `WakeLockLedger::isHeld`，持锁判定收口 | 2026-09 |
| §7.8 步骤② 「addon 的 `napi_*` 从 libnode 动态表解析」 | §7.8 | 真机推翻：bionic 的 linker namespace **不把先做的 `dlopen(RTLD_GLOBAL)` 符号给后做的 `dlopen`**（glibc 会）——addon 实测 `cannot locate symbol "napi_add_env_cleanup_hook"`。改为 **addon 自己 DT_NEEDED `libnode.so`**（按 SONAME 命中已加载的那份，与落位目录无关） | 2026-09-29 |
| 「bionic 上 DT_NEEDED + `$ORIGIN` 足以让链接形宿主起来」 | 本文件上一行方案 | 实测**不完整**：RUNPATH 只作用于可执行文件自己的直接 NEEDED，**不作用于被依赖库的传递依赖** → 链接形宿主三处摆放一致死在 `libc++_shared.so`（`CANNOT LINK EXECUTABLE … needed by …/libnode.so`）。宿主改为**自己 NEEDED `libc++_shared.so`**（撤 `-static-libstdc++`）、保持 dlopen 形不链 libnode；addon 反之（链 libnode、静态 STL） | 2026-09-29 |
| 宿主 `-static-libstdc++`「不把 libc++_shared 推给装载面」 | `build-native.sh` LDFLAGS 注 | 作废：正是它让 libnode 的传递依赖无人解析。宿主改用共享 libc++（NDK 默认） | 2026-09-29 |
| 绝对 rpath / `LD_LIBRARY_PATH` 注入作为兜底 | 上一轮讨论 | 绝对 rpath 在 bionic 上**不生效**（`CANNOT LINK EXECUTABLE … not found`，`$ORIGIN` 有效）；`LD_LIBRARY_PATH` 只作真机调试手段，不作为交付依赖（交付形态实测无需它） | 2026-09-29 |
| 「打包整轨」 | §14 P0 | 移入后续版本（向导 UI 与 Keystore 取密随轨走）；加密资产/loader 一并移出需求 | 2026-09-23 |
| `tools/jvm-test*` 本机旁路「保留作快速门」 | CLAUDE.md 构建段 + §6 末 | **删除**（脚本已删）：本机快速门 = CI 同源 `./gradlew` 单模块任务（本机 SDK 已配置、12 任务同源可直跑，不留第二口径）；原 jvm-test-all 的「aborted ≠ 绿」纪律由约定插件 `autoscript.test-guard`（build-logic）承接 —— 测试出现 skipped/aborted 即红，环境门禁类在 `TestGuard.ENV_GATED`，其余 `-PallowSkipped=<类名>` 显式放行 | 2026-09-30 |
| `:engine:sandbox` 模块壳保留、settings 模块表按协调者冻结不动 | §6 行 + §18 第 1 项（2026-09-26 拍板附注） | **空壳从 settings 注释摘除**（QuickJS 裁撤口径不变，审查步骤 1）：`include` 注释 + ModuleGraphTest `include` 正则改锚行首（注释行不计）+ 允许集删行 + 模块表 15→14；目录留盘，复活 = 注释回 + 登记 | 2026-09-30 |
| §12.2「语义层（handler）住 `:platform:capabilities`」+「为什么 handler 不住 `:platform:system`」两层理由 | §12.2「分两层，别混」段（原两句原文见本节末引用块） | **口径反转（审查步骤 6，零新模块方案）**：handler 归位实现模块 —— 系统面十一件住 `:platform:system` 的 `SystemNamespaces.kt`（与 `SystemSpis`/契约同模块，2026-09-30 步骤 6a/6b/6d 分批迁入），`dialogs` 留 `:platform:capabilities`（`DialogHost` 实现按约定在同模块），`workManager` 归 `:app-service:scheduler`、`power_manager` 归 `:platform:system`；原理由 (1) 共担门禁组 → 校验仍单点住在 `SystemNamespaces` 工厂束（同模块一处，不各写一份），(2) 装配层双模块直连 → `PlatformWiring` 本就经 §6 包级例外二同时可见两模块、根包经工厂缝零 platform 类型（`ArchitectureTest` 依赖级门禁验证）；与 `EnginesNamespaceHandler` 住 `:app-service:runtime` 同形态（handler 归位实现模块）。§6 两行、§12.2 表 handler 列与两段散文同批改 | 2026-09-30 |

### 附：§12.2 被反转口径原文照抄（2026-09-30 步骤 6 摘录前的原文）

> - **语义层**（handler）住 `:platform:capabilities` 的 `SystemNamespaces.kt`，纯 JVM 可测（假 SPI 注入即可跑）：参数校验（spec 守卫、必填字段、`timeout > 0`）、枚举字面量解析（`ShellMode`/`DialogMode`，拼错即报错不静默套默认）、默认值（shell 超时 30s）、错误分类**透传**（`AutojsException.error` 原码回桥）、响应形状编码（与 `extras.ts` 逐字对齐）；

> 所以「为什么 handler 不住 `:platform:system`」有两层理由：(1) 五个命名空间共享一套门禁组（OVERLAY/ROOT/ADB_INPUT），语义放一起才不会各写一份校验；(2) handler 若住 `:platform:system`，装配层就得同时直连 `:platform:capabilities` 与 `:platform:system` 两个模块才凑得齐 Router —— §6 对 `:app` 非装配包明令禁止这一直连（装配包 shell 的生产装配 `PlatformWiring` 经包级例外二放行，但那只是"把 SPI 拼成束"，不构成把 handler 挪去 `:platform:system` 的理由：主因仍是 (1) 的共担门禁）。

---

## 口径之外的判据注记（暂留契约侧）

§7.7 的 `TM_CCOEFF_NORMED` 长篇实测注记（2026-09-25）目前仍在契约正文里。
它**读起来像决策**（「口径改了」在末句），但主体是「为什么 CCOEFF 而不是 CCORR」
的判据论证。这一轮不动；下次迁移时按「判据留契约、变更进本文件」切开。

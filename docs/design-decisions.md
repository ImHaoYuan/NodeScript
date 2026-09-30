# AutoScript 决策记录

> **本文件收两类东西**：① 已拍板的开放决策点（从 `framework-design.md` §18 外迁，
> §号与编号保持原样）；② 被实测推翻/改过的口径（原口径与新口径并列，**不删旧**）。
>
> 契约仍在 [`framework-design.md`](framework-design.md)；落地状态见
> [`design-status.md`](design-status.md)。三者分工：
> **契约说「是什么」，台账说「实现到哪」，本文件说「为什么这么定、什么被改过」。**
>
> **只追加**：新决策按日期加在「已拍板」顶部；推翻旧口径时在「已推翻」加一行，
> 原文照抄，不编辑历史。

---

## 已拍板（原 §18 第 8、9 项）

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

## 仍待拍板（原 §18 第 1–7 项）

以下七项尚未拍板，原文保留在 [`framework-design.md` §18](framework-design.md)，
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

---

## 口径之外的判据注记（暂留契约侧）

§7.7 的 `TM_CCOEFF_NORMED` 长篇实测注记（2026-09-25）目前仍在契约正文里。
它**读起来像决策**（「口径改了」在末句），但主体是「为什么 CCOEFF 而不是 CCORR」
的判据论证。这一轮不动；下次迁移时按「判据留契约、变更进本文件」切开。

## 9. 自动化能力设计

### 9.1 无障碍通道（`accessibility` / `ui_selector` / `ui_object`）
- **服务在 `:main`**；EventDelegate 面向桥暴露**节流拉取式**事件流（按 `seq` 游标，批量取，背压到数据面 TSF）。
- **紧凑索引树**：节点序列化只含基础索引（id、className、关键 attr 指针），属性**按需二次查询**；全树 JSON 序列化性能杀手已从协议层面排除。
- 查找：`UiSelector` 构建 → 树读 + [可选] 属性谓词过滤，`findOne/findAll/findOneOf`（Promise），超时 `ERR_NOT_FOUND/NotFoundError`。
- 操作：`click/longClick/scroll/setText/copy/paste` 走无障碍 Action；手势 `dispatchGesture`（canPerformGestures）。
- **句柄代理**：JS 侧 `UiObject` = 代理对象（§7.4），操作带 generation，控件已离开窗口树 → `ERR_STALE_HANDLE`。
- 窗口树：`window('modal/active/…)`、`UiObject.window`、event 监听（`EventEmitter`）。
- 全链路如实时树可能加速：惰性属性化已内建在索引树设计中。
- **已落地（Kotlin 侧）**：`A11yNamespaceHandler`（方法表与 payload 见该类 KDoc；构造只收 `:domain` SPI `UiNodeTreeReader`/`UiActionExecutor`/`InputProvider`，Android 真实现替换内存树/
  输入即插——handler 逻辑不变）+ 内存窗口树/输入替身，共 62 项 JVM 单测（`A11yWaitForTest` 5；app 侧挂载测试另计）；**Kotlin 侧 `waitFor` 读的载荷键是 `conditions`**（与 `findOne` 同构；
  轮询等待是宿主责任，内存树是单次快照，`timeout/interval` 只透传回显，不伪造等待）。JS facade `a11y.ts` 的 `waitFor` 已对齐：发 `conditions`（曾发 `selector`，
  会在白名单外字段上回 `ERR_INVALID_PARAM`——已修，两侧同构），其余方法键早已对齐。**`waitFor` 回 boolean，`findOne` 回 `{ref}`（别混）**：`waitFor` 命中回 `Ok "true"`、
  无匹配回 `Ok "false"`，**不回**节点体也不回 `Err NOT_FOUND` —— JS facade 的 `waitFor` 是 `Promise<boolean>`（§12.3 `const ok = await auto.a11y.waitFor(...)`，`result === true`），
  复用 `findOne` 的回包路径会让它恒 `false`（这正是刚修掉的漂移：Kotlin 侧曾是 `"waitFor" -> findOne(...)`，两侧各自的测试都没抓到）。**参数错误仍是 `Err ERR_INVALID_PARAM`**，
  不一并折成 `false`（「没等到」与「发错了」是两回事）。两侧各有钉它的测试：Kotlin `A11yWaitForTest`（5 项）、JS `a11y.test.cjs` 的「waitFor 返回 boolean」用例。
  **Android 真实现已落**（`:platform:capabilities`，分层两答）：语义层 `AndroidUiTree`（树+动作一体、句柄注册表共享——与 `InMemoryUiTree` 双身份同形）+ `AndroidGestureInput` 只认 `A11yBridge` 缝（纯 JVM，
  假桥注入跑全部选择器/句柄/手势逻辑）；设备层 `AutoScriptAccessibilityService`（清单+配置随库合并）实现同一接口并在 `onServiceConnected` 登记 `A11yServiceHolder`、
  `onDestroy` 清空。**桥面方法**（root/findBySelector/findByText/剪贴板/手势）服务未连 → `ERR_SERVICE_DISABLED`；**句柄面动作**先解注册表（未登记 → `ERR_STALE_HANDLE`；
  已登记但服务已死 → `refresh=false` → `ERR_STALE_HANDLE` 并回收）；`MODAL` 作用域以焦点窗为模态代理（AOSP `AccessibilityWindowInfo` **没有** `isModal`，主源码实证）；
  手势关门位读服务 `CAPABILITY_CAN_PERFORM_GESTURES`（系统没有 `AccessibilityManager.canPerformGestures()`，同样主源码实证——设计里这个方法名是错的）；四向滚动走 `AccessibilityAction.ACTION_SCROLL_*.getId()`（顶层 int 只有 FORWARD/
  BACKWARD）。ArchUnit 量化分层：`android..` 只许服务三件（`AutoScriptAccessibilityService`/`ServiceBridge`/`ServiceNode`），语义层碰 android 即红；capabilities 本机测试随之切 `--android-jar`（运行期 stub 不碰—
  —测试全走假桥）。事件环 `A11yEventRing`（有界 512、`nodeHandle` 恒 null 不伪造句柄、type 用 `windowStateChanged`/`windowContentChanged`/`viewScrolled` 诚实名）。

### 9.2 截图与图像管线（`media_projection` / `image` / `@autojs/opencv`）
```
FrameSource (SPI)
  ├─ AccessibilityScreenshotSource  API34 takeScreenshotOfWindow · 333ms 节流 · 默认
  └─ MediaProjectionSource          会话式 · createScreenCaptureIntent→同意→FGS(type mediaProjection)→createVirtualDisplay
       └─ Surface → ImageReader(maxImages=2~3 对象池) → Frame 进入 libopencv.so
            └─ 灰度/裁剪/缩放/旋转/找色/模板匹配/特征(ORB)/颜色查找 — 全 native, 0~1 拷贝
               （计算核八算子全落：找色/模板匹配/灰度/裁剪/缩放/旋转/特征，P1 native 面收官；桥面八算法已全开（2026-09-29））
```
- 截图对象生命周期：JS `Image` 句柄 → native 帧句柄；`recycle()` 显式 + finalize 兜底；`dispose` tombstone 协议同 §7.4。
- `FLAG_SECURE` → 分类错误（§7.6），不返回黑图（让脚本可判断）。
- **已落地（Kotlin 侧）**：`ScreenshotSource`（333ms 节流 / generation=1 单帧句柄 / 会话 open-close；`recycle` 已升为 `:domain` `FrameSource` SPI 方法）+ `ScreenNamespaceHandler`（构造只收 `FrameSource` SPI，
  `capture/recycle/startCapturer/nextFrame/closeSession`）。**Android 真实现已接（§9.2 a11y 截图路径）**：`AndroidFrameProducer` 经 `A11yBridge.{screenSnapshot,takeScreenshot}`（`AutoScriptAccessibilityService` 设备面实现—
  —`ScreenshotResult` HardwareBuffer→软位图→**紧密 RGBA 像素**（2026-09-26 起不再压 JPEG —— 那段压缩是死重且有损），**实际尺寸随帧走**（`ProducedFrame`，曾经固定 1080×2400 回包是对 JS 报假尺寸，
  已除）；配置 `canTakeScreenshot=true` 进 res/xml（AOSP 明示缺它两法都不可用）；失败码分类映射 SECURE→`ERR_BLACK_FRAME`、系统限频→`ERR_INVALID_PARAM`、通道失效/
  无效窗口→`ERR_SERVICE_DISABLED`、内部错→`ERR_IO`；API34+ `takeScreenshotOfWindow`、API30–33 `takeScreenshot`、API<30 如实 `ERR_NOT_IMPLEMENTED`）。生产装配 `PlatformWiring.screenHandler = CapabilityNamespaces.screen(ScreenshotSource(AndroidFrameProducer(), analyzer = images))`（**同一个** analyzer 也喂给 `images` 缝，
  §18-8(b) 一张表；analyzer 为 null 即退回本地帧表） → `AppShellApplication.installWithFiles`，与 a11y 同底（`SystemA11yBridge`，服务未连 = `ERR_SERVICE_DISABLED`）；锁屏/无窗口由 `ScreenPolicy` 预检分类，
  安全窗由回调码兜底（无障碍读不到窗口 FLAG_SECURE，`secureForeground` 预检位恒 false —— 不伪造预检能力，分类结果殊途同归）。**仍缺**：MediaProjection 高清会话（授权 UI + FGS + ImageReader→libopencv.so）—
  —换 producer 即插，语义面不动；P0 会话由同一 a11y 帧源连续截图承接。
- MediaProjection **会话语义**：`capture()` 一次性授权会话（API34 每会话确认）；`reconnect` 不自动重试授权，由 PermissionCenter 引导用户重授权。
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
- **两缝帧表已合一（§18 第 8 项 (b)，2026-09-26 落地）**：`screen.capture()` 出的帧与
  `images.decode` 出的帧现在**同号段、互认** —— 拿截屏帧当 `images.findImage()` 的
  haystack 通，`images.release()` 也放得掉一帧截屏。"帧不通用"那条纪律**取消**。
  落点是**发号侧归一**（§7.4）：
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

### 9.3 输入通道（`root_automator` / 手势）
`InputProvider` SPI 三实现：无障碍手势（默认）/ root `sendevent`（root 设备自选）/ Shizuku-ADB（可代理 dev `${i}` 事件）。统一 `touchDown/Move/Up` + 手势 DSL。root 能力分级进 PermissionCenter，无 root 不降级渲染为禁用（不假装可用）。

### 9.4 悬浮窗 / UI 宿主
- `floating_window`：`TYPE_ACCESSIBILITY_OVERLAY`（可信窗口易保持）＋ `SYSTEM_ALERT_WINDOW`（普通）；运行时权限 checkbox 进能力中心。
- **落地分层（勿混）**：`floatingWindow` handler 只管**参数与信封**（spec 守卫 → `ERR_INVALID_PARAM`、句柄两字段 `{refId,generation}` 的原样编码、分类错误原码透传），
  住 `:platform:capabilities` 的 `FloatingWindowNamespaceHandler`（纯 JVM 可测）；**句柄记账（generation 递增）、`close` 幂等、`ERR_STALE_HANDLE` 的起源、窗口类型选择（`TYPE_ACCESSIBILITY_OVERLAY`/
  `SYSTEM_ALERT_WINDOW`）全在实现侧** —— 由 `:domain` 的 `FloatingWindowHost` SPI 承接，Android 实现 `AndroidFloatingWindowHost` 已落在 `:platform:system`（§12.2 分两层）。
- 脚本 UI：JS 声明 XML 布局 → 桥传 `:main` 渲染原生 View（`UiHost` SPI）；`ui_web` → WebView + JS 桥（双向事件回 Node）；`ui` Activity 方式独立宿主 Activity（BAL 限制内，仅当可见/继承时启动）。
- 事件回投（点击/输入/页面生命周期）→ RuntimeChannel → JS 侧 `EventEmitter`。

### 9.5 权限与能力中心（three-state 门禁）
统一 `CapabilityStatus = GRANTED / DEGRADED / DENIED`：
- **GRANTED**：系统授予且当前可用（含会话型 MediaProjection 已激活）。
- **DEGRADED**：可降级但受限（如 a11y 树只能节流读、闹钟降 setWindow、无 root、BAL 限制、电池未豁免、ROM 自启被关）。
- **DENIED**：被用户/系统拒绝，操作抛 `ERR_PERMISSION_DENIED`。
- 能力中心 UI：枚举所有能力 + 当前三态 + 一键跳转系统页 + 降级说明；`PermissionFacade` 是唯一的权限入口（模块不直接查 `Settings`/`ActivityCompat`，可 Mock）。
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

### 9.6 数据与存储（`datastore` / `settings` / `zip`）
- `datastore` = SQLite-backed KV + serializer 适配（JSON/native 对象/byte），事务语义；同步 importer 仅供纯内存。**契约已落 `:domain`**（`storage/`）：`DataStore` SPI（全挂起，
  同步 importer 不进契约 —— 铁律 2，纯 JS 进程内路径专有）+ `StoredEntry`（`Json` 透传文本 / `Bytes` 内容相等；存 JSON `null` ≠ 键缺失）+ `DataStoreTxn`（暂存写、
  block 抛错整批不落、提交对并发读者一个原子点）+ `InMemoryDataStore` 纯内存参考实现与契约测试 —— **桥面已通**：handler（`:platform:capabilities` 的 `DatastoreNamespaceHandler`，
  `{found,value}` 信封保 `undefined`≠`null`、字节值不过桥如实 NOT_IMPLEMENTED、`transaction` 不上桥）+ 独立注入缝（`assemble.datastoreHandler`，不入 `systemHandlers` 束 ——
  存储面无共担门禁）+ JS facade（`datastore.ts`，`get` 拆信封）。**SQLite 实现已落** `:platform:system`（`AndroidDataStore` + `SqliteKvOps` 单表 `kv(key,kind,value)`，`KvRowCodec` 显式 kind 列裁定文本/
  字节、访问器按 kind 惰性；`SystemSpis.Bundle.datastore` 入口就绪）——**生产已接**（`PlatformWiring.of` → `inject` → `AppShellApplication.installWithFiles` 喂 `datastoreHandler` 独立缝；
  `PlatformWiringTest` 同路径真转接覆盖）。多库 = handler 键前缀 `name:key`，领域层不发明第二套路由。
- `settings` = 系统设置读写（`:domain` `SystemSettings` SPI + `:platform:system` `AndroidSystemSettings`，P0 钉在 `Settings.System` 命名空间：string/int 两型 × 读写 + `canWrite` 探针）。
  写入口径（§9.5）：未授 `WRITE_SETTINGS` → 抛 `ERR_PERMISSION_DENIED`（不是回 false —— 授权问题是分类错误）；已授权仍被系统拒 → `ERR_IO`；读侧缺失回 null（不拿 0/
  空串冒充 —— 0 是合法亮度）。**桥面已通**：handler（`:platform:capabilities` 的 `SettingsNamespaceHandler`，方法表照抄 SPI 五件 `canWrite`/`getString`/`getInt`/`putString`/
  `putInt`，**不提供猜型的 `get`/`put` 别名** —— 串与数是两套系统 API，按 `typeof` 推断就是发明策略；读缺失回**裸 JSON `null`** 而非 datastore 的 `{found,value}` 信封 —
  — 本契约值面只有 String/Int，`null` 不与任何合法值撞，空串/0 是真值一律不冒充缺失；写前**不预检 `canWrite`** —— 未授 `WRITE_SETTINGS` 抛 `ERR_PERMISSION_DENIED` 的判据唯一出处是 SPI，
  handler 再判一遍必漂移）+ 独立注入缝（`assemble.settingsHandler`，同 datastore/zip 不入 `systemHandlers` 束）+ JS facade（`settings.ts`，五方法与 SPI 1:1，缺失回 `null` 不回 `undefined`）。
  双侧钉子：`SettingsNamespaceHandlerTest` + `settings.test.cjs`。**授权引导页未进 `GrantPage`**（能力中心有 settings 入口时再开）；**生产已接**（`PlatformWiring.of` → `inject` → `installWithFiles` 喂 `settingsHandler` 独立缝；
  `PlatformWiringTest` 同路径覆盖）。
- 脚本文件目录：`files/scripts/<projectId>/` 标准化；要求 `assets→filesDir` **原子部署**（tmp 写入 + sha256 校验 + rename 替换），防止半截断电文件。
- `shell`：SPI 是 `:domain` 的 `ShellExecutor`（`exec(command, mode, timeoutMillis)` → `ShellResult`，超时是**实现者义务**而非可选项 —— §10 零 spawn 的副作用落在宿主侧，且铁律 3 禁止无限等待）；语义层 `ShellNamespaceHandler` 住 `:platform:capabilities`（参数校验、超时默认值 30s、`ShellMode` 字面量 `default`/`root`/`adb`），Android 实现 `AndroidShellExecutor`（`Runtime.exec("sh","-c",…)`）已落在 `:platform:system`。
  **Promise 封装的诚实口径**：`stdout`/`stderr` 为 `null` 表示「流无输出」，不是「有输出但为空」；退出码非 0 不是异常，原样回 `{code, stdout, stderr}` 由 JS 侧判；child_process 副作用限制如实上报。
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
- `zip` = 归档压缩/解压（`:domain` `ZipArchiver` SPI + `:platform:system` `JdkZipArchiver`）。契约级安全底线：**zip-slip 先验后写** —— 全包条目名校验通过才落字节（`../` 逃逸/绝对路径条目 → `ERR_INVALID_PARAM`，界内零写入；与 shell 超时同级的实现方义务）。压缩 tmp+rename 原子落位、空目录条目保留、垃圾包如实 `ERR_IO`（`ZipFile` 中央目录校验，不用对垃圾"零条目静默成功"的流式读）。
  **桥面已通**：handler（`:platform:capabilities` `ZipNamespaceHandler`，参数口径+错误原码透传，不碰归档字节）+ 独立注入缝（`assemble.zipHandler`）+ JS facade（`zip.ts`）。与 packager 的 npm 专用 zip（`NpmSnapshot` 固定 mtime+integrity）边界分明，不互相复用。
- **已落地（项目目录与装配期补部署）**：`:domain` 的 `ScriptPaths`（项目根 `files/scripts/<projectId>` 的单一事实来源 —— 纯路径计算、无 IO；拼错目录名编译期即可见）被三处复用（`:app-service:npm` 的 `NpmProjectLayout`、
  `AppShellKit` 装配、调度侧补部署）。`:app-service:scheduler` 的 `ScriptDeployRecovery` 在 `AppShellKit.assemble` 时跑一次：**只补缺、绝不覆盖**（用户手改的脚本原样留着）、
  空清单如实为空（`deployReport.changed == false`，不粉饰成"已恢复"）、单文件失败不带走整批（`deployFailures()` 列路径+原因）。来源合并（`scriptSources` 显式优先 + `assets/scripts/<projectId>/` 按项目补缺，
  单项目读失败跳过不炸整批）：配方经 `scriptProjects` + `assetReader` 两缝拿资产（配方本身不直连 AssetManager，保持纯 JVM 可测），`AppShellApplication.installWithFiles` 喂真实现（`assets.list("scripts")` 枚举 + `AndroidAssetsSource.readScripts()` 按需读）。
  **诚实边界**：本类不是部署器 —— 覆盖/版本/审批仍归 `script-repo` 的 `AtomicDeployer` 与 npm `InstallCoordinator`（sha256/journal/审批链路）；框架也没有内置脚本模板，
  来源给多少补多少。


### 9.7 OCR（P1）与插件（P2）
- `OcrProvider` SPI：**不内置 OCR 实现（2026-09-26 拍板）**——MLKit 插件基准实现（可下载模型）不做；`OcrProvider` 只保留可替换接缝（§2/§6），有需要的脚本自带外部方案。插件以独立 `:plugin:*` 模块 + 清单注册，native addon 走 `:node-runtime-build` 交叉编译管线（arm64 `.node`）。
- 插件加载: P2，plugin.json 声明 require 钩子/资源/权限；市场脚本不可加载任意插件（白名单）。**沙箱已裁（§18 第 1 项）** —— 这条白名单是能力面的，不再有"引到 QuickJS 子集"的去处。

---


package com.autoscript.domain.automation

import com.autoscript.domain.bridge.HandleRef

/**
 * 图像分析契约（docs/framework-design.md §9.2；JS 对偶 `auto.images`，
 * 对标 AutoJsPro v9 `images.matchTemplate/findImage` + `@autojs/opencv`）。
 *
 * 为什么住 `:domain`：与 [FrameSource] / `com.autoscript.domain.system.SensorSource`
 * 同一套理由 —— 真实现要碰 native 管线（`libopencv.so`，OpenCV 4.x）与
 * `BitmapFactory`，§6 要求 `:platform:*` 只依赖 `:domain`；「拿什么帧、算什么」
 * 与「像素在哪、谁来遍历」切开，桥面 handler 才是纯 JVM 可测的。
 *
 * **P0 范围钉死在三个操作**（刻意不预支的面，逐条给理由）：
 * - **只有 `decode`/`matchTemplate`/`findImage`**（+ 对称的 [release]）：§9.2 管线图里的
 *   灰度/裁剪/缩放/旋转/找色/特征(ORB) 全在 `libopencv.so`（P1）—— 那些操作**没有**
 *   脚本消费方之前不开桥面。§12.3 文档示例里出现的 `captureScreen()`/`toGrayscale()`
 *   因此同步改写真形态（截图归 `auto.screen`，灰度归 P1 的 native 面）。
 * - **P1 第一个算子 = [findColor]**（2026-09-25 落地，§7.7 有量化承诺的那一条）：
 *   灰度/裁剪/缩放/旋转/特征静候消费方，唯独找色在 §15 性能表上挂着一行
 *   `找色 < 10ms` **却没有任何实现在背后**—— 预算表不是愿望清单，先还这笔账。
 *   落地范围刻意窄：单色 + 逐分量容差 + 可选区域 + 回第一个命中，见该方法 KDoc。
 * - **P1 图像桥消费方 = 灰度/裁剪/缩放/旋转/特征五算子**（2026-09-29 落地，
 *   §9.2 末"桥面刻意不开"的推演兑现）：计算核与 host 语义门 2026-09-25 已落
 *   （`imgnative_gray/crop/resize/rotate/feature`），消费方已到 —— 灰度（匹配前
 *   预处理）、裁剪（把子图当独立模板）、缩放（同一套模板跑多分辨率设备）、
 *   旋转（把画面转正再匹配）、特征（刚性匹配之外的第二种找图语义，见
 *   [findFeature]），于是桥面同批开通：`toGrayscale`/`crop`/`resize`/`rotate`
 *   （→ 新帧）+ [findFeature]（→ 坐标，不产出帧）。
 * - **两个匹配方法同一个阈值键 `threshold`**：facade 曾一个发 `tolerance` 一个发
 *   `threshold` —— 同一个 opencv 概念（TM_CCOEFF_NORMED 得分 ≥ 阈值即命中）两个键名，
 *   两侧 mock 各自自洽所以漂移没被抓到。契约侧钉死一个名字，宿主不认的键不静默丢弃。
 * - **阈值域 [0,1]**（越界 → `ERR_INVALID_PARAM`，handler 校验；实现不再各自宽严不一）；
 *   置信度同域，与阈值可直接比较（不同实现的方法差异是实现细节，域是契约）。
 *
 * **帧的所有权归本 SPI**（与 `FrameSource.recycle` 的分界）：[decode] 发号
 * （[HandleRef.refId] 单调递增、[HandleRef.generation] 恒 1 —— 一个文件一个帧，
 * 不复用不缓存：缓存会让两个 refId 指向同一份像素，释放一个另一个即成野句柄），
 * 帧的 width/height **只在 decode 回包随帧给脚本**（脚本要拿真尺寸做坐标换算）。
 * 匹配 API 不再要求回传尺寸 —— 那是对实现报它自己已知的值，传了就是漂移面。
 *
 * **句柄纪律**（§7.4，与 `FloatingWindowHost`/`ScreenshotSource` 同形）：**未知/跨代**
 * 抛 `ERR_STALE_HANDLE`（"没见过的帧"）；**已释放的帧再放**同样 `ERR_STALE_HANDLE`
 * （与 `ScreenshotSource.recycle` 逐字同口径：放掉即从在场面表移除 —— 不提供
 * "静默成功"的第二次）。所以"重复放不炸"对脚本的含义是**同一个 finally 不会抛**：
 * 帧只要还握着（没放），怎么放都回 true；放过了就是不在场，如实 STALE。
 * 匹配时任一参数句柄已死 → 同码。
 *
 * **未匹配是答案不是异常**：`matchTemplate`/`findImage` 回 `null` = 屏上/图里没有
 * 达到阈值的位置 —— 调用方据此走自己的分支，不编 `ERR_NOT_FOUND`（那是 UiSelector 的语义）。
 *
 * **文件缺失是分类错误**：路径不存在 → `ERR_FILE_NOT_FOUND`（不是空帧、不是 null）——
 * 路径**必须是绝对的**（§18 第 9 项 2026-09-25 拍板：四层都不解析路径，相对写法按
 * `:main` 进程 CWD（= `/`）解析，`fromFile('part.png')` 只会回 `ERR_FILE_NOT_FOUND`）。
 *
 * **真机实现缺席时由装配层不注入**（桥回 `ERR_NOT_IMPLEMENTED`），**绝不塞凑数实现**：
 * 一个看不见像素的"内存分析器"只能靠自报坐标假装匹配成功 —— 那比没有更坏
 * （脚本会照着假坐标点下去）。所以本 SPI 不提供内存替身，`InMemory*` 那套不适用。
 */
interface ImageAnalyzer {

    /**
     * 从文件解码一帧（native 0 拷贝；内容 opaque）。
     * @throws IllegalArgumentException 空白路径（handler 折 `ERR_INVALID_PARAM`）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_FILE_NOT_FOUND` 路径
     * 不存在；`ERR_IO` 解码失败（不是合法图片）。
     */
    suspend fun decode(path: String): ImageFrame

    /**
     * 把一帧**已在内存里的像素**登记进帧表，回发句柄（§18 第 8 项 (b) 2026-09-25 拍板：
     * 截屏帧与 `decode` 帧**共用一个帧表**，"帧不通用"那条纪律取消）。
     *
     * 这是 `screen.capture()` 的落点：**发号侧归一** —— 本方法与 [decode] 共用同一号段
     * （同一个实现的同一张表），于是 `screen.capture()` 出的句柄可以直接当
     * `images.findImage()` 的 haystack，反过来 `images.release()` 也能放掉一帧截屏。
     *
     * 像素契约：[rgba] 是**紧密打包**的 `width * height * 4` 字节，通道序 **R,G,B,A**
     * （Android `Bitmap.getPixels(int[])` 的 `0xAARRGGBB` 逐像素打包出的 R,G,B,A ——
     * 不猜 `copyPixelsToBuffer` 的字节序。那是屏幕上已经存在的像素，不落盘、
     * 不经 JPEG 往返，保住 §7.7「屏幕帧→native」与
     * findColor「按分量精确夹」两条承诺）。进帧表时只做一次通道序 swizzle（→ BGRA，
     * 帧表不变式），并拷出自有缓冲（不持有调用方的 `ByteArray`）。
     *
     * @throws IllegalArgumentException 尺寸非正，或字节数与尺寸不符（调用方的错，
     *   实现不猜也不补零）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_IO` native 拒收。
     */
    suspend fun ingest(width: Int, height: Int, rgba: ByteArray): ImageFrame

    /**
     * 显式释放帧句柄（幂等；JS `FrameSource.recycle` 对偶）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 未知/跨代句柄。
     */
    suspend fun release(handle: HandleRef)

    /**
     * 模板匹配：在 [haystack] 里找 [needle]，得分 ≥ [threshold] 即命中。
     *
     * 得分是 **TM_CCOEFF_NORMED**（相关系数，自带亮度归一）。已知代价，脚本作者该知道：
     * **方差≈0 的模板**（纯色块/没有纹理的图）会让整个结果面恒 1.0 —— 不是"到处都匹配"，
     * 是"得分在这个模板上没有区分度"。此时命中的坐标稳定可复现但不唯一，别把它当"就是这块"。
     * 要区分"就是这块"请拿带纹理的模板，或改用 [findColor] 后自己核对邻域。
     *
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 任一帧已死。
     */
    suspend fun matchTemplate(haystack: HandleRef, needle: HandleRef, threshold: Double): ImageMatch?

    /**
     * 找图（[matchTemplate] 的调用侧别名，阈值语义同）：名字对齐 Pro v9 的 `findImage`。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 任一帧已死。
     */
    suspend fun findImage(haystack: HandleRef, needle: HandleRef, threshold: Double): ImageMatch?

    /**
     * 找色（§9.2 native 面第一个 P1 算子；§7.7 承诺 `findColor` 1080p < 10ms）：
     * 在 [haystack]（或其 [region] 子矩形）里找**第一个**与 [color] 的分量差各不
     * 超过 [tolerance] 的像素，回它的全帧坐标与实际像素分量。
     *
     * 与 matchTemplate 的分界：那是"整块图案在哪"，这是"这个色在哪"—— 找色不问
     * 图案、形状、连不连通，只看分量是否落在容差带内（OpenCV `inRange` 的逐分量
     * 包含语义，见 `imgnative_color`）。
     *
     * **命中多个时回哪一个**：稳定可复现的一个（native `findNonZero` 点列的首个），
     * 但**不承诺**"离左上角最近"或别的排序口径 —— 那会是另一套没在契约里的策略，
     * 要挑最近/最大连通域的脚本得自己拿坐标再筛。
     *
     * **未命中是答案不是异常**：回 `null`（扫过了、没有），**不编** `ERR_NOT_FOUND`。
     * 与"区域扫过 0 像素"（空图/空区域 → `ERR_INVALID_PARAM`）不是一回事。
     *
     * @param color 目标色四分量 `[r,g,b,a]`，各 ∈ `[0,255]`（**序由字节序解释为
     *   R,G,B,A**，与 Android `Bitmap` 的 `0xAARRGGBB` 同序，不另立一套）；
     * @param tolerance 逐分量容差 ∈ `[0,255]`（非欧氏距离：每个分量各自带 ±tolerance
     *   的上下界，落在带内即命中）；
     * @param region 可选子矩形 `[x,y,w,h]`：给了就必须**整体**落在帧内（越界 →
     *   `ERR_INVALID_PARAM`，不静默裁剪成"只看得到的那半"——那会让脚本以为扫过全区域）；
     *   缺省 null = 全帧。命中坐标是**全帧坐标**（区域只是搜索范围，不是坐标系）。
     * @throws IllegalArgumentException 分量/容差越界或 region 形状不成立
     *   （handler 折 `ERR_INVALID_PARAM`）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 帧已死；
     *   `ERR_INVALID_PARAM` 区域越出帧界；`ERR_IO` native 拒收。
     */
    suspend fun findColor(
        haystack: HandleRef,
        color: List<Int>,
        tolerance: Int,
        region: List<Int>? = null,
    ): ColorHit?

    /**
     * 灰度化：`frame` → **新帧**（4 通道 BGRA，三通道同灰值、alpha 原样带过去）。
     *
     * **产出新帧（§9.2 native 面 2026-09-25 起）**：帧表是所有权表 —— 本方法把
     * 新帧也落进同一张表，回新句柄；**原帧不被就地改灰**，两个句柄各自在场、
     * 各自独立 release。产出帧在原生侧（`imgnative_gray`）按 0.299R+0.587G+0.114B
     * （OpenCV `COLOR_BGRA2GRAY`）取灰 —— 权重不经本层（也不经过桥面）。
     *
     * 用途由调用方定（§9.2 未开桥面时的推演现在兑现）：匹配前的预处理
     * （灰帧不改变 findImage 的结果，只是省一点计算）、给下游取"亮度面"。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 帧已死。
     */
    suspend fun toGrayscale(frame: HandleRef): ImageFrame

    /**
     * 裁剪：`frame` 的 `region` 子矩形 → **新帧**（尺寸 = region 的 w/h；不含
     * region 外像素）。**必须给 region**（缺省 = 参数错，不是整帧副本 —— 语义上
     * "取一个子区域"，想要整帧副本就明写整帧区域）。
     *
     * [region] 契约与 [findColor] 同源（`imgnative_crop` 复用 `resolve_region`）：
     * `[x,y,w,h]`，要求**整体落在帧内**（`x+w == 宽` 贴边合法，越界 → 参数错，
     * 不静默裁剪成"只看得到的那半"）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 帧已死；
     *   `ERR_INVALID_PARAM` region 越出帧界。
     */
    suspend fun crop(frame: HandleRef, region: List<Int>): ImageFrame

    /**
     * 缩放：`frame` → 目标尺寸 `width` × `height` **新帧**（像素值重算，逐点
     * 不一定等于原帧）。插值固定 `INTER_LINEAR`（不做入参：NEAREST 放大是块状
     * 马赛克，CUBIC/LANCZOS 更贵且在截图这类非照片输入上无可证增益 —— 多一个
     * 入参就多一个"选错静默换答案"的漂移面）。
     *
     * 入参是**目标尺寸**不是倍数（倍数是调用方算的浮点）：尺寸真值只有产出帧
     * 的地方知道，让下游自己推是猜。单边配额 16384（16384²×4 ≈ 1GB，再往上是
     * 笔误把字节数当宽高 —— 配额拒收与尺寸不合法同码）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 帧已死；
     *   `ERR_INVALID_PARAM` 目标尺寸非正/超配额。
     */
    suspend fun resize(frame: HandleRef, width: Int, height: Int): ImageFrame

    /**
     * 旋转：`frame` 绕帧中心逆时针转 `degrees` → **新帧**。角度**有限**
     * （NaN/Inf → 参数错：三角函数吃掉它们不报错，矩阵是垃圾）。
     *
     * 画布是 **expand**（包络公式 `round(|w·cosθ|+|h·sinθ|)` ×
     * `round(|w·sinθ|+|h·cosθ|)`，包住整图不静默裁像素）；0°/360° 恒等（360°
     * 先归一，不因浮点余数差一像素）。插值固定 LINEAR、填充固定 REPLICATE
     * （黑边是找色的假阳性源）。想要"旋转裁剪"：先 [rotate] 再 [crop]。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 帧已死；
     *   `ERR_INVALID_PARAM` 角度非有限/画布超配额。
     */
    suspend fun rotate(frame: HandleRef, degrees: Double): ImageFrame

    /**
     * 特征匹配（§9.2 native 面收官算子，P1 图像桥消费方 2026-09-29 开通）：
     * 在 [scene] 里找 [template] **不同尺寸/轻微旋转变体**，回**模板中心**在场景
     * 中的坐标与置信度 —— 与 [matchTemplate] 的左上角 + 模板尺寸不同：特征匹配
     * 没有"模板尺寸"概念（模板在场景里多大是未知的），回中心让脚本直接点下去。
     *
     * 链全固定（原文见 `imgnative_feature`）：ORB(nfeatures=1000) →
     * BFMatcher(HAMMING) knn k=2 → Lowe ratio 0.75 → 中位数偏移 ±3px 几何一致性
     * 计数。**未匹配是答案**（回 `null`，与 [matchTemplate] 同一条纪律）——
     * "场景里没有这个物体"与"没找到达到阈值的位置"是同一种答案；纯色模板
     * （空描述子）也落未匹配，不是异常。刚性匹配（matchTemplate，模板尺寸必须
     * 一致）与特征匹配（容忍缩放/旋转）是两种找图语义，脚本按场景任选其一。
     *
     * 置信度与 [matchTemplate] 同域 `[0,1]`（几何一致内点数 / good 数），可直接
     * 比较；无阈值入参 —— 本方法只报数，不替脚本决定"多少算找到"（阈值由调用方
     * 拿坐标/置信度自己判）。
     * @throws com.autoscript.domain.core.AutojsException `ERR_STALE_HANDLE` 任一帧已死。
     */
    suspend fun findFeature(scene: HandleRef, template: HandleRef): FeatureHit?
}

/**
 * 一次匹配命中（左上角坐标 + 尺寸 + 置信度；对齐 opencv 语义）。
 * `width/height` 是**模板在画面里被匹配上的区域尺寸**（通常 = 模板尺寸），不是画面尺寸。
 */
data class ImageMatch(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val confidence: Double,
)

/**
 * 一次找色命中（§9.2 native 面 P1 第一个算子）。
 *
 * `x`/`y` 是**全帧坐标**（`region` 只是搜索范围，不是坐标系）；`r`/`g`/`b`/`a`
 * 是命中点的**实际像素分量**（不一定是目标色的逐字值 —— 容差带内的哪一个被扫到
 * 就回哪一个，脚本要拿它做二次判断时看的是真值，不是自己传进去的期望）。
 */
data class ColorHit(
    val x: Int,
    val y: Int,
    val r: Int,
    val g: Int,
    val b: Int,
    val a: Int,
)

/**
 * 一次特征匹配命中（§9.2 `imgnative_feature`；`images.findFeature`）。
 *
 * `x`/`y` 是**模板中心**在场景中的坐标（像素）—— 与 [ImageMatch] 的左上角 +
 * 模板尺寸不同：特征匹配没有"模板尺寸"概念（模板在场景里多大是未知的），回
 * 中心让脚本直接点下去。`confidence` 与 [ImageMatch] 同域 `[0,1]`（几何一致
 * 内点数 / good 数），可直接比较。
 */
data class FeatureHit(
    val x: Int,
    val y: Int,
    val confidence: Double,
)

// bridge/image —— feature 族（ORB 特征匹配 + Lowe ratio，不产出帧、只回坐标）
//
// 2026-10-01 D7 自 `imgnative.cpp` 拆出（同批拆出 match 族；共享面的口子见
// `imgnative_internal.h`）。**语义逐字未改**：机制与实测记录都留在函数自己的
// 注释里（旋转容忍的边界、BGRA 直喂的理由、整屏 ORB 配额那一段）。
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgproc.hpp>

#include "imgnative_internal.h"

// 本 TU 用到的共享面（定义见 imgnative_internal.h / imgnative.cpp）。
using imgnative::find_locked;
using imgnative::frame_is_normalized;
using imgnative::frame_mutex;
using imgnative::resolve_region;

extern "C" {

// ── feature：在场景帧里找模板帧（ORB 特征匹配 + Lowe ratio，§9.2 管线里的"特征"）。
//
// **桥面此刻同样不对脚本开这个方法**（`:domain ImageAnalyzer` 五方法里没有它，
// `ImagesNamespaceHandler` 也不认 `feature`）：与 gray/crop/resize/rotate 同一条纪律 ——
// 先落计算核 + host 语义门，等真出现消费方再开桥面。与 matchTemplate 的分界：
// 那是刚性模板匹配（逐像素相关，模板转 30°/缩一半就没分了）；这是特征匹配
// （ORB 描述子 + 汉明距离，对旋转/尺度有一定容忍 —— host 实测子图转 30° 后
// 仍有 53 个 crosscheck 匹配，top 距离 0/0/1/1/1）。
//
// 为什么是 ORB 而不是 SIFT/SURF/AKAZE：ORB 描述子是二进制（32 字节），汉明距离
// 用 BFMatcher 硬件友好（POPCNT）；SIFT 是浮点描述子（L2 距离，又一 competent
// 的距离口径），SURF 非free（`OPENCV_ENABLE_NONFREE=OFF`，构建轨里写死了）。
// ORB 参数固定 `nfeatures=1000` 不做入参（与 resize/rotate 的"固定 LINEAR"同一条
// 纪律）：host 实测 nfeatures=500 与 1000 在同一子图上出的描述子**逐字节一致**
// （mean=0.0 max=0）—— 参数只 control 截断位置，不改描述子本身，所以调参不换
// 答案，只换"留几个"。1000 是"够用的上限"，不是"最优值"。
//
// 旋转容忍的边界（host 实测，先写下来免得被高估）：ORB 有旋转不变性（灰度质心法定
// 向），但**只在"特征点本身被转"时成立** —— 子块转 30° 后整块内容相对场景转了，
// 描述子仍能对上（crosscheck top 距离 0/0/1/1/1），但几何阶段的中位数偏移假设
// （"模板是场景子块"→ 偏移为常数）不再成立，内点只剩 1 个 → 按"几何不一致"判
// 未匹配。所以"转 30° 仍命中"是不成立的期望：旋转容忍指的是**描述子层面**，
// 不是"本算子的几何验证也跟着转"。真要转着找，得先把单应估计（findHomography，
// calib3d 模块）接进来 —— 那是另一个算子，不是调参能解决的。
//
// 为什么 BGRA 直喂（不先转灰）：host 实测 BGRA 直喂与手转灰出的关键点/描述子
// **逐字节一致**（sameDesc=1）—— ORB 内部自己按第一通道取灰（对 BGRA 就是 B），
// 本管线的输入（截图/PNG）B 通道与亮度强相关，转灰是冗余步骤。冗余步骤不是无害的：
// 多一次 cvtColor 就多一个"转错了静默换答案"的漂移面。
//
// 【2026-10-01 场景侧配额】整屏截图（短边 > kWholeScreenShort）上 ORB 的
// `nfeatures` 是**全局 Harris 配额**，不是逐区域配额 —— 真机 1080×2400 实测：
// nf=1000 时 1000 个点全落在高对比区，UI 常用的小区域**一个点都没有**
// （370×80=0、300×150=0、200×150=0、540×600 只有 7 个），于是「场景里明明有
// 这个图标，findFeature 恒 found=0」。抬到 8000（并把 et 31→10，否则边缘 31px
// 内不产生特征，细条状 UI 全被切掉）后覆盖到位：370×80=16、300×150=50、
// 200×150=31、540×600=489。代价 29.1 → 46.0ms（真机 ×7 中位，+58%）——
// 与「多花 17ms 换掉一个恒假的算子」相比是划算的（判据见 §7.7）。
//
// 为什么不给模板侧也抬：小图的 1000 配额**从来不是瓶颈**（400×300 夹具的 461 个点
// 一个不落全在 1000 以内，抬到 8000 结果逐字节相同），而模板侧保持缺省值
// （1000/et=31/ft=20）是 host_feature 既有 32 例**逐字不变**的前提。
constexpr int kSceneNFeatures = 8000;      // 整屏场景的 ORB 配额
constexpr int kSceneEdgeThreshold = 10;    // 整屏场景的边界阈值（31 会切掉细条状 UI）
constexpr int kWholeScreenShort = 640;     // 短边超过此值 = 整屏截图形态
// 内点在模板坐标里的**最小铺开度**（任一边跨度 / 该边长）。
// 真命中实测：dots 夹具 0.120、真机 300×150 0.577、540×600 0.820；
// 假阳实测 0.053/0.032。门槛 0.10 落在两者中间且**不比既有夹具更严**
// （夹具 0.120 刚好在门槛之上 —— 这是刻意的：新判据不得把既有绿夹具变红）。
constexpr double kMinInlierSpan = 0.10;
// 零关键点垫边重试的边宽（见 imgnative_feature 里的「零关键点垫边重试」段）。
// 32 的取值依据：短边 +2*32 = +64 越过 ORB 预筛的 2*et=62 门槛，同时给内容两侧
// 留下约一个特征尺度（ft=20 的 patch 半径 2*sqrt(2)*20 ≈ 56，32 够覆盖半圈邻域）。
// 探针扫过 16/32/48：16 不足（短边小的模板仍在 62 门槛下），48 越救越靠复制缝编答案。
constexpr int kPadRetry = 32;
// 零关键点垫边重试的**复核门**：报出位置的模板窗（严格坐标，不放松）与模板自身的
// TM_CCOEFF_NORMED 必须 >= 此值。为 0.6 的依据（探针 /tmp/ffix/ver4.cpp）：
// 重试救回的 30 个真命中在干净/σ3/σ8/0.5px 平移四种模板退化下该量恒 **>= 0.909**，
// 而"报出位置根本没有模板像素"的那批 <= 0.501。0.6 落在两簇之间的空档里，
// 离真命中下界留了 0.3 的余量（模板与场景来自不同次截图、有噪声/压缩差时不误伤），
// 又把 3 个纯编答案挡掉 2 个。门槛再往上（0.85）就开始切 0.5px 平移档的真命中了。
constexpr double kRetryVerify = 0.60;

// 匹配链（固定，不做入参）：ORB detectAndCompute → BFMatcher(HAMMING, crossCheck)
// → Lowe ratio（knn k=2，阈值 0.75）→ 几何一致性计数。host 实测 ratio=0.7/0.75/0.8
// 在子图->全图上 good=29/30/33、几何正确都是 19 —— 阈值在 0.7~0.8 间不换答案，
// 0.75 取中（Lowe 原论文值），不是调出来的"最优"。
//
// 回包是**模板中心在场景中的坐标**（不是左上角 —— 与 matchTemplate 的 ImageMatch
// 不同：特征匹配没有"模板尺寸"的概念，模板多大在场景里是未知的；回中心让脚本
// 直接点下去）。out_x/out_y 是 double（亚像素无意义 —— ORB 关键点是像素级，
// double 只是不丢坐标小数；脚本取整即用）。
//
// **未匹配是答案不是异常**（与 matchTemplate 的 out_match=0 同一条纪律）：
// status 仍回 IMG_OK，*out_found=0 且 x/y/confidence 全 0。空描述子（纯色模板，
// ORB 找不到关键点）→ 同样是"未匹配"（不是 IO 错 —— 图是合法的图，只是没有特征）。
// 调用方判据只有 status + *out_found（与 match 同口径：拒收早退不写出参）。
//
// 置信度 = 几何一致内点数 / good 数（[0,1]，与 matchTemplate 的 confidence 同域，
// 可直接比较）。阈值由调用方定（与 matchTemplate 的 threshold 同位置 —— 本层
// 只报数，不替脚本决定"多少算找到"）。
int imgnative_feature(int64_t scene, int64_t templ, int32_t* out_found,
                      double* out_x, double* out_y, double* out_conf) {
    if (out_found == nullptr || out_x == nullptr || out_y == nullptr || out_conf == nullptr)
        return IMG_ERR_INVALID_PARAM;
    try {
        const std::lock_guard<std::mutex> lk(frame_mutex());
        const cv::Mat* s = find_locked(scene);
        const cv::Mat* t = find_locked(templ);
        if (s == nullptr || t == nullptr) return IMG_ERR_STALE_HANDLE;
        if (!frame_is_normalized(*s) || !frame_is_normalized(*t)) return IMG_ERR_IO;

        // 场景侧：短边 > 640 视为**整屏截图**，配额抬到 kSceneNFeatures —— 见下方长注。
        const int scene_short = std::min(s->cols, s->rows);
        const bool whole_screen = scene_short > kWholeScreenShort;
        auto orb = whole_screen
            ? cv::ORB::create(kSceneNFeatures, 1.2f, 8, kSceneEdgeThreshold)
            : cv::ORB::create(1000);
        std::vector<cv::KeyPoint> ks, kt;
        cv::Mat ds, dt;
        orb->detectAndCompute(*s, cv::noArray(), ks, ds);
        // 模板侧保持缺省不变（1000/et=31/ft=20）—— 小图的配额不是瓶颈（同 1000 档的
        // 点全留下），而缺省值让 host_feature 的既有夹具逐字不变（见 kSceneNFeatures 注）。
        auto orb_t = cv::ORB::create(1000);
        orb_t->detectAndCompute(*t, cv::noArray(), kt, dt);
        // 零关键点垫边重试（2026-10-01，治「薄/小模板恒 found=0」）
        //
        // 病灶不在模板"没特征"。ORB 丢掉短边很短的整条模板是一种**预筛**：
        // 每个八度的 `runByImageBorder(kp, size, edgeThreshold)` 在某边 <= 2*et 时
        // 直接清空该层全部关键点 —— 缺省 et=31 意味着**短边 <= 62 的模板侧面恒为零**
        // （与内容无关）。真机 UI 上一条工具行 1080×60、一个图标面板 120×90 全落在这段。
        // 探针 /tmp/ffix/et2.cpp 实测（真位置在场的全帧，短边 48~62 共 270 样本/档）：
        //   def(et=31) 对=0 错=0 kp0=270  |  et10(反解 et) 对=0~2 kp0≈250  |  pad32 对=25~43
        // —— 「按短边反解 et」这条路**被量死了**：预筛过了以后这些点仍进不了匹配
        // （短边 37~64 的模板，点只出在粗八度上，那里的 patch 已被模板边框裁掉，
        // 描述子与场景同位置的点对不上，hamming 19~64、Lowe ratio 恒 >= 0.75）。
        // 真正管用的是**给模板补一圈上下文**：反射复制 32px 后短边 +64 越过了 62，
        // 内容两侧也有了真实的邻域，描述子重新可比。
        //
        // 只对"缺省 ORB 一个点都没给"的模板生效 —— 有点的模板逐字节不变（host 侧
        // 夹具 kp_t=69/67 均不触发）。全帧 1080×2400、448 个已知真位置的样本上：
        //   off 对=48 错=2  |  pad 对=78 错=5（其中重复区 3、纯编 2，见 /tmp/ffix/fp10.cpp）
        // 救回来的 30 个（120×90 +5、80×60 +7、160×120 +8、200×150 +3、300×150 +2、
        // 370×80 +5）里 28 个落在真位置 3px 内。剩下 2 个是「报出位置根本没有模板
        // 像素」的纯编答案 —— 垫边补的那圈上下文既是这条路的**收益来源**，也是它的
        // **代价来源**：它让"内容 + 一圈复制缝"整体可比，于是复制缝上的自匹配偶尔也
        // 能凑出一个几何一致的偏移。所以这条路挂一道**像素复核**（见 kRetryVerify，
        // 在函数末尾）：报出位置的模板窗与模板自身直接打一次相关，>= kRetryVerify
        // 才认。0.6 把 3 个纯编挡掉 2 个，四种模板退化下的 30 个真命中一个不伤 ——
        // 这是"多花一次模板尺寸的 matchTemplate 换掉一类假阳"的买卖。
        // 不做「只留模板矩形内的点」（探针 padin）：过滤描述子矩阵会挪动 queryIdx，
        // 实测与全留逐字同解，不值得多一份索引纪律。
        // 时间上是一次额外的模板级 ORB（模板尺寸量级）—— 只在原来恒 found=0 的
        // 模板上才付。**这条不是一次性成本**：本函数没有模板侧缓存（`NeedlePrep`
        // 那套缓存是 match 的，别混），每次调用都重跑 ORB，所以薄模板是**每次**
        // 多付一次模板级 ORB（真机 120×90 实测 45.3 → 46.9ms；整屏场景档本身更贵，
        // 见 §9.2 的实测区间）。要低延迟轮询就控制调用频率，别指望第二次便宜。
        bool used_retry = false;
        if (kt.empty()) {
            cv::Mat tpad;
            cv::copyMakeBorder(*t, tpad, kPadRetry, kPadRetry, kPadRetry, kPadRetry,
                               cv::BORDER_REPLICATE);
            kt.clear();
            dt.release();
            orb_t->detectAndCompute(tpad, cv::noArray(), kt, dt);
            for (auto& kp : kt) kp.pt = cv::Point2f(kp.pt.x - kPadRetry, kp.pt.y - kPadRetry);
            used_retry = true;
        }
        // 空描述子（纯色图）= 没有特征可比 = 未匹配（答案，不是异常）
        if (ds.empty() || dt.empty() || ks.empty() || kt.empty()) {
            *out_found = 0;
            *out_x = *out_y = *out_conf = 0.0;
            return IMG_OK;
        }

        cv::BFMatcher matcher(cv::NORM_HAMMING);
        std::vector<std::vector<cv::DMatch>> knn;
        matcher.knnMatch(dt, ds, knn, 2);
        std::vector<cv::DMatch> good;
        for (const auto& p : knn) {
            if (p.size() == 2 && p[0].distance < 0.75 * p[1].distance) good.push_back(p[0]);
        }
        // good 太少（<4）连几何验证都做不了 = 未匹配
        if (good.size() < 4) {
            *out_found = 0;
            *out_x = *out_y = *out_conf = 0.0;
            return IMG_OK;
        }

        // 几何一致性：正确匹配应满足 scenePt ≈ templPt + offset（模板是场景子块时
        // offset 为常数）。用"中位数偏移 ±3px 内点占比"做验证 —— 不用 findHomography
        //（那要 calib3d 模块；中位数偏移在平移场景下是同解，且 host 实测 19/30 内点）。
        std::vector<double> dxs, dys;
        dxs.reserve(good.size());
        dys.reserve(good.size());
        for (const auto& m : good) {
            dxs.push_back(ks[m.trainIdx].pt.x - kt[m.queryIdx].pt.x);
            dys.push_back(ks[m.trainIdx].pt.y - kt[m.queryIdx].pt.y);
        }
        std::sort(dxs.begin(), dxs.end());
        std::sort(dys.begin(), dys.end());
        const double mdx = dxs[dxs.size() / 2], mdy = dys[dys.size() / 2];
        int inl = 0;
        for (const auto& m : good) {
            const double dx = ks[m.trainIdx].pt.x - kt[m.queryIdx].pt.x;
            const double dy = ks[m.trainIdx].pt.y - kt[m.queryIdx].pt.y;
            if (std::fabs(dx - mdx) < 3.0 && std::fabs(dy - mdy) < 3.0) ++inl;
        }
        // 内点 <4 = 几何不一致 = 未匹配（误报的形状：棋盘格模板 top 距离 60+，
        // 连 ratio 都过不了几个，更到不了这里）
        if (inl < 4) {
            *out_found = 0;
            *out_x = *out_y = *out_conf = 0.0;
            return IMG_OK;
        }
        // 内点**必须铺开在整个模板上**（本轮新增的第二个几何判据，见 kMinInlierSpan）。
        // 只数内点个数挡不住一种假阳：几枚误配恰好凑出同一个偏移（真机实测：
        // 200×150 模板报错 208.7px，inl=7、conf=0.64 —— 比真命中的 conf 还漂亮），
        // 但它们的模板侧坐标挤在 10.6×4.8 的一小块里（占模板 5%/3%）—— 那不是
        // 「模板出现在场景里」，是「模板的一小块出现在场景里」。
        // 真命中实测跨度：dots 夹具 0.120/0.160、真机 300×150 0.577/0.107、
        // 540×600 0.820/0.637。门槛取**任一边 ≥ kMinInlierSpan**（不是两边都要）：
        // 「图标横向铺开、纵向只有一行字」这形态的 y 跨度天然就小（真机 300×150
        // 的 y 只有 0.107），要求两边都够会把真命中挡掉；一条边铺开就已经否掉
        // "挤在一角"这个形状。
        {
            double bx0 = 1e18, bx1 = -1e18, by0 = 1e18, by1 = -1e18;
            for (const auto& m : good) {
                const double dx = ks[m.trainIdx].pt.x - kt[m.queryIdx].pt.x;
                const double dy = ks[m.trainIdx].pt.y - kt[m.queryIdx].pt.y;
                if (std::fabs(dx - mdx) < 3.0 && std::fabs(dy - mdy) < 3.0) {
                    const cv::Point2f& p = kt[m.queryIdx].pt;
                    bx0 = std::min(bx0, static_cast<double>(p.x));
                    bx1 = std::max(bx1, static_cast<double>(p.x));
                    by0 = std::min(by0, static_cast<double>(p.y));
                    by1 = std::max(by1, static_cast<double>(p.y));
                }
            }
            const double sx = (bx1 - bx0) / t->cols, sy = (by1 - by0) / t->rows;
            if (sx < kMinInlierSpan && sy < kMinInlierSpan) {
                *out_found = 0;
                *out_x = *out_y = *out_conf = 0.0;
                return IMG_OK;
            }
        }
        // 命中位置 = **模板中心**在场景中的坐标（契约字面口径）= 中位偏移 + 模板半宽高。
        //
        // 【2026-10-01 修】原先报的是「内点在场景中的质心」，旧注释写着「内点质心即
        // 模板在场景中的位置」—— 那句只在**内点在模板里均匀铺开**时成立，而 ORB 的
        // 内点从来不是均匀的（只有文字行/图标边缘这类高对比处才出特征，它们挤在模板
        // 的某几行上），于是质心被拽向特征密集的一侧。实测偏差（探针 /tmp/ffix/cen.cpp，
        // 真值 = 模板左上角 + 半尺寸）：
        //
        //   真机 540×600  err 82.4  | 真机 300×150  err 60.8  | 合成 UI 屏 200×150  err 31.6 / 20.5
        //   dots 夹具 200×150 err 1.0（内点恰好铺得开，所以既有夹具看不见这个缺陷）
        //
        // 同一批样本上「中位偏移 + 模板中心」误差 0.0~0.8px。中位偏移本来就是这条链
        // 里已经在算的量，换公式零额外成本。对一个「回坐标让脚本点下去」的算子，
        // 偏 80px 等于点错控件 —— 语义错，不是精度问题。
        // 像素复核（只挂在垫边重试这条路上，见上面的「零关键点垫边重试」段）。
        // 判据本身不新：`matchTemplate` 就是这个算子族里的那个算子，这里只是把它
        // 当成**一次单点判决**用。报出窗必须完整落在帧内（合同时报出的是"模板在
        // 场景中的位置"，场景里没有这块区域就谈不上命中 —— 越界直接未匹配，
        // 不做裁剪），再直接读该点的 TM_CCOEFF_NORMED。
        if (used_retry) {
            const int vx = static_cast<int>(std::lround(mdx));
            const int vy = static_cast<int>(std::lround(mdy));
            if (vx < 0 || vy < 0 || vx + t->cols > s->cols || vy + t->rows > s->rows) {
                *out_found = 0;
                *out_x = *out_y = *out_conf = 0.0;
                return IMG_OK;
            }
            cv::Mat vr;
            cv::matchTemplate((*s)(cv::Rect(vx, vy, t->cols, t->rows)), *t, vr,
                              cv::TM_CCOEFF_NORMED);
            if (vr.at<float>(0, 0) < kRetryVerify) {
                *out_found = 0;
                *out_x = *out_y = *out_conf = 0.0;
                return IMG_OK;
            }
        }
        *out_found = 1;
        *out_x = mdx + t->cols / 2.0;
        *out_y = mdy + t->rows / 2.0;
        *out_conf = static_cast<double>(inl) / static_cast<double>(good.size());
        return IMG_OK;
    } catch (const cv::Exception&) {
        return IMG_ERR_IO;
    }
}

}  // extern "C"

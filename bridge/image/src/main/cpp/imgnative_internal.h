// bridge/image —— 计算核的**内部**共享面（`imgnative*.cpp` 三个 TU 共用；不进 JNI、不进 ABI）
//
// 为什么有这一件（2026-10-01 D7「大文件拆分」）：计算核原先是**一个** 1440 行的
// `imgnative.cpp`，而它的共享状态（帧表 + 两把锁）与十个算子搅在一起 —— 读的人
// 得先翻过整个文件才知道「谁能动帧表」。按 host 测试已有的算子族刀口拆成三个 TU
// （`imgnative.cpp` 帧表+基础/产出算子、`imgnative_match.cpp` match 族、
// `imgnative_feature.cpp` feature 族），**共享状态只留一份**，跨 TU 的那几条口子
// 在这里显式列出来。
//
// 拆分的硬约束（踩了就是真机事故，不是风格问题）：
//   * **帧表必须单实例**：C++ 的匿名命名空间是**每 TU 一份** —— 把 `g_frames`
//     抄进第二个 TU，两个 TU 会各自维护一张表，`decode` 出的帧在 `match` 里
//     查不到（症状 = 随机 STALE，且与编译顺序有关）。所以帧表与两把锁的定义
//     **只住 `imgnative.cpp`**，其余 TU 经下面的口子访问；
//   * **锁纪律不变**：`frame_mutex()` 仍是那一把全局互斥量，帧表段的锁范围
//     （查找/发号/擦除）与拆分前逐字相同 —— 口子只是把「同文件内的直接访问」
//     换成「跨文件的具名调用」，没有一格锁被挪出或挪进。
#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>

#include <opencv2/core.hpp>

// match 金字塔路径的 kill switch（**定义**在 imgnative.cpp；host 差分双跑直接翻它）。
extern std::atomic<bool> g_force_exact;

// 状态码：与桥面 ERR_* 一一对应（Kotlin 侧原码透传，不做二次折叠）。
// 沿用原来的全局名（拆分前它们就在匿名命名空间里以这些名字被十个入口使用）——
// 改名的收益只是"看着更整齐"，代价是三个 TU 里几十处无信息 churn。
constexpr int IMG_OK = 0;
constexpr int IMG_ERR_STALE_HANDLE = 1;
constexpr int IMG_ERR_FILE_NOT_FOUND = 2;
constexpr int IMG_ERR_IO = 3;
constexpr int IMG_ERR_INVALID_PARAM = 4;

/** `color` 未命中的 x 哨兵（0,0 是合法首像素坐标，不能拿它当"没有"）。 */
constexpr int32_t IMG_MISS = -1;

namespace imgnative {

/** 帧表互斥量（跨 TU 同一把）。帧表段的每个操作都必须持它。 */
std::mutex& frame_mutex();

/** 调用方必须已持 [frame_mutex]。返回 nullptr = 未知/已释放/跨代。 */
cv::Mat* find_locked(int64_t ref);

/** 帧表在场判断（与 [find_locked] 同源）。调用方必须已持 [frame_mutex]。 */
bool contains_locked(int64_t ref);

/**
 * 帧表不变式谓词：**在场帧恒 4 通道 8 位**（decode/ingest 归一保证）。
 * 具名而不是内联写 `channels()==4 && depth()==CV_8U`：这条是可被产出算子违反的，
 * 值得一个能被搜索、被讨论的名字（`imgnative_color` 的 Vec4b 回读在 3 通道帧上
 * 会静默读进下一行首字节，见该算子的守卫）。
 */
bool frame_is_normalized(const cv::Mat& m);

/**
 * 把可选 region（x,y,w,h）解析成一个**保证落在帧内**的矩形。调用方必须已持
 * [frame_mutex]（要读帧尺寸）。`region == nullptr` = 全帧；越界/非正宽高 → false
 * （找色、裁剪、match 的 region 共用这一个判据，不各写一份）。
 */
bool resolve_region(const cv::Mat& frame, const int32_t* region, cv::Rect* out);

/**
 * match 族两张派生缓存（模板端 prep / 场景端 prep）的失效钩子 —— 定义在
 * `imgnative_match.cpp`（缓存与它同住一个 TU）。`release` 擦掉帧表条目后调它，
 * 保证「帧没了，它的派生图也没了」。
 */
void drop_prep_caches(int64_t ref);

}  // namespace imgnative

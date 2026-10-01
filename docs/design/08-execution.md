## 8. 执行层设计

### 8.1 引擎抽象（`:domain`，纯 Kotlin）—— 已落定形态
```kotlin
interface ScriptEngine {                              // 实现在 :engine:node-process
  val id: EngineId
  suspend fun execute(run: EngineRunRequest): EngineRunReceipt
  suspend fun stop(): StopResult                      // 四步 quiesce 入口
  suspend fun kill(): KillCause                       // 仅 RuntimeController 有调用权（§4.1 kill 权威）
  suspend fun status(): EngineStatus
}
data class EngineRunRequest(projectId, scriptPath, args, runNonce, timeoutMillis)
data class EngineRunReceipt(runId, handle: HandleRef)
enum class EngineStatus { IDLE, BOOTING, RUNNING, QUIESCING, STOPPED, CRASHED }
sealed interface StopResult { Clean; TimedOut(partial) }
enum class KillCause { REQUESTED, WATCHDOG_HEARTBEAT, WATCHDOG_CPU, OOM, ENGINE_REQUEST, DRIFT, TIMEOUT }

interface EnginePool {                                // 实现在 :app-service:runtime
  val capacity: Int
  suspend fun acquire(request: PoolAcquireRequest): PoolAcquireOutcome   // Granted | TimedOut | Failed
  suspend fun release(handle: PoolHandle): StopResult
  suspend fun killAll(reason: KillCause)
  fun recycle(slot: PoolSlot)                         // 强杀后收归：槽位复位 + 还许可证（原子、幂等）
  fun stats(): PoolStats                              // capacity / free / busy
}
```
四条与早期草案的差异（都是落地后收敛的结果，写下来防止文档倒着改代码）：
- **无 `pause/resume`/`console: Flow`/`events: Flow`/`channel()`**：暂停未进 P0；控制台与事件走 §7.3 的 TSF 双队列 + EventBus 拉取，不建模成引擎侧 Flow（轮询式 Flow 会把「事件」伪装成「流」，丢失 TTL 与背压语义）；命名通道在 `:app-service:runtime` 的 `EnginesNamespaceHandler` 侧按 `channel/channelEmit/channelDrain/channelClose` 显式管理。
- **引擎是一次一脚本**（`execute(run)` 非 `start(session)`）：同槽位不并发两个脚本，会话身份 = `runId`。
- **`acquire/release` 收 `PoolAcquireRequest`/`PoolHandle`**而不是 `EngineSession`/`ExecutionHandle`：排队上限（`waitTimeoutMillis`）必须与请求同行，否则满池只能无限等。**时限归属（`TimeoutEnforcer`）同样与请求同行**：`AWAITER`（缺省，调度链路——发起方自己 await 终结并超时强杀）/ `WATCHDOG`（桥 `engines.exec`——发起方拿到句柄就返回，期限线随锚点交给看门狗，到点落 `KillCause.TIMEOUT`）；选 `WATCHDOG` 而不给 `scriptTimeoutMillis` 构造即 `require` 失败——判据是**发起方等不等**，不是「有没有声明超时」，声明了却没人执行等于没声明（§8.6）。
- 实现：`:engine:node-process`（NodeFactory），经 **Provider/SPI** 注入（`QuickJSFactory` 随沙箱裁掉，§18 第 1 项；缝留在原处，将来真要第二个引擎不必改接口）。

### 8.2 引擎实例模型决议（批判决议）
- **不做**「单 Node 实例多 engine/多 Job」——共享 context 的 `process.exit()`、全局变量、模块副作用全部泄漏（批判 2 反面教材）；「模块作用域隔离」被明确定为**假隔离**。
- **不做**「v1 用 worker_threads 做并发引擎」——手机端行为未验证（nodejs-mobile #130），且一个 worker 群共享进程=共享隔离边界。列为 P3 **实验性**特性，入口显式标「实验」。
- **做**：进程池 + 每脚本一进程。并发上限=池容量；超载任务进入队列（清晰的产品化语义，而不是偷偷并发）。
- 执行中的 slot 在 `:main` 持 FGS/绑定，池进程按内存采样动态缩容（占位 slot 空闲超时回收）。

**记账不变量（`FixedEnginePool` 强制，违反即池缩水）**：
- 许可证（公平 `Semaphore`）与 FREE 槽位 **1:1**；夺槽必须在 `stateLock` 临界区内、且**先于** `engine.execute` —— 否则 execute 的启动耗时就是窗口期，并发 acquire 会选中同一槽位（同一进程跑两个脚本）。
- 有证无槽 = 记账失真，立即还证返回失败，绝不吞证转死锁。
- **每条终结路径都必须成对归还「槽位 + 许可证」**：正常 stop/release 走 `quiesce()` 后还证；启动失败与调用方取消走 `recycle`；**强杀（killRun）也必须收归** —— 这是踩过的坑：只 `kill()` 不还证，`free` 与可领许可证永久错位，池容量缩水，表现为「引擎再不接活」。
- `recycle(slot)` 在池侧原子完成「状态复位 + 代次前进 + 还证」，幂等不超发；`PoolSlot.generation` 让过期句柄 release 时如实判定已净，绝不拆新占用者（§7.4 代次纪律）。
- 满池时排队上限来自 `PoolAcquireRequest.waitTimeoutMillis`；**桥接路径上 `engines.exec` 的上限 = payload `waitTimeoutMillis` 优先，否则请求侧 TTL**（§7.4 每次跨进程操作必有 TTL）。TTL 若不递进池，满池只剩「无限等」一条路，调用方只能自己取消，无法诚实回 `ERR_TIMEOUT`。

### 8.3 生命周期状态机（每执行单元）—— 目标态 vs P0 已落地

```
          ┌────────────────────────────────────────────────────────┐
          ▼                                                        │
  ┌────────────┐  start →  ┌──────────┐  心跳失联×N/CPU 风暴/OOM   │
  │  PENDING    │─────────▶│ RUNNING  │───────────────────────────▶│
  └────────────┘           └────┬─────┘    (看门狗触发 kill→下一态)  │
                                │ pause             resume          │
                                │ ◀────────┐  SU────  ┌───────────┐ │
                                │          └─────────│ SUSPENDED  │─┤
                                │      (多源计数>0)   └───────────┘ │
            stop/reason/崩溃     ▼                                  │
                          ┌───────────┐  排空超时   ┌────────────┐  │
                          │ SINKING    │──────────▶│ QUIESCED    │─┘
                          └───────────┘            └────────────┘
  一切终态: TERMINATED(done|crashed|killed|timeout) → 归档 RunRecord
```

规则：
- **SUSPENDED 用多源计数**（UI 页面、FGS 需求、诊断暂停……各自 `acquire/release`），计数归零才回 RUNNING；不是布尔标志。
- **看门狗判定只看 RUNNING**；SUSPENDED 不回度量（dispatchLag 只在 RUNNING 采样，防误杀合法暂停）。
- `SINKING → QUIESCED` 有容忍窗口（grace，默认 5s，可配置）：排空 in-flight（generation 匹配才算有效），窗口到未排枯则斩杀。
- 任何路径都不可能「停在 RUNNING 无归宿」：RUNNING 必须挂一个心跳 deadline，超时即进 SINKING。

**P0 已落地的收敛子集**（代码是事实来源，别按上图臆造）：
- `:domain` 的 `EngineStateMachine`（合法转移表 + `kill()` 归因：`REQUESTED → STOPPED`，其余原因 → `CRASHED`）**已真正驱动池侧**：`PoolSlot` 持一个状态机实例，与「占槽/回收」同生共死 —— 夺槽 `markBusy` → `BOOTING`，`execute` 拿到 `EngineRunReceipt` → `RUNNING`（`PoolSlot.markRunning`），`quiesce` → `QUIESCING → STOPPED`（stop 超时兜底杀掉则归 `REQUESTED`），`recycle`/`forceFree` 按传入 `KillCause` 归因（watchdog/OOM → `CRASHED`）后回收回 `IDLE`。槽位侧的三态投影 `SlotState{FREE, BUSY, QUIESCING}` 仍在（§8.2 记账要用），但不再与 `EngineStatus` 脱节。
- 为什么状态机挂 `PoolSlot` 而不是另建一张表：状态机必须与占槽/回收同生共死，分表就要处理「表里有行、槽位已 FREE」的孤儿；`FixedEnginePool` 的 stateLock 已保证读写与记账原子。非法转移抛 `IllegalStateTransition`（响亮失败，不静默修状态）。
- **状态对照已落地**（`RuntimeController.statusOf(runId)` / `runStatuses()`）：同时读宿主自报（`ScriptEngine.status()`）与池侧投影（`PoolSlot.status()`），分歧如实进 `RunStatus.drift`；`EngineWatchdog.Tick.drift` 把它带进每轮监督清单。**只报分歧、不改状态** —— 校准不是替某一侧抹平差异，而是让差异先可见。合法组合白名单：池 IDLE ↔ 宿主任意（已回收，宿主说什么都不算异常）、BOOTING ↔ 宿主 IDLE/BOOTING（execute 未返回）、RUNNING ↔ RUNNING、QUIESCING ↔ QUIESCING/STOPPED、池侧 STOPPED/CRASHED ↔ 宿主任意。宿主读不到（探针抛错/引擎已死）→ `host = null` 且**不算 drift**：那是「量不到」，不是「不一致」，混在一起会让真分歧被噪声埋掉。
- **裁决已落地**（`EngineWatchdog` 的 drift 连段 + `KillCause.DRIFT`）：单轮分歧只是真机的窗口期常态（宿主刚推 STOPPED、池还没 quiesce 完），连续 `driftKillThreshold` 轮（缺省 3 轮 ≈ 1.5s）还对不上才是真分裂 —— 此时经 `RuntimeController.killRun(runId, DRIFT)` 杀掉重来，不猜哪一侧对（校准不是替某一侧抹平差异）。`Tick.drift` 照常每轮记账（谁看见谁处理），`Tick.driftKilled` 单独列出分歧杀供诊断区分"病死"（三路判定）与"分歧杀"；连段中间弥合一轮即从头数，失踪/被杀的 run 清零（防 runId 复用背旧账）。`DRIFT` 归 `CRASHED`（`EngineStateMachine.onKill`：非 REQUESTED 一律 CRASHED），与"管理者主动停"（REQUESTED → STOPPED）区分"自杀"与"他杀"。阈值是 `EngineWatchdog` 构造参数（`DEFAULT_DRIFT_KILL_THRESHOLD`），装配层可配。
- 上图里的 `SUSPENDED`（多源计数）与 `PENDING` 在 P0 **均不存在**：`EngineStatus` 枚举里没有 SUSPENDED，`awaitCompletion` 只把 RUNNING 判活（看门狗口径一致，见 §8.4）。**任何依赖 SUSPENDED 的设计（暂停恢复、诊断暂停计数）仍然没有代码基础。**

### 8.4 看门狗（三路，防死循环/僵尸/饥饿）
- **心跳**（数据面，周期 ~500ms，携带自回事务序列号）：连失 K 次 → 重启判定；心跳与操作 TTL 互补——**await 的 RPC 有 TTL，整体执行有心跳**。
- **CPU 外带差分**（`:main` 独立线程读 `/proc/<pid>/stat` utime+stime 差分，**不依赖 Node 合作**）：单核持续 >95% 超过阈值（默认 30s，可配）→ 杀。防 `while(true)`/Promise 风暴这种「心跳还活着但永不放行」的形态。
- **内存**：RSS 超阈值（分级配置，池缩容信号）→ 降载警告，连续超阈 → kill + archive。
- 看门狗**不作为业务**：只输出「恢复建议」（重启/重试/降级），不自动无人值守自愈（§1 诚实原则）。

**P0 已落地**：`:app-service:runtime` 的 `WatchdogPolicy` 是三路纯判定（`WatchdogSample{pid,status,heartbeatMillis,cpuPercent,rssBytes} → Healthy | Kill(cause, reason)`），默认阈值：心跳 500ms × 连失 3 次、CPU ≥95% 持续 30s、RSS ≥512MB；只对 `RUNNING` 判活；`RuntimeController.judge()` 委托它，裁决落点 = `killRun`/`killAll`（kill 权威 §4.1）。
**采样已落地**（`ProcessMonitor`，`:app-service:runtime`）：`/proc/<pid>/stat` 与 `/proc/<pid>/status` 的读取 + 折算，产出同一份 `WatchdogSample`，交给 `WatchdogPolicy` 判定。诚实口径写死在实现与单测里：CPU 分子 = 两次 `utime+stime` 差（jiffies → ms），分母 = 调用方给的墙钟间隔，**不折算单核**（多核满载必须看着就 >100%，否则漏杀 Promise 风暴）；`comm` 含空格括号时从**最后一个 `)`** 之后切字段；首采样 / 换 pid / 时钟回拨 / 计数回绕一律回 0.0%，不给假差分；`/proc` 不可读 → 整份样本回 null（按「无法度量」处理，不猜健康），`status` 读不到只丢 RSS 这一路，不作废 CPU 样本。**本类只采样不裁决**（裁决仍归 `WatchdogPolicy`，kill 仍归 `RuntimeController`，runId→pid 归属表仍归 `:app`）。

**调度循环已落地**（`EngineWatchdog`，`:app-service:runtime`）：§8.4 的三路分工终于有了周期性调度者 —— `RuntimeController.watchAnchors()` 交出在途执行的 `WatchAnchor{runId, receipt.pid}`（**pid 取 `EngineRunReceipt.pid` 快照，不是 `ScriptEngine.pid` 当前值**；槽位复用后两者会分叉），`ProcessMonitor` 按 pid 分别记账采样本，`RuntimeController.judge()` 裁决，`Kill` 落 `killRun(runId, cause)` —— 判据、采样、执行三方仍是三个类，调度者只负责「到点把三者接起来」，不自己长判定口径。`AppShell.assemble` 交出 watchdog 实例与 `ProcessMonitor`/`heartbeatMillis` 注入缝，`startWatchdog(scope)` 之前不转；生产路径由 `AppShellKit.assemble` 在装壳时就转起来（§4.1 的真实调用点）——域缺省是壳自己持有的 `SupervisorJob`（`AssembledShell.close` 先停轮转再关池与持久句柄），调用方也可传自己的域（那就自己负责停：`EngineWatchdog.start` 的 KDoc 写死了这条所有权规则）。
- **采样周期 = `heartbeatIntervalMillis`**（`WatchdogPolicy` 那条因此从 private 变 public）：周期长于心跳阈值会把活引擎判死（500ms×3 的阈值配 3s 轮询 = 假阳性），controller 的这份 policy 经 `RuntimeController.watchdogPolicy()` 原样交给 watchdog —— **只有一份阈值**，不在装配层另造一个。
- **pid 终结即忘**：无论正常结束、被 watchdog 杀掉还是本轮采样后不在途，`EngineWatchdog` 都调 `ProcessMonitor.forget(pid)`。Linux 复用 pid，不遗忘等于让新进程背旧 CPU 基线（虚高 → 误杀）；pid 落到别的 runId 时 CPU 历史整段清零，同理。
- **不另建 pid→runId 表**（§8.4 原口径不变）：归属表只有一份，就在 `RuntimeController` 的在途账里。`:app` 侧再抄一份必然漂移（stop/kill 路径不止一条），`watchAnchors()` 是它的只读投影。

**心跳一路已接线**（§8.4 缺口② 补齐）：`HeartbeatLedger`（`:app-service:runtime`）是心跳的宿主侧收单方，`RuntimeController.heartbeat(runId, seq)` 是它的桥侧入口，`RuntimeController.heartbeatMillis(runId)` 是看门狗的问讯口 —— `EngineWatchdog` 缺省就问 controller 那份账本（`AppShell.assemble` 的 `heartbeatMillis` 缺省 null = 装配时接真账本；显式传 `{ null }` = 明示这一路不接，看门狗如实记 `Tick.noHeartbeat`）。JS 侧 `engines.heartbeat {runId,seq}` + `startHeartbeat(runId)` 定时打点（`unref` 定时器，不保活事件循环）。
- **序号即真伪**：账本只认**递增** `seq`。同/旧 seq 一律拒收（只累计 `staleBeats()`，不刷时间戳）—— 否则宿主张力下积压的旧心跳会把一个**已经死了**的 run 一直喂成活的，那正是心跳这一路要抓的形态。
- **从未打点回 null，不回 0**：0 会被当成「刚刚打过」，失联判定永不触发；null = 量不到，看门狗据此记 `noHeartbeat`。
- **与 run 同生共死**：`stop`/`killRun`/`killAll`/`settleDone`/`settleKilled` 五条终结路径全部 `forget(runId)`。不遗忘 = runId 复用时新 run 背上一段「假年轻」，失联判定被推迟到下一次自然打点。
- **无主心跳不建账**：`RuntimeController.heartbeat` 先验在途再落账本 —— 不在途 runId（已结算/从未存在）回 `false` 且不记账。否则迟到/重发的心跳会在复用 runId 上复活旧账，或把活 run 的账顶出记账上限；桥侧未知 runId 仍 Ok `false`（不是调用方错误，不 4xx），JS mock 宿主复刻同一口径。
- **仍不伪造**：拿 watchdog 自己的轮转周期当心跳依旧是禁止的 —— `while(true)`（心跳活着、CPU 打满）只靠外带差分抓得到。

至此 §8.4 三路判据、采样、调度、心跳打点全部闭环。**pid 半边已接线**：`NodeProcessEngine`（`:engine:node-process`，Kotlin spawn）的 `EngineRunReceipt.pid` = spawn 瞬间真子进程 pid（`NodeProcessEngineRealSpawnTest` 实测 >0 且非自身；`:app` E2E 经 `watchAnchors` 锚点 pid>0 复验），看门狗 `/proc` 采样锚点在 spawn 路径上是活的。**心跳的生产链已备**：spawn env 下传 `AUTOSCRIPT_RUN_ID` → kBootstrap 500ms 自动打点 / 桌面脚本显式 `startHeartbeat`，E2E 实测 `heartbeatMillis(runId)` 落账非 null；桥监听 `BridgeSocketListener` 亦已接（abstract 绑定 + uid 门禁 + `NewlineFrameServer` serve，JVM 假缝单测；bind 失败 = 离线降级不注入名，见 §7.5/§7.8）。addon 的 JS 消费面亦已落（facade `attachNative()`，§7.8）。**设备面只剩真机联调**（jniLibs 三件套 + addon 资产落位 + facade dist 随包与打包入口 attach 接线 2026-09-24 均已落，见 §12.4/§19 切片路线）未落；在那之前，真机上当前生效的是「未 spawn / 宿主不给 pid → noPid」「宿主不打点 → noHeartbeat」两条量不到路径 —— 这由 `Tick` 的三个清单如实区分，不是一个笼统的 `unmeasurable`。

### 8.5 崩溃恢复与幂等（checkpoint 意图日志）
- `:main` 的 scheduler 持久化 **意图日志（intent log）**：`RUN_START(projectId, entry, runNonce, scheduledAt) → …execute… → COMMIT(result)` append-only（SQLite，启动即回放）。
- **恢复只跟随 COMMIT**：进程/手机重启后，未 COMMIT 的 run 视为「未完成意向」→ 重新入队，但生成**新的 runId + 保留 runNonce**；执行体用 `runNonce` 做**幂等键**（外部副作用目标幂等，如「只发一次」的通知 id、datastore 原子键），杜绝重复业务副作用。
- **rerun 新 RunRecord**（每次重跑都是新 runId）——满足批判「resume=新 runId」语义；「断点续跑」只对纯内存任务可选，涉及副作用任务默认不允许自动续。

**归档入口（已落地契约，§8.5）**：两套 runId 是「一个真值的两个投影，必须成对写入」。

| 侧 | 身份字段 | 寄存器 |
|---|---|---|
| 意图日志（scheduler） | `intentRunId`（`IntentRun.runId`） | intent log |
| 引擎运行记录（engine） | `engineRunId`（`EngineRunReceipt.runId`） | `RunRecord(id)` |

`:domain` 的 `EngineRunLink(intentRunId, engineRunId)` 是关联契约；`RunArchive` SPI 是引擎侧档案（`put(record, link)` / `record` / `link` / `recordsOfIntent` / `recordsOfProject` / `unfinished`）。纪律：终态（`SUCCEEDED/FAILED/CRASHED/CANCELLED`）append-only，**不可改写、不可复活**，违反必须响亮失败而不是静默吞。只写一侧 = 孤儿记录（「引擎在跑而任务中心查不到」或反之），`DispatchReport.link` 在门禁拒绝/排队超时/启动失败时如实为 null。接线在 `:app` 的 `ControllerRunDispatcher`（拿到 Receipt 后生成 link）+ `AppShell`（scheduler 持 `RunArchive`）。**持久形态已落地**：`JournalFileStore`（意图日志）+ `FileRunArchive`（运行档案）同用一套 jsonl 行格式（共享 `JsonLine`），`force(true)` + 启动 replay + 半行容忍；两文件分开存是刻意的 —— 键不同（intentRunId vs engineRunId）、只写一侧的孤儿在格式上才可见。

孤儿结算两条路（都只在 `recoverUncommitted` 里跑，运行期绝不扫——那会把正在跑的执行误判成孤儿）：
- **逐 intent**（`settleOrphanArchive`）：本次要 reopen 的遗留意向，其关联档案里还没终态的记录 → 如实 `CRASHED`（宿主死时没结算）；
- **全档空档**（`settleVoidArchive`）：`log.commit` 与 `recordLink` 不同事务，中间崩溃会留下「意图行已终态、档案停在 RUNNING」的孤儿 —— 它挂不到任何未 COMMIT 行上，逐 intent 那条路永远看不见。恢复刚起来时引擎池必空，档案里所有非终态记录都只能是上一进程遗物，此时按 `unfinished()` 自报统一补 `CRASHED`（无 link 的记录跳过：独立执行不属意图日志管辖）；link 原样保留，任务中心仍可按 IntentRun 追到这条失联记录。

### 8.6 调度系统
- 触发源五类：`定时(cron/alarm) `、`Intent/广播`、`事件(无障碍/通知)`、`用户点击`、`引擎内部 engines.exec`。
- **SchedulerProvider SPI**：同一接口后 P1 可切 `WorkManager` 之外的实现（保活场景自持 alarm + 注册 receiver）。触发→拉起引擎进程→注入 API→归日志。
- **守时语义诚实化**（批判 11 定案）：设备**亮屏 + 解锁**是保底契约；预热闹钟 `scheduledAt - 60s` 先拉起进程（引擎进程需时 ~1s），axexact 闹钟失败时降级到 setWindow 并在 UI 标注「可能偏差」。**熄屏任务**＝任务显式声明三态之一：`screen.on`(需 wakelock+确认)/`screen.any`/`screen.off`(禁 MediaProjection，只允许无障碍+网络)。
- 触发时若引擎池满 → 排队，绝无静默丢任务（日志+UI）。
- **排队上限由投递方给**：`ControllerRunDispatcher` 的 `queueTimeoutMillis`；到期 → `RunOutcome.Cancelled`（"排队取消"口径：未获槽、未执行，link 为 null）。
- **P0 已落地**：满池排队**默认有界**，不再有"默认无限等"这条路。`queueTimeoutMillis` 显式覆盖优先；不传则按触发源分级取默认上限（`ControllerRunDispatcher.DEFAULT_QUEUE_TIMEOUTS`）：`ENGINE_INTERNAL` 15s（满池下的跨引擎调用是"持有者等后来者"的嵌套形态，必须最先爆，否则变跨引擎死锁）、`USER_CLICK` 10s（人盯 UI，等不及就如实 Cancelled，不让按钮原地转圈）、`INTENT_BROADCAST`/`EVENT` 60s（外部涌入本应容忍排队）、`TIMED` 120s（守时任务已承诺"亮屏+解锁保底 + 可能偏差"，2 分钟只为满足铁律 3，不追求抢跑）。分级表是**注入缝**（构造函数参数），装配层可换成自己的口径；`queueTimeoutMillis` 传 0 视为漏配，构造即 `IllegalArgumentException`——0 等于"永不允许排队"，与"绝不静默丢任务"相反。
- **P0 已落地（deadline 记账）**：`PendingRun.deadlineMillis` + `isExpired(now)` 记下"本次投递的到期时刻"，与 dispatcher 的排队上限**同源但不同职**——dispatcher 那侧管"在途排队等不等得起"，deadline 管"宿主重启之后这条意向还值不值得投"（崩溃恢复面对的是另一件事：进程死过一次，用户早走了/外部事件早凉了）。期限写在 `IntentRun.deadlineMillis` 上随 RUN_START 行落盘，`reopen` 原样带到新行（恢复重投不得变期限，否则同一意向两套到期口径）；`Scheduler.onTrigger` 按 `排期时刻 + deadlineFor(触发源)` 填，`recoverUncommitted` 遇过期意向照样 `reopen` 封口记账，但**不再 dispatch**，直接 COMMIT [RunOutcome.Cancelled]（`RecoveryRecord.expired` 标出，任务中心按 runId 读到"为何没跑"——绝不在恢复路径里静默跳过）。两处口径同源由装配层保证：`Scheduler` 的 `deadlineFor` 与 `ControllerRunDispatcher.queueTimeout` 默认同喂一张分级表（`DefaultDeadlines` 与 `DEFAULT_QUEUE_TIMEOUTS` 数字一致，前者是缺省值不是契约，装配层可各自覆盖）。
- **P0 已落地（调度侧停止与收口）**：`Scheduler.lastHandle`（`EngineStopHandle{idLink, name, runNonce, stop}`，§12.3 engines.exec 的调度侧投影）只在 dispatcher 真的产生了引擎执行（`DispatchReport.link != null`）时持有 —— 门禁拒绝/排队超时/启动失败没有可停的东西，不持有假句柄；停止入口由 dispatcher 经 `DispatchReport.stop` 填权（`:app` 的 `ControllerRunDispatcher` 在 start 成功时绑定 `RuntimeController.stop` → 池四步 quiesce；三条未产生执行的早退分支回 null stop；已结算后调用落 AlreadyGone 幂等 no-op，永不升级为 kill）；`stopLastRun()` 转发句柄的 stop（成功不清句柄，停止幂等），`canStopLastRun()` 从句柄现算；`sink()` 撤销全部触发器并置位（此后 `onTrigger` 早退）；`quiesceThenStop()` = sink → 停最近一次 run → 返回句柄供归档（§13 铁律 4 的调度侧部分）。线程契约诚实声明：lastHandle/sinking 读并发安全，写仅发生在 `onTrigger` 内（装配层负责把五类触发源串行化）。
- **P0 已落地（执行侧急停）**：`RuntimeController.forceStopAll(cause)` —— 进程级急停的显式入口（应用被杀/系统回收/测试收口），与请求驱动的 `killAll` 区分（killAll 是裁决/停全部的落点，在途表经 guard 串行收走；forceStopAll 只做杀全部 + 清在途表 + 忘心跳，不走请求语义）。**装配层有序收口已落地**：`AppShell.shutdown(cause)` = `scheduler.quiesceThenStop()`（先 sink 拒收新投递、再停最近 run）→ `controller.forceStopAll(cause)`（再杀全部槽位并复用）。顺序不可反：先杀后停会在调度不知情窗口继续投递。两步都幂等；返回调度侧被停句柄供归档（无句柄时为空，不假装停过）。
- **P0 已落地（无人 await 的 run 自带期限，2026-10-01）**：
  原先的诚实边界是「引擎侧 `waitCompletion` 超时不发起的场景还没人管——在途 run 没人收尾时 watchdog 是唯一兜底」，而看门狗三路判据全看**进程表现**（心跳/CPU/RSS）：一个心跳正常、CPU 空闲、RSS 很低的长跑脚本三路都判它健康，**没有任何一路收得住它**。收口判据不是「有没有声明超时」而是**发起方等不等**（`PoolAcquireRequest.timeoutEnforcer`，见 §8.1）：
  - `AWAITER`（缺省）：调度链路的 `ControllerRunDispatcher` 自己 `awaitCompletion`，超时 → `killRun(REQUESTED)`（归 STOPPED）——这条本来就有人收尾；
  - `WATCHDOG`：桥 `engines.exec` 拿到句柄即返回、没人 await 终结 —— 期限 `startedAt + scriptTimeoutMillis` 经 `RuntimeController.WatchAnchor.deadlineMillis` 交给看门狗，到点落 `KillCause.TIMEOUT`（归 CRASHED：期限到期是强制收账，不是调用方主动停）。
  期限线在 `EngineWatchdog.tick()` 里**先于** pid/心跳那两条「量不到」分支判 —— 期限不依赖任何度量，它是发起方声明的事实；排在后面会让「宿主不给 pid」或「心跳未接线」的 run 连期限都够不着，恰好退回「没人收尾」那一类。同时 `engines.exec` 的 `timeoutMillis` 由可选改为**必填**：缺席/`null`/`<= 0` → `ERR_INVALID_PARAM`（构造期 `require` 同款守卫）——缺省值在这里没有诚实来源，编一个（30s？5min？）等于替脚本静默决定它能跑多久（与 `queueTimeoutMillis` 传 0 视为漏配同一条纪律：响亮失败）。
  **仍待覆盖（诚实边界）**：期限只覆盖**声明了期限**的 run，且只在看门狗轮转真的在跑时有效（生产轮转挂 `AppShellApplication` 的 SupervisorJob 域）——轮转没起 = 没有期限线；池空退避（`idlePollMillis`）期间新起的 run 最坏晚一个退避周期才被看到。另外 `runNonce` 幂等只保证**外部副作用**不重复，不保证「期限内跑完」——期限到点即杀，写到一半的副作用仍由执行体自己的幂等键兜底。
- **P0 已落地（Android 触发侧）**：`AlarmSchedulerProvider`（`:app` 装配层）把 `SchedulerProvider.registerTrigger` 翻译成闹钟——预拉提前量 `wakeAheadMillis` 是契约字段（60s，测试与调用方同一份值，不藏常量）；**提前量只向前推、不向后扯**（排期已到即夹到当前时刻，ROM 对负延迟处置不一）；`canScheduleExact` 为假时降级 `setWindow` 且**记账**（`degradedTasks()`，`taskId → 排期时刻`），能力中心据此标注「可能偏差」——**不静默降级**。框架调用（真 `AlarmManager`）在 `AndroidAlarmPort`，本类**无判断**：taskId → `KeyStableHash` 定 requestCode（同 taskId 恒同，重复 arm 是替换）、`setExactAndAllowWhileIdle`/`setWindow` 两个调用点、取消 = `alarmManager.cancel` + `pendingIntent.cancel`（两个都要）。`PendingIntent` 在 API 31+ 必须 `FLAG_MUTABLE`（系统要填 `EXTRA_ALARM_*`）。回投侧是**静态注册**的接收器 `AlarmReceiver`（精确闹钟响时进程可能已被 ROM 杀掉，`registerReceiver` 收不到）走 `goAsync()` 在广播窗口内把 taskId 经 `AlarmDispatch` → `SchedulerAlarmRoute` 送回 `Scheduler.onTrigger`（TIMED 来源 + 闹钟真实排期），于是 runNonce/意图日志/dispatcher 口径与手动触发完全一致。**装配前/后的漏投不静默丢弃**：没接路线的闹钟进 `AlarmDispatch.missed()`（同一 taskId 只留最新一条，`drainMissed()` 清账），`AppShellApplication.missedAlarms()` 供能力中心如实呈现「闹钟已响但调度未就绪」。
- **P0 已落地（屏幕门禁的生产实现）**：`AndroidScreenGate`（`:app`）——`SCREEN_ON` 在两个系统查询缝（`interactive` = `PowerManager.isInteractive`，`deferWakeLock` = 持锁方）任一为假时**如实 `Deny`**，不降级成「锁屏也跑」（那条路径的表现是「任务成功、实际什么都没发生」）；`SCREEN_OFF` 先经 `ScreenOffGuard` 收起画面类能力再放行（无障碍 + 网络在锁屏下真实可用）；`ANY` 放行。两条缝的值由 JVM 单测注入，判断逻辑因此可测而不必 Mock 框架对象。**`AllowAll` 与 `AndroidScreenGate` 在 `ANY` 上必须同结论**（`AndroidScreenGateTest` 有断言守着），否则同一条任务在单测里放行、真机上被拒，差别只在现场暴露。

- **P0 已落地（任务中心读口与屏，2026-09-24）**：`HostSummary` 增 `taskCenter()`（挂起：读任务注册表 + 运行档案两个持久寄存器；**读失败抛** —— `:ui` 如实显示「读任务失败」，而不是冒充「一条任务都没有」，那是「读成功且真没登记过」的另一种事实）→ 拼装 `TaskCenterRead.snapshot`（`:app` 壳装配包，纯 JVM 可测，**不自带 IO**：取数由调用方注入）：`TimedSchedule` 三态 → `ScheduleSpec` 三态**逐字段**映射不聚合（Cron 也保留成行 —— 不可能日期/坏行算不出下一跳时留名不续排，任务不从列表凭空消失）、`ScreenGuarantee` → `ScreenRequirement` **按名对表**（不用 `ordinal`；`TaskCenterReadTest` 有两边枚举同集断言，调度器加值先红再谈映射）、停用任务**不问下一跳**（问了也会得到答案，而那个答案会让人以为停用任务还会跑）、恢复账 `total`/`expired`/`failureText` 三笔分开且**失败时 `retried=0`**（`total-expired` 会把失败报成成功）→ DTO 住 `:domain` 的 `TaskCenter.kt`（`TaskCenterSnapshot`/`ScheduledTaskRow`/`RunRow`/`RecoveryRow` —— 调度器类型只在 `:app`，呈现层照旧只依赖 `:domain`）→ `:ui` 的 `TaskCenterScreen` + `TaskCenterState`（纯状态 DTO，JVM 可测）：三页签之二、**没读到 ≠ 一条任务都没有**（`NOT_LOADED` 与 `failed` 分开且保留原异常文案）、下一跳为 null **不编时间**（停用与 Cron 算不出两义由 `enabled`/`schedule` 分辨）、未结算执行文案点破「**不是此刻正在跑**」（`unfinished()` 只增不减，这栏只可能是上一进程遗物）、降级任务标「可能偏差」。刷新与能力中心同构：回前台/切页签现取一次，读失败不自激重读。读切片落地后，登记/取消/立即执行随操作面接上（见下条）。
- **P0 已落地（任务操作面：登记/取消/立即执行，2026-09-24）**：写口走 `HostSummary` 三方法 —— `registerTask(TaskRegistration): String`（回分配到的 id；`:domain` 的 `TaskRegistration` 是**纯数据** DTO，校验不在 DTO 里）/ `cancelTask(taskId)`（幂等：tombstone 先行，ghost id 照样返回成功 —— 用户视角"它已经不在了"）/ `runTaskNow(taskId)`（`TriggerSource.USER_CLICK`，**挂起到本次执行结算**再返回）。两个实现方都收口：`AppShellApplication` 壳未装配即抛（不冒充成功），`FakeHost` 同步长出 UOE（替身纪律：HostSummary 长一个成员，替身同批跟上，否则 `:ui` 单测编译断）。语义闸门唯一落点是 `:app` 的 `TaskCenterOps.toScheduledTask` —— 与桥侧 `WorkManagerNamespaceHandler` **同规则两侧各测**：空串三字段点名拒绝、`Once.delaySeconds<0` 拒、`Daily` 靠 `TimedSchedule` 的 0..23/0..59 require 拒、`Cron` 经调度器 `CronTab.parse` 校验（非法点名哪一段，两侧非法样本同源）、timeout≤0 拒、非法时区拒、`ScreenRequirement` **按名对表** `ScreenGuarantee`（同集断言先红）。形状解析（整数文本、trim）收在 `:ui` 的 `RegistrationForm`，语义规则不复述一份（两处规则必然漂移）。诚实边界三条：(1) **`runTaskNow` 不哑火** —— `onTrigger` 对 sinking/缺席任务静默 return，所以 `AssembledShell.runTaskNow` 先查再触发，查不过就抛（检查与触发间的 TOCTOU 窗口已接受并写进 KDoc）；不改 `onTrigger` 签名（~28 处调用点全是语句位，改返回型会让非 void `@Test` 静默跳过）；(2) **回执不说脚本成败** —— `onTrigger` 只到结算，成败在意图日志/控制台，回执写「执行成败见控制台」；UI 侧 `TaskCenterState.opInFlight` 挂起期间禁用全部操作按钮（防双击双投）；(3) **Once 立即执行即出册** —— 调度器 `finally` 对 Once 终态化，卡片事前标「一次性任务：执行过后自动移出注册表」，回执点破「已执行并出册」，刷新后卡片消失是排期语义不是被取消。操作失败**不清任务清单**（`opError` ≠ `loadError`，`copy` 保留 `tasks` —— 抹掉会让用户以为任务全没了）；`opNotice` 只由成功写入，`of()`/`failed()` 归零（现取纪律），`performTaskOp` 刷完表再盖回。取消走 `AlertDialog` 一次性确认（误点的代价 = 手工重登记）。登记表单**缺省全空**（cron 格缺省 `0 9 * * *`）—— 误提交过不了 `:app` 闸门，不产生幽灵任务。UI 侧操作是 `HostSummary` 的**独立写口**，不经 `workManager` 桥（桥是脚本侧命名空间；宿主自己的操作面不该绕进程一匝）。
### 8.7 保活与电源
- `:main` 持 **specialUse FGS**（`onCreate` 启动，`TYPE_SPECIAL_USE` 勾选 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="automation"`，无超时）。
- 电池优化白名单、精确闹钟、开机启动、后台 Activity 启动豁免(**BAL**：仅允许 overlay 可见窗口路径/notification 触发路径)、自启动被 ROM 关闭——**全部入 PermissionCenter 三态门禁**（未授权=黄，被 ROM 杀=红且给跳转指引）。
- 长跑脚本自身需要**WakeLock** 时用 `power_manager`（引擎进程请求 → `:main` 对应 FGS 加唤醒锁的 acquire/release，配套超时自动释放）——**已落地（2026-09-24；2026-09-30 步骤 6d handler 与账本同批迁 `:platform:system`）**：`PowerManagerNamespaceHandler`（`acquire`/`release`/`status` 三方法，直驱同模块 `WakeLockLedger`，账本语义零改；keepalive 走 `:domain` `KeepAliveRenew` 窄缝）+ `AppShell.assemble` 的 `powerManagerHandler` **独立缝**（生产经 `PlatformWiring.powerManagerHandler(keeper)` 造）（与 datastore/zip/settings/notification/clipboard 同形，不入 `systemHandlers` 束）+ `AppShellApplication.installWithFiles` 现建喂缝 + `bridge/js` 的 `power.ts`（`acquire`/`release`/`status`）与 `power.test.cjs` 双侧契约。诚实口径三条：脚本锁必须限时（无期限只属框架 token）、token 服务端分配（脚本自带会互撞/互释）、取不到锁回 `ERR_SERVICE_DISABLED` 且未记账（门禁据此拒绝 `SCREEN_ON`）；直驱账本不走 `ForegroundKeeper.start(token)`（那个单槽只属框架，调两次互踩 `frameworkToken`）。
- **P0 已落地（`:main` 侧 FGS + 真唤醒锁，2026-09-23）**：三个可分离的缝，判断全在 JVM 可测面，系统接触面各收在一个类里。
  - **`WakeLockOps` / `WakeLockLedger`**（`platform/system/.../system/WakeLock.kt`，步骤 6d 自 `:app` 迁入；`:app` 经 `ForegroundKeeper`/`lockHeld()` 读）：`AndroidWakeLockOps` = 真 `PARTIAL_WAKE_LOCK`（`setReferenceCounted(false)`，acquire/release 异常一律吞成 `false` + 日志）；`WakeLockLedger` = **token 引用计数 + 超时自动释放**——`hold(token, timeoutMillis)` 只在**首次**（账本为空）时真取锁，**取锁失败不记账**（"记了账却没锁"是假绿之源）；`release(token)` 用"先查存在再删"（超时 token 的值为 null，照样能释放）；`sweep()` 释放到期项并返回名单；`isHeld()` = **账本非空 ∧ `ops.held`**——两侧任一说"没有"就一律算没锁。token/超时的形状就是给 P1 `power_manager` 预留的插口（引擎进程请求 → FGS 加锁走同一账本）——该命名空间已落地（见上条），账本语义零改，兑现了预留时的承诺。
  - **`ForegroundOps` / `ForegroundServiceBase` / `ForegroundKeeper`**（`app/.../shell/ForegroundOps.kt`、`ForegroundKeeper.kt`）：`AndroidForegroundOps` 管 `startForeground`/`stopForeground`，API 34+ 传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，通知走 `ForegroundNotifications`（渠道 `autoscript.foreground`）；`ForegroundServiceBase` 是**服务不自装配**的落点——服务的 `onStartCommand` 按 `ACTION_START`/`ACTION_STOP` 分支，Keeper 从进程级邮箱 `ForegroundHost.keeper` 现取（服务**不持有** Keeper/账本，避免 service → 根包成环；`START_NOT_STICKY`：重启路径没有 Keeper 上下文，续期统一走 `Application.onCreate` 的同一条装配路径，与 `BootReceiver` 同纪律）；`ForegroundKeeper.isActive()` = **`ops.foregroundRunning` ∧ `wakeLocks.isHeld()`**——"请求过" ≠ "生效了"；`stop()` 在系统仍报前台时返回 `false`（保持 drain 路径活着，不假装停干净），随后由 `renew()` 兜；`renew()` = sweep 到期锁 → 账本还持着而服务掉了就补拉 → 账本空了但服务还开着就停掉；守护 ticker（15 分钟）只做这两件事，`Throwable` 全吞（`scheduleWithFixedDelay` 一旦抛出就静默停摆）。
  - **屏幕门禁收口**：`AndroidScreenGate.of(...)` 的 `deferWakeLock` 生产实参已是 `ForegroundKeeper::lockHeld`（= 账本 `isHeld`；`AppShellApplication.screenGateOf`，根包经此读口不碰 platform 类型）。**§8.7 原来那条"恒真 = 明写的待接"就此作废**：现在熄屏 + `SCREEN_ON` 的真实表现是「锁没拿到就 `Deny`」，且 `Deny` 的判词与账本一致——不是靠恒真放行后再指望系统。
  - **如实呈现**：`ShellSummary.keepAliveActive`（`:domain`，**不带默认值**——每个产出方必须显式回答"保活到底生效没有"，漏填编译期就炸）由 `AppShellApplication` 填 `keepAliveActive()`；`:ui` 首屏据此直说「保活已生效」/「保活未生效：熄屏的亮屏任务会被拒绝」——这是"任务为什么没跑"的直接答案，不藏在二级页。
  - **诚实边界**：`onTerminate()` 在真机上**从不被调用**（进程死时系统自行回收 wakelock），它存在只为测试/模拟器收口 + 给"谁来停"一个代码落点；保活未生效时 `AppShellApplication` 记 `Log.w` 并在 UI 上显示红色，**不降级成"锁屏也跑"**。

### 8.8 屏幕语义（截图直连 §9.2）
`ERR_SCREEN_LOCKED`/`ERR_BLACK_FRAME` 显式化：MediaProjection 在 keyguard 下黑帧、a11y 在锁屏无可用窗口 → 引擎收到的是**分类错误而非黑图**，脚本可 try/catch 策略分支。

---


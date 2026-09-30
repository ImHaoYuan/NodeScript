package com.autoscript.appservice.scheduler

import com.autoscript.appservice.scheduler.core.CronTab
import com.autoscript.appservice.scheduler.core.ScheduledTask
import com.autoscript.appservice.scheduler.core.Scheduler
import com.autoscript.appservice.scheduler.core.ScreenGuarantee
import com.autoscript.appservice.scheduler.core.TimedSchedule
import com.autoscript.domain.bridge.BridgeRequest
import com.autoscript.domain.bridge.BridgeResponse
import com.autoscript.domain.json.DomainJson
import com.autoscript.domain.bridge.RpcNamespaceHandler
import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.time.ZoneId

/**
 * `workManager` 命名空间桥处理器（docs §8.6/§9.6 定时 API；JS `workManager.*` 的 Kotlin 对偶）。
 *
 * 归属（2026-09-30 审查步骤 6 反转）：自 `:app` 装配包迁入 `:app-service:scheduler`
 * —— 与 `engines` 住 `:app-service:runtime` 同形态：**handler 归位实现模块**，
 * 直驱本模块 [Scheduler]（登记/取消/列举）。原驻 `:app` 的理由是 arch 门禁禁
 * `domain.bridge`，本批同门撤该条并量化：handler 只碰转接面三型
 * （BridgeRequest/BridgeResponse/RpcNamespaceHandler），不进引擎/权限/平台。
 * 挂载点仍在 `AppShell.assemble` 恒挂载（调度器是本壳自建的，不经注入缝）——
 * 与 a11y/screen 缝的区别：能力实现在 `:platform`（必须注入）。
 *
 * 线格式（与 `bridge/js` workManager.ts 逐字段对齐，扁平 JSON，无嵌套数组之外的结构）：
 * - `create`：`{id?,name,projectId,scriptPath,schedule:{kind,...},screen?,args?,
 *   scriptTimeoutMillis?,timezone?,enabled?}` → Ok `{"id":"…"}`。
 *   `schedule.kind` = `once`（`delaySeconds`）/`daily`（`hourOfDay`+`minuteOfHour`）；
 *   `cron`（`expr`，5 字段 `分 时 日 月 周`）→ 非法表达式 Err INVALID_PARAM
 *   （校验出处 [CronTab.parse]，与 UI 侧 `TaskCenterOps` 同口径）；
 *   `screen` 缺省 `ANY`；`timezone` 缺省系统默认；`enabled` 缺省 true；
 *   `id` 缺省服务端分配（UUID）。
 * - `cancel`：`{id}` → Ok `true`（幂等：从未登记的 id 照样 true，与 `Scheduler.cancel` 一致）。
 * - `list`：无参 → Ok `[task,…]`（与 create 同一任务形状；按 id 排序）。
 * - 未知方法 → ERR_NOT_IMPLEMENTED；非法载荷 → ERR_INVALID_PARAM。
 */
class WorkManagerNamespaceHandler(private val scheduler: Scheduler) : RpcNamespaceHandler() {

    override suspend fun dispatch(request: BridgeRequest): BridgeResponse = ok(request, payload(request))

    private suspend fun payload(request: BridgeRequest): String? {
        return when (request.method) {
            "create" -> create(DomainJson.decodeObject(requirePayload(request)))
            "cancel" -> {
                val f = DomainJson.decodeObject(requirePayload(request))
                scheduler.cancel(DomainJson.reqStr(f, "id"))
                "true"
            }
            "list" -> DomainJson.encode(scheduler.tasks().map { encodeTask(it) })
            else -> throw AutojsException(
                ErrorCode.ERR_NOT_IMPLEMENTED, "未知 workManager 方法: ${request.method}",
            )
        }
    }

    private suspend fun create(f: Map<String, DomainJson.Value>): String {
        val schedule = parseSchedule(DomainJson.reqObj(f, "schedule"))
        val task = ScheduledTask(
            id = (f["id"] as? DomainJson.Value.S)?.v?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString(),
            name = DomainJson.reqStr(f, "name").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("name 不得为空串"),
            projectId = DomainJson.reqStr(f, "projectId").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("projectId 不得为空串"),
            scriptPath = DomainJson.reqStr(f, "scriptPath").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("scriptPath 不得为空串"),
            schedule = schedule,
            screen = when (val v = f["screen"]) {
                null, is DomainJson.Value.Null -> ScreenGuarantee.ANY
                is DomainJson.Value.S -> try {
                    ScreenGuarantee.valueOf(v.v)
                } catch (_: IllegalArgumentException) {
                    throw IllegalArgumentException("screen 非法: ${v.v}（SCREEN_ON/ANY/SCREEN_OFF）")
                }
                else -> throw IllegalArgumentException("screen 必须是字符串")
            },
            args = when (val v = f["args"]) {
                null, is DomainJson.Value.Null -> emptyList()
                is DomainJson.Value.Arr -> v.items.map {
                    (it as? DomainJson.Value.S)?.v
                        ?: throw IllegalArgumentException("args 元素必须是字符串")
                }
                else -> throw IllegalArgumentException("args 必须是数组")
            },
            scriptTimeoutMillis = when (val v = f["scriptTimeoutMillis"]) {
                null, is DomainJson.Value.Null -> null
                is DomainJson.Value.N -> v.raw.toLongOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("scriptTimeoutMillis 必须 > 0")
                else -> throw IllegalArgumentException("scriptTimeoutMillis 必须是数字")
            },
            timezone = when (val v = f["timezone"]) {
                null, is DomainJson.Value.Null -> ZoneId.systemDefault()
                is DomainJson.Value.S -> try {
                    ZoneId.of(v.v)
                } catch (_: Exception) {
                    throw IllegalArgumentException("timezone 非法: ${v.v}")
                }
                else -> throw IllegalArgumentException("timezone 必须是字符串")
            },
            enabled = when (val v = f["enabled"]) {
                null, is DomainJson.Value.Null -> true
                is DomainJson.Value.B -> v.v
                else -> throw IllegalArgumentException("enabled 必须是布尔")
            },
        )
        scheduler.schedule(task)
        return """{"id":${DomainJson.encode(task.id)}}"""
    }

    private fun parseSchedule(f: Map<String, DomainJson.Value>): TimedSchedule {
        return when (DomainJson.reqStr(f, "kind")) {
            "once" -> TimedSchedule.Once(
                (f["delaySeconds"] as? DomainJson.Value.N)?.raw?.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("once 需要非负 delaySeconds"),
            )
            "daily" -> TimedSchedule.Daily(
                (f["hourOfDay"] as? DomainJson.Value.N)?.raw?.toIntOrNull()
                    ?: throw IllegalArgumentException("daily 需要 hourOfDay"),
                (f["minuteOfHour"] as? DomainJson.Value.N)?.raw?.toIntOrNull()
                    ?: throw IllegalArgumentException("daily 需要 minuteOfHour"),
            )
            "cron" -> {
                // CronTab.parse 是唯一校验出处（与 UI 侧 Ops 同口径）：非法表达式抛
                // IllegalArgumentException → handle 折叠为 ERR_INVALID_PARAM。
                val expr = (f["expr"] as? DomainJson.Value.S)?.v
                    ?: throw IllegalArgumentException("cron 需要字符串 expr")
                try {
                    CronTab.parse(expr)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("cron 表达式非法：${e.message}")
                }
                TimedSchedule.Cron(expr.trim())
            }
            else -> throw IllegalArgumentException("schedule.kind 非法（once/daily/cron）")
        }
        // TimedSchedule.Daily 的 init require 负责越界拒绝（hour 0..23/minute 0..59），
        // 抛出的 IllegalArgumentException 由 handle 折叠为 ERR_INVALID_PARAM。
    }

    private fun encodeTask(t: ScheduledTask): Map<String, Any?> = mapOf(
        "id" to t.id,
        "name" to t.name,
        "projectId" to t.projectId,
        "scriptPath" to t.scriptPath,
        "schedule" to when (val s = t.schedule) {
            is TimedSchedule.Once -> mapOf("kind" to "once", "delaySeconds" to s.delaySeconds)
            is TimedSchedule.Daily -> mapOf(
                "kind" to "daily", "hourOfDay" to s.hourOfDay, "minuteOfHour" to s.minuteOfHour,
            )
            is TimedSchedule.Cron -> mapOf("kind" to "cron", "expr" to s.expr)
        },
        "screen" to t.screen.name,
        "args" to t.args,
        "scriptTimeoutMillis" to t.scriptTimeoutMillis,
        "timezone" to t.timezone.id,
        "enabled" to t.enabled,
    )

    private fun requirePayload(request: BridgeRequest): String =
        request.payload ?: throw IllegalArgumentException("${request.method} 需要 payload 对象")
}

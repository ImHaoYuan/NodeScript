package com.autoscript.appservice.scheduler.persist

import com.autoscript.domain.json.DomainJson
import java.io.IOException

/**
 * jsonl 行级解析/转义（persist 包内共享）—— codec 合一后的薄件。
 *
 * 服务的是"冻结行格式"：键为字符串，值为字符串/整数/null/字符串数组四种。
 * codec 走 `:domain` [DomainJson]（仓内唯一 codec），本文件只留两件 persist 专属的事：
 *
 * 1. **值域裁剪**：[parse] 逐值 unwrap 到四型，布尔/嵌套对象照旧响亮拒绝 ——
 *    冻结行格式的值域是协议，不是 codec 的事；
 * 2. **错误类型保型**：codec 抛 IllegalArgumentException，persist 层契约是
 *    IOException（"行损坏"，消费方 try/catch 口径与单测断言面不变）—— 一律包一层。
 *
 * 单测与生产同一份解析，格式漂移在编译期可见而非运行时爆炸。persist 层零第三方依赖
 * （只有 :domain + JDK）的口径不破。
 *
 * 兼容边：老 quote 不转义控制字符，含裸控制符的存量行会被
 * DomainJson 拒（"未转义控制字符"）—— 落在 IOException 保型内，表现仍是"行损坏"响亮失败。
 */
internal object JsonLine {

    /** 解析一行 `{...}` 为字段表（值仅为 String/Long/null/字符串数组 四种）。 */
    fun parse(line: String): Map<String, Any?> {
        val m = try {
            DomainJson.decodeObject(line)
        } catch (e: IllegalArgumentException) {
            throw IOException("journal 行损坏：${e.message}", e)
        }
        val fields = LinkedHashMap<String, Any?>(m.size)
        for ((k, v) in m) {
            fields[k] = when (v) {
                is DomainJson.Value.S -> v.v
                is DomainJson.Value.N -> v.raw.toLongOrNull()
                    ?: throw IOException("journal 行损坏：字段 $k 非整数（${v.raw}）")
                DomainJson.Value.Null -> null
                is DomainJson.Value.Arr -> v.items.map {
                    (it as? DomainJson.Value.S)?.v
                        ?: throw IOException("journal 行损坏：字段 $k 数组含非字符串")
                }
                else -> throw IOException("journal 行损坏：字段 $k 值型越界（仅 字符串/整数/null/字符串数组）")
            }
        }
        return fields
    }

    /** 字符串数组编码（args 等列表字段；空列表 = `[]`）。 */
    fun quoteAll(items: List<String>): String = DomainJson.encode(items)

    fun quote(s: String): String = DomainJson.encode(s)
}

/** 字段表强类型读取（缺键/类型错 = 行损坏，响亮失败）。 */
internal fun Map<String, Any?>.str(key: String): String =
    this[key] as? String ?: throw IOException("journal 行损坏：缺字符串字段 $key")

internal fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Long) ?: throw IOException("journal 行损坏：缺数字字段 $key")

internal fun Map<String, Any?>.optLong(key: String): Long? =
    when (val v = this[key]) {
        null -> null
        is Long -> v
        else -> throw IOException("journal 行损坏：字段 $key 非数字")
    }

internal fun Map<String, Any?>.optStr(key: String): String? =
    when (val v = this[key]) {
        null -> null
        is String -> v
        else -> throw IOException("journal 行损坏：字段 $key 非字符串")
    }

/** 缺键（老 journal 行）= 空列表；类型错 = 行损坏响亮失败。 */
internal fun Map<String, Any?>.optStrList(key: String): List<String> =
    when (val v = this[key]) {
        null -> emptyList()
        is List<*> -> v.map { it as? String ?: throw IOException("journal 行损坏：字段 $key 数组含非字符串") }
        else -> throw IOException("journal 行损坏：字段 $key 非字符串数组")
    }

package com.autoscript.testkit

import com.autoscript.appservice.npm.DirSizer
import com.autoscript.domain.json.DomainJson

/** 测试里的打包前清单；调用后再删改源树，模拟 APK 剪裁或损坏。 */
fun npmManifest(files: Map<String, ByteArray>): String = DomainJson.encode(
    mapOf(
        "count" to files.size,
        "bytes" to files.values.sumOf { it.size.toLong() },
        "files" to files.map { (path, bytes) -> mapOf("path" to path, "sha256" to DirSizer.sha256(bytes)) },
    ),
)

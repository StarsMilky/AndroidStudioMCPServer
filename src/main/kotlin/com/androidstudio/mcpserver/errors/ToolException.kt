package com.androidstudio.mcpserver.errors

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ToolException(
    val errorCode: McpErrorCode,
    val data: Map<String, String> = emptyMap()
) : RuntimeException(errorCode.message) {

    fun toErrorJson(): String = buildJsonObject {
        put("error_code", errorCode.code)
        put("message", errorCode.message)
        put("data", buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
    }.toString()
}

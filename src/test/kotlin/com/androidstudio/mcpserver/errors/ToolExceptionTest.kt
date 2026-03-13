package com.androidstudio.mcpserver.errors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolExceptionTest {

    @Test
    fun `toErrorJson produces valid JSON with error_code and message`() {
        val ex = ToolException(McpErrorCode.SYMBOL_NOT_FOUND)
        val json = Json.parseToJsonElement(ex.toErrorJson()).jsonObject

        assertEquals("symbol_not_found", json["error_code"]?.jsonPrimitive?.content)
        assertEquals("Symbol not found at specified position", json["message"]?.jsonPrimitive?.content)
        assertNotNull(json["data"])
    }

    @Test
    fun `toErrorJson includes data map`() {
        val ex = ToolException(
            McpErrorCode.PSI_ERROR,
            mapOf("file" to "Test.kt", "reason" to "Parse failed")
        )
        val json = Json.parseToJsonElement(ex.toErrorJson()).jsonObject
        val data = json["data"]?.jsonObject

        assertNotNull(data)
        assertEquals("Test.kt", data!!["file"]?.jsonPrimitive?.content)
        assertEquals("Parse failed", data["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toErrorJson with empty data produces empty data object`() {
        val ex = ToolException(McpErrorCode.TIMEOUT)
        val json = Json.parseToJsonElement(ex.toErrorJson()).jsonObject
        val data = json["data"]?.jsonObject

        assertNotNull(data)
        assertTrue(data!!.isEmpty())
    }

    @Test
    fun `exception message matches error code message`() {
        val ex = ToolException(McpErrorCode.INDEXING_IN_PROGRESS)
        assertEquals(McpErrorCode.INDEXING_IN_PROGRESS.message, ex.message)
    }
}

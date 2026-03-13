package com.androidstudio.mcpserver.formatting

import com.androidstudio.mcpserver.util.McpJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResponseFormatterTest {

    @Serializable
    data class SmallResult(val name: String, val value: Int)

    @Serializable
    data class ListResult(
        val total: Int,
        val items: List<String>,
        val truncated: Boolean = false,
        val hint: String? = null
    )

    @Test
    fun `format returns original JSON when within size limit`() {
        val result = SmallResult("test", 42)
        val json = ResponseFormatter.format(result, SmallResult.serializer(), SizePolicy.RESOLVE_SYMBOL)
        val parsed = McpJson.parseToJsonElement(json).jsonObject

        assertEquals("test", parsed["name"]?.jsonPrimitive?.content)
        assertEquals(42, parsed["value"]?.jsonPrimitive?.int)
    }

    @Test
    fun `format truncates list when exceeding size limit`() {
        val items = (1..100).map { "item_${it}_with_a_very_long_suffix_to_increase_size_${it}" }
        val result = ListResult(total = items.size, items = items)

        val tinyPolicy = SizePolicy.RESOLVE_SYMBOL
        val json = ResponseFormatter.format(result, ListResult.serializer(), tinyPolicy)
        val parsed = McpJson.parseToJsonElement(json).jsonObject

        assertEquals(true, parsed["truncated"]?.jsonPrimitive?.boolean)
        assertNotNull(parsed["hint"])

        val truncatedItems = parsed["items"]?.jsonArray
        assertNotNull(truncatedItems)
        assertTrue(
            "Truncated list should be smaller than original",
            truncatedItems!!.size < items.size
        )
    }

    @Test
    fun `format does not truncate when list fits within limit`() {
        val result = ListResult(total = 2, items = listOf("a", "b"))
        val json = ResponseFormatter.format(result, ListResult.serializer(), SizePolicy.CHECKPOINT)
        val parsed = McpJson.parseToJsonElement(json).jsonObject

        assertEquals(false, parsed["truncated"]?.jsonPrimitive?.boolean)
        assertNull(parsed["hint"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, parsed["items"]?.jsonArray?.size)
    }

    @Test
    fun `SizePolicy has 14 entries matching data model`() {
        assertEquals(14, SizePolicy.entries.size)
    }

    @Test
    fun `SizePolicy values match spec limits`() {
        assertEquals(200, SizePolicy.RESOLVE_SYMBOL.maxBytes)
        assertEquals(3072, SizePolicy.FIND_REFERENCES.maxBytes)
        assertEquals(3584, SizePolicy.GET_SCOPE.maxBytes)
        assertEquals(16384, SizePolicy.QUERY_PROJECT_PANORAMA.maxBytes)
        assertEquals(4096, SizePolicy.SANDBOX.maxBytes)
    }
}

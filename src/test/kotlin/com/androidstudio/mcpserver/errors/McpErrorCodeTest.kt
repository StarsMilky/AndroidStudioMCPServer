package com.androidstudio.mcpserver.errors

import org.junit.Assert.*
import org.junit.Test

class McpErrorCodeTest {

    @Test
    fun `all error codes have unique codes`() {
        val codes = McpErrorCode.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `all error codes have non-empty messages`() {
        McpErrorCode.entries.forEach { errorCode ->
            assertTrue("${errorCode.name} has empty message", errorCode.message.isNotBlank())
        }
    }

    @Test
    fun `error code count matches spec (6 codes)`() {
        assertEquals(6, McpErrorCode.entries.size)
    }

    @Test
    fun `INDEXING_IN_PROGRESS has correct code`() {
        assertEquals("indexing_in_progress", McpErrorCode.INDEXING_IN_PROGRESS.code)
    }
}

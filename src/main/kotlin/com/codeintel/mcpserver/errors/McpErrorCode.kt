package com.codeintel.mcpserver.errors

import kotlinx.serialization.Serializable

@Serializable
enum class McpErrorCode(val code: String, val message: String) {
    INDEXING_IN_PROGRESS("indexing_in_progress", "IDE is still indexing, please retry later"),
    SYMBOL_NOT_FOUND("symbol_not_found", "Symbol not found at specified position"),
    CONFLICT_DETECTED("conflict_detected", "File was modified during operation"),
    TIMEOUT("timeout", "Operation timed out"),
    PSI_ERROR("psi_error", "PSI analysis error"),
    INVALID_SCOPE("invalid_scope", "Invalid scope parameter"),
}

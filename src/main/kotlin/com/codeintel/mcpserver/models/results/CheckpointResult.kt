package com.codeintel.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class CheckpointResult(
    val label: String? = null,
    val timestamp: String? = null,
    val entries: List<com.codeintel.mcpserver.models.results.HistoryEntry>? = null,
    val diff: String? = null,
    val restoredFiles: Int? = null,
    val hint: String? = null,
)

@Serializable
data class HistoryEntry(
    val timestamp: String,
    val label: String?,
    val sizeDelta: String,
)

package com.androidstudio.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class CheckpointResult(
    val label: String? = null,
    val timestamp: String? = null,
    val entries: List<HistoryEntry>? = null,
    val diff: String? = null,
    val restoredFiles: Int? = null
)

@Serializable
data class HistoryEntry(
    val timestamp: String,
    val label: String?,
    val sizeDelta: String
)

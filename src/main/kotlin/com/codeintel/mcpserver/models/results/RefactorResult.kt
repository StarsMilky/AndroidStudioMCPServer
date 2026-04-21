package com.codeintel.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class RefactorResult(
    val success: Boolean,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val preview: List<com.codeintel.mcpserver.models.results.ChangePreview>? = null,
    val conflicts: List<String>? = null,
    val checkpointLabel: String,
    val nextAction: String? = null,
)

@Serializable
data class ChangePreview(val file: String, val change: String)

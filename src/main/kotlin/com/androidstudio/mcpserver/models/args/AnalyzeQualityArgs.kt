package com.androidstudio.mcpserver.models.args
import kotlinx.serialization.Serializable

@Serializable
data class AnalyzeQualityArgs(
    val mode: QualityMode,
    val scope: String = "project",
    val target: String? = null,
    val topN: Int = 10,
)

@Serializable
enum class QualityMode { COMPLEXITY, DEAD_CODE, CLONES, PATTERNS, ERROR_HANDLING }

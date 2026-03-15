package com.androidstudio.mcpserver.models.results
import kotlinx.serialization.Serializable

@Serializable
data class QualityReport(
    val mode: String,
    val issues: List<QualityIssue>,
    val truncated: Boolean = false,
    val hint: String? = null,
)

@Serializable
data class QualityIssue(
    val type: String,
    val severity: String,
    val file: String,
    val line: Int? = null,
    val description: String,
    val suggestion: String? = null,
    val metrics: Map<String, String>? = null,
)

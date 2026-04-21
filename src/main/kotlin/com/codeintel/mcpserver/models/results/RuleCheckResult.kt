package com.codeintel.mcpserver.models.results
import kotlinx.serialization.Serializable

@Serializable
data class RuleCheckResult(
    val passed: Int,
    val failed: Int,
    val violations: List<com.codeintel.mcpserver.models.results.RuleViolation>,
    val truncated: Boolean = false,
    val hint: String? = null,
    val nextAction: String? = null,
)

@Serializable
data class RuleViolation(
    val rule: String,
    val violator: String,
    val illegalDependency: String,
    val file: String,
    val line: Int,
    val suggestion: String? = null,
)

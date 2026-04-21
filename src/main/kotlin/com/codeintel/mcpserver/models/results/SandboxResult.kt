package com.codeintel.mcpserver.models.results
import kotlinx.serialization.Serializable

@Serializable
data class SandboxResult(
    val sourceCode: String? = null,
    val artifact: String? = null,
    val kotlinCode: String? = null,
    val warnings: List<String>? = null,
    val problemsFound: Int? = null,
    val problemsFixed: Int? = null,
    val unfixable: List<com.codeintel.mcpserver.models.results.UnfixableItem>? = null,
    val checkpointLabel: String? = null,
    val nextAction: String? = null,
)

@Serializable
data class UnfixableItem(val file: String, val line: Int, val reason: String)

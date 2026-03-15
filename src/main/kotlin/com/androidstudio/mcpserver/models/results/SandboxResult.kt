package com.androidstudio.mcpserver.models.results
import kotlinx.serialization.Serializable

@Serializable
data class SandboxResult(
    val sourceCode: String? = null,
    val artifact: String? = null,
    val kotlinCode: String? = null,
    val warnings: List<String>? = null,
    val problemsFound: Int? = null,
    val problemsFixed: Int? = null,
    val unfixable: List<UnfixableItem>? = null,
    val checkpointLabel: String? = null,
)

@Serializable
data class UnfixableItem(val file: String, val line: Int, val reason: String)

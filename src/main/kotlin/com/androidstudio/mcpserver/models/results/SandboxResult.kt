package com.androidstudio.mcpserver.models.results
import kotlinx.serialization.Serializable

@Serializable
data class SandboxResult(
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val compileErrors: List<String>? = null,
    val sourceCode: String? = null,
    val artifact: String? = null,
    val jarPath: String? = null,
    val imageBase64: String? = null,
    val renderWarnings: List<String>? = null,
    val kotlinCode: String? = null,
    val warnings: List<String>? = null,
    val problemsFound: Int? = null,
    val problemsFixed: Int? = null,
    val unfixable: List<UnfixableItem>? = null,
    val timedOut: Boolean = false,
    val checkpointLabel: String? = null
)

@Serializable
data class UnfixableItem(val file: String, val line: Int, val reason: String)

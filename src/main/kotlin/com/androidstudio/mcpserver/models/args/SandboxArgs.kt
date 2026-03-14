package com.androidstudio.mcpserver.models.args
import kotlinx.serialization.Serializable

@Serializable
data class SandboxArgs(
    val operation: SandboxOperation,
    val timeout: Int = 30,
    val qualifiedClassName: String? = null,
    val javaFile: String? = null,
    val inspectionScope: String? = null,
    val inspectionIds: List<String>? = null,
    val dryRun: Boolean = true
)

@Serializable
enum class SandboxOperation { DECOMPILE, CONVERT_J2K, BATCH_FIX }

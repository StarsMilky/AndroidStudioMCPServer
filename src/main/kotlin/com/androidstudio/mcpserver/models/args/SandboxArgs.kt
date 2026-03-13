package com.androidstudio.mcpserver.models.args
import kotlinx.serialization.Serializable

@Serializable
data class SandboxArgs(
    val operation: SandboxOperation,
    val language: String? = null,
    val code: String? = null,
    val moduleContext: String? = null,
    val timeout: Int = 30,
    val qualifiedClassName: String? = null,
    val layoutFile: String? = null,
    val deviceConfig: DeviceConfig? = null,
    val javaFile: String? = null,
    val inspectionScope: String? = null,
    val inspectionIds: List<String>? = null,
    val dryRun: Boolean = true
)

@Serializable
enum class SandboxOperation { SCRATCH, DECOMPILE, RENDER, CONVERT_J2K, BATCH_FIX }

@Serializable
data class DeviceConfig(
    val screenWidthDp: Int = 360,
    val screenHeightDp: Int = 640,
    val density: String = "xxhdpi",
    val nightMode: Boolean = false,
    val locale: String = "en",
    val apiLevel: Int = 34
)

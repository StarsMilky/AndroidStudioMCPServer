package com.codeintel.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class FindReferencesArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val mode: com.codeintel.mcpserver.models.args.FindReferencesMode = _root_ide_package_.com.codeintel.mcpserver.models.args.FindReferencesMode.USAGES,
    val scope: String = "project",
    val depth: Int = 3,
    val offset: Int = 0,
    val limit: Int = 20,
)

@Serializable
enum class FindReferencesMode {
    USAGES, CALLERS, CALLEES, TYPE_HIERARCHY,
    @Deprecated("Use CALLERS instead")
    CALL_HIERARCHY
}

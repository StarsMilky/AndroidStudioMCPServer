package com.codeintel.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class FindReferencesArgs(
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val mode: FindReferencesMode = FindReferencesMode.USAGES,
    val scope: String = "project",
    val depth: Int = 3,
    val offset: Int = 0,
    val limit: Int = 20,
    @kotlinx.serialization.SerialName("qualified_name")
    val qualifiedName: String? = null,
)

@Serializable
enum class FindReferencesMode {
    USAGES, CALLERS, CALLEES, TYPE_HIERARCHY,
    @Deprecated("Use CALLERS instead")
    CALL_HIERARCHY
}

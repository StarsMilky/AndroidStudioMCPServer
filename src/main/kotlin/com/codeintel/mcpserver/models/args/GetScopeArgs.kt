package com.codeintel.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class GetScopeArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val filter: com.codeintel.mcpserver.models.args.ScopeFilter = _root_ide_package_.com.codeintel.mcpserver.models.args.ScopeFilter.ALL,
)

@Serializable
enum class ScopeFilter {
    ALL, VARIABLES, METHODS, TYPES
}

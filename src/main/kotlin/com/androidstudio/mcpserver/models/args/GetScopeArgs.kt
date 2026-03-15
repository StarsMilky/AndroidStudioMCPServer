package com.androidstudio.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class GetScopeArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val filter: ScopeFilter = ScopeFilter.ALL,
)

@Serializable
enum class ScopeFilter {
    ALL, VARIABLES, METHODS, TYPES
}

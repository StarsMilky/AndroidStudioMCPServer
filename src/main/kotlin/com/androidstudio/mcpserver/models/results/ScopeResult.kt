package com.androidstudio.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class ScopeResult(
    val localVariables: List<ScopeSymbol>,
    val thisMembers: List<ScopeSymbol>,
    val extensionFunctions: List<ScopeSymbol>,
    val importedSymbols: List<ScopeSymbol>,
    val truncated: Boolean = false
)

@Serializable
data class ScopeSymbol(
    val name: String,
    val type: String,
    val kind: String
)

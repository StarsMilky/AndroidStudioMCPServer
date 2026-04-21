package com.codeintel.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class ScopeResult(
    val localVariables: List<com.codeintel.mcpserver.models.results.ScopeSymbol>,
    val thisMembers: List<com.codeintel.mcpserver.models.results.ScopeSymbol>,
    val extensionFunctions: List<com.codeintel.mcpserver.models.results.ScopeSymbol>,
    val importedSymbols: List<com.codeintel.mcpserver.models.results.ScopeSymbol>,
    val truncated: Boolean = false,
    val nextAction: String? = null,
)

@Serializable
data class ScopeSymbol(
    val name: String,
    val type: String,
    val kind: String,
)

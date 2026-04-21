package com.codeintel.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class FindSymbolResult(
    val matches: List<SymbolInfo>,
    val totalMatches: Int,
    val nextAction: String? = null,
)

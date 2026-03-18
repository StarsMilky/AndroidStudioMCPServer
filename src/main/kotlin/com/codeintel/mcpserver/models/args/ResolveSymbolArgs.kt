package com.codeintel.mcpserver.models.args

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SymbolKindFilter {
    @SerialName("CLASS") CLASS,
    @SerialName("METHOD") METHOD,
    @SerialName("FIELD") FIELD,
    @SerialName("ALL") ALL,
}

@Serializable
data class ResolveSymbolArgs(
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val name: String? = null,
    val kind: SymbolKindFilter? = null,
    val scope: String = "project",
    val limit: Int = 10,
)

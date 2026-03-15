package com.androidstudio.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class SymbolInfo(
    val qualifiedType: String,
    val declarationFile: String,
    val declarationLine: Int,
    val kind: SymbolKind,
)

@Serializable
enum class SymbolKind {
    CLASS, METHOD, FIELD, VARIABLE, PARAMETER, PROPERTY, PACKAGE, OBJECT, ENUM_ENTRY
}

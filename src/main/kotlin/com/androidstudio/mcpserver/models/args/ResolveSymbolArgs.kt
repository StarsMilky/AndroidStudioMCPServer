package com.androidstudio.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class ResolveSymbolArgs(
    val file: String,
    val line: Int,
    val column: Int,
)

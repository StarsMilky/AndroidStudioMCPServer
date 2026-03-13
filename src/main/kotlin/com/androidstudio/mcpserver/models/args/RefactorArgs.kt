package com.androidstudio.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class RefactorArgs(
    val operation: RefactorOperation,
    val file: String,
    val line: Int? = null,
    val column: Int? = null,
    val newName: String? = null,
    val targetPackage: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val methodName: String? = null,
    val newParameters: List<ParameterChange>? = null,
    val newReturnType: String? = null
)

@Serializable
enum class RefactorOperation {
    RENAME, MOVE, EXTRACT, SAFE_DELETE, CHANGE_SIGNATURE
}

@Serializable
data class ParameterChange(
    val name: String,
    val type: String,
    val defaultValue: String? = null
)

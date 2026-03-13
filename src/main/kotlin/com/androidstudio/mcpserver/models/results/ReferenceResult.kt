package com.androidstudio.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class ReferenceResult(
    val total: Int,
    val usages: List<UsageInfo>? = null,
    val callHierarchy: CallNode? = null,
    val typeHierarchy: TypeHierarchyInfo? = null,
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class UsageInfo(
    val file: String,
    val line: Int,
    val code: String,
    val usageType: UsageType
)

@Serializable
enum class UsageType {
    CALL, OVERRIDE, READ, WRITE
}

@Serializable
data class CallNode(
    val method: String,
    val file: String,
    val line: Int,
    val children: List<CallNode> = emptyList()
)

@Serializable
data class TypeHierarchyInfo(
    val target: String,
    val supers: List<String>,
    val inheritors: List<InheritorInfo>
)

@Serializable
data class InheritorInfo(val className: String, val file: String)

package com.codeintel.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class ReferenceResult(
    val total: Int,
    val usages: List<com.codeintel.mcpserver.models.results.UsageInfo>? = null,
    val callHierarchy: com.codeintel.mcpserver.models.results.CallNode? = null,
    val typeHierarchy: com.codeintel.mcpserver.models.results.TypeHierarchyInfo? = null,
    val truncated: Boolean = false,
    val hint: String? = null,
)

@Serializable
data class UsageInfo(
    val file: String,
    val line: Int,
    val code: String,
    val usageType: com.codeintel.mcpserver.models.results.UsageType,
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
    val children: List<CallNode> = emptyList(),
)

@Serializable
data class TypeHierarchyInfo(
    val target: String,
    val supers: List<String>,
    val inheritors: List<com.codeintel.mcpserver.models.results.InheritorInfo>,
)

@Serializable
data class InheritorInfo(val className: String, val file: String)

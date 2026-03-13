package com.androidstudio.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class QueryProjectArgs(
    val mode: QueryProjectMode = QueryProjectMode.OVERVIEW,
    val targetClass: String? = null,
    val changeType: ChangeType? = null,
    val maxHops: Int = 3,
    val module: String? = null
)

@Serializable
enum class QueryProjectMode {
    OVERVIEW, DEPENDENCY, API_SURFACE, VARIANT
}

@Serializable
enum class ChangeType {
    SIGNATURE_CHANGE, BEHAVIOR_CHANGE, DELETE
}

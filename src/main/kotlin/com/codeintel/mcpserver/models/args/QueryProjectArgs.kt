package com.codeintel.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class QueryProjectArgs(
    val mode: com.codeintel.mcpserver.models.args.QueryProjectMode = _root_ide_package_.com.codeintel.mcpserver.models.args.QueryProjectMode.OVERVIEW,
    val targetClass: String? = null,
    val changeType: com.codeintel.mcpserver.models.args.ChangeType? = null,
    val maxHops: Int = 3,
    val module: String? = null,
)

@Serializable
enum class QueryProjectMode {
    OVERVIEW, DEPENDENCY, API_SURFACE, VARIANT
}

@Serializable
enum class ChangeType {
    SIGNATURE_CHANGE, BEHAVIOR_CHANGE, DELETE
}

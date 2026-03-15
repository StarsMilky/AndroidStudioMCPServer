package com.codeintel.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class QueryFrameworkArgs(
    val framework: com.codeintel.mcpserver.models.args.FrameworkType,
    val detailTarget: String? = null,
)

@Serializable
enum class FrameworkType {
    ROOM, RETROFIT, HILT, COMPOSE, NAVIGATION
}

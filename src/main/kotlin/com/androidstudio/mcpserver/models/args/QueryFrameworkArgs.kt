package com.androidstudio.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class QueryFrameworkArgs(
    val framework: FrameworkType,
    val detailTarget: String? = null
)

@Serializable
enum class FrameworkType {
    ROOM, RETROFIT, HILT, COMPOSE, NAVIGATION
}

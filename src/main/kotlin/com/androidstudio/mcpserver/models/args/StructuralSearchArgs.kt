package com.androidstudio.mcpserver.models.args
import kotlinx.serialization.Serializable

@Serializable
data class StructuralSearchArgs(
    val pattern: String,
    val fileType: String = "kotlin",
    val scope: String = "project",
    val typeConstraint: String? = null,
    val limit: Int = 20
)

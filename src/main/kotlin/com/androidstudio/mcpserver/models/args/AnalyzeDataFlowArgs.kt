package com.androidstudio.mcpserver.models.args
import kotlinx.serialization.Serializable

@Serializable
data class AnalyzeDataFlowArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val mode: DataFlowMode = DataFlowMode.NULLABILITY
)

@Serializable
enum class DataFlowMode { NULLABILITY, FORWARD, BACKWARD, EXTERNAL_ANNOTATIONS }

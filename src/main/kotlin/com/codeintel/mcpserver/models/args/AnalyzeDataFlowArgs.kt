package com.codeintel.mcpserver.models.args
import kotlinx.serialization.Serializable

@Serializable
data class AnalyzeDataFlowArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val mode: com.codeintel.mcpserver.models.args.DataFlowMode = _root_ide_package_.com.codeintel.mcpserver.models.args.DataFlowMode.NULLABILITY,
)

@Serializable
enum class DataFlowMode { NULLABILITY, FORWARD, BACKWARD, EXTERNAL_ANNOTATIONS }

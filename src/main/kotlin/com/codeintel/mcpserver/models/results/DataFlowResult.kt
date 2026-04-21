package com.codeintel.mcpserver.models.results
import kotlinx.serialization.Serializable

@Serializable
data class DataFlowResult(
    val nullability: String? = null,
    val reason: String? = null,
    val nullPaths: List<String>? = null,
    val flowPaths: List<com.codeintel.mcpserver.models.results.FlowPath>? = null,
    val annotations: List<com.codeintel.mcpserver.models.results.ExternalAnnotationInfo>? = null,
    val nextAction: String? = null,
)

@Serializable
data class FlowPath(val steps: List<com.codeintel.mcpserver.models.results.FlowStep>)

@Serializable
data class FlowStep(val file: String, val line: Int, val code: String)

@Serializable
data class ExternalAnnotationInfo(
    val annotation: String,
    val target: String,
    val source: String = "JetBrains external annotations",
)

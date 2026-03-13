package com.androidstudio.mcpserver.models.results
import kotlinx.serialization.Serializable

@Serializable
data class DataFlowResult(
    val nullability: String? = null,
    val reason: String? = null,
    val nullPaths: List<String>? = null,
    val flowPaths: List<FlowPath>? = null
)

@Serializable
data class FlowPath(val steps: List<FlowStep>)

@Serializable
data class FlowStep(val file: String, val line: Int, val code: String)

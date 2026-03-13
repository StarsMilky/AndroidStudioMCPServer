package com.androidstudio.mcpserver.models.args
import kotlinx.serialization.Serializable

@Serializable
data class CheckRulesArgs(val rules: List<ArchitectureRule>)

@Serializable
data class ArchitectureRule(
    val name: String,
    val source: String,
    val mustNotDependOn: List<String>
)

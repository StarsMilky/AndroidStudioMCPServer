package com.androidstudio.mcpserver.models.args

import kotlinx.serialization.Serializable

@Serializable
data class CheckpointArgs(
    val operation: CheckpointOperation,
    val label: String? = null,
    val file: String? = null,
    val targetLabel: String? = null,
)

@Serializable
enum class CheckpointOperation {
    CREATE, HISTORY, ROLLBACK, DIFF
}

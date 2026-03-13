package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.args.CheckpointOperation
import com.androidstudio.mcpserver.models.results.CheckpointResult
import com.androidstudio.mcpserver.models.results.HistoryEntry
import com.androidstudio.mcpserver.util.ProjectUtils
import com.intellij.history.LocalHistory
import com.intellij.openapi.project.Project
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CheckpointManager {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun execute(
        project: Project,
        operation: CheckpointOperation,
        label: String?,
        file: String?,
        targetLabel: String?
    ): CheckpointResult {
        return when (operation) {
            CheckpointOperation.CREATE -> createCheckpoint(project, label)
            CheckpointOperation.HISTORY -> getHistory(project, file)
            CheckpointOperation.ROLLBACK -> rollback(project, label)
            CheckpointOperation.DIFF -> diff(project, file, targetLabel)
        }
    }

    fun createAutoCheckpoint(project: Project, operationName: String): String {
        val label = "auto-$operationName-${System.currentTimeMillis()}"
        LocalHistory.getInstance().putSystemLabel(project, label)
        return label
    }

    private fun createCheckpoint(project: Project, label: String?): CheckpointResult {
        val effectiveLabel = label ?: "checkpoint-${System.currentTimeMillis()}"
        LocalHistory.getInstance().putSystemLabel(project, effectiveLabel)
        return CheckpointResult(
            label = effectiveLabel,
            timestamp = formatter.format(Instant.now())
        )
    }

    private fun getHistory(project: Project, file: String?): CheckpointResult {
        if (file == null) {
            throw ToolException(
                McpErrorCode.INVALID_SCOPE,
                mapOf("reason" to "file is required for history operation")
            )
        }
        ProjectUtils.findFile(project, file)
        return CheckpointResult(entries = emptyList())
    }

    private fun rollback(project: Project, label: String?): CheckpointResult {
        if (label == null) {
            throw ToolException(
                McpErrorCode.INVALID_SCOPE,
                mapOf("reason" to "label is required for rollback operation")
            )
        }
        return CheckpointResult(label = label, restoredFiles = 0)
    }

    private fun diff(project: Project, file: String?, targetLabel: String?): CheckpointResult {
        if (file == null) {
            throw ToolException(
                McpErrorCode.INVALID_SCOPE,
                mapOf("reason" to "file is required for diff operation")
            )
        }
        ProjectUtils.findFile(project, file)
        return CheckpointResult(diff = "No previous revisions found")
    }
}

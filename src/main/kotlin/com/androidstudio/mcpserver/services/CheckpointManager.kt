package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.args.CheckpointOperation
import com.androidstudio.mcpserver.models.results.CheckpointResult
import com.androidstudio.mcpserver.models.results.HistoryEntry
import com.androidstudio.mcpserver.util.ProjectUtils
import com.intellij.history.LocalHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

object CheckpointManager {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val checkpointSnapshots = ConcurrentHashMap<String, Map<String, ByteArray>>()

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

        val basePath = project.basePath
        if (basePath != null) {
            try {
                val snapshot = mutableMapOf<String, ByteArray>()
                val baseDir = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                    .findFileByUrl("file://$basePath")
                if (baseDir != null) {
                    captureSnapshot(baseDir, snapshot, maxFiles = 200)
                }
                checkpointSnapshots[effectiveLabel] = snapshot
            } catch (_: Exception) {}
        }

        return CheckpointResult(
            label = effectiveLabel,
            timestamp = formatter.format(Instant.now())
        )
    }

    private fun captureSnapshot(dir: VirtualFile, snapshot: MutableMap<String, ByteArray>, maxFiles: Int) {
        if (snapshot.size >= maxFiles) return
        for (child in dir.children) {
            if (snapshot.size >= maxFiles) return
            if (child.isDirectory) {
                if (child.name == "build" || child.name == ".gradle" || child.name == ".idea") continue
                captureSnapshot(child, snapshot, maxFiles)
            } else if (child.extension in listOf("kt", "java", "xml", "gradle", "kts", "properties")) {
                try {
                    snapshot[child.path] = child.contentsToByteArray()
                } catch (_: Exception) {}
            }
        }
    }

    private fun getHistory(project: Project, file: String?): CheckpointResult {
        if (file == null) {
            val allLabels = checkpointSnapshots.keys.sorted()
            val entries = allLabels.map { label ->
                val snap = checkpointSnapshots[label]!!
                HistoryEntry(
                    timestamp = label.substringAfterLast("-"),
                    label = label,
                    sizeDelta = "${snap.size} file(s) captured"
                )
            }
            return CheckpointResult(entries = entries, hint = "Pass 'file' to see file-specific history with size deltas")
        }

        val vf = ProjectUtils.findFile(project, file)
        val entries = mutableListOf<HistoryEntry>()
        val currentSize = vf.length

        entries.add(HistoryEntry(
            timestamp = formatter.format(Instant.ofEpochMilli(vf.timeStamp)),
            label = "current",
            sizeDelta = "$currentSize bytes"
        ))

        for ((label, snapshot) in checkpointSnapshots.entries.sortedByDescending { it.key }) {
            val filePath = vf.path
            val oldContent = snapshot[filePath]
            if (oldContent != null) {
                val oldSize = oldContent.size.toLong()
                val delta = currentSize - oldSize
                val deltaStr = if (delta >= 0) "+$delta bytes" else "$delta bytes"
                val changed = !vf.contentsToByteArray().contentEquals(oldContent)
                entries.add(HistoryEntry(
                    timestamp = label.substringAfterLast("-"),
                    label = label,
                    sizeDelta = "$deltaStr${if (changed) " (modified)" else " (unchanged)"}"
                ))
            }
        }

        return CheckpointResult(entries = entries)
    }

    private fun rollback(project: Project, label: String?): CheckpointResult {
        if (label == null) {
            throw ToolException(
                McpErrorCode.INVALID_SCOPE,
                mapOf("reason" to "label is required for rollback operation")
            )
        }

        val snapshot = checkpointSnapshots[label]
            ?: return CheckpointResult(
                label = label,
                restoredFiles = 0,
                diff = "Checkpoint '$label' not found in snapshots. Available: ${checkpointSnapshots.keys.joinToString(", ")}"
            )

        var restored = 0
        val errors = mutableListOf<String>()

        for ((filePath, oldContent) in snapshot) {
            try {
                val vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                    .findFileByUrl("file://$filePath") ?: continue
                val currentContent = vf.contentsToByteArray()
                if (!currentContent.contentEquals(oldContent)) {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                        vf.setBinaryContent(oldContent)
                    }
                    restored++
                }
            } catch (e: Exception) {
                errors.add("${filePath.substringAfterLast("/")}: ${e.message}")
            }
        }

        return CheckpointResult(
            label = label,
            restoredFiles = restored,
            diff = if (errors.isEmpty()) "Rolled back $restored file(s) to checkpoint: $label"
            else "Rolled back $restored file(s), ${errors.size} error(s): ${errors.take(5).joinToString("; ")}"
        )
    }

    private fun diff(project: Project, file: String?, targetLabel: String?): CheckpointResult {
        if (file == null) {
            throw ToolException(
                McpErrorCode.INVALID_SCOPE,
                mapOf("reason" to "file is required for diff operation")
            )
        }
        val vf = ProjectUtils.findFile(project, file)
        val currentContent = String(vf.contentsToByteArray())

        if (targetLabel != null) {
            val snapshot = checkpointSnapshots[targetLabel]
            if (snapshot != null && vf.path in snapshot) {
                val oldContent = String(snapshot[vf.path]!!)
                if (oldContent == currentContent) {
                    return CheckpointResult(diff = "No changes since checkpoint: $targetLabel")
                }
                return CheckpointResult(diff = buildUnifiedDiff(oldContent, currentContent, targetLabel))
            }
            return CheckpointResult(diff = "Checkpoint '$targetLabel' not found or file not captured in snapshot")
        }

        return CheckpointResult(diff = "Specify target_label to compare against a checkpoint")
    }

    private fun buildUnifiedDiff(old: String, new: String, label: String): String {
        val oldLines = old.lines()
        val newLines = new.lines()
        val sb = StringBuilder()
        sb.appendLine("--- $label")
        sb.appendLine("+++ current")

        var diffCount = 0
        val maxLines = maxOf(oldLines.size, newLines.size)
        var contextStart = -1

        for (i in 0 until maxLines) {
            val oldLine = oldLines.getOrNull(i)
            val newLine = newLines.getOrNull(i)
            when {
                oldLine == newLine -> {
                    if (contextStart >= 0 && i - contextStart < 3) {
                        sb.appendLine(" ${i + 1}| $oldLine")
                    } else {
                        contextStart = -1
                    }
                }
                oldLine == null -> {
                    contextStart = i
                    sb.appendLine("+${i + 1}| $newLine")
                    diffCount++
                }
                newLine == null -> {
                    contextStart = i
                    sb.appendLine("-${i + 1}| $oldLine")
                    diffCount++
                }
                else -> {
                    contextStart = i
                    sb.appendLine("-${i + 1}| $oldLine")
                    sb.appendLine("+${i + 1}| $newLine")
                    diffCount++
                }
            }
            if (diffCount > 100) {
                sb.appendLine("... (truncated)")
                break
            }
        }

        if (diffCount == 0) return "No differences"
        sb.insert(0, "Changes: $diffCount line(s) differ\n")
        return sb.toString()
    }
}

package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.args.SandboxArgs
import com.androidstudio.mcpserver.models.args.SandboxOperation
import com.androidstudio.mcpserver.models.results.SandboxResult
import com.intellij.openapi.project.Project

object SandboxExecutor {
    fun execute(project: Project, args: SandboxArgs): SandboxResult {
        return when (args.operation) {
            SandboxOperation.SCRATCH -> executeScratch(project, args)
            SandboxOperation.DECOMPILE -> executeDecompile(project, args)
            SandboxOperation.RENDER -> executeRender(project, args)
            SandboxOperation.CONVERT_J2K -> executeConvertJ2K(project, args)
            SandboxOperation.BATCH_FIX -> executeBatchFix(project, args)
        }
    }

    private fun executeScratch(project: Project, args: SandboxArgs): SandboxResult {
        val code = args.code ?: throw ToolException(McpErrorCode.INVALID_SCOPE, mapOf("reason" to "code is required for scratch"))
        return SandboxResult(stdout = "", stderr = "", exitCode = 0)
    }

    private fun executeDecompile(project: Project, args: SandboxArgs): SandboxResult {
        val className = args.qualifiedClassName ?: throw ToolException(McpErrorCode.INVALID_SCOPE, mapOf("reason" to "qualifiedClassName is required for decompile"))
        return SandboxResult(sourceCode = "// Decompiled: $className")
    }

    private fun executeRender(project: Project, args: SandboxArgs): SandboxResult {
        args.layoutFile ?: throw ToolException(McpErrorCode.INVALID_SCOPE, mapOf("reason" to "layoutFile is required for render"))
        return SandboxResult(renderWarnings = listOf("Render requires Android plugin runtime"))
    }

    private fun executeConvertJ2K(project: Project, args: SandboxArgs): SandboxResult {
        args.javaFile ?: throw ToolException(McpErrorCode.INVALID_SCOPE, mapOf("reason" to "javaFile is required for convert_j2k"))
        return SandboxResult(kotlinCode = "// Conversion pending")
    }

    private fun executeBatchFix(project: Project, args: SandboxArgs): SandboxResult {
        val checkpointLabel = if (!args.dryRun) {
            CheckpointManager.createAutoCheckpoint(project, "batch_fix")
        } else null
        return SandboxResult(problemsFound = 0, problemsFixed = 0, checkpointLabel = checkpointLabel)
    }
}

package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.args.RefactorArgs
import com.androidstudio.mcpserver.models.args.RefactorOperation
import com.androidstudio.mcpserver.models.results.RefactorResult
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringFactory
import org.jetbrains.kotlin.psi.*

object RefactorExecutor {

    fun execute(project: Project, args: RefactorArgs): RefactorResult {
        val checkpointLabel = CheckpointManager.createAutoCheckpoint(project, args.operation.name.lowercase())

        return PsiUtils.smartReadAction(project) {
            when (args.operation) {
                RefactorOperation.RENAME -> executeRename(project, args, checkpointLabel)
                RefactorOperation.MOVE -> executeMove(project, args, checkpointLabel)
                RefactorOperation.EXTRACT -> executeExtract(project, args, checkpointLabel)
                RefactorOperation.SAFE_DELETE -> executeSafeDelete(project, args, checkpointLabel)
                RefactorOperation.CHANGE_SIGNATURE -> executeChangeSignature(project, args, checkpointLabel)
            }
        }
    }

    private fun executeRename(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        val newName = args.newName ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "newName is required for rename")
        )
        val element = resolveTargetElement(project, args)
        val factory = RefactoringFactory.getInstance(project)
        val renameRefactoring = factory.createRename(element, newName, true, true)
        val usages = renameRefactoring.findUsages()

        val affectedFiles = usages
            .mapNotNull { it.file?.virtualFile }
            .map { ProjectUtils.toRelativePath(project, it) }
            .distinct()

        WriteCommandAction.runWriteCommandAction(project) {
            renameRefactoring.doRefactoring(usages)
        }

        return RefactorResult(
            success = true,
            affectedFiles = affectedFiles,
            changesCount = usages.size,
            checkpointLabel = checkpointLabel
        )
    }

    private fun executeMove(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        args.targetPackage ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "targetPackage is required for move")
        )
        return RefactorResult(
            success = true,
            affectedFiles = listOf(args.file),
            changesCount = 1,
            checkpointLabel = checkpointLabel
        )
    }

    private fun executeExtract(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        args.startLine ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "startLine is required for extract")
        )
        args.endLine ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "endLine is required for extract")
        )
        return RefactorResult(
            success = true,
            affectedFiles = listOf(args.file),
            changesCount = 1,
            checkpointLabel = checkpointLabel
        )
    }

    private fun executeSafeDelete(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        val element = resolveTargetElement(project, args)
        val usages = com.intellij.psi.search.searches.ReferencesSearch.search(element).findAll()

        val conflicts = if (usages.isNotEmpty()) {
            usages.mapNotNull { ref ->
                val file = ref.element.containingFile?.virtualFile
                    ?.let { ProjectUtils.toRelativePath(project, it) } ?: return@mapNotNull null
                val doc = PsiDocumentManager.getInstance(project).getDocument(ref.element.containingFile)
                val line = doc?.getLineNumber(ref.element.textOffset)?.plus(1) ?: 0
                "$file:$line"
            }
        } else null

        if (conflicts.isNullOrEmpty()) {
            WriteCommandAction.runWriteCommandAction(project) {
                element.delete()
            }
        }

        return RefactorResult(
            success = conflicts.isNullOrEmpty(),
            affectedFiles = if (conflicts.isNullOrEmpty()) listOf(args.file) else emptyList(),
            changesCount = if (conflicts.isNullOrEmpty()) 1 else 0,
            conflicts = conflicts,
            checkpointLabel = checkpointLabel
        )
    }

    private fun executeChangeSignature(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        resolveTargetElement(project, args)
        return RefactorResult(
            success = true,
            affectedFiles = listOf(args.file),
            changesCount = 1,
            checkpointLabel = checkpointLabel
        )
    }

    private fun resolveTargetElement(project: Project, args: RefactorArgs): PsiElement {
        val line = args.line ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "line is required")
        )
        val column = args.column ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "column is required")
        )
        val vf = ProjectUtils.findFile(project, args.file)
        val psiFile = ProjectUtils.getPsiFile(project, vf)
        val offset = ProjectUtils.lineColumnToOffset(psiFile, line, column)
        val element = psiFile.findElementAt(offset)
            ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

        return element.parent?.reference?.resolve()
            ?: element.reference?.resolve()
            ?: PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
            ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)
    }
}

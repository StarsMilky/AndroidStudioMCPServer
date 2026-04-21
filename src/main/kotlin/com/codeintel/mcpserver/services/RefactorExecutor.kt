package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.lang.LanguageAdapter
import com.codeintel.mcpserver.models.args.RefactorArgs
import com.codeintel.mcpserver.models.args.RefactorOperation
import com.codeintel.mcpserver.models.results.RefactorResult
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringFactory

object RefactorExecutor {

    fun execute(project: Project, args: RefactorArgs): RefactorResult {
        val checkpointLabel = CheckpointManager.createAutoCheckpoint(project, args.operation.name.lowercase())

        val result = when (args.operation) {
            RefactorOperation.RENAME -> executeRename(project, args, checkpointLabel)
            RefactorOperation.MOVE -> executeMove(project, args, checkpointLabel)
            RefactorOperation.EXTRACT -> executeExtract(project, args, checkpointLabel)
            RefactorOperation.SAFE_DELETE -> executeSafeDelete(project, args, checkpointLabel)
            RefactorOperation.CHANGE_SIGNATURE -> executeChangeSignature(project, args, checkpointLabel)
        }
        val hint = if (result.success) {
            "💡 Next: checkpoint(operation='DIFF', file='${args.file}', target_label='$checkpointLabel') to review, or ROLLBACK to revert."
        } else {
            "💡 Next: inspect conflicts[] above; fix or call checkpoint(operation='ROLLBACK', label='$checkpointLabel') to revert."
        }
        return result.copy(nextAction = hint)
    }

    private fun executeRename(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        val newName = args.newName ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "newName is required for rename")
        )

        val readResult = PsiUtils.smartReadAction(project) {
            val element = resolveTargetElement(project, args)
            val factory = RefactoringFactory.getInstance(project)
            val renameRefactoring = factory.createRename(element, newName, true, true)
            val usages = renameRefactoring.findUsages()
            val affectedFiles = usages
                .mapNotNull { it.file?.virtualFile }
                .map { ProjectUtils.toRelativePath(project, it) }
                .distinct()
            Triple(element, usages, affectedFiles)
        }

        val (element, usages, affectedFiles) = readResult

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val factory = RefactoringFactory.getInstance(project)
                val renameRefactoring = factory.createRename(element, newName, true, true)
                renameRefactoring.doRefactoring(usages)
            }
        }

        return RefactorResult(
            success = true,
            affectedFiles = affectedFiles,
            changesCount = usages.size,
            checkpointLabel = checkpointLabel
        )
    }

    private fun executeMove(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        val targetPackage = args.targetPackage ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "targetPackage is required for move")
        )

        val readResult = PsiUtils.smartReadAction(project) {
            val vf = ProjectUtils.findFile(project, args.file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val affectedFiles = mutableListOf(args.file)
            val decls = LanguageAdapter.all(project)
                .firstNotNullOfOrNull { it.listMovableDeclarations(psiFile) }
                ?: emptyList()
            val refs = decls.flatMap { d -> ReferencesSearch.search(d).findAll().toList() }
            affectedFiles.addAll(refs.mapNotNull { r ->
                r.element.containingFile?.virtualFile?.let { ProjectUtils.toRelativePath(project, it) }
            }.distinct())
            Pair(psiFile, affectedFiles)
        }

        val (psiFile, affectedFiles) = readResult

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val targetDir = findOrCreatePackageDir(project, psiFile, targetPackage)
                LanguageAdapter.all(project).any { it.setFilePackage(psiFile, targetPackage) }
                if (targetDir != null) {
                    targetDir.add(psiFile.copy())
                    psiFile.delete()
                }
            }
        }

        return RefactorResult(
            success = true,
            affectedFiles = affectedFiles.distinct(),
            changesCount = affectedFiles.size,
            checkpointLabel = checkpointLabel
        )
    }

    private fun findOrCreatePackageDir(project: Project, currentFile: PsiFile, targetPackage: String): PsiDirectory? {
        val sourceRoots = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentSourceRoots
        val currentRoot = sourceRoots.firstOrNull { root ->
            currentFile.virtualFile.path.startsWith(root.path)
        } ?: return null

        val packageParts = targetPackage.split(".")
        var dir = PsiManager.getInstance(project).findDirectory(currentRoot) ?: return null
        for (part in packageParts) {
            val subDir = dir.findSubdirectory(part)
            dir = if (subDir != null) {
                subDir
            } else {
                WriteCommandAction.writeCommandAction(project).compute<PsiDirectory, Exception> {
                    dir.createSubdirectory(part)
                }
            }
        }
        return dir
    }

    private fun executeExtract(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        val startLine = args.startLine ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "startLine is required for extract")
        )
        val endLine = args.endLine ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "endLine is required for extract")
        )
        val methodName = args.newName ?: args.methodName ?: "extractedMethod"

        data class ExtractInfo(val psiFile: PsiFile, val startOffset: Int, val endOffset: Int,
                               val selectedText: String, val containingClass: PsiElement?)

        val info = PsiUtils.smartReadAction(project) {
            val vf = ProjectUtils.findFile(project, args.file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: throw ToolException(McpErrorCode.PSI_ERROR, mapOf("reason" to "Cannot get document"))
            val startOffset = document.getLineStartOffset(startLine - 1)
            val endOffset = document.getLineEndOffset(endLine - 1)
            val selectedText = document.text.substring(startOffset, endOffset)
            val containingClass = LanguageAdapter.all(project)
                .firstNotNullOfOrNull { it.findContainingClassLike(psiFile, startOffset, endOffset) }
            ExtractInfo(psiFile, startOffset, endOffset, selectedText, containingClass)
        }

        if (info.containingClass == null) {
            return RefactorResult(
                success = false, affectedFiles = listOf(args.file), changesCount = 0,
                conflicts = listOf("Cannot find containing class for the selected range"),
                checkpointLabel = checkpointLabel
            )
        }

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val document = PsiDocumentManager.getInstance(project).getDocument(info.psiFile)
                    ?: return@runWriteCommandAction
                val adapters = LanguageAdapter.all(project)
                val containingClass = info.containingClass
                val inserted = adapters.any {
                    it.extractMethodInClass(containingClass, methodName, info.selectedText)
                }
                if (inserted) {
                    val callExpr = adapters
                        .firstNotNullOfOrNull { it.extractMethodCallExpression(containingClass, methodName) }
                        ?: "$methodName()"
                    document.replaceString(info.startOffset, info.endOffset, callExpr)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
            }
        }

        return RefactorResult(
            success = true, affectedFiles = listOf(args.file), changesCount = 1,
            checkpointLabel = checkpointLabel
        )
    }

    private fun executeSafeDelete(project: Project, args: RefactorArgs, checkpointLabel: String): RefactorResult {
        val readResult = PsiUtils.smartReadAction(project) {
            val element = resolveTargetElement(project, args)
            val usages = ReferencesSearch.search(element).findAll()
            val conflicts = if (usages.isNotEmpty()) {
                usages.mapNotNull { ref ->
                    val file = ref.element.containingFile?.virtualFile
                        ?.let { ProjectUtils.toRelativePath(project, it) } ?: return@mapNotNull null
                    val doc = PsiDocumentManager.getInstance(project).getDocument(ref.element.containingFile)
                    val line = doc?.getLineNumber(ref.element.textOffset)?.plus(1) ?: 0
                    "$file:$line"
                }
            } else null
            Pair(element, conflicts)
        }

        val (element, conflicts) = readResult

        if (conflicts.isNullOrEmpty()) {
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    element.delete()
                }
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
        val readResult = PsiUtils.smartReadAction(project) {
            val element = resolveTargetElement(project, args)
            val adapters = LanguageAdapter.all(project)
            val method = if (adapters.any { it.isMethodLike(element) }) element
                else adapters.firstNotNullOfOrNull { it.findEnclosingMethod(element) }
                ?: throw ToolException(
                    McpErrorCode.INVALID_SCOPE,
                    mapOf("reason" to "Target must be a method/function for change_signature")
                )
            val usages = ReferencesSearch.search(method).findAll()
            val affectedFiles = usages
                .mapNotNull { it.element.containingFile?.virtualFile }
                .map { ProjectUtils.toRelativePath(project, it) }
                .distinct()
                .toMutableList()
            affectedFiles.add(args.file)
            Triple(method, usages.size, affectedFiles)
        }

        val (method, usageCount, affectedFiles) = readResult

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val adapters = LanguageAdapter.all(project)
                if (args.newReturnType != null) {
                    adapters.any { it.changeReturnType(method, args.newReturnType) }
                }
                if (args.newParameters != null) {
                    adapters.any { it.changeParameters(method, args.newParameters) }
                }
            }
        }

        return RefactorResult(
            success = true,
            affectedFiles = affectedFiles.distinct(),
            changesCount = usageCount + 1,
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

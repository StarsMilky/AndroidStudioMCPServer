package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.models.args.RefactorArgs
import com.codeintel.mcpserver.models.args.RefactorOperation
import com.codeintel.mcpserver.models.results.RefactorResult
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringFactory
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference

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
            val refs = when (psiFile) {
                is PsiJavaFile -> psiFile.classes.flatMap { cls ->
                    ReferencesSearch.search(cls).findAll().toList()
                }
                is KtFile -> psiFile.declarations.flatMap { decl ->
                    ReferencesSearch.search(decl).findAll().toList()
                }
                else -> emptyList()
            }
            affectedFiles.addAll(refs.mapNotNull { r ->
                r.element.containingFile?.virtualFile?.let { ProjectUtils.toRelativePath(project, it) }
            }.distinct())
            Pair(psiFile, affectedFiles)
        }

        val (psiFile, affectedFiles) = readResult

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val targetDir = findOrCreatePackageDir(project, psiFile, targetPackage)
                when (psiFile) {
                    is PsiJavaFile -> {
                        val packageStatement = psiFile.packageStatement
                        val factory = PsiElementFactory.getInstance(project)
                        if (packageStatement != null) {
                            packageStatement.replace(factory.createPackageStatement(targetPackage))
                        }
                    }
                    is KtFile -> {
                        val packageDirective = psiFile.packageDirective
                        val newDirective = KtPsiFactory(project).createPackageDirective(
                            org.jetbrains.kotlin.name.FqName(targetPackage)
                        )
                        if (packageDirective != null) {
                            packageDirective.replace(newDirective)
                        }
                    }
                }
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
            val containingClass = PsiTreeUtil.findChildrenOfAnyType(
                psiFile, PsiClass::class.java, KtClass::class.java
            ).firstOrNull { cls ->
                cls.textRange.startOffset <= startOffset && cls.textRange.endOffset >= endOffset
            }
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
                val document = PsiDocumentManager.getInstance(project).getDocument(info.psiFile) ?: return@runWriteCommandAction
                when (info.psiFile) {
                    is KtFile -> {
                        val factory = KtPsiFactory(project)
                        val newMethod = factory.createFunction("private fun $methodName() {\n${info.selectedText}\n}")
                        val ktClass = info.containingClass as? KtClass
                        if (ktClass != null) {
                            val body = ktClass.body
                            if (body != null) {
                                body.addBefore(newMethod, body.rBrace)
                                body.addBefore(factory.createNewLine(), body.rBrace)
                            }
                        }
                        document.replaceString(info.startOffset, info.endOffset, "$methodName()")
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }
                    is PsiJavaFile -> {
                        val factory = PsiElementFactory.getInstance(project)
                        val methodText = "private void $methodName() {\n${info.selectedText}\n}"
                        val newMethod = factory.createMethodFromText(methodText, info.containingClass)
                        (info.containingClass as? PsiClass)?.add(newMethod)
                        document.replaceString(info.startOffset, info.endOffset, "$methodName();")
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }
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
            val method = when (element) {
                is PsiMethod -> element
                is KtNamedFunction -> element
                else -> throw ToolException(
                    McpErrorCode.INVALID_SCOPE, mapOf("reason" to "Target must be a method/function for change_signature")
                )
            }
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
                when (method) {
                    is KtNamedFunction -> {
                        val factory = KtPsiFactory(project)
                        if (args.newReturnType != null) {
                            val typeRef = method.typeReference
                            if (typeRef != null) {
                                typeRef.replace(factory.createTypeCodeFragment(args.newReturnType, method).getContentElement()!!)
                            } else {
                                method.setTypeReference(factory.createTypeCodeFragment(args.newReturnType, method).getContentElement() as KtTypeReference)
                            }
                        }
                        if (args.newParameters != null) {
                            val paramList = method.valueParameterList
                            if (paramList != null) {
                                val newParams = args.newParameters.joinToString(", ") { p ->
                                    val default = if (p.defaultValue != null) " = ${p.defaultValue}" else ""
                                    "${p.name}: ${p.type}$default"
                                }
                                val newFunction = factory.createFunction("fun temp($newParams) {}")
                                paramList.replace(newFunction.valueParameterList!!)
                            }
                        }
                    }
                    is PsiMethod -> {
                        val factory = PsiElementFactory.getInstance(project)
                        if (args.newReturnType != null) {
                            val newType = factory.createTypeFromText(args.newReturnType, method)
                            val oldReturnType = method.returnTypeElement
                            if (oldReturnType != null) {
                                oldReturnType.replace(factory.createTypeElement(newType))
                            }
                        }
                        if (args.newParameters != null) {
                            val paramList = method.parameterList
                            for (param in paramList.parameters) { param.delete() }
                            for (p in args.newParameters) {
                                val type = factory.createTypeFromText(p.type, method)
                                paramList.add(factory.createParameter(p.name, type))
                            }
                        }
                    }
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

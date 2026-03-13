package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.results.SymbolInfo
import com.androidstudio.mcpserver.models.results.SymbolKind
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import org.jetbrains.kotlin.psi.*

object SymbolResolver {

    fun resolve(project: Project, file: String, line: Int, column: Int): SymbolInfo {
        return PsiUtils.readAction(project) {
            val vf = ProjectUtils.findFile(project, file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val offset = ProjectUtils.lineColumnToOffset(psiFile, line, column)
            val element = psiFile.findElementAt(offset)
                ?: throw ToolException(
                    McpErrorCode.SYMBOL_NOT_FOUND,
                    mapOf("line" to line.toString(), "column" to column.toString())
                )

            val ref = element.parent?.reference ?: element.reference
            val resolved = ref?.resolve()

            if (resolved != null) {
                buildSymbolInfo(project, resolved)
            } else {
                buildSymbolInfoFromElement(project, element)
            }
        }
    }

    private fun buildSymbolInfo(project: Project, element: PsiElement): SymbolInfo {
        val qualifiedType = resolveQualifiedName(element)
        val file = element.containingFile?.virtualFile
        val declarationFile = if (file != null) ProjectUtils.toRelativePath(project, file) else "<unknown>"
        val declarationLine = getLineNumber(element)
        val kind = classifySymbolKind(element)

        return SymbolInfo(
            qualifiedType = qualifiedType,
            declarationFile = declarationFile,
            declarationLine = declarationLine,
            kind = kind
        )
    }

    private fun buildSymbolInfoFromElement(project: Project, element: PsiElement): SymbolInfo {
        val parent = element.parent ?: throw ToolException(
            McpErrorCode.SYMBOL_NOT_FOUND,
            mapOf("reason" to "Cannot resolve symbol at position")
        )
        return buildSymbolInfo(project, parent)
    }

    private fun resolveQualifiedName(element: PsiElement): String = when (element) {
        is PsiClass -> element.qualifiedName ?: element.name ?: "<anonymous>"
        is PsiMethod -> {
            val containingClass = element.containingClass?.qualifiedName ?: ""
            "$containingClass.${element.name}"
        }
        is PsiField -> {
            val containingClass = element.containingClass?.qualifiedName ?: ""
            val type = element.type.canonicalText
            "$containingClass.${element.name}: $type"
        }
        is PsiVariable -> {
            val type = element.type.canonicalText
            "${element.name}: $type"
        }
        is PsiParameter -> {
            val type = element.type.canonicalText
            "${element.name}: $type"
        }
        is KtNamedFunction -> {
            val fqName = element.fqName?.asString()
            fqName ?: element.name ?: "<anonymous>"
        }
        is KtProperty -> {
            val fqName = element.fqName?.asString()
            fqName ?: element.name ?: "<anonymous>"
        }
        is KtClass -> {
            val fqName = element.fqName?.asString()
            fqName ?: element.name ?: "<anonymous>"
        }
        is KtParameter -> element.name ?: "<anonymous>"
        else -> element.text.take(50)
    }

    private fun classifySymbolKind(element: PsiElement): SymbolKind = when (element) {
        is PsiClass, is KtClass, is KtObjectDeclaration -> SymbolKind.CLASS
        is PsiMethod, is KtNamedFunction -> SymbolKind.METHOD
        is PsiField -> SymbolKind.FIELD
        is KtProperty -> SymbolKind.PROPERTY
        is PsiParameter, is KtParameter -> SymbolKind.PARAMETER
        is PsiLocalVariable -> SymbolKind.VARIABLE
        else -> SymbolKind.VARIABLE
    }

    private fun getLineNumber(element: PsiElement): Int {
        val file = element.containingFile ?: return 0
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return 0
        return document.getLineNumber(element.textOffset) + 1
    }
}

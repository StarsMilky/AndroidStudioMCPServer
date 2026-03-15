package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.models.results.SymbolInfo
import com.codeintel.mcpserver.models.results.SymbolKind
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiVariable
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

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

        val packageDirective = findAncestor(element, PsiPackageStatement::class.java, KtPackageDirective::class.java)
        if (packageDirective != null) {
            val packageName = when (packageDirective) {
                is PsiPackageStatement -> packageDirective.packageName
                is KtPackageDirective -> packageDirective.fqName.asString()
                else -> element.text ?: "<unknown>"
            }
            return SymbolInfo(
                qualifiedType = packageName,
                declarationFile = element.containingFile?.virtualFile
                    ?.let { ProjectUtils.toRelativePath(project, it) } ?: "<unknown>",
                declarationLine = getLineNumber(element),
                kind = SymbolKind.PACKAGE
            )
        }

        val importDirective = findAncestor(element, PsiImportStatement::class.java, KtImportDirective::class.java)
        if (importDirective != null) {
            val importFqn = when (importDirective) {
                is PsiImportStatement -> importDirective.qualifiedName ?: element.text ?: "<unknown>"
                is KtImportDirective -> importDirective.importedFqName?.asString() ?: element.text ?: "<unknown>"
                else -> element.text ?: "<unknown>"
            }
            return SymbolInfo(
                qualifiedType = importFqn,
                declarationFile = element.containingFile?.virtualFile
                    ?.let { ProjectUtils.toRelativePath(project, it) } ?: "<unknown>",
                declarationLine = getLineNumber(element),
                kind = SymbolKind.CLASS
            )
        }

        return buildSymbolInfo(project, parent)
    }

    private fun findAncestor(element: PsiElement, vararg types: Class<out PsiElement>): PsiElement? {
        var current: PsiElement? = element
        repeat(10) {
            current = current?.parent ?: return null
            if (types.any { it.isInstance(current) }) return current
        }
        return null
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
        is KtObjectDeclaration -> {
            val fqName = element.fqName?.asString()
            fqName ?: element.name ?: "<anonymous>"
        }
        is KtParameter -> element.name ?: "<anonymous>"
        is PsiPackage -> element.qualifiedName
        else -> {
            try {
                val text = element.text
                if (text != null) text.take(80) else "<unknown>"
            } catch (_: Exception) {
                "<unknown>"
            }
        }
    }

    private fun classifySymbolKind(element: PsiElement): SymbolKind = when (element) {
        is PsiClass -> if (element.isEnum) SymbolKind.ENUM_ENTRY else SymbolKind.CLASS
        is KtClass -> SymbolKind.CLASS
        is KtObjectDeclaration -> SymbolKind.OBJECT
        is PsiMethod, is KtNamedFunction -> SymbolKind.METHOD
        is PsiField -> SymbolKind.FIELD
        is KtProperty -> SymbolKind.PROPERTY
        is PsiParameter, is KtParameter -> SymbolKind.PARAMETER
        is PsiLocalVariable -> SymbolKind.VARIABLE
        is PsiPackage -> SymbolKind.PACKAGE
        else -> SymbolKind.VARIABLE
    }

    private fun getLineNumber(element: PsiElement): Int {
        val file = element.containingFile ?: return 0
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return 0
        return document.getLineNumber(element.textOffset) + 1
    }
}

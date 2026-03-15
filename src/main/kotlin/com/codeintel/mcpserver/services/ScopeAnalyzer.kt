package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.models.args.GetScopeArgs
import com.codeintel.mcpserver.models.args.ScopeFilter
import com.codeintel.mcpserver.models.results.ScopeResult
import com.codeintel.mcpserver.models.results.ScopeSymbol
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiDeclarationStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

object ScopeAnalyzer {

    fun analyze(project: Project, args: GetScopeArgs): ScopeResult {
        return PsiUtils.readAction(project) {
            val vf = ProjectUtils.findFile(project, args.file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val offset = ProjectUtils.lineColumnToOffset(psiFile, args.line, args.column)
            val element = psiFile.findElementAt(offset)
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            val locals = mutableListOf<ScopeSymbol>()
            val members = mutableListOf<ScopeSymbol>()
            val extensions = mutableListOf<ScopeSymbol>()
            val imported = mutableListOf<ScopeSymbol>()

            collectLocalVariables(element, locals)
            collectThisMembers(element, members)
            collectImportedSymbols(psiFile, imported)
            collectExtensionFunctions(psiFile, extensions)

            ScopeResult(
                localVariables = applyFilter(locals, args.filter, "variable"),
                thisMembers = applyFilter(members, args.filter, null),
                extensionFunctions = applyFilter(extensions, args.filter, "method"),
                importedSymbols = applyFilter(imported, args.filter, null)
            )
        }
    }

    private fun collectLocalVariables(element: PsiElement, result: MutableList<ScopeSymbol>) {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is PsiCodeBlock -> {
                    current.statements.filterIsInstance<PsiDeclarationStatement>()
                        .forEach { decl ->
                        decl.declaredElements.filterIsInstance<PsiLocalVariable>()
                            .forEach { variable ->
                            result.add(ScopeSymbol(
                                name = variable.name,
                                type = variable.type.canonicalText,
                                kind = "variable"
                            ))
                        }
                    }
                }
                is KtBlockExpression -> {
                    current.statements.filterIsInstance<KtProperty>().forEach { prop ->
                        if (prop.isLocal) {
                            result.add(ScopeSymbol(
                                name = prop.name ?: return@forEach,
                                type = prop.typeReference?.text ?: "Unknown",
                                kind = "variable"
                            ))
                        }
                    }
                }
                is KtNamedFunction, is PsiMethod -> {
                    collectParameterSymbols(current, result)
                    break
                }
            }
            current = current.parent
        }
    }

    private fun collectParameterSymbols(method: PsiElement, result: MutableList<ScopeSymbol>) {
        when (method) {
            is PsiMethod -> method.parameterList.parameters.forEach { param ->
                result.add(ScopeSymbol(
                    name = param.name,
                    type = param.type.canonicalText,
                    kind = "variable"
                ))
            }
            is KtNamedFunction -> method.valueParameters.forEach { param ->
                result.add(ScopeSymbol(
                    name = param.name ?: return@forEach,
                    type = param.typeReference?.text ?: "Unknown",
                    kind = "variable"
                ))
            }
        }
    }

    private fun collectThisMembers(element: PsiElement, result: MutableList<ScopeSymbol>) {
        val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
        if (containingClass != null) {
            containingClass.fields.forEach { field ->
                result.add(ScopeSymbol(
                    name = field.name,
                    type = field.type.canonicalText,
                    kind = "property"
                ))
            }
            containingClass.methods.forEach { method ->
                result.add(ScopeSymbol(
                    name = method.name,
                    type = method.returnType?.canonicalText ?: "void",
                    kind = "method"
                ))
            }
            return
        }

        val ktClass = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java)
        if (ktClass != null) {
            ktClass.declarations.forEach { decl ->
                when (decl) {
                    is KtProperty -> result.add(ScopeSymbol(
                        name = decl.name ?: return@forEach,
                        type = decl.typeReference?.text ?: "Unknown",
                        kind = "property"
                    ))
                    is KtNamedFunction -> result.add(ScopeSymbol(
                        name = decl.name ?: return@forEach,
                        type = decl.typeReference?.text ?: "Unit",
                        kind = "method"
                    ))
                }
            }
        }
    }

    private fun collectImportedSymbols(psiFile: PsiFile, result: MutableList<ScopeSymbol>) {
        when (psiFile) {
            is PsiJavaFile -> psiFile.importList?.importStatements?.forEach { imp ->
                val name = imp.qualifiedName?.substringAfterLast('.') ?: return@forEach
                result.add(ScopeSymbol(name = name, type = imp.qualifiedName ?: "", kind = "type"))
            }
            is KtFile -> psiFile.importDirectives.forEach { imp ->
                val name = imp.importedFqName?.shortName()?.asString() ?: return@forEach
                result.add(ScopeSymbol(
                    name = name,
                    type = imp.importedFqName?.asString() ?: "",
                    kind = "type"
                ))
            }
        }
    }

    private fun collectExtensionFunctions(psiFile: PsiFile, result: MutableList<ScopeSymbol>) {
        if (psiFile is KtFile) {
            psiFile.declarations.filterIsInstance<KtNamedFunction>().forEach { func ->
                if (func.receiverTypeReference != null) {
                    result.add(ScopeSymbol(
                        name = func.name ?: return@forEach,
                        type = "${func.receiverTypeReference?.text}.() -> " +
                            "${func.typeReference?.text ?: "Unit"}",
                        kind = "method"
                    ))
                }
            }
        }
    }

    private fun applyFilter(
        symbols: List<ScopeSymbol>,
        filter: ScopeFilter,
        targetKind: String?
    ): List<ScopeSymbol> =
        when (filter) {
            ScopeFilter.ALL -> symbols
            ScopeFilter.VARIABLES ->
                symbols.filter { it.kind == "variable" || it.kind == "property" }
            ScopeFilter.METHODS -> symbols.filter { it.kind == "method" }
            ScopeFilter.TYPES -> symbols.filter { it.kind == "type" }
        }
}

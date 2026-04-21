package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.lang.LanguageAdapter
import com.codeintel.mcpserver.models.args.GetScopeArgs
import com.codeintel.mcpserver.models.args.ScopeFilter
import com.codeintel.mcpserver.models.results.ScopeResult
import com.codeintel.mcpserver.models.results.ScopeSymbol
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

object ScopeAnalyzer {

    fun analyze(project: Project, args: GetScopeArgs): ScopeResult {
        return PsiUtils.readAction(project) {
            val vf = ProjectUtils.findFile(project, args.file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val offset = ProjectUtils.lineColumnToOffset(psiFile, args.line, args.column)
            val element = psiFile.findElementAt(offset)
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            val adapters = LanguageAdapter.all(project)

            val locals = mutableListOf<ScopeSymbol>()
            val members = mutableListOf<ScopeSymbol>()
            val extensions = mutableListOf<ScopeSymbol>()
            val imported = mutableListOf<ScopeSymbol>()

            collectLocalVariables(element, adapters, locals)
            collectThisMembers(element, adapters, members)
            collectImportedSymbols(psiFile, adapters, imported)
            collectExtensionFunctions(psiFile, adapters, extensions)

            ScopeResult(
                localVariables = applyFilter(locals, args.filter, "variable"),
                thisMembers = applyFilter(members, args.filter, null),
                extensionFunctions = applyFilter(extensions, args.filter, "method"),
                importedSymbols = applyFilter(imported, args.filter, null),
                nextAction = "💡 Next: resolve_symbol(file='${args.file}', line=<N>, column=<M>) on any symbol of interest to inspect its full type."
            )
        }
    }

    private fun collectLocalVariables(
        element: PsiElement,
        adapters: List<LanguageAdapter>,
        result: MutableList<ScopeSymbol>
    ) {
        var current: PsiElement? = element
        while (current != null) {
            val node: PsiElement = current
            adapters.firstNotNullOfOrNull { it.collectBlockLocals(node) }?.let { result.addAll(it) }
            if (adapters.any { it.isMethodLike(node) }) {
                adapters.firstNotNullOfOrNull { it.collectMethodParameters(node) }
                    ?.let { result.addAll(it) }
                return
            }
            current = current.parent
        }
    }

    private fun collectThisMembers(
        element: PsiElement,
        adapters: List<LanguageAdapter>,
        result: MutableList<ScopeSymbol>
    ) {
        adapters.firstNotNullOfOrNull { it.collectClassMembersAt(element) }
            ?.let { result.addAll(it) }
    }

    private fun collectImportedSymbols(
        psiFile: PsiFile,
        adapters: List<LanguageAdapter>,
        result: MutableList<ScopeSymbol>
    ) {
        adapters.firstNotNullOfOrNull { it.collectImportedSymbols(psiFile) }
            ?.let { result.addAll(it) }
    }

    private fun collectExtensionFunctions(
        psiFile: PsiFile,
        adapters: List<LanguageAdapter>,
        result: MutableList<ScopeSymbol>
    ) {
        adapters.firstNotNullOfOrNull { it.collectExtensionFunctions(psiFile) }
            ?.let { result.addAll(it) }
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

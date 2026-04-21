package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.lang.LanguageAdapter
import com.codeintel.mcpserver.models.args.FindSymbolArgs
import com.codeintel.mcpserver.models.args.ResolveSymbolArgs
import com.codeintel.mcpserver.models.args.SymbolKindFilter
import com.codeintel.mcpserver.models.results.FindSymbolResult
import com.codeintel.mcpserver.models.results.SymbolInfo
import com.codeintel.mcpserver.models.results.SymbolKind
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

object SymbolResolver {

    fun resolve(project: Project, args: ResolveSymbolArgs): SymbolInfo {
        val info = resolveByPosition(project, args.file, args.line, args.column)
        val hint = "💡 Next: find_references(qualified_name='${info.qualifiedType}', mode='USAGES') to see where it's used."
        return info.copy(nextAction = hint)
    }

    fun findByName(project: Project, args: FindSymbolArgs): FindSymbolResult {
        val name = args.name
        val kindFilter = args.kind ?: SymbolKindFilter.ALL
        val limit = args.limit.coerceIn(1, 50)

        return PsiUtils.smartReadAction(project) {
            val scope = resolveScope(project, args.scope)
            val results = mutableListOf<SymbolInfo>()

            if (name.contains('.')) {
                resolveByQualifiedName(project, name, scope, results)
            }

            if (results.isEmpty()) {
                resolveByShortName(project, name, kindFilter, scope, results, limit)
            }

            if (results.isEmpty()) {
                throw ToolException(
                    McpErrorCode.SYMBOL_NOT_FOUND,
                    mapOf("name" to name, "reason" to "No matching symbol found")
                )
            }

            val nextAction = if (results.size == 1) {
                val first = results.first()
                "💡 Next: find_references(qualified_name='${first.qualifiedType}', mode='USAGES') to see where it's used."
            } else {
                "💡 ${results.size} matches. Use find_references with a qualified_name to narrow down."
            }

            FindSymbolResult(
                matches = results.take(limit),
                totalMatches = results.size,
                nextAction = nextAction,
            )
        }
    }

    fun resolveByPosition(project: Project, file: String, line: Int, column: Int): SymbolInfo {
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

    private fun resolveByQualifiedName(
        project: Project,
        fqn: String,
        scope: GlobalSearchScope,
        results: MutableList<SymbolInfo>
    ) {
        val facade = JavaPsiFacade.getInstance(project)
        val classes = facade.findClasses(fqn, scope)
        for (cls in classes) {
            results.add(buildSymbolInfo(project, cls))
        }

        if (results.isEmpty()) {
            val pkg = facade.findPackage(fqn)
            if (pkg != null) {
                results.add(
                    SymbolInfo(
                        qualifiedType = fqn,
                        declarationFile = "<package>",
                        declarationLine = 0,
                        kind = SymbolKind.PACKAGE
                    )
                )
            }
        }

        if (results.isEmpty()) {
            val lastDot = fqn.lastIndexOf('.')
            if (lastDot > 0) {
                val classPart = fqn.substring(0, lastDot)
                val memberName = fqn.substring(lastDot + 1)
                val ownerClasses = facade.findClasses(classPart, scope)
                for (owner in ownerClasses) {
                    for (method in owner.findMethodsByName(memberName, false)) {
                        results.add(buildSymbolInfo(project, method))
                    }
                    val field = owner.findFieldByName(memberName, false)
                    if (field != null) {
                        results.add(buildSymbolInfo(project, field))
                    }
                }
            }
        }
    }

    private fun resolveByShortName(
        project: Project,
        name: String,
        kindFilter: SymbolKindFilter,
        scope: GlobalSearchScope,
        results: MutableList<SymbolInfo>,
        limit: Int
    ) {
        val cache = PsiShortNamesCache.getInstance(project)

        if (kindFilter == SymbolKindFilter.ALL || kindFilter == SymbolKindFilter.CLASS) {
            val classes = cache.getClassesByName(name, scope)
            for (cls in classes) {
                if (results.size >= limit) break
                results.add(buildSymbolInfo(project, cls))
            }
        }

        if (kindFilter == SymbolKindFilter.ALL || kindFilter == SymbolKindFilter.METHOD) {
            val methods = cache.getMethodsByName(name, scope)
            for (method in methods) {
                if (results.size >= limit) break
                results.add(buildSymbolInfo(project, method))
            }
        }

        if (kindFilter == SymbolKindFilter.ALL || kindFilter == SymbolKindFilter.FIELD) {
            val fields = cache.getFieldsByName(name, scope)
            for (field in fields) {
                if (results.size >= limit) break
                results.add(buildSymbolInfo(project, field))
            }
        }
    }

    private fun resolveScope(project: Project, scope: String): GlobalSearchScope {
        return when {
            scope == "project" -> GlobalSearchScope.projectScope(project)
            scope.startsWith("module:") -> {
                val moduleName = scope.removePrefix("module:")
                val module = com.intellij.openapi.module.ModuleManager.getInstance(project)
                    .findModuleByName(moduleName)
                if (module != null) GlobalSearchScope.moduleScope(module)
                else GlobalSearchScope.projectScope(project)
            }
            else -> GlobalSearchScope.projectScope(project)
        }
    }

    private fun buildSymbolInfo(project: Project, element: PsiElement): SymbolInfo {
        val qualifiedType = resolveQualifiedName(element)
        val file = element.containingFile?.virtualFile
        val declarationFile = if (file != null) ProjectUtils.toRelativePath(project, file) else "<unknown>"
        val declarationLine = getLineNumber(element)
        val declarationColumn = getColumnNumber(element)
        val kind = classifySymbolKind(element)

        return SymbolInfo(
            qualifiedType = qualifiedType,
            declarationFile = declarationFile,
            declarationLine = declarationLine,
            kind = kind,
            declarationColumn = declarationColumn,
        )
    }

    private fun buildSymbolInfoFromElement(project: Project, element: PsiElement): SymbolInfo {
        val parent = element.parent ?: throw ToolException(
            McpErrorCode.SYMBOL_NOT_FOUND,
            mapOf("reason" to "Cannot resolve symbol at position")
        )

        // Package declarations: any adapter may claim it
        val pkgHit = LanguageAdapter.all(project)
            .firstNotNullOfOrNull { it.findEnclosingPackageDirective(element) }
        if (pkgHit != null) {
            val (node, packageName) = pkgHit
            return SymbolInfo(
                qualifiedType = packageName,
                declarationFile = node.containingFile?.virtualFile
                    ?.let { ProjectUtils.toRelativePath(project, it) } ?: "<unknown>",
                declarationLine = getLineNumber(node),
                kind = SymbolKind.PACKAGE
            )
        }

        // Import statements: any adapter may claim it
        val importHit = LanguageAdapter.all(project)
            .firstNotNullOfOrNull { it.findEnclosingImportDirective(element) }
        if (importHit != null) {
            val (node, importFqn) = importHit
            return SymbolInfo(
                qualifiedType = importFqn,
                declarationFile = node.containingFile?.virtualFile
                    ?.let { ProjectUtils.toRelativePath(project, it) } ?: "<unknown>",
                declarationLine = getLineNumber(node),
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

    private fun resolveQualifiedName(element: PsiElement): String {
        // Delegate to the owning language adapter first (Kotlin/Java/Python/...).
        LanguageAdapter.all(element.project)
            .firstNotNullOfOrNull { it.qualifiedSignature(element) }
            ?.let { return it }
        // Fallback: plain text / name for elements no adapter recognizes.
        return try {
            val text = element.text
            if (text != null) text.take(80) else "<unknown>"
        } catch (_: Exception) {
            "<unknown>"
        }
    }

    private fun classifySymbolKind(element: PsiElement): SymbolKind {
        LanguageAdapter.all(element.project)
            .firstNotNullOfOrNull { it.classifySymbol(element) }
            ?.let { return it }
        return SymbolKind.VARIABLE
    }

    private fun getLineNumber(element: PsiElement): Int {
        val file = element.containingFile ?: return 0
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return 0
        return document.getLineNumber(element.textOffset) + 1
    }

    private fun getColumnNumber(element: PsiElement): Int {
        val file = element.containingFile ?: return 0
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return 0
        val offset = element.textOffset
        val line = document.getLineNumber(offset)
        return offset - document.getLineStartOffset(line) + 1
    }
}

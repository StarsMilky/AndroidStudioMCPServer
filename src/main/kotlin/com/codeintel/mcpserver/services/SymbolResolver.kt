package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.models.args.ResolveSymbolArgs
import com.codeintel.mcpserver.models.args.SymbolKindFilter
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
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

object SymbolResolver {

    fun resolve(project: Project, args: ResolveSymbolArgs): SymbolInfo {
        return if (args.name != null) {
            resolveByName(project, args)
        } else {
            val file = args.file ?: throw ToolException(
                McpErrorCode.PSI_ERROR,
                mapOf("reason" to "Either 'name' or 'file'+'line'+'column' must be provided")
            )
            val line = args.line ?: throw ToolException(
                McpErrorCode.PSI_ERROR,
                mapOf("reason" to "'line' is required when using file-based resolution")
            )
            val column = args.column ?: throw ToolException(
                McpErrorCode.PSI_ERROR,
                mapOf("reason" to "'column' is required when using file-based resolution")
            )
            resolveByPosition(project, file, line, column)
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

    private fun resolveByName(project: Project, args: ResolveSymbolArgs): SymbolInfo {
        val name = args.name!!
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

            val primary = results.first()
            if (results.size == 1) {
                primary
            } else {
                primary.copy(totalMatches = results.size)
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

    private fun getColumnNumber(element: PsiElement): Int {
        val file = element.containingFile ?: return 0
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return 0
        val offset = element.textOffset
        val line = document.getLineNumber(offset)
        return offset - document.getLineStartOffset(line) + 1
    }
}

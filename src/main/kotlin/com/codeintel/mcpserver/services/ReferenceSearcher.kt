package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.lang.LanguageAdapter
import com.codeintel.mcpserver.models.args.FindReferencesArgs
import com.codeintel.mcpserver.models.args.FindReferencesMode
import com.codeintel.mcpserver.models.results.CallNode
import com.codeintel.mcpserver.models.results.InheritorInfo
import com.codeintel.mcpserver.models.results.ReferenceResult
import com.codeintel.mcpserver.models.results.TypeHierarchyInfo
import com.codeintel.mcpserver.models.results.UsageInfo
import com.codeintel.mcpserver.models.results.UsageType
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

object ReferenceSearcher {

    fun search(project: Project, args: FindReferencesArgs): ReferenceResult {
        val result = if (args.qualifiedName != null) {
            searchByQualifiedName(project, args)
        } else {
            searchByPosition(project, args)
        }
        val hint = when (args.mode) {
            com.codeintel.mcpserver.models.args.FindReferencesMode.USAGES ->
                "💡 Next: for any interesting usage, call resolve_symbol(file, line, column) to inspect its type."
            com.codeintel.mcpserver.models.args.FindReferencesMode.CALLERS,
            com.codeintel.mcpserver.models.args.FindReferencesMode.CALL_HIERARCHY ->
                "💡 Next: use analyze_data_flow(mode='BACKWARD') at a caller to trace where values originate."
            com.codeintel.mcpserver.models.args.FindReferencesMode.CALLEES ->
                "💡 Next: pick an interesting callee and resolve_symbol to inspect its declaration."
            com.codeintel.mcpserver.models.args.FindReferencesMode.TYPE_HIERARCHY ->
                "💡 Next: for any subtype, use find_references(mode='USAGES') to see where that subtype is instantiated."
        }
        return result.copy(nextAction = hint)
    }

    private fun searchByPosition(project: Project, args: FindReferencesArgs): ReferenceResult {
        val file = args.file ?: throw ToolException(
            McpErrorCode.PSI_ERROR,
            mapOf("reason" to "Either 'qualified_name' or 'file'+'line'+'column' must be provided")
        )
        val line = args.line ?: throw ToolException(
            McpErrorCode.PSI_ERROR,
            mapOf("reason" to "'line' is required when using file-based resolution")
        )
        val column = args.column ?: throw ToolException(
            McpErrorCode.PSI_ERROR,
            mapOf("reason" to "'column' is required when using file-based resolution")
        )

        return PsiUtils.readAction(project) {
            val vf = ProjectUtils.findFile(project, file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val offset = ProjectUtils.lineColumnToOffset(psiFile, line, column)
            val element = psiFile.findElementAt(offset)
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            val target = element.parent?.reference?.resolve()
                ?: element.reference?.resolve()
                ?: element.parent
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            dispatchByMode(project, target, args)
        }
    }

    private fun searchByQualifiedName(project: Project, args: FindReferencesArgs): ReferenceResult {
        val fqn = args.qualifiedName!!

        return PsiUtils.smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val facade = com.intellij.psi.JavaPsiFacade.getInstance(project)

            var target: PsiElement? = facade.findClass(fqn, scope)

            if (target == null) {
                val lastDot = fqn.lastIndexOf('.')
                if (lastDot > 0) {
                    val classPart = fqn.substring(0, lastDot)
                    val memberName = fqn.substring(lastDot + 1)
                    val ownerClass = facade.findClass(classPart, scope)
                    if (ownerClass != null) {
                        target = ownerClass.findMethodsByName(memberName, false).firstOrNull()
                            ?: ownerClass.findFieldByName(memberName, false)
                    }
                }
            }

            if (target == null) {
                throw ToolException(
                    McpErrorCode.SYMBOL_NOT_FOUND,
                    mapOf("qualified_name" to fqn, "reason" to "No matching symbol found by qualified name")
                )
            }

            dispatchByMode(project, target, args)
        }
    }

    private fun dispatchByMode(
        project: Project,
        target: PsiElement,
        args: FindReferencesArgs
    ): ReferenceResult {
        return when (args.mode) {
            FindReferencesMode.USAGES -> searchUsages(project, target, args)
            FindReferencesMode.CALLERS, FindReferencesMode.CALL_HIERARCHY ->
                searchCallHierarchy(project, target, args)
            FindReferencesMode.CALLEES -> searchCallees(project, target, args)
            FindReferencesMode.TYPE_HIERARCHY -> searchTypeHierarchy(project, target, args)
        }
    }

    private fun searchUsages(
        project: Project,
        target: PsiElement,
        args: FindReferencesArgs
    ): ReferenceResult {
        val scope = resolveScope(project, args.scope)
        val references = ReferencesSearch.search(target, scope).findAll()
        val total = references.size
        val paginated = references
            .drop(args.offset)
            .take(args.limit)
            .mapNotNull { ref -> buildUsageInfo(project, ref) }

        return ReferenceResult(total = total, usages = paginated)
    }

    private fun buildUsageInfo(project: Project, ref: PsiReference): UsageInfo? {
        val element = ref.element
        val file = element.containingFile?.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project)
            .getDocument(element.containingFile) ?: return null
        val line = document.getLineNumber(element.textOffset) + 1
        val lineStart = document.getLineStartOffset(line - 1)
        val lineEnd = document.getLineEndOffset(line - 1)
        val code = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim()
        val usageType = classifyUsageType(element)

        return UsageInfo(
            file = ProjectUtils.toRelativePath(project, file),
            line = line,
            code = code,
            usageType = usageType
        )
    }

    private fun classifyUsageType(element: PsiElement): UsageType {
        LanguageAdapter.all(element.project)
            .firstNotNullOfOrNull { it.classifyUsage(element) }
            ?.let { return it }
        return UsageType.READ
    }

    private fun searchCallHierarchy(
        project: Project,
        target: PsiElement,
        args: FindReferencesArgs
    ): ReferenceResult {
        val method = findEnclosingMethod(target)
            ?: throw ToolException(
                McpErrorCode.SYMBOL_NOT_FOUND,
                mapOf("reason" to "Target is not a method")
            )

        val rootNode = buildCallTree(project, method, args.depth, mutableSetOf())
        return ReferenceResult(total = countNodes(rootNode), callHierarchy = rootNode)
    }

    private fun buildCallTree(
        project: Project,
        method: PsiElement,
        maxDepth: Int,
        visited: MutableSet<PsiElement>
    ): CallNode {
        if (!visited.add(method)) {
            return buildCallNodeForMethod(project, method, emptyList())
        }

        val children = if (maxDepth > 0) {
            val scope = GlobalSearchScope.projectScope(project)
            val callers = ReferencesSearch.search(method, scope).findAll()
                .mapNotNull { ref ->
                    val caller = findEnclosingMethod(ref.element)
                    if (caller != null && caller !in visited) {
                        buildCallTree(project, caller, maxDepth - 1, visited)
                    } else null
                }
            callers
        } else emptyList()

        return buildCallNodeForMethod(project, method, children)
    }

    private fun buildCallNodeForMethod(
        project: Project,
        method: PsiElement,
        children: List<CallNode>
    ): CallNode {
        val name = LanguageAdapter.all(project)
            .firstNotNullOfOrNull { it.qualifiedSignature(method) }
            ?: method.text.take(30)
        val file = method.containingFile?.virtualFile
        val filePath = if (file != null) ProjectUtils.toRelativePath(project, file)
            else "<unknown>"
        val document = method.containingFile?.let {
            PsiDocumentManager.getInstance(project).getDocument(it)
        }
        val line = document?.getLineNumber(method.textOffset)?.plus(1) ?: 0

        return CallNode(method = name, file = filePath, line = line, children = children)
    }

    private fun countNodes(node: CallNode): Int = 1 + node.children.sumOf { countNodes(it) }

    private fun searchCallees(
        project: Project,
        target: PsiElement,
        args: FindReferencesArgs
    ): ReferenceResult {
        val method = findEnclosingMethod(target)
            ?: throw ToolException(
                McpErrorCode.SYMBOL_NOT_FOUND,
                mapOf("reason" to "Target is not a method")
            )

        val callees = mutableListOf<UsageInfo>()
        val visited = mutableSetOf<String>()

        val body: PsiElement? = LanguageAdapter.all(project)
            .firstNotNullOfOrNull { it.getMethodBody(method) }

        if (body != null) {
            collectCallees(project, body, callees, visited, args.limit)
        }

        return ReferenceResult(
            total = callees.size,
            usages = callees
        )
    }

    private fun collectCallees(
        project: Project, element: PsiElement,
        callees: MutableList<UsageInfo>, visited: MutableSet<String>, limit: Int
    ) {
        if (callees.size >= limit) return

        // Let any adapter resolve the element as a call site.
        val resolved = LanguageAdapter.all(project)
            .firstNotNullOfOrNull { it.resolveCallTarget(element) }
        if (resolved != null) {
            val key = LanguageAdapter.all(project)
                .firstNotNullOfOrNull { it.qualifiedSignature(resolved) }
                ?: (resolved as? PsiNamedElement)?.name
                ?: resolved.text?.take(50)
                ?: ""
            if (key.isNotEmpty() && visited.add(key)) {
                val file = resolved.containingFile?.virtualFile
                val filePath = if (file != null) ProjectUtils.toRelativePath(project, file)
                    else "<unknown>"
                val doc = resolved.containingFile?.let {
                    PsiDocumentManager.getInstance(project).getDocument(it)
                }
                val line = doc?.getLineNumber(resolved.textOffset)?.plus(1) ?: 0
                val code = if (resolved is PsiMethod) {
                    val paramTypes = resolved.parameterList.parameters
                        .joinToString(", ") { it.type.presentableText }
                    "${resolved.containingClass?.name ?: ""}.${resolved.name}($paramTypes)"
                } else {
                    key.substringAfterLast(".").ifEmpty { key }
                }
                callees.add(UsageInfo(
                    file = filePath,
                    line = line,
                    code = code,
                    usageType = UsageType.CALL
                ))
            }
        }

        for (child in element.children) {
            if (callees.size >= limit) break
            collectCallees(project, child, callees, visited, limit)
        }
    }

    private fun searchTypeHierarchy(
        project: Project,
        target: PsiElement,
        args: FindReferencesArgs
    ): ReferenceResult {
        val psiClass = LanguageAdapter.all(project)
            .firstNotNullOfOrNull { it.asPsiClass(target) }
            ?: PsiTreeUtil.getParentOfType(target, PsiClass::class.java)
            ?: throw ToolException(
                McpErrorCode.SYMBOL_NOT_FOUND,
                mapOf("reason" to "Target is not a class")
            )

        val supers = psiClass.supers.mapNotNull { it.qualifiedName }
        val scope = GlobalSearchScope.projectScope(project)
        val inheritors = ClassInheritorsSearch.search(psiClass, scope, true).findAll()
            .map { inheritor ->
                InheritorInfo(
                    className = inheritor.qualifiedName ?: inheritor.name ?: "<anonymous>",
                    file = inheritor.containingFile?.virtualFile?.let {
                        ProjectUtils.toRelativePath(project, it)
                    } ?: "<unknown>"
                )
            }

        return ReferenceResult(
            total = supers.size + inheritors.size,
            typeHierarchy = TypeHierarchyInfo(
                target = psiClass.qualifiedName ?: psiClass.name ?: "<anonymous>",
                supers = supers,
                inheritors = inheritors
            )
        )
    }

    private fun findEnclosingMethod(element: PsiElement): PsiElement? {
        val adapters = LanguageAdapter.all(element.project)
        if (adapters.any { it.isMethodLike(element) }) return element
        return adapters.firstNotNullOfOrNull { it.findEnclosingMethod(element) }
    }

    private fun resolveScope(project: Project, scope: String): GlobalSearchScope = when (scope) {
        "project" -> GlobalSearchScope.projectScope(project)
        "file" -> throw ToolException(
            McpErrorCode.INVALID_SCOPE,
            mapOf("reason" to "File scope requires file context")
        )
        else -> GlobalSearchScope.projectScope(project)
    }
}

package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.args.FindReferencesArgs
import com.androidstudio.mcpserver.models.args.FindReferencesMode
import com.androidstudio.mcpserver.models.results.CallNode
import com.androidstudio.mcpserver.models.results.InheritorInfo
import com.androidstudio.mcpserver.models.results.ReferenceResult
import com.androidstudio.mcpserver.models.results.TypeHierarchyInfo
import com.androidstudio.mcpserver.models.results.UsageInfo
import com.androidstudio.mcpserver.models.results.UsageType
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

object ReferenceSearcher {

    fun search(project: Project, args: FindReferencesArgs): ReferenceResult {
        return PsiUtils.readAction(project) {
            val vf = ProjectUtils.findFile(project, args.file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val offset = ProjectUtils.lineColumnToOffset(psiFile, args.line, args.column)
            val element = psiFile.findElementAt(offset)
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            val target = element.parent?.reference?.resolve()
                ?: element.reference?.resolve()
                ?: element.parent
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            when (args.mode) {
                FindReferencesMode.USAGES -> searchUsages(project, target, args)
                FindReferencesMode.CALLERS, FindReferencesMode.CALL_HIERARCHY ->
                    searchCallHierarchy(project, target, args)
                FindReferencesMode.CALLEES -> searchCallees(project, target, args)
                FindReferencesMode.TYPE_HIERARCHY -> searchTypeHierarchy(project, target, args)
            }
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
        val parent = element.parent
        return when {
            parent is PsiMethodCallExpression -> UsageType.CALL
            parent is KtCallExpression -> UsageType.CALL
            parent is PsiMethod && PsiTreeUtil.isAncestor(parent, element, true) ->
                UsageType.OVERRIDE
            parent is PsiAssignmentExpression && parent.lExpression == element -> UsageType.WRITE
            parent is KtBinaryExpression && parent.left == element &&
                parent.operationReference.text == "=" -> UsageType.WRITE
            else -> UsageType.READ
        }
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
        val name = when (method) {
            is PsiMethod -> "${method.containingClass?.qualifiedName ?: ""}.${method.name}"
            is KtNamedFunction -> method.fqName?.asString() ?: method.name ?: "<anonymous>"
            else -> method.text.take(30)
        }
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
        val method = PsiTreeUtil.getParentOfType(target, PsiMethod::class.java, false)
            ?: PsiTreeUtil.getParentOfType(target, KtNamedFunction::class.java, false)
            ?: throw ToolException(
                McpErrorCode.SYMBOL_NOT_FOUND,
                mapOf("reason" to "Target is not a method")
            )

        val callees = mutableListOf<UsageInfo>()
        val visited = mutableSetOf<String>()

        val body: PsiElement? = when (method) {
            is PsiMethod -> method.body
            is KtNamedFunction -> method.bodyBlockExpression ?: method.bodyExpression
            else -> null
        }

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

        when (element) {
            is PsiMethodCallExpression -> {
                val resolved = element.resolveMethod()
                if (resolved != null) {
                    val key = "${resolved.containingClass?.qualifiedName}.${resolved.name}"
                    if (visited.add(key)) {
                        val file = resolved.containingFile?.virtualFile
                        val filePath = if (file != null) ProjectUtils.toRelativePath(project, file)
                            else "<unknown>"
                        val doc = resolved.containingFile?.let {
                            PsiDocumentManager.getInstance(project).getDocument(it)
                        }
                        val line = doc?.getLineNumber(resolved.textOffset)?.plus(1) ?: 0
                        val paramTypes = resolved.parameterList.parameters
                            .joinToString(", ") { it.type.presentableText }
                        val code = "${resolved.containingClass?.name ?: ""}." +
                            "${resolved.name}($paramTypes)"
                        callees.add(UsageInfo(
                            file = filePath,
                            line = line,
                            code = code,
                            usageType = UsageType.CALL
                        ))
                    }
                }
            }
            is KtCallExpression -> {
                val ref = element.calleeExpression?.reference?.resolve()
                    ?: element.references.firstNotNullOfOrNull { it.resolve() }
                if (ref is PsiNamedElement) {
                    val key = when (ref) {
                        is PsiMethod -> "${ref.containingClass?.qualifiedName}.${ref.name}"
                        is KtNamedFunction -> ref.fqName?.asString() ?: ref.name ?: ""
                        else -> ref.text?.take(50) ?: ""
                    }
                    if (key.isNotEmpty() && visited.add(key)) {
                        val file = ref.containingFile?.virtualFile
                        val filePath = if (file != null) ProjectUtils.toRelativePath(project, file)
                            else "<unknown>"
                        val doc = ref.containingFile?.let {
                            PsiDocumentManager.getInstance(project).getDocument(it)
                        }
                        val line = doc?.getLineNumber(ref.textOffset)?.plus(1) ?: 0
                        callees.add(UsageInfo(
                            file = filePath, line = line,
                            code = key.substringAfterLast(".").ifEmpty { key },
                            usageType = UsageType.CALL
                        ))
                    }
                }
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
        val psiClass = when (target) {
            is PsiClass -> target
            is KtClass -> target.toLightClass()
            else -> PsiTreeUtil.getParentOfType(target, PsiClass::class.java)
        } ?: throw ToolException(
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
        if (element is PsiMethod || element is KtNamedFunction) return element
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            ?: PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
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

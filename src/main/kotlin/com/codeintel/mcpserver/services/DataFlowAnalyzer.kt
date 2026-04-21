package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.models.args.AnalyzeDataFlowArgs
import com.codeintel.mcpserver.models.args.DataFlowMode
import com.codeintel.mcpserver.models.results.DataFlowResult
import com.codeintel.mcpserver.models.results.ExternalAnnotationInfo
import com.codeintel.mcpserver.models.results.FlowPath
import com.codeintel.mcpserver.models.results.FlowStep
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiVariable
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

object DataFlowAnalyzer {
    fun analyze(project: Project, args: AnalyzeDataFlowArgs): DataFlowResult {
        val result = PsiUtils.smartReadAction(project) {
            val vf = ProjectUtils.findFile(project, args.file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val offset = ProjectUtils.lineColumnToOffset(psiFile, args.line, args.column)
            val element = psiFile.findElementAt(offset)
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            when (args.mode) {
                DataFlowMode.NULLABILITY -> analyzeNullability(project, element, psiFile)
                DataFlowMode.FORWARD -> analyzeForward(project, element, psiFile)
                DataFlowMode.BACKWARD -> analyzeBackward(project, element, psiFile)
                DataFlowMode.EXTERNAL_ANNOTATIONS -> analyzeExternalAnnotations(project, element)
            }
        }
        val hint = when (args.mode) {
            DataFlowMode.NULLABILITY ->
                "💡 Next: if nullable, find_references(mode='USAGES') on this symbol to see if any call site handles null."
            DataFlowMode.FORWARD ->
                "💡 Next: for any downstream consumer of interest, resolve_symbol(file,line,col) to learn its type."
            DataFlowMode.BACKWARD ->
                "💡 Next: pick a source and find_references(mode='CALLERS') to see which callers feed this value."
            DataFlowMode.EXTERNAL_ANNOTATIONS ->
                "💡 Next: use resolve_symbol(file,line,col) to confirm the annotated library method signature."
        }
        return result.copy(nextAction = hint)
    }

    private fun analyzeNullability(
        project: Project,
        element: PsiElement,
        psiFile: PsiFile
    ): DataFlowResult {
        val javaVar = element.parent as? PsiVariable
        if (javaVar != null) {
            return analyzeJavaNullability(project, javaVar)
        }

        val ktProp = PsiTreeUtil.getParentOfType(element, KtProperty::class.java)
        if (ktProp != null) {
            return analyzeKotlinPropertyNullability(project, ktProp)
        }

        val ktParam = PsiTreeUtil.getParentOfType(element, KtParameter::class.java)
        if (ktParam != null) {
            val typeText = ktParam.typeReference?.text ?: "Any"
            val isNullable = typeText.endsWith("?")
            return DataFlowResult(
                nullability = if (isNullable) "nullable" else "non_null",
                reason = "Kotlin parameter type: $typeText"
            )
        }

        val ref = element.parent?.reference ?: element.reference
        val resolved = ref?.resolve()
        if (resolved != null) {
            return analyzeResolvedNullability(project, resolved)
        }

        return DataFlowResult(
            nullability = "unknown",
            reason = "Unable to determine nullability: element type is " +
                element.javaClass.simpleName
        )
    }

    private fun analyzeJavaNullability(project: Project, variable: PsiVariable): DataFlowResult {
        val type = variable.type
        val annotations = variable.annotations
        val hasNullable = annotations.any { ann ->
            val fqn = ann.qualifiedName ?: ""
            fqn.contains("Nullable")
        }
        val hasNonNull = annotations.any { ann ->
            val fqn = ann.qualifiedName ?: ""
            fqn.contains("NonNull") || fqn.contains("NotNull") || fqn.contains("Nonnull")
        }

        val nullPaths = mutableListOf<String>()
        val initializer = variable.initializer
        if (initializer != null) {
            if (initializer.text == "null") {
                nullPaths.add("initialized to null at declaration")
            }
        }

        val usages = ReferencesSearch.search(variable).findAll()
        for (ref in usages.take(20)) {
            val assignExpr = PsiTreeUtil.getParentOfType(
                ref.element,
                PsiAssignmentExpression::class.java
            )
            if (assignExpr != null && assignExpr.rExpression?.text == "null") {
                val doc = PsiDocumentManager.getInstance(project)
                    .getDocument(ref.element.containingFile)
                val line = doc?.getLineNumber(ref.element.textOffset)?.plus(1) ?: 0
                nullPaths.add("assigned null at line $line")
            }
        }

        val nullability = when {
            hasNonNull -> "non_null"
            hasNullable -> "nullable"
            type.canonicalText.endsWith("?") -> "nullable"
            nullPaths.isNotEmpty() -> "possibly_null"
            type is PsiPrimitiveType -> "non_null"
            else -> "platform_type"
        }

        return DataFlowResult(
            nullability = nullability,
            reason = "Java type: ${type.canonicalText}, annotations: " +
                annotations.map { it.qualifiedName },
            nullPaths = nullPaths.ifEmpty { null }
        )
    }

    private fun analyzeKotlinPropertyNullability(
        project: Project,
        prop: KtProperty
    ): DataFlowResult {
        val typeText = prop.typeReference?.text
        val initializer = prop.initializer

        val nullPaths = mutableListOf<String>()
        val isExplicitlyNullable = typeText?.endsWith("?") == true

        if (initializer?.text == "null") {
            nullPaths.add("initialized to null at declaration")
        }

        if (prop.isVar) {
            val usages = ReferencesSearch.search(prop).findAll()
            for (ref in usages.take(20)) {
                val parent = ref.element.parent
                if (parent is KtBinaryExpression &&
                    parent.operationToken == org.jetbrains.kotlin.lexer.KtTokens.EQ) {
                    if (parent.right?.text == "null") {
                        val doc = PsiDocumentManager.getInstance(project)
                    .getDocument(ref.element.containingFile)
                        val line = doc?.getLineNumber(ref.element.textOffset)?.plus(1) ?: 0
                        nullPaths.add("assigned null at line $line")
                    }
                }
            }
        }

        val delegateText = prop.delegateExpression?.text
        val isLazy = delegateText?.startsWith("lazy") == true
        val isLateinit = prop.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.LATEINIT_KEYWORD)

        val nullability = when {
            isLateinit -> "lateinit (non_null after init, throws before)"
            isLazy -> "non_null (lazy initialized)"
            isExplicitlyNullable -> "nullable"
            typeText != null -> "non_null"
            initializer != null && initializer.text != "null" -> "non_null (inferred)"
            else -> "unknown"
        }

        val inferredType = typeText
            ?: (if (initializer != null)
                "inferred from: ${initializer.text.take(50)}"
            else
                "unknown")

        return DataFlowResult(
            nullability = nullability,
            reason = "Kotlin property type: $inferredType, var=${prop.isVar}",
            nullPaths = nullPaths.ifEmpty { null }
        )
    }

    private fun analyzeResolvedNullability(
        project: Project,
        resolved: PsiElement
    ): DataFlowResult {
        return when (resolved) {
            is PsiVariable -> analyzeJavaNullability(project, resolved)
            is KtProperty -> analyzeKotlinPropertyNullability(project, resolved)
            is KtParameter -> {
                val typeText = resolved.typeReference?.text ?: "Any"
                DataFlowResult(
                    nullability = if (typeText.endsWith("?")) "nullable" else "non_null",
                    reason = "Kotlin parameter type: $typeText"
                )
            }
            else -> DataFlowResult(
                nullability = "unknown",
                reason = "Cannot determine nullability for ${resolved.javaClass.simpleName}"
            )
        }
    }

    private fun analyzeForward(
        project: Project,
        element: PsiElement,
        psiFile: PsiFile
    ): DataFlowResult {
        val target = element.parent?.reference?.resolve()
            ?: element.reference?.resolve()
            ?: PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
            ?: return DataFlowResult(flowPaths = emptyList())

        val refs = ReferencesSearch.search(target).findAll().take(30)
        val steps = mutableListOf<FlowStep>()

        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        val originLine = doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
        steps.add(FlowStep(
            file = ProjectUtils.toRelativePath(project, psiFile.virtualFile),
            line = originLine,
            code = element.parent?.text?.take(80) ?: element.text.take(80)
        ))

        for (ref in refs) {
            val refFile = ref.element.containingFile ?: continue
            val refVf = refFile.virtualFile ?: continue
            val refDoc = PsiDocumentManager.getInstance(project).getDocument(refFile) ?: continue
            val refLine = refDoc.getLineNumber(ref.element.textOffset) + 1
            val lineStart = refDoc.getLineStartOffset(refLine - 1)
            val lineEnd = refDoc.getLineEndOffset(refLine - 1)
            val lineText = refDoc.text.substring(lineStart, lineEnd).trim()

            steps.add(FlowStep(
                file = ProjectUtils.toRelativePath(project, refVf),
                line = refLine,
                code = lineText.take(120)
            ))
        }

        return DataFlowResult(flowPaths = listOf(FlowPath(steps = steps)))
    }

    private fun analyzeBackward(
        project: Project,
        element: PsiElement,
        psiFile: PsiFile
    ): DataFlowResult {
        val target = element.parent?.reference?.resolve()
            ?: element.reference?.resolve()
            ?: PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
            ?: return DataFlowResult(flowPaths = emptyList())

        val steps = mutableListOf<FlowStep>()

        when (target) {
            is PsiVariable -> {
                val initializer = target.initializer
                if (initializer != null) {
                    val file = target.containingFile?.virtualFile
                    if (file != null) {
                        val doc = PsiDocumentManager.getInstance(project)
                            .getDocument(target.containingFile)
                        val line = doc?.getLineNumber(initializer.textOffset)?.plus(1) ?: 0
                        steps.add(FlowStep(
                            file = ProjectUtils.toRelativePath(project, file),
                            line = line,
                            code = "initializer: ${initializer.text.take(100)}"
                        ))
                    }
                }
            }
            is KtProperty -> {
                val initializer = target.initializer
                if (initializer != null) {
                    val file = target.containingFile?.virtualFile
                    if (file != null) {
                        val doc = PsiDocumentManager.getInstance(project)
                            .getDocument(target.containingFile)
                        val line = doc?.getLineNumber(initializer.textOffset)?.plus(1) ?: 0
                        steps.add(FlowStep(
                            file = ProjectUtils.toRelativePath(project, file),
                            line = line,
                            code = "initializer: ${initializer.text.take(100)}"
                        ))
                    }
                }
            }
            is KtParameter -> {
                val fn = PsiTreeUtil.getParentOfType(target, KtNamedFunction::class.java)
                if (fn != null) {
                    val callers = ReferencesSearch.search(fn).findAll().take(10)
                    for (caller in callers) {
                        val callerFile = caller.element.containingFile?.virtualFile ?: continue
                        val callerDoc = PsiDocumentManager.getInstance(project)
                            .getDocument(caller.element.containingFile) ?: continue
                        val callerLine = callerDoc.getLineNumber(caller.element.textOffset) + 1
                        val lineStart = callerDoc.getLineStartOffset(callerLine - 1)
                        val lineEnd = callerDoc.getLineEndOffset(callerLine - 1)
                        val lineText = callerDoc.text.substring(lineStart, lineEnd).trim()
                        steps.add(FlowStep(
                            file = ProjectUtils.toRelativePath(project, callerFile),
                            line = callerLine,
                            code = "caller: ${lineText.take(120)}"
                        ))
                    }
                }
            }
        }

        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        val line = doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
        steps.add(FlowStep(
            file = ProjectUtils.toRelativePath(project, psiFile.virtualFile),
            line = line,
            code = "target: ${element.parent?.text?.take(80) ?: element.text.take(80)}"
        ))

        return DataFlowResult(flowPaths = listOf(FlowPath(steps = steps)))
    }

    private fun analyzeExternalAnnotations(project: Project, element: PsiElement): DataFlowResult {
        val resolved = element.parent?.reference?.resolve()
            ?: element.reference?.resolve()
            ?: PsiTreeUtil.getParentOfType(element, PsiNamedElement::class.java)
            ?: return DataFlowResult(
                annotations = emptyList(),
                reason = "Cannot resolve target element"
            )

        val annotations = mutableListOf<ExternalAnnotationInfo>()
        val extAnnoManager = com.intellij.codeInsight.ExternalAnnotationsManager
            .getInstance(project)

        when (resolved) {
            is PsiModifierListOwner -> {
                val targetLabel = when (resolved) {
                    is PsiMethod -> "method:${resolved.name}"
                    is PsiParameter -> "parameter:${resolved.name}"
                    is PsiField -> "field:${resolved.name}"
                    else -> "element"
                }

                try {
                    val externalAnnos: Array<PsiAnnotation> =
                        extAnnoManager.findExternalAnnotations(resolved) ?: emptyArray()
                    for (anno in externalAnnos) {
                        val fqn = anno.qualifiedName ?: continue
                        annotations.add(ExternalAnnotationInfo(
                            annotation = "@${fqn.substringAfterLast(".")}",
                            target = targetLabel
                        ))
                    }
                } catch (_: Exception) {}

                if (resolved is PsiMethod) {
                    for (param in resolved.parameterList.parameters) {
                        try {
                            val paramAnnos: Array<PsiAnnotation> =
                                extAnnoManager.findExternalAnnotations(param) ?: emptyArray()
                            for (anno in paramAnnos) {
                                val fqn = anno.qualifiedName ?: continue
                                annotations.add(ExternalAnnotationInfo(
                                    annotation = "@${fqn.substringAfterLast(".")}",
                                    target = "parameter:${param.name}"
                                ))
                            }
                        } catch (_: Exception) {}
                    }
                }

                val ownAnnos = resolved.annotations.filter { ann ->
                    val fqn = ann.qualifiedName ?: ""
                    fqn.contains("Nullable") || fqn.contains("NonNull") ||
                        fqn.contains("NotNull") ||
                        fqn.contains("UiThread") || fqn.contains("WorkerThread") ||
                        fqn.contains("MainThread") ||
                        fqn.contains("IntRange") || fqn.contains("FloatRange") ||
                        fqn.contains("Size")
                }
                for (anno in ownAnnos) {
                    val fqn = anno.qualifiedName ?: continue
                    annotations.add(ExternalAnnotationInfo(
                        annotation = "@${fqn.substringAfterLast(".")}",
                        target = targetLabel,
                        source = "source code annotation"
                    ))
                }
            }
        }

        return DataFlowResult(
            annotations = annotations,
            reason = if (annotations.isEmpty())
                "No external annotations found for this element"
            else
                "${annotations.size} annotation(s) found"
        )
    }
}

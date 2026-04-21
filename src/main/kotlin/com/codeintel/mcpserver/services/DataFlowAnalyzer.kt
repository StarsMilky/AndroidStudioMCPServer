package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.lang.LanguageAdapter
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
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiParameter
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil

object DataFlowAnalyzer {
    fun analyze(project: Project, args: AnalyzeDataFlowArgs): DataFlowResult {
        val result = PsiUtils.smartReadAction(project) {
            val vf = ProjectUtils.findFile(project, args.file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val offset = ProjectUtils.lineColumnToOffset(psiFile, args.line, args.column)
            val element = psiFile.findElementAt(offset)
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            when (args.mode) {
                DataFlowMode.NULLABILITY -> analyzeNullability(project, element)
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

    private fun analyzeNullability(project: Project, element: PsiElement): DataFlowResult {
        val adapters = LanguageAdapter.all(project)

        // Adapter-owned cursor-based nullability (walks up to find declaring var/param).
        for (adapter in adapters) {
            val owned = adapter.analyzeNullabilityFromCursor(project, element)
            if (owned != null) return owned
        }

        // Fallback: resolve reference, then ask adapters about the resolved target.
        val ref = element.parent?.reference ?: element.reference
        val resolved = ref?.resolve()
        if (resolved != null) {
            for (adapter in adapters) {
                val owned = adapter.analyzeNullabilityOfResolved(project, resolved)
                if (owned != null) return owned
            }
        }

        return DataFlowResult(
            nullability = "unknown",
            reason = "Unable to determine nullability: element type is " +
                element.javaClass.simpleName
        )
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

        for (adapter in LanguageAdapter.all(project)) {
            val adapterSteps = adapter.backwardFlowSteps(project, target)
            if (adapterSteps != null) {
                steps.addAll(adapterSteps)
                break
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

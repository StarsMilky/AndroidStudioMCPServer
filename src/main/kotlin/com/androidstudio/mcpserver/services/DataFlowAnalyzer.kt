package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.args.AnalyzeDataFlowArgs
import com.androidstudio.mcpserver.models.args.DataFlowMode
import com.androidstudio.mcpserver.models.results.DataFlowResult
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.*

object DataFlowAnalyzer {
    fun analyze(project: Project, args: AnalyzeDataFlowArgs): DataFlowResult {
        return PsiUtils.smartReadAction(project) {
            val vf = ProjectUtils.findFile(project, args.file)
            val psiFile = ProjectUtils.getPsiFile(project, vf)
            val offset = ProjectUtils.lineColumnToOffset(psiFile, args.line, args.column)
            val element = psiFile.findElementAt(offset)
                ?: throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND)

            when (args.mode) {
                DataFlowMode.NULLABILITY -> analyzeNullability(element)
                DataFlowMode.FORWARD -> DataFlowResult(flowPaths = emptyList())
                DataFlowMode.BACKWARD -> DataFlowResult(flowPaths = emptyList())
            }
        }
    }

    private fun analyzeNullability(element: PsiElement): DataFlowResult {
        val variable = element.parent as? PsiVariable
        if (variable != null) {
            val type = variable.type
            val isNullable = type.canonicalText.endsWith("?")
            return DataFlowResult(
                nullability = if (isNullable) "possibly_null" else "definitely_not_null",
                reason = "Based on type declaration: ${type.canonicalText}"
            )
        }
        return DataFlowResult(nullability = "possibly_null", reason = "Unable to determine nullability from context")
    }
}

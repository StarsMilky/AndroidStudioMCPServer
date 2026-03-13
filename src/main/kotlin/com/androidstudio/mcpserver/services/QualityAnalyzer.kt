package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.models.args.AnalyzeQualityArgs
import com.androidstudio.mcpserver.models.args.QualityMode
import com.androidstudio.mcpserver.models.results.QualityReport
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project

object QualityAnalyzer {
    fun analyze(project: Project, args: AnalyzeQualityArgs): QualityReport {
        return PsiUtils.smartReadAction(project) {
            when (args.mode) {
                QualityMode.COMPLEXITY -> QualityReport(mode = "complexity", issues = emptyList())
                QualityMode.DEAD_CODE -> QualityReport(mode = "dead_code", issues = emptyList())
                QualityMode.CLONES -> QualityReport(mode = "clones", issues = emptyList())
                QualityMode.PATTERNS -> QualityReport(mode = "patterns", issues = emptyList())
                QualityMode.ERROR_HANDLING -> QualityReport(mode = "error_handling", issues = emptyList())
            }
        }
    }
}

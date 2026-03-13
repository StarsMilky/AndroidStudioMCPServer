package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.AnalyzeQualityArgs
import com.androidstudio.mcpserver.models.results.QualityReport
import com.androidstudio.mcpserver.services.QualityAnalyzer
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class AnalyzeQualityTool : AbstractMcpTool<AnalyzeQualityArgs>(AnalyzeQualityArgs.serializer()) {
    override val name: String = "analyze_quality"
    override val description: String = "代码质量分析——复杂度热点、死代码、代码克隆、设计模式/反模式、异常处理"

    override fun handle(project: Project, args: AnalyzeQualityArgs): Response {
        return try {
            val result = QualityAnalyzer.analyze(project, args)
            Response(ResponseFormatter.format(result, QualityReport.serializer(), SizePolicy.ANALYZE_QUALITY))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

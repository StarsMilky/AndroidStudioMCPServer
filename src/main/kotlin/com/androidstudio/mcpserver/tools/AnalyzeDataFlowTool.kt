package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.AnalyzeDataFlowArgs
import com.androidstudio.mcpserver.models.results.DataFlowResult
import com.androidstudio.mcpserver.services.DataFlowAnalyzer
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class AnalyzeDataFlowTool : AbstractMcpTool<AnalyzeDataFlowArgs>(AnalyzeDataFlowArgs.serializer()) {
    override val name: String = "analyze_data_flow"
    override val description: String = "数据流分析——空安全推理、值传播追踪、外部注解查询"

    override fun handle(project: Project, args: AnalyzeDataFlowArgs): Response {
        return try {
            val result = DataFlowAnalyzer.analyze(project, args)
            Response(ResponseFormatter.format(result, DataFlowResult.serializer(), SizePolicy.ANALYZE_DATA_FLOW))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

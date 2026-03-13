package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.QueryProjectArgs
import com.androidstudio.mcpserver.models.args.QueryProjectMode
import com.androidstudio.mcpserver.models.results.ProjectOverview
import com.androidstudio.mcpserver.services.ProjectAnalyzer
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class QueryProjectTool : AbstractMcpTool<QueryProjectArgs>(QueryProjectArgs.serializer()) {
    override val name: String = "query_project"
    override val description: String = "项目全景图、依赖关系图、变更影响分析、API 表面分析、Build Variant 感知"

    override fun handle(project: Project, args: QueryProjectArgs): Response {
        return try {
            val result = ProjectAnalyzer.analyze(project, args)
            val policy = if (args.mode == QueryProjectMode.OVERVIEW) {
                SizePolicy.QUERY_PROJECT_PANORAMA
            } else {
                SizePolicy.QUERY_PROJECT_DETAIL
            }
            Response(ResponseFormatter.format(result, ProjectOverview.serializer(), policy))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.QueryFrameworkArgs
import com.androidstudio.mcpserver.models.results.FrameworkViewResult
import com.androidstudio.mcpserver.services.FrameworkAnalyzer
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class QueryFrameworkTool : AbstractMcpTool<QueryFrameworkArgs>(QueryFrameworkArgs.serializer()) {
    override val name: String = "query_framework"
    override val description: String = "基于注解扫描生成 Room/Retrofit/Hilt/Compose/Navigation 的结构化框架视图"

    override fun handle(project: Project, args: QueryFrameworkArgs): Response {
        return try {
            val result = FrameworkAnalyzer.analyze(project, args)
            val policy = if (args.detailTarget != null) {
                SizePolicy.QUERY_FRAMEWORK_DETAIL
            } else {
                SizePolicy.QUERY_FRAMEWORK_LIST
            }
            Response(ResponseFormatter.format(result, FrameworkViewResult.serializer(), policy))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

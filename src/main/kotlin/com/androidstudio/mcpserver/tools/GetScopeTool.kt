package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.GetScopeArgs
import com.androidstudio.mcpserver.models.results.ScopeResult
import com.androidstudio.mcpserver.services.ScopeAnalyzer
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.ide.mcp.Response
import com.intellij.openapi.project.Project

class GetScopeTool : AbstractMcpTool<GetScopeArgs>(GetScopeArgs.serializer()) {
    override val name: String = "get_scope"
    override val description: String = "获取指定代码位置的所有可用符号（局部变量、成员、扩展函数、导入符号）"

    override fun handle(project: Project, args: GetScopeArgs): Response {
        return try {
            val result = ScopeAnalyzer.analyze(project, args)
            Response(ResponseFormatter.format(result, ScopeResult.serializer(), SizePolicy.GET_SCOPE))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

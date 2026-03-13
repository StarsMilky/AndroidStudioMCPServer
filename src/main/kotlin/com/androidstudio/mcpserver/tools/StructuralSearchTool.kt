package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.StructuralSearchArgs
import com.androidstudio.mcpserver.models.results.SearchMatchResult
import com.androidstudio.mcpserver.services.StructuralSearcher
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class StructuralSearchTool : AbstractMcpTool<StructuralSearchArgs>(StructuralSearchArgs.serializer()) {
    override val name: String = "structural_search"
    override val description: String = "基于 AST 的代码模式搜索（IntelliJ SSR 语法），超越文本搜索"

    override fun handle(project: Project, args: StructuralSearchArgs): Response {
        return try {
            val result = StructuralSearcher.search(project, args)
            Response(ResponseFormatter.format(result, SearchMatchResult.serializer(), SizePolicy.STRUCTURAL_SEARCH))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

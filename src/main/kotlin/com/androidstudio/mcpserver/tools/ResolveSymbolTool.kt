package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.ResolveSymbolArgs
import com.androidstudio.mcpserver.models.results.SymbolInfo
import com.androidstudio.mcpserver.services.SymbolResolver
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.ide.mcp.Response
import com.intellij.openapi.project.Project

class ResolveSymbolTool : AbstractMcpTool<ResolveSymbolArgs>(ResolveSymbolArgs.serializer()) {
    override val name: String = "resolve_symbol"
    override val description: String = "精确解析代码中任意位置的符号：返回全限定类型、声明位置、符号类别"

    override fun handle(project: Project, args: ResolveSymbolArgs): Response {
        return try {
            val result = SymbolResolver.resolve(project, args.file, args.line, args.column)
            Response(ResponseFormatter.format(result, SymbolInfo.serializer(), SizePolicy.RESOLVE_SYMBOL))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

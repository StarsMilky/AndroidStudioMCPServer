package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.FindReferencesArgs
import com.androidstudio.mcpserver.models.results.ReferenceResult
import com.androidstudio.mcpserver.services.ReferenceSearcher
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.ide.mcp.Response
import com.intellij.openapi.project.Project

class FindReferencesTool : AbstractMcpTool<FindReferencesArgs>(FindReferencesArgs.serializer()) {
    override val name: String = "find_references"
    override val description: String = "语义级查找符号的引用/调用层级/类型层级（零误报，区分引用类型）"

    override fun handle(project: Project, args: FindReferencesArgs): Response {
        return try {
            val result = ReferenceSearcher.search(project, args)
            Response(ResponseFormatter.format(result, ReferenceResult.serializer(), SizePolicy.FIND_REFERENCES))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

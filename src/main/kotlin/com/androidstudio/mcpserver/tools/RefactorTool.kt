package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.RefactorArgs
import com.androidstudio.mcpserver.models.results.RefactorResult
import com.androidstudio.mcpserver.services.RefactorExecutor
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class RefactorTool : AbstractMcpTool<RefactorArgs>(RefactorArgs.serializer()) {
    override val name: String = "refactor"
    override val description: String = "语义级安全重构（rename/move/extract/safe_delete/change_signature），跨 Java/Kotlin/XML/Manifest"

    override fun handle(project: Project, args: RefactorArgs): Response {
        return try {
            val result = RefactorExecutor.execute(project, args)
            Response(ResponseFormatter.format(result, RefactorResult.serializer(), SizePolicy.REFACTOR))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

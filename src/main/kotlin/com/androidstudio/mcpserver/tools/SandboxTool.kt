package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.SandboxArgs
import com.androidstudio.mcpserver.models.results.SandboxResult
import com.androidstudio.mcpserver.services.SandboxExecutor
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class SandboxTool : AbstractMcpTool<SandboxArgs>(SandboxArgs.serializer()) {
    override val name: String = "sandbox"
    override val description: String = "安全沙盒——运行临时代码、反编译、渲染布局、Java→Kotlin 转换、批量修复"

    override fun handle(project: Project, args: SandboxArgs): Response {
        return try {
            val result = SandboxExecutor.execute(project, args)
            Response(ResponseFormatter.format(result, SandboxResult.serializer(), SizePolicy.SANDBOX))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.CheckpointArgs
import com.androidstudio.mcpserver.models.results.CheckpointResult
import com.androidstudio.mcpserver.services.CheckpointManager
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.ide.mcp.Response
import com.intellij.openapi.project.Project

class CheckpointTool : AbstractMcpTool<CheckpointArgs>(CheckpointArgs.serializer()) {
    override val name: String = "checkpoint"
    override val description: String = "Local History 操作——创建检查点、查看历史、回滚、对比差异"

    override fun handle(project: Project, args: CheckpointArgs): Response {
        return try {
            val result = CheckpointManager.execute(
                project, args.operation, args.label, args.file, args.targetLabel
            )
            Response(ResponseFormatter.format(result, CheckpointResult.serializer(), SizePolicy.CHECKPOINT))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

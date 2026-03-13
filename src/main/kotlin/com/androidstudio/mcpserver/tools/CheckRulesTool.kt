package com.androidstudio.mcpserver.tools

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.CheckRulesArgs
import com.androidstudio.mcpserver.models.results.RuleCheckResult
import com.androidstudio.mcpserver.services.RuleChecker
import com.intellij.openapi.project.Project
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class CheckRulesTool : AbstractMcpTool<CheckRulesArgs>(CheckRulesArgs.serializer()) {
    override val name: String = "check_rules"
    override val description: String = "验证代码是否遵守自定义架构规则（层级违规、非法依赖）"

    override fun handle(project: Project, args: CheckRulesArgs): Response {
        return try {
            val result = RuleChecker.check(project, args)
            Response(ResponseFormatter.format(result, RuleCheckResult.serializer(), SizePolicy.CHECK_RULES))
        } catch (e: ToolException) {
            Response(error = e.toErrorJson())
        }
    }
}

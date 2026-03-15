package com.androidstudio.mcpserver.server

import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.formatting.ResponseFormatter
import com.androidstudio.mcpserver.formatting.SizePolicy
import com.androidstudio.mcpserver.models.args.AnalyzeDataFlowArgs
import com.androidstudio.mcpserver.models.args.AnalyzeQualityArgs
import com.androidstudio.mcpserver.models.args.CheckpointArgs
import com.androidstudio.mcpserver.models.args.CheckRulesArgs
import com.androidstudio.mcpserver.models.args.FindReferencesArgs
import com.androidstudio.mcpserver.models.args.GetScopeArgs
import com.androidstudio.mcpserver.models.args.QueryProjectArgs
import com.androidstudio.mcpserver.models.args.QueryProjectMode
import com.androidstudio.mcpserver.models.args.QueryFrameworkArgs
import com.androidstudio.mcpserver.models.args.RefactorArgs
import com.androidstudio.mcpserver.models.args.ResolveSymbolArgs
import com.androidstudio.mcpserver.models.args.SandboxArgs
import com.androidstudio.mcpserver.models.args.StructuralSearchArgs
import com.androidstudio.mcpserver.models.results.CheckpointResult
import com.androidstudio.mcpserver.models.results.DataFlowResult
import com.androidstudio.mcpserver.models.results.FrameworkViewResult
import com.androidstudio.mcpserver.models.results.ProjectOverview
import com.androidstudio.mcpserver.models.results.QualityReport
import com.androidstudio.mcpserver.models.results.ReferenceResult
import com.androidstudio.mcpserver.models.results.RefactorResult
import com.androidstudio.mcpserver.models.results.RuleCheckResult
import com.androidstudio.mcpserver.models.results.SandboxResult
import com.androidstudio.mcpserver.models.results.ScopeResult
import com.androidstudio.mcpserver.models.results.SearchMatchResult
import com.androidstudio.mcpserver.models.results.SymbolInfo
import com.androidstudio.mcpserver.services.CheckpointManager
import com.androidstudio.mcpserver.services.DataFlowAnalyzer
import com.androidstudio.mcpserver.services.FrameworkAnalyzer
import com.androidstudio.mcpserver.services.ProjectAnalyzer
import com.androidstudio.mcpserver.services.QualityAnalyzer
import com.androidstudio.mcpserver.services.RefactorExecutor
import com.androidstudio.mcpserver.services.ReferenceSearcher
import com.androidstudio.mcpserver.services.RuleChecker
import com.androidstudio.mcpserver.services.SandboxExecutor
import com.androidstudio.mcpserver.services.ScopeAnalyzer
import com.androidstudio.mcpserver.services.StructuralSearcher
import com.androidstudio.mcpserver.services.SymbolResolver
import com.androidstudio.mcpserver.util.McpJson
import com.androidstudio.mcpserver.util.ProjectResolver
import com.androidstudio.mcpserver.util.PsiUtils
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject

object ToolRegistrar {

    fun registerAll(server: Server) {
        registerResolveSymbol(server)
        registerFindReferences(server)
        registerGetScope(server)
        registerCheckpoint(server)
        registerRefactor(server)
        registerQueryProject(server)
        registerQueryFramework(server)
        registerAnalyzeDataFlow(server)
        registerCheckRules(server)
        registerStructuralSearch(server)
        registerAnalyzeQuality(server)
        registerSandbox(server)
    }

    private inline fun <reified A, reified R> handleTool(
        toolName: String,
        arguments: JsonObject?,
        argsDeserializer: kotlinx.serialization.KSerializer<A>,
        resultSerializer: kotlinx.serialization.KSerializer<R>,
        policy: SizePolicy,
        action: (com.intellij.openapi.project.Project, A) -> R
    ): CallToolResult {
        ToolMetricsService.markStart(toolName)
        val startTime = System.currentTimeMillis()
        return try {
            val project = ProjectResolver.resolve(arguments)
            PsiUtils.refreshForExternalChanges(project)
            val args = McpJson.decodeFromJsonElement(argsDeserializer, arguments ?: JsonObject(emptyMap()))
            val result = action(project, args)
            val json = ResponseFormatter.format(result, resultSerializer, policy)
            ToolMetricsService.markComplete(toolName, json, System.currentTimeMillis() - startTime, false)
            CallToolResult(content = listOf(TextContent(json)))
        } catch (e: ToolException) {
            val errorJson = e.toErrorJson()
            ToolMetricsService.markComplete(toolName, errorJson, System.currentTimeMillis() - startTime, true)
            CallToolResult(content = listOf(TextContent(errorJson)), isError = true)
        }
    }

    private fun registerResolveSymbol(server: Server) {
        server.addTool(
            name = "resolve_symbol",
            description = "精确解析代码中任意位置的符号：返回全限定类型、声明位置、符号类别",
            inputSchema = ToolSchemas.resolveSymbol
        ) { request ->
            handleTool(
                "resolve_symbol",
                request.arguments,
                ResolveSymbolArgs.serializer(),
                SymbolInfo.serializer(),
                SizePolicy.RESOLVE_SYMBOL
            ) { project, args ->
                SymbolResolver.resolve(project, args.file, args.line, args.column)
            }
        }
    }

    private fun registerFindReferences(server: Server) {
        server.addTool(
            name = "find_references",
            description = "语义级查找符号的引用/调用层级/类型层级（零误报，区分引用类型）",
            inputSchema = ToolSchemas.findReferences
        ) { request ->
            handleTool(
                "find_references",
                request.arguments,
                FindReferencesArgs.serializer(),
                ReferenceResult.serializer(),
                SizePolicy.FIND_REFERENCES
            ) { project, args ->
                ReferenceSearcher.search(project, args)
            }
        }
    }

    private fun registerGetScope(server: Server) {
        server.addTool(
            name = "get_scope",
            description = "获取指定代码位置的所有可用符号（局部变量、成员、扩展函数、导入符号）",
            inputSchema = ToolSchemas.getScope
        ) { request ->
            handleTool(
                "get_scope",
                request.arguments,
                GetScopeArgs.serializer(),
                ScopeResult.serializer(),
                SizePolicy.GET_SCOPE
            ) { project, args ->
                ScopeAnalyzer.analyze(project, args)
            }
        }
    }

    private fun registerCheckpoint(server: Server) {
        server.addTool(
            name = "checkpoint",
            description = "Local History 操作——创建检查点、查看历史、回滚、对比差异",
            inputSchema = ToolSchemas.checkpoint
        ) { request ->
            handleTool(
                "checkpoint",
                request.arguments,
                CheckpointArgs.serializer(),
                CheckpointResult.serializer(),
                SizePolicy.CHECKPOINT
            ) { project, args ->
                CheckpointManager.execute(project, args.operation, args.label, args.file, args.targetLabel)
            }
        }
    }

    private fun registerRefactor(server: Server) {
        server.addTool(
            name = "refactor",
            description = "语义级安全重构（rename/move/extract/safe_delete/change_signature），跨 Java/Kotlin/XML/Manifest",
            inputSchema = ToolSchemas.refactor
        ) { request ->
            handleTool(
                "refactor",
                request.arguments,
                RefactorArgs.serializer(),
                RefactorResult.serializer(),
                SizePolicy.REFACTOR
            ) { project, args ->
                RefactorExecutor.execute(project, args)
            }
        }
    }

    private fun registerQueryProject(server: Server) {
        server.addTool(
            name = "query_project",
            description = "项目全景图、依赖关系图、变更影响分析、API 表面分析、Build Variant 感知",
            inputSchema = ToolSchemas.queryProject
        ) { request ->
            val toolName = "query_project"
            ToolMetricsService.markStart(toolName)
            val startTime = System.currentTimeMillis()
            try {
                val project = ProjectResolver.resolve(request.arguments)
                PsiUtils.refreshForExternalChanges(project)
                val args = McpJson.decodeFromJsonElement(
                    QueryProjectArgs.serializer(),
                    request.arguments ?: JsonObject(emptyMap())
                )
                val result = ProjectAnalyzer.analyze(project, args)
                val policy = if (args.mode == QueryProjectMode.OVERVIEW) {
                    SizePolicy.QUERY_PROJECT_PANORAMA
                } else {
                    SizePolicy.QUERY_PROJECT_DETAIL
                }
                val json = ResponseFormatter.format(result, ProjectOverview.serializer(), policy)
                ToolMetricsService.markComplete(toolName, json, System.currentTimeMillis() - startTime, false)
                CallToolResult(content = listOf(TextContent(json)))
            } catch (e: ToolException) {
                val errorJson = e.toErrorJson()
                ToolMetricsService.markComplete(toolName, errorJson, System.currentTimeMillis() - startTime, true)
                CallToolResult(content = listOf(TextContent(errorJson)), isError = true)
            }
        }
    }

    private fun registerQueryFramework(server: Server) {
        server.addTool(
            name = "query_framework",
            description = "基于注解扫描生成 Room/Retrofit/Hilt/Compose/Navigation 的结构化框架视图",
            inputSchema = ToolSchemas.queryFramework
        ) { request ->
            val toolName = "query_framework"
            ToolMetricsService.markStart(toolName)
            val startTime = System.currentTimeMillis()
            try {
                val project = ProjectResolver.resolve(request.arguments)
                PsiUtils.refreshForExternalChanges(project)
                val args = McpJson.decodeFromJsonElement(
                    QueryFrameworkArgs.serializer(),
                    request.arguments ?: JsonObject(emptyMap())
                )
                val result = FrameworkAnalyzer.analyze(project, args)
                val policy = if (args.detailTarget != null) {
                    SizePolicy.QUERY_FRAMEWORK_DETAIL
                } else {
                    SizePolicy.QUERY_FRAMEWORK_LIST
                }
                val json = ResponseFormatter.format(result, FrameworkViewResult.serializer(), policy)
                ToolMetricsService.markComplete(toolName, json, System.currentTimeMillis() - startTime, false)
                CallToolResult(content = listOf(TextContent(json)))
            } catch (e: ToolException) {
                val errorJson = e.toErrorJson()
                ToolMetricsService.markComplete(toolName, errorJson, System.currentTimeMillis() - startTime, true)
                CallToolResult(content = listOf(TextContent(errorJson)), isError = true)
            }
        }
    }

    private fun registerAnalyzeDataFlow(server: Server) {
        server.addTool(
            name = "analyze_data_flow",
            description = "数据流分析——空安全推理、值传播追踪、外部注解查询",
            inputSchema = ToolSchemas.analyzeDataFlow
        ) { request ->
            handleTool(
                "analyze_data_flow",
                request.arguments,
                AnalyzeDataFlowArgs.serializer(),
                DataFlowResult.serializer(),
                SizePolicy.ANALYZE_DATA_FLOW
            ) { project, args ->
                DataFlowAnalyzer.analyze(project, args)
            }
        }
    }

    private fun registerCheckRules(server: Server) {
        server.addTool(
            name = "check_rules",
            description = "验证代码是否遵守自定义架构规则（层级违规、非法依赖）",
            inputSchema = ToolSchemas.checkRules
        ) { request ->
            handleTool(
                "check_rules",
                request.arguments,
                CheckRulesArgs.serializer(),
                RuleCheckResult.serializer(),
                SizePolicy.CHECK_RULES
            ) { project, args ->
                RuleChecker.check(project, args)
            }
        }
    }

    private fun registerStructuralSearch(server: Server) {
        server.addTool(
            name = "structural_search",
            description = "基于 AST 的代码模式搜索（IntelliJ SSR 语法），超越文本搜索",
            inputSchema = ToolSchemas.structuralSearch
        ) { request ->
            handleTool(
                "structural_search",
                request.arguments,
                StructuralSearchArgs.serializer(),
                SearchMatchResult.serializer(),
                SizePolicy.STRUCTURAL_SEARCH
            ) { project, args ->
                StructuralSearcher.search(project, args)
            }
        }
    }

    private fun registerAnalyzeQuality(server: Server) {
        server.addTool(
            name = "analyze_quality",
            description = "代码质量分析——复杂度热点、死代码、代码克隆、设计模式/反模式、异常处理",
            inputSchema = ToolSchemas.analyzeQuality
        ) { request ->
            handleTool(
                "analyze_quality",
                request.arguments,
                AnalyzeQualityArgs.serializer(),
                QualityReport.serializer(),
                SizePolicy.ANALYZE_QUALITY
            ) { project, args ->
                QualityAnalyzer.analyze(project, args)
            }
        }
    }

    private fun registerSandbox(server: Server) {
        server.addTool(
            name = "sandbox",
            description = "安全沙盒——反编译查看源码、Java→Kotlin 转换、批量修复（如移除无用 import）",
            inputSchema = ToolSchemas.sandbox
        ) { request ->
            handleTool(
                "sandbox",
                request.arguments,
                SandboxArgs.serializer(),
                SandboxResult.serializer(),
                SizePolicy.SANDBOX
            ) { project, args ->
                SandboxExecutor.execute(project, args)
            }
        }
    }
}

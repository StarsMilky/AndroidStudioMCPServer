package com.codeintel.mcpserver.server

import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.formatting.ResponseFormatter
import com.codeintel.mcpserver.formatting.SizePolicy
import com.codeintel.mcpserver.models.args.AnalyzeDataFlowArgs
import com.codeintel.mcpserver.models.args.AnalyzeQualityArgs
import com.codeintel.mcpserver.models.args.CheckpointArgs
import com.codeintel.mcpserver.models.args.CheckRulesArgs
import com.codeintel.mcpserver.models.args.FindReferencesArgs
import com.codeintel.mcpserver.models.args.GetScopeArgs
import com.codeintel.mcpserver.models.args.QueryProjectArgs
import com.codeintel.mcpserver.models.args.QueryProjectMode
import com.codeintel.mcpserver.models.args.QueryFrameworkArgs
import com.codeintel.mcpserver.models.args.RefactorArgs
import com.codeintel.mcpserver.models.args.ResolveSymbolArgs
import com.codeintel.mcpserver.models.args.SandboxArgs
import com.codeintel.mcpserver.models.args.StructuralSearchArgs
import com.codeintel.mcpserver.models.results.CheckpointResult
import com.codeintel.mcpserver.models.results.DataFlowResult
import com.codeintel.mcpserver.models.results.FrameworkViewResult
import com.codeintel.mcpserver.models.results.ProjectOverview
import com.codeintel.mcpserver.models.results.QualityReport
import com.codeintel.mcpserver.models.results.ReferenceResult
import com.codeintel.mcpserver.models.results.RefactorResult
import com.codeintel.mcpserver.models.results.RuleCheckResult
import com.codeintel.mcpserver.models.results.SandboxResult
import com.codeintel.mcpserver.models.results.ScopeResult
import com.codeintel.mcpserver.models.results.SearchMatchResult
import com.codeintel.mcpserver.models.results.SymbolInfo
import com.codeintel.mcpserver.services.CheckpointManager
import com.codeintel.mcpserver.services.DataFlowAnalyzer
import com.codeintel.mcpserver.services.FrameworkAnalyzer
import com.codeintel.mcpserver.services.ProjectAnalyzer
import com.codeintel.mcpserver.services.QualityAnalyzer
import com.codeintel.mcpserver.services.RefactorExecutor
import com.codeintel.mcpserver.services.ReferenceSearcher
import com.codeintel.mcpserver.services.RuleChecker
import com.codeintel.mcpserver.services.SandboxExecutor
import com.codeintel.mcpserver.services.ScopeAnalyzer
import com.codeintel.mcpserver.services.StructuralSearcher
import com.codeintel.mcpserver.services.SymbolResolver
import com.codeintel.mcpserver.util.MCP_JSON
import com.codeintel.mcpserver.util.ProjectResolver
import com.codeintel.mcpserver.util.PsiUtils
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
            val args = MCP_JSON.decodeFromJsonElement(
                argsDeserializer,
                arguments ?: JsonObject(emptyMap())
            )
            val result = action(project, args)
            val json = ResponseFormatter.format(result, resultSerializer, policy)
            ToolMetricsService.markComplete(
                toolName,
                json,
                System.currentTimeMillis() - startTime,
                false
            )
            CallToolResult(content = listOf(TextContent(json)))
        } catch (e: ToolException) {
            val errorJson = e.toErrorJson()
            ToolMetricsService.markComplete(
                toolName,
                errorJson,
                System.currentTimeMillis() - startTime,
                true
            )
            CallToolResult(content = listOf(TextContent(errorJson)), isError = true)
        }
    }

    private fun registerResolveSymbol(server: Server) {
        server.addTool(
            name = "resolve_symbol",
            description = "Resolve symbol by position (file+line+column) OR by name. Returns fully-qualified type, declaration location (file, line, column), and symbol kind. Use name-based lookup to avoid grep.",
            inputSchema = ToolSchemas.resolveSymbol
        ) { request ->
            handleTool(
                "resolve_symbol",
                request.arguments,
                ResolveSymbolArgs.serializer(),
                SymbolInfo.serializer(),
                SizePolicy.RESOLVE_SYMBOL
            ) { project, args ->
                SymbolResolver.resolve(project, args)
            }
        }
    }

    private fun registerFindReferences(server: Server) {
        server.addTool(
            name = "find_references",
            description = "Semantic reference search by position (file+line+column) OR by qualified name. Supports usages, call hierarchy, callees, and type hierarchy (zero false positives).",
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
            description = "List all visible symbols at a code position (locals, members, extensions, imports)",
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
            description = "Local History operations: create checkpoint, view history, rollback, diff",
            inputSchema = ToolSchemas.checkpoint
        ) { request ->
            handleTool(
                "checkpoint",
                request.arguments,
                CheckpointArgs.serializer(),
                CheckpointResult.serializer(),
                SizePolicy.CHECKPOINT
            ) { project, args ->
                CheckpointManager.execute(
                    project,
                    args.operation,
                    args.label,
                    args.file,
                    args.targetLabel
                )
            }
        }
    }

    private fun registerRefactor(server: Server) {
        server.addTool(
            name = "refactor",
            description = "Semantic-safe refactoring (rename/move/extract/safe_delete/change_signature) across Java/Kotlin/XML/Manifest",
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
            description = "Project overview, dependency graph, change impact analysis, API surface, build variants",
            inputSchema = ToolSchemas.queryProject
        ) { request ->
            val toolName = "query_project"
            ToolMetricsService.markStart(toolName)
            val startTime = System.currentTimeMillis()
            try {
                val project = ProjectResolver.resolve(request.arguments)
                PsiUtils.refreshForExternalChanges(project)
                val args = MCP_JSON.decodeFromJsonElement(
                    QueryProjectArgs.serializer(),
                    request.arguments ?: JsonObject(emptyMap())
                )
                val result = ProjectAnalyzer.analyze(project, args)
                val policy = if (args.mode == QueryProjectMode.OVERVIEW) {
                    SizePolicy.QUERY_PROJECT_PANORAMA
                } else {
                    SizePolicy.QUERY_PROJECT_DETAIL
                }
                val json = ResponseFormatter.format(
                    result,
                    ProjectOverview.serializer(),
                    policy
                )
                ToolMetricsService.markComplete(
                    toolName,
                    json,
                    System.currentTimeMillis() - startTime,
                    false
                )
                CallToolResult(content = listOf(TextContent(json)))
            } catch (e: ToolException) {
                val errorJson = e.toErrorJson()
                ToolMetricsService.markComplete(
                    toolName,
                    errorJson,
                    System.currentTimeMillis() - startTime,
                    true
                )
                CallToolResult(content = listOf(TextContent(errorJson)), isError = true)
            }
        }
    }

    private fun registerQueryFramework(server: Server) {
        server.addTool(
            name = "query_framework",
            description = "Annotation-based structural views for Room/Retrofit/Hilt/Compose/Navigation",
            inputSchema = ToolSchemas.queryFramework
        ) { request ->
            val toolName = "query_framework"
            ToolMetricsService.markStart(toolName)
            val startTime = System.currentTimeMillis()
            try {
                val project = ProjectResolver.resolve(request.arguments)
                PsiUtils.refreshForExternalChanges(project)
                val args = MCP_JSON.decodeFromJsonElement(
                    QueryFrameworkArgs.serializer(),
                    request.arguments ?: JsonObject(emptyMap())
                )
                val result = FrameworkAnalyzer.analyze(project, args)
                val policy = if (args.detailTarget != null) {
                    SizePolicy.QUERY_FRAMEWORK_DETAIL
                } else {
                    SizePolicy.QUERY_FRAMEWORK_LIST
                }
                val json = ResponseFormatter.format(
                    result,
                    FrameworkViewResult.serializer(),
                    policy
                )
                ToolMetricsService.markComplete(
                    toolName,
                    json,
                    System.currentTimeMillis() - startTime,
                    false
                )
                CallToolResult(content = listOf(TextContent(json)))
            } catch (e: ToolException) {
                val errorJson = e.toErrorJson()
                ToolMetricsService.markComplete(
                    toolName,
                    errorJson,
                    System.currentTimeMillis() - startTime,
                    true
                )
                CallToolResult(content = listOf(TextContent(errorJson)), isError = true)
            }
        }
    }

    private fun registerAnalyzeDataFlow(server: Server) {
        server.addTool(
            name = "analyze_data_flow",
            description = "Data flow analysis: nullability inference, value propagation tracing, external annotations",
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
            description = "Validate code against custom architecture rules (layer violations, illegal dependencies)",
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
            description = "AST-based code pattern search using IntelliJ SSR syntax, beyond text search",
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
            description = "Code quality analysis: complexity hotspots, dead code, clones, patterns, error handling",
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
            description = "Sandbox: decompile library source, Java-to-Kotlin conversion, batch inspections",
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

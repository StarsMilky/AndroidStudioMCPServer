package com.codeintel.mcpserver.server

import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.formatting.ResponseFormatter
import com.codeintel.mcpserver.formatting.SizePolicy
import com.codeintel.mcpserver.models.args.AnalyzeDataFlowArgs
import com.codeintel.mcpserver.models.args.AnalyzeQualityArgs
import com.codeintel.mcpserver.models.args.CheckpointArgs
import com.codeintel.mcpserver.models.args.CheckRulesArgs
import com.codeintel.mcpserver.models.args.FindReferencesArgs
import com.codeintel.mcpserver.models.args.FindSymbolArgs
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
import com.codeintel.mcpserver.models.results.FindSymbolResult
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
        registerFindSymbol(server)
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
            description = """
                Resolve the symbol at an exact code position (file+line+column). Returns its fully-qualified type, declaration location, and kind.

                When to use:
                 - You already have a cursor position and need to know what symbol/type is there
                 - To verify a type before further analysis (e.g. "is UserId a class or typealias?")
                 - Chaining from find_references results (which give you file+line+column)

                Example: resolve_symbol(file="src/Main.kt", line=42, column=12)

                Returns: qualifiedType, declarationFile, declarationLine, declarationColumn, kind.
                For name-based lookup (when you only know the symbol name), use find_symbol instead.
            """.trimIndent(),
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

    private fun registerFindSymbol(server: Server) {
        server.addTool(
            name = "find_symbol",
            description = """
                Find a symbol (class, method, field) by name. Returns matches with fully-qualified name, declaration file, line, and kind.

                USE THIS INSTEAD OF grep/rg to locate code symbols — zero false positives (uses IDE index, not text search), works across Java/Kotlin.

                When to use:
                 - The user mentions a symbol by name (e.g. "show me UserRepository")
                 - You need to locate a symbol before running find_references or analyze_data_flow
                 - You want to disambiguate candidates before choosing one

                Example: find_symbol(name="UserRepository", kind="CLASS")
                Example: find_symbol(name="com.example.UserRepository.findById")

                Returns: matches[] with {qualifiedName, declarationFile, declarationLine, kind}, totalMatches, nextAction.
            """.trimIndent(),
            inputSchema = ToolSchemas.findSymbol
        ) { request ->
            handleTool(
                "find_symbol",
                request.arguments,
                FindSymbolArgs.serializer(),
                FindSymbolResult.serializer(),
                SizePolicy.RESOLVE_SYMBOL
            ) { project, args ->
                SymbolResolver.findByName(project, args)
            }
        }
    }

    private fun registerFindReferences(server: Server) {
        server.addTool(
            name = "find_references",
            description = """
                Find all usages, callers, callees, or type hierarchy of a symbol.

                USE THIS INSTEAD OF grep/rg when searching for method calls, class usages, or override chains. Works across Java/Kotlin/XML in one call. Zero false positives.

                When to use:
                 - "Where is this method called?" → mode=CALLERS
                 - "What does this method invoke?" → mode=CALLEES
                 - "Who uses this class/field?" → mode=USAGES
                 - "What subclasses this / what does it implement?" → mode=TYPE_HIERARCHY

                Two entry modes: by position (file+line+column) OR by qualified_name (preferred when you already know the FQN).

                Example: find_references(qualified_name="com.example.UserRepo.findById", mode="CALLERS", depth=3)

                Returns: usages[] or callHierarchy or typeHierarchy. Paginated via offset/limit.
            """.trimIndent(),
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
            description = """
                List all symbols visible at a code position: locals, parameters, fields, extensions, imports.

                When to use:
                 - Before writing/inserting code at a cursor position, to know what's in scope
                 - To check if a name is shadowed or already defined
                 - To discover available extension functions

                Example: get_scope(file="src/Main.kt", line=42, column=10, filter="ALL")
            """.trimIndent(),
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
            description = """
                Save / list / rollback / diff local snapshots of the project.

                When to use:
                 - ALWAYS call with operation=CREATE before any multi-file refactor or risky change
                 - Use DIFF to review what changed since a checkpoint
                 - Use ROLLBACK to revert when a refactor went wrong

                Example: checkpoint(operation="CREATE", label="before-big-refactor")
            """.trimIndent(),
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
            description = """
                Safe rename / move / extract / safe-delete / change-signature across Java+Kotlin+XML+Manifest.

                NEVER use sed or text-replace for renaming code — this handles all references, imports, and override chains automatically.

                When to use:
                 - User says "rename X to Y" → operation=RENAME
                 - User says "move this class to package P" → operation=MOVE
                 - User says "extract these lines into a function" → operation=EXTRACT
                 - User says "delete this unused thing" → operation=SAFE_DELETE (checks references first)
                 - User says "add/change a parameter" → operation=CHANGE_SIGNATURE

                Example: refactor(operation="RENAME", file="Foo.kt", line=5, column=7, new_name="Bar")

                Tip: run checkpoint(CREATE) before any multi-file refactor.
            """.trimIndent(),
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
            description = """
                ⭐ START HERE when entering an unfamiliar project. Returns project architecture: modules, key classes, detected frameworks, build variants.

                When to use:
                 - First action on any new project ("where do I start?") → mode=OVERVIEW
                 - Before large refactors, to see transitive dependency impact → mode=DEPENDENCY
                 - To map the public API surface of a module → mode=API_SURFACE
                 - To inspect active build variants / flavors → mode=VARIANT

                Example: query_project(mode="OVERVIEW")
                Example: query_project(mode="DEPENDENCY", target_class="com.example.UserRepo", change_type="SIGNATURE_CHANGE")
            """.trimIndent(),
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
            description = """
                Deep-dive into framework-specific structure: Room entities/DAOs, Retrofit APIs, Hilt bindings, Compose composables, Navigation graphs.

                When to use:
                 - User asks about DB schema or queries → framework=ROOM
                 - User asks about API endpoints → framework=RETROFIT
                 - User asks about DI / module bindings → framework=HILT
                 - User asks about UI screens or composables → framework=COMPOSE
                 - User asks about navigation flow → framework=NAVIGATION

                Use detail_target to drill into a specific entity/interface by name.

                Example: query_framework(framework="ROOM", detail_target="UserEntity")
            """.trimIndent(),
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
            description = """
                Track how a value flows or whether it can be null. Replaces manual code tracing.

                When to use:
                 - "Can this expression be null at this point?" → mode=NULLABILITY
                 - "Where does this value come from?" → mode=BACKWARD
                 - "Where does this value propagate to?" → mode=FORWARD
                 - "What nullability annotations apply here?" → mode=EXTERNAL_ANNOTATIONS

                Example: analyze_data_flow(file="Foo.kt", line=10, column=5, mode="NULLABILITY")
            """.trimIndent(),
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
            description = """
                Validate architecture rules against the codebase (e.g. "UI must not depend on DB layer"). Returns a list of violations with file:line.

                When to use:
                 - To enforce layering/module boundaries during code review
                 - To detect forbidden cross-package dependencies

                Example: check_rules(rules=[{name:"no-ui-to-db", source:"com.app.ui", must_not_depend_on:["com.app.db"]}])
            """.trimIndent(),
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
            description = """
                AST-pattern search using IntelliJ SSR (Structural Search & Replace).

                USE THIS INSTEAD OF grep for code patterns (e.g. "all @Composable functions", "all synchronized blocks", "new Thread() calls"). Grep matches text; this matches syntax trees — zero false positives.

                When to use:
                 - Finding anti-patterns across the codebase
                 - Locating uses of a specific construct (annotations, try/catch shapes, lambda patterns)

                Example: structural_search(pattern="Thread().start()", file_type="kotlin")
                Example: structural_search(pattern="@Composable fun ${'$'}X${'$'}", file_type="kotlin")
            """.trimIndent(),
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
            description = """
                Find code quality hotspots: complexity, dead code, duplicates, pattern violations, bad error handling. Use for audits and code reviews.

                Modes: COMPLEXITY (cyclomatic hotspots), DEAD_CODE (unused symbols), CLONES (copy-pasted code), PATTERNS (anti-patterns), ERROR_HANDLING (empty catch blocks, swallowed exceptions).

                Example: analyze_quality(mode="COMPLEXITY", top_n=10)
                Example: analyze_quality(mode="DEAD_CODE", scope="module:app")
            """.trimIndent(),
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
            description = """
                Advanced operations: decompile library classes (read 3rd-party source), Java→Kotlin conversion, batch IDE inspections with auto-fix.

                When to use:
                 - User wants to see source of a compiled dependency → DECOMPILE
                 - Converting a legacy Java file to Kotlin → CONVERT_J2K
                 - Applying IDE inspections at scale (try dry_run=true first) → BATCH_FIX

                Example: sandbox(operation="DECOMPILE", qualified_class_name="java.util.HashMap")

                Note: JVM-only.
            """.trimIndent(),
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

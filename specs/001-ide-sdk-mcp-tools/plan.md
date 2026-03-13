# Implementation Plan: IDE SDK MCP Tools

**Branch**: `001-ide-sdk-mcp-tools` | **Date**: 2026-03-13 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-ide-sdk-mcp-tools/spec.md`

## Summary

基于 IntelliJ 2025.2+ 内置 MCP Server 的 `AbstractMcpTool` 扩展点，实现 12 个 PSI 语义分析工具，为 AI Agent 提供确定性的代码理解、重构和质量分析能力。插件以 Android Studio 2025.2.2+ 为目标平台，通过 kotlinx-serialization 定义工具参数和返回值，通过 ReadAction/WriteAction 安全访问 PSI 模型。

## Technical Context

**Language/Version**: Kotlin 2.1.20, JVM 21  
**Primary Dependencies**: IntelliJ Platform SDK (252.27397.103), MCP Server Plugin (`com.intellij.mcpServer:1.0.30` marketplace), kotlinx-serialization-json  
**Storage**: N/A（使用 IDE 内存 PSI 模型 + Local History）  
**Testing**: IntelliJ Platform Test Framework (`BasePlatformTestCase`, `LightJavaCodeInsightFixtureTestCase`)  
**Target Platform**: Android Studio 2025.2+ (IntelliJ platform 252+)  
**Project Type**: IntelliJ Platform Plugin (MCP Server extension)  
**Performance Goals**: ≤5s (200 类), ≤30s (500+ 类), 返回大小 200B~16KB  
**Constraints**: ReadAction/WriteAction PSI 线程模型, 单 Agent 非独占并发模型  
**Scale/Scope**: 12 个正交 MCP 工具, 支持 1000+ 类的 Android 项目

## Constitution Check

*Constitution file not found — skipping gate check. No violations to justify.*

## Project Structure

### Documentation (this feature)

```text
specs/001-ide-sdk-mcp-tools/
├── spec.md
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (MCP tool schemas)
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
src/main/kotlin/com/androidstudio/mcpserver/
├── tools/                          # Layer 1: MCP Tool handlers (AbstractMcpTool)
│   ├── ResolveSymbolTool.kt        # FR-001: resolve_symbol
│   ├── FindReferencesTool.kt       # FR-002: find_references
│   ├── GetScopeTool.kt             # FR-003: get_scope
│   ├── RefactorTool.kt             # FR-004: refactor
│   ├── AnalyzeDataFlowTool.kt      # FR-005: analyze_data_flow
│   ├── QueryProjectTool.kt         # FR-006: query_project
│   ├── QueryFrameworkTool.kt       # FR-007: query_framework
│   ├── AnalyzeQualityTool.kt       # FR-008: analyze_quality
│   ├── CheckRulesTool.kt           # FR-009: check_rules
│   ├── StructuralSearchTool.kt     # FR-010: structural_search
│   ├── CheckpointTool.kt           # FR-011: checkpoint
│   └── SandboxTool.kt              # FR-012: sandbox
│
├── services/                       # Layer 2: PSI 业务逻辑（可复用、可测试）
│   ├── SymbolResolver.kt           # PsiReference.resolve(), PsiExpression.getType()
│   ├── ReferenceSearcher.kt        # ReferencesSearch, CallHierarchy, TypeHierarchy
│   ├── ScopeAnalyzer.kt            # PsiScopeProcessor, processDeclarations()
│   ├── RefactorExecutor.kt         # RefactoringFactory, 5 种重构操作
│   ├── DataFlowAnalyzer.kt         # DfaUtil, SliceAnalysis, ExternalAnnotations
│   ├── ProjectAnalyzer.kt          # ModuleManager, DependenciesBuilder, VariantContext
│   ├── FrameworkAnalyzer.kt        # AnnotatedElementsSearch (Room/Retrofit/Hilt/...)
│   ├── QualityAnalyzer.kt          # GlobalInspectionTool, DuplicatesProfile
│   ├── RuleChecker.kt              # DependencyValidationManager
│   ├── StructuralSearcher.kt       # StructuralSearchProfile, Matcher
│   ├── CheckpointManager.kt        # LocalHistory API
│   └── SandboxExecutor.kt          # ScratchFileService, IdeaDecompiler, J2K
│
├── models/                         # Layer 3: 数据模型
│   ├── args/                       # @Serializable 输入参数类
│   │   ├── ResolveSymbolArgs.kt
│   │   ├── FindReferencesArgs.kt
│   │   ├── GetScopeArgs.kt
│   │   ├── RefactorArgs.kt
│   │   ├── AnalyzeDataFlowArgs.kt
│   │   ├── QueryProjectArgs.kt
│   │   ├── QueryFrameworkArgs.kt
│   │   ├── AnalyzeQualityArgs.kt
│   │   ├── CheckRulesArgs.kt
│   │   ├── StructuralSearchArgs.kt
│   │   ├── CheckpointArgs.kt
│   │   └── SandboxArgs.kt
│   └── results/                    # @Serializable 输出结果类
│       ├── SymbolInfo.kt
│       ├── ReferenceResult.kt
│       ├── ScopeResult.kt
│       ├── RefactorResult.kt
│       ├── DataFlowResult.kt
│       ├── ProjectOverview.kt
│       ├── FrameworkViewResult.kt
│       ├── QualityReport.kt
│       ├── RuleCheckResult.kt
│       ├── SearchMatchResult.kt
│       ├── CheckpointResult.kt
│       └── SandboxResult.kt
│
├── formatting/                     # Layer 4: 返回大小控制
│   ├── ResponseFormatter.kt        # JSON 序列化 + 大小裁剪
│   └── SizePolicy.kt              # 各工具大小上限配置 (FR-013)
│
├── errors/                         # Layer 5: 统一错误处理
│   ├── McpErrorCode.kt            # 错误码枚举 (FR-017)
│   └── ToolException.kt           # 工具异常基类
│
└── util/                           # 共享工具
    ├── PsiUtils.kt                # ReadAction/WriteAction 辅助函数
    ├── ProjectUtils.kt            # Project 实例工具函数
    └── JsonUtils.kt               # McpJson (JsonNamingStrategy.SnakeCase + encodeDefaults)

src/main/resources/META-INF/
└── plugin.xml                      # 12 个 mcpServerTool 扩展注册

src/test/kotlin/com/androidstudio/mcpserver/
├── tools/                          # Tool handler 集成测试
├── services/                       # Service 单元测试
└── testdata/                       # 测试用 Java/Kotlin 源文件
```

**Structure Decision**: 三层架构 — Tool Handler（MCP 适配层）→ Service（PSI 业务逻辑）→ Models（数据传输对象）。Tool 层薄，Service 层厚，便于单元测试和复用。

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    MCP Client (Cursor/Claude/etc.)       │
└──────────────────────┬──────────────────────────────────┘
                       │ JSON-RPC over stdio/SSE
┌──────────────────────▼──────────────────────────────────┐
│              IntelliJ Built-in MCP Server                │
│              (com.intellij.mcpServer)                    │
│   ┌───────────┐ ┌───────────┐ ┌───────────────────┐    │
│   │ Built-in  │ │ Built-in  │ │  Our Extension     │    │
│   │ Tools     │ │ Tools     │ │  Tools (12)        │    │
│   │ (rename,  │ │ (search,  │ │  ┌──────────────┐  │    │
│   │  symbol,  │ │  terminal │ │  │AbstractMcpTool│  │    │
│   │  files)   │ │  ...)     │ │  └──────┬───────┘  │    │
│   └───────────┘ └───────────┘ │         │          │    │
└───────────────────────────────┼─────────┼──────────┘    │
                                │         │               │
┌───────────────────────────────▼─────────▼───────────────┘
│           Our Plugin: AndroidStudioMCPServer              │
│                                                           │
│  ┌─ Tool Layer ──────────────────────────────────────┐   │
│  │ ResolveSymbolTool  FindReferencesTool  GetScopeTool│   │
│  │ RefactorTool  AnalyzeDataFlowTool  QueryProjectTool│   │
│  │ QueryFrameworkTool  AnalyzeQualityTool            │   │
│  │ CheckRulesTool  StructuralSearchTool              │   │
│  │ CheckpointTool  SandboxTool                       │   │
│  └────────────────────┬──────────────────────────────┘   │
│                       │ delegates                         │
│  ┌─ Service Layer ────▼──────────────────────────────┐   │
│  │ SymbolResolver   ReferenceSearcher  ScopeAnalyzer │   │
│  │ RefactorExecutor DataFlowAnalyzer ProjectAnalyzer │   │
│  │ FrameworkAnalyzer QualityAnalyzer  RuleChecker    │   │
│  │ StructuralSearcher CheckpointManager              │   │
│  │ SandboxExecutor                                   │   │
│  └────────────────────┬──────────────────────────────┘   │
│                       │ uses                              │
│  ┌─ IntelliJ Platform ▼ APIs ────────────────────────┐   │
│  │ PSI  UAST  RefactoringEngine  DFA  LocalHistory   │   │
│  │ SSR  InspectionEngine  ScratchFile  Layoutlib*    │   │
│  └───────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### D1: Tool Layer 保持薄

每个 Tool 类 ≤50 行：参数解析 → 调用 Service → 格式化返回。复杂逻辑全部在 Service 层。

```kotlin
class ResolveSymbolTool : AbstractMcpTool<ResolveSymbolArgs>(ResolveSymbolArgs.serializer()) {
    override val name = "resolve_symbol"
    override val description = "精确解析代码中任意位置的符号：返回全限定类型、声明位置、符号类别"

    override fun handle(project: Project, args: ResolveSymbolArgs): Response {
        return try {
            val result = SymbolResolver.resolve(project, args.file, args.line, args.column)
            Response(ResponseFormatter.format(result, SizePolicy.RESOLVE_SYMBOL))
        } catch (e: ToolException) {
            Response(error = e.toMcpError())
        }
    }
}
```

### D2: ReadAction 统一封装

所有 PSI 读操作通过 `PsiUtils` 封装，提供两种策略：

- **`readAction`**: 索引中立即返回 `INDEXING_IN_PROGRESS` 错误（快速失败），适用于高频、低延迟工具
- **`smartReadAction`**: 等待索引完成后执行（阻塞），适用于分析类工具（可容忍等待）

```kotlin
object PsiUtils {
    fun <T> readAction(project: Project, action: () -> T): T {
        if (DumbService.isDumb(project)) {
            throw ToolException(McpErrorCode.INDEXING_IN_PROGRESS)
        }
        return ApplicationManager.getApplication().runReadAction(Computable { action() })
    }

    fun <T> smartReadAction(project: Project, action: () -> T): T {
        return DumbService.getInstance(project).runReadActionInSmartMode(Computable { action() })
    }
}
```

**各工具 ReadAction 策略分配**：

| 工具 | 策略 | 理由 |
|------|------|------|
| `resolve_symbol` | `readAction` (快速失败) | 高频基础操作，Agent 可稍后重试 |
| `find_references` | `readAction` (快速失败) | 高频操作，结果依赖完整索引 |
| `get_scope` | `readAction` (快速失败) | 高频操作 |
| `refactor` | `smartReadAction` (等待) | 写操作前需要完整索引保证安全 |
| `analyze_data_flow` | `smartReadAction` (等待) | 深度分析，结果质量优先于延迟 |
| `query_project` | `smartReadAction` (等待) | 全局分析，需要完整索引 |
| `query_framework` | `smartReadAction` (等待) | 注解扫描依赖完整索引 |
| `analyze_quality` | `smartReadAction` (等待) | 全局分析 |
| `check_rules` | `smartReadAction` (等待) | 依赖分析需要完整索引 |
| `structural_search` | `smartReadAction` (等待) | AST 匹配需要完整 PSI 树 |
| `checkpoint` | N/A | 不涉及 PSI 读取（LocalHistory API） |
| `sandbox` | 按子操作区分 | scratch: N/A; decompile: `readAction`; render: N/A; j2k: `smartReadAction`; batch_fix: `smartReadAction` |

### D3: 多模式工具的路由

`find_references`、`query_project`、`analyze_quality` 等多模式工具通过 `mode` 参数路由到不同 Service 方法：

```kotlin
@Serializable
data class FindReferencesArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val mode: FindReferencesMode = FindReferencesMode.USAGES,
    val scope: String = "project",
    val depth: Int = 3
)

@Serializable
enum class FindReferencesMode { USAGES, CALL_HIERARCHY, TYPE_HIERARCHY }
```

### D4: 返回大小控制策略 (FR-013)

```kotlin
enum class SizePolicy(val maxBytes: Int) {
    RESOLVE_SYMBOL(200),
    FIND_REFERENCES(3072),
    GET_SCOPE(3584),
    REFACTOR(2048),
    ANALYZE_DATA_FLOW(2048),
    QUERY_PROJECT_PANORAMA(16384),
    QUERY_PROJECT_DETAIL(2048),
    QUERY_FRAMEWORK_LIST(1536),
    QUERY_FRAMEWORK_DETAIL(1024),
    ANALYZE_QUALITY(3072),
    CHECK_RULES(3072),
    STRUCTURAL_SEARCH(3072),
    CHECKPOINT(4096),
    SANDBOX(4096);
}
```

`ResponseFormatter` 在序列化后检查字节大小，超出时自动：
1. 截断列表为 TopN
2. 添加 `"truncated": true, "total": N` 元数据
3. 附带 `"hint": "Use detail mode to get specific items"` 提示（FR-014a 渐进式披露）

### D5: 统一错误码 (FR-017)

错误通过 `Response(error = jsonString)` 返回，MCP 协议层以 `isError=true` 传递给 Agent。
error string 内部采用统一 JSON 结构体，Agent 可解析 `error_code` 做分支处理：

```kotlin
enum class McpErrorCode(val code: String, val message: String) {
    INDEXING_IN_PROGRESS("indexing_in_progress", "IDE is still indexing, please retry later"),
    SYMBOL_NOT_FOUND("symbol_not_found", "Symbol not found at specified position"),
    CONFLICT_DETECTED("conflict_detected", "File was modified during operation"),
    TIMEOUT("timeout", "Operation timed out"),
    PSI_ERROR("psi_error", "PSI analysis error"),
    INVALID_SCOPE("invalid_scope", "Invalid scope parameter");
}

// ToolException → Response(error = JSON)
class ToolException(val errorCode: McpErrorCode, val data: Map<String, String> = emptyMap()) : RuntimeException() {
    fun toErrorJson(): String = buildJsonObject {
        put("error_code", errorCode.code)
        put("message", errorCode.message)
        put("data", buildJsonObject { data.forEach { (k, v) -> put(k, v) } })
    }.toString()
}
```
```

### D6: 写操作安全模式 (FR-018)

`RefactorTool` 和 `SandboxTool.batch_fix` 在执行前自动调用 `CheckpointManager.createAutoCheckpoint()`：

```kotlin
// RefactorExecutor.kt
fun executeRefactor(project: Project, args: RefactorArgs): RefactorResult {
    CheckpointManager.createAutoCheckpoint(project, "before-${args.operation}")
    return WriteCommandAction.runWriteCommandAction(project, Computable {
        when (args.operation) {
            RefactorOperation.RENAME -> executeRename(project, args)
            RefactorOperation.MOVE -> executeMove(project, args)
            // ...
        }
    })
}
```

## Implementation Priority

基于 spec 中的 User Story 优先级和依赖关系：

| Phase | Tools | 理由 |
|-------|-------|------|
| **P0** (MVP) | `resolve_symbol`, `find_references`, `get_scope`, `checkpoint` | Agent 基础能力 + 安全网，最小可用集 |
| **P1** | `refactor`, `query_project`, `query_framework` | 重构 + 项目理解，高频使用 |
| **P2** | `analyze_data_flow`, `check_rules`, `structural_search` | 深度分析能力 |
| **P3** | `analyze_quality`, `sandbox` | 质量治理 + 沙盒，最多子模式 |

## Complexity Tracking

> No constitution violations — no complexity tracking needed.

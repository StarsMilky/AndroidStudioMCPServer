# Data Model: IDE SDK MCP Tools

**Date**: 2026-03-13 | **Branch**: `001-ide-sdk-mcp-tools`

## Entity Overview

本项目不涉及持久化数据库。所有实体均为 **内存中的数据传输对象（DTO）**，用于 MCP 工具的输入参数和返回值。使用 kotlinx-serialization 进行 JSON 序列化。

### 命名策略

Kotlin 数据类使用 `camelCase`（如 `qualifiedType`），JSON 线格式使用 `snake_case`（如 `qualified_type`）。
通过全局 `JsonNamingStrategy.SnakeCase` 统一映射，无需在每个字段上添加 `@SerialName`：

```kotlin
val McpJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
    ignoreUnknownKeys = true
}
```

```
┌─────────────┐     ┌──────────────┐     ┌──────────────┐
│  Tool Args  │────▶│   Service    │────▶│ Tool Result  │
│ (@Serializ) │     │ (PSI Logic)  │     │ (@Serializ)  │
└─────────────┘     └──────────────┘     └──────────────┘
```

## Input Entities (Args)

### ResolveSymbolArgs

```kotlin
@Serializable
data class ResolveSymbolArgs(
    val file: String,       // 文件路径（相对于项目根目录）
    val line: Int,          // 1-based 行号
    val column: Int         // 1-based 列号
)
```

### FindReferencesArgs

```kotlin
@Serializable
data class FindReferencesArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val mode: FindReferencesMode = FindReferencesMode.USAGES,
    val scope: String = "project",    // project | module | file
    val depth: Int = 3,               // 调用层级分析深度
    val offset: Int = 0,              // 分页偏移
    val limit: Int = 20               // 分页大小
)

@Serializable
enum class FindReferencesMode {
    USAGES,           // 引用查找
    CALL_HIERARCHY,   // 调用层级
    TYPE_HIERARCHY    // 类型层级
}
```

### GetScopeArgs

```kotlin
@Serializable
data class GetScopeArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val filter: ScopeFilter = ScopeFilter.ALL
)

@Serializable
enum class ScopeFilter { ALL, VARIABLES, METHODS, TYPES }
```

### RefactorArgs

```kotlin
@Serializable
data class RefactorArgs(
    val operation: RefactorOperation,
    val file: String,
    val line: Int? = null,      // rename/safe_delete/change_signature 需要指定符号位置
    val column: Int? = null,
    // rename
    val newName: String? = null,
    // move
    val targetPackage: String? = null,
    // extract
    val startLine: Int? = null,
    val endLine: Int? = null,
    val methodName: String? = null,
    // change_signature
    val newParameters: List<ParameterChange>? = null,
    val newReturnType: String? = null
)

@Serializable
enum class RefactorOperation {
    RENAME, MOVE, EXTRACT, SAFE_DELETE, CHANGE_SIGNATURE
}

@Serializable
data class ParameterChange(
    val name: String,
    val type: String,
    val defaultValue: String? = null
)
```

### AnalyzeDataFlowArgs

```kotlin
@Serializable
data class AnalyzeDataFlowArgs(
    val file: String,
    val line: Int,
    val column: Int,
    val mode: DataFlowMode = DataFlowMode.NULLABILITY
)

@Serializable
enum class DataFlowMode { NULLABILITY, FORWARD, BACKWARD }
```

### QueryProjectArgs

```kotlin
@Serializable
data class QueryProjectArgs(
    val mode: QueryProjectMode = QueryProjectMode.OVERVIEW,
    // overview mode: no extra params
    // dependency mode:
    val targetClass: String? = null,
    val changeType: ChangeType? = null,
    val maxHops: Int = 3,
    // api_surface mode:
    val module: String? = null,
    // variant mode: no extra params
)

@Serializable
enum class QueryProjectMode {
    OVERVIEW, DEPENDENCY, API_SURFACE, VARIANT
}

@Serializable
enum class ChangeType {
    SIGNATURE_CHANGE, BEHAVIOR_CHANGE, DELETE
}
```

### QueryFrameworkArgs

```kotlin
@Serializable
data class QueryFrameworkArgs(
    val framework: FrameworkType,
    val detailTarget: String? = null  // null=列表模式, 非null=详情模式
)

@Serializable
enum class FrameworkType { ROOM, RETROFIT, HILT, COMPOSE, NAVIGATION }
```

### AnalyzeQualityArgs

```kotlin
@Serializable
data class AnalyzeQualityArgs(
    val mode: QualityMode,
    val scope: String = "project",    // project | module
    val target: String? = null,       // 模块名（scope=module 时）
    val topN: Int = 10
)

@Serializable
enum class QualityMode {
    COMPLEXITY, DEAD_CODE, CLONES, PATTERNS, ERROR_HANDLING
}
```

### CheckRulesArgs

```kotlin
@Serializable
data class CheckRulesArgs(
    val rules: List<ArchitectureRule>
)

@Serializable
data class ArchitectureRule(
    val name: String,
    val source: String,           // glob pattern: "com.example.domain.**"
    val mustNotDependOn: List<String>  // ["android.**", "androidx.**"]
)
```

### StructuralSearchArgs

```kotlin
@Serializable
data class StructuralSearchArgs(
    val pattern: String,          // SSR 模板语法
    val fileType: String = "kotlin",  // kotlin | java | xml
    val scope: String = "project",
    val typeConstraint: String? = null,
    val limit: Int = 20
)
```

### CheckpointArgs

```kotlin
@Serializable
data class CheckpointArgs(
    val operation: CheckpointOperation,
    val label: String? = null,       // create/rollback: 检查点名称
    val file: String? = null,        // history/diff: 文件路径
    val targetLabel: String? = null   // diff: 对比目标检查点
)

@Serializable
enum class CheckpointOperation { CREATE, HISTORY, ROLLBACK, DIFF }
```

### SandboxArgs

```kotlin
@Serializable
data class SandboxArgs(
    val operation: SandboxOperation,
    // scratch:
    val language: String? = null,     // kotlin | java
    val code: String? = null,
    val moduleContext: String? = null,
    val timeout: Int = 30,            // 秒
    // decompile:
    val qualifiedClassName: String? = null,
    // render:
    val layoutFile: String? = null,
    val deviceConfig: DeviceConfig? = null,
    // convert_j2k:
    val javaFile: String? = null,
    // batch_fix:
    val inspectionScope: String? = null,
    val inspectionIds: List<String>? = null,
    val dryRun: Boolean = true
)

@Serializable
enum class SandboxOperation {
    SCRATCH, DECOMPILE, RENDER, CONVERT_J2K, BATCH_FIX
}

@Serializable
data class DeviceConfig(
    val screenWidthDp: Int = 360,
    val screenHeightDp: Int = 640,
    val density: String = "xxhdpi",
    val nightMode: Boolean = false,
    val locale: String = "en",
    val apiLevel: Int = 34
)
```

## Output Entities (Results)

### SymbolInfo

```kotlin
@Serializable
data class SymbolInfo(
    val qualifiedType: String,       // "com.example.MyClass<T>"
    val declarationFile: String,     // 相对路径
    val declarationLine: Int,
    val kind: SymbolKind
)

@Serializable
enum class SymbolKind { CLASS, METHOD, FIELD, VARIABLE, PARAMETER, PROPERTY }
```

### ReferenceResult

```kotlin
@Serializable
data class ReferenceResult(
    val total: Int,
    val usages: List<UsageInfo>? = null,
    val callHierarchy: CallNode? = null,
    val typeHierarchy: TypeHierarchyInfo? = null,
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class UsageInfo(
    val file: String,
    val line: Int,
    val code: String,
    val usageType: UsageType
)

@Serializable
enum class UsageType { CALL, OVERRIDE, READ, WRITE }

@Serializable
data class CallNode(
    val method: String,
    val file: String,
    val line: Int,
    val children: List<CallNode> = emptyList()
)

@Serializable
data class TypeHierarchyInfo(
    val target: String,
    val supers: List<String>,
    val inheritors: List<InheritorInfo>
)

@Serializable
data class InheritorInfo(val className: String, val file: String)
```

### ScopeResult

```kotlin
@Serializable
data class ScopeResult(
    val localVariables: List<ScopeSymbol>,
    val thisMembers: List<ScopeSymbol>,
    val extensionFunctions: List<ScopeSymbol>,
    val importedSymbols: List<ScopeSymbol>,
    val truncated: Boolean = false
)

@Serializable
data class ScopeSymbol(
    val name: String,
    val type: String,
    val kind: String    // variable | method | property | type
)
```

### RefactorResult

```kotlin
@Serializable
data class RefactorResult(
    val success: Boolean,
    val affectedFiles: List<String>,
    val changesCount: Int,
    val preview: List<ChangePreview>? = null,
    val conflicts: List<String>? = null,  // safe_delete 冲突
    val checkpointLabel: String           // 自动创建的检查点名
)

@Serializable
data class ChangePreview(val file: String, val change: String)
```

### DataFlowResult

```kotlin
@Serializable
data class DataFlowResult(
    val nullability: String? = null,       // "definitely_not_null" | "possibly_null" | "definitely_null"
    val reason: String? = null,
    val nullPaths: List<String>? = null,
    val flowPaths: List<FlowPath>? = null
)

@Serializable
data class FlowPath(val steps: List<FlowStep>)

@Serializable
data class FlowStep(val file: String, val line: Int, val code: String)
```

### ProjectOverview

```kotlin
@Serializable
data class ProjectOverview(
    // overview mode
    val modules: List<ModuleInfo>? = null,
    val architecturePattern: String? = null,
    val entryPoints: List<String>? = null,
    val dependencyDirection: String? = null,
    // dependency mode
    val cycles: List<CycleInfo>? = null,
    val impact: ImpactAnalysis? = null,
    val couplingMetrics: Map<String, CouplingMetric>? = null,
    // api_surface mode
    val publicApi: ApiSurface? = null,
    val leakyAbstractions: List<LeakyAbstraction>? = null,
    // variant mode
    val variant: VariantInfo? = null,
    // pagination
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class ModuleInfo(
    val name: String,
    val type: String,
    val dependsOn: List<String>,
    val stats: ModuleStats
)

@Serializable
data class ModuleStats(val classes: Int, val kotlinFiles: Int, val javaFiles: Int)

@Serializable
data class CycleInfo(val path: List<String>, val severity: String)

@Serializable
data class ImpactAnalysis(
    val directImpact: List<String>,
    val transitiveImpact: Map<String, List<String>>,
    val affectedModules: List<String>,
    val affectedTests: List<String>,
    val riskLevel: String,
    val suggestion: String? = null
)

@Serializable
data class CouplingMetric(
    val afferent: Int,
    val efferent: Int,
    val instability: Double
)

@Serializable
data class ApiSurface(val classes: List<ApiClass>, val totalPublicSymbols: Int)

@Serializable
data class ApiClass(val name: String, val kind: String, val methods: List<ApiMethod>)

@Serializable
data class ApiMethod(val name: String, val signature: String, val visibility: String)

@Serializable
data class LeakyAbstraction(val issue: String, val file: String, val suggestion: String)

@Serializable
data class VariantInfo(
    val variant: String,
    val buildType: String,
    val flavors: List<String>,
    val activeSourceDirs: List<String>,
    val inactiveSourceDirs: List<String>,
    val buildConfigFields: Map<String, String>
)
```

### FrameworkViewResult

```kotlin
@Serializable
data class FrameworkViewResult(
    val framework: String,
    val summary: FrameworkSummary? = null,
    val room: RoomView? = null,
    val retrofit: RetrofitView? = null,
    val hilt: HiltView? = null,
    val compose: ComposeView? = null,
    val navigation: NavigationView? = null,
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class FrameworkSummary(val totalComponents: Int, val detailTarget: String? = null)

@Serializable
data class RoomView(
    val databases: List<RoomDatabase>,
    val entities: List<RoomEntity>,
    val daos: List<RoomDao>,
    val migrations: List<RoomMigration>
)

@Serializable
data class RoomDatabase(val name: String, val entities: List<String>, val version: Int)

@Serializable
data class RoomEntity(
    val name: String,
    val tableName: String,
    val fields: List<EntityField>,
    val primaryKey: List<String>,
    val indices: List<String>,
    val relations: List<String>
)

@Serializable
data class EntityField(val name: String, val type: String, val nullable: Boolean)

@Serializable
data class RoomDao(val name: String, val methods: List<DaoMethod>)

@Serializable
data class DaoMethod(val name: String, val sql: String?, val returnType: String, val annotation: String)

@Serializable
data class RoomMigration(val from: Int, val to: Int, val file: String)

@Serializable
data class RetrofitView(
    val interfaces: List<RetrofitInterface>
)

@Serializable
data class RetrofitInterface(val name: String, val baseUrl: String?, val endpoints: List<RetrofitEndpoint>)

@Serializable
data class RetrofitEndpoint(
    val method: String,
    val path: String,
    val httpMethod: String,
    val returnType: String,
    val parameters: List<EndpointParam>
)

@Serializable
data class EndpointParam(val name: String, val type: String, val annotation: String)

@Serializable
data class HiltView(
    val modules: List<HiltModule>,
    val components: List<HiltComponent>,
    val entryPoints: List<HiltEntryPoint>
)

@Serializable
data class HiltModule(val name: String, val installedIn: String, val provides: List<HiltProvides>)

@Serializable
data class HiltProvides(val methodName: String, val returnType: String, val scope: String?)

@Serializable
data class HiltComponent(val name: String, val scope: String, val parent: String?)

@Serializable
data class HiltEntryPoint(val name: String, val installedIn: String, val methods: List<String>)

@Serializable
data class ComposeView(
    val composables: List<ComposableInfo>,
    val themes: List<ThemeInfo>,
    val stateHolders: List<StateHolderInfo>
)

@Serializable
data class ComposableInfo(val name: String, val file: String, val line: Int, val parameters: List<String>, val preview: Boolean)

@Serializable
data class ThemeInfo(val name: String, val file: String, val colorScheme: String?)

@Serializable
data class StateHolderInfo(val name: String, val stateType: String, val file: String)

@Serializable
data class NavigationView(
    val graphs: List<NavGraph>,
    val destinations: List<NavDestination>,
    val deepLinks: List<NavDeepLink>
)

@Serializable
data class NavGraph(val id: String, val startDestination: String, val file: String)

@Serializable
data class NavDestination(val id: String, val className: String?, val arguments: List<NavArgument>, val graphId: String)

@Serializable
data class NavArgument(val name: String, val type: String, val nullable: Boolean, val defaultValue: String?)

@Serializable
data class NavDeepLink(val uri: String, val destination: String)
```

### QualityReport / RuleViolation / SearchMatch / CheckpointInfo / SandboxOutput

```kotlin
@Serializable
data class QualityReport(
    val mode: String,
    val issues: List<QualityIssue>,
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class QualityIssue(
    val type: String,
    val severity: String,       // critical | warning | info
    val file: String,
    val line: Int? = null,
    val description: String,
    val suggestion: String? = null,
    val metrics: Map<String, String>? = null
)

@Serializable
data class RuleCheckResult(
    val passed: Int,
    val failed: Int,
    val violations: List<RuleViolation>,
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class RuleViolation(
    val rule: String,
    val violator: String,
    val illegalDependency: String,
    val file: String,
    val line: Int,
    val suggestion: String? = null
)

@Serializable
data class SearchMatchResult(
    val total: Int,
    val matches: List<SearchMatch>,
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class SearchMatch(
    val file: String,
    val line: Int,
    val matchedCode: String
)

@Serializable
data class CheckpointResult(
    // create
    val label: String? = null,
    val timestamp: String? = null,
    // history
    val entries: List<HistoryEntry>? = null,
    // diff
    val diff: String? = null,
    // rollback
    val restoredFiles: Int? = null
)

@Serializable
data class HistoryEntry(
    val timestamp: String,
    val label: String?,
    val sizeDelta: String
)

@Serializable
data class SandboxResult(
    // scratch
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val compileErrors: List<String>? = null,
    // decompile
    val sourceCode: String? = null,
    val artifact: String? = null,
    val jarPath: String? = null,
    // render
    val imageBase64: String? = null,
    val renderWarnings: List<String>? = null,
    // convert_j2k
    val kotlinCode: String? = null,
    val warnings: List<String>? = null,
    // batch_fix
    val problemsFound: Int? = null,
    val problemsFixed: Int? = null,
    val unfixable: List<UnfixableItem>? = null,
    // timeout
    val timedOut: Boolean = false,
    // batch_fix auto-checkpoint (FR-018)
    val checkpointLabel: String? = null
)

@Serializable
data class UnfixableItem(val file: String, val line: Int, val reason: String)
```

## Entity Relationships

```
ResolveSymbolArgs ──▶ SymbolInfo
FindReferencesArgs ──▶ ReferenceResult ──▶ UsageInfo | CallNode | TypeHierarchyInfo
GetScopeArgs ──▶ ScopeResult ──▶ ScopeSymbol
RefactorArgs ──▶ RefactorResult ──▶ ChangePreview
AnalyzeDataFlowArgs ──▶ DataFlowResult ──▶ FlowPath ──▶ FlowStep
QueryProjectArgs ──▶ ProjectOverview ──▶ ModuleInfo | CycleInfo | ImpactAnalysis | VariantInfo
QueryFrameworkArgs ──▶ FrameworkViewResult ──▶ RoomView | RetrofitView | HiltView | ComposeView | NavigationView
AnalyzeQualityArgs ──▶ QualityReport ──▶ QualityIssue
CheckRulesArgs ──▶ RuleCheckResult ──▶ RuleViolation
StructuralSearchArgs ──▶ SearchMatchResult ──▶ SearchMatch
CheckpointArgs ──▶ CheckpointResult ──▶ HistoryEntry
SandboxArgs ──▶ SandboxResult
```

## Validation Rules

| Entity | Rule |
|--------|------|
| All `file` fields | 必须存在于项目目录中，通过 `ProjectUtils.findFile()` 验证 |
| `line`, `column` | 必须 ≥ 1（1-based），且在文件行数范围内 |
| `RefactorArgs.newName` | RENAME 操作时必填 |
| `RefactorArgs.targetPackage` | MOVE 操作时必填 |
| `SandboxArgs.code` | SCRATCH 操作时必填 |
| `SandboxArgs.timeout` | 1~120 秒范围 |
| `FindReferencesArgs.depth` | 1~10 范围 |
| `QueryProjectArgs.maxHops` | 1~10 范围 |
| `AnalyzeQualityArgs.topN` | 1~100 范围 |

## Response Size Limits (FR-013)

每个 Result 经 `ResponseFormatter` 序列化后字节大小 MUST 不超过以下上限。超限时自动截断并设置 `truncated=true` + `hint` 提示。

| Result 类 | 对应工具 | 上限 |
|-----------|---------|------|
| `SymbolInfo` | resolve_symbol | 200B |
| `ReferenceResult` | find_references | 3KB |
| `ScopeResult` | get_scope | 3.5KB |
| `RefactorResult` | refactor | 2KB |
| `DataFlowResult` | analyze_data_flow | 2KB |
| `ProjectOverview` (overview) | query_project | 16KB |
| `ProjectOverview` (detail) | query_project | 2KB |
| `FrameworkViewResult` (list) | query_framework | 1.5KB |
| `FrameworkViewResult` (detail) | query_framework | 1KB |
| `QualityReport` | analyze_quality | 3KB |
| `RuleCheckResult` | check_rules | 3KB |
| `SearchMatchResult` | structural_search | 3KB |
| `CheckpointResult` | checkpoint | 4KB |
| `SandboxResult` | sandbox | 4KB |

# Research: IDE SDK MCP Tools

**Date**: 2026-03-13 | **Branch**: `001-ide-sdk-mcp-tools`

## R1: MCP Server Integration Strategy

**Decision**: 扩展 IntelliJ 2025.2+ 内置 MCP Server，通过 `AbstractMcpTool` 扩展点注册工具

**Rationale**:
- IntelliJ 2025.2 起内置 MCP Server（plugin ID: `com.intellij.mcpServer`，在 Android Studio 中通过 marketplace plugin 引入：`plugin("com.intellij.mcpServer", "1.0.30")`），已处理 stdio/SSE 传输层
- 提供 `AbstractMcpTool<Args>` 扩展点，第三方插件可注册自定义工具
- 无需自行实现 JSON-RPC、stdio transport、MCP 协议握手等基础设施
- 注册的工具自动对所有已配置的 MCP 客户端（Claude Desktop、Cursor、VS Code）可见

**Alternatives considered**:
- ❌ 使用 Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) 独立实现：需要自行处理 transport（插件运行在 IDE 进程内，无法直接用 System.in/out）、额外复杂度
- ❌ 保持 Android Studio 2025.1 目标 + 独立 MCP Server：兼容性更广但架构复杂度成倍增加

## R2: AbstractMcpTool API

**Decision**: 每个 MCP 工具对应一个 `AbstractMcpTool<Args>` 子类

**API 签名**（来自 JetBrains demo 插件 `MaXal/mcpExtensionPlugin`）:

```kotlin
class MyTool : AbstractMcpTool<MyArgs>(MyArgs.serializer()) {
    override val name: String = "tool_name"
    override val description: String = "Tool description"

    override fun handle(project: Project, args: MyArgs): Response {
        // PSI operations wrapped in readAction/writeAction
        return Response("result JSON string")
        // or Response(error = "error message")
    }
}

@Serializable
data class MyArgs(val param1: String, val param2: Int)
```

**plugin.xml 注册**:

```xml
<depends>com.intellij.mcpServer</depends>
<extensions defaultExtensionNs="com.intellij">
    <mcpServerTool implementation="com.example.MyTool"/>
</extensions>
```

**关键约束**:
- `handle()` 在后台线程调用（非 EDT）
- PSI 读操作需包裹在 `ApplicationManager.getApplication().runReadAction {}` 中
- PSI 写操作需包裹在 `WriteCommandAction.runWriteCommandAction {}` 中（EDT 上执行）
- 返回 `Response(String)` 或 `Response(error = String)`

## R3: IntelliJ PSI Threading Model (2024.1+)

**Decision**: 使用传统 ReadAction/WriteAction API（非协程版），因为 `handle()` 是同步方法

**Rationale**:
- `AbstractMcpTool.handle()` 返回 `Response`（非 suspend），不在协程上下文中
- 使用 `ApplicationManager.getApplication().runReadAction(Computable { ... })` 进行 PSI 读操作
- 写操作使用 `WriteCommandAction.runWriteCommandAction(project) { ... }`
- 未来如果 `handle()` 变为 suspend，可迁移到 `readAction {}` / `readAndWriteAction {}`

**Key APIs**:
- `smartReadAction(project) { ... }` — 等待 smart mode（索引完成后执行），推荐用于需要完整索引的操作
- `DumbService.isDumb(project)` — 检测当前是否在索引中（对应 FR-016）

## R4: Build Configuration

**Decision**: 目标 Android Studio 2025.2.2.7 (IntelliJ platform 252.27397.103)

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    kotlin("plugin.serialization") version "2.1.20"
}

dependencies {
    intellijPlatform {
        androidStudio("2025.2.2.7")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.json")
        plugin("com.intellij.mcpServer", "1.0.30")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}
```

**Note**: Android-specific APIs（Build Variant、layoutlib）需要额外声明 `org.jetbrains.android` 依赖，具体版本在实现阶段确认。

## R5: PSI API Mapping (12 Tools → SDK API)

| Tool | Primary PSI/SDK API | Package |
|------|-------------------|---------|
| `resolve_symbol` | `PsiReference.resolve()`, `PsiExpression.getType()` | `com.intellij.psi` |
| `find_references` | `ReferencesSearch.search()`, `CallHierarchyBrowser`, `ClassInheritorsSearch` | `com.intellij.psi.search.searches` |
| `get_scope` | `PsiScopeProcessor`, `PsiElement.processDeclarations()` | `com.intellij.psi.scope` |
| `refactor` | `RefactoringFactory`, `MoveClassesOrPackagesProcessor`, `ExtractMethodProcessor`, `SafeDeleteProcessor`, `ChangeSignatureProcessor` | `com.intellij.refactoring` |
| `analyze_data_flow` | `DfaUtil`, `SliceAnalysisParams`, `ExternalAnnotationsManager` | `com.intellij.codeInspection.dataFlow` |
| `query_project` | `ModuleManager`, `AllClassesSearch`, `DependenciesBuilder` | `com.intellij.openapi.module` |
| `query_framework` | `AnnotatedElementsSearch`, UAST (`UastLanguagePlugin`) | `com.intellij.psi.search.searches` |
| `analyze_quality` | `GlobalInspectionTool`, `DuplicatesProfile`, `CyclomaticComplexityVisitor` | `com.intellij.codeInspection` |
| `check_rules` | `DependencyValidationManager`, import analysis via PSI | `com.intellij.packageDependencies` |
| `structural_search` | `StructuralSearchProfile`, `Matcher`, `MatchOptions` | `com.intellij.structuralsearch` |
| `checkpoint` | `LocalHistory.getInstance()`, `putSystemLabel()` | `com.intellij.history` |
| `sandbox` | `ScratchFileService`, `IdeaDecompiler`, `RenderService`*, `J2kConverterExtension`*, `InspectionEngine` | various |

*标注项需要 Android 插件依赖

## R6: JetBrains 内置 MCP 工具对比

内置 MCP Server 已提供的工具（无需重复实现）:

| 内置工具 | 我们的对应工具 | 关系 |
|---------|-------------|------|
| `get_symbol_info` | `resolve_symbol` | **互补** — 内置返回文档，我们返回精确类型/声明位置 |
| `rename_refactoring` | `refactor` | **我们的超集** — 我们支持 5 种重构操作 |
| `get_file_problems` | `analyze_quality` | **互补** — 内置按文件检查，我们做项目级质量分析 |
| `search_in_files_by_text/regex` | `structural_search` | **互补** — 内置做文本搜索，我们做 AST 模式匹配 |

**结论**: 我们的 12 个工具与内置工具不冲突，提供更深层的 PSI 语义分析能力。

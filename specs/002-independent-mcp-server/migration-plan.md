# 迁移计划：独立 MCP Server（方案 B）

**日期**: 2026-03-13  
**分支建议**: `002-independent-mcp-server`  
**前置对话**: [API 迁移诊断](5c527ca3-cfc7-48bf-b6c0-a58f82d496c1)

---

## 1. 背景与动机

### 1.1 问题

插件基于 MCP Server 插件 v1.0.30 的 `AbstractMcpTool` API 构建，但 Android Studio 2025.3.2 捆绑的 MCP Server v253.30387.25 **完全重构了 API**：

| 项目 | 旧 API (v1.0.30) | 新 API (v253.x) |
|------|-----------------|-----------------|
| 扩展点 | `<mcpServerTool>` | `<mcpServer.mcpToolset>` (已删除旧扩展点) |
| 基类 | `org.jetbrains.mcpserverplugin.AbstractMcpTool<Args>` | `com.intellij.mcpserver.McpToolset` 接口 |
| 响应 | `org.jetbrains.ide.mcp.Response` | `com.intellij.mcpserver.McpToolCallResult` |
| Project 获取 | `handle(project: Project, args: Args)` | `coroutineContext.getProject()` |

结果：我们的 12 个工具全部未注册，Cursor 只看到内置 21 个工具。

### 1.2 决策

**不再依赖 `com.intellij.mcpServer` 插件**，改用 Kotlin MCP SDK (`io.modelcontextprotocol:kotlin-sdk`) 自建独立 MCP Server。

### 1.3 收益

- API 完全自控，不受 JetBrains 内部 API 变更影响
- MCP 协议是标准规范，极其稳定
- 不绑定特定 Android Studio 版本
- 工具列表独立清晰，不和内置 21 个混在一起
- 可以自定义工具描述、Schema，控制 AI 使用体验

---

## 2. 目标架构

```
┌─────────────┐    SSE/Streamable HTTP    ┌──────────────────────────────┐
│   Cursor     │ ◄──────────────────────► │  Android Studio               │
│  (MCP Client)│                          │  ┌──────────────────────────┐ │
│              │                          │  │ Our Plugin                │ │
│              │                          │  │  ┌─────────────────────┐ │ │
│              │                          │  │  │ MCP Server (Ktor)   │ │ │
│              │                          │  │  │  SSE on port XXXX   │ │ │
│              │                          │  │  └────────┬────────────┘ │ │
│              │                          │  │           │              │ │
│              │                          │  │  ┌────────▼────────────┐ │ │
│              │                          │  │  │ 12 PSI Tool Handlers│ │ │
│              │                          │  │  └────────┬────────────┘ │ │
│              │                          │  │           │              │ │
│              │                          │  │  ┌────────▼────────────┐ │ │
│              │                          │  │  │ Services Layer      │ │ │
│              │                          │  │  │ (PSI Business Logic)│ │ │
│              │                          │  │  └─────────────────────┘ │ │
│              │                          │  └──────────────────────────┘ │
└─────────────┘                          └──────────────────────────────┘
```

Cursor 同时连接两个 MCP Server：
- **内置 JetBrains MCP**: 文件读写、终端、构建、搜索（21 个基础工具）
- **我们的 MCP**: PSI 代码智能（12 个分析/重构工具）

---

## 3. 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| MCP 协议实现 | `io.modelcontextprotocol:kotlin-sdk` | 0.9.0 |
| HTTP Server | Ktor CIO (随 MCP SDK 引入) | 与 SDK 一致 |
| 传输协议 | Streamable HTTP (推荐) 或 SSE | MCP 标准 |
| JSON 序列化 | `kotlinx-serialization-json` | 1.7.3 |
| IDE 平台 | IntelliJ Platform SDK | 252+ (不再绑定特定版本) |
| 构建 | Gradle + intellij-platform-plugin | 2.10.2 |

---

## 4. 关键 API 参考

### 4.1 MCP SDK Server 创建

```kotlin
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.*

val mcpServer = Server(
    serverInfo = Implementation(
        name = "android-studio-psi-tools",
        version = "1.0.0"
    ),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
        ),
    )
)
```

### 4.2 注册工具

```kotlin
mcpServer.addTool(
    name = "resolve_symbol",
    description = "精确解析代码中任意位置的符号：返回全限定类型、声明位置、符号类别",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("file", buildJsonObject {
                put("type", "string")
                put("description", "文件路径（相对于项目根目录）")
            })
            put("line", buildJsonObject {
                put("type", "integer")
                put("description", "行号（1-based）")
            })
            put("column", buildJsonObject {
                put("type", "integer")
                put("description", "列号（1-based）")
            })
        },
        required = listOf("file", "line", "column")
    )
) { request ->
    val file = request.arguments?.get("file")?.jsonPrimitive?.content ?: throw McpError("missing file")
    val line = request.arguments?.get("line")?.jsonPrimitive?.int ?: throw McpError("missing line")
    val column = request.arguments?.get("column")?.jsonPrimitive?.int ?: throw McpError("missing column")

    // 获取 Project（需要自己实现）
    val project = ProjectManager.getInstance().openProjects.firstOrNull()
        ?: return@addTool CallToolResult(content = listOf(TextContent("No project open")), isError = true)

    val result = SymbolResolver.resolve(project, file, line, column)
    CallToolResult(content = listOf(TextContent(McpJson.encodeToString(result))))
}
```

### 4.3 启动 HTTP Server

```kotlin
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

embeddedServer(CIO, host = "127.0.0.1", port = findFreePort()) {
    install(ContentNegotiation) { json(McpJson) }
    mcpStreamableHttp { mcpServer }
}.start(wait = false)
```

### 4.4 IDE 插件生命周期集成

```kotlin
class McpServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // 在项目打开时启动 MCP Server
        McpServerManager.getInstance().startIfNeeded(project)
    }
}
```

---

## 5. 项目获取策略

旧 API 通过 `handle(project: Project, args)` 直接传递 Project。新方案需要自己解决：

### 方案：projectPath 参数 + 自动推断

```kotlin
fun resolveProject(projectPath: String?): Project {
    val openProjects = ProjectManager.getInstance().openProjects
    if (openProjects.isEmpty()) throw ToolException(McpErrorCode.PSI_ERROR, "No project open")

    // 如果只有一个项目，直接返回
    if (openProjects.size == 1) return openProjects.first()

    // 如果指定了 projectPath，匹配
    if (projectPath != null) {
        return openProjects.find { it.basePath == projectPath }
            ?: throw ToolException(McpErrorCode.PSI_ERROR, "Project not found: $projectPath")
    }

    // 默认返回第一个
    return openProjects.first()
}
```

所有 12 个工具都接受可选的 `projectPath` 参数。

---

## 6. 端口发现机制

插件启动的 Ktor server 使用动态端口，需要通知客户端。

### 方案：写入 well-known 文件

```kotlin
// 写入端口信息到 ~/.android-studio-mcp-server.json
val portFile = Path.of(System.getProperty("user.home"), ".android-studio-mcp-server.json")
portFile.writeText("""{"port": $port, "pid": ${ProcessHandle.current().pid()}}""")
```

Cursor 配置使用固定端口或通过 wrapper 脚本读取：

```json
{
  "mcpServers": {
    "android-studio-psi": {
      "url": "http://127.0.0.1:17532/mcp"
    }
  }
}
```

> 建议使用 **固定端口 + fallback 机制**（如果端口被占用则递增查找），简化配置。

---

## 7. 文件变更清单

### 7.1 删除的文件（与旧 MCP Server API 相关）

```
src/main/kotlin/com/androidstudio/mcpserver/tools/
├── ResolveSymbolTool.kt      # 删除（合并到 ToolRegistrar）
├── FindReferencesTool.kt      # 删除
├── GetScopeTool.kt            # 删除
├── CheckpointTool.kt          # 删除
├── RefactorTool.kt            # 删除
├── QueryProjectTool.kt        # 删除
├── QueryFrameworkTool.kt      # 删除
├── AnalyzeDataFlowTool.kt    # 删除
├── CheckRulesTool.kt          # 删除
├── StructuralSearchTool.kt    # 删除
├── AnalyzeQualityTool.kt     # 删除
└── SandboxTool.kt             # 删除
```

### 7.2 新建的文件

```
src/main/kotlin/com/androidstudio/mcpserver/
├── server/
│   ├── McpServerManager.kt        # MCP Server 生命周期管理（单例）
│   ├── McpServerStartupActivity.kt # IDE ProjectActivity，项目打开时启动
│   ├── ToolRegistrar.kt           # 注册 12 个工具到 Server
│   └── ToolSchemas.kt             # 所有工具的 JSON Schema 定义
├── util/
│   └── ProjectResolver.kt         # projectPath → Project 解析
```

### 7.3 修改的文件

| 文件 | 变更说明 |
|------|---------|
| `build.gradle.kts` | 移除 `plugin("com.intellij.mcpServer")`，添加 `io.modelcontextprotocol:kotlin-sdk`，添加 Ktor 依赖 |
| `plugin.xml` | 移除 `<depends>com.intellij.mcpServer</depends>` 和所有 `<mcpServerTool>` 注册，添加 `postStartupActivity` |
| `errors/ToolException.kt` | 可能微调（错误响应格式改为 MCP SDK 的 `isError` 机制） |
| `formatting/ResponseFormatter.kt` | 输出改为 `CallToolResult(content = [TextContent(json)])` |

### 7.4 保留不变的文件

```
services/              # 全部保留，PSI 业务逻辑不变
├── SymbolResolver.kt
├── ReferenceSearcher.kt
├── ScopeAnalyzer.kt
├── CheckpointManager.kt
├── RefactorExecutor.kt
├── ProjectAnalyzer.kt
├── FrameworkAnalyzer.kt
├── DataFlowAnalyzer.kt
├── RuleChecker.kt
├── StructuralSearcher.kt
├── QualityAnalyzer.kt
└── SandboxExecutor.kt

models/                # 全部保留，用于内部序列化返回 JSON
├── args/*.kt          # 保留作为参数解析辅助（可选，也可直接从 JsonObject 解析）
└── results/*.kt       # 保留，序列化为 JSON 返回给 MCP Client

formatting/            # 保留，ResponseFormatter + SizePolicy 仍然有效
errors/                # 保留，McpErrorCode 仍然有效
util/                  # 保留，PsiUtils + ProjectUtils + JsonUtils 不变
```

---

## 8. build.gradle.kts 变更

```kotlin
plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.androidstudio.mcpserver"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        androidStudio("2025.2.2.7")  // 保持最低兼容版本
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.json")
        // ❌ 移除: plugin("com.intellij.mcpServer", "1.0.30")
    }

    // ✅ 新增: MCP SDK + Ktor Server
    implementation("io.modelcontextprotocol:kotlin-sdk:0.9.0")
    implementation("io.ktor:ktor-server-cio:3.1.1")        // 版本需与 SDK 兼容
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")

    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    buildSearchableOptions { enabled = false }
    named("prepareJarSearchableOptions") { dependsOn.clear(); enabled = false }
    named("jarSearchableOptions") { dependsOn.clear(); enabled = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
```

> **注意**：Ktor 版本需与 MCP SDK 0.9.0 兼容。MCP SDK 基于 Ktor 3.x。实际版本号在实现时通过 `./gradlew dependencies` 确认。

---

## 9. plugin.xml 变更

```xml
<idea-plugin>
    <id>com.androidstudio.mcpserver.AndroidStudioMCPServer</id>
    <name>Android Studio PSI Tools (MCP)</name>
    <vendor url="https://github.com/xxx">xxx</vendor>
    <description><![CDATA[
        12 PSI-based code intelligence tools for AI agents via MCP protocol.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.java</depends>
    <depends>org.jetbrains.kotlin</depends>
    <depends>com.intellij.modules.json</depends>
    <!-- ❌ 移除: <depends>com.intellij.mcpServer</depends> -->

    <extensions defaultExtensionNs="com.intellij">
        <!-- ❌ 移除所有 <mcpServerTool> -->
        <!-- ✅ 新增: 项目打开时启动 MCP Server -->
        <postStartupActivity implementation="com.androidstudio.mcpserver.server.McpServerStartupActivity"/>
    </extensions>

    <extensions defaultExtensionNs="org.jetbrains.kotlin">
        <supportsKotlinPluginMode supportsK1="true" supportsK2="true" />
    </extensions>
</idea-plugin>
```

---

## 10. 核心新建类设计

### 10.1 McpServerManager（单例）

```kotlin
@Service(Service.Level.APP)
class McpServerManager {
    private var server: Server? = null
    private var ktorServer: EmbeddedServer<*, *>? = null
    private val port = AtomicInteger(0)

    fun startIfNeeded(project: Project) { /* 启动逻辑 */ }
    fun stop() { /* 停止逻辑 */ }
    fun getPort(): Int = port.get()

    companion object {
        fun getInstance(): McpServerManager =
            ApplicationManager.getApplication().getService(McpServerManager::class.java)
    }
}
```

### 10.2 ToolRegistrar

```kotlin
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

    private fun registerResolveSymbol(server: Server) {
        server.addTool(
            name = "resolve_symbol",
            description = "...",
            inputSchema = ToolSchemas.resolveSymbol
        ) { request ->
            val project = ProjectResolver.resolve(request.arguments)
            val file = request.arguments!!["file"]!!.jsonPrimitive.content
            val line = request.arguments!!["line"]!!.jsonPrimitive.int
            val column = request.arguments!!["column"]!!.jsonPrimitive.int

            try {
                val result = SymbolResolver.resolve(project, file, line, column)
                val json = ResponseFormatter.format(result, SymbolInfo.serializer(), SizePolicy.RESOLVE_SYMBOL)
                CallToolResult(content = listOf(TextContent(json)))
            } catch (e: ToolException) {
                CallToolResult(content = listOf(TextContent(e.toErrorJson())), isError = true)
            }
        }
    }

    // ... 11 more tools
}
```

### 10.3 ToolSchemas

```kotlin
object ToolSchemas {
    val resolveSymbol = ToolSchema(
        properties = buildJsonObject {
            put("file", buildJsonObject {
                put("type", "string")
                put("description", "文件路径（相对于项目根目录）")
            })
            put("line", buildJsonObject {
                put("type", "integer")
                put("description", "行号（1-based）")
            })
            put("column", buildJsonObject {
                put("type", "integer")
                put("description", "列号（1-based）")
            })
        },
        required = listOf("file", "line", "column")
    )

    // ... schemas for all 12 tools
}
```

---

## 11. Cursor 客户端配置

### 11.1 全局配置 (`~/.cursor/mcp.json`)

```json
{
  "mcpServers": {
    "android-studio-psi": {
      "url": "http://127.0.0.1:17532/mcp"
    }
  }
}
```

### 11.2 端口约定

- 默认端口: **17532**（"PSI" 的谐音 + 工具数量 12 → 1-75-32，或其他易记端口）
- 如果被占用: 自动递增 17533, 17534...
- 端口信息写入 `~/.android-studio-mcp-psi.json` 供自动化脚本读取

---

## 12. 实现步骤（按顺序）

### Phase 1: 基础设施（~1.5 小时）

| # | 任务 | 文件 |
|---|------|------|
| 1 | 更新 `build.gradle.kts`：移除 mcpServer 依赖，添加 MCP SDK + Ktor | `build.gradle.kts` |
| 2 | 更新 `plugin.xml`：移除 mcpServer depends 和 mcpServerTool，添加 postStartupActivity | `plugin.xml` |
| 3 | 创建 `ProjectResolver.kt` | `util/ProjectResolver.kt` |
| 4 | 创建 `McpServerManager.kt` | `server/McpServerManager.kt` |
| 5 | 创建 `McpServerStartupActivity.kt` | `server/McpServerStartupActivity.kt` |
| 6 | 验证编译通过 | `./gradlew build` |

### Phase 2: 工具注册（~2 小时）

| # | 任务 | 文件 |
|---|------|------|
| 7 | 创建 `ToolSchemas.kt`（12 个工具的 JSON Schema） | `server/ToolSchemas.kt` |
| 8 | 创建 `ToolRegistrar.kt`（12 个工具的注册 + handler） | `server/ToolRegistrar.kt` |
| 9 | 删除旧的 12 个 `tools/*.kt` 文件 | `tools/*.kt` |
| 10 | 适配 `ResponseFormatter` 输出为 `CallToolResult` | `formatting/ResponseFormatter.kt` |
| 11 | 适配 `ToolException` 的错误处理 | `errors/ToolException.kt` |
| 12 | 验证编译通过 | `./gradlew build` |

### Phase 3: 集成验证（~1 小时）

| # | 任务 |
|---|------|
| 13 | `./gradlew buildPlugin` 构建插件包 |
| 14 | 安装到 Android Studio，检查日志确认 MCP Server 启动 |
| 15 | 配置 Cursor 连接，验证 12 个工具出现在列表中 |
| 16 | 调用 `resolve_symbol` 进行端到端测试 |
| 17 | 更新 `quickstart.md` |

---

## 13. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Ktor 版本与 IDE 内置 Ktor 冲突 | ClassLoader 错误 | 插件 ClassLoader 隔离；如有冲突，改用 Netty 引擎或 IDE 内置 HTTP Server |
| MCP SDK 依赖过大导致插件包膨胀 | 插件安装/更新变慢 | 使用 `kotlin-sdk-server` 而非完整 SDK；Ktor CIO 引擎轻量 |
| 固定端口被占用 | MCP Server 启动失败 | 自动端口递增 + 写入端口文件 |
| IDE 关闭时 Ktor Server 未清理 | 端口泄漏 | 注册 AppLifecycleListener，IDE 关闭时 stop server |
| kotlinx-serialization 版本冲突 | 运行时错误 | 使用 `compileOnly` + IDE bundled 版本；MCP SDK 的序列化版本需验证兼容 |

---

## 14. 内置工具对照表（供参考）

### 内置 MCP Server 21 个工具（不需要我们实现）

| 工具 | 能力 |
|------|------|
| `execute_run_configuration` | 运行配置执行 |
| `get_run_configurations` | 运行配置列表 |
| `build_project` | 触发构建 |
| `get_file_problems` | 单文件 lint/inspection |
| `get_project_dependencies` | 依赖列表 |
| `get_project_modules` | 模块列表 |
| `create_new_file` | 创建文件 |
| `find_files_by_glob` | Glob 查找 |
| `find_files_by_name_keyword` | 关键字查找 |
| `get_all_open_file_paths` | 打开文件列表 |
| `list_directory_tree` | 目录树 |
| `open_file_in_editor` | 打开文件 |
| `reformat_file` | 格式化文件 |
| `get_file_text_by_path` | 读文件内容 |
| `replace_text_in_file` | 替换文件内容 |
| `search_in_files_by_regex` | 正则搜索 |
| `search_in_files_by_text` | 文本搜索 |
| `get_symbol_info` | 符号文档（Quick Doc） |
| `rename_refactoring` | 重命名重构 |
| `execute_terminal_command` | 终端命令 |
| `get_repositories` | Git 仓库列表 |

### 我们的 12 个 PSI 工具（独有价值）

| 工具 | 独有价值 | 与内置重叠度 |
|------|---------|------------|
| `resolve_symbol` | 精确类型 + 声明位置 + SymbolKind | 中（vs `get_symbol_info`，信息角度不同） |
| `find_references` | 统一引用/调用/类型层级查询 | 无 |
| `get_scope` | 位置可用符号（变量/方法/类型） | 无 |
| `checkpoint` | Local History 操作 | 无 |
| `refactor` | 5 种重构（rename/move/extract/safe_delete/change_sig） | 低（内置只有 rename） |
| `query_project` | 自适应全景图 | 低（内置只有 flat 列表） |
| `query_framework` | Room/Retrofit/Hilt/Compose 结构化视图 | 无 |
| `analyze_data_flow` | 值来源/去向/null 状态追踪 | 无 |
| `check_rules` | 架构规则校验 | 无 |
| `structural_search` | AST 模式匹配 | 低（内置只有文本/正则） |
| `analyze_quality` | 项目级 TopN 质量分析 | 低（内置只有单文件） |
| `sandbox` | 执行/反编译/渲染/J2K/批量修复 | 无 |

---

## 15. 现有代码资产盘点

### 15.1 可完全复用（不需修改）

| 文件 | 行数 | 说明 |
|------|------|------|
| `errors/McpErrorCode.kt` | ~20 | 错误码枚举 |
| `formatting/SizePolicy.kt` | ~18 | 返回大小策略 |
| `util/JsonUtils.kt` | ~15 | McpJson 配置 |
| `util/PsiUtils.kt` | ~30 | readAction/smartReadAction |
| `util/ProjectUtils.kt` | ~40 | findFile/toRelativePath/lineColumnToOffset |
| `models/results/*.kt` | ~12 files | 返回数据类（SymbolInfo, ReferenceResult 等） |
| `services/*.kt` | ~12 files | PSI 业务逻辑（核心资产） |

### 15.2 需要微调

| 文件 | 变更 |
|------|------|
| `errors/ToolException.kt` | `toErrorJson()` 输出可能需要适配 `CallToolResult.isError` |
| `formatting/ResponseFormatter.kt` | 返回类型从 `String` 变为包装在 `TextContent` 中 |

### 15.3 需要删除

| 文件 | 原因 |
|------|------|
| `tools/*.kt` (12 个文件) | 旧 `AbstractMcpTool` 适配器，被 `ToolRegistrar` 替代 |

### 15.4 需要新建

| 文件 | 说明 |
|------|------|
| `server/McpServerManager.kt` | MCP Server 生命周期管理 |
| `server/McpServerStartupActivity.kt` | IDE 启动钩子 |
| `server/ToolRegistrar.kt` | 12 个工具注册 + handler |
| `server/ToolSchemas.kt` | 12 个工具的 JSON Schema |
| `util/ProjectResolver.kt` | projectPath → Project 解析 |

### 15.5 `models/args/*.kt` 的处理

这些文件（`ResolveSymbolArgs`, `FindReferencesArgs` 等）原用于 `AbstractMcpTool<Args>` 的参数反序列化。新方案中工具 handler 直接从 `JsonObject` 读取参数。

**两种选择：**
- **保留**: 在 handler 中 `McpJson.decodeFromJsonElement(ArgsClass.serializer(), request.arguments)` 反序列化，代码更清晰
- **删除**: 直接从 `request.arguments` 读取 `jsonPrimitive`，代码更少

**建议：保留**，复用已有的参数验证逻辑。

---

## 16. 输出校验清单

- [ ] `./gradlew clean build buildPlugin` 编译通过
- [ ] 安装到 Android Studio，日志无报错
- [ ] MCP Server 在项目打开后自动启动
- [ ] 端口信息写入 `~/.android-studio-mcp-psi.json`
- [ ] Cursor 配置后能看到 12 个工具列表
- [ ] `resolve_symbol` 端到端调用成功
- [ ] `find_references` 端到端调用成功
- [ ] IDE 关闭后 MCP Server 正确停止
- [ ] 内置 JetBrains MCP Server 仍然正常工作（两者互不干扰）

# Quickstart: Android Studio PSI Tools (MCP)

## Prerequisites

- Android Studio **2025.2.2+**
- JDK 21
- Gradle 9.x

## 1. Clone & Build

```bash
git clone <repo-url>
cd AndroidStudioMCPServer
./gradlew buildPlugin
```

构建产物位于 `build/distributions/AndroidStudioMCPServer-*.zip`。

## 2. Install Plugin

方式 A — 从磁盘安装:
1. 打开 Android Studio → Settings → Plugins → ⚙ → Install Plugin from Disk
2. 选择 `build/distributions/AndroidStudioMCPServer-*.zip`
3. 重启 IDE

方式 B — 开发模式:
```bash
./gradlew runIde
```
这将启动一个带有插件的沙盒 IDE 实例。

## 3. Configure Cursor

插件安装后，打开任意项目时会自动启动独立 MCP Server（默认端口 17532）。

在 Cursor 全局配置 `~/.cursor/mcp.json` 中添加：

```json
{
  "mcpServers": {
    "android-studio-psi": {
      "url": "http://127.0.0.1:17532/mcp"
    }
  }
}
```

端口信息也会写入 `~/.android-studio-mcp-psi.json` 供自动化脚本读取。

> **注意**：本插件的 MCP Server 与 JetBrains 内置 MCP Server 完全独立，两者互不干扰。
> Cursor 会同时连接两个 MCP Server——内置的 21 个基础工具 + 我们的 12 个 PSI 工具。

## 4. Verify Tools

在 Cursor 中输入：

> "列出所有可用的 MCP 工具"

你应该看到 12 个 PSI 分析/重构工具：
- `resolve_symbol` — 精确类型解析
- `find_references` — 引用/调用/类型层级查找
- `get_scope` — 位置可用符号
- `refactor` — 安全重构（rename/move/extract/safe_delete/change_signature）
- `analyze_data_flow` — 空安全/值传播分析
- `query_project` — 项目全景图
- `query_framework` — Room/Retrofit/Hilt/Compose/Navigation 视图
- `analyze_quality` — 代码质量 TopN
- `check_rules` — 架构规则校验
- `structural_search` — AST 模式搜索
- `checkpoint` — Local History 操作
- `sandbox` — 执行/反编译/渲染/J2K/批量修复

## 5. Quick Test

### 解析符号
```
resolve_symbol(file="app/src/main/java/com/example/MainActivity.kt", line=15, column=10)
```

### 查找引用
```
find_references(file="app/src/main/java/com/example/UserRepository.kt", line=10, column=5, mode="USAGES")
```

### 创建安全检查点
```
checkpoint(operation="CREATE", label="before-refactoring")
```

## Architecture

```
Cursor (MCP Client) ←→ Our Plugin (Ktor MCP Server on port 17532)
                        ├── 12 Tool Handlers (ToolRegistrar)
                        ├── Services Layer (PSI Business Logic)
                        └── IntelliJ Platform SDK (PSI/VFS/Index)
```

## Development Workflow

### 添加新工具

1. 在 `models/args/` 中定义 `@Serializable` 参数类
2. 在 `models/results/` 中定义返回结果类
3. 在 `services/` 中实现 PSI 业务逻辑
4. 在 `server/ToolSchemas.kt` 中定义 JSON Schema
5. 在 `server/ToolRegistrar.kt` 中注册工具 handler
6. 运行 `./gradlew test` 验证
7. 运行 `./gradlew runIde` 端到端测试

### 运行测试

```bash
./gradlew test                    # 全部测试
./gradlew test --tests "*ResolveSymbol*"  # 单个工具测试
```

### 项目结构速查

```
src/main/kotlin/com/androidstudio/mcpserver/
├── server/      → MCP Server 管理 + 工具注册（McpServerManager, ToolRegistrar, ToolSchemas）
├── services/    → PSI 业务逻辑（厚层，核心实现）
├── models/      → Args + Results 数据类
├── formatting/  → 返回大小控制
├── errors/      → 统一错误码
└── util/        → ReadAction/WriteAction 辅助 + ProjectResolver
```

## Key Constraints

| 约束 | 说明 |
|------|------|
| PSI 读操作 | 必须在 `PsiUtils.readAction {}` 中执行 |
| PSI 写操作 | 必须在 `WriteCommandAction.runWriteCommandAction {}` 中执行 |
| 索引检查 | 工具调用前检查 `DumbService.isDumb()`，索引中返回 `INDEXING_IN_PROGRESS` |
| 返回大小 | 每个工具有独立上限（200B~16KB），通过 `ResponseFormatter` 自动裁剪 |
| 错误码 | 使用 `McpErrorCode` 枚举，通过 `ToolException` 返回 JSON 错误 |
| 多项目 | 所有工具支持可选 `project_path` 参数，单项目时自动推断 |

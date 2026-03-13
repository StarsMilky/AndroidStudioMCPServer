# Quickstart: Android Studio IDE SDK MCP Tools

## Prerequisites

- Android Studio **2025.2.2+**（内置 MCP Server）
- JDK 21
- Gradle 8.x

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

## 3. Enable MCP Server

1. Settings → Tools → MCP Server → Enable MCP Server
2. 在 Clients Auto-Configuration 中为你的 AI 客户端点击 Auto-Configure
3. 重启 AI 客户端（Cursor / Claude Desktop / VS Code）

## 4. Verify Tools

在 AI 客户端中输入：

> "列出所有可用的 MCP 工具"

你应该看到除内置工具外，还有 12 个新增工具：
- `resolve_symbol`
- `find_references`
- `get_scope`
- `refactor`
- `analyze_data_flow`
- `query_project`
- `query_framework`
- `analyze_quality`
- `check_rules`
- `structural_search`
- `checkpoint`
- `sandbox`

## 5. Quick Test

### 解析符号
让 AI Agent 执行：
```
resolve_symbol(file="app/src/main/java/com/example/MainActivity.kt", line=15, column=10)
```

### 查找引用
```
find_references(file="app/src/main/java/com/example/UserRepository.kt", line=10, column=5, mode="usages")
```

### 创建安全检查点
```
checkpoint(operation="create", label="before-refactoring")
```

## Development Workflow

### 添加新工具

1. 在 `models/args/` 中定义 `@Serializable` 参数类
2. 在 `models/results/` 中定义返回结果类
3. 在 `services/` 中实现 PSI 业务逻辑
4. 在 `tools/` 中创建 `AbstractMcpTool<Args>` 子类
5. 在 `plugin.xml` 中注册 `<mcpServerTool implementation="..."/>`
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
├── tools/       → MCP 工具入口（薄层，≤50 行/类）
├── services/    → PSI 业务逻辑（厚层，核心实现）
├── models/      → Args + Results 数据类
├── formatting/  → 返回大小控制
├── errors/      → 统一错误码
└── util/        → ReadAction/WriteAction 辅助
```

## Key Constraints

| 约束 | 说明 |
|------|------|
| PSI 读操作 | 必须在 `PsiUtils.readAction {}` 中执行 |
| PSI 写操作 | 必须在 `WriteCommandAction.runWriteCommandAction {}` 中执行 |
| 索引检查 | 工具调用前检查 `DumbService.isDumb()`，索引中返回 `INDEXING_IN_PROGRESS` |
| 返回大小 | 每个工具有独立上限（200B~16KB），通过 `ResponseFormatter` 自动裁剪 |
| 错误码 | 使用 `McpErrorCode` 枚举，映射到 JSON-RPC 标准错误格式 |

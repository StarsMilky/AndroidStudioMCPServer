# Android Studio MCP - Code Intelligence

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

> **[English Documentation](README.md)**

一个 Android Studio 插件，通过 [MCP 协议](https://modelcontextprotocol.io/) 向 AI Agent 暴露 **12 个 IDE 级别的代码智能工具**。

让 AI Agent 不再依赖 `grep` 和文件读取来"猜测"代码结构，而是直接使用 Android Studio 内部的语义分析能力——类型解析、引用图谱、调用链、数据流分析和安全重构。

---

## 为什么需要这个插件

| 任务 | 没有 MCP | 有 MCP |
|------|---------|--------|
| "这个变量是什么类型？" | 读整个文件，靠上下文猜 | `resolve_symbol` → 精确限定类型，仅 50 tokens |
| "谁调用了这个方法？" | `grep` → 噪声高、有误报 | `find_references(CALLERS)` → 语义级调用链 |
| "这个参数可为 null 吗？" | CLI 无法判断 | `analyze_data_flow(NULLABILITY)` → PSI 级推理 |
| "安全重命名" | `sed` → 漏改 XML / Manifest | `refactor(RENAME)` → 跨语言更新所有引用 |
| "项目整体架构？" | 手动读 20+ 个文件 | `query_project(OVERVIEW)` → 一次调用获取全貌 |

### 量化评估结果

在一个包含 159 个类（Room + Hilt + Compose）的 Android 项目上自动评估：

- **Token 效率提升 8 倍** — Agent 执行等效任务消耗的 Token 减少 88%
- **信噪比 100%** — 结构化语义数据 vs grep 的 67% 噪声
- **任务完成率 12/12 (100%)** — CLI 仅 8/12（6 个任务不借助 IDE 根本无法完成）
- **安全网** — checkpoint 提供回滚能力，保护高风险代码修改

---

## 12 个工具一览

| 工具 | 功能说明 |
|------|---------|
| `resolve_symbol` | 精确解析任意代码位置的符号类型、种类和限定名 |
| `find_references` | 语义级引用查找、调用方、被调用方、类型层级 |
| `get_scope` | 获取指定位置所有可见符号 |
| `query_project` | 项目全景图、依赖影响分析、API 表面、构建变体 |
| `query_framework` | Room / Retrofit / Hilt / Compose / Navigation 框架专项分析 |
| `analyze_data_flow` | 空安全推理、正向/反向值传播追踪 |
| `analyze_quality` | 圈复杂度热点、死代码、重复代码、异常处理 |
| `check_rules` | 验证自定义架构依赖规则 |
| `structural_search` | IntelliJ SSR 引擎 — 支持模板变量的结构化代码搜索 |
| `refactor` | 重命名、移动、提取、安全删除、修改签名 |
| `checkpoint` | 创建 / 查看 / 回滚 IDE 本地历史快照 |
| `sandbox` | 反编译库类、Java 转 Kotlin、批量代码检查修复 |

---

## 快速开始

### 环境要求

- Android Studio **2025.2**（Ladybug）或更新版本
- JDK 21+

### 构建安装

```bash
git clone https://github.com/StarsMilky/android-studio-mcpserver.git
cd android-studio-mcpserver
./gradlew buildPlugin
```

在 `build/distributions/` 目录下找到生成的 ZIP 文件，通过以下方式安装：

**Android Studio → Settings → Plugins → ⚙️ → Install Plugin from Disk...**

### 连接 Cursor

插件会在 Android Studio 打开项目时自动启动 MCP 服务器，地址为 `http://127.0.0.1:17532/mcp`。

在 `~/.cursor/mcp.json` 中添加：

```json
{
  "mcpServers": {
    "android-studio-code-intel": {
      "url": "http://127.0.0.1:17532/mcp"
    }
  }
}
```

也可以使用 Android Studio 底部「MCP Code Intelligence」面板中的「Configure Cursor」按钮自动配置。

---

## 架构

```
┌─────────────────────────────────────────────────────┐
│                  Cursor / AI Agent                   │
│                (MCP Client, JSON-RPC)                │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP (Streamable MCP)
                       ▼
┌─────────────────────────────────────────────────────┐
│              内嵌 Ktor CIO 服务器                     │
│           (MCP Streamable HTTP 传输层)                │
├─────────────────────────────────────────────────────┤
│                  ToolRegistrar                       │
│           12 工具 × ToolMetricsService               │
├──────────┬──────────┬──────────┬────────────────────┤
│ 符号解析  │ 引用搜索  │ 项目分析  │ 质量 / 规则 /      │
│          │          │          │ 数据流 / SSR /      │
│          │          │          │ 重构 / 沙盒         │
├──────────┴──────────┴──────────┴────────────────────┤
│          IntelliJ Platform PSI + SDK                 │
│     (ReadAction / WriteCommandAction / VFS)          │
└─────────────────────────────────────────────────────┘
```

**核心技术栈：**
- **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`) — MCP 协议实现
- **Ktor CIO** — 内嵌 HTTP 服务器，用于 Streamable MCP 传输
- **IntelliJ PSI** — 语义代码模型（类型、引用、数据流）
- **IntelliJ SSR** — 支持模板变量的结构化搜索
- **kotlinx-serialization** — JSON 序列化

---

## 工具面板

插件在 Android Studio 中添加了「MCP Code Intelligence」工具面板：

- 服务器状态和地址
- Cursor 配置状态
- 每个工具的调用次数、Token 消耗、执行时间
- 实时执行状态指示

---

## 评估框架

项目内置了全自动评估框架 `eval/mcp_evaluator.py`，从 5 个维度对比 MCP 工具与 CLI 等效操作：

| 维度 | MCP | CLI |
|------|-----|-----|
| Token 效率 | 高出 **8 倍** | 基线 |
| 信噪比 | **100%** 结构化数据 | 33%（67% 噪声） |
| 任务完成率 | **12/12** (100%) | 8/12 (67%) |
| 安全性 | checkpoint + 语义重构 | 无 |
| 平均延迟 | 1.2s | ~0ms（本地） |

运行评估：

```bash
python -m venv .venv
.venv/Scripts/pip install requests sseclient-py
.venv/Scripts/python eval/mcp_evaluator.py
```

详细方法论见 [`docs/evaluation-plan.md`](docs/evaluation-plan.md)。

---

## Cursor Skill 文件

项目包含一个即用型 Cursor Skill 文件 `.cursor/skills/android-studio-mcp/SKILL.md`，它会教 AI Agent：

- 何时使用 MCP 工具而非 CLI
- 4 种工作流模式（Bug 调查、功能开发、代码审查、代码库理解）
- 工具链接模式
- 常见错误规避

---

## 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 2.1.20 |
| JVM 目标 | 21 |
| IntelliJ Platform | 252+（Android Studio 2025.2）|
| Kotlin MCP SDK | 0.9.0 |
| Ktor | 3.2.3 |
| kotlinx-serialization | 1.7.3 |

---

## 许可证

[Apache License 2.0](LICENSE)

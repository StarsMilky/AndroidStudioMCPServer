# MCP Code Intelligence

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

> **[中文文档](README_zh.md)**

An IntelliJ Platform plugin that exposes **13 IDE-level code intelligence tools** to AI agents via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/). Works with **Android Studio**, **IntelliJ IDEA**, and other JetBrains IDEs.

Instead of letting AI agents fumble through `grep` and file reads, give them direct access to the same semantic understanding that your IDE uses internally — type resolution, reference graphs, call hierarchies, data flow analysis, and safe refactoring. Every tool response now carries a `nextAction` hint so agents chain calls automatically.

---

## Why This Exists

| Task | Without MCP | With MCP |
|------|-------------|----------|
| "Where is UserRepository?" | `grep` → noisy text matches | `find_symbol(name="UserRepository")` → exact declaration with file+line+column |
| "What type is this?" | Read entire file, guess from context | `resolve_symbol(file, line, col)` → exact qualified type in 50 tokens |
| "Who calls this method?" | `grep` → noisy text matches with false positives | `find_references(qualified_name="com.Foo.bar", mode="CALLERS")` → semantic call chain, no position needed |
| "Is this nullable?" | Impossible via CLI | `analyze_data_flow(NULLABILITY)` → PSI-level inference |
| "Rename safely" | `sed` → misses XML/Manifest references | `refactor(RENAME)` → updates all references across languages |
| "Project architecture?" | Read 20+ files manually | `query_project(OVERVIEW)` → full picture in 1 call |

### Benchmark (single project, for reference only)

Evaluated on one 159-class Android project (Room + Hilt + Compose):

| Dimension | MCP | CLI |
|-----------|-----|-----|
| Token Efficiency | **8x** fewer tokens | Baseline |
| Signal-to-Noise | **100%** structured data | 33% (67% noise) |
| Task Completion | **12/12** | 8/12 (6 impossible without IDE) |
| Safety | Checkpoint + semantic refactor | None |

> *Data from a single project evaluation. Results may vary depending on project size and architecture.*

---

## 13 Tools at a Glance

| Tool | What It Does |
|------|-------------|
| `resolve_symbol` | Resolve symbol at a code position (file+line+column) — returns type, kind, qualified name, declaration |
| `find_symbol` | Locate a symbol by simple or fully-qualified name — replaces `grep` for symbol lookup |
| `find_references` | Semantic usages, callers, callees, type hierarchy — by position or by qualified name |
| `get_scope` | All visible symbols at a given position |
| `query_project` | Project overview, dependency impact, API surface, build variants |
| `query_framework` | Room / Retrofit / Hilt / Compose / Navigation analysis |
| `analyze_data_flow` | Nullability inference, forward/backward value tracing |
| `analyze_quality` | Cyclomatic complexity, dead code, clones, error handling |
| `check_rules` | Validate custom architecture dependency rules |
| `structural_search` | IntelliJ SSR engine — pattern search with template variables |
| `refactor` | Rename, move, extract, safe delete, change signature |
| `checkpoint` | Create / list / rollback IDE local history snapshots |
| `sandbox` | Decompile library classes, Java-to-Kotlin, batch inspections |

> **v2.0 Breaking change:** `resolve_symbol` is now position-only. Migrate name-based lookups to the new `find_symbol` tool.

---

## Getting Started

### Prerequisites

- IntelliJ-based IDE with **platform build 252+** (Android Studio 2025.2+ / IntelliJ IDEA 2025.2+)
- JDK 21+

### Build & Install

```bash
git clone https://github.com/StarsMilky/android-studio-mcpserver.git
cd android-studio-mcpserver
./gradlew buildPlugin
```

Install the generated ZIP from `build/distributions/` via:

**IDE → Settings → Plugins → ⚙️ → Install Plugin from Disk...**

### Connect to Cursor

The plugin auto-starts an MCP server on `http://127.0.0.1:17532/mcp` when the IDE opens a project.

Add to your `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "mcp-code-intel": {
      "url": "http://127.0.0.1:17532/mcp"
    }
  }
}
```

Or use the **"Configure Cursor"** button in the plugin's Tool Window (`MCP Code Intelligence` panel at the bottom of your IDE).

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Cursor / AI Agent                 │
│                  (MCP Client, JSON-RPC)              │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP (Streamable MCP)
                       ▼
┌─────────────────────────────────────────────────────┐
│              Embedded Ktor CIO Server                │
│            (MCP Streamable HTTP Transport)           │
├─────────────────────────────────────────────────────┤
│                  ToolRegistrar                       │
│         13 tools × ToolMetricsService                │
├──────────┬──────────┬──────────┬────────────────────┤
│ Symbol   │Reference │ Project  │ Quality / Rules /   │
│ Resolver │ Searcher │ Analyzer │ DataFlow / SSR /    │
│          │          │          │ Refactor / Sandbox  │
├──────────┴──────────┴──────────┴────────────────────┤
│           IntelliJ Platform PSI + SDK                │
│      (ReadAction / WriteCommandAction / VFS)         │
└─────────────────────────────────────────────────────┘
```

**Key technologies:**
- **Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`) — MCP protocol implementation
- **Ktor CIO** — embedded HTTP server for Streamable MCP transport
- **IntelliJ PSI** — semantic code model (types, references, data flow)
- **IntelliJ SSR** — structural search with template variables
- **kotlinx-serialization** — JSON serialization

---

## Tool Window

The plugin adds an **MCP Code Intelligence** tool window to your IDE:

- Server status and URL
- Cursor configuration status
- Per-tool invocation count, token usage, execution time
- Real-time execution indicator

---

## Cursor Integration

### Skill

A ready-to-use Cursor Skill is included at `.cursor/skills/MCP Code Intelligence/SKILL.md`. It teaches the AI Agent:

- When to use each MCP tool vs CLI
- 4 workflow patterns (bug investigation, feature development, code review, codebase exploration)
- Tool chaining patterns
- Common mistakes to avoid

### MCP-First Rule

An always-on Cursor Rule at `.cursor/rules/mcp-first.mdc` enforces an **MCP-first policy**: the AI agent must prefer MCP tools over CLI equivalents (e.g. `find_references` instead of `grep`, `refactor` instead of `sed`).

---

## Tech Stack

| Component | Version |
|-----------|---------|
| Kotlin | 2.1.20 |
| JVM Target | 21 |
| IntelliJ Platform | 252+ (Android Studio 2025.2+ / IntelliJ IDEA 2025.2+) |
| Kotlin MCP SDK | 0.9.0 |
| Ktor | 3.2.3 |
| kotlinx-serialization | 1.7.3 |

---

## License

[Apache License 2.0](LICENSE)

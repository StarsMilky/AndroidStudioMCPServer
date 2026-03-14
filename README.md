# Android Studio MCP - Code Intelligence

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

> **[中文文档](README_zh.md)**

An Android Studio plugin that exposes **12 IDE-level code intelligence tools** to AI agents via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/).

Instead of letting AI agents fumble through `grep` and file reads, give them direct access to the same semantic understanding that Android Studio uses internally — type resolution, reference graphs, call hierarchies, data flow analysis, and safe refactoring.

---

## Why This Exists

| Task | Without MCP | With MCP |
|------|-------------|----------|
| "What type is this?" | Read entire file, guess from context | `resolve_symbol` → exact qualified type in 50 tokens |
| "Who calls this method?" | `grep` → noisy text matches with false positives | `find_references(CALLERS)` → semantic call chain |
| "Is this nullable?" | Impossible via CLI | `analyze_data_flow(NULLABILITY)` → PSI-level inference |
| "Rename safely" | `sed` → misses XML/Manifest references | `refactor(RENAME)` → updates all references across languages |
| "Project architecture?" | Read 20+ files manually | `query_project(OVERVIEW)` → full picture in 1 call |

### Measured Impact

Automated evaluation on a 159-class Android project with Room, Hilt, and Compose:

- **8x token efficiency** — Agent consumes 88% fewer tokens for equivalent tasks
- **100% signal-to-noise** — structured semantic data vs 67% noise from grep
- **12/12 task completion** — vs 8/12 with CLI (6 tasks are impossible without the IDE)
- **Checkpoint safety net** — rollback capability before risky code changes

---

## 12 Tools at a Glance

| Tool | What It Does |
|------|-------------|
| `resolve_symbol` | Resolve type, kind, and qualified name at any code position |
| `find_references` | Semantic usages, callers, callees, type hierarchy |
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

---

## Getting Started

### Prerequisites

- Android Studio **2025.2** (Ladybug) or later
- JDK 21+

### Build & Install

```bash
git clone https://github.com/StarsMilky/android-studio-mcpserver.git
cd android-studio-mcpserver
./gradlew buildPlugin
```

Install the generated ZIP from `build/distributions/` via:

**Android Studio → Settings → Plugins → ⚙️ → Install Plugin from Disk...**

### Connect to Cursor

The plugin auto-starts an MCP server on `http://127.0.0.1:17532/mcp` when Android Studio opens a project.

Add to your `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "android-studio-code-intel": {
      "url": "http://127.0.0.1:17532/mcp"
    }
  }
}
```

Or use the **"Configure Cursor"** button in the plugin's Tool Window (`MCP Code Intelligence` panel at the bottom of Android Studio).

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
│         12 tools × ToolMetricsService                │
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

The plugin adds an **MCP Code Intelligence** tool window to Android Studio:

- Server status and URL
- Cursor configuration status
- Per-tool invocation count, token usage, execution time
- Real-time execution indicator

---

## Evaluation

An automated evaluation framework is included in `eval/`. It compares MCP tools against equivalent CLI operations across 5 dimensions:

| Dimension | MCP | CLI |
|-----------|-----|-----|
| Token Efficiency | **8.0x** more efficient | Baseline |
| Signal-to-Noise | **100%** structured data | 33% (67% noise) |
| Task Completion | **12/12** (100%) | 8/12 (67%) |
| Safety | Checkpoint + semantic refactor | None |
| Avg Latency | 1.2s | ~0ms (local) |

Run it yourself:

```bash
python -m venv .venv
# Windows
.venv/Scripts/pip install requests sseclient-py
.venv/Scripts/python eval/mcp_evaluator.py
# macOS / Linux
.venv/bin/pip install requests sseclient-py
.venv/bin/python eval/mcp_evaluator.py
```

See [`docs/evaluation-plan.md`](docs/evaluation-plan.md) for methodology details.

---

## Cursor Skill

A ready-to-use Cursor Skill is included at `.cursor/skills/android-studio-mcp/SKILL.md`. It teaches the AI Agent:

- When to use each MCP tool vs CLI
- 4 workflow patterns (bug investigation, feature development, code review, codebase exploration)
- Tool chaining patterns
- Common mistakes to avoid

---

## Tech Stack

| Component | Version |
|-----------|---------|
| Kotlin | 2.1.20 |
| JVM Target | 21 |
| IntelliJ Platform | 252+ (Android Studio 2025.2) |
| Kotlin MCP SDK | 0.9.0 |
| Ktor | 3.2.3 |
| kotlinx-serialization | 1.7.3 |

---

## License

[Apache License 2.0](LICENSE)

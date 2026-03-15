# Changelog

## [1.0.0] — 2026-03-14

### Added

- 12 MCP tools exposing IDE-level code intelligence to AI agents:
  `resolve_symbol`, `find_references`, `get_scope`, `query_project`,
  `query_framework`, `analyze_data_flow`, `analyze_quality`, `check_rules`,
  `structural_search`, `refactor`, `checkpoint`, `sandbox`
- Embedded Ktor CIO HTTP server with Streamable MCP transport
- Built-in Tool Window: server status, Cursor config, per-tool metrics
- One-click Cursor auto-configuration (`~/.cursor/mcp.json`)
- K1 and K2 Kotlin compiler support
- Cursor Skill at `.cursor/skills/android-studio-mcp/SKILL.md`

---
name: android-studio-mcp
description: Enforces using Android Studio MCP tools for code analysis, refactoring, and project understanding instead of CLI commands. Provides a decision tree mapping developer intents to the correct MCP tool. Triggers when working on Android/Kotlin/Java projects with the android-studio-mcp server connected.
---

# Android Studio MCP Tools — Usage Guide

When the `android-studio-mcp` server is connected, you have access to 12 IDE-powered tools that provide capabilities impossible via CLI. **Always prefer these tools over CLI equivalents.**

## Critical Rules

1. **NEVER** use `grep`/`rg` to find code references → use `find_references`
2. **NEVER** guess a variable's type by reading code → use `resolve_symbol`
3. **NEVER** use `sed`/text replace for renaming → use `refactor(action="rename")`
4. **NEVER** manually search for all subclasses/implementations → use `find_references(kind="inheritors")`
5. **NEVER** start modifying code without first calling `checkpoint(action="create")`
6. **ALWAYS** call `query_project(mode="panorama")` as your first action on a new project
7. **ALWAYS** call `resolve_symbol` when unsure about a type — do not guess

## Decision Tree

### "I need to understand this project"

```
First time seeing this project?
  → query_project(mode="panorama")      # complete architecture map

Need framework details (DB tables, API endpoints, DI graph)?
  → query_framework(framework, detail)

Need to know what code quality issues exist?
  → analyze_quality(scope, top_n=10)

Need to check architecture rule violations?
  → check_rules(rules)
```

### "I need to understand this code"

```
What type is this variable/expression?
  → resolve_symbol(file, line, column)

Who uses this class/method/field?
  → find_references(kind="usages")

Who calls this method? (with depth)
  → find_references(kind="callers", depth=N)

What classes implement this interface?
  → find_references(kind="inheritors")

What's the parent class chain?
  → find_references(kind="supers")

What symbols can I use at this code location?
  → get_scope(file, line, column)

Can this variable be null here?
  → analyze_data_flow(query="nullability")

Where does this value come from?
  → analyze_data_flow(query="value_sources", direction="backward")
```

### "I need to modify code"

```
Before ANY multi-file change:
  → checkpoint(action="create", label="before-<description>")

Rename a symbol (class/method/field/variable):
  → refactor(action="rename")            # NOT sed/text-replace

Move a class to another package:
  → refactor(action="move")

Extract a code block into a method:
  → refactor(action="extract_method")

Delete a class/method safely:
  → refactor(action="safe_delete")       # checks for references first

Change a method's parameter list:
  → refactor(action="change_signature")

Something went wrong, need to undo:
  → checkpoint(action="rollback", label="before-<description>")
```

### "I need to find code patterns"

```
Find specific AST pattern (e.g. "all Thread().start() calls"):
  → structural_search(pattern, scope)

Find all classes with a specific annotation:
  → structural_search(pattern="@Entity class $C$")
```

### "I need to run/test/convert something"

```
Run a code snippet to verify behavior:
  → sandbox(action="run_code", code, language)

See a library class's source code:
  → sandbox(action="decompile", qualified_class_name)

Convert Java file to Kotlin:
  → sandbox(action="convert_j2k", file)

Preview an XML layout without device:
  → sandbox(action="render_layout", layout_file)

Batch-apply IDE quick fixes:
  → sandbox(action="batch_fix", scope, inspection_ids)
```

## Tool Reference (12 tools)

| Tool | Purpose | Key params |
|------|---------|-----------|
| `resolve_symbol` | Get exact type at a code position | file, line, column |
| `find_references` | Find usages/callers/callees/inheritors/supers | file, line, col, kind, depth, limit |
| `get_scope` | List available symbols at a position | file, line, col, filter |
| `refactor` | Safe rename/move/extract/delete/change-signature | action, file, line, col, params |
| `analyze_data_flow` | Nullability check, value source/consumer tracing | file, line, col, query, max_steps |
| `query_project` | Project architecture (auto-adapts to project size) | mode: "panorama" or "detail" |
| `query_framework` | Framework-specific views (Room/Retrofit/Hilt/Compose) | framework, detail |
| `analyze_quality` | Top N code quality issues | scope, aspects, top_n |
| `check_rules` | Validate architecture rules | rules[] |
| `structural_search` | AST pattern matching | pattern, scope, limit |
| `checkpoint` | Local History: create/rollback/diff/history | action, label |
| `sandbox` | Run code / decompile / render / convert / batch-fix | action, params |

## Workflow Template

For any non-trivial task, follow this sequence:

```
1. query_project(mode="panorama")        — understand the battlefield
2. resolve_symbol / find_references      — understand the specific area
3. checkpoint(action="create")           — save before changes
4. [make changes using refactor or direct edits]
5. find_references on changed symbols    — verify no breakage
6. checkpoint(action="diff") if needed   — review what you changed
```

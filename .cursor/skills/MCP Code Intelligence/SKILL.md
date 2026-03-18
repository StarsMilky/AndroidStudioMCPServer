---
name: mcp-code-intel
description: >
  Use when working on Android/Kotlin/Java projects with the mcp-code-intel
  MCP server connected. Triggers on: any code
  analysis, refactoring, symbol lookup, reference search, project overview,
  framework query (Room/Retrofit/Hilt/Compose/Navigation), data flow analysis,
  quality check, structural search, checkpoint management, decompile, J2K
  conversion, batch-fix. NEVER use grep/rg for code references, NEVER use
  sed/text-replace for renaming, NEVER guess types. Do NOT use for PDF, image,
  or non-JVM language tasks.
always-apply: false
---

# MCP Code Intelligence

12 IDE-level tools for AI agents. **Always prefer these over CLI equivalents.**

## Quick Reference

| Task | Tool Call |
|------|-----------|
| Find a symbol by name | `resolve_symbol(name="UserRepository", kind="CLASS")` |
| Find a symbol by FQN | `resolve_symbol(name="com.example.UserRepository")` |
| What type is this? | `resolve_symbol(file, line, column)` |
| Who uses this symbol? (by name) | `find_references(qualified_name="com.example.Foo", mode="USAGES")` |
| Who uses this symbol? (by pos) | `find_references(file, line, col, mode="USAGES")` |
| Who calls this method? | `find_references(qualified_name="com.Foo.bar", mode="CALLERS", depth=3)` |
| What does this method call? | `find_references(file, line, col, mode="CALLEES")` |
| Class hierarchy | `find_references(qualified_name="com.Foo", mode="TYPE_HIERARCHY")` |
| Symbols at cursor | `get_scope(file, line, col, filter="ALL")` |
| Project overview | `query_project(mode="OVERVIEW")` |
| Dependency impact | `query_project(mode="DEPENDENCY", target_class="com.Foo")` |
| Framework details | `query_framework(framework="ROOM", detail_target="UserEntity")` |
| Null safety check | `analyze_data_flow(file, line, col, mode="NULLABILITY")` |
| Value tracing | `analyze_data_flow(file, line, col, mode="BACKWARD")` |
| Quality hotspots | `analyze_quality(mode="COMPLEXITY", top_n=10)` |
| Architecture rules | `check_rules(rules=[...])` |
| Pattern search | `structural_search(pattern="@Composable fun $X$")` |
| Rename safely | `refactor(operation="RENAME", file, line, col, new_name="New")` |
| Create checkpoint | `checkpoint(operation="CREATE", label="before-fix")` |
| Decompile class | `sandbox(operation="DECOMPILE", qualified_class_name="...")` |

## Critical Rules

1. **NEVER** use `grep`/`rg` for code references → `find_references(mode="USAGES")`
2. **NEVER** use `grep`/`rg` to locate a symbol → `resolve_symbol(name="SymbolName")`
3. **NEVER** guess types → `resolve_symbol`
4. **NEVER** use `sed`/text-replace for renaming → `refactor(operation="RENAME")`
5. **NEVER** start multi-file changes without → `checkpoint(operation="CREATE")`
6. **ALWAYS** start with `query_project(mode="OVERVIEW")` on a new project
7. **ALWAYS** use `resolve_symbol(name=...)` to find symbol locations instead of grep
8. All enum values are **UPPERCASE**: `"USAGES"`, `"RENAME"`, `"OVERVIEW"`, not `"usages"`
9. `resolve_symbol` and `find_references` accept **two entry modes**: `file+line+column` (position) OR `name`/`qualified_name` (name-based). Use whichever is more convenient.

## Workflows

### Workflow 1: Investigate a Bug

```
1. resolve_symbol(file, line, col)
   → Learn the exact type, qualified name, declaration location
   → Use the qualified name to understand what you're dealing with

2. find_references(mode="CALLERS", depth=2)
   → Trace all callers to understand how the buggy code is reached
   → Output includes file, line, snippet for each caller

3. analyze_data_flow(mode="NULLABILITY")
   → Check if the bug is a null safety issue
   → Output shows nullable/non-null status and the inference chain

4. analyze_data_flow(mode="BACKWARD")
   → Trace where the problematic value originates

5. checkpoint(operation="CREATE", label="before-bugfix")
   → Save state before making changes

6. [Fix the bug]

7. find_references(mode="USAGES") on changed symbols
   → Verify no other code is broken by the fix
```

### Workflow 2: Add a New Feature

```
1. query_project(mode="OVERVIEW")
   → Get project architecture: modules, key classes, frameworks
   → Response adapts to project size: full class map for small, module digest for large

2. query_framework(framework="HILT")
   → Understand DI setup: modules, bindings, scoped components
   → Use detail_target="ModuleName" for deeper inspection

3. find_references(mode="TYPE_HIERARCHY") on related base classes
   → Discover existing implementations to follow the same pattern

4. get_scope(file, line, col)
   → See what's available at the insertion point: variables, methods, types

5. checkpoint(operation="CREATE", label="before-feature-xyz")

6. [Implement feature]

7. refactor(operation="RENAME/MOVE") if needed
   → IDE handles all cross-file updates automatically

8. checkpoint(operation="DIFF", file="...", target_label="before-feature-xyz")
   → Review all changes made
```

### Workflow 3: Code Review / Quality Audit

```
1. analyze_quality(mode="COMPLEXITY", top_n=10)
   → Find the most complex methods (cyclomatic complexity hotspots)

2. analyze_quality(mode="DEAD_CODE")
   → Find unused classes, methods, fields

3. check_rules(rules=[
     {name: "UI must not access DB", source: "com.app.ui", must_not_depend_on: ["com.app.db"]},
     {name: "No Android in domain", source: "com.app.domain", must_not_depend_on: ["android."]}
   ])
   → Verify architecture boundaries

4. structural_search(pattern="Thread().start()", file_type="kotlin")
   → Find anti-patterns across codebase

5. analyze_quality(mode="ERROR_HANDLING")
   → Find empty catch blocks, swallowed exceptions
```

### Workflow 4: Understand Unfamiliar Codebase

```
1. query_project(mode="OVERVIEW")
   → Get modules, class hierarchy, framework summary, key hub classes

2. query_project(mode="API_SURFACE", module="app")
   → See public API of a specific module

3. query_framework(framework="COMPOSE")
   → Map all Composable functions and navigation routes

4. query_framework(framework="ROOM")
   → Map all DB entities, DAOs, queries

5. structural_search(pattern="class $X$ : ViewModel", file_type="kotlin")
   → Find all ViewModels to understand app screens

6. For any symbol you encounter (NO grep needed):
   resolve_symbol(name="SymbolName") → find_references(file, line, col, mode="USAGES")
   OR directly: find_references(qualified_name="com.example.SymbolName", mode="CALLERS")
```

## Tool Details

### resolve_symbol
**Two entry modes:**
- **By position:** `resolve_symbol(file, line, column)` — resolve symbol at exact code position
- **By name:** `resolve_symbol(name="UserRepository")` — find symbol by simple name or FQN

Name-based lookup supports:
- Simple name: `name="UserRepository"` (uses `PsiShortNamesCache` index)
- Fully-qualified name: `name="com.example.data.UserRepository"` (uses `JavaPsiFacade`)
- Member lookup: `name="com.example.UserRepository.findById"`
- Kind filter: `kind="CLASS"` / `"METHOD"` / `"FIELD"` / `"ALL"`

Returns: `qualifiedType`, `declarationFile`, `declarationLine`, `declarationColumn`, `kind` (CLASS/METHOD/FIELD/VARIABLE/PARAMETER/PROPERTY/PACKAGE/OBJECT/ENUM_ENTRY), `totalMatches` (when multiple matches found)

### find_references
**Two entry modes:**
- **By position:** `find_references(file, line, column, mode="USAGES")`
- **By qualified name:** `find_references(qualified_name="com.example.Foo", mode="USAGES")` — no need to know file+line+column

Modes:
- `mode="USAGES"` → all references to the symbol (with file, line, snippet, reference type)
- `mode="CALLERS"` → methods that call this method (with call chain up to `depth` levels)
- `mode="CALLEES"` → methods called inside this method body
- `mode="TYPE_HIERARCHY"` → subtypes and supertypes of this class/interface
- Pagination: `offset` and `limit` for large result sets

### get_scope
- `filter="ALL"` → everything visible at this position
- `filter="VARIABLES"` → local vars, parameters, fields
- `filter="METHODS"` → callable methods (including extensions)
- `filter="TYPES"` → visible classes, interfaces, type aliases

### query_project
- `mode="OVERVIEW"` → adaptive: returns full class map for small projects, module-level digest for large ones. Includes `frameworks` summary and `key_classes` (hub classes with most references)
- `mode="DEPENDENCY"` → transitive impact analysis: given `target_class` and `change_type` (SIGNATURE_CHANGE/BEHAVIOR_CHANGE/DELETE), traces affected classes up to `max_hops`
- `mode="API_SURFACE"` → public classes, methods, fields of a module
- `mode="VARIANT"` → build variants and flavors

### query_framework
Supports: `ROOM` (entities, DAOs, queries), `RETROFIT` (interfaces, endpoints), `HILT` (modules, bindings, components), `COMPOSE` (composables, navigation), `NAVIGATION` (nav graph, destinations)

Use `detail_target` to drill into a specific entity/interface by name.

### analyze_data_flow
- `mode="NULLABILITY"` → null safety analysis at the given position
- `mode="FORWARD"` → trace where a value flows to (consumers)
- `mode="BACKWARD"` → trace where a value comes from (sources)
- `mode="EXTERNAL_ANNOTATIONS"` → query @Nullable/@NonNull annotations on library APIs

### refactor
All operations are **real refactorings** — they update all references across Java/Kotlin/XML/Manifest:
- `RENAME` → requires `new_name`
- `MOVE` → requires `target_package`
- `EXTRACT` → requires `start_line`, `end_line`, `method_name`
- `SAFE_DELETE` → checks for references before deleting
- `CHANGE_SIGNATURE` → `new_parameters[]` and/or `new_return_type`

### checkpoint
- `CREATE` → takes a snapshot of all open files. **Always do this before multi-file changes**
- `HISTORY` → lists all checkpoint labels (optionally filtered by `file`)
- `ROLLBACK` → restores files to a labeled checkpoint state
- `DIFF` → shows what changed between now and a checkpoint label

### structural_search
Uses IntelliJ SSR engine for Kotlin/Java (supports full template variables, type constraints). XML uses tag-name matching.
- Patterns: `class $X$ : Base`, `@Anno fun $X$`, `$T$.start()`, `synchronized ($lock$) { $stmt$; }`
- `file_type`: `kotlin`, `java`, `xml`, `all`
- `scope`: `project` (default) or `module:ModuleName`

### analyze_quality
Modes: `COMPLEXITY` (cyclomatic), `DEAD_CODE`, `CLONES`, `PATTERNS`, `ERROR_HANDLING`
- `scope`: `project` or `module:ModuleName`
- `target`: specific class/package to narrow analysis
- `top_n`: limit results (default 10)

### sandbox
- `DECOMPILE` → view source of compiled/library classes
- `CONVERT_J2K` → Java to Kotlin conversion
- `BATCH_FIX` → apply IDE inspections (e.g., `["UnusedImport", "RedundantVisibilityModifier"]`). Use `dry_run=true` first!

## Tool Chaining Patterns

```
resolve_symbol(name="UserDao") → find_references(file, line, col, mode="CALLERS")
  "Find UserDao by name, then show me all callers. No grep needed."

find_references(qualified_name="com.example.UserDao.findAll", mode="CALLERS")
  "Directly find all callers of a method by qualified name. One call, no grep."

query_project → query_framework → structural_search
  "Project uses Room. Let me find all entities, then search for raw SQL queries."

find_references(CALLERS) → analyze_data_flow(NULLABILITY)
  "This method is called from 5 places. Let me check if any pass null."

checkpoint(CREATE) → refactor(RENAME) → find_references(USAGES) → checkpoint(DIFF)
  "Save state, rename, verify nothing broke, review changes."
```

## Scope Parameter

Many tools accept a `scope` parameter:
- `"project"` → search/analyze the entire project (default)
- `"module:app"` → limit to the `app` module only
- Use module scope for faster results on large projects

## Limitations

- Tools operate on the **currently open project** in Android Studio
- `refactor` modifies the in-memory PSI model; files need IDE save to persist to disk
- `checkpoint ROLLBACK` restores from plugin snapshots, not full IDE Local History
- `structural_search` for XML uses tag-name matching (SSR engine handles Kotlin/Java)
- `sandbox CONVERT_J2K` is a basic structural converter, not the full J2K engine

package com.codeintel.mcpserver.server

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object ToolSchemas {

    private fun stringProp(desc: String) = buildJsonObject {
        put("type", "string")
        put("description", desc)
    }

    private fun intProp(desc: String) = buildJsonObject {
        put("type", "integer")
        put("description", desc)
    }

    private fun boolProp(desc: String) = buildJsonObject {
        put("type", "boolean")
        put("description", desc)
    }

    private fun enumProp(desc: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", desc)
        putJsonArray("enum") { values.forEach { add(it) } }
    }

    val resolveSymbol = ToolSchema(
        properties = buildJsonObject {
            put("file", stringProp("File path (relative to project root). Example: 'app/src/main/java/com/example/Foo.kt'"))
            put("line", intProp("Line number (1-based). Example: 42"))
            put("column", intProp("Column number (1-based). Example: 12"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("file", "line", "column")
    )

    val findSymbol = ToolSchema(
        properties = buildJsonObject {
            put("name", stringProp("Symbol name. Simple ('UserRepository') or fully-qualified ('com.example.UserRepository' / 'com.example.UserRepository.findById')."))
            put("kind", enumProp("Filter by symbol kind (default ALL)", listOf("CLASS", "METHOD", "FIELD", "ALL")))
            put("scope", stringProp("Search scope: 'project' (default) or 'module:<name>'"))
            put("limit", intProp("Max results (default 10)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("name")
    )

    val findReferences = ToolSchema(
        properties = buildJsonObject {
            put("file", stringProp("File path (relative to project root). Required with line+column for position-based lookup."))
            put("line", intProp("Line number (1-based). Required with file+column for position-based lookup."))
            put("column", intProp("Column number (1-based). Required with file+line for position-based lookup."))
            put("qualified_name", stringProp("Fully-qualified symbol name (e.g. 'com.example.UserRepository' or 'com.example.UserRepository.findById'). Alternative to file+line+column."))
            put(
                "mode",
                enumProp(
                    "Query mode",
                    listOf("USAGES", "CALLERS", "CALLEES", "TYPE_HIERARCHY")
                )
            )
            put(
                "scope",
                stringProp("Search scope: project (default) or module:<name>")
            )
            put("depth", intProp("Call hierarchy depth (default 3)"))
            put("offset", intProp("Pagination offset (default 0)"))
            put("limit", intProp("Page size (default 20)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = emptyList()
    )

    val getScope = ToolSchema(
        properties = buildJsonObject {
            put("file", stringProp("File path (relative to project root)"))
            put("line", intProp("Line number (1-based)"))
            put("column", intProp("Column number (1-based)"))
            put("filter", enumProp("Filter type", listOf("ALL", "VARIABLES", "METHODS", "TYPES")))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("file", "line", "column")
    )

    val checkpoint = ToolSchema(
        properties = buildJsonObject {
            put("operation", enumProp("Operation type", listOf("CREATE", "HISTORY", "ROLLBACK", "DIFF")))
            put("label", stringProp("Checkpoint label (used with CREATE/ROLLBACK)"))
            put("file", stringProp("Target file path (used with HISTORY/DIFF)"))
            put("target_label", stringProp("Target checkpoint label (used with DIFF)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("operation")
    )

    val refactor = ToolSchema(
        properties = buildJsonObject {
            put(
                "operation",
                enumProp(
                    "Refactoring type",
                    listOf("RENAME", "MOVE", "EXTRACT", "SAFE_DELETE", "CHANGE_SIGNATURE")
                )
            )
            put("file", stringProp("Target file path"))
            put("line", intProp("Line number (1-based)"))
            put("column", intProp("Column number (1-based)"))
            put("new_name", stringProp("New name (required for RENAME)"))
            put("target_package", stringProp("Target package (required for MOVE)"))
            put("start_line", intProp("Start line for extraction (required for EXTRACT)"))
            put("end_line", intProp("End line for extraction (required for EXTRACT)"))
            put("method_name", stringProp("New method name (required for EXTRACT)"))
            put("new_parameters", buildJsonObject {
                put("type", "array")
                put("description", "New parameter list (used with CHANGE_SIGNATURE)")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("name", stringProp("Parameter name"))
                        put("type", stringProp("Parameter type"))
                        put("default_value", stringProp("Default value (optional)"))
                    }
                }
            })
            put("new_return_type", stringProp("New return type (used with CHANGE_SIGNATURE)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("operation", "file")
    )

    val queryProject = ToolSchema(
        properties = buildJsonObject {
            put(
                "mode",
                enumProp(
                    "Query mode",
                    listOf("OVERVIEW", "DEPENDENCY", "API_SURFACE", "VARIANT")
                )
            )
            put(
                "target_class",
                stringProp("Target class FQN (used with DEPENDENCY)")
            )
            put(
                "change_type",
                enumProp(
                    "Change type",
                    listOf("SIGNATURE_CHANGE", "BEHAVIOR_CHANGE", "DELETE")
                )
            )
            put("max_hops", intProp("Dependency propagation depth (default 3)"))
            put("module", stringProp("Module name (used with VARIANT/API_SURFACE)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = emptyList()
    )

    val queryFramework = ToolSchema(
        properties = buildJsonObject {
            put(
                "framework",
                enumProp(
                    "Framework type",
                    listOf("ROOM", "RETROFIT", "HILT", "COMPOSE", "NAVIGATION")
                )
            )
            put("detail_target", stringProp("Detail target name (e.g. entity or interface name)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("framework")
    )

    val analyzeDataFlow = ToolSchema(
        properties = buildJsonObject {
            put("file", stringProp("File path (relative to project root)"))
            put("line", intProp("Line number (1-based)"))
            put("column", intProp("Column number (1-based)"))
            put(
                "mode",
                enumProp(
                    "Analysis mode",
                    listOf("NULLABILITY", "FORWARD", "BACKWARD", "EXTERNAL_ANNOTATIONS")
                )
            )
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("file", "line", "column")
    )

    val checkRules = ToolSchema(
        properties = buildJsonObject {
            put("rules", buildJsonObject {
                put("type", "array")
                put("description", "Architecture rules list")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("name", stringProp("Rule name"))
                        put("source", stringProp("Source package/module"))
                        put("must_not_depend_on", buildJsonObject {
                            put("type", "array")
                            put("description", "Forbidden dependency packages/modules")
                            putJsonObject("items") { put("type", "string") }
                        })
                    }
                    putJsonArray("required") {
                        add("name")
                        add("source")
                        add("must_not_depend_on")
                    }
                }
            })
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("rules")
    )

    val structuralSearch = ToolSchema(
        properties = buildJsonObject {
            put("pattern", stringProp("IntelliJ SSR search pattern (e.g. \"\$T\$ \$v\$ = new Thread()\")"))
            put("file_type", enumProp("File type", listOf("kotlin", "java", "xml", "all")))
            put("scope", stringProp("Search scope: project (default) or module:<name>"))
            put("type_constraint", stringProp("Type constraint (optional)"))
            put("limit", intProp("Max results (default 20)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("pattern")
    )

    val analyzeQuality = ToolSchema(
        properties = buildJsonObject {
            put(
                "mode",
                enumProp(
                    "Analysis mode",
                    listOf("COMPLEXITY", "DEAD_CODE", "CLONES", "PATTERNS", "ERROR_HANDLING")
                )
            )
            put("scope", stringProp("Analysis scope: project (default) or module:<name>"))
            put("target", stringProp("Target class/package (optional, narrows scope)"))
            put("top_n", intProp("Return top N results (default 10)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("mode")
    )

    val sandbox = ToolSchema(
        properties = buildJsonObject {
            put("operation", enumProp("Sandbox operation", listOf("DECOMPILE", "CONVERT_J2K", "BATCH_FIX")))
            put("timeout", intProp("Timeout in seconds (default 30)"))
            put("qualified_class_name", stringProp("Fully-qualified class name (used with DECOMPILE)"))
            put("java_file", stringProp("Java file path (used with CONVERT_J2K)"))
            put("inspection_scope", stringProp("Inspection scope (used with BATCH_FIX)"))
            put("inspection_ids", buildJsonObject {
                put("type", "array")
                put("description", "Inspection ID list (used with BATCH_FIX)")
                putJsonObject("items") { put("type", "string") }
            })
            put("dry_run", boolProp("Dry run mode (used with BATCH_FIX, default true)"))
            put("project_path", stringProp("Project path (optional, for multi-project setups)"))
        },
        required = listOf("operation")
    )
}

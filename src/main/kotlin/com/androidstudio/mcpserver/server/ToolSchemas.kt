package com.androidstudio.mcpserver.server

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
            put("file", stringProp("文件路径（相对于项目根目录）"))
            put("line", intProp("行号（1-based）"))
            put("column", intProp("列号（1-based）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("file", "line", "column")
    )

    val findReferences = ToolSchema(
        properties = buildJsonObject {
            put("file", stringProp("文件路径（相对于项目根目录）"))
            put("line", intProp("行号（1-based）"))
            put("column", intProp("列号（1-based）"))
            put(
                "mode",
                enumProp(
                    "查询模式",
                    listOf("USAGES", "CALLERS", "CALLEES", "TYPE_HIERARCHY")
                )
            )
            put(
                "scope",
                stringProp("搜索范围：project（默认）或 module:模块名")
            )
            put("depth", intProp("调用层级深度（默认 3）"))
            put("offset", intProp("分页偏移量（默认 0）"))
            put("limit", intProp("分页大小（默认 20）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("file", "line", "column")
    )

    val getScope = ToolSchema(
        properties = buildJsonObject {
            put("file", stringProp("文件路径（相对于项目根目录）"))
            put("line", intProp("行号（1-based）"))
            put("column", intProp("列号（1-based）"))
            put("filter", enumProp("过滤类型", listOf("ALL", "VARIABLES", "METHODS", "TYPES")))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("file", "line", "column")
    )

    val checkpoint = ToolSchema(
        properties = buildJsonObject {
            put("operation", enumProp("操作类型", listOf("CREATE", "HISTORY", "ROLLBACK", "DIFF")))
            put("label", stringProp("检查点标签（CREATE/ROLLBACK 时使用）"))
            put("file", stringProp("目标文件路径（HISTORY/DIFF 时使用）"))
            put("target_label", stringProp("目标检查点标签（DIFF 时使用）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("operation")
    )

    val refactor = ToolSchema(
        properties = buildJsonObject {
            put(
                "operation",
                enumProp(
                    "重构类型",
                    listOf("RENAME", "MOVE", "EXTRACT", "SAFE_DELETE", "CHANGE_SIGNATURE")
                )
            )
            put("file", stringProp("目标文件路径"))
            put("line", intProp("行号（1-based）"))
            put("column", intProp("列号（1-based）"))
            put("new_name", stringProp("新名称（RENAME 时必需）"))
            put("target_package", stringProp("目标包名（MOVE 时必需）"))
            put("start_line", intProp("提取代码起始行（EXTRACT 时必需）"))
            put("end_line", intProp("提取代码结束行（EXTRACT 时必需）"))
            put("method_name", stringProp("新方法名（EXTRACT 时必需）"))
            put("new_parameters", buildJsonObject {
                put("type", "array")
                put("description", "新参数列表（CHANGE_SIGNATURE 时使用）")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("name", stringProp("参数名"))
                        put("type", stringProp("参数类型"))
                        put("default_value", stringProp("默认值（可选）"))
                    }
                }
            })
            put("new_return_type", stringProp("新返回类型（CHANGE_SIGNATURE 时使用）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("operation", "file")
    )

    val queryProject = ToolSchema(
        properties = buildJsonObject {
            put(
                "mode",
                enumProp(
                    "查询模式",
                    listOf("OVERVIEW", "DEPENDENCY", "API_SURFACE", "VARIANT")
                )
            )
            put(
                "target_class",
                stringProp("目标类全限定名（DEPENDENCY 时使用）")
            )
            put(
                "change_type",
                enumProp(
                    "变更类型",
                    listOf("SIGNATURE_CHANGE", "BEHAVIOR_CHANGE", "DELETE")
                )
            )
            put("max_hops", intProp("依赖传播深度（默认 3）"))
            put("module", stringProp("指定模块（VARIANT/API_SURFACE 时使用）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = emptyList()
    )

    val queryFramework = ToolSchema(
        properties = buildJsonObject {
            put(
                "framework",
                enumProp(
                    "框架类型",
                    listOf("ROOM", "RETROFIT", "HILT", "COMPOSE", "NAVIGATION")
                )
            )
            put("detail_target", stringProp("详情目标名称（如实体名、接口名）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("framework")
    )

    val analyzeDataFlow = ToolSchema(
        properties = buildJsonObject {
            put("file", stringProp("文件路径（相对于项目根目录）"))
            put("line", intProp("行号（1-based）"))
            put("column", intProp("列号（1-based）"))
            put(
                "mode",
                enumProp(
                    "分析模式",
                    listOf("NULLABILITY", "FORWARD", "BACKWARD", "EXTERNAL_ANNOTATIONS")
                )
            )
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("file", "line", "column")
    )

    val checkRules = ToolSchema(
        properties = buildJsonObject {
            put("rules", buildJsonObject {
                put("type", "array")
                put("description", "架构规则列表")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("name", stringProp("规则名称"))
                        put("source", stringProp("源包/模块"))
                        put("must_not_depend_on", buildJsonObject {
                            put("type", "array")
                            put("description", "禁止依赖的包/模块列表")
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
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("rules")
    )

    val structuralSearch = ToolSchema(
        properties = buildJsonObject {
            put("pattern", stringProp("IntelliJ SSR 搜索模式（如 \"\$T\$ \$v\$ = new Thread()\"）"))
            put("file_type", enumProp("文件类型", listOf("kotlin", "java", "xml", "all")))
            put("scope", stringProp("搜索范围：project（默认）或 module:模块名"))
            put("type_constraint", stringProp("类型约束（可选）"))
            put("limit", intProp("结果数量上限（默认 20）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("pattern")
    )

    val analyzeQuality = ToolSchema(
        properties = buildJsonObject {
            put(
                "mode",
                enumProp(
                    "分析模式",
                    listOf("COMPLEXITY", "DEAD_CODE", "CLONES", "PATTERNS", "ERROR_HANDLING")
                )
            )
            put("scope", stringProp("分析范围：project（默认）或 module:模块名"))
            put("target", stringProp("目标类/包（可选，缩小范围）"))
            put("top_n", intProp("返回 Top N 结果（默认 10）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("mode")
    )

    val sandbox = ToolSchema(
        properties = buildJsonObject {
            put("operation", enumProp("沙盒操作", listOf("DECOMPILE", "CONVERT_J2K", "BATCH_FIX")))
            put("timeout", intProp("超时秒数（默认 30）"))
            put("qualified_class_name", stringProp("全限定类名（DECOMPILE 时使用）"))
            put("java_file", stringProp("Java 文件路径（CONVERT_J2K 时使用）"))
            put("inspection_scope", stringProp("检查范围（BATCH_FIX 时使用）"))
            put("inspection_ids", buildJsonObject {
                put("type", "array")
                put("description", "Inspection ID 列表（BATCH_FIX 时使用）")
                putJsonObject("items") { put("type", "string") }
            })
            put("dry_run", boolProp("试运行模式（BATCH_FIX 时使用，默认 true）"))
            put("project_path", stringProp("项目路径（多项目时指定，可选）"))
        },
        required = listOf("operation")
    )
}

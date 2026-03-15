package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.args.SandboxArgs
import com.androidstudio.mcpserver.models.args.SandboxOperation
import com.androidstudio.mcpserver.models.results.SandboxResult
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

object SandboxExecutor {
    fun execute(project: Project, args: SandboxArgs): SandboxResult {
        return when (args.operation) {
            SandboxOperation.DECOMPILE -> executeDecompile(project, args)
            SandboxOperation.CONVERT_J2K -> executeConvertJ2K(project, args)
            SandboxOperation.BATCH_FIX -> executeBatchFix(project, args)
        }
    }

    private fun executeDecompile(project: Project, args: SandboxArgs): SandboxResult {
        val className = args.qualifiedClassName ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "qualifiedClassName is required for decompile")
        )

        return PsiUtils.smartReadAction(project) {
            val scope = GlobalSearchScope.allScope(project)
            val psiClass = JavaPsiFacade.getInstance(project).findClass(className, scope)
                ?: throw ToolException(
                    McpErrorCode.SYMBOL_NOT_FOUND,
                    mapOf("class" to className, "reason" to "Class not found")
                )

            val file = psiClass.containingFile
            val sourceCode = if (file != null) {
                val text = file.text
                if (text.length > 10000) {
                    val classText = psiClass.text
                    "// File: ${file.name}\n// Showing class only (file too large)\n\n$classText"
                } else {
                    "// File: ${file.name}\n\n$text"
                }
            } else {
                "// Unable to retrieve source for $className"
            }

            val isFromJar = psiClass.containingFile?.virtualFile?.path?.contains(".jar!") == true
            val warnings = if (isFromJar) {
                listOf("Source retrieved from decompiled .class file in JAR")
            } else null

            SandboxResult(sourceCode = sourceCode, warnings = warnings)
        }
    }

    private fun executeConvertJ2K(project: Project, args: SandboxArgs): SandboxResult {
        val javaFile = args.javaFile ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "javaFile is required for convert_j2k")
        )

        return PsiUtils.smartReadAction(project) {
            val vf = ProjectUtils.findFile(project, javaFile)
            val pf = ProjectUtils.getPsiFile(project, vf) as? PsiJavaFile
                ?: throw ToolException(McpErrorCode.INVALID_SCOPE, mapOf("reason" to "File is not a Java file"))

            val sb = StringBuilder()
            val packageName = pf.packageName
            if (packageName.isNotEmpty()) {
                sb.appendLine("package $packageName")
                sb.appendLine()
            }

            for (importStmt in pf.importList?.importStatements ?: emptyArray()) {
                sb.appendLine("import ${importStmt.qualifiedName}")
            }
            if (pf.importList?.importStatements?.isNotEmpty() == true) sb.appendLine()

            for (cls in pf.classes) {
                convertJavaClassToKotlin(cls, sb, indent = "")
            }

            SandboxResult(
                kotlinCode = sb.toString(),
                warnings = listOf(
                    "This is a basic structural conversion. For production use, use IDE's built-in J2K converter.",
                    "Manual review required for: null safety, property vs field, companion objects, etc."
                )
            )
        }
    }

    private fun convertJavaClassToKotlin(cls: PsiClass, sb: StringBuilder, indent: String) {
        val visibility = when {
            cls.hasModifierProperty(PsiModifier.PUBLIC) -> ""
            cls.hasModifierProperty(PsiModifier.PRIVATE) -> "private "
            cls.hasModifierProperty(PsiModifier.PROTECTED) -> "protected "
            else -> "internal "
        }
        val abstract = if (cls.hasModifierProperty(PsiModifier.ABSTRACT)) "abstract " else ""
        val open = if (!cls.hasModifierProperty(PsiModifier.FINAL) && !cls.isInterface && !cls.isEnum) "open " else ""
        val keyword = when {
            cls.isInterface -> "interface"
            cls.isEnum -> "enum class"
            else -> "${open}${abstract}class"
        }

        val superTypes = mutableListOf<String>()
        cls.superClass?.let {
            if (it.qualifiedName != "java.lang.Object") superTypes.add(it.name ?: "")
        }
        cls.interfaces.forEach { superTypes.add(it.name ?: "") }

        val superClause = if (superTypes.isNotEmpty()) " : ${superTypes.joinToString(", ")}" else ""
        sb.appendLine("${indent}${visibility}$keyword ${cls.name}$superClause {")

        for (field in cls.fields) {
            val fVisibility = when {
                field.hasModifierProperty(PsiModifier.PRIVATE) -> "private "
                field.hasModifierProperty(PsiModifier.PROTECTED) -> "protected "
                else -> ""
            }
            val mutable = if (field.hasModifierProperty(PsiModifier.FINAL)) "val" else "var"
            val type = convertJavaTypeToKotlin(field.type.canonicalText)
            val init = field.initializer?.text?.let { " = $it" } ?: ""
            sb.appendLine("$indent    $fVisibility$mutable ${field.name}: $type$init")
        }
        if (cls.fields.isNotEmpty()) sb.appendLine()

        for (method in cls.methods) {
            if (method.isConstructor) continue
            val mVisibility = when {
                method.hasModifierProperty(PsiModifier.PRIVATE) -> "private "
                method.hasModifierProperty(PsiModifier.PROTECTED) -> "protected "
                else -> ""
            }
            val override = if (method.findSuperMethods().isNotEmpty()) "override " else ""
            val params = method.parameterList.parameters.joinToString(", ") {
                "${it.name}: ${convertJavaTypeToKotlin(it.type.canonicalText)}"
            }
            val returnType = method.returnType?.let { convertJavaTypeToKotlin(it.canonicalText) } ?: "Unit"
            val returnClause = if (returnType == "Unit" || returnType == "void") "" else ": $returnType"
            sb.appendLine("$indent    ${mVisibility}${override}fun ${method.name}($params)$returnClause {")
            val body = method.body
            if (body != null) {
                val bodyText = convertJavaBodyToKotlin(body.text)
                sb.appendLine("$indent        $bodyText")
            } else {
                sb.appendLine("$indent        // TODO: convert body")
            }
            sb.appendLine("$indent    }")
            sb.appendLine()
        }

        sb.appendLine("$indent}")
    }

    private fun convertJavaTypeToKotlin(type: String): String = when (type) {
        "int", "java.lang.Integer" -> "Int"
        "long", "java.lang.Long" -> "Long"
        "double", "java.lang.Double" -> "Double"
        "float", "java.lang.Float" -> "Float"
        "boolean", "java.lang.Boolean" -> "Boolean"
        "byte", "java.lang.Byte" -> "Byte"
        "short", "java.lang.Short" -> "Short"
        "char", "java.lang.Character" -> "Char"
        "void", "java.lang.Void" -> "Unit"
        "java.lang.String" -> "String"
        "java.lang.Object" -> "Any"
        else -> {
            var result = type
                .replace("java.util.List", "List")
                .replace("java.util.Map", "Map")
                .replace("java.util.Set", "Set")
                .replace("java.util.Collection", "Collection")
            result.substringAfterLast(".")
        }
    }

    private fun convertJavaBodyToKotlin(body: String): String {
        var result = body
            .removePrefix("{").removeSuffix("}").trim()
            .replace(Regex("""(\w+)\s+(\w+)\s*=\s*""")) { m ->
                val type = m.groupValues[1]
                val name = m.groupValues[2]
                "val $name: ${convertJavaTypeToKotlin(type)} = "
            }
            .replace("System.out.println(", "println(")
            .replace("System.err.println(", "System.err.println(")
            .replace(Regex("""\((\w+)\)\s*"""), "$1 as ")
            .replace(" != null", " != null")
            .replace(" == null", " == null")
            .replace("null", "null")
        if (result.length > 500) result = result.take(500) + "\n// ... (truncated, manual conversion needed)"
        return result
    }

    private fun executeBatchFix(project: Project, args: SandboxArgs): SandboxResult {
        val checkpointLabel = if (!args.dryRun) {
            CheckpointManager.createAutoCheckpoint(project, "batch_fix")
        } else null

        val inspectionIds = args.inspectionIds ?: listOf("UnusedImport")

        return PsiUtils.smartReadAction(project) {
            val scope = GlobalSearchScope.projectScope(project)
            val ktFiles = com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, "kt", scope)
            val javaFiles = com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, "java", scope)

            var problemsFound = 0
            var problemsFixed = 0
            val unfixable = mutableListOf<com.androidstudio.mcpserver.models.results.UnfixableItem>()

            for (vf in ktFiles + javaFiles) {
                val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
                val relPath = ProjectUtils.toRelativePath(project, vf)

                for (inspectionId in inspectionIds) {
                    when (inspectionId) {
                        "UnusedImport", "unused-import" -> {
                            if (pf is org.jetbrains.kotlin.psi.KtFile) {
                                for (importDir in pf.importDirectives) {
                                    val importedFqn = importDir.importedFqName?.asString() ?: continue
                                    val simpleName = importedFqn.substringAfterLast(".")
                                    val isUsed = pf.declarations.any { decl -> decl.text.contains(simpleName) }
                                    if (!isUsed) {
                                        problemsFound++
                                        if (!args.dryRun) {
                                            try {
                                                WriteCommandAction.runWriteCommandAction(project) { importDir.delete() }
                                                problemsFixed++
                                            } catch (e: Exception) {
                                                unfixable.add(com.androidstudio.mcpserver.models.results.UnfixableItem(
                                                    file = relPath, line = 0,
                                                    reason = "Failed to remove import: ${e.message}"
                                                ))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "RedundantVisibilityModifier", "redundant-visibility" -> {
                            if (pf is org.jetbrains.kotlin.psi.KtFile) {
                                for (decl in PsiTreeUtil.findChildrenOfType(pf, org.jetbrains.kotlin.psi.KtDeclaration::class.java)) {
                                    if (decl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PUBLIC_KEYWORD) &&
                                        decl.parent is org.jetbrains.kotlin.psi.KtClassBody) {
                                        problemsFound++
                                    }
                                }
                            }
                        }
                        "ExplicitThis", "explicit-this" -> {
                            if (pf is org.jetbrains.kotlin.psi.KtFile) {
                                for (expr in PsiTreeUtil.findChildrenOfType(pf, org.jetbrains.kotlin.psi.KtThisExpression::class.java)) {
                                    val parent = expr.parent
                                    if (parent is org.jetbrains.kotlin.psi.KtDotQualifiedExpression) {
                                        problemsFound++
                                    }
                                }
                            }
                        }
                        else -> {
                            unfixable.add(com.androidstudio.mcpserver.models.results.UnfixableItem(
                                file = relPath, line = 0,
                                reason = "Inspection '$inspectionId' is not supported for batch fix"
                            ))
                            break
                        }
                    }
                }
            }

            SandboxResult(
                problemsFound = problemsFound,
                problemsFixed = problemsFixed,
                unfixable = unfixable.ifEmpty { null },
                checkpointLabel = checkpointLabel
            )
        }
    }
}

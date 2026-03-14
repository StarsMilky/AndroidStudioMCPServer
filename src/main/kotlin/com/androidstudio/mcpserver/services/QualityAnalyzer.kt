package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.models.args.AnalyzeQualityArgs
import com.androidstudio.mcpserver.models.args.QualityMode
import com.androidstudio.mcpserver.models.results.QualityIssue
import com.androidstudio.mcpserver.models.results.QualityReport
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

object QualityAnalyzer {
    fun analyze(project: Project, args: AnalyzeQualityArgs): QualityReport {
        return PsiUtils.smartReadAction(project) {
            when (args.mode) {
                QualityMode.COMPLEXITY -> analyzeComplexity(project, args)
                QualityMode.DEAD_CODE -> analyzeDeadCode(project, args)
                QualityMode.CLONES -> analyzeClones(project, args)
                QualityMode.PATTERNS -> analyzePatterns(project, args)
                QualityMode.ERROR_HANDLING -> analyzeErrorHandling(project, args)
            }
        }
    }

    private fun analyzeComplexity(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = mutableListOf<QualityIssue>()
        val scope = resolveScope(project, args)
        val files = collectSourceFiles(project, scope, args.target)

        for (vf in files) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val relPath = ProjectUtils.toRelativePath(project, vf)

            when (pf) {
                is KtFile -> {
                    for (fn in PsiTreeUtil.findChildrenOfType(pf, KtNamedFunction::class.java)) {
                        val complexity = computeKotlinComplexity(fn)
                        if (complexity > 10) {
                            val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                            val line = doc?.getLineNumber(fn.textOffset)?.plus(1) ?: 0
                            val severity = when {
                                complexity > 30 -> "critical"
                                complexity > 20 -> "high"
                                complexity > 15 -> "medium"
                                else -> "low"
                            }
                            issues.add(QualityIssue(
                                type = "high_complexity",
                                severity = severity,
                                file = relPath,
                                line = line,
                                description = "Function '${fn.name}' has cyclomatic complexity of $complexity",
                                suggestion = "Consider extracting helper methods to reduce complexity",
                                metrics = mapOf("cyclomatic_complexity" to complexity.toString(), "lines" to fn.text.lines().size.toString())
                            ))
                        }
                    }
                }
                is PsiJavaFile -> {
                    for (method in PsiTreeUtil.findChildrenOfType(pf, PsiMethod::class.java)) {
                        val complexity = computeJavaComplexity(method)
                        if (complexity > 10) {
                            val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                            val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 0
                            val severity = when {
                                complexity > 30 -> "critical"
                                complexity > 20 -> "high"
                                complexity > 15 -> "medium"
                                else -> "low"
                            }
                            issues.add(QualityIssue(
                                type = "high_complexity",
                                severity = severity,
                                file = relPath,
                                line = line,
                                description = "Method '${method.name}' has cyclomatic complexity of $complexity",
                                suggestion = "Consider refactoring into smaller methods",
                                metrics = mapOf("cyclomatic_complexity" to complexity.toString())
                            ))
                        }
                    }
                }
            }
        }

        issues.sortByDescending { it.metrics?.get("cyclomatic_complexity")?.toIntOrNull() ?: 0 }
        return QualityReport(mode = "complexity", issues = issues.take(args.topN))
    }

    private fun computeKotlinComplexity(fn: KtNamedFunction): Int {
        var complexity = 1
        val body = fn.bodyExpression?.text ?: fn.bodyBlockExpression?.text ?: return 1
        complexity += countOccurrences(body, "\\bif\\b")
        complexity += countOccurrences(body, "\\belse if\\b")
        complexity += countOccurrences(body, "\\bfor\\b")
        complexity += countOccurrences(body, "\\bwhile\\b")
        complexity += countOccurrences(body, "\\bwhen\\b")
        complexity += countOccurrences(body, "->") - countOccurrences(body, "\\bwhen\\b")
        complexity += countOccurrences(body, "\\bcatch\\b")
        complexity += countOccurrences(body, "&&")
        complexity += countOccurrences(body, "\\|\\|")
        complexity += countOccurrences(body, "\\?:")
        return maxOf(1, complexity)
    }

    private fun computeJavaComplexity(method: PsiMethod): Int {
        var complexity = 1
        val body = method.body?.text ?: return 1
        complexity += countOccurrences(body, "\\bif\\b")
        complexity += countOccurrences(body, "\\belse if\\b")
        complexity += countOccurrences(body, "\\bfor\\b")
        complexity += countOccurrences(body, "\\bwhile\\b")
        complexity += countOccurrences(body, "\\bswitch\\b")
        complexity += countOccurrences(body, "\\bcase\\b")
        complexity += countOccurrences(body, "\\bcatch\\b")
        complexity += countOccurrences(body, "&&")
        complexity += countOccurrences(body, "\\|\\|")
        complexity += countOccurrences(body, "\\?")
        return maxOf(1, complexity)
    }

    private fun countOccurrences(text: String, pattern: String): Int {
        return try { Regex(pattern).findAll(text).count() } catch (_: Exception) { 0 }
    }

    private fun analyzeDeadCode(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = mutableListOf<QualityIssue>()
        val scope = resolveScope(project, args)
        val files = collectSourceFiles(project, scope, args.target)

        for (vf in files) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val relPath = ProjectUtils.toRelativePath(project, vf)

            when (pf) {
                is KtFile -> {
                    for (fn in PsiTreeUtil.findChildrenOfType(pf, KtNamedFunction::class.java)) {
                        if (fn.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) && fn.name != null) {
                            val refs = ReferencesSearch.search(fn, GlobalSearchScope.fileScope(pf)).findAll()
                            if (refs.isEmpty()) {
                                val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                                val line = doc?.getLineNumber(fn.textOffset)?.plus(1) ?: 0
                                issues.add(QualityIssue(
                                    type = "unused_function",
                                    severity = "medium",
                                    file = relPath,
                                    line = line,
                                    description = "Private function '${fn.name}' appears unused",
                                    suggestion = "Remove if no longer needed"
                                ))
                            }
                        }
                    }
                    for (prop in PsiTreeUtil.findChildrenOfType(pf, KtProperty::class.java)) {
                        if (prop.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) && prop.name != null &&
                            PsiTreeUtil.getParentOfType(prop, KtClassOrObject::class.java) != null
                        ) {
                            val refs = ReferencesSearch.search(prop, GlobalSearchScope.fileScope(pf)).findAll()
                            if (refs.isEmpty()) {
                                val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                                val line = doc?.getLineNumber(prop.textOffset)?.plus(1) ?: 0
                                issues.add(QualityIssue(
                                    type = "unused_property",
                                    severity = "low",
                                    file = relPath,
                                    line = line,
                                    description = "Private property '${prop.name}' appears unused",
                                    suggestion = "Remove if no longer needed"
                                ))
                            }
                        }
                    }
                }
                is PsiJavaFile -> {
                    for (cls in pf.classes) {
                        for (method in cls.methods) {
                            if (method.hasModifierProperty(PsiModifier.PRIVATE)) {
                                val refs = ReferencesSearch.search(method, GlobalSearchScope.fileScope(pf)).findAll()
                                if (refs.isEmpty()) {
                                    val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                                    val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 0
                                    issues.add(QualityIssue(
                                        type = "unused_method",
                                        severity = "medium",
                                        file = relPath,
                                        line = line,
                                        description = "Private method '${method.name}' appears unused",
                                        suggestion = "Remove if no longer needed"
                                    ))
                                }
                            }
                        }
                        for (field in cls.fields) {
                            if (field.hasModifierProperty(PsiModifier.PRIVATE)) {
                                val refs = ReferencesSearch.search(field, GlobalSearchScope.fileScope(pf)).findAll()
                                if (refs.isEmpty()) {
                                    val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                                    val line = doc?.getLineNumber(field.textOffset)?.plus(1) ?: 0
                                    issues.add(QualityIssue(
                                        type = "unused_field",
                                        severity = "low",
                                        file = relPath,
                                        line = line,
                                        description = "Private field '${field.name}' appears unused",
                                        suggestion = "Remove if no longer needed"
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }

        return QualityReport(mode = "dead_code", issues = issues.take(args.topN))
    }

    private fun analyzeClones(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = mutableListOf<QualityIssue>()
        val scope = resolveScope(project, args)
        val files = collectSourceFiles(project, scope, args.target)

        data class MethodSignature(val paramCount: Int, val lineCount: Int, val bodyHash: Int)
        val methodMap = mutableMapOf<MethodSignature, MutableList<Triple<String, Int, String>>>()

        for (vf in files) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val relPath = ProjectUtils.toRelativePath(project, vf)

            when (pf) {
                is KtFile -> {
                    for (fn in PsiTreeUtil.findChildrenOfType(pf, KtNamedFunction::class.java)) {
                        val body = fn.bodyExpression?.text ?: fn.bodyBlockExpression?.text ?: continue
                        val lines = body.lines().size
                        if (lines < 5) continue
                        val normalized = body.replace(Regex("\\s+"), " ").trim()
                        val sig = MethodSignature(fn.valueParameters.size, lines, normalized.hashCode())
                        val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                        val line = doc?.getLineNumber(fn.textOffset)?.plus(1) ?: 0
                        methodMap.getOrPut(sig) { mutableListOf() }.add(Triple(relPath, line, fn.name ?: "<anon>"))
                    }
                }
                is PsiJavaFile -> {
                    for (method in PsiTreeUtil.findChildrenOfType(pf, PsiMethod::class.java)) {
                        val body = method.body?.text ?: continue
                        val lines = body.lines().size
                        if (lines < 5) continue
                        val normalized = body.replace(Regex("\\s+"), " ").trim()
                        val sig = MethodSignature(method.parameterList.parametersCount, lines, normalized.hashCode())
                        val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                        val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 0
                        methodMap.getOrPut(sig) { mutableListOf() }.add(Triple(relPath, line, method.name))
                    }
                }
            }
        }

        for ((sig, locations) in methodMap) {
            if (locations.size < 2) continue
            val desc = locations.joinToString(", ") { "${it.third} at ${it.first}:${it.second}" }
            issues.add(QualityIssue(
                type = "code_clone",
                severity = if (sig.lineCount > 20) "high" else "medium",
                file = locations.first().first,
                line = locations.first().second,
                description = "Identical method bodies found (${sig.lineCount} lines, ${locations.size} clones): $desc",
                suggestion = "Extract common logic into a shared method",
                metrics = mapOf("clone_count" to locations.size.toString(), "line_count" to sig.lineCount.toString())
            ))
        }

        return QualityReport(mode = "clones", issues = issues.take(args.topN))
    }

    private fun analyzePatterns(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = mutableListOf<QualityIssue>()
        val scope = resolveScope(project, args)
        val files = collectSourceFiles(project, scope, args.target)

        for (vf in files) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val relPath = ProjectUtils.toRelativePath(project, vf)

            when (pf) {
                is KtFile -> {
                    for (cls in PsiTreeUtil.findChildrenOfType(pf, KtClass::class.java)) {
                        val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                        val line = doc?.getLineNumber(cls.textOffset)?.plus(1) ?: 0

                        if (cls.isData()) {
                            val props = cls.primaryConstructorParameters
                            if (props.size > 8) {
                                issues.add(QualityIssue(
                                    type = "too_many_fields",
                                    severity = "medium",
                                    file = relPath,
                                    line = line,
                                    description = "Data class '${cls.name}' has ${props.size} properties",
                                    suggestion = "Consider using Builder pattern or grouping related fields"
                                ))
                            }
                        }

                        val methods = PsiTreeUtil.findChildrenOfType(cls, KtNamedFunction::class.java)
                        if (methods.size > 20) {
                            issues.add(QualityIssue(
                                type = "god_class",
                                severity = "high",
                                file = relPath,
                                line = line,
                                description = "Class '${cls.name}' has ${methods.size} methods (potential God Class)",
                                suggestion = "Consider splitting into smaller classes with single responsibilities",
                                metrics = mapOf("method_count" to methods.size.toString())
                            ))
                        }
                    }
                }
                is PsiJavaFile -> {
                    for (cls in pf.classes) {
                        val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                        val line = doc?.getLineNumber(cls.textOffset)?.plus(1) ?: 0

                        if (cls.methods.size > 20) {
                            issues.add(QualityIssue(
                                type = "god_class",
                                severity = "high",
                                file = relPath,
                                line = line,
                                description = "Class '${cls.name}' has ${cls.methods.size} methods (potential God Class)",
                                suggestion = "Consider splitting into smaller classes",
                                metrics = mapOf("method_count" to cls.methods.size.toString())
                            ))
                        }
                    }
                }
            }
        }

        return QualityReport(mode = "patterns", issues = issues.take(args.topN))
    }

    private fun analyzeErrorHandling(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = mutableListOf<QualityIssue>()
        val scope = resolveScope(project, args)
        val files = collectSourceFiles(project, scope, args.target)

        for (vf in files) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val relPath = ProjectUtils.toRelativePath(project, vf)

            when (pf) {
                is KtFile -> {
                    for (tryExpr in PsiTreeUtil.findChildrenOfType(pf, KtTryExpression::class.java)) {
                        for (catchClause in tryExpr.catchClauses) {
                            val catchBody = catchClause.catchBody?.text?.trim() ?: ""
                            val exceptionType = catchClause.catchParameter?.typeReference?.text ?: "Exception"
                            val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                            val line = doc?.getLineNumber(catchClause.textOffset)?.plus(1) ?: 0

                            if (catchBody.isEmpty() || catchBody == "{}") {
                                issues.add(QualityIssue(
                                    type = "empty_catch",
                                    severity = "high",
                                    file = relPath,
                                    line = line,
                                    description = "Empty catch block for $exceptionType",
                                    suggestion = "Log the exception or handle it properly"
                                ))
                            }
                            if (exceptionType == "Exception" || exceptionType == "Throwable") {
                                issues.add(QualityIssue(
                                    type = "broad_catch",
                                    severity = "medium",
                                    file = relPath,
                                    line = line,
                                    description = "Catching too broad exception type: $exceptionType",
                                    suggestion = "Catch specific exception types"
                                ))
                            }
                        }
                    }
                }
                is PsiJavaFile -> {
                    for (tryStmt in PsiTreeUtil.findChildrenOfType(pf, PsiTryStatement::class.java)) {
                        for (catchSection in tryStmt.catchSections) {
                            val catchBody = catchSection.catchBlock?.text?.trim() ?: ""
                            val catchType = catchSection.catchType?.canonicalText ?: "Exception"
                            val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                            val line = doc?.getLineNumber(catchSection.textOffset)?.plus(1) ?: 0

                            if (catchBody == "{}" || catchBody.isEmpty()) {
                                issues.add(QualityIssue(
                                    type = "empty_catch",
                                    severity = "high",
                                    file = relPath,
                                    line = line,
                                    description = "Empty catch block for $catchType",
                                    suggestion = "Log the exception or handle it properly"
                                ))
                            }
                            if (catchType == "java.lang.Exception" || catchType == "Exception" ||
                                catchType == "java.lang.Throwable" || catchType == "Throwable"
                            ) {
                                issues.add(QualityIssue(
                                    type = "broad_catch",
                                    severity = "medium",
                                    file = relPath,
                                    line = line,
                                    description = "Catching too broad exception type: $catchType",
                                    suggestion = "Catch specific exception types"
                                ))
                            }
                        }
                    }
                }
            }
        }

        return QualityReport(mode = "error_handling", issues = issues.take(args.topN))
    }

    // ==================== Helpers ====================

    private fun resolveScope(project: Project, args: AnalyzeQualityArgs): GlobalSearchScope {
        val scopeStr = args.scope
        if (scopeStr.startsWith("module:")) {
            val moduleName = scopeStr.removePrefix("module:")
            val module = com.intellij.openapi.module.ModuleManager.getInstance(project)
                .modules.firstOrNull { it.name == moduleName }
            if (module != null) {
                return module.moduleScope
            }
        }
        return GlobalSearchScope.projectScope(project)
    }

    private fun collectSourceFiles(
        project: Project,
        scope: GlobalSearchScope,
        target: String?
    ): Collection<com.intellij.openapi.vfs.VirtualFile> {
        val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
        val javaFiles = FilenameIndex.getAllFilesByExt(project, "java", scope)
        val all = ktFiles + javaFiles
        if (target != null) {
            return all.filter { vf ->
                val path = ProjectUtils.toRelativePath(project, vf)
                path.contains(target, ignoreCase = true)
            }
        }
        return all
    }
}

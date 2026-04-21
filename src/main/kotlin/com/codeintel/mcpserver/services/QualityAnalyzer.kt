package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.lang.CloneCandidate
import com.codeintel.mcpserver.lang.LanguageAdapter
import com.codeintel.mcpserver.models.args.AnalyzeQualityArgs
import com.codeintel.mcpserver.models.args.QualityMode
import com.codeintel.mcpserver.models.results.QualityIssue
import com.codeintel.mcpserver.models.results.QualityReport
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

object QualityAnalyzer {
    fun analyze(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val result = PsiUtils.smartReadAction(project) {
            when (args.mode) {
                QualityMode.COMPLEXITY -> analyzeComplexity(project, args)
                QualityMode.DEAD_CODE -> analyzeDeadCode(project, args)
                QualityMode.CLONES -> analyzeClones(project, args)
                QualityMode.PATTERNS -> analyzePatterns(project, args)
                QualityMode.ERROR_HANDLING -> analyzeErrorHandling(project, args)
            }
        }
        val hint = when (args.mode) {
            QualityMode.COMPLEXITY ->
                "💡 Next: pick the hottest method and find_references(mode='CALLERS') to decide safe refactor scope."
            QualityMode.DEAD_CODE ->
                "💡 Next: for each unused symbol, find_references(mode='USAGES') to double-check, then refactor(operation='SAFE_DELETE')."
            QualityMode.CLONES ->
                "💡 Next: use refactor(operation='EXTRACT') on one duplicate to consolidate into a shared function."
            QualityMode.PATTERNS ->
                "💡 Next: use structural_search to locate every instance of a specific anti-pattern."
            QualityMode.ERROR_HANDLING ->
                "💡 Next: inspect each empty catch with resolve_symbol to decide whether to rethrow, log, or handle."
        }
        return result.copy(nextAction = hint)
    }

    private fun analyzeComplexity(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = collectPerFile(project, args) { adapter, pf, relPath ->
            adapter.findComplexityIssues(project, pf, relPath)
        }
        val sorted = issues.sortedByDescending {
            it.metrics?.get("cyclomatic_complexity")?.toIntOrNull() ?: 0
        }
        return QualityReport(mode = "complexity", issues = sorted.take(args.topN))
    }

    private fun analyzeDeadCode(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = collectPerFile(project, args) { adapter, pf, relPath ->
            adapter.findDeadCodeIssues(project, pf, relPath)
        }
        return QualityReport(mode = "dead_code", issues = issues.take(args.topN))
    }

    private fun analyzeClones(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val candidates = mutableListOf<CloneCandidate>()
        forEachFile(project, args) { adapter, pf, relPath ->
            adapter.collectCloneCandidates(project, pf, relPath)?.let { candidates.addAll(it) }
        }

        val grouped = candidates.groupBy { Triple(it.paramCount, it.lineCount, it.bodyHash) }
        val issues = mutableListOf<QualityIssue>()
        for ((key, group) in grouped) {
            if (group.size < 2) continue
            val desc = group.joinToString(", ") { "${it.methodName} at ${it.relPath}:${it.line}" }
            val first = group.first()
            issues.add(QualityIssue(
                type = "code_clone",
                severity = if (key.second > 20) "high" else "medium",
                file = first.relPath,
                line = first.line,
                description = "Identical method bodies found (${key.second} lines, ${group.size} clones): $desc",
                suggestion = "Extract common logic into a shared method",
                metrics = mapOf(
                    "clone_count" to group.size.toString(),
                    "line_count" to key.second.toString()
                )
            ))
        }
        return QualityReport(mode = "clones", issues = issues.take(args.topN))
    }

    private fun analyzePatterns(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = collectPerFile(project, args) { adapter, pf, relPath ->
            adapter.findPatternIssues(project, pf, relPath)
        }
        return QualityReport(mode = "patterns", issues = issues.take(args.topN))
    }

    private fun analyzeErrorHandling(project: Project, args: AnalyzeQualityArgs): QualityReport {
        val issues = collectPerFile(project, args) { adapter, pf, relPath ->
            adapter.findErrorHandlingIssues(project, pf, relPath)
        }
        return QualityReport(mode = "error_handling", issues = issues.take(args.topN))
    }

    // ==================== Helpers ====================

    private fun collectPerFile(
        project: Project,
        args: AnalyzeQualityArgs,
        block: (LanguageAdapter, com.intellij.psi.PsiFile, String) -> List<QualityIssue>?
    ): List<QualityIssue> {
        val issues = mutableListOf<QualityIssue>()
        forEachFile(project, args) { adapter, pf, relPath ->
            block(adapter, pf, relPath)?.let { issues.addAll(it) }
        }
        return issues
    }

    private fun forEachFile(
        project: Project,
        args: AnalyzeQualityArgs,
        action: (LanguageAdapter, com.intellij.psi.PsiFile, String) -> Unit
    ) {
        val scope = resolveScope(project, args)
        val adapters = LanguageAdapter.all(project)
        val files = collectSourceFiles(project, scope, args.target, adapters)
        for (vf in files) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val relPath = ProjectUtils.toRelativePath(project, vf)
            val adapter = adapters.firstOrNull { runCatching { it.canHandle(pf) }.getOrDefault(false) }
                ?: continue
            action(adapter, pf, relPath)
        }
    }

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
        target: String?,
        adapters: List<LanguageAdapter>
    ): Collection<com.intellij.openapi.vfs.VirtualFile> {
        val exts = adapters.flatMap { it.fileExtensions() }.toSet()
            .ifEmpty { setOf("kt", "java") }
        val all = exts.flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
        if (target != null) {
            return all.filter { vf ->
                val path = ProjectUtils.toRelativePath(project, vf)
                path.contains(target, ignoreCase = true)
            }
        }
        return all
    }
}

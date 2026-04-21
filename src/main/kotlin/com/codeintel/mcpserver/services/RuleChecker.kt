package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.lang.LanguageAdapter
import com.codeintel.mcpserver.models.args.CheckRulesArgs
import com.codeintel.mcpserver.models.results.RuleCheckResult
import com.codeintel.mcpserver.models.results.RuleViolation
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

object RuleChecker {
    fun check(project: Project, args: CheckRulesArgs): RuleCheckResult {
        return PsiUtils.smartReadAction(project) {
            val violations = mutableListOf<RuleViolation>()
            val scope = GlobalSearchScope.projectScope(project)
            val adapters = LanguageAdapter.all(project)

            for (rule in args.rules) {
                val sourcePattern = rule.source.replace(".**", "").replace(".*", "")
                val sourceFiles = adapters
                    .flatMap { it.fileExtensions() }
                    .distinct()
                    .flatMap { ext -> FilenameIndex.getAllFilesByExt(project, ext, scope) }

                for (vf in sourceFiles) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: continue
                    val adapter = LanguageAdapter.forFile(psiFile) ?: continue
                    val packageName = adapter.packageName(psiFile) ?: continue
                    if (!matchesGlob(packageName, rule.source)) continue

                    val imports = adapter.importedFqNames(psiFile) ?: emptyList()

                    for (imp in imports) {
                        for (forbidden in rule.mustNotDependOn) {
                            if (matchesGlob(imp, forbidden)) {
                                violations.add(RuleViolation(
                                    rule = rule.name,
                                    violator = packageName,
                                    illegalDependency = imp,
                                    file = vf.path,
                                    line = 1,
                                    suggestion = "Remove dependency on $imp"
                                ))
                            }
                        }
                    }
                }
            }

            val passed = args.rules.size - violations.map { it.rule }.distinct().size
            val failed = violations.map { it.rule }.distinct().size
            val hint = if (failed == 0) {
                "💡 All rules passed. Consider adding more rules to lock in architecture invariants."
            } else {
                "💡 Next: open each violation file, and refactor(operation='MOVE' or 'RENAME') to remove forbidden dependency."
            }
            RuleCheckResult(passed = passed, failed = failed, violations = violations, nextAction = hint)
        }
    }

    private fun matchesGlob(text: String, pattern: String): Boolean =
        text.matches(Regex(pattern.replace(".", "\\.").replace("**", ".*").replace("*", "[^.]*")))
}

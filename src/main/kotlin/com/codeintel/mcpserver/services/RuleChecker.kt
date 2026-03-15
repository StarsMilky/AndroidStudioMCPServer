package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.models.args.CheckRulesArgs
import com.codeintel.mcpserver.models.results.RuleCheckResult
import com.codeintel.mcpserver.models.results.RuleViolation
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

object RuleChecker {
    fun check(project: Project, args: CheckRulesArgs): RuleCheckResult {
        return PsiUtils.smartReadAction(project) {
            val violations = mutableListOf<RuleViolation>()
            val scope = GlobalSearchScope.projectScope(project)

            for (rule in args.rules) {
                val sourcePattern = rule.source.replace(".**", "").replace(".*", "")
                val javaFiles = FilenameIndex.getAllFilesByExt(project, "java", scope)
                val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)

                for (vf in javaFiles + ktFiles) {
                    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: continue
                    val packageName = when (psiFile) {
                        is PsiJavaFile -> psiFile.packageName
                        is org.jetbrains.kotlin.psi.KtFile -> psiFile.packageFqName.asString()
                        else -> continue
                    }
                    if (!matchesGlob(packageName, rule.source)) continue

                    val imports = when (psiFile) {
                        is PsiJavaFile -> psiFile.importList?.importStatements?.mapNotNull { it.qualifiedName } ?: emptyList()
                        is org.jetbrains.kotlin.psi.KtFile -> psiFile.importDirectives.mapNotNull { it.importedFqName?.asString() }
                        else -> emptyList()
                    }

                    for (imp in imports) {
                        for (forbidden in rule.mustNotDependOn) {
                            if (matchesGlob(imp, forbidden)) {
                                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(psiFile)
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
            RuleCheckResult(passed = passed, failed = violations.map { it.rule }.distinct().size, violations = violations)
        }
    }

    private fun matchesGlob(text: String, pattern: String): Boolean =
        text.matches(Regex(pattern.replace(".", "\\.").replace("**", ".*").replace("*", "[^.]*")))
}

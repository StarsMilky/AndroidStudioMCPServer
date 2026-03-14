package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.models.args.StructuralSearchArgs
import com.androidstudio.mcpserver.models.results.SearchMatch
import com.androidstudio.mcpserver.models.results.SearchMatchResult
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.structuralsearch.MatchOptions
import com.intellij.structuralsearch.MatchResult
import com.intellij.structuralsearch.Matcher
import com.intellij.structuralsearch.plugin.util.CollectingMatchResultSink
import org.jetbrains.kotlin.idea.KotlinFileType

object StructuralSearcher {

    private val log = Logger.getInstance(StructuralSearcher::class.java)

    fun search(project: Project, args: StructuralSearchArgs): SearchMatchResult {
        return PsiUtils.smartReadAction(project) {
            val scope = resolveScope(project, args.scope)

            if (args.fileType.lowercase() == "xml") {
                return@smartReadAction searchXmlFallback(project, args, scope)
            }

            try {
                searchWithSsr(project, args, scope)
            } catch (e: Exception) {
                log.warn("SSR engine failed, falling back to custom matcher: ${e.message}", e)
                searchWithCustomMatcher(project, args, scope)
            }
        }
    }

    private fun searchWithSsr(
        project: Project,
        args: StructuralSearchArgs,
        scope: GlobalSearchScope
    ): SearchMatchResult {
        val options = MatchOptions()
        options.searchPattern = args.pattern
        options.scope = scope
        options.isRecursiveSearch = true

        when (args.fileType.lowercase()) {
            "kotlin", "kt" -> options.setFileType(KotlinFileType.INSTANCE)
            "java" -> options.setFileType(JavaFileType.INSTANCE)
            "all" -> options.setFileType(JavaFileType.INSTANCE)
            else -> options.setFileType(KotlinFileType.INSTANCE)
        }

        val sink = CollectingMatchResultSink()
        Matcher(project, options).findMatches(sink)

        val matches = mutableListOf<SearchMatch>()
        for (result in sink.matches) {
            if (matches.size >= args.limit) break
            val matchedElement = result.match ?: continue
            val file = matchedElement.containingFile ?: continue
            val vf = file.virtualFile ?: continue
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: continue
            val line = doc.getLineNumber(matchedElement.textOffset) + 1
            val relPath = ProjectUtils.toRelativePath(project, vf)
            val code = matchedElement.text.lines().firstOrNull()?.take(120) ?: ""
            matches.add(SearchMatch(file = relPath, line = line, matchedCode = code))
        }

        if (args.fileType.lowercase() == "all") {
            val kotlinMatches = try {
                val ktOptions = MatchOptions()
                ktOptions.searchPattern = args.pattern
                ktOptions.scope = scope
                ktOptions.isRecursiveSearch = true
                ktOptions.setFileType(KotlinFileType.INSTANCE)

                val ktSink = CollectingMatchResultSink()
                Matcher(project, ktOptions).findMatches(ktSink)

                ktSink.matches.mapNotNull { result ->
                    if (matches.size >= args.limit) return@mapNotNull null
                    val el = result.match ?: return@mapNotNull null
                    val f = el.containingFile ?: return@mapNotNull null
                    val vf = f.virtualFile ?: return@mapNotNull null
                    val doc = PsiDocumentManager.getInstance(project).getDocument(f) ?: return@mapNotNull null
                    val line = doc.getLineNumber(el.textOffset) + 1
                    SearchMatch(
                        file = ProjectUtils.toRelativePath(project, vf),
                        line = line,
                        matchedCode = el.text.lines().firstOrNull()?.take(120) ?: ""
                    )
                }
            } catch (_: Exception) { emptyList() }

            val xmlMatches = searchXmlFallback(project, args, scope).matches
            matches.addAll(kotlinMatches)
            matches.addAll(xmlMatches)
        }

        return SearchMatchResult(total = matches.size, matches = matches.take(args.limit))
    }

    // Fallback: custom pattern matcher for when SSR is unavailable
    private fun searchWithCustomMatcher(
        project: Project,
        args: StructuralSearchArgs,
        scope: GlobalSearchScope
    ): SearchMatchResult {
        val matches = mutableListOf<SearchMatch>()
        val extensions = when (args.fileType.lowercase()) {
            "kotlin", "kt" -> listOf("kt")
            "java" -> listOf("java")
            "xml" -> listOf("xml")
            "all" -> listOf("kt", "java", "xml")
            else -> listOf("kt", "java")
        }

        for (ext in extensions) {
            val files = FilenameIndex.getAllFilesByExt(project, ext, scope)
            for (vf in files) {
                if (matches.size >= args.limit) break
                val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
                val relPath = ProjectUtils.toRelativePath(project, vf)
                val doc = PsiDocumentManager.getInstance(project).getDocument(pf) ?: continue

                val regex = try {
                    Regex(patternToRegex(args.pattern))
                } catch (_: Exception) { continue }

                for (match in regex.findAll(pf.text)) {
                    if (matches.size >= args.limit) break
                    val line = doc.getLineNumber(match.range.first) + 1
                    matches.add(SearchMatch(file = relPath, line = line, matchedCode = match.value.take(120)))
                }
            }
        }

        return SearchMatchResult(total = matches.size, matches = matches)
    }

    // XML search uses PSI tree walking (SSR doesn't handle XML patterns well)
    private fun searchXmlFallback(
        project: Project,
        args: StructuralSearchArgs,
        scope: GlobalSearchScope
    ): SearchMatchResult {
        val matches = mutableListOf<SearchMatch>()
        val files = FilenameIndex.getAllFilesByExt(project, "xml", scope)

        for (vf in files) {
            if (matches.size >= args.limit) break
            val pf = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
            val doc = PsiDocumentManager.getInstance(project).getDocument(pf) ?: continue
            val relPath = ProjectUtils.toRelativePath(project, vf)

            val tags = PsiTreeUtil.findChildrenOfType(pf, com.intellij.psi.xml.XmlTag::class.java)
            val patternLower = args.pattern.lowercase().removePrefix("<").trim()

            for (tag in tags) {
                if (matches.size >= args.limit) break
                if (tag.name.lowercase().contains(patternLower) ||
                    tag.name.lowercase() == patternLower
                ) {
                    val line = doc.getLineNumber(tag.textOffset) + 1
                    val attrs = tag.attributes.joinToString(" ") { "${it.name}=\"${it.value}\"" }
                    val code = "<${tag.name} $attrs>".take(120)
                    matches.add(SearchMatch(file = relPath, line = line, matchedCode = code))
                }
            }
        }

        return SearchMatchResult(total = matches.size, matches = matches)
    }

    private fun resolveScope(project: Project, scope: String?): GlobalSearchScope {
        if (scope != null && scope.startsWith("module:")) {
            val moduleName = scope.removePrefix("module:")
            val module = com.intellij.openapi.module.ModuleManager.getInstance(project)
                .modules.find { it.name == moduleName }
            if (module != null) {
                return module.moduleScope
            }
        }
        return GlobalSearchScope.projectScope(project)
    }

    private fun patternToRegex(pattern: String): String {
        return pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("*", "\\*")
            .replace("+", "\\+")
            .replace("?", "\\?")
            .replace("|", "\\|")
            .replace("^", "\\^")
            .replace(Regex("""\$\w+\$"""), "\\w+")
    }
}

package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.models.args.StructuralSearchArgs
import com.androidstudio.mcpserver.models.results.SearchMatch
import com.androidstudio.mcpserver.models.results.SearchMatchResult
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

object StructuralSearcher {
    fun search(project: Project, args: StructuralSearchArgs): SearchMatchResult {
        return PsiUtils.smartReadAction(project) {
            val scope = when (args.scope) {
                "project" -> GlobalSearchScope.projectScope(project)
                else -> GlobalSearchScope.projectScope(project)
            }

            val matches = mutableListOf<SearchMatch>()
            val pattern = args.pattern
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

                    val fileMatches = searchInFile(project, pf, pattern, args.typeConstraint)
                    for ((line, code) in fileMatches) {
                        if (matches.size >= args.limit) break
                        matches.add(SearchMatch(file = relPath, line = line, matchedCode = code))
                    }
                }
            }

            SearchMatchResult(total = matches.size, matches = matches)
        }
    }

    private fun searchInFile(
        project: Project,
        psiFile: PsiFile,
        pattern: String,
        typeConstraint: String?
    ): List<Pair<Int, String>> {
        val results = mutableListOf<Pair<Int, String>>()
        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return results

        val matchers = parsePattern(pattern)
        if (matchers.isEmpty()) return results

        when (psiFile) {
            is KtFile -> searchKotlinFile(project, psiFile, doc, matchers, typeConstraint, results)
            is PsiJavaFile -> searchJavaFile(project, psiFile, doc, matchers, typeConstraint, results)
            is com.intellij.psi.xml.XmlFile -> searchXmlFile(project, psiFile, doc, matchers, results)
        }

        return results
    }

    private fun searchKotlinFile(
        project: Project,
        file: KtFile,
        doc: com.intellij.openapi.editor.Document,
        matchers: List<PatternMatcher>,
        typeConstraint: String?,
        results: MutableList<Pair<Int, String>>
    ) {
        for (matcher in matchers) {
            when (matcher) {
                is PatternMatcher.ClassPattern -> {
                    for (cls in PsiTreeUtil.findChildrenOfType(file, KtClass::class.java)) {
                        if (matchesClassPattern(cls.name, matcher.namePattern) &&
                            matchesSuperType(cls, matcher.superType)
                        ) {
                            val line = doc.getLineNumber(cls.textOffset) + 1
                            results.add(line to cls.text.lines().first().take(120))
                        }
                    }
                }
                is PatternMatcher.FunctionPattern -> {
                    for (fn in PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)) {
                        if (matchesFunctionPattern(fn.name, matcher.namePattern) &&
                            matchesAnnotation(fn.annotationEntries, matcher.annotation) &&
                            matchesReturnType(fn.typeReference?.text, typeConstraint)
                        ) {
                            val line = doc.getLineNumber(fn.textOffset) + 1
                            results.add(line to fn.text.lines().first().take(120))
                        }
                    }
                }
                is PatternMatcher.PropertyPattern -> {
                    for (prop in PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java)) {
                        if (matchesName(prop.name, matcher.namePattern) &&
                            matchesAnnotation(prop.annotationEntries, matcher.annotation)
                        ) {
                            val line = doc.getLineNumber(prop.textOffset) + 1
                            results.add(line to prop.text.take(120))
                        }
                    }
                }
                is PatternMatcher.TextPattern -> {
                    val regex = try { Regex(matcher.regex) } catch (_: Exception) { null }
                    if (regex != null) {
                        val text = file.text
                        for (match in regex.findAll(text)) {
                            val line = doc.getLineNumber(match.range.first) + 1
                            results.add(line to match.value.take(120))
                        }
                    }
                }
            }
        }
    }

    private fun searchJavaFile(
        project: Project,
        file: PsiJavaFile,
        doc: com.intellij.openapi.editor.Document,
        matchers: List<PatternMatcher>,
        typeConstraint: String?,
        results: MutableList<Pair<Int, String>>
    ) {
        for (matcher in matchers) {
            when (matcher) {
                is PatternMatcher.ClassPattern -> {
                    for (cls in PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)) {
                        if (matchesClassPattern(cls.name, matcher.namePattern) &&
                            matchesJavaSuperType(cls, matcher.superType)
                        ) {
                            val line = doc.getLineNumber(cls.textOffset) + 1
                            val firstLine = cls.text.lines().first().take(120)
                            results.add(line to firstLine)
                        }
                    }
                }
                is PatternMatcher.FunctionPattern -> {
                    for (method in PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)) {
                        if (matchesFunctionPattern(method.name, matcher.namePattern) &&
                            matchesJavaAnnotation(method.annotations, matcher.annotation) &&
                            matchesReturnType(method.returnType?.canonicalText, typeConstraint)
                        ) {
                            val line = doc.getLineNumber(method.textOffset) + 1
                            val firstLine = method.text.lines().first().take(120)
                            results.add(line to firstLine)
                        }
                    }
                }
                is PatternMatcher.PropertyPattern -> {
                    for (field in PsiTreeUtil.findChildrenOfType(file, PsiField::class.java)) {
                        if (matchesName(field.name, matcher.namePattern) &&
                            matchesJavaAnnotation(field.annotations, matcher.annotation)
                        ) {
                            val line = doc.getLineNumber(field.textOffset) + 1
                            results.add(line to field.text.take(120))
                        }
                    }
                }
                is PatternMatcher.TextPattern -> {
                    val regex = try { Regex(matcher.regex) } catch (_: Exception) { null }
                    if (regex != null) {
                        for (match in regex.findAll(file.text)) {
                            val line = doc.getLineNumber(match.range.first) + 1
                            results.add(line to match.value.take(120))
                        }
                    }
                }
            }
        }
    }

    private fun searchXmlFile(
        project: Project,
        file: com.intellij.psi.xml.XmlFile,
        doc: com.intellij.openapi.editor.Document,
        matchers: List<PatternMatcher>,
        results: MutableList<Pair<Int, String>>
    ) {
        val tags = PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.xml.XmlTag::class.java)
        for (matcher in matchers) {
            when (matcher) {
                is PatternMatcher.ClassPattern -> {
                    for (tag in tags) {
                        val tagName = tag.name
                        if (tagName.contains(matcher.namePattern.replace("\$", "").replace("_", "")) ||
                            matchesClassPattern(tagName.substringAfterLast("."), matcher.namePattern)) {
                            val line = doc.getLineNumber(tag.textOffset) + 1
                            results.add(line to "<${tag.name} ${tag.attributes.joinToString(" ") { "${it.name}=\"${it.value}\"" }}>".take(120))
                        }
                    }
                }
                is PatternMatcher.TextPattern -> {
                    val regex = try { Regex(matcher.regex) } catch (_: Exception) { null }
                    if (regex != null) {
                        for (match in regex.findAll(file.text)) {
                            val line = doc.getLineNumber(match.range.first) + 1
                            results.add(line to match.value.take(120))
                        }
                    }
                }
                else -> {
                    for (tag in tags) {
                        for (attr in tag.attributes) {
                            val attrValue = attr.value ?: continue
                            if (attrValue.contains(matcher.toString(), ignoreCase = true)) {
                                val line = doc.getLineNumber(tag.textOffset) + 1
                                results.add(line to "<${tag.name} ... ${attr.name}=\"${attr.value}\">".take(120))
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== Pattern Parsing ====================

    private sealed class PatternMatcher {
        data class ClassPattern(val namePattern: String, val superType: String?) : PatternMatcher()
        data class FunctionPattern(val namePattern: String, val annotation: String?) : PatternMatcher()
        data class PropertyPattern(val namePattern: String, val annotation: String?) : PatternMatcher()
        data class TextPattern(val regex: String) : PatternMatcher()
    }

    private fun parsePattern(pattern: String): List<PatternMatcher> {
        val trimmed = pattern.trim()
        val classRegex = Regex("""class\s+(\$?\w+\$?)\s*(?::\s*(\w+)|extends\s+(\w+))?""")
        val classMatch = classRegex.find(trimmed)
        if (classMatch != null) {
            val namePattern = classMatch.groupValues[1]
            val superType = classMatch.groupValues[2].ifEmpty { classMatch.groupValues[3].ifEmpty { null } }
            return listOf(PatternMatcher.ClassPattern(namePattern, superType))
        }

        val funRegex = Regex("""(?:fun|function|def)\s+(\$?\w+\$?)\s*\(""")
        val funMatch = funRegex.find(trimmed)
        if (funMatch != null) {
            return listOf(PatternMatcher.FunctionPattern(funMatch.groupValues[1], null))
        }

        val annFunRegex = Regex("""@(\w+)\s+(?:fun|function)\s+(\$?\w+\$?)""")
        val annFunMatch = annFunRegex.find(trimmed)
        if (annFunMatch != null) {
            return listOf(PatternMatcher.FunctionPattern(annFunMatch.groupValues[2], annFunMatch.groupValues[1]))
        }

        val annOnlyRegex = Regex("""^@(\w+)\s*$""")
        val annOnlyMatch = annOnlyRegex.find(trimmed)
        if (annOnlyMatch != null) {
            val ann = annOnlyMatch.groupValues[1]
            return listOf(
                PatternMatcher.FunctionPattern("\$_\$", ann),
                PatternMatcher.PropertyPattern("\$_\$", ann)
            )
        }

        val annRegex = Regex("""@(\w+)""")
        val annMatch = annRegex.find(trimmed)
        if (annMatch != null && !trimmed.contains("class") && !trimmed.contains("fun")) {
            return listOf(
                PatternMatcher.FunctionPattern("\$_\$", annMatch.groupValues[1]),
                PatternMatcher.PropertyPattern("\$_\$", annMatch.groupValues[1])
            )
        }

        val ssrRegex = trimmed.replace("\$\\w+\$".toRegex(), "\\\\w+")
        return listOf(PatternMatcher.TextPattern(ssrRegex))
    }

    // ==================== Matching Helpers ====================

    private fun matchesClassPattern(name: String?, pattern: String): Boolean {
        if (pattern == "\$_\$" || pattern == "\$A\$") return true
        if (name == null) return false
        return name == pattern || Regex(pattern.replace("\$", ".*")).matches(name)
    }

    private fun matchesFunctionPattern(name: String?, pattern: String): Boolean {
        if (pattern == "\$_\$" || pattern == "\$A\$") return true
        if (name == null) return false
        return name == pattern || Regex(pattern.replace("\$", ".*")).matches(name)
    }

    private fun matchesName(name: String?, pattern: String): Boolean {
        if (pattern == "\$_\$" || pattern == "\$A\$") return true
        if (name == null) return false
        return name == pattern
    }

    private fun matchesSuperType(cls: KtClass, superType: String?): Boolean {
        if (superType == null) return true
        return cls.superTypeListEntries.any { entry ->
            entry.text.contains(superType)
        }
    }

    private fun matchesJavaSuperType(cls: PsiClass, superType: String?): Boolean {
        if (superType == null) return true
        return cls.superClass?.name == superType ||
               cls.implementsListTypes.any { it.className == superType }
    }

    private fun matchesAnnotation(annotations: List<KtAnnotationEntry>, annotation: String?): Boolean {
        if (annotation == null) return true
        return annotations.any { it.shortName?.asString() == annotation }
    }

    private fun matchesJavaAnnotation(annotations: Array<PsiAnnotation>, annotation: String?): Boolean {
        if (annotation == null) return true
        return annotations.any { ann ->
            ann.qualifiedName?.substringAfterLast(".") == annotation
        }
    }

    private fun matchesReturnType(returnType: String?, constraint: String?): Boolean {
        if (constraint == null) return true
        if (returnType == null) return false
        return returnType.contains(constraint, ignoreCase = true)
    }
}

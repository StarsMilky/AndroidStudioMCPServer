package com.codeintel.mcpserver.lang

import com.codeintel.mcpserver.models.results.SymbolKind
import com.codeintel.mcpserver.models.results.UsageType
import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtPackageDirective
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * LanguageAdapter for Kotlin. Registered through the `com.codeintel.mcpserver.languageAdapter`
 * extension point from the mcp-kotlin descriptor (requires `org.jetbrains.kotlin`).
 *
 * All Kotlin PSI imports live here so that the core plugin can load without
 * org.jetbrains.kotlin being present (e.g. IDEs that don't ship Kotlin support).
 */
class KotlinLanguageAdapter : LanguageAdapter {
    override val id: String = "KOTLIN"

    override fun canHandle(file: PsiFile): Boolean = file is KtFile

    override fun listTopLevelSymbols(file: PsiFile): List<PsiElement> {
        val kt = file as? KtFile ?: return emptyList()
        return kt.declarations.toList()
    }

    override fun getContainingSymbol(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(
            element,
            KtNamedFunction::class.java,
            KtProperty::class.java,
            KtClassOrObject::class.java
        )

    override fun qualifiedName(element: PsiElement): String? = when (element) {
        is KtClassOrObject -> element.fqName?.asString()
        is KtNamedFunction -> element.fqName?.asString()
        is KtProperty -> element.fqName?.asString()
        is KtNamedDeclaration -> element.fqName?.asString()
        else -> null
    }

    override fun fileExtensions(): List<String> = listOf("kt", "kts")

    override fun packageName(file: PsiFile): String? =
        (file as? KtFile)?.packageFqName?.asString()

    override fun importedFqNames(file: PsiFile): List<String>? {
        val kt = file as? KtFile ?: return null
        return kt.importDirectives.mapNotNull { it.importedFqName?.asString() }
    }

    override fun structuralSearchTarget(): Pair<LanguageFileType, Language> =
        KotlinFileType.INSTANCE to KotlinLanguage.INSTANCE

    override fun findEnclosingPackageDirective(element: PsiElement): Pair<PsiElement, String>? {
        val pkg = PsiTreeUtil.getParentOfType(element, KtPackageDirective::class.java, false) ?: return null
        return pkg to pkg.fqName.asString()
    }

    override fun findEnclosingImportDirective(element: PsiElement): Pair<PsiElement, String>? {
        val imp = PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java, false) ?: return null
        val fqn = imp.importedFqName?.asString() ?: imp.text ?: return null
        return imp to fqn
    }

    override fun classifySymbol(element: PsiElement): SymbolKind? = when (element) {
        is KtClass -> SymbolKind.CLASS
        is KtObjectDeclaration -> SymbolKind.OBJECT
        is KtNamedFunction -> SymbolKind.METHOD
        is KtProperty -> SymbolKind.PROPERTY
        is KtParameter -> SymbolKind.PARAMETER
        else -> null
    }

    override fun qualifiedSignature(element: PsiElement): String? = when (element) {
        is KtClassOrObject -> element.fqName?.asString() ?: element.name
        is KtNamedFunction -> element.fqName?.asString() ?: element.name
        is KtProperty -> element.fqName?.asString() ?: element.name
        is KtParameter -> element.name
        is KtNamedDeclaration -> element.fqName?.asString() ?: element.name
        else -> null
    }

    override fun classifyUsage(element: PsiElement): UsageType? {
        val parent = element.parent ?: return null
        return when {
            parent is KtCallExpression -> UsageType.CALL
            parent is KtBinaryExpression && parent.left == element &&
                parent.operationReference.text == "=" -> UsageType.WRITE
            else -> null
        }
    }

    override fun isMethodLike(element: PsiElement): Boolean = element is KtNamedFunction

    override fun findEnclosingMethod(element: PsiElement): PsiElement? =
        if (element is KtNamedFunction) element
        else PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)

    override fun getMethodBody(method: PsiElement): PsiElement? {
        val fn = method as? KtNamedFunction ?: return null
        return fn.bodyBlockExpression ?: fn.bodyExpression
    }

    override fun resolveCallTarget(element: PsiElement): PsiElement? {
        val call = element as? KtCallExpression ?: return null
        return call.calleeExpression?.references
            ?.firstNotNullOfOrNull { it.resolve() }
            ?: call.references.firstNotNullOfOrNull { it.resolve() }
    }

    override fun asPsiClass(element: PsiElement): PsiClass? = when (element) {
        is KtClass -> element.toLightClass()
        is KtClassOrObject -> element.toLightClass()
        else -> null
    }

    override fun collectBlockLocals(
        element: PsiElement
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val block = element as? org.jetbrains.kotlin.psi.KtBlockExpression ?: return null
        return block.statements
            .filterIsInstance<KtProperty>()
            .filter { it.isLocal }
            .mapNotNull { p ->
                val name = p.name ?: return@mapNotNull null
                com.codeintel.mcpserver.models.results.ScopeSymbol(
                    name = name,
                    type = p.typeReference?.text ?: "Unknown",
                    kind = "variable"
                )
            }
    }

    override fun collectMethodParameters(
        method: PsiElement
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val fn = method as? KtNamedFunction ?: return null
        return fn.valueParameters.mapNotNull { p ->
            val name = p.name ?: return@mapNotNull null
            com.codeintel.mcpserver.models.results.ScopeSymbol(
                name = name,
                type = p.typeReference?.text ?: "Unknown",
                kind = "variable"
            )
        }
    }

    override fun collectClassMembersAt(
        element: PsiElement
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val cls = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java) ?: return null
        val result = mutableListOf<com.codeintel.mcpserver.models.results.ScopeSymbol>()
        cls.declarations.forEach { decl ->
            when (decl) {
                is KtProperty -> decl.name?.let { n ->
                    result.add(com.codeintel.mcpserver.models.results.ScopeSymbol(
                        name = n, type = decl.typeReference?.text ?: "Unknown", kind = "property"))
                }
                is KtNamedFunction -> decl.name?.let { n ->
                    result.add(com.codeintel.mcpserver.models.results.ScopeSymbol(
                        name = n, type = decl.typeReference?.text ?: "Unit", kind = "method"))
                }
            }
        }
        return result
    }

    override fun collectImportedSymbols(
        file: PsiFile
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val kt = file as? KtFile ?: return null
        return kt.importDirectives.mapNotNull { imp ->
            val fq = imp.importedFqName ?: return@mapNotNull null
            com.codeintel.mcpserver.models.results.ScopeSymbol(
                name = fq.shortName().asString(),
                type = fq.asString(),
                kind = "type"
            )
        }
    }

    override fun collectExtensionFunctions(
        file: PsiFile
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val kt = file as? KtFile ?: return null
        return kt.declarations.filterIsInstance<KtNamedFunction>()
            .filter { it.receiverTypeReference != null }
            .mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                com.codeintel.mcpserver.models.results.ScopeSymbol(
                    name = name,
                    type = "${f.receiverTypeReference?.text}.() -> ${f.typeReference?.text ?: "Unit"}",
                    kind = "method"
                )
            }
    }

    override fun setFilePackage(file: PsiFile, targetPackage: String): Boolean {
        val kt = file as? KtFile ?: return false
        val packageDirective = kt.packageDirective ?: return false
        val factory = org.jetbrains.kotlin.psi.KtPsiFactory(file.project)
        val newDirective = factory.createPackageDirective(
            org.jetbrains.kotlin.name.FqName(targetPackage)
        )
        packageDirective.replace(newDirective)
        return true
    }

    override fun listMovableDeclarations(file: PsiFile): List<PsiElement>? =
        (file as? KtFile)?.declarations?.toList()

    override fun changeReturnType(method: PsiElement, newReturnType: String): Boolean {
        val fn = method as? KtNamedFunction ?: return false
        val factory = org.jetbrains.kotlin.psi.KtPsiFactory(fn.project)
        val typeRef = fn.typeReference
        val fragment = factory.createTypeCodeFragment(newReturnType, fn).getContentElement()
            ?: return false
        if (typeRef != null) {
            typeRef.replace(fragment)
        } else {
            fn.setTypeReference(fragment as org.jetbrains.kotlin.psi.KtTypeReference)
        }
        return true
    }

    override fun changeParameters(
        method: PsiElement,
        parameters: List<com.codeintel.mcpserver.models.args.ParameterChange>
    ): Boolean {
        val fn = method as? KtNamedFunction ?: return false
        val factory = org.jetbrains.kotlin.psi.KtPsiFactory(fn.project)
        val paramList = fn.valueParameterList ?: return false
        val newParams = parameters.joinToString(", ") { p ->
            val default = if (p.defaultValue != null) " = ${p.defaultValue}" else ""
            "${p.name}: ${p.type}$default"
        }
        val newFunction = factory.createFunction("fun temp($newParams) {}")
        paramList.replace(newFunction.valueParameterList!!)
        return true
    }

    override fun findContainingClassLike(
        file: PsiFile,
        startOffset: Int,
        endOffset: Int
    ): PsiElement? {
        if (file !is KtFile) return null
        return PsiTreeUtil.findChildrenOfType(file, KtClass::class.java)
            .firstOrNull { it.textRange.startOffset <= startOffset && it.textRange.endOffset >= endOffset }
    }

    override fun extractMethodInClass(
        containingClass: PsiElement,
        methodName: String,
        body: String
    ): Boolean {
        val cls = containingClass as? KtClass ?: return false
        val factory = org.jetbrains.kotlin.psi.KtPsiFactory(cls.project)
        val newMethod = factory.createFunction("private fun $methodName() {\n$body\n}")
        val classBody = cls.body ?: return false
        classBody.addBefore(newMethod, classBody.rBrace)
        classBody.addBefore(factory.createNewLine(), classBody.rBrace)
        return true
    }

    override fun extractMethodCallExpression(
        containingClass: PsiElement,
        methodName: String
    ): String? = if (containingClass is KtClass) "$methodName()" else null

    // -------- Data flow (nullability) --------

    override fun analyzeNullabilityFromCursor(
        project: com.intellij.openapi.project.Project,
        element: PsiElement
    ): com.codeintel.mcpserver.models.results.DataFlowResult? {
        val ktProp = PsiTreeUtil.getParentOfType(element, KtProperty::class.java)
        if (ktProp != null) return analyzeKotlinPropertyNullability(project, ktProp)
        val ktParam = PsiTreeUtil.getParentOfType(element, KtParameter::class.java)
        if (ktParam != null) return analyzeKtParameterNullability(ktParam)
        return null
    }

    override fun analyzeNullabilityOfResolved(
        project: com.intellij.openapi.project.Project,
        resolved: PsiElement
    ): com.codeintel.mcpserver.models.results.DataFlowResult? = when (resolved) {
        is KtProperty -> analyzeKotlinPropertyNullability(project, resolved)
        is KtParameter -> analyzeKtParameterNullability(resolved)
        else -> null
    }

    override fun backwardFlowSteps(
        project: com.intellij.openapi.project.Project,
        target: PsiElement
    ): List<com.codeintel.mcpserver.models.results.FlowStep>? {
        val out = mutableListOf<com.codeintel.mcpserver.models.results.FlowStep>()
        when (target) {
            is KtProperty -> {
                val initializer = target.initializer ?: return emptyList()
                val file = target.containingFile?.virtualFile ?: return emptyList()
                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(target.containingFile)
                val line = doc?.getLineNumber(initializer.textOffset)?.plus(1) ?: 0
                out.add(com.codeintel.mcpserver.models.results.FlowStep(
                    file = com.codeintel.mcpserver.util.ProjectUtils.toRelativePath(project, file),
                    line = line,
                    code = "initializer: ${initializer.text.take(100)}"
                ))
            }
            is KtParameter -> {
                val fn = PsiTreeUtil.getParentOfType(target, KtNamedFunction::class.java) ?: return emptyList()
                val callers = com.intellij.psi.search.searches.ReferencesSearch
                    .search(fn).findAll().take(10)
                for (caller in callers) {
                    val callerFile = caller.element.containingFile?.virtualFile ?: continue
                    val callerDoc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                        .getDocument(caller.element.containingFile) ?: continue
                    val callerLine = callerDoc.getLineNumber(caller.element.textOffset) + 1
                    val lineStart = callerDoc.getLineStartOffset(callerLine - 1)
                    val lineEnd = callerDoc.getLineEndOffset(callerLine - 1)
                    val lineText = callerDoc.text.substring(lineStart, lineEnd).trim()
                    out.add(com.codeintel.mcpserver.models.results.FlowStep(
                        file = com.codeintel.mcpserver.util.ProjectUtils.toRelativePath(project, callerFile),
                        line = callerLine,
                        code = "caller: ${lineText.take(120)}"
                    ))
                }
            }
            else -> return null
        }
        return out
    }

    private fun analyzeKtParameterNullability(
        param: KtParameter
    ): com.codeintel.mcpserver.models.results.DataFlowResult {
        val typeText = param.typeReference?.text ?: "Any"
        val isNullable = typeText.endsWith("?")
        return com.codeintel.mcpserver.models.results.DataFlowResult(
            nullability = if (isNullable) "nullable" else "non_null",
            reason = "Kotlin parameter type: $typeText"
        )
    }

    private fun analyzeKotlinPropertyNullability(
        project: com.intellij.openapi.project.Project,
        prop: KtProperty
    ): com.codeintel.mcpserver.models.results.DataFlowResult {
        val typeText = prop.typeReference?.text
        val initializer = prop.initializer

        val nullPaths = mutableListOf<String>()
        val isExplicitlyNullable = typeText?.endsWith("?") == true

        if (initializer?.text == "null") {
            nullPaths.add("initialized to null at declaration")
        }

        if (prop.isVar) {
            val usages = com.intellij.psi.search.searches.ReferencesSearch.search(prop).findAll()
            for (ref in usages.take(20)) {
                val parent = ref.element.parent
                if (parent is KtBinaryExpression &&
                    parent.operationToken == org.jetbrains.kotlin.lexer.KtTokens.EQ &&
                    parent.right?.text == "null"
                ) {
                    val doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                        .getDocument(ref.element.containingFile)
                    val line = doc?.getLineNumber(ref.element.textOffset)?.plus(1) ?: 0
                    nullPaths.add("assigned null at line $line")
                }
            }
        }

        val delegateText = prop.delegateExpression?.text
        val isLazy = delegateText?.startsWith("lazy") == true
        val isLateinit = prop.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.LATEINIT_KEYWORD)

        val nullability = when {
            isLateinit -> "lateinit (non_null after init, throws before)"
            isLazy -> "non_null (lazy initialized)"
            isExplicitlyNullable -> "nullable"
            typeText != null -> "non_null"
            initializer != null && initializer.text != "null" -> "non_null (inferred)"
            else -> "unknown"
        }

        val inferredType = typeText
            ?: (if (initializer != null) "inferred from: ${initializer.text.take(50)}" else "unknown")

        return com.codeintel.mcpserver.models.results.DataFlowResult(
            nullability = nullability,
            reason = "Kotlin property type: $inferredType, var=${prop.isVar}",
            nullPaths = nullPaths.ifEmpty { null }
        )
    }

    // -------- Quality --------

    override fun findComplexityIssues(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<com.codeintel.mcpserver.models.results.QualityIssue>? {
        if (file !is KtFile) return null
        val issues = mutableListOf<com.codeintel.mcpserver.models.results.QualityIssue>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        for (fn in PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)) {
            val complexity = computeKotlinComplexity(fn)
            if (complexity <= 10) continue
            val line = doc?.getLineNumber(fn.textOffset)?.plus(1) ?: 0
            val severity = when {
                complexity > 30 -> "critical"
                complexity > 20 -> "high"
                complexity > 15 -> "medium"
                else -> "low"
            }
            issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                type = "high_complexity",
                severity = severity,
                file = relPath,
                line = line,
                description = "Function '${fn.name}' has cyclomatic complexity of $complexity",
                suggestion = "Consider extracting helper methods to reduce complexity",
                metrics = mapOf(
                    "cyclomatic_complexity" to complexity.toString(),
                    "lines" to fn.text.lines().size.toString()
                )
            ))
        }
        return issues
    }

    override fun findDeadCodeIssues(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<com.codeintel.mcpserver.models.results.QualityIssue>? {
        if (file !is KtFile) return null
        val issues = mutableListOf<com.codeintel.mcpserver.models.results.QualityIssue>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        val scope = com.intellij.psi.search.GlobalSearchScope.fileScope(file)
        for (fn in PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)) {
            if (!fn.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) || fn.name == null) continue
            val refs = com.intellij.psi.search.searches.ReferencesSearch.search(fn, scope).findAll()
            if (refs.isNotEmpty()) continue
            val line = doc?.getLineNumber(fn.textOffset)?.plus(1) ?: 0
            issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                type = "unused_function", severity = "medium",
                file = relPath, line = line,
                description = "Private function '${fn.name}' appears unused",
                suggestion = "Remove if no longer needed"
            ))
        }
        for (prop in PsiTreeUtil.findChildrenOfType(file, KtProperty::class.java)) {
            if (!prop.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) ||
                prop.name == null ||
                PsiTreeUtil.getParentOfType(prop, KtClassOrObject::class.java) == null
            ) continue
            val refs = com.intellij.psi.search.searches.ReferencesSearch.search(prop, scope).findAll()
            if (refs.isNotEmpty()) continue
            val line = doc?.getLineNumber(prop.textOffset)?.plus(1) ?: 0
            issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                type = "unused_property", severity = "low",
                file = relPath, line = line,
                description = "Private property '${prop.name}' appears unused",
                suggestion = "Remove if no longer needed"
            ))
        }
        return issues
    }

    override fun collectCloneCandidates(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<CloneCandidate>? {
        if (file !is KtFile) return null
        val out = mutableListOf<CloneCandidate>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        for (fn in PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)) {
            val body = fn.bodyExpression?.text ?: fn.bodyBlockExpression?.text ?: continue
            val lines = body.lines().size
            if (lines < 5) continue
            val normalized = body.replace(Regex("\\s+"), " ").trim()
            val line = doc?.getLineNumber(fn.textOffset)?.plus(1) ?: 0
            out.add(CloneCandidate(
                relPath = relPath,
                line = line,
                methodName = fn.name ?: "<anon>",
                paramCount = fn.valueParameters.size,
                lineCount = lines,
                bodyHash = normalized.hashCode()
            ))
        }
        return out
    }

    override fun findPatternIssues(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<com.codeintel.mcpserver.models.results.QualityIssue>? {
        if (file !is KtFile) return null
        val issues = mutableListOf<com.codeintel.mcpserver.models.results.QualityIssue>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        for (cls in PsiTreeUtil.findChildrenOfType(file, KtClass::class.java)) {
            val line = doc?.getLineNumber(cls.textOffset)?.plus(1) ?: 0
            if (cls.isData()) {
                val props = cls.primaryConstructorParameters
                if (props.size > 8) {
                    issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                        type = "too_many_fields", severity = "medium",
                        file = relPath, line = line,
                        description = "Data class '${cls.name}' has ${props.size} properties",
                        suggestion = "Consider using Builder pattern or grouping related fields"
                    ))
                }
            }
            val methods = PsiTreeUtil.findChildrenOfType(cls, KtNamedFunction::class.java)
            if (methods.size > 20) {
                issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                    type = "god_class", severity = "high",
                    file = relPath, line = line,
                    description = "Class '${cls.name}' has ${methods.size} methods (potential God Class)",
                    suggestion = "Consider splitting into smaller classes with single responsibilities",
                    metrics = mapOf("method_count" to methods.size.toString())
                ))
            }
        }
        return issues
    }

    override fun findErrorHandlingIssues(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<com.codeintel.mcpserver.models.results.QualityIssue>? {
        if (file !is KtFile) return null
        val issues = mutableListOf<com.codeintel.mcpserver.models.results.QualityIssue>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        for (tryExpr in PsiTreeUtil.findChildrenOfType(file, org.jetbrains.kotlin.psi.KtTryExpression::class.java)) {
            for (catchClause in tryExpr.catchClauses) {
                val catchBody = catchClause.catchBody?.text?.trim() ?: ""
                val exceptionType = catchClause.catchParameter?.typeReference?.text ?: "Exception"
                val line = doc?.getLineNumber(catchClause.textOffset)?.plus(1) ?: 0
                if (catchBody.isEmpty() || catchBody == "{}") {
                    issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                        type = "empty_catch", severity = "high",
                        file = relPath, line = line,
                        description = "Empty catch block for $exceptionType",
                        suggestion = "Log the exception or handle it properly"
                    ))
                }
                if (exceptionType == "Exception" || exceptionType == "Throwable") {
                    issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                        type = "broad_catch", severity = "medium",
                        file = relPath, line = line,
                        description = "Catching too broad exception type: $exceptionType",
                        suggestion = "Catch specific exception types"
                    ))
                }
            }
        }
        return issues
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

    private fun countOccurrences(text: String, pattern: String): Int =
        try { Regex(pattern).findAll(text).count() } catch (_: Exception) { 0 }
}

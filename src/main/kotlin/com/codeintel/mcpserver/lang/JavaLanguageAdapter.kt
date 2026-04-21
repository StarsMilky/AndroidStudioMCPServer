package com.codeintel.mcpserver.lang

import com.codeintel.mcpserver.models.results.SymbolKind
import com.codeintel.mcpserver.models.results.UsageType
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * LanguageAdapter for Java. Registered through the `com.codeintel.mcpserver.languageAdapter`
 * extension point from the mcp-java descriptor (requires the Java IDE module).
 *
 * All PSI access here must stay behind the `com.intellij.java` optional dependency
 * so that IDEs without Java support (pure Python / Go / Web IDEs) can still load
 * the core plugin.
 */
class JavaLanguageAdapter : LanguageAdapter {
    override val id: String = "JAVA"

    override fun canHandle(file: PsiFile): Boolean = file is PsiJavaFile

    override fun listTopLevelSymbols(file: PsiFile): List<PsiElement> =
        (file as? PsiJavaFile)?.classes?.toList() ?: emptyList()

    override fun getContainingSymbol(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, PsiMember::class.java, PsiClass::class.java)

    override fun qualifiedName(element: PsiElement): String? = when (element) {
        is PsiClass -> element.qualifiedName
        is PsiMember -> {
            val owner = element.containingClass?.qualifiedName
            val name = element.name
            if (owner != null && name != null) "$owner.$name" else null
        }
        else -> null
    }

    override fun fileExtensions(): List<String> = listOf("java")

    override fun packageName(file: PsiFile): String? =
        (file as? PsiJavaFile)?.packageName

    override fun importedFqNames(file: PsiFile): List<String>? {
        val pf = file as? PsiJavaFile ?: return null
        return pf.importList?.importStatements?.mapNotNull { it.qualifiedName } ?: emptyList()
    }

    override fun structuralSearchTarget(): Pair<LanguageFileType, Language> =
        JavaFileType.INSTANCE to JavaLanguage.INSTANCE

    override fun findEnclosingPackageDirective(element: PsiElement): Pair<PsiElement, String>? {
        val pkg = PsiTreeUtil.getParentOfType(element, PsiPackageStatement::class.java, false) ?: return null
        return pkg to pkg.packageName
    }

    override fun findEnclosingImportDirective(element: PsiElement): Pair<PsiElement, String>? {
        val imp = PsiTreeUtil.getParentOfType(element, PsiImportStatement::class.java, false) ?: return null
        val fqn = imp.qualifiedName ?: imp.text ?: return null
        return imp to fqn
    }

    override fun classifySymbol(element: PsiElement): SymbolKind? = when (element) {
        is PsiClass -> if (element.isEnum) SymbolKind.ENUM_ENTRY else SymbolKind.CLASS
        is PsiMethod -> SymbolKind.METHOD
        is PsiField -> SymbolKind.FIELD
        is PsiParameter -> SymbolKind.PARAMETER
        is PsiLocalVariable -> SymbolKind.VARIABLE
        is PsiPackage -> SymbolKind.PACKAGE
        else -> null
    }

    override fun qualifiedSignature(element: PsiElement): String? = when (element) {
        is PsiClass -> element.qualifiedName ?: element.name
        is PsiMethod -> {
            val owner = element.containingClass?.qualifiedName ?: ""
            "$owner.${element.name}"
        }
        is PsiField -> {
            val owner = element.containingClass?.qualifiedName ?: ""
            "$owner.${element.name}: ${element.type.canonicalText}"
        }
        is PsiParameter -> "${element.name}: ${element.type.canonicalText}"
        is PsiVariable -> "${element.name}: ${element.type.canonicalText}"
        is PsiPackage -> element.qualifiedName
        else -> null
    }

    override fun classifyUsage(element: PsiElement): UsageType? {
        val parent = element.parent ?: return null
        return when {
            parent is PsiMethodCallExpression -> UsageType.CALL
            parent is PsiReferenceExpression &&
                parent.parent is PsiMethodCallExpression &&
                (parent.parent as PsiMethodCallExpression).methodExpression === parent -> UsageType.CALL
            parent is PsiMethod && PsiTreeUtil.isAncestor(parent, element, true) -> UsageType.OVERRIDE
            parent is PsiAssignmentExpression && parent.lExpression == element -> UsageType.WRITE
            else -> null
        }
    }

    override fun isMethodLike(element: PsiElement): Boolean = element is PsiMethod

    override fun findEnclosingMethod(element: PsiElement): PsiElement? =
        if (element is PsiMethod) element
        else PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)

    override fun getMethodBody(method: PsiElement): PsiElement? =
        (method as? PsiMethod)?.body

    override fun resolveCallTarget(element: PsiElement): PsiElement? {
        val call = element as? PsiMethodCallExpression ?: return null
        return call.resolveMethod()
    }

    override fun asPsiClass(element: PsiElement): PsiClass? = element as? PsiClass

    override fun collectBlockLocals(
        element: PsiElement
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val block = element as? com.intellij.psi.PsiCodeBlock ?: return null
        return block.statements
            .filterIsInstance<com.intellij.psi.PsiDeclarationStatement>()
            .flatMap { decl ->
                decl.declaredElements.filterIsInstance<PsiLocalVariable>().map { v ->
                    com.codeintel.mcpserver.models.results.ScopeSymbol(
                        name = v.name,
                        type = v.type.canonicalText,
                        kind = "variable"
                    )
                }
            }
    }

    override fun collectMethodParameters(
        method: PsiElement
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val m = method as? PsiMethod ?: return null
        return m.parameterList.parameters.map { p ->
            com.codeintel.mcpserver.models.results.ScopeSymbol(
                name = p.name,
                type = p.type.canonicalText,
                kind = "variable"
            )
        }
    }

    override fun collectClassMembersAt(
        element: PsiElement
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val cls = PsiTreeUtil.getParentOfType(element, PsiClass::class.java) ?: return null
        val result = mutableListOf<com.codeintel.mcpserver.models.results.ScopeSymbol>()
        cls.fields.forEach { f ->
            result.add(com.codeintel.mcpserver.models.results.ScopeSymbol(
                name = f.name, type = f.type.canonicalText, kind = "property"))
        }
        cls.methods.forEach { m ->
            result.add(com.codeintel.mcpserver.models.results.ScopeSymbol(
                name = m.name, type = m.returnType?.canonicalText ?: "void", kind = "method"))
        }
        return result
    }

    override fun collectImportedSymbols(
        file: PsiFile
    ): List<com.codeintel.mcpserver.models.results.ScopeSymbol>? {
        val pf = file as? PsiJavaFile ?: return null
        return pf.importList?.importStatements?.mapNotNull { imp ->
            val qn = imp.qualifiedName ?: return@mapNotNull null
            com.codeintel.mcpserver.models.results.ScopeSymbol(
                name = qn.substringAfterLast('.'),
                type = qn,
                kind = "type"
            )
        } ?: emptyList()
    }

    override fun setFilePackage(file: PsiFile, targetPackage: String): Boolean {
        val pf = file as? PsiJavaFile ?: return false
        val pkg = pf.packageStatement ?: return false
        val factory = com.intellij.psi.PsiElementFactory.getInstance(file.project)
        pkg.replace(factory.createPackageStatement(targetPackage))
        return true
    }

    override fun listMovableDeclarations(file: PsiFile): List<PsiElement>? =
        (file as? PsiJavaFile)?.classes?.toList()

    override fun changeReturnType(method: PsiElement, newReturnType: String): Boolean {
        val m = method as? PsiMethod ?: return false
        val factory = com.intellij.psi.PsiElementFactory.getInstance(m.project)
        val newType = factory.createTypeFromText(newReturnType, m)
        val old = m.returnTypeElement ?: return false
        old.replace(factory.createTypeElement(newType))
        return true
    }

    override fun changeParameters(
        method: PsiElement,
        parameters: List<com.codeintel.mcpserver.models.args.ParameterChange>
    ): Boolean {
        val m = method as? PsiMethod ?: return false
        val factory = com.intellij.psi.PsiElementFactory.getInstance(m.project)
        val paramList = m.parameterList
        for (p in paramList.parameters) { p.delete() }
        for (p in parameters) {
            val type = factory.createTypeFromText(p.type, m)
            paramList.add(factory.createParameter(p.name, type))
        }
        return true
    }

    override fun findContainingClassLike(
        file: PsiFile,
        startOffset: Int,
        endOffset: Int
    ): PsiElement? {
        if (file !is PsiJavaFile) return null
        return PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)
            .firstOrNull { it.textRange.startOffset <= startOffset && it.textRange.endOffset >= endOffset }
    }

    override fun extractMethodInClass(
        containingClass: PsiElement,
        methodName: String,
        body: String
    ): Boolean {
        val cls = containingClass as? PsiClass ?: return false
        val factory = com.intellij.psi.PsiElementFactory.getInstance(cls.project)
        val methodText = "private void $methodName() {\n$body\n}"
        val newMethod = factory.createMethodFromText(methodText, cls)
        cls.add(newMethod)
        return true
    }

    override fun extractMethodCallExpression(
        containingClass: PsiElement,
        methodName: String
    ): String? = if (containingClass is PsiClass) "$methodName();" else null

    // -------- Data flow (nullability) --------

    override fun analyzeNullabilityFromCursor(
        project: com.intellij.openapi.project.Project,
        element: PsiElement
    ): com.codeintel.mcpserver.models.results.DataFlowResult? {
        val javaVar = element.parent as? PsiVariable ?: return null
        return analyzeJavaNullability(project, javaVar)
    }

    override fun analyzeNullabilityOfResolved(
        project: com.intellij.openapi.project.Project,
        resolved: PsiElement
    ): com.codeintel.mcpserver.models.results.DataFlowResult? {
        if (resolved !is PsiVariable) return null
        return analyzeJavaNullability(project, resolved)
    }

    override fun backwardFlowSteps(
        project: com.intellij.openapi.project.Project,
        target: PsiElement
    ): List<com.codeintel.mcpserver.models.results.FlowStep>? {
        if (target !is PsiVariable) return null
        val initializer = target.initializer ?: return emptyList()
        val file = target.containingFile?.virtualFile ?: return emptyList()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
            .getDocument(target.containingFile)
        val line = doc?.getLineNumber(initializer.textOffset)?.plus(1) ?: 0
        return listOf(
            com.codeintel.mcpserver.models.results.FlowStep(
                file = com.codeintel.mcpserver.util.ProjectUtils.toRelativePath(project, file),
                line = line,
                code = "initializer: ${initializer.text.take(100)}"
            )
        )
    }

    private fun analyzeJavaNullability(
        project: com.intellij.openapi.project.Project,
        variable: PsiVariable
    ): com.codeintel.mcpserver.models.results.DataFlowResult {
        val type = variable.type
        val annotations = variable.annotations
        val hasNullable = annotations.any { it.qualifiedName?.contains("Nullable") == true }
        val hasNonNull = annotations.any {
            val fqn = it.qualifiedName ?: ""
            fqn.contains("NonNull") || fqn.contains("NotNull") || fqn.contains("Nonnull")
        }

        val nullPaths = mutableListOf<String>()
        val initializer = variable.initializer
        if (initializer != null && initializer.text == "null") {
            nullPaths.add("initialized to null at declaration")
        }

        val usages = com.intellij.psi.search.searches.ReferencesSearch.search(variable).findAll()
        for (ref in usages.take(20)) {
            val assignExpr = PsiTreeUtil.getParentOfType(ref.element, PsiAssignmentExpression::class.java)
            if (assignExpr != null && assignExpr.rExpression?.text == "null") {
                val doc = com.intellij.psi.PsiDocumentManager.getInstance(project)
                    .getDocument(ref.element.containingFile)
                val line = doc?.getLineNumber(ref.element.textOffset)?.plus(1) ?: 0
                nullPaths.add("assigned null at line $line")
            }
        }

        val nullability = when {
            hasNonNull -> "non_null"
            hasNullable -> "nullable"
            type.canonicalText.endsWith("?") -> "nullable"
            nullPaths.isNotEmpty() -> "possibly_null"
            type is com.intellij.psi.PsiPrimitiveType -> "non_null"
            else -> "platform_type"
        }

        return com.codeintel.mcpserver.models.results.DataFlowResult(
            nullability = nullability,
            reason = "Java type: ${type.canonicalText}, annotations: " +
                annotations.map { it.qualifiedName },
            nullPaths = nullPaths.ifEmpty { null }
        )
    }

    // -------- Quality --------

    override fun findComplexityIssues(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<com.codeintel.mcpserver.models.results.QualityIssue>? {
        if (file !is PsiJavaFile) return null
        val issues = mutableListOf<com.codeintel.mcpserver.models.results.QualityIssue>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        for (method in PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)) {
            val complexity = computeJavaComplexity(method)
            if (complexity <= 10) continue
            val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 0
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
                description = "Method '${method.name}' has cyclomatic complexity of $complexity",
                suggestion = "Consider refactoring into smaller methods",
                metrics = mapOf("cyclomatic_complexity" to complexity.toString())
            ))
        }
        return issues
    }

    override fun findDeadCodeIssues(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<com.codeintel.mcpserver.models.results.QualityIssue>? {
        if (file !is PsiJavaFile) return null
        val issues = mutableListOf<com.codeintel.mcpserver.models.results.QualityIssue>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        val scope = com.intellij.psi.search.GlobalSearchScope.fileScope(file)
        for (cls in file.classes) {
            for (method in cls.methods) {
                if (!method.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE)) continue
                val refs = com.intellij.psi.search.searches.ReferencesSearch.search(method, scope).findAll()
                if (refs.isNotEmpty()) continue
                val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 0
                issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                    type = "unused_method",
                    severity = "medium",
                    file = relPath,
                    line = line,
                    description = "Private method '${method.name}' appears unused",
                    suggestion = "Remove if no longer needed"
                ))
            }
            for (field in cls.fields) {
                if (!field.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE)) continue
                val refs = com.intellij.psi.search.searches.ReferencesSearch.search(field, scope).findAll()
                if (refs.isNotEmpty()) continue
                val line = doc?.getLineNumber(field.textOffset)?.plus(1) ?: 0
                issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                    type = "unused_field",
                    severity = "low",
                    file = relPath,
                    line = line,
                    description = "Private field '${field.name}' appears unused",
                    suggestion = "Remove if no longer needed"
                ))
            }
        }
        return issues
    }

    override fun collectCloneCandidates(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<CloneCandidate>? {
        if (file !is PsiJavaFile) return null
        val out = mutableListOf<CloneCandidate>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        for (method in PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)) {
            val body = method.body?.text ?: continue
            val lines = body.lines().size
            if (lines < 5) continue
            val normalized = body.replace(Regex("\\s+"), " ").trim()
            val line = doc?.getLineNumber(method.textOffset)?.plus(1) ?: 0
            out.add(CloneCandidate(
                relPath = relPath,
                line = line,
                methodName = method.name,
                paramCount = method.parameterList.parametersCount,
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
        if (file !is PsiJavaFile) return null
        val issues = mutableListOf<com.codeintel.mcpserver.models.results.QualityIssue>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        for (cls in file.classes) {
            val line = doc?.getLineNumber(cls.textOffset)?.plus(1) ?: 0
            if (cls.methods.size > 20) {
                issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
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
        return issues
    }

    override fun findErrorHandlingIssues(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        relPath: String
    ): List<com.codeintel.mcpserver.models.results.QualityIssue>? {
        if (file !is PsiJavaFile) return null
        val issues = mutableListOf<com.codeintel.mcpserver.models.results.QualityIssue>()
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(file)
        for (tryStmt in PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiTryStatement::class.java)) {
            for (catchSection in tryStmt.catchSections) {
                val catchBody = catchSection.catchBlock?.text?.trim() ?: ""
                val catchType = catchSection.catchType?.canonicalText ?: "Exception"
                val line = doc?.getLineNumber(catchSection.textOffset)?.plus(1) ?: 0
                if (catchBody == "{}" || catchBody.isEmpty()) {
                    issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                        type = "empty_catch", severity = "high",
                        file = relPath, line = line,
                        description = "Empty catch block for $catchType",
                        suggestion = "Log the exception or handle it properly"
                    ))
                }
                if (catchType == "java.lang.Exception" || catchType == "Exception" ||
                    catchType == "java.lang.Throwable" || catchType == "Throwable") {
                    issues.add(com.codeintel.mcpserver.models.results.QualityIssue(
                        type = "broad_catch", severity = "medium",
                        file = relPath, line = line,
                        description = "Catching too broad exception type: $catchType",
                        suggestion = "Catch specific exception types"
                    ))
                }
            }
        }
        return issues
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

    private fun countOccurrences(text: String, pattern: String): Int =
        try { Regex(pattern).findAll(text).count() } catch (_: Exception) { 0 }

    // -------- Project overview --------

    override fun collectClassEntries(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        includeMembers: Boolean
    ): Map<String, List<com.codeintel.mcpserver.models.results.ClassEntry>>? {
        if (file !is PsiJavaFile) return null
        val pkg = file.packageName.ifEmpty { "<root>" }
        val entries = file.classes.map { buildJavaClassEntry(it, includeMembers) }
        return mapOf(pkg to entries)
    }

    override fun collectKeyClassCandidates(
        project: com.intellij.openapi.project.Project,
        file: PsiFile,
        scope: com.intellij.psi.search.GlobalSearchScope,
        moduleName: String
    ): List<com.codeintel.mcpserver.models.results.KeyClassInfo>? {
        // Java contributes to key classes via same heuristics as Kotlin, though
        // the legacy implementation only walked .kt files. We opt-in Java too.
        if (file !is PsiJavaFile) return null
        val out = mutableListOf<com.codeintel.mcpserver.models.results.KeyClassInfo>()
        for (cls in file.classes) {
            val name = cls.qualifiedName ?: continue
            val refCount = try {
                com.intellij.psi.search.searches.ReferencesSearch.search(cls, scope).findAll().size
            } catch (_: Exception) { 0 }
            if (refCount < 3) continue
            val role = when {
                name.contains("Repository") -> "repository"
                name.contains("UseCase") -> "use_case"
                else -> "hub"
            }
            out.add(com.codeintel.mcpserver.models.results.KeyClassInfo(
                name = name,
                module = moduleName,
                role = role,
                references = refCount
            ))
        }
        return out
    }

    override fun collectApiClasses(
        project: com.intellij.openapi.project.Project,
        file: PsiFile
    ): List<com.codeintel.mcpserver.models.results.ApiClass>? {
        if (file !is PsiJavaFile) return null
        val out = mutableListOf<com.codeintel.mcpserver.models.results.ApiClass>()
        for (cls in file.classes) {
            if (!cls.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC)) continue
            val methods = cls.methods
                .filter { it.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC) }
                .map { m ->
                    val params = m.parameterList.parameters.joinToString(", ") {
                        "${it.type.canonicalText} ${it.name}"
                    }
                    com.codeintel.mcpserver.models.results.ApiMethod(
                        name = m.name,
                        signature = "${m.returnType?.canonicalText ?: "void"} ${m.name}($params)",
                        visibility = "public"
                    )
                }
            out.add(com.codeintel.mcpserver.models.results.ApiClass(
                name = cls.qualifiedName ?: cls.name ?: "<anonymous>",
                kind = when {
                    cls.isInterface -> "interface"
                    cls.isEnum -> "enum"
                    cls.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT) -> "abstract_class"
                    else -> "class"
                },
                methods = methods
            ))
        }
        return out
    }

    override fun findClassByFqName(
        project: com.intellij.openapi.project.Project,
        scope: com.intellij.psi.search.GlobalSearchScope,
        fqName: String
    ): PsiElement? = com.intellij.psi.JavaPsiFacade.getInstance(project).findClass(fqName, scope)

    override fun getEnclosingClassLike(element: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

    override fun collectRetrofitInterfaces(
        project: com.intellij.openapi.project.Project,
        file: PsiFile
    ): List<com.codeintel.mcpserver.models.results.RetrofitInterface>? {
        if (file !is PsiJavaFile) return null
        val out = mutableListOf<com.codeintel.mcpserver.models.results.RetrofitInterface>()
        val httpMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")
        for (cls in file.classes) {
            if (!cls.isInterface) continue
            val has = cls.methods.any { m ->
                m.getAnnotation("retrofit2.http.GET") != null ||
                m.getAnnotation("retrofit2.http.POST") != null ||
                m.getAnnotation("retrofit2.http.PUT") != null ||
                m.getAnnotation("retrofit2.http.DELETE") != null ||
                m.getAnnotation("retrofit2.http.PATCH") != null
            }
            if (!has) continue
            val endpoints = mutableListOf<com.codeintel.mcpserver.models.results.RetrofitEndpoint>()
            for (method in cls.methods) {
                for (httpMethod in httpMethods) {
                    val ann = method.getAnnotation("retrofit2.http.$httpMethod") ?: continue
                    val path = ann.findAttributeValue("value")?.text?.removeSurrounding("\"") ?: ""
                    val params = method.parameterList.parameters.map { p ->
                        val paramAnn = p.annotations.firstOrNull()
                            ?.qualifiedName?.substringAfterLast(".") ?: "Body"
                        com.codeintel.mcpserver.models.results.EndpointParam(
                            name = p.name ?: "",
                            type = p.type.canonicalText,
                            annotation = "@$paramAnn"
                        )
                    }
                    endpoints.add(com.codeintel.mcpserver.models.results.RetrofitEndpoint(
                        method = method.name,
                        path = path,
                        httpMethod = httpMethod,
                        returnType = method.returnType?.canonicalText ?: "Unit",
                        parameters = params
                    ))
                }
            }
            out.add(com.codeintel.mcpserver.models.results.RetrofitInterface(
                name = cls.qualifiedName ?: cls.name ?: "",
                baseUrl = null,
                endpoints = endpoints
            ))
        }
        return out
    }

    private fun buildJavaClassEntry(
        cls: PsiClass,
        includeMembers: Boolean
    ): com.codeintel.mcpserver.models.results.ClassEntry {
        val supers = mutableListOf<String>()
        cls.superClass?.let {
            if (it.qualifiedName != "java.lang.Object") supers.add(it.name ?: "")
        }
        cls.interfaces.forEach { supers.add(it.name ?: "") }
        val annos = cls.annotations.mapNotNull { "@${it.qualifiedName?.substringAfterLast(".")}" }
        val kind = when {
            cls.isInterface -> "interface"
            cls.isEnum -> "enum"
            cls.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT) -> "abstract class"
            else -> "class"
        }
        val visibility = when {
            cls.hasModifierProperty(com.intellij.psi.PsiModifier.PRIVATE) -> "private"
            cls.hasModifierProperty(com.intellij.psi.PsiModifier.PROTECTED) -> "protected"
            else -> "public"
        }
        val members = if (includeMembers) {
            cls.methods
                .filter { it.hasModifierProperty(com.intellij.psi.PsiModifier.PUBLIC) }
                .map { m ->
                    val params = m.parameterList.parameters.joinToString(", ") {
                        "${it.type.presentableText} ${it.name}"
                    }
                    "${m.name}($params): ${m.returnType?.presentableText ?: "void"}"
                }
        } else null
        return com.codeintel.mcpserver.models.results.ClassEntry(
            name = cls.name ?: "<anonymous>",
            kind = kind,
            visibility = visibility,
            superTypes = supers.ifEmpty { null },
            annotations = annos.ifEmpty { null },
            members = members?.ifEmpty { null }
        )
    }
}

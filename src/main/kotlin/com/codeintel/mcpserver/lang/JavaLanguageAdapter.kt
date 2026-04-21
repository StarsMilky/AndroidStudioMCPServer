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
}

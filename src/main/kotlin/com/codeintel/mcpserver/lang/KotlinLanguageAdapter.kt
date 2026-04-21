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
}

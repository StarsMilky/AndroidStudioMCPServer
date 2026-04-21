package com.codeintel.mcpserver.lang

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
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
}

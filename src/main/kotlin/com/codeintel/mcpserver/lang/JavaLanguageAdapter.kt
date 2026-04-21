package com.codeintel.mcpserver.lang

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
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
}

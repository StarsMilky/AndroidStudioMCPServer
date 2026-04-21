package com.codeintel.mcpserver.lang

import com.codeintel.mcpserver.models.results.SymbolKind
import com.codeintel.mcpserver.models.results.UsageType
import com.intellij.lang.Language
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Language-specific behavior that the core services delegate to.
 *
 * One adapter per supported language family (Java, Kotlin, Python, Go, TS/JS, ...).
 * Registered through the `com.codeintel.mcpserver.languageAdapter` extension point,
 * which lives on the `com.intellij.modules.platform` dependency so that optional
 * language bundles (Java / Kotlin / Android / Python / Go / JS ...) can ship their
 * own adapter without blocking the core plugin from loading.
 *
 * All methods run under the read action provided by the caller; implementations
 * must be side-effect free.
 */
interface LanguageAdapter {
    /**
     * Stable, UPPER_SNAKE_CASE identifier (e.g. "JAVA", "KOTLIN", "PYTHON").
     * Used for diagnostics and for ToolMetricsService to attribute per-language cost.
     */
    val id: String

    /**
     * Returns true if this adapter should handle the given file.
     * The first adapter whose [canHandle] returns true wins.
     */
    fun canHandle(file: PsiFile): Boolean

    /**
     * Top-level declarations (classes, top-level functions, top-level properties,
     * type aliases, ...). Returned in source order.
     */
    fun listTopLevelSymbols(file: PsiFile): List<PsiElement> = emptyList()

    /**
     * The nearest enclosing named symbol for [element] (method, class, property, ...).
     * Returns null for elements at file scope.
     */
    fun getContainingSymbol(element: PsiElement): PsiElement? = null

    /**
     * Compute a fully-qualified name for [element], or null if the language does
     * not have a natural FQN for it.
     */
    fun qualifiedName(element: PsiElement): String? = null

    /**
     * Source file extensions owned by this language (e.g. ["java"], ["kt", "kts"]).
     * Used by scanners that enumerate files through FilenameIndex.
     */
    fun fileExtensions(): List<String> = emptyList()

    /**
     * Package/namespace name declared in [file], or empty string if none.
     * Returns null if the file is not owned by this language.
     */
    fun packageName(file: PsiFile): String? = null

    /**
     * Fully-qualified names of imports declared in [file].
     * Returns null if the file is not owned by this language.
     */
    fun importedFqNames(file: PsiFile): List<String>? = null

    /**
     * (FileType, Language) pair used by IntelliJ's Structural Search engine
     * for this language. Returns null if SSR is not supported.
     */
    fun structuralSearchTarget(): Pair<LanguageFileType, Language>? = null

    /**
     * If [element] (or one of its ancestors) is inside a package / namespace
     * declaration owned by this language, returns `declarationNode to fqName`.
     * Returns null otherwise.
     */
    fun findEnclosingPackageDirective(element: PsiElement): Pair<PsiElement, String>? = null

    /**
     * If [element] (or one of its ancestors) is inside an import statement owned
     * by this language, returns `importNode to importedFqName`. Returns null otherwise.
     */
    fun findEnclosingImportDirective(element: PsiElement): Pair<PsiElement, String>? = null

    /**
     * Map a language-specific PSI node to a [SymbolKind], or null if the adapter
     * does not recognize the element type.
     */
    fun classifySymbol(element: PsiElement): SymbolKind? = null

    /**
     * A human-readable fully-qualified signature suitable for display
     * (e.g. "com.example.Foo.bar: String" for a Kotlin property with type).
     * Falls back to [qualifiedName] when the adapter has no richer form.
     */
    fun qualifiedSignature(element: PsiElement): String? = qualifiedName(element)

    /**
     * If [element] is a reference in a construct recognized by this adapter
     * (a call expression, an assignment lhs, an override), return its
     * [UsageType]. Return null to let the caller try another adapter or
     * use a default.
     */
    fun classifyUsage(element: PsiElement): UsageType? = null

    /**
     * Return true if [element] is a method/function-like declaration in this
     * language (Java method, Kotlin fun/property accessor, Python def, ...).
     */
    fun isMethodLike(element: PsiElement): Boolean = false

    /**
     * Walk up the tree to find the enclosing method-like declaration for
     * [element]. Return null if none (e.g. top-level or inside a field).
     */
    fun findEnclosingMethod(element: PsiElement): PsiElement? = null

    /**
     * Return the body block/expression of a method-like declaration if
     * [method] is recognized by this adapter. Null otherwise.
     */
    fun getMethodBody(method: PsiElement): PsiElement? = null

    /**
     * If [element] is a call expression whose resolved callee is interesting
     * for callees-analysis, return the (resolvedCallee, callSiteElement) pair.
     * Return null if [element] is not a call expression for this language.
     */
    fun resolveCallTarget(element: PsiElement): PsiElement? = null

    /**
     * Convert a language-specific class-like declaration to a platform
     * [PsiClass] for interop with ClassInheritorsSearch and other Java-side
     * APIs. Return null if this element is not a class or cannot be bridged.
     */
    fun asPsiClass(element: PsiElement): PsiClass? = null

    companion object {
        val EP_NAME: ExtensionPointName<LanguageAdapter> =
            ExtensionPointName.create("com.codeintel.mcpserver.languageAdapter")

        /**
         * First adapter that claims the file, or null if none of the bundled
         * language modules are installed in this IDE.
         */
        fun forFile(file: PsiFile): LanguageAdapter? =
            EP_NAME.extensionList.firstOrNull { runCatching { it.canHandle(file) }.getOrDefault(false) }

        /**
         * Find adapter by stable id (case-insensitive).
         */
        fun byId(id: String): LanguageAdapter? =
            EP_NAME.extensionList.firstOrNull { it.id.equals(id, ignoreCase = true) }

        /**
         * All registered adapters (order = declaration order in plugin.xml).
         */
        @Suppress("UNUSED_PARAMETER")
        fun all(project: Project): List<LanguageAdapter> = EP_NAME.extensionList
    }
}

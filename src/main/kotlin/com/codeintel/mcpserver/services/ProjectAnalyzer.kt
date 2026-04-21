package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.codeintel.mcpserver.models.args.QueryProjectArgs
import com.codeintel.mcpserver.models.args.QueryProjectMode
import com.codeintel.mcpserver.models.results.ApiClass
import com.codeintel.mcpserver.models.results.ApiMethod
import com.codeintel.mcpserver.models.results.ApiSurface
import com.codeintel.mcpserver.models.results.ClassEntry
import com.codeintel.mcpserver.models.results.CycleInfo
import com.codeintel.mcpserver.models.results.FrameworkOverviewSummary
import com.codeintel.mcpserver.models.results.ImpactAnalysis
import com.codeintel.mcpserver.models.results.KeyClassInfo
import com.codeintel.mcpserver.models.results.ModuleInfo
import com.codeintel.mcpserver.models.results.ModuleStats
import com.codeintel.mcpserver.models.results.ProjectOverview
import com.codeintel.mcpserver.models.results.VariantInfo
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

object ProjectAnalyzer {

    fun analyze(project: Project, args: QueryProjectArgs): ProjectOverview {
        val result = PsiUtils.smartReadAction(project) {
            when (args.mode) {
                QueryProjectMode.OVERVIEW -> analyzeOverview(project)
                QueryProjectMode.DEPENDENCY -> analyzeDependency(project, args)
                QueryProjectMode.API_SURFACE -> analyzeApiSurface(project, args)
                QueryProjectMode.VARIANT -> analyzeVariant(project)
            }
        }
        val hint = when (args.mode) {
            QueryProjectMode.OVERVIEW ->
                "💡 Next: query_framework(framework='ROOM/HILT/COMPOSE/...') to drill into a detected framework, or find_symbol(name='...') on any key class."
            QueryProjectMode.DEPENDENCY ->
                "💡 Next: for each affected class, find_references(qualified_name='...', mode='USAGES') to estimate blast radius."
            QueryProjectMode.API_SURFACE ->
                "💡 Next: find_references(qualified_name='...', mode='USAGES') on a public symbol to see external callers."
            QueryProjectMode.VARIANT ->
                "💡 Next: get_scope(file,line,col) in a source file to see which variant-specific symbols are visible."
        }
        return result.copy(nextAction = hint)
    }

    private fun analyzeOverview(project: Project): ProjectOverview {
        val moduleManager = ModuleManager.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)
        val allKtFiles = FilenameIndex.getAllFilesByExt(project, "kt", projectScope)
        val allJavaFiles = FilenameIndex.getAllFilesByExt(project, "java", projectScope)
        val totalClasses = allKtFiles.size + allJavaFiles.size

        val modules = moduleManager.modules.map { module ->
            val rootManager = ModuleRootManager.getInstance(module)
            val deps = rootManager.dependencies.map { it.name }
            val scope = module.moduleScope
            val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope).size
            val javaFiles = FilenameIndex.getAllFilesByExt(project, "java", scope).size

            ModuleInfo(
                name = module.name,
                type = detectModuleType(module.name),
                dependsOn = deps,
                stats = ModuleStats(
                    classes = ktFiles + javaFiles,
                    kotlinFiles = ktFiles,
                    javaFiles = javaFiles
                )
            )
        }

        val pattern = detectArchitecturePattern(modules)
        val entryPoints = detectEntryPoints(project)
        val cycles = detectCycles(modules)
        val frameworksSummary = detectFrameworksSummary(project, projectScope)

        val level: String
        var classMap: Map<String, List<ClassEntry>>? = null
        var keyClasses: List<KeyClassInfo>? = null

        when {
            totalClasses <= 80 -> {
                level = if (totalClasses <= 20) "full_members" else "class_signatures"
                classMap = buildClassMap(project, projectScope, includeMembers = totalClasses <= 20)
            }
            totalClasses <= 300 -> {
                level = "class_names"
                classMap = buildClassMap(project, projectScope, includeMembers = false)
            }
            else -> {
                level = if (totalClasses <= 800) "module_digest" else "grouped_digest"
                keyClasses = detectKeyClasses(project, projectScope)
            }
        }

        return ProjectOverview(
            level = level,
            projectClasses = totalClasses,
            modules = modules,
            architecturePattern = pattern,
            entryPoints = entryPoints,
            dependencyDirection = if (modules.size > 1)
                buildDependencyDirection(modules)
            else
                "single-module",
            cycles = cycles,
            frameworks = frameworksSummary,
            keyClasses = keyClasses,
            classMap = classMap
        )
    }

    private fun buildDependencyDirection(modules: List<ModuleInfo>): String {
        val hasApp = modules.any { it.type == "application" }
        val hasDomain = modules.any { it.type == "domain" }
        val hasData = modules.any { it.type == "data" }
        return when {
            hasApp && hasDomain && hasData -> "app → domain ← data"
            hasApp && hasDomain -> "app → domain"
            else -> "multi-module"
        }
    }

    private fun detectFrameworksSummary(
        project: Project,
        scope: GlobalSearchScope
    ): Map<String, FrameworkOverviewSummary> {
        val result = mutableMapOf<String, FrameworkOverviewSummary>()
        val allFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope) +
            FilenameIndex.getAllFilesByExt(project, "java", scope)

        var roomEntities = 0
        var daos = 0
        var hiltModules = 0
        var retrofitServices = 0
        var composables = 0
        var navGraphs = 0

        for (vf in allFiles) {
            val content = try { String(vf.contentsToByteArray()) } catch (_: Exception) { continue }
            if (content.contains("@Entity")) roomEntities++
            if (content.contains("@Dao")) daos++
            if (content.contains("@Module") && content.contains("@InstallIn")) hiltModules++
            if (content.contains("@GET")
                || content.contains("@POST")
                || content.contains("@PUT")
            ) retrofitServices++
            if (content.contains("@Composable")) composables++
        }

        val xmlFiles = FilenameIndex.getAllFilesByExt(project, "xml", scope)
        for (vf in xmlFiles) {
            val content = try { String(vf.contentsToByteArray()) } catch (_: Exception) { continue }
            if (content.contains("<navigation") || content.contains("app:navGraph")) navGraphs++
        }

        if (roomEntities > 0) result["room"] = FrameworkOverviewSummary(
            roomEntities + daos,
            "$roomEntities entities, $daos DAOs"
        )
        if (hiltModules > 0) result["hilt"] = FrameworkOverviewSummary(
            hiltModules,
            "$hiltModules modules"
        )
        if (retrofitServices > 0) result["retrofit"] = FrameworkOverviewSummary(
            retrofitServices,
            "$retrofitServices service files"
        )
        if (composables > 0) result["compose"] = FrameworkOverviewSummary(
            composables,
            "$composables composable files"
        )
        if (navGraphs > 0) result["navigation"] = FrameworkOverviewSummary(
            navGraphs,
            "$navGraphs navigation files"
        )

        return result
    }

    private fun buildClassMap(
        project: Project, scope: GlobalSearchScope, includeMembers: Boolean
    ): Map<String, List<ClassEntry>> {
        val classMap = mutableMapOf<String, MutableList<ClassEntry>>()

        for (vf in FilenameIndex.getAllFilesByExt(project, "kt", scope)) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
            val pkg = pf.packageFqName.asString().ifEmpty { "<root>" }
            for (decl in pf.declarations.filterIsInstance<KtClassOrObject>()) {
                val entry = buildKotlinClassEntry(decl, includeMembers)
                classMap.getOrPut(pkg) { mutableListOf() }.add(entry)
            }
        }

        for (vf in FilenameIndex.getAllFilesByExt(project, "java", scope)) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile ?: continue
            val pkg = pf.packageName.ifEmpty { "<root>" }
            for (cls in pf.classes) {
                val entry = buildJavaClassEntry(cls, includeMembers)
                classMap.getOrPut(pkg) { mutableListOf() }.add(entry)
            }
        }

        return classMap
    }

    private fun buildKotlinClassEntry(decl: KtClassOrObject, includeMembers: Boolean): ClassEntry {
        val supers = decl.superTypeListEntries.map { it.text.substringBefore("(").trim() }
        val annos = decl.annotationEntries.map { "@${it.shortName?.asString() ?: ""}" }
        val visibility = when {
            decl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) -> "private"
            decl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD) -> "internal"
            else -> "public"
        }
        val kind = when (decl) {
            is KtClass -> when {
                decl.isInterface() -> "interface"
                decl.isEnum() -> "enum"
                decl.isData() -> "data class"
                decl.isSealed() -> "sealed class"
                else -> "class"
            }
            is KtObjectDeclaration -> "object"
            else -> "class"
        }

        val members = if (includeMembers) {
            val fns = PsiTreeUtil.findChildrenOfType(decl, KtNamedFunction::class.java)
                .filter { !it.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) }
                .map { fn ->
                    val params = fn.valueParameters.joinToString(", ") {
                        "${it.name}: ${it.typeReference?.text ?: "Any"}"
                    }
                    val ret = fn.typeReference?.text?.let { ": $it" } ?: ""
                    "${fn.name}($params)$ret"
                }
            val props = PsiTreeUtil.findChildrenOfType(decl, KtProperty::class.java)
                .filter {
                    it.parent == decl.body
                        && !it.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)
                }
                .map { "${it.name}: ${it.typeReference?.text ?: "?"}" }
            props + fns
        } else null

        return ClassEntry(
            name = decl.name ?: "<anonymous>",
            kind = kind,
            visibility = visibility,
            superTypes = supers.ifEmpty { null },
            annotations = annos.ifEmpty { null },
            members = members?.ifEmpty { null }
        )
    }

    private fun buildJavaClassEntry(cls: PsiClass, includeMembers: Boolean): ClassEntry {
        val supers = mutableListOf<String>()
        cls.superClass?.let {
            if (it.qualifiedName != "java.lang.Object") supers.add(it.name ?: "")
        }
        cls.interfaces.forEach { supers.add(it.name ?: "") }
        val annos = cls.annotations.mapNotNull { "@${it.qualifiedName?.substringAfterLast(".")}" }
        val kind = when {
            cls.isInterface -> "interface"
            cls.isEnum -> "enum"
            cls.hasModifierProperty(PsiModifier.ABSTRACT) -> "abstract class"
            else -> "class"
        }
        val visibility = when {
            cls.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
            cls.hasModifierProperty(PsiModifier.PROTECTED) -> "protected"
            else -> "public"
        }

        val members = if (includeMembers) {
            cls.methods.filter { it.hasModifierProperty(PsiModifier.PUBLIC) }.map { m ->
                val params = m.parameterList.parameters.joinToString(", ") {
                    "${it.type.presentableText} ${it.name}"
                }
                "${m.name}($params): ${m.returnType?.presentableText ?: "void"}"
            }
        } else null

        return ClassEntry(
            name = cls.name ?: "<anonymous>",
            kind = kind,
            visibility = visibility,
            superTypes = supers.ifEmpty { null },
            annotations = annos.ifEmpty { null },
            members = members?.ifEmpty { null }
        )
    }

    private fun detectKeyClasses(project: Project, scope: GlobalSearchScope): List<KeyClassInfo> {
        val candidates = mutableListOf<KeyClassInfo>()
        val moduleManager = ModuleManager.getInstance(project)

        for (vf in FilenameIndex.getAllFilesByExt(project, "kt", scope).take(500)) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
            val moduleName = moduleManager.modules.firstOrNull { module ->
                ModuleRootManager.getInstance(module).fileIndex.isInContent(vf)
            }?.name ?: "unknown"

            for (decl in pf.declarations.filterIsInstance<KtClassOrObject>()) {
                val name = decl.fqName?.asString() ?: continue
                val refCount = try {
                    ReferencesSearch.search(decl, scope).findAll().size
                } catch (_: Exception) { 0 }
                if (refCount >= 3) {
                    val role = when {
                        decl.annotationEntries.any {
                            it.shortName?.asString() == "HiltAndroidApp"
                        } -> "application"
                        decl.annotationEntries.any {
                            it.shortName?.asString() == "AndroidEntryPoint"
                        } -> "entry_point"
                        decl.annotationEntries.any {
                            it.shortName?.asString() == "HiltViewModel"
                        } -> "viewmodel"
                        name.contains("Repository") -> "repository"
                        name.contains("UseCase") -> "use_case"
                        else -> "hub"
                    }
                    candidates.add(
                        KeyClassInfo(
                            name = name,
                            module = moduleName,
                            role = role,
                            references = refCount
                        )
                    )
                }
            }
        }

        return candidates.sortedByDescending { it.references }.take(10)
    }

    private fun analyzeDependency(project: Project, args: QueryProjectArgs): ProjectOverview {
        val targetClass = args.targetClass ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE,
            mapOf("reason" to "target_class is required for dependency mode")
        )

        val scope = GlobalSearchScope.projectScope(project)
        val facade = JavaPsiFacade.getInstance(project)
        val psiClass = facade.findClass(targetClass, scope)

        if (psiClass == null) {
            val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
            val targetSimple = targetClass.substringAfterLast(".")
            var foundElement: PsiNamedElement? = null
            for (vf in ktFiles) {
                val pf = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
                foundElement = PsiTreeUtil.findChildrenOfType(pf, KtClass::class.java)
                    .firstOrNull { it.fqName?.asString() == targetClass || it.name == targetSimple }
                if (foundElement != null) break
            }
            if (foundElement == null) {
                throw ToolException(McpErrorCode.SYMBOL_NOT_FOUND, mapOf("class" to targetClass))
            }
            return buildDependencyResult(project, foundElement, args)
        }

        return buildDependencyResult(project, psiClass, args)
    }

    private fun buildDependencyResult(
        project: Project,
        element: PsiElement,
        args: QueryProjectArgs
    ): ProjectOverview {
        val scope = GlobalSearchScope.projectScope(project)
        val maxHops = args.maxHops.coerceIn(1, 5)

        val directRefs = ReferencesSearch.search(element, scope).findAll()

        val directImpact = directRefs.mapNotNull { ref ->
            val file = ref.element.containingFile?.virtualFile ?: return@mapNotNull null
            ProjectUtils.toRelativePath(project, file)
        }.distinct()

        val transitiveImpact = mutableMapOf<String, List<String>>()
        if (maxHops > 1) {
            val hop1Classes = directRefs.mapNotNull { ref ->
                PsiTreeUtil.getParentOfType(ref.element, PsiClass::class.java)
                    ?: PsiTreeUtil.getParentOfType(ref.element, KtClassOrObject::class.java)
            }.distinct()

            var currentHopElements: List<PsiElement> = hop1Classes
            for (hop in 2..maxHops) {
                if (currentHopElements.isEmpty()) break
                val nextHopFiles = mutableListOf<String>()
                val nextHopElements = mutableListOf<PsiElement>()

                for (el in currentHopElements.take(20)) {
                    val refs = try {
                        ReferencesSearch.search(el, scope).findAll().take(10)
                    } catch (_: Exception) { emptyList() }
                    for (ref in refs) {
                        val file = ref.element.containingFile?.virtualFile ?: continue
                        nextHopFiles.add(ProjectUtils.toRelativePath(project, file))
                        PsiTreeUtil.getParentOfType(ref.element, PsiClass::class.java)
                            ?.let { nextHopElements.add(it) }
                        PsiTreeUtil.getParentOfType(ref.element, KtClassOrObject::class.java)
                            ?.let { nextHopElements.add(it) }
                    }
                }
                val uniqueFiles = nextHopFiles.distinct() - directImpact.toSet()
                if (uniqueFiles.isNotEmpty()) {
                    transitiveImpact["hop_$hop"] = uniqueFiles
                }
                currentHopElements = nextHopElements.distinct()
            }
        }

        val moduleManager = ModuleManager.getInstance(project)
        val affectedModules = directRefs.mapNotNull { ref ->
            val file = ref.element.containingFile?.virtualFile ?: return@mapNotNull null
            moduleManager.modules.firstOrNull { module ->
                ModuleRootManager.getInstance(module).fileIndex.isInContent(file)
            }?.name
        }.distinct()

        val affectedTests = directRefs.mapNotNull { ref ->
            val file = ref.element.containingFile?.virtualFile ?: return@mapNotNull null
            val path = file.path
            if (path.contains("test") || path.contains("Test")) {
                ProjectUtils.toRelativePath(project, file)
            } else null
        }.distinct()

        val totalTransitive = transitiveImpact.values.sumOf { it.size }
        val totalImpact = directImpact.size + totalTransitive
        val riskLevel = when {
            totalImpact > 30 || affectedModules.size > 3 -> "high"
            totalImpact > 10 || affectedModules.size > 1 -> "medium"
            else -> "low"
        }

        return ProjectOverview(
            impact = ImpactAnalysis(
                directImpact = directImpact,
                transitiveImpact = transitiveImpact,
                affectedModules = affectedModules,
                affectedTests = affectedTests,
                riskLevel = riskLevel,
                suggestion = "Direct: ${directImpact.size} files, Transitive: $totalTransitive "
                    + "files across ${affectedModules.size} module(s)"
            )
        )
    }

    private fun analyzeApiSurface(project: Project, args: QueryProjectArgs): ProjectOverview {
        val scope = if (args.module != null) {
            val module = ModuleManager.getInstance(project).modules
                .firstOrNull { it.name == args.module }
            module?.moduleScope ?: GlobalSearchScope.projectScope(project)
        } else {
            GlobalSearchScope.projectScope(project)
        }

        val apiClasses = mutableListOf<ApiClass>()
        val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
        val javaFiles = FilenameIndex.getAllFilesByExt(project, "java", scope)

        for (vf in javaFiles) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile ?: continue
            for (cls in pf.classes) {
                if (!cls.hasModifierProperty(PsiModifier.PUBLIC)) continue
                val methods = cls.methods
                    .filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
                    .map { m ->
                        val params = m.parameterList.parameters.joinToString(", ") {
                            "${it.type.canonicalText} ${it.name}"
                        }
                        ApiMethod(
                            name = m.name,
                            signature = "${m.returnType?.canonicalText ?: "void"} "
                                + "${m.name}($params)",
                            visibility = "public"
                        )
                    }
                apiClasses.add(ApiClass(
                    name = cls.qualifiedName ?: cls.name ?: "<anonymous>",
                    kind = when {
                        cls.isInterface -> "interface"
                        cls.isEnum -> "enum"
                        cls.hasModifierProperty(PsiModifier.ABSTRACT) -> "abstract_class"
                        else -> "class"
                    },
                    methods = methods
                ))
            }
        }

        for (vf in ktFiles) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
            for (decl in pf.declarations) {
                if (decl !is KtClassOrObject) continue
                if (decl.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD)) continue
                val methods = PsiTreeUtil.findChildrenOfType(decl, KtNamedFunction::class.java)
                    .filter { !it.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) }
                    .map { fn ->
                        val params = fn.valueParameters.joinToString(", ") {
                            "${it.name ?: "_"}: ${it.typeReference?.text ?: "Any"}"
                        }
                        ApiMethod(
                            name = fn.name ?: "<anonymous>",
                            signature = "fun ${fn.name}($params): "
                                + "${fn.typeReference?.text ?: "Unit"}",
                            visibility = if (fn.hasModifier(
                                org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD
                            )) "internal" else "public"
                        )
                    }
                apiClasses.add(ApiClass(
                    name = decl.fqName?.asString() ?: decl.name ?: "<anonymous>",
                    kind = when (decl) {
                        is KtClass -> when {
                            decl.isInterface() -> "interface"
                            decl.isEnum() -> "enum"
                            else -> "class"
                        }
                        is KtObjectDeclaration -> "object"
                        else -> "class"
                    },
                    methods = methods
                ))
            }
        }

        val totalPublicSymbols = apiClasses.sumOf { 1 + it.methods.size }

        return ProjectOverview(
            publicApi = ApiSurface(classes = apiClasses, totalPublicSymbols = totalPublicSymbols)
        )
    }

    private fun analyzeVariant(project: Project): ProjectOverview {
        val scope = GlobalSearchScope.projectScope(project)
        val buildGradle = FilenameIndex.getVirtualFilesByName("build.gradle.kts", scope) +
                FilenameIndex.getVirtualFilesByName("build.gradle", scope)

        val appBuildFile = buildGradle.firstOrNull { it.path.contains("/app/") }
        val buildConfigFields = mutableMapOf<String, String>()
        val flavors = mutableListOf<String>()
        var buildType = "debug"

        if (appBuildFile != null) {
            val content = String(appBuildFile.contentsToByteArray())
            val flavorRegex = Regex("""(\w+)\s*\{[^}]*dimension""")
            flavorRegex.findAll(content).forEach { flavors.add(it.groupValues[1]) }

            val buildConfigRegex = Regex(
                """buildConfigField\s*\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)"""
            )
            buildConfigRegex.findAll(content).forEach {
                buildConfigFields[it.groupValues[2]] = "${it.groupValues[1]}=${it.groupValues[3]}"
            }
        }

        val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        val activeSourceDirs = sourceRoots.map { ProjectUtils.toRelativePath(project, it) }

        return ProjectOverview(
            variant = VariantInfo(
                variant = if (flavors.isNotEmpty()) "${flavors.first()}Debug" else "debug",
                buildType = buildType,
                flavors = flavors,
                activeSourceDirs = activeSourceDirs,
                inactiveSourceDirs = emptyList(),
                buildConfigFields = buildConfigFields
            )
        )
    }

    private fun detectModuleType(name: String): String = when {
        name == "app" || name.endsWith(".app") -> "application"
        name.contains("domain") -> "domain"
        name.contains("data") -> "data"
        name.contains("core") -> "core"
        name.contains("feature") -> "feature"
        else -> "library"
    }

    private fun detectArchitecturePattern(modules: List<ModuleInfo>): String {
        val names = modules.map { it.name.lowercase() }
        return when {
            names.any { it.contains("domain") }
                && names.any { it.contains("data") } -> "Clean Architecture"
            names.any { it.contains("feature") } -> "Feature-based modularization"
            modules.size == 1 -> "Monolithic"
            else -> "Multi-module"
        }
    }

    private fun detectEntryPoints(project: Project): List<String> {
        val scope = GlobalSearchScope.projectScope(project)
        val manifests = FilenameIndex.getVirtualFilesByName("AndroidManifest.xml", scope)
        return if (manifests.isNotEmpty()) listOf("AndroidManifest.xml") else emptyList()
    }

    private fun detectCycles(modules: List<ModuleInfo>): List<CycleInfo> {
        val depMap = modules.associate { it.name to it.dependsOn.toSet() }
        val cycles = mutableListOf<CycleInfo>()
        for ((modName, deps) in depMap) {
            for (dep in deps) {
                val depDeps = depMap[dep] ?: continue
                if (modName in depDeps) {
                    val path = listOf(modName, dep, modName)
                    if (cycles.none { it.path.toSet() == path.toSet() }) {
                        cycles.add(CycleInfo(path = path, severity = "warning"))
                    }
                }
            }
        }
        return cycles
    }
}

package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.androidstudio.mcpserver.models.args.QueryProjectArgs
import com.androidstudio.mcpserver.models.args.QueryProjectMode
import com.androidstudio.mcpserver.models.results.*
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

object ProjectAnalyzer {

    fun analyze(project: Project, args: QueryProjectArgs): ProjectOverview {
        return PsiUtils.smartReadAction(project) {
            when (args.mode) {
                QueryProjectMode.OVERVIEW -> analyzeOverview(project)
                QueryProjectMode.DEPENDENCY -> analyzeDependency(project, args)
                QueryProjectMode.API_SURFACE -> analyzeApiSurface(project, args)
                QueryProjectMode.VARIANT -> analyzeVariant(project)
            }
        }
    }

    private fun analyzeOverview(project: Project): ProjectOverview {
        val moduleManager = ModuleManager.getInstance(project)
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
                stats = ModuleStats(classes = ktFiles + javaFiles, kotlinFiles = ktFiles, javaFiles = javaFiles)
            )
        }

        val pattern = detectArchitecturePattern(modules)
        val entryPoints = detectEntryPoints(project)

        return ProjectOverview(
            modules = modules,
            architecturePattern = pattern,
            entryPoints = entryPoints,
            dependencyDirection = if (modules.size > 1) "app → domain → data" else "single-module"
        )
    }

    private fun analyzeDependency(project: Project, args: QueryProjectArgs): ProjectOverview {
        val targetClass = args.targetClass ?: throw ToolException(
            McpErrorCode.INVALID_SCOPE, mapOf("reason" to "targetClass is required for dependency mode")
        )
        return ProjectOverview(
            impact = ImpactAnalysis(
                directImpact = emptyList(),
                transitiveImpact = emptyMap(),
                affectedModules = emptyList(),
                affectedTests = emptyList(),
                riskLevel = "low",
                suggestion = "Analysis for $targetClass"
            )
        )
    }

    private fun analyzeApiSurface(project: Project, args: QueryProjectArgs): ProjectOverview {
        return ProjectOverview(
            publicApi = ApiSurface(classes = emptyList(), totalPublicSymbols = 0)
        )
    }

    private fun analyzeVariant(project: Project): ProjectOverview {
        return ProjectOverview(
            variant = VariantInfo(
                variant = "debug",
                buildType = "debug",
                flavors = emptyList(),
                activeSourceDirs = emptyList(),
                inactiveSourceDirs = emptyList(),
                buildConfigFields = emptyMap()
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
            names.any { it.contains("domain") } && names.any { it.contains("data") } -> "Clean Architecture"
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
}

package com.codeintel.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class ProjectOverview(
    val level: String? = null,
    val projectClasses: Int? = null,
    val modules: List<com.codeintel.mcpserver.models.results.ModuleInfo>? = null,
    val architecturePattern: String? = null,
    val entryPoints: List<String>? = null,
    val dependencyDirection: String? = null,
    val cycles: List<com.codeintel.mcpserver.models.results.CycleInfo>? = null,
    val frameworks: Map<String, com.codeintel.mcpserver.models.results.FrameworkOverviewSummary>? = null,
    val keyClasses: List<com.codeintel.mcpserver.models.results.KeyClassInfo>? = null,
    val classMap: Map<String, List<com.codeintel.mcpserver.models.results.ClassEntry>>? = null,
    val impact: com.codeintel.mcpserver.models.results.ImpactAnalysis? = null,
    val couplingMetrics: Map<String, com.codeintel.mcpserver.models.results.CouplingMetric>? = null,
    val publicApi: com.codeintel.mcpserver.models.results.ApiSurface? = null,
    val leakyAbstractions: List<com.codeintel.mcpserver.models.results.LeakyAbstraction>? = null,
    val variant: com.codeintel.mcpserver.models.results.VariantInfo? = null,
    val truncated: Boolean = false,
    val hint: String? = null,
)

@Serializable
data class FrameworkOverviewSummary(val count: Int, val details: String? = null)

@Serializable
data class KeyClassInfo(val name: String, val module: String, val role: String, val references: Int = 0)

@Serializable
data class ClassEntry(
    val name: String,
    val kind: String,
    val visibility: String? = null,
    val superTypes: List<String>? = null,
    val annotations: List<String>? = null,
    val members: List<String>? = null,
)

@Serializable
data class ModuleInfo(
    val name: String,
    val type: String,
    val dependsOn: List<String>,
    val stats: com.codeintel.mcpserver.models.results.ModuleStats,
)

@Serializable
data class ModuleStats(val classes: Int, val kotlinFiles: Int, val javaFiles: Int)

@Serializable
data class CycleInfo(val path: List<String>, val severity: String)

@Serializable
data class ImpactAnalysis(
    val directImpact: List<String>,
    val transitiveImpact: Map<String, List<String>>,
    val affectedModules: List<String>,
    val affectedTests: List<String>,
    val riskLevel: String,
    val suggestion: String? = null,
)

@Serializable
data class CouplingMetric(val afferent: Int, val efferent: Int, val instability: Double)

@Serializable
data class ApiSurface(val classes: List<com.codeintel.mcpserver.models.results.ApiClass>, val totalPublicSymbols: Int)

@Serializable
data class ApiClass(val name: String, val kind: String, val methods: List<com.codeintel.mcpserver.models.results.ApiMethod>)

@Serializable
data class ApiMethod(val name: String, val signature: String, val visibility: String)

@Serializable
data class LeakyAbstraction(val issue: String, val file: String, val suggestion: String)

@Serializable
data class VariantInfo(
    val variant: String,
    val buildType: String,
    val flavors: List<String>,
    val activeSourceDirs: List<String>,
    val inactiveSourceDirs: List<String>,
    val buildConfigFields: Map<String, String>,
)

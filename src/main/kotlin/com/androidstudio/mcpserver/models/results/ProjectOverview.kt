package com.androidstudio.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class ProjectOverview(
    val modules: List<ModuleInfo>? = null,
    val architecturePattern: String? = null,
    val entryPoints: List<String>? = null,
    val dependencyDirection: String? = null,
    val cycles: List<CycleInfo>? = null,
    val impact: ImpactAnalysis? = null,
    val couplingMetrics: Map<String, CouplingMetric>? = null,
    val publicApi: ApiSurface? = null,
    val leakyAbstractions: List<LeakyAbstraction>? = null,
    val variant: VariantInfo? = null,
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class ModuleInfo(
    val name: String,
    val type: String,
    val dependsOn: List<String>,
    val stats: ModuleStats
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
    val suggestion: String? = null
)

@Serializable
data class CouplingMetric(val afferent: Int, val efferent: Int, val instability: Double)

@Serializable
data class ApiSurface(val classes: List<ApiClass>, val totalPublicSymbols: Int)

@Serializable
data class ApiClass(val name: String, val kind: String, val methods: List<ApiMethod>)

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
    val buildConfigFields: Map<String, String>
)

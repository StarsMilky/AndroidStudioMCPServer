package com.codeintel.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class FrameworkViewResult(
    val framework: String,
    val summary: com.codeintel.mcpserver.models.results.FrameworkSummary? = null,
    val room: com.codeintel.mcpserver.models.results.RoomView? = null,
    val retrofit: com.codeintel.mcpserver.models.results.RetrofitView? = null,
    val hilt: com.codeintel.mcpserver.models.results.HiltView? = null,
    val compose: com.codeintel.mcpserver.models.results.ComposeView? = null,
    val navigation: com.codeintel.mcpserver.models.results.NavigationView? = null,
    val truncated: Boolean = false,
    val hint: String? = null,
    val nextAction: String? = null,
)

@Serializable
data class FrameworkSummary(val totalComponents: Int, val detailTarget: String? = null)

@Serializable
data class RoomView(
    val databases: List<com.codeintel.mcpserver.models.results.RoomDatabase>,
    val entities: List<com.codeintel.mcpserver.models.results.RoomEntity>,
    val daos: List<com.codeintel.mcpserver.models.results.RoomDao>,
    val migrations: List<com.codeintel.mcpserver.models.results.RoomMigration>,
)

@Serializable
data class RoomDatabase(val name: String, val entities: List<String>, val version: Int)

@Serializable
data class RoomEntity(
    val name: String,
    val tableName: String,
    val fields: List<com.codeintel.mcpserver.models.results.EntityField>,
    val primaryKey: List<String>,
    val indices: List<String>,
    val relations: List<String>,
)

@Serializable
data class EntityField(val name: String, val type: String, val nullable: Boolean)

@Serializable
data class RoomDao(val name: String, val methods: List<com.codeintel.mcpserver.models.results.DaoMethod>)

@Serializable
data class DaoMethod(
    val name: String,
    val sql: String?,
    val returnType: String,
    val annotation: String,
)

@Serializable
data class RoomMigration(val from: Int, val to: Int, val file: String)

@Serializable
data class RetrofitView(val interfaces: List<com.codeintel.mcpserver.models.results.RetrofitInterface>)

@Serializable
data class RetrofitInterface(
    val name: String,
    val baseUrl: String?,
    val endpoints: List<com.codeintel.mcpserver.models.results.RetrofitEndpoint>,
)

@Serializable
data class RetrofitEndpoint(
    val method: String,
    val path: String,
    val httpMethod: String,
    val returnType: String,
    val parameters: List<com.codeintel.mcpserver.models.results.EndpointParam>,
)

@Serializable
data class EndpointParam(val name: String, val type: String, val annotation: String)

@Serializable
data class HiltView(
    val modules: List<com.codeintel.mcpserver.models.results.HiltModule>,
    val components: List<com.codeintel.mcpserver.models.results.HiltComponent>,
    val entryPoints: List<com.codeintel.mcpserver.models.results.HiltEntryPoint>,
)

@Serializable
data class HiltModule(val name: String, val installedIn: String, val provides: List<com.codeintel.mcpserver.models.results.HiltProvides>)

@Serializable
data class HiltProvides(val methodName: String, val returnType: String, val scope: String?)

@Serializable
data class HiltComponent(val name: String, val scope: String, val parent: String?)

@Serializable
data class HiltEntryPoint(val name: String, val installedIn: String, val methods: List<String>)

@Serializable
data class ComposeView(
    val composables: List<com.codeintel.mcpserver.models.results.ComposableInfo>,
    val themes: List<com.codeintel.mcpserver.models.results.ThemeInfo>,
    val stateHolders: List<com.codeintel.mcpserver.models.results.StateHolderInfo>,
)

@Serializable
data class ComposableInfo(
    val name: String,
    val file: String,
    val line: Int,
    val parameters: List<String>,
    val preview: Boolean,
)

@Serializable
data class ThemeInfo(val name: String, val file: String, val colorScheme: String?)

@Serializable
data class StateHolderInfo(val name: String, val stateType: String, val file: String)

@Serializable
data class NavigationView(
    val graphs: List<com.codeintel.mcpserver.models.results.NavGraph>,
    val destinations: List<com.codeintel.mcpserver.models.results.NavDestination>,
    val deepLinks: List<com.codeintel.mcpserver.models.results.NavDeepLink>,
)

@Serializable
data class NavGraph(val id: String, val startDestination: String, val file: String)

@Serializable
data class NavDestination(
    val id: String,
    val className: String?,
    val arguments: List<com.codeintel.mcpserver.models.results.NavArgument>,
    val graphId: String,
)

@Serializable
data class NavArgument(
    val name: String,
    val type: String,
    val nullable: Boolean,
    val defaultValue: String?,
)

@Serializable
data class NavDeepLink(val uri: String, val destination: String)

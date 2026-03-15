package com.androidstudio.mcpserver.models.results

import kotlinx.serialization.Serializable

@Serializable
data class FrameworkViewResult(
    val framework: String,
    val summary: FrameworkSummary? = null,
    val room: RoomView? = null,
    val retrofit: RetrofitView? = null,
    val hilt: HiltView? = null,
    val compose: ComposeView? = null,
    val navigation: NavigationView? = null,
    val truncated: Boolean = false,
    val hint: String? = null,
)

@Serializable
data class FrameworkSummary(val totalComponents: Int, val detailTarget: String? = null)

@Serializable
data class RoomView(
    val databases: List<RoomDatabase>,
    val entities: List<RoomEntity>,
    val daos: List<RoomDao>,
    val migrations: List<RoomMigration>,
)

@Serializable
data class RoomDatabase(val name: String, val entities: List<String>, val version: Int)

@Serializable
data class RoomEntity(
    val name: String,
    val tableName: String,
    val fields: List<EntityField>,
    val primaryKey: List<String>,
    val indices: List<String>,
    val relations: List<String>,
)

@Serializable
data class EntityField(val name: String, val type: String, val nullable: Boolean)

@Serializable
data class RoomDao(val name: String, val methods: List<DaoMethod>)

@Serializable
data class DaoMethod(val name: String, val sql: String?, val returnType: String, val annotation: String)

@Serializable
data class RoomMigration(val from: Int, val to: Int, val file: String)

@Serializable
data class RetrofitView(val interfaces: List<RetrofitInterface>)

@Serializable
data class RetrofitInterface(val name: String, val baseUrl: String?, val endpoints: List<RetrofitEndpoint>)

@Serializable
data class RetrofitEndpoint(
    val method: String,
    val path: String,
    val httpMethod: String,
    val returnType: String,
    val parameters: List<EndpointParam>,
)

@Serializable
data class EndpointParam(val name: String, val type: String, val annotation: String)

@Serializable
data class HiltView(
    val modules: List<HiltModule>,
    val components: List<HiltComponent>,
    val entryPoints: List<HiltEntryPoint>,
)

@Serializable
data class HiltModule(val name: String, val installedIn: String, val provides: List<HiltProvides>)

@Serializable
data class HiltProvides(val methodName: String, val returnType: String, val scope: String?)

@Serializable
data class HiltComponent(val name: String, val scope: String, val parent: String?)

@Serializable
data class HiltEntryPoint(val name: String, val installedIn: String, val methods: List<String>)

@Serializable
data class ComposeView(
    val composables: List<ComposableInfo>,
    val themes: List<ThemeInfo>,
    val stateHolders: List<StateHolderInfo>,
)

@Serializable
data class ComposableInfo(val name: String, val file: String, val line: Int, val parameters: List<String>, val preview: Boolean)

@Serializable
data class ThemeInfo(val name: String, val file: String, val colorScheme: String?)

@Serializable
data class StateHolderInfo(val name: String, val stateType: String, val file: String)

@Serializable
data class NavigationView(
    val graphs: List<NavGraph>,
    val destinations: List<NavDestination>,
    val deepLinks: List<NavDeepLink>,
)

@Serializable
data class NavGraph(val id: String, val startDestination: String, val file: String)

@Serializable
data class NavDestination(val id: String, val className: String?, val arguments: List<NavArgument>, val graphId: String)

@Serializable
data class NavArgument(val name: String, val type: String, val nullable: Boolean, val defaultValue: String?)

@Serializable
data class NavDeepLink(val uri: String, val destination: String)

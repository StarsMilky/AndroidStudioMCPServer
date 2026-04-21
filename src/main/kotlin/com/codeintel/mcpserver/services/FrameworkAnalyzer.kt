package com.codeintel.mcpserver.services

import com.codeintel.mcpserver.lang.ComposeFileInfo
import com.codeintel.mcpserver.lang.LanguageAdapter
import com.codeintel.mcpserver.models.args.FrameworkType
import com.codeintel.mcpserver.models.args.QueryFrameworkArgs
import com.codeintel.mcpserver.models.results.ComposableInfo
import com.codeintel.mcpserver.models.results.ComposeView
import com.codeintel.mcpserver.models.results.DaoMethod
import com.codeintel.mcpserver.models.results.EndpointParam
import com.codeintel.mcpserver.models.results.EntityField
import com.codeintel.mcpserver.models.results.FrameworkSummary
import com.codeintel.mcpserver.models.results.FrameworkViewResult
import com.codeintel.mcpserver.models.results.HiltComponent
import com.codeintel.mcpserver.models.results.HiltEntryPoint
import com.codeintel.mcpserver.models.results.HiltModule
import com.codeintel.mcpserver.models.results.HiltProvides
import com.codeintel.mcpserver.models.results.HiltView
import com.codeintel.mcpserver.models.results.NavArgument
import com.codeintel.mcpserver.models.results.NavDeepLink
import com.codeintel.mcpserver.models.results.NavDestination
import com.codeintel.mcpserver.models.results.NavGraph
import com.codeintel.mcpserver.models.results.NavigationView
import com.codeintel.mcpserver.models.results.RetrofitEndpoint
import com.codeintel.mcpserver.models.results.RetrofitInterface
import com.codeintel.mcpserver.models.results.RetrofitView
import com.codeintel.mcpserver.models.results.RoomDao
import com.codeintel.mcpserver.models.results.RoomDatabase
import com.codeintel.mcpserver.models.results.RoomEntity
import com.codeintel.mcpserver.models.results.RoomView
import com.codeintel.mcpserver.models.results.StateHolderInfo
import com.codeintel.mcpserver.models.results.ThemeInfo
import com.codeintel.mcpserver.util.ProjectUtils
import com.codeintel.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile

object FrameworkAnalyzer {

    fun analyze(project: Project, args: QueryFrameworkArgs): FrameworkViewResult {
        val result = PsiUtils.smartReadAction(project) {
            val adapter = com.codeintel.mcpserver.framework.FrameworkAdapter.find(args.framework.name)
            adapter?.scan(project, args.detailTarget) ?: when (args.framework) {
                FrameworkType.ROOM -> analyzeRoom(project, args.detailTarget)
                FrameworkType.RETROFIT -> analyzeRetrofit(project, args.detailTarget)
                FrameworkType.HILT -> analyzeHilt(project, args.detailTarget)
                FrameworkType.COMPOSE -> analyzeCompose(project, args.detailTarget)
                FrameworkType.NAVIGATION -> analyzeNavigation(project, args.detailTarget)
            }
        }
        val hint = if (args.detailTarget == null) {
            "💡 Next: query_framework(framework='${args.framework.name}', detail_target='<name>') to drill into one entry, or find_symbol(name='...') on any listed class."
        } else {
            "💡 Next: find_references(qualified_name='${args.detailTarget}', mode='USAGES') to see where '${args.detailTarget}' is used."
        }
        return result.copy(nextAction = hint)
    }

    internal fun analyzeRoomPublic(project: Project, detailTarget: String?) = analyzeRoom(project, detailTarget)
    internal fun analyzeRetrofitPublic(project: Project, detailTarget: String?) = analyzeRetrofit(project, detailTarget)
    internal fun analyzeHiltPublic(project: Project, detailTarget: String?) = analyzeHilt(project, detailTarget)
    internal fun analyzeComposePublic(project: Project, detailTarget: String?) = analyzeCompose(project, detailTarget)
    internal fun analyzeNavigationPublic(project: Project, detailTarget: String?) = analyzeNavigation(project, detailTarget)

    // ==================== ROOM ====================

    private fun analyzeRoom(project: Project, detailTarget: String?): FrameworkViewResult {
        val scope = GlobalSearchScope.projectScope(project)
        val entities = findAnnotatedClasses(project, scope, "androidx.room.Entity")
        val daos = findAnnotatedClasses(project, scope, "androidx.room.Dao")
        val databases = findAnnotatedClasses(project, scope, "androidx.room.Database")

        return FrameworkViewResult(
            framework = "room",
            summary = FrameworkSummary(
                totalComponents = entities.size + daos.size + databases.size,
                detailTarget = detailTarget
            ),
            room = RoomView(
                databases = databases.map { cls -> extractRoomDatabase(cls) },
                entities = entities.map { cls -> extractRoomEntity(cls) },
                daos = daos.map { cls -> extractRoomDao(cls) },
                migrations = emptyList()
            )
        )
    }

    private fun extractRoomDatabase(cls: PsiClass): RoomDatabase {
        val annotation = cls.getAnnotation("androidx.room.Database")
        var version = 1
        val entityNames = mutableListOf<String>()

        if (annotation != null) {
            val versionAttr = annotation.findAttributeValue("version")
            if (versionAttr != null) {
                try { version = versionAttr.text.trim().toInt() } catch (_: Exception) {}
            }
            val entitiesAttr = annotation.findAttributeValue("entities")
            if (entitiesAttr != null) {
                val refs = PsiTreeUtil.findChildrenOfType(entitiesAttr, PsiClassObjectAccessExpression::class.java)
                for (ref in refs) {
                    val typeName = ref.operand.type.canonicalText
                    entityNames.add(typeName.substringAfterLast("."))
                }
                if (entityNames.isEmpty()) {
                    val text = entitiesAttr.text
                    val regex = Regex("""(\w+)::class""")
                    regex.findAll(text).forEach { entityNames.add(it.groupValues[1]) }
                }
            }
        }

        return RoomDatabase(name = cls.name ?: "", entities = entityNames, version = version)
    }

    private fun extractRoomEntity(cls: PsiClass): RoomEntity {
        val fields = mutableListOf<EntityField>()
        val primaryKeys = mutableListOf<String>()
        val indices = mutableListOf<String>()

        val entityAnnotation = cls.getAnnotation("androidx.room.Entity")
        if (entityAnnotation != null) {
            val pkAttr = entityAnnotation.findAttributeValue("primaryKeys")
            if (pkAttr != null) {
                val text = pkAttr.text
                val regex = Regex(""""(\w+)"""")
                regex.findAll(text).forEach { primaryKeys.add(it.groupValues[1]) }
            }
        }

        for (field in cls.allFields) {
            if (field.getAnnotation("androidx.room.Ignore") != null) continue
            val type = field.type.canonicalText
            val nullable = type.endsWith("?") ||
                    field.getAnnotation("org.jetbrains.annotations.Nullable") != null ||
                    field.getAnnotation("androidx.annotation.Nullable") != null
            fields.add(EntityField(name = field.name, type = type, nullable = nullable))

            if (field.getAnnotation("androidx.room.PrimaryKey") != null) {
                primaryKeys.add(field.name)
            }
        }

        val refined = LanguageAdapter.all(cls.project).firstNotNullOfOrNull { it.refineRoomEntity(cls) }
        if (refined != null) {
            fields.clear()
            primaryKeys.clear()
            fields.addAll(refined.first)
            primaryKeys.addAll(refined.second)
        }

        val tableName = extractTableName(entityAnnotation, cls.name ?: "")

        return RoomEntity(
            name = cls.name ?: "",
            tableName = tableName,
            fields = fields,
            primaryKey = primaryKeys.distinct(),
            indices = indices,
            relations = emptyList()
        )
    }

    private fun extractTableName(annotation: PsiAnnotation?, defaultName: String): String {
        if (annotation == null) return defaultName.lowercase()
        val tableAttr = annotation.findAttributeValue("tableName")
        if (tableAttr != null) {
            val text = tableAttr.text.trim().removeSurrounding("\"")
            if (text.isNotEmpty() && text != "null") return text
        }
        return defaultName.lowercase()
    }

    private fun extractRoomDao(cls: PsiClass): RoomDao {
        val methods = mutableListOf<DaoMethod>()

        for (method in cls.methods) {
            val queryAnn = method.getAnnotation("androidx.room.Query")
            val insertAnn = method.getAnnotation("androidx.room.Insert")
            val updateAnn = method.getAnnotation("androidx.room.Update")
            val deleteAnn = method.getAnnotation("androidx.room.Delete")

            val (sql, ann) = when {
                queryAnn != null -> {
                    val sqlVal = queryAnn.findAttributeValue("value")?.text?.removeSurrounding("\"") ?: ""
                    sqlVal to "@Query"
                }
                insertAnn != null -> null to "@Insert"
                updateAnn != null -> null to "@Update"
                deleteAnn != null -> null to "@Delete"
                else -> continue
            }

            methods.add(DaoMethod(
                name = method.name,
                sql = sql,
                returnType = method.returnType?.canonicalText ?: "Unit",
                annotation = ann
            ))
        }

        if (methods.isEmpty()) {
            val refined = LanguageAdapter.all(cls.project)
                .firstNotNullOfOrNull { it.refineRoomDaoMethods(cls) }
            if (refined != null) methods.addAll(refined)
        }

        return RoomDao(name = cls.name ?: "", methods = methods)
    }

    // ==================== RETROFIT ====================

    private fun analyzeRetrofit(project: Project, detailTarget: String?): FrameworkViewResult {
        val scope = GlobalSearchScope.projectScope(project)
        val interfaces = mutableListOf<RetrofitInterface>()
        val adapters = LanguageAdapter.all(project)

        val exts = adapters.flatMap { it.fileExtensions() }.toSet().ifEmpty { setOf("kt", "java") }
        val files = exts.flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
        for (vf in files) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val adapter = adapters.firstOrNull {
                runCatching { it.canHandle(pf) }.getOrDefault(false)
            } ?: continue
            adapter.collectRetrofitInterfaces(project, pf)?.let { interfaces.addAll(it) }
        }

        return FrameworkViewResult(
            framework = "retrofit",
            summary = FrameworkSummary(totalComponents = interfaces.size, detailTarget = detailTarget),
            retrofit = RetrofitView(interfaces = interfaces)
        )
    }

    // ==================== HILT ====================

    private fun analyzeHilt(project: Project, detailTarget: String?): FrameworkViewResult {
        val scope = GlobalSearchScope.projectScope(project)
        val modules = findAnnotatedClasses(project, scope, "dagger.Module")
        val entryPointClasses = findAnnotatedClasses(project, scope, "dagger.hilt.android.AndroidEntryPoint")
        val hiltEntryPoints = findAnnotatedClasses(project, scope, "dagger.hilt.EntryPoint")

        return FrameworkViewResult(
            framework = "hilt",
            summary = FrameworkSummary(
                totalComponents = modules.size + entryPointClasses.size + hiltEntryPoints.size,
                detailTarget = detailTarget
            ),
            hilt = HiltView(
                modules = modules.map { cls -> extractHiltModule(cls) },
                components = entryPointClasses.map { cls ->
                    HiltComponent(
                        name = cls.qualifiedName ?: cls.name ?: "",
                        scope = extractAnnotationValue(cls, "dagger.hilt.android.AndroidEntryPoint"),
                        parent = cls.superClass?.qualifiedName
                    )
                },
                entryPoints = hiltEntryPoints.map { cls ->
                    val installedIn = extractInstalledIn(cls)
                    HiltEntryPoint(
                        name = cls.qualifiedName ?: cls.name ?: "",
                        installedIn = installedIn,
                        methods = cls.methods.map { "${it.name}(): ${it.returnType?.canonicalText ?: "Unit"}" }
                    )
                }
            )
        )
    }

    private fun extractHiltModule(cls: PsiClass): HiltModule {
        val installedIn = extractInstalledIn(cls)
        val provides = mutableListOf<HiltProvides>()

        for (method in cls.methods) {
            val providesAnn = method.getAnnotation("dagger.Provides")
            val bindsAnn = method.getAnnotation("dagger.Binds")
            if (providesAnn != null || bindsAnn != null) {
                val scopeAnn = method.annotations.firstOrNull { ann ->
                    val fqn = ann.qualifiedName ?: ""
                    fqn.contains("Singleton") || fqn.contains("ActivityScoped") ||
                    fqn.contains("ViewModelScoped") || fqn.contains("FragmentScoped")
                }
                provides.add(HiltProvides(
                    methodName = method.name,
                    returnType = method.returnType?.canonicalText ?: "Unit",
                    scope = scopeAnn?.qualifiedName?.substringAfterLast(".")
                ))
            }
        }

        if (provides.isEmpty()) {
            val refined = LanguageAdapter.all(cls.project)
                .firstNotNullOfOrNull { it.refineHiltProvides(cls) }
            if (refined != null) provides.addAll(refined)
        }

        return HiltModule(
            name = cls.name ?: "",
            installedIn = installedIn,
            provides = provides
        )
    }

    private fun extractInstalledIn(cls: PsiClass): String {
        val installIn = cls.getAnnotation("dagger.hilt.InstallIn")
            ?: cls.getAnnotation("dagger.hilt.android.InstallIn")
            ?: return ""
        val value = installIn.findAttributeValue("value") ?: return ""
        val text = value.text
        val regex = Regex("""(\w+)(?:::class|\.class)""")
        return regex.findAll(text).map { it.groupValues[1] }.joinToString(", ")
    }

    private fun extractAnnotationValue(cls: PsiClass, annotationFqn: String): String {
        val ann = cls.getAnnotation(annotationFqn) ?: return ""
        val value = ann.findAttributeValue("value") ?: return ""
        return value.text.removeSurrounding("\"")
    }

    // ==================== COMPOSE ====================

    private fun analyzeCompose(project: Project, detailTarget: String?): FrameworkViewResult {
        val scope = GlobalSearchScope.projectScope(project)
        val composables = mutableListOf<ComposableInfo>()
        val themes = mutableListOf<ThemeInfo>()
        val stateHolders = mutableListOf<StateHolderInfo>()
        val adapters = LanguageAdapter.all(project)
        val exts = adapters.flatMap { it.fileExtensions() }.toSet().ifEmpty { setOf("kt", "java") }
        val files = exts.flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }

        for (vf in files) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val adapter = adapters.firstOrNull {
                runCatching { it.canHandle(pf) }.getOrDefault(false)
            } ?: continue
            val info = adapter.collectComposeInfo(project, pf) ?: continue
            composables.addAll(info.composables)
            themes.addAll(info.themes)
            stateHolders.addAll(info.stateHolders)
        }

        return FrameworkViewResult(
            framework = "compose",
            summary = FrameworkSummary(totalComponents = composables.size, detailTarget = detailTarget),
            compose = ComposeView(
                composables = composables,
                themes = themes,
                stateHolders = stateHolders
            )
        )
    }

    // ==================== NAVIGATION ====================

    private fun analyzeNavigation(project: Project, detailTarget: String?): FrameworkViewResult {
        val scope = GlobalSearchScope.projectScope(project)
        val graphs = mutableListOf<NavGraph>()
        val destinations = mutableListOf<NavDestination>()
        val deepLinks = mutableListOf<NavDeepLink>()

        val navXmls = FilenameIndex.getAllFilesByExt(project, "xml", scope)
            .filter { it.path.contains("navigation") }
        for (vf in navXmls) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? XmlFile ?: continue
            val rootTag = pf.rootTag ?: continue
            if (rootTag.name != "navigation") continue
            val relPath = ProjectUtils.toRelativePath(project, vf)
            val graphId = rootTag.getAttributeValue("android:id")?.removePrefix("@+id/") ?: vf.nameWithoutExtension
            val startDest = rootTag.getAttributeValue("app:startDestination")?.removePrefix("@id/") ?: ""

            graphs.add(NavGraph(id = graphId, startDestination = startDest, file = relPath))

            for (child in rootTag.subTags) {
                if (child.name == "fragment" || child.name == "activity" || child.name == "dialog") {
                    val destId = child.getAttributeValue("android:id")?.removePrefix("@+id/") ?: ""
                    val className = child.getAttributeValue("android:name")
                    val args = child.findSubTags("argument").map { arg ->
                        NavArgument(
                            name = arg.getAttributeValue("android:name") ?: "",
                            type = arg.getAttributeValue("app:argType") ?: "string",
                            nullable = arg.getAttributeValue("app:nullable") == "true",
                            defaultValue = arg.getAttributeValue("android:defaultValue")
                        )
                    }
                    destinations.add(NavDestination(
                        id = destId, className = className, arguments = args.toList(), graphId = graphId
                    ))

                    for (dl in child.findSubTags("deepLink")) {
                        val uri = dl.getAttributeValue("app:uri") ?: dl.getAttributeValue("android:uri") ?: ""
                        deepLinks.add(NavDeepLink(uri = uri, destination = destId))
                    }
                }
            }
        }

        val adapters = LanguageAdapter.all(project)
        val exts = adapters.flatMap { it.fileExtensions() }.toSet().ifEmpty { setOf("kt", "java") }
        val sourceFiles = exts.flatMap { FilenameIndex.getAllFilesByExt(project, it, scope) }
        for (vf in sourceFiles) {
            val pf = PsiManager.getInstance(project).findFile(vf) ?: continue
            val adapter = adapters.firstOrNull {
                runCatching { it.canHandle(pf) }.getOrDefault(false)
            } ?: continue
            val routes = adapter.collectNavComposableRoutes(project, pf) ?: continue
            for (route in routes) {
                if (destinations.none { it.id == route.id }) destinations.add(route)
            }
        }

        return FrameworkViewResult(
            framework = "navigation",
            summary = FrameworkSummary(
                totalComponents = graphs.size + destinations.size,
                detailTarget = detailTarget
            ),
            navigation = NavigationView(
                graphs = graphs,
                destinations = destinations,
                deepLinks = deepLinks
            )
        )
    }

    // ==================== SHARED ====================

    private fun findAnnotatedClasses(project: Project, scope: GlobalSearchScope, annotationFqn: String): List<PsiClass> {
        val facade = JavaPsiFacade.getInstance(project)
        val annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project))
            ?: return emptyList()
        return AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll().toList()
    }
}

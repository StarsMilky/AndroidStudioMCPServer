package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.models.args.FrameworkType
import com.androidstudio.mcpserver.models.args.QueryFrameworkArgs
import com.androidstudio.mcpserver.models.results.ComposableInfo
import com.androidstudio.mcpserver.models.results.ComposeView
import com.androidstudio.mcpserver.models.results.DaoMethod
import com.androidstudio.mcpserver.models.results.EndpointParam
import com.androidstudio.mcpserver.models.results.EntityField
import com.androidstudio.mcpserver.models.results.FrameworkSummary
import com.androidstudio.mcpserver.models.results.FrameworkViewResult
import com.androidstudio.mcpserver.models.results.HiltComponent
import com.androidstudio.mcpserver.models.results.HiltEntryPoint
import com.androidstudio.mcpserver.models.results.HiltModule
import com.androidstudio.mcpserver.models.results.HiltProvides
import com.androidstudio.mcpserver.models.results.HiltView
import com.androidstudio.mcpserver.models.results.NavArgument
import com.androidstudio.mcpserver.models.results.NavDeepLink
import com.androidstudio.mcpserver.models.results.NavDestination
import com.androidstudio.mcpserver.models.results.NavGraph
import com.androidstudio.mcpserver.models.results.NavigationView
import com.androidstudio.mcpserver.models.results.RetrofitEndpoint
import com.androidstudio.mcpserver.models.results.RetrofitInterface
import com.androidstudio.mcpserver.models.results.RetrofitView
import com.androidstudio.mcpserver.models.results.RoomDao
import com.androidstudio.mcpserver.models.results.RoomDatabase
import com.androidstudio.mcpserver.models.results.RoomEntity
import com.androidstudio.mcpserver.models.results.RoomView
import com.androidstudio.mcpserver.models.results.StateHolderInfo
import com.androidstudio.mcpserver.models.results.ThemeInfo
import com.androidstudio.mcpserver.util.ProjectUtils
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

object FrameworkAnalyzer {

    fun analyze(project: Project, args: QueryFrameworkArgs): FrameworkViewResult {
        return PsiUtils.smartReadAction(project) {
            when (args.framework) {
                FrameworkType.ROOM -> analyzeRoom(project, args.detailTarget)
                FrameworkType.RETROFIT -> analyzeRetrofit(project, args.detailTarget)
                FrameworkType.HILT -> analyzeHilt(project, args.detailTarget)
                FrameworkType.COMPOSE -> analyzeCompose(project, args.detailTarget)
                FrameworkType.NAVIGATION -> analyzeNavigation(project, args.detailTarget)
            }
        }
    }

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

        val ktClass = cls.navigationElement
        if (ktClass is KtClass) {
            fields.clear()
            primaryKeys.clear()
            for (param in ktClass.primaryConstructorParameters) {
                val typeName = param.typeReference?.text ?: "Any"
                val isNullable = typeName.endsWith("?")
                fields.add(EntityField(name = param.name ?: "", type = typeName, nullable = isNullable))
            }
            for (prop in ktClass.getProperties()) {
                if (prop.annotationEntries.any { it.shortName?.asString() == "PrimaryKey" }) {
                    primaryKeys.add(prop.name ?: "")
                }
            }
            for (param in ktClass.primaryConstructorParameters) {
                if (param.annotationEntries.any { it.shortName?.asString() == "PrimaryKey" }) {
                    primaryKeys.add(param.name ?: "")
                }
            }
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

        val ktClass = cls.navigationElement
        if (ktClass is KtClass && methods.isEmpty()) {
            for (fn in PsiTreeUtil.findChildrenOfType(ktClass, KtNamedFunction::class.java)) {
                val annotations = fn.annotationEntries
                val queryAnn = annotations.firstOrNull { it.shortName?.asString() == "Query" }
                val insertAnn = annotations.firstOrNull { it.shortName?.asString() == "Insert" }
                val updateAnn = annotations.firstOrNull { it.shortName?.asString() == "Update" }
                val deleteAnn = annotations.firstOrNull { it.shortName?.asString() == "Delete" }

                val (sql, ann) = when {
                    queryAnn != null -> {
                        val args = queryAnn.valueArguments
                        val sqlText = args.firstOrNull()?.getArgumentExpression()?.text?.removeSurrounding("\"") ?: ""
                        sqlText to "@Query"
                    }
                    insertAnn != null -> null to "@Insert"
                    updateAnn != null -> null to "@Update"
                    deleteAnn != null -> null to "@Delete"
                    else -> continue
                }

                methods.add(DaoMethod(
                    name = fn.name ?: "",
                    sql = sql,
                    returnType = fn.typeReference?.text ?: "Unit",
                    annotation = ann
                ))
            }
        }

        return RoomDao(name = cls.name ?: "", methods = methods)
    }

    // ==================== RETROFIT ====================

    private fun analyzeRetrofit(project: Project, detailTarget: String?): FrameworkViewResult {
        val scope = GlobalSearchScope.projectScope(project)
        val interfaces = mutableListOf<RetrofitInterface>()

        val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
        val javaFiles = FilenameIndex.getAllFilesByExt(project, "java", scope)

        for (vf in javaFiles) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile ?: continue
            for (cls in pf.classes) {
                if (!cls.isInterface) continue
                val hasRetrofitMethods = cls.methods.any { m ->
                    m.getAnnotation("retrofit2.http.GET") != null ||
                    m.getAnnotation("retrofit2.http.POST") != null ||
                    m.getAnnotation("retrofit2.http.PUT") != null ||
                    m.getAnnotation("retrofit2.http.DELETE") != null ||
                    m.getAnnotation("retrofit2.http.PATCH") != null
                }
                if (!hasRetrofitMethods) continue
                interfaces.add(extractRetrofitInterface(cls))
            }
        }

        for (vf in ktFiles) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
            for (decl in pf.declarations) {
                if (decl !is KtClass || !decl.isInterface()) continue
                val hasRetrofitAnnotations = PsiTreeUtil.findChildrenOfType(decl, KtNamedFunction::class.java).any { fn ->
                    fn.annotationEntries.any { ann ->
                        val name = ann.shortName?.asString() ?: ""
                        name in listOf("GET", "POST", "PUT", "DELETE", "PATCH")
                    }
                }
                if (!hasRetrofitAnnotations) continue
                interfaces.add(extractKotlinRetrofitInterface(project, decl))
            }
        }

        return FrameworkViewResult(
            framework = "retrofit",
            summary = FrameworkSummary(totalComponents = interfaces.size, detailTarget = detailTarget),
            retrofit = RetrofitView(interfaces = interfaces)
        )
    }

    private fun extractRetrofitInterface(cls: PsiClass): RetrofitInterface {
        val endpoints = mutableListOf<RetrofitEndpoint>()
        val httpMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

        for (method in cls.methods) {
            for (httpMethod in httpMethods) {
                val ann = method.getAnnotation("retrofit2.http.$httpMethod") ?: continue
                val path = ann.findAttributeValue("value")?.text?.removeSurrounding("\"") ?: ""
                val params = method.parameterList.parameters.map { p ->
                    val paramAnn = p.annotations.firstOrNull()?.qualifiedName?.substringAfterLast(".") ?: "Body"
                    EndpointParam(name = p.name ?: "", type = p.type.canonicalText, annotation = "@$paramAnn")
                }
                endpoints.add(RetrofitEndpoint(
                    method = method.name,
                    path = path,
                    httpMethod = httpMethod,
                    returnType = method.returnType?.canonicalText ?: "Unit",
                    parameters = params
                ))
            }
        }

        return RetrofitInterface(
            name = cls.qualifiedName ?: cls.name ?: "",
            baseUrl = null,
            endpoints = endpoints
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

        val ktClass = cls.navigationElement
        if (ktClass is KtClass && provides.isEmpty()) {
            for (fn in PsiTreeUtil.findChildrenOfType(ktClass, KtNamedFunction::class.java)) {
                val hasProvides = fn.annotationEntries.any { it.shortName?.asString() == "Provides" }
                val hasBinds = fn.annotationEntries.any { it.shortName?.asString() == "Binds" }
                if (hasProvides || hasBinds) {
                    provides.add(HiltProvides(
                        methodName = fn.name ?: "",
                        returnType = fn.typeReference?.text ?: "Unit",
                        scope = null
                    ))
                }
            }
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

        val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
        for (vf in ktFiles) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
            val relPath = ProjectUtils.toRelativePath(project, vf)

            for (fn in PsiTreeUtil.findChildrenOfType(pf, KtNamedFunction::class.java)) {
                val isComposable = fn.annotationEntries.any { ann ->
                    ann.shortName?.asString() == "Composable"
                }
                if (!isComposable) continue

                val isPreview = fn.annotationEntries.any { ann ->
                    ann.shortName?.asString() == "Preview"
                }

                val params = fn.valueParameters.map { p ->
                    "${p.name ?: "_"}: ${p.typeReference?.text ?: "Any"}"
                }

                val doc = PsiDocumentManager.getInstance(project).getDocument(pf)
                val line = doc?.getLineNumber(fn.textOffset)?.plus(1) ?: 0
                val name = fn.name ?: "<anonymous>"

                composables.add(ComposableInfo(
                    name = name,
                    file = relPath,
                    line = line,
                    parameters = params,
                    preview = isPreview
                ))

                if (name.contains("Theme", ignoreCase = true)) {
                    themes.add(ThemeInfo(
                        name = name,
                        file = relPath,
                        colorScheme = null
                    ))
                }
            }

            for (cls in PsiTreeUtil.findChildrenOfType(pf, KtClass::class.java)) {
                val hasStateProps = cls.getProperties().any { prop ->
                    val typeText = prop.typeReference?.text ?: ""
                    typeText.contains("MutableState") || typeText.contains("StateFlow") ||
                    typeText.contains("MutableStateFlow")
                }
                if (hasStateProps) {
                    stateHolders.add(StateHolderInfo(
                        name = cls.name ?: "",
                        stateType = "ViewModel/StateHolder",
                        file = relPath
                    ))
                }
            }
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

        val ktFiles = FilenameIndex.getAllFilesByExt(project, "kt", scope)
        for (vf in ktFiles) {
            val pf = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
            val hasNavHost = pf.text.contains("NavHost") || pf.text.contains("composable(")
            if (!hasNavHost) continue
            val relPath = ProjectUtils.toRelativePath(project, vf)

            val composableRegex = Regex("""composable\s*\(\s*(?:route\s*=\s*)?["']([^"']+)["']""")
            composableRegex.findAll(pf.text).forEach { match ->
                val route = match.groupValues[1]
                if (destinations.none { it.id == route }) {
                    destinations.add(NavDestination(
                        id = route, className = null,
                        arguments = emptyList(), graphId = relPath
                    ))
                }
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

    private fun extractKotlinRetrofitInterface(project: Project, ktClass: KtClass): RetrofitInterface {
        val endpoints = mutableListOf<RetrofitEndpoint>()
        val httpMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")

        for (fn in PsiTreeUtil.findChildrenOfType(ktClass, KtNamedFunction::class.java)) {
            for (httpMethod in httpMethods) {
                val ann = fn.annotationEntries.firstOrNull { it.shortName?.asString() == httpMethod } ?: continue
                val path = ann.valueArguments.firstOrNull()?.getArgumentExpression()?.text
                    ?.removeSurrounding("\"") ?: ""
                val params = fn.valueParameters.map { p ->
                    val paramAnn = p.annotationEntries.firstOrNull()?.shortName?.asString() ?: "Body"
                    EndpointParam(
                        name = p.name ?: "",
                        type = p.typeReference?.text ?: "Any",
                        annotation = "@$paramAnn"
                    )
                }
                endpoints.add(RetrofitEndpoint(
                    method = fn.name ?: "",
                    path = path,
                    httpMethod = httpMethod,
                    returnType = fn.typeReference?.text ?: "Unit",
                    parameters = params
                ))
            }
        }

        return RetrofitInterface(
            name = ktClass.fqName?.asString() ?: ktClass.name ?: "",
            baseUrl = null,
            endpoints = endpoints
        )
    }
}

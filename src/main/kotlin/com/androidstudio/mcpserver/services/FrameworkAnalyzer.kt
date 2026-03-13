package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.models.args.FrameworkType
import com.androidstudio.mcpserver.models.args.QueryFrameworkArgs
import com.androidstudio.mcpserver.models.results.*
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch

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
                databases = databases.map { cls ->
                    RoomDatabase(name = cls.name ?: "", entities = emptyList(), version = 1)
                },
                entities = entities.map { cls ->
                    RoomEntity(
                        name = cls.name ?: "",
                        tableName = cls.name?.lowercase() ?: "",
                        fields = emptyList(),
                        primaryKey = emptyList(),
                        indices = emptyList(),
                        relations = emptyList()
                    )
                },
                daos = daos.map { cls ->
                    RoomDao(name = cls.name ?: "", methods = emptyList())
                },
                migrations = emptyList()
            )
        )
    }

    private fun analyzeRetrofit(project: Project, detailTarget: String?): FrameworkViewResult {
        return FrameworkViewResult(
            framework = "retrofit",
            summary = FrameworkSummary(totalComponents = 0, detailTarget = detailTarget),
            retrofit = RetrofitView(interfaces = emptyList())
        )
    }

    private fun analyzeHilt(project: Project, detailTarget: String?): FrameworkViewResult {
        val scope = GlobalSearchScope.projectScope(project)
        val modules = findAnnotatedClasses(project, scope, "dagger.Module")

        return FrameworkViewResult(
            framework = "hilt",
            summary = FrameworkSummary(totalComponents = modules.size, detailTarget = detailTarget),
            hilt = HiltView(
                modules = modules.map { cls ->
                    HiltModule(
                        name = cls.name ?: "",
                        installedIn = "",
                        provides = emptyList()
                    )
                },
                components = emptyList(),
                entryPoints = emptyList()
            )
        )
    }

    private fun analyzeCompose(project: Project, detailTarget: String?): FrameworkViewResult {
        return FrameworkViewResult(
            framework = "compose",
            summary = FrameworkSummary(totalComponents = 0, detailTarget = detailTarget),
            compose = ComposeView(composables = emptyList(), themes = emptyList(), stateHolders = emptyList())
        )
    }

    private fun analyzeNavigation(project: Project, detailTarget: String?): FrameworkViewResult {
        return FrameworkViewResult(
            framework = "navigation",
            summary = FrameworkSummary(totalComponents = 0, detailTarget = detailTarget),
            navigation = NavigationView(graphs = emptyList(), destinations = emptyList(), deepLinks = emptyList())
        )
    }

    private fun findAnnotatedClasses(project: Project, scope: GlobalSearchScope, annotationFqn: String): List<PsiClass> {
        val facade = JavaPsiFacade.getInstance(project)
        val annotationClass = facade.findClass(annotationFqn, GlobalSearchScope.allScope(project))
            ?: return emptyList()
        return AnnotatedElementsSearch.searchPsiClasses(annotationClass, scope).findAll().toList()
    }
}

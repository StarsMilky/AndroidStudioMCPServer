package com.codeintel.mcpserver.framework

import com.codeintel.mcpserver.models.results.FrameworkViewResult
import com.codeintel.mcpserver.services.FrameworkAnalyzer
import com.intellij.openapi.project.Project

class RoomFrameworkAdapter : FrameworkAdapter {
    override val name = "ROOM"
    override fun scan(project: Project, detailTarget: String?): FrameworkViewResult =
        FrameworkAnalyzer.analyzeRoomPublic(project, detailTarget)
}

class RetrofitFrameworkAdapter : FrameworkAdapter {
    override val name = "RETROFIT"
    override fun scan(project: Project, detailTarget: String?): FrameworkViewResult =
        FrameworkAnalyzer.analyzeRetrofitPublic(project, detailTarget)
}

class HiltFrameworkAdapter : FrameworkAdapter {
    override val name = "HILT"
    override fun scan(project: Project, detailTarget: String?): FrameworkViewResult =
        FrameworkAnalyzer.analyzeHiltPublic(project, detailTarget)
}

class ComposeFrameworkAdapter : FrameworkAdapter {
    override val name = "COMPOSE"
    override fun scan(project: Project, detailTarget: String?): FrameworkViewResult =
        FrameworkAnalyzer.analyzeComposePublic(project, detailTarget)
}

class NavigationFrameworkAdapter : FrameworkAdapter {
    override val name = "NAVIGATION"
    override fun scan(project: Project, detailTarget: String?): FrameworkViewResult =
        FrameworkAnalyzer.analyzeNavigationPublic(project, detailTarget)
}

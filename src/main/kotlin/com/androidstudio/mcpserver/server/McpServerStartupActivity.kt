package com.androidstudio.mcpserver.server

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class McpServerStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(McpServerStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.info("Project opened: ${project.name}, starting MCP PSI Server...")
        McpServerManager.getInstance().startIfNeeded(project)
    }
}

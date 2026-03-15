package com.codeintel.mcpserver.util

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProjectResolver {

    fun resolve(arguments: JsonObject?): Project {
        val openProjects = ProjectManager.getInstance().openProjects
        if (openProjects.isEmpty()) {
            throw ToolException(McpErrorCode.PSI_ERROR, mapOf("reason" to "No project open in IDE"))
        }

        val projectPath = arguments?.get("project_path")?.jsonPrimitive?.content

        if (openProjects.size == 1) return openProjects.first()

        if (projectPath != null) {
            return openProjects.find { it.basePath == projectPath }
                ?: throw ToolException(
                    McpErrorCode.PSI_ERROR,
                    mapOf("reason" to "Project not found: $projectPath")
                )
        }

        return openProjects.first()
    }
}

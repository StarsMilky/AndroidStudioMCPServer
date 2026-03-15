package com.codeintel.mcpserver.server

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Path

object ClientAutoConfigurator {

    private val log = Logger.getInstance(ClientAutoConfigurator::class.java)
    private val prettyJson = Json { prettyPrint = true }

    data class ConfigResult(val success: Boolean, val filePath: String, val message: String)

    data class CursorConfigStatus(
        val configured: Boolean,
        val configFilePath: String,
        val configuredUrl: String? = null
    )

    private fun getCursorConfigFile(): File =
        Path.of(System.getProperty("user.home"), ".cursor", "mcp.json").toFile()

    fun isCursorConfigured(): CursorConfigStatus {
        val configFile = getCursorConfigFile()
        val serverUrl = _root_ide_package_.com.codeintel.mcpserver.server.McpServerManager.getInstance().getUrl()
        val entryName = _root_ide_package_.com.codeintel.mcpserver.server.McpServerManager.MCP_SERVER_ENTRY_NAME

        if (!configFile.exists() || configFile.length() == 0L) {
            return CursorConfigStatus(false, configFile.absolutePath)
        }

        return try {
            val json = Json.parseToJsonElement(configFile.readText()).jsonObject
            val servers = json["mcpServers"]?.jsonObject ?: return CursorConfigStatus(false, configFile.absolutePath)
            val entry = servers[entryName]?.jsonObject ?: return CursorConfigStatus(false, configFile.absolutePath)
            val url = entry["url"]?.jsonPrimitive?.content

            when {
                url == serverUrl -> CursorConfigStatus(true, configFile.absolutePath, url)
                url != null -> CursorConfigStatus(true, configFile.absolutePath, url)
                else -> CursorConfigStatus(false, configFile.absolutePath)
            }
        } catch (e: Exception) {
            log.warn("Failed to check Cursor config", e)
            CursorConfigStatus(false, configFile.absolutePath)
        }
    }

    fun configureCursor(): ConfigResult {
        val configFile = getCursorConfigFile()
        return writeServerEntry(configFile, "Cursor")
    }

    fun getMcpConfigJson(): String {
        val serverUrl = _root_ide_package_.com.codeintel.mcpserver.server.McpServerManager.getInstance().getUrl()
        val entryName = _root_ide_package_.com.codeintel.mcpserver.server.McpServerManager.MCP_SERVER_ENTRY_NAME
        val config = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject(entryName) {
                    put("url", serverUrl)
                }
            }
        }
        return prettyJson.encodeToString(JsonObject.serializer(), config)
    }

    private fun writeServerEntry(configFile: File, clientName: String): ConfigResult {
        val serverUrl = _root_ide_package_.com.codeintel.mcpserver.server.McpServerManager.getInstance().getUrl()
        val entryName = _root_ide_package_.com.codeintel.mcpserver.server.McpServerManager.MCP_SERVER_ENTRY_NAME

        try {
            configFile.parentFile?.mkdirs()

            val existingJson: JsonObject = if (configFile.exists() && configFile.length() > 0) {
                try {
                    Json.parseToJsonElement(configFile.readText()).jsonObject
                } catch (e: Exception) {
                    log.warn("Failed to parse existing $clientName config, creating new", e)
                    JsonObject(emptyMap())
                }
            } else {
                JsonObject(emptyMap())
            }

            val existingServers = existingJson["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())

            val updatedServers = buildJsonObject {
                for ((key, value) in existingServers) {
                    if (key != entryName) put(key, value)
                }
                putJsonObject(entryName) {
                    put("url", serverUrl)
                }
            }

            val updatedConfig = buildJsonObject {
                for ((key, value) in existingJson) {
                    if (key != "mcpServers") put(key, value)
                }
                put("mcpServers", updatedServers)
            }

            configFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), updatedConfig))

            val message = "$clientName config updated: $entryName → $serverUrl"
            log.info(message)
            return ConfigResult(true, configFile.absolutePath, message)
        } catch (e: Exception) {
            val message = "Failed to configure $clientName: ${e.message}"
            log.error(message, e)
            return ConfigResult(false, configFile.absolutePath, message)
        }
    }

    fun showResultNotification(result: ConfigResult) {
        val type = if (result.success) NotificationType.INFORMATION else NotificationType.ERROR
        val title = if (result.success) "MCP Client Configured" else "MCP Client Configuration Failed"

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("MCP Code Intelligence")
            .createNotification(title, result.message, type)

        if (result.success) {
            notification.addAction(object : com.intellij.notification.NotificationAction("Open Config File") {
                override fun actionPerformed(
                    e: com.intellij.openapi.actionSystem.AnActionEvent,
                    notification: com.intellij.notification.Notification
                ) {
                    val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(result.filePath)
                    if (vFile != null) {
                        val project = ProjectManager.getInstance().openProjects.firstOrNull()
                        if (project != null) {
                            FileEditorManager.getInstance(project).openFile(vFile, true)
                        }
                    }
                    notification.expire()
                }
            })
        }

        notification.notify(ProjectManager.getInstance().openProjects.firstOrNull())
    }
}

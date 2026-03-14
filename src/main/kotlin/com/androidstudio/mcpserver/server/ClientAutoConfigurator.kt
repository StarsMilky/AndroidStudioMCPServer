package com.androidstudio.mcpserver.server

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Path

object ClientAutoConfigurator {

    private val log = Logger.getInstance(ClientAutoConfigurator::class.java)

    data class ConfigResult(val success: Boolean, val filePath: String, val message: String)

    fun configureCursor(): ConfigResult {
        val configFile = Path.of(System.getProperty("user.home"), ".cursor", "mcp.json").toFile()
        return writeServerEntry(configFile, "Cursor")
    }

    fun configureClaude(): ConfigResult {
        val appData = System.getenv("APPDATA")
            ?: System.getProperty("user.home") + "/AppData/Roaming"
        val configFile = Path.of(appData, "Claude", "claude_desktop_config.json").toFile()
        return writeServerEntry(configFile, "Claude Desktop")
    }

    private fun writeServerEntry(configFile: File, clientName: String): ConfigResult {
        val serverUrl = McpServerManager.getInstance().getUrl()
        val entryName = McpServerManager.MCP_SERVER_ENTRY_NAME

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

            val prettyJson = Json { prettyPrint = true }
            configFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), updatedConfig))

            val message = "$clientName 配置已更新: $entryName → $serverUrl"
            log.info(message)
            return ConfigResult(true, configFile.absolutePath, message)
        } catch (e: Exception) {
            val message = "配置 $clientName 失败: ${e.message}"
            log.error(message, e)
            return ConfigResult(false, configFile.absolutePath, message)
        }
    }

    fun showResultNotification(result: ConfigResult) {
        val type = if (result.success) NotificationType.INFORMATION else NotificationType.ERROR
        val title = if (result.success) "MCP 客户端已配置" else "MCP 客户端配置失败"

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("MCP PSI Tools")
            .createNotification(title, result.message, type)

        if (result.success) {
            notification.addAction(object : com.intellij.notification.NotificationAction("打开配置文件") {
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

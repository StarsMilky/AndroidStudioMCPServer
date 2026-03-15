package com.androidstudio.mcpserver.server

import com.androidstudio.mcpserver.util.MCP_JSON
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class ServerState { STOPPED, STARTING, RUNNING, ERROR }

data class ToolInfo(val name: String, val description: String)

fun interface ServerStateListener {
    fun onStateChanged(state: ServerState)
}

@Service(Service.Level.APP)
class McpServerManager : Disposable {

    private val log = Logger.getInstance(McpServerManager::class.java)

    private var mcpServer: Server? = null
    private var ktorServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val port = AtomicInteger(0)
    private val state = AtomicReference(ServerState.STOPPED)
    private val listeners = CopyOnWriteArrayList<ServerStateListener>()
    private var errorMessage: String? = null

    fun addStateListener(listener: ServerStateListener) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: ServerStateListener) {
        listeners.remove(listener)
    }

    fun getState(): ServerState = state.get()
    fun getPort(): Int = port.get()
    fun getErrorMessage(): String? = errorMessage
    fun getUrl(): String = "http://127.0.0.1:${port.get()}/mcp"

    fun getRegisteredTools(): List<ToolInfo> = TOOL_REGISTRY

    fun startIfNeeded(project: Project) {
        if (state.get() == ServerState.RUNNING || state.get() == ServerState.STARTING) {
            log.info("MCP Server already started on port ${port.get()}")
            return
        }
        doStart()
    }

    fun restart() {
        doStop()
        doStart()
    }

    fun stop() {
        doStop()
    }

    override fun dispose() {
        doStop()
    }

    private fun doStart() {
        setState(ServerState.STARTING)
        errorMessage = null

        try {
            val server = Server(
                serverInfo = Implementation(
                    name = "android-studio-code-intelligence",
                    version = "1.0.0"
                ),
                options = ServerOptions(
                    capabilities = ServerCapabilities(
                        tools = ServerCapabilities.Tools(listChanged = false),
                    ),
                )
            )

            ToolRegistrar.registerAll(server)
            mcpServer = server

            val selectedPort = findFreePort(DEFAULT_PORT)
            port.set(selectedPort)

            ktorServer = embeddedServer(CIO, host = "127.0.0.1", port = selectedPort) {
                install(ContentNegotiation) {
                    json(MCP_JSON)
                }
                mcpStreamableHttp {
                    server
                }
            }.also { it.start(wait = false) }

            writePortFile(selectedPort)
            setState(ServerState.RUNNING)
            log.info("MCP Code Intelligence Server started on http://127.0.0.1:$selectedPort/mcp")
        } catch (e: Exception) {
            errorMessage = e.message
            setState(ServerState.ERROR)
            log.error("Failed to start MCP Server", e)
        }
    }

    private fun doStop() {
        if (state.get() == ServerState.STOPPED) return

        try {
            ktorServer?.stop(1000, 2000)
            ktorServer = null
            mcpServer = null
            deletePortFile()
            setState(ServerState.STOPPED)
            log.info("MCP Code Intelligence Server stopped")
        } catch (e: Exception) {
            log.error("Error stopping MCP Server", e)
        }
    }

    private fun setState(newState: ServerState) {
        state.set(newState)
        for (listener in listeners) {
            try {
                listener.onStateChanged(newState)
            } catch (e: Exception) {
                log.warn("State listener error", e)
            }
        }
    }

    private fun findFreePort(startPort: Int): Int {
        for (p in startPort..startPort + MAX_PORT_ATTEMPTS) {
            try {
                ServerSocket(p).use { return p }
            } catch (_: IOException) {
                continue
            }
        }
        ServerSocket(0).use { return it.localPort }
    }

    private fun writePortFile(port: Int) {
        try {
            val portFile = Path.of(System.getProperty("user.home"), PORT_FILE_NAME)
            val pid = ProcessHandle.current().pid()
            portFile.toFile().writeText("""{"port": $port, "pid": $pid}""")
        } catch (e: Exception) {
            log.warn("Failed to write port file", e)
        }
    }

    private fun deletePortFile() {
        try {
            val portFile = Path.of(System.getProperty("user.home"), PORT_FILE_NAME)
            portFile.toFile().delete()
        } catch (e: Exception) {
            log.warn("Failed to delete port file", e)
        }
    }

    companion object {
        const val DEFAULT_PORT = 17532
        const val PORT_FILE_NAME = ".android-studio-mcp-code-intel.json"
        const val MAX_PORT_ATTEMPTS = 10
        const val MCP_SERVER_ENTRY_NAME = "android-studio-code-intel"

        fun getInstance(): McpServerManager =
            ApplicationManager.getApplication().getService(McpServerManager::class.java)

        val TOOL_REGISTRY = listOf(
            ToolInfo("resolve_symbol", "精确解析代码中任意位置的符号"),
            ToolInfo("find_references", "语义级查找符号的引用/调用层级/类型层级"),
            ToolInfo("get_scope", "获取指定代码位置的所有可用符号"),
            ToolInfo("checkpoint", "Local History 操作——创建检查点、查看历史、回滚"),
            ToolInfo("refactor", "语义级安全重构（rename/move/extract/safe_delete）"),
            ToolInfo("query_project", "项目全景图、依赖关系图、API 表面分析"),
            ToolInfo("query_framework", "Room/Retrofit/Hilt/Compose/Navigation 框架视图"),
            ToolInfo("analyze_data_flow", "数据流分析——空安全推理、值传播追踪"),
            ToolInfo("check_rules", "验证代码是否遵守自定义架构规则"),
            ToolInfo("structural_search", "基于 AST 的代码模式搜索"),
            ToolInfo("analyze_quality", "代码质量分析——复杂度热点、死代码"),
            ToolInfo("sandbox", "安全沙盒——反编译、J2K 转换、批量修复"),
        )
    }
}

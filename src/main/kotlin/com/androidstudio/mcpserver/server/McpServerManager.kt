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
            ToolInfo("resolve_symbol", "Resolve symbol at any code position"),
            ToolInfo("find_references", "Semantic reference search with call/type hierarchy"),
            ToolInfo("get_scope", "List all visible symbols at a code position"),
            ToolInfo("checkpoint", "Local History: create checkpoint, view history, rollback"),
            ToolInfo("refactor", "Semantic-safe refactoring (rename/move/extract/safe_delete)"),
            ToolInfo("query_project", "Project overview, dependency graph, API surface analysis"),
            ToolInfo("query_framework", "Room/Retrofit/Hilt/Compose/Navigation framework views"),
            ToolInfo("analyze_data_flow", "Data flow analysis: nullability inference, value tracing"),
            ToolInfo("check_rules", "Validate code against custom architecture rules"),
            ToolInfo("structural_search", "AST-based code pattern search"),
            ToolInfo("analyze_quality", "Code quality: complexity hotspots, dead code"),
            ToolInfo("sandbox", "Sandbox: decompile, J2K conversion, batch fix"),
        )
    }
}

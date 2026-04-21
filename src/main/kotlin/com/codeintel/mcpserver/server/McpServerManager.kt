@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.codeintel.mcpserver.server

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
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class ServerState { STOPPED, STARTING, RUNNING, ERROR }

data class ToolInfo(val name: String, val description: String)

fun interface ServerStateListener {
    fun onStateChanged(state: com.codeintel.mcpserver.server.ServerState)
}

@Service(Service.Level.APP)
class McpServerManager : Disposable {

    private val log = Logger.getInstance(McpServerManager::class.java)

    private var mcpServer: Server? = null
    private var ktorServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val port = AtomicInteger(0)
    private val state = AtomicReference(_root_ide_package_.com.codeintel.mcpserver.server.ServerState.STOPPED)
    private val listeners = CopyOnWriteArrayList<com.codeintel.mcpserver.server.ServerStateListener>()
    private var errorMessage: String? = null

    fun addStateListener(listener: com.codeintel.mcpserver.server.ServerStateListener) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: com.codeintel.mcpserver.server.ServerStateListener) {
        listeners.remove(listener)
    }

    fun getState(): com.codeintel.mcpserver.server.ServerState = state.get()
    fun getPort(): Int = port.get()
    fun getErrorMessage(): String? = errorMessage
    fun getUrl(): String = "http://127.0.0.1:${port.get()}/mcp"

    fun getRegisteredTools(): List<com.codeintel.mcpserver.server.ToolInfo> = TOOL_REGISTRY

    fun startIfNeeded(project: Project) {
        if (state.get() == _root_ide_package_.com.codeintel.mcpserver.server.ServerState.RUNNING || state.get() == _root_ide_package_.com.codeintel.mcpserver.server.ServerState.STARTING) {
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
        setState(_root_ide_package_.com.codeintel.mcpserver.server.ServerState.STARTING)
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

            _root_ide_package_.com.codeintel.mcpserver.server.ToolRegistrar.registerAll(server)
            mcpServer = server

            val selectedPort = findFreePort(DEFAULT_PORT)
            port.set(selectedPort)

            val mcpTransportJson = Json {
                explicitNulls = false
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

            ktorServer = embeddedServer(CIO, host = "127.0.0.1", port = selectedPort) {
                install(ContentNegotiation) { json(mcpTransportJson) }
                mcpStreamableHttp {
                    server
                }
            }.also { it.start(wait = false) }

            writePortFile(selectedPort)
            setState(_root_ide_package_.com.codeintel.mcpserver.server.ServerState.RUNNING)
            log.info("MCP Code Intelligence Server started on http://127.0.0.1:$selectedPort/mcp")
        } catch (e: Exception) {
            errorMessage = e.message
            setState(_root_ide_package_.com.codeintel.mcpserver.server.ServerState.ERROR)
            log.error("Failed to start MCP Server", e)
        }
    }

    private fun doStop() {
        if (state.get() == _root_ide_package_.com.codeintel.mcpserver.server.ServerState.STOPPED) return

        try {
            ktorServer?.stop(1000, 2000)
            ktorServer = null
            mcpServer = null
            deletePortFile()
            setState(_root_ide_package_.com.codeintel.mcpserver.server.ServerState.STOPPED)
            log.info("MCP Code Intelligence Server stopped")
        } catch (e: Exception) {
            log.error("Error stopping MCP Server", e)
        }
    }

    private fun setState(newState: com.codeintel.mcpserver.server.ServerState) {
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
        const val PORT_FILE_NAME = ".mcp-code-intel.json"
        const val MAX_PORT_ATTEMPTS = 10
        const val MCP_SERVER_ENTRY_NAME = "mcp-code-intel"

        fun getInstance(): McpServerManager =
            ApplicationManager.getApplication().getService(McpServerManager::class.java)

        val TOOL_REGISTRY = listOf(
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "resolve_symbol",
                "Resolve symbol at any code position"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "find_symbol",
                "Locate a symbol by simple name or fully-qualified name"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "find_references",
                "Semantic reference search with call/type hierarchy"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "get_scope",
                "List all visible symbols at a code position"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "checkpoint",
                "Local History: create checkpoint, view history, rollback"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "refactor",
                "Semantic-safe refactoring (rename/move/extract/safe_delete)"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "query_project",
                "Project overview, dependency graph, API surface analysis"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "query_framework",
                "Room/Retrofit/Hilt/Compose/Navigation framework views"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "analyze_data_flow",
                "Data flow analysis: nullability inference, value tracing"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "check_rules",
                "Validate code against custom architecture rules"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "structural_search",
                "AST-based code pattern search"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "analyze_quality",
                "Code quality: complexity hotspots, dead code"
            ),
            _root_ide_package_.com.codeintel.mcpserver.server.ToolInfo(
                "sandbox",
                "Sandbox: decompile, J2K conversion, batch fix"
            ),
        )
    }
}

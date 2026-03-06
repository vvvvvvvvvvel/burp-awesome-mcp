package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.portswigger.mcp.core.McpActivityStatus
import net.portswigger.mcp.core.McpOutputLogger
import net.portswigger.mcp.core.McpSettingsSnapshot
import net.portswigger.mcp.core.McpTransportMode
import net.portswigger.mcp.tools.registerBurpTools
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class AgentMcpServerManager(
    private val api: MontoyaApi,
) : ServerManager {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var ktorServer: EmbeddedServer<*, *>? = null

    @Volatile private var shutdownRequested = false

    override fun start(
        settings: McpSettingsSnapshot,
        onStateChanged: (ServerState) -> Unit,
    ) {
        if (shutdownRequested) {
            onStateChanged(ServerState.Failed("server manager is shutting down"))
            return
        }
        onStateChanged(ServerState.Starting)
        val requestSummary =
            buildJsonObject {
                put("enabled", settings.enabled)
                put("host", settings.host)
                put("port", settings.port)
                put("transport", settings.transport.name.lowercase())
            }
        McpOutputLogger.logServer(
            name = "start",
            status = McpActivityStatus.INFO,
            request = requestSummary,
            note = "${settings.host}:${settings.port} (${settings.transport.name.lowercase()})",
        )

        try {
            executor.submit {
                val startedNanos = System.nanoTime()
                try {
                    if (shutdownRequested) {
                        stopInternal()
                        onStateChanged(ServerState.Stopped)
                        return@submit
                    }
                    stopInternal()

                    val mcpServer =
                        Server(
                            serverInfo = Implementation("awesome-mcp", "1.0.0"),
                            options =
                                ServerOptions(
                                    capabilities =
                                        ServerCapabilities(
                                            tools = ServerCapabilities.Tools(listChanged = false),
                                        ),
                                ),
                        )

                    ktorServer =
                        embeddedServer(Netty, host = settings.host, port = settings.port) {
                            install(ContentNegotiation) {
                                json(McpJson)
                            }

                            when (settings.transport) {
                                McpTransportMode.SSE -> {
                                    mcp {
                                        mcpServer
                                    }
                                }

                                McpTransportMode.STREAMABLE_HTTP -> {
                                    routing {
                                        route("/mcp") {
                                            post {
                                                val transport =
                                                    StreamableHttpServerTransport(enableJsonResponse = true).also {
                                                        it.setSessionIdGenerator(null)
                                                    }
                                                mcpServer.createSession(transport)
                                                transport.handlePostRequest(session = null, call = call)
                                            }

                                            get {
                                                call.response.headers.append("Allow", "POST")
                                                call.respond(HttpStatusCode.MethodNotAllowed)
                                            }

                                            delete {
                                                call.response.headers.append("Allow", "POST")
                                                call.respond(HttpStatusCode.MethodNotAllowed)
                                            }
                                        }
                                    }
                                }
                            }

                            mcpServer.registerBurpTools(api)
                        }

                    if (shutdownRequested) {
                        stopInternal()
                        onStateChanged(ServerState.Stopped)
                        return@submit
                    }

                    ktorServer?.start(wait = false)
                    McpOutputLogger.logServer(
                        name = "running",
                        status = McpActivityStatus.OK,
                        request = requestSummary,
                        durationMs = elapsedMillis(startedNanos),
                        note = "${settings.host}:${settings.port} (${settings.transport.name.lowercase()})",
                    )
                    onStateChanged(ServerState.Running)
                } catch (e: Exception) {
                    api.logging().logToError(e)
                    McpOutputLogger.logServer(
                        name = "start",
                        status = McpActivityStatus.ERROR,
                        request = requestSummary,
                        durationMs = elapsedMillis(startedNanos),
                        note = e.message ?: "failed to start",
                    )
                    onStateChanged(ServerState.Failed(e.message ?: "failed to start", e))
                }
            }
        } catch (_: RejectedExecutionException) {
            onStateChanged(ServerState.Failed("server manager is shutting down"))
        }
    }

    override fun stop(onStateChanged: (ServerState) -> Unit) {
        if (shutdownRequested) {
            onStateChanged(ServerState.Stopped)
            return
        }
        onStateChanged(ServerState.Stopping)
        val requestSummary =
            buildJsonObject {
                put("action", "stop")
            }
        McpOutputLogger.logServer(
            name = "stop",
            status = McpActivityStatus.INFO,
            request = requestSummary,
        )

        try {
            executor.submit {
                val startedNanos = System.nanoTime()
                try {
                    stopInternal()
                    McpOutputLogger.logServer(
                        name = "stopped",
                        status = McpActivityStatus.OK,
                        request = requestSummary,
                        durationMs = elapsedMillis(startedNanos),
                    )
                    onStateChanged(ServerState.Stopped)
                } catch (e: Exception) {
                    api.logging().logToError(e)
                    McpOutputLogger.logServer(
                        name = "stop",
                        status = McpActivityStatus.ERROR,
                        request = requestSummary,
                        durationMs = elapsedMillis(startedNanos),
                        note = e.message ?: "failed to stop",
                    )
                    onStateChanged(ServerState.Failed(e.message ?: "failed to stop", e))
                }
            }
        } catch (_: RejectedExecutionException) {
            onStateChanged(ServerState.Stopped)
        }
    }

    override fun shutdown() {
        shutdownRequested = true
        runCatching { executor.submit { stopInternal() }.get(10, TimeUnit.SECONDS) }
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        stopInternal()
    }

    private fun stopInternal() {
        ktorServer?.stop(1000, 5000)
        ktorServer = null
    }
}

private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

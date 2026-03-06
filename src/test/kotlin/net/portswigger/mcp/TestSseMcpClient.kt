package net.portswigger.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

class TestSseMcpClient {
    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val CONNECT_TIMEOUT_MILLIS = 15_000L
    }

    private val logger = LoggerFactory.getLogger(TestSseMcpClient::class.java)
    private val mcp: Client = Client(clientInfo = Implementation(name = "test-mcp-client", version = "1.0.0"))
    private var connected = false

    private lateinit var tools: List<Tool>

    private fun newHttpClient(): HttpClient =
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            }
            install(SSE)
        }

    private suspend fun refreshToolCache(): List<Tool> =
        mcp
            .listTools()
            ?.tools
            .orEmpty()
            .also { tools = it }

    suspend fun connectToServer(sseUrl: String) {
        McpTestTrace.log("mcp-client", "connect.request", "url=$sseUrl")
        try {
            mcp.connect(SseClientTransport(newHttpClient(), urlString = sseUrl))
            connected = true

            refreshToolCache()
            McpTestTrace.log("mcp-client", "connect.response", "tools=${tools.joinToString(",") { it.name }}")
            println("Connected to server with tools: ${tools.joinToString(", ") { it.name }}")
        } catch (e: Exception) {
            McpTestTrace.log("mcp-client", "connect.error", e.message ?: "unknown error")
            println("Failed to connect to MCP server: $e")
            throw e
        }
    }

    suspend fun connectToStreamableServer(endpointUrl: String) {
        McpTestTrace.log("mcp-client", "connect_streamable.request", "url=$endpointUrl")
        try {
            val transport =
                StreamableHttpClientTransport(
                    newHttpClient(),
                    endpointUrl,
                    30.seconds,
                    {
                        accept(ContentType.Application.Json)
                    },
                )

            mcp.connect(transport)
            connected = true

            refreshToolCache()
            McpTestTrace.log("mcp-client", "connect_streamable.response", "tools=${tools.joinToString(",") { it.name }}")
            println("Connected to streamable server with tools: ${tools.joinToString(", ") { it.name }}")
        } catch (e: Exception) {
            McpTestTrace.log("mcp-client", "connect_streamable.error", e.message ?: "unknown error")
            println("Failed to connect to streamable MCP server: $e")
            throw e
        }
    }

    fun isConnected(): Boolean = connected

    suspend fun ping(): EmptyResult {
        McpTestTrace.log("mcp-client", "ping.request")
        try {
            val pingRequest = mcp.ping()
            logger.info("Ping sent: $pingRequest")
            McpTestTrace.log("mcp-client", "ping.response", pingRequest)
            return pingRequest
        } catch (e: Exception) {
            logger.error("Failed to send ping: $e")
            McpTestTrace.log("mcp-client", "ping.error", e.message ?: "unknown error")
            throw e
        }
    }

    suspend fun listTools(): List<Tool> {
        McpTestTrace.log("mcp-client", "list_tools.request")
        try {
            refreshToolCache()
            logger.info("Tools listed: ${tools.joinToString(", ") { it.name }}")
            McpTestTrace.log("mcp-client", "list_tools.response", "tools=${tools.joinToString(",") { it.name }}")
            return tools
        } catch (e: Exception) {
            logger.error("Failed to list tools: $e")
            McpTestTrace.log("mcp-client", "list_tools.error", e.message ?: "unknown error")
            throw e
        }
    }

    suspend fun callTool(
        toolName: String,
        arguments: Map<String, Any>,
    ): CallToolResult {
        McpTestTrace.log("mcp-client", "call_tool.request", "tool=$toolName args=$arguments")
        try {
            val result = mcp.callTool(toolName, arguments)
            McpTestTrace.log(
                "mcp-client",
                "call_tool.response",
                "tool=$toolName isError=${result.isError} content=${result.content}",
            )
            return result
        } catch (e: Exception) {
            logger.error("Failed to call tool: $e")
            McpTestTrace.log("mcp-client", "call_tool.error", "tool=$toolName error=${e.message ?: "unknown error"}")
            throw e
        }
    }

    suspend fun close() {
        McpTestTrace.log("mcp-client", "close.request")
        try {
            mcp.close()
            connected = false
            logger.info("MCP client closed successfully.")
            McpTestTrace.log("mcp-client", "close.response", "closed=true")
        } catch (e: Exception) {
            logger.error("Failed to close MCP client: $e")
            McpTestTrace.log("mcp-client", "close.error", e.message ?: "unknown error")
        }
    }
}

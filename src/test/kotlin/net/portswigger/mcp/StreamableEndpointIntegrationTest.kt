package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.core.McpSettingsSnapshot
import net.portswigger.mcp.core.McpTransportMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class StreamableEndpointIntegrationTest {
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val httpClient = HttpClient.newBuilder().build()
    private lateinit var serverManager: ServerManager
    private var serverState: ServerState = ServerState.Stopped
    private val port = findAvailablePort()

    @BeforeEach
    fun setup() {
        McpTestTrace.log("StreamableEndpointIntegrationTest", "setup.start", "port=$port")
        serverManager = AgentMcpServerManager(api)
        serverManager.start(
            McpSettingsSnapshot(
                enabled = true,
                host = "127.0.0.1",
                port = port,
                transport = McpTransportMode.STREAMABLE_HTTP,
            ),
        ) {
            serverState = it
        }

        runBlocking {
            repeat(40) {
                if (serverState == ServerState.Running) return@runBlocking
                delay(100)
            }
            error("Server failed to start, state=$serverState")
        }
        McpTestTrace.log("StreamableEndpointIntegrationTest", "setup.ready", "state=$serverState")
    }

    @AfterEach
    fun tearDown() {
        McpTestTrace.log("StreamableEndpointIntegrationTest", "teardown.start")
        serverManager.stop {}
        serverManager.shutdown()
        McpTestTrace.log("StreamableEndpointIntegrationTest", "teardown.done")
    }

    @Test
    fun `direct streamable endpoint should not return not acceptable`() {
        McpTestTrace.log(
            "StreamableEndpointIntegrationTest",
            "test.start",
            "direct streamable endpoint should not return not acceptable",
        )
        val directResponse =
            postJson(
                url = "http://127.0.0.1:$port/mcp",
                accept = "application/json, text/event-stream",
            )
        val directStatus = directResponse.status
        assertNotEquals(406, directStatus)
        assertNotEquals(404, directStatus)
        assertTrue(directStatus in setOf(200, 202, 204, 400, 415, 422, 500), "unexpected direct status=$directStatus")
        McpTestTrace.log(
            "StreamableEndpointIntegrationTest",
            "test.done",
            "direct=$directStatus",
        )
    }

    @Test
    fun `streamable get endpoint should return method not allowed`() {
        McpTestTrace.log(
            "StreamableEndpointIntegrationTest",
            "test.start",
            "streamable get endpoint should return method not allowed",
        )
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("http://127.0.0.1:$port/mcp"))
                .header("Accept", "text/event-stream")
                .GET()
                .build()

        val status = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).statusCode()
        McpTestTrace.log(
            "StreamableEndpointIntegrationTest",
            "http.response",
            "GET /mcp status=$status",
        )
        assertEquals(405, status)
        McpTestTrace.log(
            "StreamableEndpointIntegrationTest",
            "test.done",
            "streamable get endpoint should return method not allowed",
        )
    }

    private fun postJson(
        url: String,
        accept: String,
    ): HttpTraceResponse {
        McpTestTrace.log(
            "StreamableEndpointIntegrationTest",
            "http.request",
            "POST $url accept=$accept contentType=application/json body={}",
        )
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Accept", accept)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        McpTestTrace.log(
            "StreamableEndpointIntegrationTest",
            "http.response",
            "POST $url status=${response.statusCode()} body=${response.body()}",
        )
        return HttpTraceResponse(status = response.statusCode(), body = response.body())
    }

    private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}

private data class HttpTraceResponse(
    val status: Int,
    val body: String,
)

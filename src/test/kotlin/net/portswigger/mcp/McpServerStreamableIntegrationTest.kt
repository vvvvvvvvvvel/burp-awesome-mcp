package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.BurpSuite
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.core.Version
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.scanner.audit.issues.AuditIssue
import burp.api.montoya.scanner.audit.issues.AuditIssueConfidence
import burp.api.montoya.scanner.audit.issues.AuditIssueSeverity
import burp.api.montoya.scope.Scope
import burp.api.montoya.sitemap.SiteMap
import burp.api.montoya.sitemap.SiteMapFilter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.core.McpSettingsSnapshot
import net.portswigger.mcp.core.McpTransportMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class McpServerStreamableIntegrationTest {
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val client = TestSseMcpClient()
    private lateinit var serverManager: ServerManager
    private var serverState: ServerState = ServerState.Stopped
    private val port = findAvailablePort()

    @BeforeEach
    fun setup() {
        McpTestTrace.log("McpServerStreamableIntegrationTest", "setup.start", "port=$port")
        val scope = mockk<Scope>(relaxed = true)
        every { api.proxy().history() } returns emptyList<ProxyHttpRequestResponse>()
        every { api.scope() } returns scope
        every { scope.isInScope(any()) } returns false
        serverManager = AgentMcpServerManager(api)
    }

    @AfterEach
    fun tearDown() {
        McpTestTrace.log("McpServerStreamableIntegrationTest", "teardown.start")
        runBlocking {
            if (client.isConnected()) {
                client.close()
            }
        }
        serverManager.stop {}
        serverManager.shutdown()
        McpTestTrace.log("McpServerStreamableIntegrationTest", "teardown.done")
    }

    @Test
    fun `server should start in streamable http mode`() =
        runBlocking {
            McpTestTrace.log("McpServerStreamableIntegrationTest", "test.start", "server should start in streamable http mode")
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

            repeat(40) {
                if (serverState == ServerState.Running) return@runBlocking
                delay(100)
            }

            assertEquals(ServerState.Running, serverState)
            McpTestTrace.log("McpServerStreamableIntegrationTest", "test.done", "state=$serverState")
        }

    @Test
    fun `streamable query scanner issues should accept empty input`() =
        runBlocking {
            val burpSuite = mockk<BurpSuite>()
            val version = mockk<Version>()
            val siteMap = mockk<SiteMap>()
            val issue = mockk<AuditIssue>()

            every { api.burpSuite() } returns burpSuite
            every { burpSuite.version() } returns version
            every { version.edition() } returns BurpSuiteEdition.PROFESSIONAL
            every { api.siteMap() } returns siteMap
            every { siteMap.issues() } returns listOf(issue)
            every { siteMap.issues(any<SiteMapFilter>()) } returns listOf(issue)
            every { issue.name() } returns "streamable-scanner-finding"
            every { issue.severity() } returns AuditIssueSeverity.HIGH
            every { issue.confidence() } returns AuditIssueConfidence.CERTAIN
            every { issue.baseUrl() } returns "https://example.com"
            every { issue.detail() } returns null
            every { issue.remediation() } returns null
            every { issue.definition() } returns null
            every { issue.requestResponses() } returns emptyList()

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

            var ready = false
            repeat(40) {
                if (serverState == ServerState.Running) {
                    ready = true
                    return@repeat
                }
                delay(100)
            }
            assertTrue(ready, "streamable server failed to start, state=$serverState")

            client.connectToStreamableServer("http://127.0.0.1:$port/mcp")
            val scannerResult = client.callTool("list_scanner_issues", emptyMap())
            assertTrue(scannerResult.isError != true, "list_scanner_issues must accept empty args in streamable mode")
        }

    private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}

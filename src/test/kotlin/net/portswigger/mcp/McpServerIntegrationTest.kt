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
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.core.McpSettingsSnapshot
import net.portswigger.mcp.core.McpTransportMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class McpServerIntegrationTest {
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = TestSseMcpClient()
    private lateinit var serverManager: ServerManager
    private var serverState: ServerState = ServerState.Stopped
    private val port = findAvailablePort()

    @BeforeEach
    fun setup() {
        McpTestTrace.log("McpServerIntegrationTest", "setup.start", "port=$port")
        val scope = mockk<Scope>(relaxed = true)
        val burpSuite = mockk<BurpSuite>(relaxed = true)
        val version = mockk<Version>(relaxed = true)
        var projectOptionsJson = """{"target":{"scope":{"advanced_mode":false,"include":[],"exclude":[]}}}"""

        every { api.proxy().history() } returns emptyList<ProxyHttpRequestResponse>()
        every { api.scope() } returns scope
        every { api.burpSuite() } returns burpSuite
        every { burpSuite.version() } returns version
        every { version.edition() } returns BurpSuiteEdition.PROFESSIONAL
        every { burpSuite.exportProjectOptionsAsJson() } answers { projectOptionsJson }
        every { burpSuite.importProjectOptionsFromJson(any()) } answers {
            projectOptionsJson = firstArg()
            Unit
        }
        every { scope.isInScope(any()) } returns false
        every { api.utilities().urlUtils().encode(any<String>()) } answers {
            (firstArg<String>())
                .replace("%", "%25")
                .replace(" ", "%20")
        }

        serverManager = AgentMcpServerManager(api)
        serverManager.start(
            McpSettingsSnapshot(
                enabled = true,
                host = "127.0.0.1",
                port = port,
                transport = McpTransportMode.SSE,
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
        McpTestTrace.log("McpServerIntegrationTest", "setup.ready", "state=$serverState")
    }

    @AfterEach
    fun tearDown() {
        McpTestTrace.log("McpServerIntegrationTest", "teardown.start")
        runBlocking {
            if (client.isConnected()) {
                client.close()
            }
        }
        serverManager.stop {}
        serverManager.shutdown()
        McpTestTrace.log("McpServerIntegrationTest", "teardown.done")
    }

    @Test
    fun `server should expose new toolset`() =
        runBlocking {
            McpTestTrace.log("McpServerIntegrationTest", "test.start", "server should expose new toolset")
            client.connectToServer("http://127.0.0.1:$port")

            val tools = client.listTools()
            val names = tools.map { it.name }

            assertTrue(names.contains("list_proxy_http_history"))
            assertTrue(names.contains("get_proxy_http_history_by_ids"))
            assertTrue(names.contains("list_proxy_websocket_history"))
            assertTrue(names.contains("list_site_map"))
            assertTrue(names.contains("get_site_map_by_keys"))
            assertTrue(names.contains("send_http1_requests"))
            assertTrue(names.contains("send_requests_to_intruder_template"))
            assertTrue(names.contains("scope_add_include"))
            assertTrue(names.contains("scope_add_exclude"))
            assertTrue(names.contains("scope_remove_include"))
            assertTrue(names.contains("scope_remove_exclude"))
            assertTrue(names.contains("scope_is_url_in_scope"))
            assertTrue(names.contains("list_cookie_jar"))
            assertTrue(names.contains("set_cookie_jar_cookie"))
            assertTrue(names.contains("expire_cookie_jar_cookie"))
            assertTrue(names.contains("list_scanner_tasks"))
            assertTrue(names.contains("list_proxy_request_listeners"))
            assertTrue(names.contains("get_project_scope_rules"))

            assertNotNull(client.ping())
            McpTestTrace.log("McpServerIntegrationTest", "test.done", "server should expose new toolset")
        }

    @Test
    fun `server should execute basic mcp tool calls`() =
        runBlocking {
            McpTestTrace.log("McpServerIntegrationTest", "test.start", "server should execute basic mcp tool calls")
            client.connectToServer("http://127.0.0.1:$port")

            val queryHistory =
                client.callTool(
                    "list_proxy_http_history",
                    mapOf(
                        "limit" to 5,
                        "start_id" to 0,
                        "filter" to mapOf("in_scope_only" to false),
                    ),
                )
            assertTrue(queryHistory.isError != true, "list_proxy_http_history must not fail")

            val queryOrganizer =
                client.callTool(
                    "list_organizer_items",
                    mapOf(
                        "limit" to 5,
                        "start_id" to 0,
                        "filter" to mapOf("in_scope_only" to false),
                    ),
                )
            assertTrue(queryOrganizer.isError != true, "list_organizer_items must not fail")

            val encoded =
                client.callTool(
                    "url_encode",
                    mapOf(
                        "items" to
                            listOf(
                                mapOf("content" to "a b"),
                            ),
                    ),
                )
            assertTrue(encoded.isError != true, "url_encode must not fail")

            val listScannerTasks = client.callTool("list_scanner_tasks", emptyMap())
            assertTrue(listScannerTasks.isError != true, "list_scanner_tasks must not fail")
            val listPayload = listScannerTasks.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            assertTrue(listPayload.contains("\"total\":"), "list_scanner_tasks payload must include total: $listPayload")
            assertTrue(listPayload.contains("\"results\":"), "list_scanner_tasks payload must include results: $listPayload")

            val includeScope =
                client.callTool(
                    "scope_add_include",
                    mapOf(
                        "url" to "wb1.ru",
                    ),
                )
            assertTrue(includeScope.isError != true, "scope_add_include must accept host-only input")

            val includeScopePrefix =
                client.callTool(
                    "scope_add_include",
                    mapOf(
                        "url" to "a/",
                    ),
                )
            assertTrue(includeScopePrefix.isError != true, "scope_add_include must accept short prefix input")

            val includeScopeInvalid =
                client.callTool(
                    "scope_add_include",
                    mapOf(
                        "url" to "!",
                    ),
                )
            assertTrue(includeScopeInvalid.isError == true, "scope_add_include must reject obvious invalid input")

            val includeScopeMalformedWithSubdomains =
                client.callTool(
                    "scope_add_include",
                    mapOf(
                        "url" to "http:///abc",
                        "include_subdomains" to true,
                    ),
                )
            assertTrue(
                includeScopeMalformedWithSubdomains.isError == true,
                "scope_add_include must reject include_subdomains for malformed/hostless URL",
            )

            val removeIncludeScope =
                client.callTool(
                    "scope_remove_include",
                    mapOf(
                        "url" to "wb1.ru",
                    ),
                )
            assertTrue(removeIncludeScope.isError != true, "scope_remove_include must not fail")
            McpTestTrace.log("McpServerIntegrationTest", "test.done", "server should execute basic mcp tool calls")
        }

    @Test
    fun `query scanner issues should handle null text fields in default mode`() =
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
            every { issue.name() } returns "scanner-finding"
            every { issue.severity() } returns AuditIssueSeverity.HIGH
            every { issue.confidence() } returns AuditIssueConfidence.CERTAIN
            every { issue.baseUrl() } returns "https://example.com"
            every { issue.detail() } returns null
            every { issue.remediation() } returns null
            every { issue.definition() } returns null
            every { issue.requestResponses() } returns emptyList()

            client.connectToServer("http://127.0.0.1:$port")
            val result = client.callTool("list_scanner_issues", emptyMap())
            assertTrue(result.isError != true, "list_scanner_issues must not fail on null detail/remediation")

            val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            assertTrue(text.contains("\"returned\":1"), "expected one scanner issue in output, got: $text")
            assertTrue(!text.contains("invalid tool input"), "runtime errors must not be masked as input errors: $text")
            assertTrue(text.contains("\"severity\":\"high\""), "scanner severity should be normalized to lower-case wire format: $text")
            assertTrue(
                text.contains("\"confidence\":\"certain\""),
                "scanner confidence should be normalized to lower-case wire format: $text",
            )
        }

    @Test
    fun `send http2 request should reject non-http2 mode`() =
        runBlocking {
            client.connectToServer("http://127.0.0.1:$port")
            val result =
                client.callTool(
                    "send_http2_requests",
                    mapOf(
                        "items" to
                            listOf(
                                mapOf(
                                    "pseudo_headers" to
                                        mapOf(
                                            ":method" to "GET",
                                            ":path" to "/",
                                            ":scheme" to "https",
                                            ":authority" to "example.com",
                                        ),
                                    "headers" to emptyMap<String, String>(),
                                    "request_body" to "",
                                    "target_hostname" to "example.com",
                                    "target_port" to 443,
                                    "uses_https" to true,
                                ),
                            ),
                        "request_options" to mapOf("http_mode" to "http_1"),
                    ),
                )

            assertTrue(result.isError != true, "send_http2_requests should return structured bulk response")
            val payload =
                result.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
            assertTrue(payload.contains("\"ok\":false"), "expected failing bulk item for invalid http_mode: $payload")
            assertTrue(
                payload.contains("requires request_options.http_mode=http_2"),
                "expected explicit http2 mode validation error: $payload",
            )
        }

    @Test
    fun `scope remove should not delete broader prefix when specific prefix requested`() =
        runBlocking {
            val burpSuite = mockk<BurpSuite>()
            every { api.burpSuite() } returns burpSuite

            var projectOptionsJson =
                """
                {
                  "target": {
                    "scope": {
                      "advanced_mode": false,
                      "include": [
                        {"enabled": true, "include_subdomains": false, "prefix": "example.com"},
                        {"enabled": true, "include_subdomains": false, "prefix": "example.com:8443"},
                        {"enabled": true, "include_subdomains": false, "prefix": "https://example.com/path"}
                      ],
                      "exclude": []
                    }
                  }
                }
                """.trimIndent()

            every { burpSuite.exportProjectOptionsAsJson() } answers { projectOptionsJson }
            every { burpSuite.importProjectOptionsFromJson(any()) } answers {
                projectOptionsJson = firstArg()
            }

            client.connectToServer("http://127.0.0.1:$port")
            val removeResult =
                client.callTool(
                    "scope_remove_include",
                    mapOf("url" to "example.com:8443"),
                )
            assertTrue(removeResult.isError != true, "scope_remove_include failed: ${removeResult.content}")

            val removePayload =
                removeResult.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
            assertTrue(removePayload.contains("\"scope_rule_updated\":true"), "expected scope rule update: $removePayload")

            val parsed = json.parseToJsonElement(projectOptionsJson).jsonObject
            val includeRules =
                parsed["target"]
                    ?.jsonObject
                    ?.get("scope")
                    ?.jsonObject
                    ?.get("include")
                    ?.jsonArray
                    ?: error("include rules missing after remove")

            val prefixes =
                includeRules.mapNotNull {
                    it
                        .jsonObject["prefix"]
                        ?.jsonPrimitive
                        ?.content
                }
            assertTrue("example.com" in prefixes, "broader prefix should remain after removing host:port")
            assertTrue("example.com:8443" !in prefixes, "specific host:port prefix should be removed")

            val removePathResult =
                client.callTool(
                    "scope_remove_include",
                    mapOf("url" to "https://example.com/path"),
                )
            assertTrue(removePathResult.isError != true, "scope_remove_include(path) failed: ${removePathResult.content}")

            val parsedAfterPath = json.parseToJsonElement(projectOptionsJson).jsonObject
            val includeRulesAfterPath =
                parsedAfterPath["target"]
                    ?.jsonObject
                    ?.get("scope")
                    ?.jsonObject
                    ?.get("include")
                    ?.jsonArray
                    ?: error("include rules missing after path remove")
            val prefixesAfterPath =
                includeRulesAfterPath.mapNotNull {
                    it
                        .jsonObject["prefix"]
                        ?.jsonPrimitive
                        ?.content
                }
            assertTrue("example.com" in prefixesAfterPath, "broader host prefix should remain after removing path prefix")
            assertTrue(
                "https://example.com/path" !in prefixesAfterPath,
                "specific path prefix should be removed without deleting host",
            )
            assertEquals(1, prefixesAfterPath.size)
        }

    @Test
    fun `scope remove should allow selecting include_subdomains variant`() =
        runBlocking {
            val burpSuite = mockk<BurpSuite>()
            every { api.burpSuite() } returns burpSuite

            var projectOptionsJson =
                """
                {
                  "target": {
                    "scope": {
                      "advanced_mode": false,
                      "include": [
                        {"enabled": true, "include_subdomains": false, "prefix": "example.com"},
                        {"enabled": true, "include_subdomains": true, "prefix": "example.com"}
                      ],
                      "exclude": []
                    }
                  }
                }
                """.trimIndent()

            every { burpSuite.exportProjectOptionsAsJson() } answers { projectOptionsJson }
            every { burpSuite.importProjectOptionsFromJson(any()) } answers {
                projectOptionsJson = firstArg()
            }

            client.connectToServer("http://127.0.0.1:$port")
            val removeResult =
                client.callTool(
                    "scope_remove_include",
                    mapOf(
                        "url" to "example.com",
                        "include_subdomains" to false,
                    ),
                )
            assertTrue(removeResult.isError != true, "scope_remove_include failed: ${removeResult.content}")

            val parsed = json.parseToJsonElement(projectOptionsJson).jsonObject
            val includeRules =
                parsed["target"]
                    ?.jsonObject
                    ?.get("scope")
                    ?.jsonObject
                    ?.get("include")
                    ?.jsonArray
                    ?: error("include rules missing after selective remove")
            assertEquals(1, includeRules.size)
            val remaining = includeRules.first().jsonObject
            assertEquals("example.com", remaining["prefix"]?.jsonPrimitive?.content)
            assertEquals("true", remaining["include_subdomains"]?.jsonPrimitive?.content)
        }

    @Test
    fun `scope check should fallback to normalized absolute url when direct prefix check is false`() =
        runBlocking {
            every { api.scope().isInScope(any()) } answers {
                firstArg<String>() == "https://example.com/path"
            }

            client.connectToServer("http://127.0.0.1:$port")
            val result =
                client.callTool(
                    "scope_is_url_in_scope",
                    mapOf("url" to "HTTPS://Example.com/path"),
                )

            assertTrue(result.isError != true, "scope_is_url_in_scope should not fail")
            val payload =
                result.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
            assertTrue(payload.contains("\"in_scope\":true"), "scope_is_url_in_scope should use normalized URL fallback: $payload")
        }

    @Test
    fun `scope check should not call Burp scope API with raw short prefix`() =
        runBlocking {
            every { api.scope().isInScope(any()) } answers {
                val candidate = firstArg<String>()
                if (candidate == "a/") {
                    throw IllegalArgumentException("raw short prefix is not a valid scope URL")
                }
                candidate == "https://a/"
            }

            client.connectToServer("http://127.0.0.1:$port")
            val result =
                client.callTool(
                    "scope_is_url_in_scope",
                    mapOf("url" to "a/"),
                )

            assertTrue(result.isError != true, "scope_is_url_in_scope should not fail on short prefix")
            val payload =
                result.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
            assertTrue(payload.contains("\"in_scope\":true"), "scope_is_url_in_scope should use normalized short-prefix fallback: $payload")
        }

    @Test
    fun `scope tools should fail fast on invalid project options json`() =
        runBlocking {
            val burpSuite = mockk<BurpSuite>(relaxed = true)
            val version = mockk<Version>(relaxed = true)
            every { api.burpSuite() } returns burpSuite
            every { burpSuite.version() } returns version
            every { version.edition() } returns BurpSuiteEdition.PROFESSIONAL
            every { burpSuite.exportProjectOptionsAsJson() } returns "{invalid-json"

            client.connectToServer("http://127.0.0.1:$port")
            val result =
                client.callTool(
                    "scope_add_include",
                    mapOf("url" to "example.com"),
                )

            assertTrue(result.isError == true, "scope_add_include should fail on invalid project options JSON")
            val payload =
                result.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
            assertTrue(payload.contains("failed to parse Burp project options JSON"), "unexpected error payload: $payload")
        }

    private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}

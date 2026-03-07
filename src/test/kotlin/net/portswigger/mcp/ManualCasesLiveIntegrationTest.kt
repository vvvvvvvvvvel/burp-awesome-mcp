package net.portswigger.mcp

import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors

class ManualCasesLiveIntegrationTest {
    private val client = TestSseMcpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var endpoint: String
    private lateinit var transport: String
    private lateinit var runId: String

    private lateinit var localServer: HttpServer
    private var localPort: Int = 0
    private var proxyListenerPort: Int = 8080
    private var interceptStateBefore: Boolean? = null

    private val reportLines = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    @BeforeEach
    fun setup() =
        runBlocking {
            endpoint = resolveValue("awesome.mcp.live.url", "AWESOME_MCP_LIVE_URL")
            transport =
                resolveValue("awesome.mcp.live.transport", "AWESOME_MCP_LIVE_TRANSPORT")
                    .ifBlank { "streamable_http" }
                    .trim()
                    .lowercase()

            assumeTrue(
                endpoint.isNotBlank(),
                "Live test skipped. Set -Dawesome.mcp.live.url=http://127.0.0.1:26001/mcp or env AWESOME_MCP_LIVE_URL.",
            )
            assumeTrue(
                transport in setOf("sse", "streamable_http", "streamable-http", "streamable"),
                "Unsupported transport '$transport'.",
            )

            runId = "manual-live-${System.currentTimeMillis()}"

            reportLines += "# Manual Cases Live Run"
            reportLines += ""
            reportLines += "- started_at: ${Instant.now()}"
            reportLines += "- endpoint: $endpoint"
            reportLines += "- transport: $transport"
            reportLines += "- run_id: $runId"
            reportLines += ""

            localServer = startLocalHttpTarget()
            localPort = localServer.address.port
            reportLines += "- local_target: http://127.0.0.1:$localPort"

            when (transport) {
                "sse" -> client.connectToServer(endpoint)
                else -> client.connectToStreamableServer(endpoint)
            }

            interceptStateBefore = runCatching { readInterceptState() }.getOrNull()
            if (interceptStateBefore == true) {
                runCatching { callToolWithRetry("set_proxy_intercept_enabled", mapOf("intercepting" to false)) }
            }

            proxyListenerPort = resolveProxyListenerPort()
            ensureScopePrefix("127.0.0.1", include = true)
            generateBaselineTraffic()

            reportLines += "- proxy_listener_port: $proxyListenerPort"
            reportLines += ""
        }

    @AfterEach
    fun tearDown() =
        runBlocking {
            runCatching {
                ensureScopePrefix("127.0.0.1", include = false)
                ensureScopePrefix("example.com", include = false)
                ensureScopePrefix("example.org", include = false)
            }

            if (interceptStateBefore == true) {
                runCatching { callToolWithRetry("set_proxy_intercept_enabled", mapOf("intercepting" to true)) }
            }

            if (client.isConnected()) {
                client.close()
            }

            runCatching {
                localServer.stop(0)
            }

            reportLines += ""
            reportLines += "## Warnings"
            if (warnings.isEmpty()) {
                reportLines += "- none"
            } else {
                warnings.forEach { reportLines += "- $it" }
            }

            val reportPath = Path.of("test-artifacts", "manual-cases-live-report.md")
            Files.createDirectories(reportPath.parent)
            Files.writeString(reportPath, reportLines.joinToString(System.lineSeparator()) + System.lineSeparator())
            McpTestTrace.log("ManualCasesLiveIntegrationTest", "report", "written=$reportPath")
        }

    @Test
    fun `manual cases should pass against live endpoint`() =
        runBlocking {
            val failures = mutableListOf<String>()

            suspend fun step(
                name: String,
                action: suspend () -> Unit,
            ) {
                try {
                    action()
                    reportLines += "- [PASS] $name"
                    McpTestTrace.log("ManualCasesLiveIntegrationTest", "pass", name)
                } catch (t: Throwable) {
                    val message = t.message ?: t::class.simpleName.orEmpty()
                    failures += "$name :: $message"
                    reportLines += "- [FAIL] $name :: ${message.replace("\n", " ")}"
                    McpTestTrace.log("ManualCasesLiveIntegrationTest", "fail", "$name :: $message")
                }
            }

            reportLines += "## Results"

            var historyIds = emptyList<Int>()
            var siteMapKey: String? = null
            var organizerIds = emptyList<Int>()
            var scannerTaskId: String? = null
            var collaboratorPayloadId: String? = null
            var collaboratorPayload: String? = null
            var collaboratorSecretKey: String? = null

            step("TC-HH-001 list_proxy_http_history defaults") {
                val payload =
                    expectOkObject(
                        "list_proxy_http_history",
                        mapOf("start_id" to 0, "limit" to 5),
                    )
                val total = payload["total"]?.jsonPrimitive?.intOrNull ?: -1
                val results = payload["results"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(total >= 0)
                assertTrue(results.size <= 5)
                historyIds =
                    results.mapNotNull {
                        it.jsonObject["id"]?.jsonPrimitive?.intOrNull
                    }
            }

            step("TC-HH-002 list_proxy_http_history with filter+serialization") {
                val payload =
                    expectOkObject(
                        "list_proxy_http_history",
                        mapOf(
                            "start_id" to 0,
                            "limit" to 10,
                            "filter" to
                                mapOf(
                                    "in_scope_only" to false,
                                    "methods" to listOf("POST"),
                                    "status_codes" to listOf(200, 401, 403),
                                    "host_regex" to "127\\.0\\.0\\.1",
                                    "listener_ports" to listOf(proxyListenerPort),
                                    "has_response" to true,
                                ),
                            "serialization" to
                                mapOf(
                                    "include_headers" to true,
                                    "include_request_body" to true,
                                    "include_response_body" to true,
                                    "include_raw_request" to false,
                                    "include_raw_response" to false,
                                    "include_binary" to false,
                                    "max_text_body_chars" to 1024,
                                    "text_overflow_mode" to "omit",
                                    "max_binary_body_bytes" to 65536,
                                ),
                        ),
                    )
                val results = payload["results"]?.jsonArray ?: JsonArray(emptyList())
                results.forEach { entry ->
                    val request = entry.jsonObject["request"]?.jsonObject ?: JsonObject(emptyMap())
                    val response = entry.jsonObject["response"]?.jsonObject ?: JsonObject(emptyMap())
                    val method = request["method"]?.jsonPrimitive?.contentOrNull
                    assertEquals("POST", method)
                    val statusCode = response["status_code"]?.jsonPrimitive?.intOrNull
                    assertTrue(statusCode in setOf(200, 401, 403))
                    val listenerPort = entry.jsonObject["listener_port"]?.jsonPrimitive?.intOrNull
                    assertEquals(proxyListenerPort, listenerPort)
                }
            }

            step("TC-HH-003 list_proxy_http_history negative start_id") {
                val payload =
                    expectOkObject(
                        "list_proxy_http_history",
                        mapOf(
                            "start_id" to -1,
                            "limit" to 1,
                            "filter" to mapOf("in_scope_only" to false),
                        ),
                    )
                val results = payload["results"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(results.size <= 1)
            }

            step("TC-HH-004 list_proxy_http_history invalid regex") {
                expectError(
                    "list_proxy_http_history",
                    mapOf(
                        "start_id" to 0,
                        "limit" to 5,
                        "filter" to mapOf("regex" to "(abc"),
                    ),
                )
            }

            step("TC-HH-005 list_proxy_http_history next cursor") {
                val first =
                    expectOkObject(
                        "list_proxy_http_history",
                        mapOf(
                            "start_id" to 0,
                            "limit" to 3,
                            "filter" to mapOf("in_scope_only" to false, "host_regex" to "127\\.0\\.0\\.1"),
                        ),
                    )
                val firstIds = first["results"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull } ?: emptyList()
                val nextJson = first["next"]
                if (nextJson == null || nextJson is JsonNull) {
                    warnings += "TC-HH-005: next is null (not enough matching records for pagination check)."
                    return@step
                }
                val second = expectOkObject("list_proxy_http_history", jsonElementToKotlin(nextJson).asMap())
                val secondIds = second["results"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull } ?: emptyList()
                val overlap = firstIds.intersect(secondIds.toSet())
                assertTrue(overlap.isEmpty(), "unexpected page overlap: $overlap")
            }

            step("TC-HH-006 get_proxy_http_history_by_ids by ids") {
                if (historyIds.size < 2) {
                    val query =
                        expectOkObject(
                            "list_proxy_http_history",
                            mapOf("start_id" to 0, "limit" to 10, "filter" to mapOf("in_scope_only" to false)),
                        )
                    historyIds = query["results"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull } ?: emptyList()
                }
                assumeTrue(historyIds.isNotEmpty(), "No history IDs available.")
                val ids = historyIds.take(2)
                val payload =
                    expectOkObject(
                        "get_proxy_http_history_by_ids",
                        mapOf(
                            "ids" to ids,
                            "serialization" to
                                mapOf(
                                    "include_headers" to true,
                                    "include_request_body" to true,
                                    "include_response_body" to true,
                                    "text_overflow_mode" to "omit",
                                ),
                        ),
                    )
                val found = payload["found"]?.jsonPrimitive?.intOrNull ?: -1
                assertTrue(found >= 1)
            }

            step("TC-HH-007 summarize_http_history_cookies") {
                val payload =
                    expectOkObject(
                        "summarize_http_history_cookies",
                        mapOf(
                            "limit" to 50,
                            "offset" to 0,
                            "order" to "desc",
                            "in_scope_only" to false,
                        ),
                    )
                assertTrue((payload["total_entries_scanned"]?.jsonPrimitive?.intOrNull ?: -1) >= 0)
                assertNotNull(payload["observations"])
            }

            step("TC-HH-008 summarize_http_history_auth_headers") {
                val payload =
                    expectOkObject(
                        "summarize_http_history_auth_headers",
                        mapOf(
                            "limit" to 50,
                            "offset" to 0,
                            "order" to "desc",
                            "in_scope_only" to false,
                        ),
                    )
                assertTrue((payload["total_entries_scanned"]?.jsonPrimitive?.intOrNull ?: -1) >= 0)
                assertNotNull(payload["observations"])
            }

            step("TC-HH-009 empty history window") {
                val payload =
                    expectOkObject(
                        "summarize_http_history_cookies",
                        mapOf("limit" to 1, "offset" to 100000, "order" to "desc", "in_scope_only" to false),
                    )
                val observations = payload["observations"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(observations.isEmpty())
            }

            step("TC-HH-010 list_proxy_http_history id_direction decreasing") {
                val payload =
                    expectOkObject(
                        "list_proxy_http_history",
                        mapOf(
                            "start_id" to 0,
                            "id_direction" to "decreasing",
                            "limit" to 5,
                            "filter" to mapOf("in_scope_only" to false),
                        ),
                    )
                val ids =
                    payload["results"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull }
                        ?: emptyList()
                if (ids.size >= 2) {
                    assertTrue(ids.zipWithNext().all { (a, b) -> a > b }, "expected descending IDs, got=$ids")
                }
                val next = payload["next"]
                if (next != null && next !is JsonNull) {
                    val direction = next.jsonObject["id_direction"]?.jsonPrimitive?.contentOrNull
                    assertEquals("decreasing", direction)
                }
            }

            var websocketMessageIds = emptyList<Long>()
            step("TC-WS-001 list_proxy_websocket_history defaults") {
                val payload =
                    expectOkObject(
                        "list_proxy_websocket_history",
                        mapOf(
                            "start_id" to 0,
                            "limit" to 10,
                            "filter" to mapOf("in_scope_only" to false),
                        ),
                    )
                val results = payload["results"]?.jsonArray ?: JsonArray(emptyList())
                if (results.isEmpty()) {
                    warnings += "TC-WS-001: no WebSocket traffic in project. WebSocket-specific checks require manual traffic generation."
                } else {
                    websocketMessageIds =
                        results.mapNotNull {
                            it.jsonObject["id"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.toLongOrNull()
                        }
                }
            }

            step("TC-WS-002 list_proxy_websocket_history filter") {
                expectOkObject(
                    "list_proxy_websocket_history",
                    mapOf(
                        "start_id" to 0,
                        "limit" to 10,
                        "filter" to mapOf("in_scope_only" to false, "direction" to "client_to_server"),
                    ),
                )
            }

            step("TC-WS-003 get_proxy_websocket_messages_by_ids by ids") {
                if (websocketMessageIds.isEmpty()) {
                    warnings += "TC-WS-003 skipped: no WebSocket message IDs available."
                    return@step
                }
                val payload = expectOkObject("get_proxy_websocket_messages_by_ids", mapOf("ids" to websocketMessageIds.take(2)))
                val found = payload["found"]?.jsonPrimitive?.intOrNull ?: -1
                assertTrue(found >= 1)
            }

            step("TC-WS-006 list_proxy_websocket_history id_direction decreasing") {
                val payload =
                    expectOkObject(
                        "list_proxy_websocket_history",
                        mapOf(
                            "start_id" to 0,
                            "id_direction" to "decreasing",
                            "limit" to 5,
                            "filter" to mapOf("in_scope_only" to false),
                        ),
                    )
                val ids =
                    payload["results"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull }
                        ?: emptyList()
                if (ids.size >= 2) {
                    assertTrue(ids.zipWithNext().all { (a, b) -> a > b }, "expected descending IDs, got=$ids")
                }
                val next = payload["next"]
                if (next != null && next !is JsonNull) {
                    val direction = next.jsonObject["id_direction"]?.jsonPrimitive?.contentOrNull
                    assertEquals("decreasing", direction)
                }
            }

            step("TC-SO-001 list_site_map defaults") {
                val payload = expectOkObject("list_site_map", mapOf("limit" to 10))
                val results = payload["results"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(results.isNotEmpty(), "site map is unexpectedly empty")
            }

            step("TC-SO-002 list_site_map filter+serialization") {
                val payload =
                    expectOkObject(
                        "list_site_map",
                        mapOf(
                            "limit" to 20,
                            "filter" to
                                mapOf(
                                    "in_scope_only" to false,
                                    "methods" to listOf("GET", "POST"),
                                    "host_regex" to "127\\.0\\.0\\.1",
                                    "status_codes" to listOf(200, 401),
                                    "has_response" to true,
                                ),
                            "serialization" to
                                mapOf(
                                    "include_headers" to true,
                                    "include_request_body" to false,
                                    "include_response_body" to false,
                                    "text_overflow_mode" to "omit",
                                ),
                        ),
                    )
                val results = payload["results"]?.jsonArray ?: JsonArray(emptyList())
                if (results.isNotEmpty()) {
                    siteMapKey =
                        results
                            .first()
                            .jsonObject["key"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                }
            }

            step("TC-SO-003 get_site_map_by_keys by keys") {
                if (siteMapKey.isNullOrBlank()) {
                    val query = expectOkObject("list_site_map", mapOf("limit" to 1, "filter" to mapOf("in_scope_only" to false)))
                    siteMapKey =
                        query["results"]
                            ?.jsonArray
                            ?.firstOrNull()
                            ?.jsonObject
                            ?.get("key")
                            ?.jsonPrimitive
                            ?.contentOrNull
                }
                assumeTrue(!siteMapKey.isNullOrBlank(), "No site map key available.")
                val payload =
                    expectOkObject(
                        "get_site_map_by_keys",
                        mapOf(
                            "keys" to listOf(siteMapKey!!, "unknown-key-${System.currentTimeMillis()}"),
                            "serialization" to
                                mapOf("include_headers" to true, "include_response_body" to true, "text_overflow_mode" to "omit"),
                        ),
                    )
                val found = payload["found"]?.jsonPrimitive?.intOrNull ?: -1
                assertTrue(found >= 1)
            }

            step("TC-SO-004 list_site_map invalid start_after_key") {
                expectError(
                    "list_site_map",
                    mapOf(
                        "limit" to 5,
                        "start_after_key" to "deadbeef",
                        "filter" to mapOf("in_scope_only" to false),
                    ),
                )
            }

            step("TC-SO-005 send_requests_to_organizer") {
                val payload =
                    expectOkObject(
                        "send_requests_to_organizer",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
                                        "target_hostname" to "example.com",
                                        "target_port" to 443,
                                        "uses_https" to true,
                                    ),
                                ),
                        ),
                    )
                val firstOk =
                    payload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("ok")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                assertTrue(firstOk == true)
            }

            step("TC-SO-006 list_organizer_items") {
                val payload =
                    expectOkObject(
                        "list_organizer_items",
                        mapOf(
                            "start_id" to 0,
                            "limit" to 10,
                            "filter" to mapOf("in_scope_only" to false),
                            "serialization" to
                                mapOf(
                                    "include_headers" to true,
                                    "include_response_body" to false,
                                    "text_overflow_mode" to "omit",
                                ),
                        ),
                    )
                organizerIds = payload["results"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull } ?: emptyList()
                assertTrue((payload["total"]?.jsonPrimitive?.intOrNull ?: -1) >= 0)
            }

            step("TC-SO-007 get_organizer_items_by_ids") {
                if (organizerIds.isEmpty()) {
                    warnings += "TC-SO-007 skipped: no organizer IDs available."
                    return@step
                }
                val payload =
                    expectOkObject(
                        "get_organizer_items_by_ids",
                        mapOf(
                            "ids" to listOf(organizerIds.first()),
                            "serialization" to
                                mapOf(
                                    "include_headers" to true,
                                    "include_response_body" to true,
                                    "text_overflow_mode" to "omit",
                                ),
                        ),
                    )
                val found = payload["found"]?.jsonPrimitive?.intOrNull ?: -1
                assertTrue(found >= 1)
            }

            step("TC-SO-008 list_organizer_items id_direction decreasing") {
                val payload =
                    expectOkObject(
                        "list_organizer_items",
                        mapOf(
                            "start_id" to 0,
                            "id_direction" to "decreasing",
                            "limit" to 5,
                            "filter" to mapOf("in_scope_only" to false),
                        ),
                    )
                val ids =
                    payload["results"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.intOrNull }
                        ?: emptyList()
                if (ids.size >= 2) {
                    assertTrue(ids.zipWithNext().all { (a, b) -> a > b }, "expected descending IDs, got=$ids")
                }
                val next = payload["next"]
                if (next != null && next !is JsonNull) {
                    val direction = next.jsonObject["id_direction"]?.jsonPrimitive?.contentOrNull
                    assertEquals("decreasing", direction)
                }
            }

            step("TC-AW-001 send_http1_requests single") {
                val payload =
                    expectOkObject(
                        "send_http1_requests",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to proxyRequest("GET", "/ping?rid=$runId"),
                                        "target_hostname" to "127.0.0.1",
                                        "target_port" to proxyListenerPort,
                                        "uses_https" to false,
                                    ),
                                ),
                            "parallel" to false,
                            "serialization" to
                                mapOf("include_headers" to true, "include_response_body" to true, "text_overflow_mode" to "omit"),
                        ),
                    )
                val firstOk =
                    payload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("ok")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                assertTrue(firstOk == true)
            }

            step("TC-AW-002 send_http1_requests parallel") {
                val payload =
                    expectOkObject(
                        "send_http1_requests",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to proxyRequest("GET", "/ping?rid=$runId"),
                                        "target_hostname" to "127.0.0.1",
                                        "target_port" to proxyListenerPort,
                                        "uses_https" to false,
                                    ),
                                    mapOf(
                                        "content" to proxyRequest("GET", "/json?rid=$runId"),
                                        "target_hostname" to "127.0.0.1",
                                        "target_port" to proxyListenerPort,
                                        "uses_https" to false,
                                    ),
                                ),
                            "parallel" to true,
                            "parallel_rps" to 5,
                        ),
                    )
                assertEquals(2, payload["results"]?.jsonArray?.size)
            }

            step("TC-AW-003 send_http2_requests basic") {
                val payload =
                    expectOkObject(
                        "send_http2_requests",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "pseudo_headers" to
                                            mapOf(
                                                ":method" to "GET",
                                                ":scheme" to "https",
                                                ":authority" to "example.com",
                                                ":path" to "/",
                                            ),
                                        "headers" to mapOf("user-agent" to "awesome-mcp-manual-test"),
                                        "request_body" to "",
                                        "target_hostname" to "example.com",
                                        "target_port" to 443,
                                        "uses_https" to true,
                                    ),
                                ),
                            "request_options" to mapOf("http_mode" to "http_2"),
                        ),
                    )
                val firstOk =
                    payload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("ok")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                assertTrue(firstOk == true)
            }

            step("TC-AW-004 send_http2_requests invalid mode") {
                val payload =
                    expectOkObject(
                        "send_http2_requests",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "pseudo_headers" to
                                            mapOf(
                                                ":method" to "GET",
                                                ":scheme" to "https",
                                                ":authority" to "example.com",
                                                ":path" to "/",
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
                val first = payload["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: JsonObject(emptyMap())
                val ok = first["ok"]?.jsonPrimitive?.booleanOrNull
                assertFalse(ok == true)
            }

            step("TC-AW-005 create_repeater_tabs") {
                val payload =
                    expectOkObject(
                        "create_repeater_tabs",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
                                        "target_hostname" to "example.com",
                                        "target_port" to 443,
                                        "uses_https" to true,
                                        "tab_name" to "MCP Repeater Smoke $runId",
                                    ),
                                ),
                        ),
                    )
                val firstOk =
                    payload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("ok")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                assertTrue(firstOk == true)
            }

            step("TC-AW-006 send_requests_to_intruder") {
                val payload =
                    expectOkObject(
                        "send_requests_to_intruder",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to "GET /search?q=test HTTP/1.1\r\nHost: example.com\r\n\r\n",
                                        "target_hostname" to "example.com",
                                        "target_port" to 443,
                                        "uses_https" to true,
                                        "tab_name" to "MCP Intruder Smoke $runId",
                                    ),
                                ),
                        ),
                    )
                val firstOk =
                    payload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("ok")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                assertTrue(firstOk == true)
            }

            step("TC-AW-007 send_requests_to_intruder_template valid") {
                val payload =
                    expectOkObject(
                        "send_requests_to_intruder_template",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to "GET /search?q=test HTTP/1.1\r\nHost: example.com\r\n\r\n",
                                        "target_hostname" to "example.com",
                                        "target_port" to 443,
                                        "uses_https" to true,
                                        "tab_name" to "MCP Intruder Template $runId",
                                        "insertion_points" to listOf(mapOf("start" to 14, "end" to 18)),
                                    ),
                                ),
                        ),
                    )
                val firstOk =
                    payload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("ok")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                assertTrue(firstOk == true)
            }

            step("TC-AW-008 send_requests_to_intruder_template invalid insertion range") {
                val payload =
                    expectOkObject(
                        "send_requests_to_intruder_template",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n",
                                        "target_hostname" to "example.com",
                                        "target_port" to 443,
                                        "uses_https" to true,
                                        "insertion_points" to listOf(mapOf("start" to 30, "end" to 10)),
                                    ),
                                ),
                        ),
                    )
                val first = payload["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: JsonObject(emptyMap())
                val ok = first["ok"]?.jsonPrimitive?.booleanOrNull
                assertFalse(ok == true)
            }

            step("TC-SU-001 scope_add_include") {
                val payload = expectOkObject("scope_add_include", mapOf("url" to "example.com", "include_subdomains" to false))
                assertEquals("example.com", payload["url"]?.jsonPrimitive?.contentOrNull)
            }

            step("TC-SU-002 scope_add_include include_subdomains") {
                val payload = expectOkObject("scope_add_include", mapOf("url" to "example.org", "include_subdomains" to true))
                assertEquals("example.org", payload["url"]?.jsonPrimitive?.contentOrNull)
            }

            step("TC-SU-003 scope_add_exclude") {
                val payload = expectOkObject("scope_add_exclude", mapOf("url" to "example.com/private", "include_subdomains" to false))
                assertEquals("example.com/private", payload["url"]?.jsonPrimitive?.contentOrNull)
            }

            step("TC-SU-004 scope_remove_include with selector") {
                val payload = expectOkObject("scope_remove_include", mapOf("url" to "example.org", "include_subdomains" to true))
                assertEquals("example.org", payload["url"]?.jsonPrimitive?.contentOrNull)
            }

            step("TC-SU-005 scope_remove_exclude") {
                val payload = expectOkObject("scope_remove_exclude", mapOf("url" to "example.com/private"))
                assertEquals("example.com/private", payload["url"]?.jsonPrimitive?.contentOrNull)
            }

            step("TC-SU-006 scope_is_url_in_scope") {
                val payload = expectOkObject("scope_is_url_in_scope", mapOf("url" to "https://example.com/"))
                assertNotNull(payload["in_scope"])
            }

            step("TC-SU-007 scope_add_include invalid input") {
                expectError("scope_add_include", mapOf("url" to "!"))
            }

            step("TC-SU-008 url encode/decode roundtrip") {
                val encoded = expectOkObject("url_encode", mapOf("items" to listOf(mapOf("content" to "a b+c&d"))))
                val encodedValue =
                    encoded["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("result")
                        ?.jsonPrimitive
                        ?.contentOrNull
                assertTrue(!encodedValue.isNullOrBlank())
                val decoded = expectOkObject("url_decode", mapOf("items" to listOf(mapOf("content" to encodedValue!!))))
                val decodedValue =
                    decoded["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("result")
                        ?.jsonPrimitive
                        ?.contentOrNull
                assertEquals("a b+c&d", decodedValue)
            }

            step("TC-SU-009 base64 encode/decode") {
                val encoded = expectOkObject("base64_encode", mapOf("items" to listOf(mapOf("content" to "hello mcp"))))
                val encodedValue =
                    encoded["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("result")
                        ?.jsonPrimitive
                        ?.contentOrNull
                assertTrue(!encodedValue.isNullOrBlank())
                val decoded = expectOkObject("base64_decode", mapOf("items" to listOf(mapOf("content" to encodedValue!!))))
                val decodedText =
                    decoded["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("result")
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                assertEquals("hello mcp", decodedText)
            }

            step("TC-SU-010 base64 decode invalid") {
                val payload = expectOkObject("base64_decode", mapOf("items" to listOf(mapOf("content" to "***"))))
                val ok =
                    payload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("ok")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                assertFalse(ok == true)
            }

            step("TC-SU-011 generate_random_string") {
                val raw = expectOkRaw("generate_random_string", mapOf("length" to 24, "character_set" to "abcdef012345"))
                val generated =
                    runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }
                        .getOrElse { raw }
                assertEquals(24, generated.length)
                assertTrue(generated.all { it in "abcdef012345" })
            }

            step("TC-CR-001 get_project_options_json") {
                val result = expectOkRaw("get_project_options_json", emptyMap())
                val parsed = parseObject(result)
                assertTrue(parsed.containsKey("project_options") || parsed.containsKey("proxy"))
            }

            step("TC-CR-002 get_user_options_json") {
                val result = expectOkRaw("get_user_options_json", emptyMap())
                parseObject(result)
            }

            step("TC-CR-003 list_proxy_request_listeners") {
                val payload = expectOkObject("list_proxy_request_listeners", emptyMap())
                assertNotNull(payload["listeners"])
            }

            step("TC-CR-004 get_project_scope_rules") {
                val payload = expectOkObject("get_project_scope_rules", emptyMap())
                assertNotNull(payload["include"])
                assertNotNull(payload["exclude"])
            }

            step("TC-CR-007 task execution engine toggle") {
                val before = expectOkObject("get_task_engine_state", emptyMap())
                val current = before["running"]?.jsonPrimitive?.booleanOrNull ?: true
                expectOkObject("set_task_engine_state", mapOf("running" to !current))
                val changed = expectOkObject("get_task_engine_state", emptyMap())
                assertEquals(!current, changed["running"]?.jsonPrimitive?.booleanOrNull)
                expectOkObject("set_task_engine_state", mapOf("running" to current))
            }

            step("TC-CR-008 proxy intercept toggle") {
                val before = expectOkObject("get_proxy_intercept_enabled", emptyMap())
                val current = before["intercepting"]?.jsonPrimitive?.booleanOrNull ?: false
                expectOkObject("set_proxy_intercept_enabled", mapOf("intercepting" to !current))
                val changed = expectOkObject("get_proxy_intercept_enabled", emptyMap())
                assertEquals(!current, changed["intercepting"]?.jsonPrimitive?.booleanOrNull)
                expectOkObject("set_proxy_intercept_enabled", mapOf("intercepting" to current))
            }

            step("TC-CR-009/010 active editor read/write") {
                val read = callToolWithRetry("get_active_text_editor_contents", emptyMap())
                if (read.isError == true) {
                    warnings += "Active editor checks require focused editable Burp text area: ${extractText(read)}"
                    return@step
                }
                val original = extractText(read)
                val newValue = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
                val write = callToolWithRetry("set_active_text_editor_contents", mapOf("text" to newValue))
                if (write.isError == true) {
                    warnings += "Active editor write skipped: ${extractText(write)}"
                    return@step
                }
                val readBack = callToolWithRetry("get_active_text_editor_contents", emptyMap())
                assertFalse(readBack.isError == true, "get_active_text_editor_contents failed after write")
                assertEquals(newValue, extractText(readBack))
                callToolWithRetry("set_active_text_editor_contents", mapOf("text" to original))
            }

            val tools = listToolsWithRetry().map { it.name }.toSet()

            step("TC-SC-001..004 scanner crawl/status/cancel") {
                assumeTrue(tools.contains("start_scanner_crawl"), "Scanner tools not available.")
                val crawl = expectOkObject("start_scanner_crawl", mapOf("seed_urls" to listOf("http://127.0.0.1:$localPort/")))
                scannerTaskId = crawl["task_id"]?.jsonPrimitive?.contentOrNull
                assumeTrue(!scannerTaskId.isNullOrBlank(), "Scanner task_id missing.")
                val status = expectOkObject("get_scanner_task_status", mapOf("task_id" to scannerTaskId!!))
                assertNotNull(status["status_message"])
                val cancelled = expectOkObject("cancel_scanner_task", mapOf("task_id" to scannerTaskId!!))
                assertNotNull(cancelled["deleted"])
            }

            step("TC-SC-002 start_scanner_audit") {
                assumeTrue(tools.contains("start_scanner_audit"), "Scanner audit tool not available.")
                val audit =
                    expectOkObject(
                        "start_scanner_audit",
                        mapOf(
                            "preset" to "passive_audit_checks",
                            "urls" to listOf("http://127.0.0.1:$localPort/"),
                        ),
                    )
                val taskId = audit["task_id"]?.jsonPrimitive?.contentOrNull
                assertTrue(!taskId.isNullOrBlank())
                expectOkObject("cancel_scanner_task", mapOf("task_id" to taskId!!))
            }

            step("TC-SC-005 list_scanner_tasks") {
                assumeTrue(tools.contains("list_scanner_tasks"), "list_scanner_tasks not available.")
                val payload = expectOkObject("list_scanner_tasks", emptyMap())
                assertNotNull(payload["total"])
                assertNotNull(payload["results"])
            }

            step("TC-SC-006 list_scanner_issues defaults") {
                assumeTrue(tools.contains("list_scanner_issues"), "list_scanner_issues not available.")
                val payload = expectOkObject("list_scanner_issues", emptyMap())
                assertNotNull(payload["total"])
                assertNotNull(payload["results"])
            }

            step("TC-SC-007 list_scanner_issues filtered") {
                assumeTrue(tools.contains("list_scanner_issues"), "list_scanner_issues not available.")
                expectOkObject(
                    "list_scanner_issues",
                    mapOf(
                        "limit" to 20,
                        "offset" to 0,
                        "severity" to listOf("high", "medium"),
                        "confidence" to listOf("certain", "firm"),
                        "name_regex" to "sql|xss",
                        "url_regex" to "127\\.0\\.0\\.1|example",
                        "include_detail" to true,
                        "include_remediation" to true,
                        "include_definition" to true,
                        "include_request_response" to true,
                        "max_request_responses" to 2,
                        "serialization" to
                            mapOf(
                                "include_headers" to true,
                                "include_request_body" to false,
                                "include_response_body" to false,
                                "text_overflow_mode" to "omit",
                            ),
                    ),
                )
            }

            step("TC-SC-008 generate_scanner_report") {
                assumeTrue(tools.contains("generate_scanner_report"), "generate_scanner_report not available.")
                val outFile = "/tmp/awesome-mcp-scanner-report-$runId.html"
                val payload =
                    expectOkObject(
                        "generate_scanner_report",
                        mapOf(
                            "output_file" to outFile,
                            "format" to "html",
                            "severity" to listOf("high", "medium"),
                            "confidence" to listOf("certain", "firm"),
                            "name_regex" to "",
                            "url_regex" to "",
                        ),
                    )
                assertTrue(Files.exists(Path.of(outFile)), "scanner report file not found: $outFile")
                assertNotNull(payload["included_issues"])
            }

            step("TC-SC-009 generate_collaborator_payload") {
                assumeTrue(tools.contains("generate_collaborator_payload"), "Collaborator tools not available.")
                val payload = expectOkObject("generate_collaborator_payload", mapOf("custom_data" to "A1B2C3"))
                collaboratorPayloadId = payload["payload_id"]?.jsonPrimitive?.contentOrNull
                collaboratorPayload = payload["payload"]?.jsonPrimitive?.contentOrNull
                collaboratorSecretKey = payload["secret_key"]?.jsonPrimitive?.contentOrNull
                assertTrue(!collaboratorPayloadId.isNullOrBlank())
                assertTrue(!collaboratorPayload.isNullOrBlank())
            }

            step("TC-SC-010 list_collaborator_interactions by payload_id") {
                assumeTrue(!collaboratorPayloadId.isNullOrBlank(), "payload_id missing")

                warnings +=
                    "Collaborator active trigger is skipped in automated run " +
                    "(depends on outbound DNS/HTTP and can stall live suites)."

                var count = 0
                repeat(6) {
                    delay(1500)
                    val payload = expectOkObject("list_collaborator_interactions", mapOf("payload_id" to collaboratorPayloadId!!))
                    count = payload["count"]?.jsonPrimitive?.intOrNull ?: 0
                    if (count > 0) return@repeat
                }
                if (count <= 0) {
                    warnings +=
                        "No collaborator interactions observed for payload_id=$collaboratorPayloadId (possible outbound/DNS restrictions)."
                }
            }

            step("TC-SC-011 list_collaborator_interactions by payload+secret_key") {
                assumeTrue(!collaboratorPayload.isNullOrBlank() && !collaboratorSecretKey.isNullOrBlank(), "payload/secret missing")
                val payload =
                    expectOkObject(
                        "list_collaborator_interactions",
                        mapOf(
                            "payload" to collaboratorPayload!!,
                            "secret_key" to collaboratorSecretKey!!,
                        ),
                    )
                assertNotNull(payload["count"])
            }

            step("TC-CJ-001 list_cookie_jar defaults") {
                val payload = expectOkObject("list_cookie_jar", emptyMap())
                assertNotNull(payload["results"])
                assertNotNull(payload["total"])
            }

            step("TC-CJ-003 set_cookie_jar_cookie + TC-CJ-002 query filtered") {
                expectOkObject(
                    "set_cookie_jar_cookie",
                    mapOf(
                        "name" to "mcp_test_cookie",
                        "value" to "test-value-123",
                        "domain" to "example.com",
                        "path" to "/",
                        "expiration" to "2030-01-01T00:00:00Z",
                        "max_value_chars" to 200,
                    ),
                )
                val payload =
                    expectOkObject(
                        "list_cookie_jar",
                        mapOf(
                            "limit" to 50,
                            "offset" to 0,
                            "order" to "desc",
                            "domain_regex" to "example",
                            "name_regex" to "mcp_test_cookie",
                            "include_expired" to true,
                            "include_values" to true,
                            "max_value_chars" to 200,
                        ),
                    )
                val results = payload["results"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(results.any { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "mcp_test_cookie" })
            }

            step("TC-CJ-004 invalid expiration") {
                expectError(
                    "set_cookie_jar_cookie",
                    mapOf(
                        "name" to "bad_cookie",
                        "value" to "v",
                        "domain" to "example.com",
                        "expiration" to "not-a-date",
                    ),
                )
            }

            step("TC-CJ-005 expire_cookie_jar_cookie") {
                val payload =
                    expectOkObject(
                        "expire_cookie_jar_cookie",
                        mapOf(
                            "name" to "mcp_test_cookie",
                            "domain" to "example.com",
                            "path" to "/",
                        ),
                    )
                val deleted = payload["deleted"]?.jsonPrimitive?.intOrNull ?: -1
                assertTrue(deleted >= 0)
            }

            step("TC-CJ-006 domain normalization") {
                expectOkObject(
                    "set_cookie_jar_cookie",
                    mapOf(
                        "name" to "mcp_dot_cookie",
                        "value" to "v1",
                        "domain" to ".example.com",
                        "path" to "/",
                    ),
                )
                val payload =
                    expectOkObject(
                        "expire_cookie_jar_cookie",
                        mapOf(
                            "name" to "mcp_dot_cookie",
                            "domain" to "example.com",
                            "path" to "/",
                        ),
                    )
                val deleted = payload["deleted"]?.jsonPrimitive?.intOrNull ?: -1
                assertTrue(deleted >= 1, "expected normalized deletion for .example.com vs example.com")
            }

            step("TC-CJ-007 delete non-existing cookie") {
                val payload =
                    expectOkObject(
                        "expire_cookie_jar_cookie",
                        mapOf(
                            "name" to "definitely_missing_cookie",
                            "domain" to "example.com",
                        ),
                    )
                val deleted = payload["deleted"]?.jsonPrimitive?.intOrNull ?: -1
                assertEquals(0, deleted)
            }

            step("TC-CR-005 set_project_options_json roundtrip") {
                val raw = expectOkRaw("get_project_options_json", emptyMap())
                expectOkObject("set_project_options_json", mapOf("json" to raw))
            }

            step("TC-CR-006 set_user_options_json roundtrip") {
                val safePatch =
                    """
                    {
                      "user_options": {
                        "display": {
                          "table_appearance": {
                            "zebra_striping": true
                          }
                        }
                      }
                    }
                    """.trimIndent()
                expectOkObject("set_user_options_json", mapOf("json" to safePatch))
            }

            if (failures.isNotEmpty()) {
                val summary =
                    buildString {
                        appendLine("Manual live cases failed: ${failures.size}")
                        failures.forEach { appendLine("- $it") }
                        appendLine("See test-artifacts/manual-cases-live-report.md for details.")
                    }
                throw AssertionError(summary)
            }
        }

    private suspend fun expectOkRaw(
        tool: String,
        args: Map<String, Any>,
    ): String {
        val result = callToolWithRetry(tool, args)
        assertFalse(result.isError == true, "$tool failed: ${extractText(result)}")
        return extractText(result)
    }

    private suspend fun expectOkObject(
        tool: String,
        args: Map<String, Any>,
    ): JsonObject {
        val raw = expectOkRaw(tool, args)
        return parseObject(raw)
    }

    private suspend fun expectError(
        tool: String,
        args: Map<String, Any>,
    ) {
        val result = callToolWithRetry(tool, args)
        assertTrue(result.isError == true, "$tool should fail for invalid input")
    }

    private suspend fun ensureScopePrefix(
        prefix: String,
        include: Boolean,
    ) {
        val tool = if (include) "scope_add_include" else "scope_remove_include"
        callToolWithRetry(tool, mapOf("url" to prefix, "include_subdomains" to false))
    }

    private suspend fun resolveProxyListenerPort(): Int {
        val listeners = callToolWithRetry("list_proxy_request_listeners", emptyMap())
        if (listeners.isError == true) return 8080
        val payload = runCatching { parseObject(extractText(listeners)) }.getOrNull() ?: return 8080
        val list = payload["listeners"]?.jsonArray ?: return 8080
        val running = list.firstOrNull { it.jsonObject["running"]?.jsonPrimitive?.booleanOrNull == true } ?: list.firstOrNull()
        return running
            ?.jsonObject
            ?.get("listener_port")
            ?.jsonPrimitive
            ?.intOrNull ?: 8080
    }

    private suspend fun generateBaselineTraffic() {
        val requests =
            listOf(
                proxyRequest("GET", "/ping?rid=$runId"),
                proxyRequest("GET", "/set-cookie?rid=$runId"),
                proxyRequest(
                    "POST",
                    "/echo?rid=$runId",
                    body = "echo=$runId",
                    extraHeaders = listOf("Content-Type: application/x-www-form-urlencoded"),
                ),
                proxyRequest("GET", "/auth?rid=$runId", extraHeaders = listOf("Authorization: Bearer $runId")),
                proxyRequest("GET", "/json?rid=$runId", extraHeaders = listOf("X-Api-Key: key-$runId")),
                proxyRequest(
                    "POST",
                    "/echo?rid=$runId",
                    body = "big=${"A".repeat(1400)}",
                    extraHeaders = listOf("Content-Type: text/plain"),
                ),
            )

        val result =
            callToolWithRetry(
                "send_http1_requests",
                mapOf(
                    "items" to
                        requests.map { raw ->
                            mapOf(
                                "content" to raw,
                                "target_hostname" to "127.0.0.1",
                                "target_port" to proxyListenerPort,
                                "uses_https" to false,
                            )
                        },
                    "parallel" to false,
                ),
            )
        assertFalse(result.isError == true, "Baseline traffic generation failed: ${extractText(result)}")
        delay(1200)
    }

    private suspend fun readInterceptState(): Boolean {
        val result = callToolWithRetry("get_proxy_intercept_enabled", emptyMap())
        if (result.isError == true) return false
        val payload = runCatching { parseObject(extractText(result)) }.getOrNull() ?: return false
        return payload["intercepting"]?.jsonPrimitive?.booleanOrNull == true
    }

    private suspend fun listToolsWithRetry() =
        runCatching { client.listTools() }.getOrElse { error ->
            if (!isRetryableError(error.message)) throw error
            reconnectClient()
            client.listTools()
        }

    private suspend fun callToolWithRetry(
        tool: String,
        args: Map<String, Any>,
    ): CallToolResult {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                return client.callTool(tool, args)
            } catch (t: Throwable) {
                lastError = t
                val retryable = isRetryableError(t.message)
                if (!retryable || attempt == 1) throw t
                McpTestTrace.log(
                    "ManualCasesLiveIntegrationTest",
                    "retry",
                    "tool=$tool reason=${t.message ?: "unknown"}",
                )
                delay(1000)
                reconnectClient()
            }
        }
        throw lastError ?: IllegalStateException("callToolWithRetry failed with unknown error")
    }

    private suspend fun reconnectClient() {
        runCatching {
            if (client.isConnected()) {
                client.close()
            }
        }
        when (transport) {
            "sse" -> client.connectToServer(endpoint)
            else -> client.connectToStreamableServer(endpoint)
        }
    }

    private fun isRetryableError(message: String?): Boolean {
        if (message == null) return false
        val normalized = message.lowercase()
        return normalized.contains("connection refused") ||
            normalized.contains("connection reset") ||
            normalized.contains("request timeout has expired") ||
            normalized.contains("not connected") ||
            normalized.contains("error connecting to transport")
    }

    private fun proxyRequest(
        method: String,
        path: String,
        body: String = "",
        extraHeaders: List<String> = emptyList(),
    ): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val absolute = "http://127.0.0.1:$localPort$normalizedPath"
        val headers = mutableListOf<String>()
        headers += "Host: 127.0.0.1:$localPort"
        headers += "User-Agent: AwesomeMcpManual/1.0"
        headers += "Accept: */*"
        headers += "Cookie: mcp_baseline_cookie=$runId"
        headers += extraHeaders
        if (body.isNotEmpty()) {
            headers += "Content-Length: ${body.toByteArray().size}"
        }
        headers += "Connection: close"

        val requestLine = "$method $absolute HTTP/1.1"
        return buildString {
            append(requestLine)
            append("\r\n")
            headers.forEach {
                append(it)
                append("\r\n")
            }
            append("\r\n")
            append(body)
        }
    }

    private fun startLocalHttpTarget(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()

        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().decodeToString()

            when {
                path == "/set-cookie" -> {
                    val payload = "cookie set"
                    exchange.responseHeaders.add("Set-Cookie", "local_session=abc123; Path=/")
                    exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(payload.toByteArray()) }
                }
                path == "/auth" -> {
                    val auth = exchange.requestHeaders.getFirst("Authorization")
                    val status = if (auth?.startsWith("Bearer ") == true) 200 else 401
                    val payload = if (status == 200) "authorized" else "unauthorized"
                    exchange.sendResponseHeaders(status, payload.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(payload.toByteArray()) }
                }
                path == "/json" -> {
                    val payload = "{\"ok\":true,\"run_id\":\"$runId\"}"
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(payload.toByteArray()) }
                }
                path == "/echo" -> {
                    val payload = "echo:$body"
                    exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(payload.toByteArray()) }
                }
                else -> {
                    val payload = "pong"
                    exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(payload.toByteArray()) }
                }
            }
        }

        server.start()
        return server
    }

    private fun extractText(result: CallToolResult): String =
        result.content
            .filterIsInstance<TextContent>()
            .firstOrNull()
            ?.text
            ?: result.content.toString()

    private fun parseObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun jsonElementToKotlin(element: JsonElement): Any? =
        when (element) {
            JsonNull -> null
            is JsonPrimitive ->
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.booleanOrNull
                    element.intOrNull != null -> element.intOrNull
                    element.content.toLongOrNull() != null -> element.content.toLong()
                    element.doubleOrNull != null -> element.doubleOrNull
                    else -> element.content
                }
            is JsonObject -> element.mapValues { (_, value) -> jsonElementToKotlin(value) }
            is JsonArray -> element.map { value -> jsonElementToKotlin(value) }
            else -> null
        }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any> =
        when (this) {
            is Map<*, *> -> this.filterKeys { it is String }.mapKeys { it.key as String } as Map<String, Any>
            else -> emptyMap()
        }

    private fun resolveValue(
        systemProperty: String,
        envName: String,
    ): String =
        System
            .getProperty(systemProperty)
            ?.trim()
            .orEmpty()
            .ifBlank { System.getenv(envName)?.trim().orEmpty() }
}

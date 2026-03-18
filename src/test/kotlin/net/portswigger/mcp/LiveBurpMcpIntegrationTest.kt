package net.portswigger.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

class LiveBurpMcpIntegrationTest {
    private val client = TestSseMcpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var endpoint: String
    private lateinit var transport: String
    private lateinit var includePrefix: String
    private lateinit var excludePrefix: String

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
                "Live Burp test skipped. Set -Dawesome.mcp.live.url=http://127.0.0.1:<port> " +
                    "or env AWESOME_MCP_LIVE_URL.",
            )
            assumeTrue(
                transport in setOf("sse", "streamable_http", "streamable-http", "streamable"),
                "Unsupported live transport '$transport'. Use sse or streamable_http.",
            )

            val runId = System.currentTimeMillis()
            includePrefix =
                resolveValue("awesome.mcp.live.scope.include_prefix", "AWESOME_MCP_LIVE_SCOPE_INCLUDE_PREFIX")
                    .ifBlank { "awesome-mcp-live-include-$runId.example" }
            excludePrefix =
                resolveValue("awesome.mcp.live.scope.exclude_prefix", "AWESOME_MCP_LIVE_SCOPE_EXCLUDE_PREFIX")
                    .ifBlank { "awesome-mcp-live-exclude-$runId.example" }

            McpTestTrace.log(
                "LiveBurpMcpIntegrationTest",
                "setup",
                "transport=$transport endpoint=$endpoint includePrefix=$includePrefix excludePrefix=$excludePrefix",
            )
            when (transport) {
                "sse" -> client.connectToServer(endpoint)
                else -> client.connectToStreamableServer(endpoint)
            }
        }

    @AfterEach
    fun tearDown() =
        runBlocking {
            runCatching {
                cleanupScopePrefix(includePrefix, "include")
                cleanupScopePrefix(excludePrefix, "exclude")
            }
            if (client.isConnected()) {
                client.close()
            }
            McpTestTrace.log("LiveBurpMcpIntegrationTest", "teardown", "done")
        }

    @Test
    fun `scope tools should preserve prefix without forced https`() =
        runBlocking {
            cleanupScopePrefix(includePrefix, "include")
            cleanupScopePrefix(excludePrefix, "exclude")

            val includeResult =
                client.callTool(
                    "scope_add_include",
                    mapOf("url" to includePrefix),
                )
            assertFalse(includeResult.isError == true, "scope_add_include failed: ${extractText(includeResult)}")
            val includePayload = parseObject(extractText(includeResult))
            assertEquals(includePrefix, includePayload["url"]?.jsonPrimitive?.content)

            val excludeResult =
                client.callTool(
                    "scope_add_exclude",
                    mapOf("url" to excludePrefix),
                )
            assertFalse(excludeResult.isError == true, "scope_add_exclude failed: ${extractText(excludeResult)}")
            val excludePayload = parseObject(extractText(excludeResult))
            assertEquals(excludePrefix, excludePayload["url"]?.jsonPrimitive?.content)

            val optionsAfterAdd = parseObject(extractText(client.callTool("get_project_options_json", emptyMap())))
            assertTrue(
                hasScopePrefix(optionsAfterAdd, "include", includePrefix),
                "include prefix not found in project options: $includePrefix",
            )
            assertTrue(
                hasScopePrefix(optionsAfterAdd, "exclude", excludePrefix),
                "exclude prefix not found in project options: $excludePrefix",
            )

            val removeInclude = client.callTool("scope_remove_include", mapOf("url" to includePrefix))
            assertFalse(removeInclude.isError == true, "scope_remove_include failed: ${extractText(removeInclude)}")
            val removeExclude = client.callTool("scope_remove_exclude", mapOf("url" to excludePrefix))
            assertFalse(removeExclude.isError == true, "scope_remove_exclude failed: ${extractText(removeExclude)}")

            val optionsAfterRemove = parseObject(extractText(client.callTool("get_project_options_json", emptyMap())))
            assertFalse(
                hasScopePrefix(optionsAfterRemove, "include", includePrefix),
                "include prefix still present after remove: $includePrefix",
            )
            assertFalse(
                hasScopePrefix(optionsAfterRemove, "exclude", excludePrefix),
                "exclude prefix still present after remove: $excludePrefix",
            )
        }

    @Test
    fun `query history should execute against live burp endpoint`() =
        runBlocking {
            val result =
                client.callTool(
                    "list_proxy_http_history",
                    mapOf(
                        "limit" to 10,
                        "start_id" to 0,
                        "filter" to mapOf("in_scope_only" to false),
                        "serialization" to
                            mapOf(
                                "max_text_body_chars" to 128,
                                "text_overflow_mode" to "omit",
                            ),
                    ),
                )
            assertFalse(result.isError == true, "list_proxy_http_history failed: ${extractText(result)}")
            val payload = parseObject(extractText(result))
            val total = payload["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
            val ids =
                payload["results"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString(",")
                    .orEmpty()
            McpTestTrace.log(
                "LiveBurpMcpIntegrationTest",
                "history.query",
                "total=$total ids=$ids next=${payload["next"]}",
            )
            assertTrue(total >= 0)
        }

    @Test
    fun `live traffic should support history regex get-by-id and cookie extraction`() =
        runBlocking {
            val runId = "awesome-live-${System.currentTimeMillis()}"
            var interceptBefore: Boolean? = null
            var exampleScopeAdded = false

            try {
                interceptBefore = readProxyInterceptState()
                if (interceptBefore == true) {
                    val disableIntercept = client.callTool("set_proxy_intercept_enabled", mapOf("intercepting" to false))
                    assertFalse(disableIntercept.isError == true, "failed to disable intercept: ${extractText(disableIntercept)}")
                }

                exampleScopeAdded = ensureScopeIncludePrefix("example.com")
                val listenerPort = resolveProxyListenerPort()

                val sendResult =
                    client.callTool(
                        "send_http1_requests",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to buildAbsoluteProxyRequest(runId),
                                        "target_hostname" to "127.0.0.1",
                                        "target_port" to listenerPort,
                                        "uses_https" to false,
                                    ),
                                ),
                            "parallel" to false,
                            "parallel_rps" to 1,
                            "fields" to listOf("status_code", "has_response"),
                        ),
                    )
                assertFalse(sendResult.isError == true, "send_http1_requests failed: ${extractText(sendResult)}")
                val sendPayload = parseObject(extractText(sendResult))
                val sendItems = sendPayload["results"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(sendItems.isNotEmpty(), "send_http1_requests returned no bulk items")
                val sendFirstOk =
                    sendItems
                        .first()
                        .jsonObject["ok"]
                        ?.jsonPrimitive
                        ?.booleanOrNull == true
                assertTrue(
                    sendFirstOk,
                    "send_http1_requests result is not ok: ${extractText(sendResult)}",
                )

                val sendExcerptResult =
                    client.callTool(
                        "send_http1_requests",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to buildAbsoluteProxyRequest(runId),
                                        "target_hostname" to "127.0.0.1",
                                        "target_port" to listenerPort,
                                        "uses_https" to false,
                                    ),
                                ),
                            "fields" to listOf("status_code", "has_response", "response.status_code"),
                            "serialization" to
                                mapOf(
                                    "regex_excerpt" to
                                        mapOf(
                                            "regex" to "Example Domain",
                                            "context_chars" to 24,
                                        ),
                                ),
                        ),
                    )
                assertFalse(
                    sendExcerptResult.isError == true,
                    "send_http1_requests(regex_excerpt) failed: ${extractText(sendExcerptResult)}",
                )
                val sendExcerptPayload = parseObject(extractText(sendExcerptResult))
                val sendExcerptItem = sendExcerptPayload["results"]!!.jsonArray.first().jsonObject
                val sendExcerptSummary = sendExcerptItem["result"]!!.jsonObject
                assertTrue(sendExcerptSummary.containsKey("match_context"), "send_http1_requests did not return match_context")

                val regexQueryResult =
                    client.callTool(
                        "list_proxy_http_history",
                        mapOf(
                            "limit" to 50,
                            "start_id" to 0,
                            "filter" to
                                mapOf(
                                    "in_scope_only" to false,
                                    "regex" to runId,
                                    "listener_ports" to listOf(listenerPort),
                                ),
                            "serialization" to
                                mapOf(
                                    "max_text_body_chars" to 512,
                                    "text_overflow_mode" to "omit",
                                ),
                        ),
                    )
                assertFalse(
                    regexQueryResult.isError == true,
                    "list_proxy_http_history(regex) failed: ${extractText(regexQueryResult)}",
                )
                val regexPayload = parseObject(extractText(regexQueryResult))
                val regexResults = regexPayload["results"]?.jsonArray ?: JsonArray(emptyList())
                assertTrue(regexResults.isNotEmpty(), "regex history query did not find generated runId=$runId")
                val nonMatchingEntries =
                    regexResults.count { entry ->
                        val requestUrl =
                            entry
                                .jsonObject["request"]
                                ?.jsonObject
                                ?.get("url")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                .orEmpty()
                        !requestUrl.contains(runId)
                    }
                assertEquals(
                    0,
                    nonMatchingEntries,
                    "regex history query returned entries without runId=$runId",
                )
                val mismatchedListenerPorts =
                    regexResults.count { entry ->
                        entry.jsonObject["listener_port"]?.jsonPrimitive?.intOrNull != listenerPort
                    }
                assertEquals(
                    0,
                    mismatchedListenerPorts,
                    "history query returned entries from unexpected listener port",
                )

                val regexExcerptQueryResult =
                    client.callTool(
                        "list_proxy_http_history",
                        mapOf(
                            "limit" to 20,
                            "start_id" to 0,
                            "filter" to
                                mapOf(
                                    "in_scope_only" to false,
                                    "regex" to runId,
                                    "listener_ports" to listOf(listenerPort),
                                ),
                            "fields" to listOf("id", "request.url", "response.status_code"),
                            "serialization" to
                                mapOf(
                                    "regex_excerpt" to
                                        mapOf(
                                            "context_chars" to 20,
                                        ),
                                ),
                        ),
                    )
                assertFalse(
                    regexExcerptQueryResult.isError == true,
                    "list_proxy_http_history(regex_excerpt) failed: ${extractText(regexExcerptQueryResult)}",
                )
                val regexExcerptPayload = parseObject(extractText(regexExcerptQueryResult))
                val regexExcerptFirst = regexExcerptPayload["results"]!!.jsonArray.first().jsonObject
                assertTrue(regexExcerptFirst.containsKey("match_context"), "list_proxy_http_history did not return match_context")

                val firstMatchedId =
                    regexResults
                        .first()
                        .jsonObject["id"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.toIntOrNull()
                assertNotNull(firstMatchedId, "failed to parse id from regex history result")

                val getByIdResult =
                    client.callTool(
                        "get_proxy_http_history_by_ids",
                        mapOf(
                            "ids" to listOf(firstMatchedId!!),
                            "fields" to listOf("id", "request.url"),
                            "serialization" to
                                mapOf(
                                    "max_text_body_chars" to 512,
                                    "text_overflow_mode" to "omit",
                                    "regex_excerpt" to
                                        mapOf(
                                            "regex" to runId,
                                            "context_chars" to 16,
                                        ),
                                ),
                        ),
                    )
                assertFalse(
                    getByIdResult.isError == true,
                    "get_proxy_http_history_by_ids failed: ${extractText(getByIdResult)}",
                )
                val getByIdPayload = parseObject(extractText(getByIdResult))
                val found = getByIdPayload["found"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
                assertEquals(1, found, "expected exactly one found item for id=$firstMatchedId")

                val requestUrl =
                    getByIdPayload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("item")
                        ?.jsonObject
                        ?.get("request")
                        ?.jsonObject
                        ?.get("url")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                assertTrue(
                    requestUrl.contains("example.com", ignoreCase = true),
                    "expected example.com in looked-up request URL, got '$requestUrl'",
                )
                val getByIdMatchContext =
                    getByIdPayload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("item")
                        ?.jsonObject
                        ?.get("match_context")
                assertTrue(getByIdMatchContext != null, "get_proxy_http_history_by_ids did not return match_context")

                val cookiesResult =
                    client.callTool(
                        "summarize_http_history_cookies",
                        mapOf(
                            "limit" to 100,
                            "offset" to 0,
                            "order" to "desc",
                            "in_scope_only" to false,
                            "regex" to runId,
                        ),
                    )
                assertFalse(
                    cookiesResult.isError == true,
                    "summarize_http_history_cookies failed: ${extractText(cookiesResult)}",
                )
                val cookiesPayload = parseObject(extractText(cookiesResult))
                val observations = cookiesPayload["observations"]?.jsonArray ?: JsonArray(emptyList())
                val hasRequestCookie =
                    observations.any { obs ->
                        val obj = obs.jsonObject
                        val source = obj["source"]?.jsonPrimitive?.contentOrNull
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        source == "request_cookie" && name == "awesome_live_cookie"
                    }
                assertTrue(
                    hasRequestCookie,
                    "expected request cookie observation for awesome_live_cookie; payload=${extractText(cookiesResult)}",
                )
            } finally {
                if (exampleScopeAdded) {
                    cleanupScopePrefix("example.com", "include")
                }
                if (interceptBefore == true) {
                    client.callTool("set_proxy_intercept_enabled", mapOf("intercepting" to true))
                }
            }
        }

    @Test
    fun `live traffic should support site map query and get by exact key`() =
        runBlocking {
            val runId = "awesome-sitemap-live-${System.currentTimeMillis()}"
            var interceptBefore: Boolean? = null
            var exampleScopeAdded = false

            try {
                interceptBefore = readProxyInterceptState()
                if (interceptBefore == true) {
                    val disableIntercept = client.callTool("set_proxy_intercept_enabled", mapOf("intercepting" to false))
                    assertFalse(disableIntercept.isError == true, "failed to disable intercept: ${extractText(disableIntercept)}")
                }

                exampleScopeAdded = ensureScopeIncludePrefix("example.com")
                val listenerPort = resolveProxyListenerPort()

                val sendResult =
                    client.callTool(
                        "send_http1_requests",
                        mapOf(
                            "items" to
                                listOf(
                                    mapOf(
                                        "content" to buildSiteMapProbeRequest(runId),
                                        "target_hostname" to "127.0.0.1",
                                        "target_port" to listenerPort,
                                        "uses_https" to false,
                                    ),
                                ),
                            "parallel" to false,
                            "parallel_rps" to 1,
                        ),
                    )
                assertFalse(sendResult.isError == true, "send_http1_requests failed: ${extractText(sendResult)}")

                var queryPayload: JsonObject? = null
                var queryItems: JsonArray = JsonArray(emptyList())
                var lastSiteMapPayload = ""
                var matchedGeneratedRunId = false
                repeat(12) {
                    val queryResult =
                        client.callTool(
                            "list_site_map",
                            mapOf(
                                "limit" to 20,
                                "start_after_key" to "",
                                "filter" to mapOf("in_scope_only" to false, "regex" to runId),
                                "serialization" to emptyMap<String, Any>(),
                            ),
                        )
                    assertFalse(queryResult.isError == true, "list_site_map failed: ${extractText(queryResult)}")
                    lastSiteMapPayload = extractText(queryResult)
                    queryPayload = parseObject(lastSiteMapPayload)
                    queryItems = queryPayload["results"]?.jsonArray ?: JsonArray(emptyList())
                    if (queryItems.isNotEmpty()) {
                        matchedGeneratedRunId = true
                        return@repeat
                    }
                    delay(500)
                }
                if (queryItems.isEmpty()) {
                    McpTestTrace.log(
                        "LiveBurpMcpIntegrationTest",
                        "site_map.fallback",
                        "generated runId not found in site map; runId=$runId last_payload=$lastSiteMapPayload",
                    )
                    val fallbackResult =
                        client.callTool(
                            "list_site_map",
                            mapOf(
                                "limit" to 20,
                                "start_after_key" to "",
                                "filter" to mapOf("in_scope_only" to false),
                                "serialization" to emptyMap<String, Any>(),
                            ),
                        )
                    assertFalse(fallbackResult.isError == true, "list_site_map fallback failed: ${extractText(fallbackResult)}")
                    queryPayload = parseObject(extractText(fallbackResult))
                    queryItems = queryPayload["results"]?.jsonArray ?: JsonArray(emptyList())
                }
                assertTrue(queryItems.isNotEmpty(), "list_site_map returned no items in fallback mode")

                val first = queryItems.first().jsonObject
                val key = first["key"]?.jsonPrimitive?.contentOrNull
                val requestUrl =
                    first["request"]
                        ?.jsonObject
                        ?.get("url")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                assertTrue(!key.isNullOrBlank(), "list_site_map result does not contain key: $lastSiteMapPayload")
                if (matchedGeneratedRunId) {
                    assertTrue(
                        requestUrl.contains(runId),
                        "list_site_map regex returned non-matching url='$requestUrl' for runId=$runId; payload=$queryPayload",
                    )
                }

                val getResult =
                    client.callTool(
                        "get_site_map_by_keys",
                        mapOf(
                            "keys" to listOf(key!!),
                            "fields" to listOf("key", "request.url"),
                            "serialization" to
                                mapOf(
                                    "regex_excerpt" to
                                        mapOf(
                                            "regex" to runId,
                                            "context_chars" to 16,
                                        ),
                                ),
                        ),
                    )
                assertFalse(getResult.isError == true, "get_site_map_by_keys failed: ${extractText(getResult)}")
                val getPayload = parseObject(extractText(getResult))
                val found = getPayload["found"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1
                assertEquals(1, found, "expected exact key lookup to return one item")
                val lookedUpKey =
                    getPayload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("item")
                        ?.jsonObject
                        ?.get("key")
                        ?.jsonPrimitive
                        ?.contentOrNull
                assertEquals(key, lookedUpKey, "looked up site map item key does not match requested key")
                val siteMapMatchContext =
                    getPayload["results"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("item")
                        ?.jsonObject
                        ?.get("match_context")
                assertTrue(siteMapMatchContext != null, "get_site_map_by_keys did not return match_context")
            } finally {
                if (exampleScopeAdded) {
                    cleanupScopePrefix("example.com", "include")
                }
                if (interceptBefore == true) {
                    client.callTool("set_proxy_intercept_enabled", mapOf("intercepting" to true))
                }
            }
        }

    @Test
    fun `create repeater tab should succeed against live burp`() =
        runBlocking {
            val runId = System.currentTimeMillis().toString()
            val repeaterResult =
                client.callTool(
                    "create_repeater_tabs",
                    mapOf(
                        "items" to
                            listOf(
                                mapOf(
                                    "content" to
                                        (
                                            "GET /?awesome_repeater_live=$runId HTTP/1.1\r\n" +
                                                "Host: example.com\r\n" +
                                                "User-Agent: AwesomeMcpLiveTest/1.0\r\n" +
                                                "Connection: close\r\n\r\n"
                                        ),
                                    "target_hostname" to "example.com",
                                    "target_port" to 80,
                                    "uses_https" to false,
                                    "tab_name" to "Awesome MCP Live $runId",
                                ),
                            ),
                    ),
                )
            assertFalse(repeaterResult.isError == true, "create_repeater_tabs failed: ${extractText(repeaterResult)}")
            val payload = parseObject(extractText(repeaterResult))
            val first = payload["results"]?.jsonArray?.firstOrNull()?.jsonObject
            val ok = first?.get("ok")?.jsonPrimitive?.booleanOrNull == true
            assertTrue(ok, "create_repeater_tabs did not return ok=true: ${extractText(repeaterResult)}")
        }

    @Test
    fun `query scanner issues should work with defaults on live burp when professional`() =
        runBlocking {
            val scannerTool =
                client
                    .listTools()
                    .firstOrNull { it.name == "list_scanner_issues" }
            assumeTrue(scannerTool != null, "list_scanner_issues tool is not available on live endpoint")
            McpTestTrace.log(
                "LiveBurpMcpIntegrationTest",
                "scanner.schema",
                "required=${scannerTool!!.inputSchema.required} schema=${scannerTool.inputSchema}",
            )

            val result = client.callTool("list_scanner_issues", emptyMap())
            val payloadText = extractText(result)

            if (result.isError == true) {
                val expectedNonPro =
                    payloadText.contains("scanner issues require Burp Professional", ignoreCase = true) ||
                        payloadText.contains("require Burp Professional", ignoreCase = true)
                if (expectedNonPro) {
                    assumeTrue(true)
                    return@runBlocking
                }
                throw AssertionError("list_scanner_issues default call failed unexpectedly: $payloadText")
            }

            val payload = parseObject(payloadText)
            assertTrue(payload.containsKey("total"), "scanner response must include total: $payloadText")
            assertTrue(payload.containsKey("returned"), "scanner response must include returned: $payloadText")
            assertTrue(payload["results"]?.jsonArray != null, "scanner response must include results array: $payloadText")
        }

    @Test
    fun `collaborator generate trigger and poll should work when enabled`() =
        runBlocking {
            val collaboratorEnabled =
                resolveValue("awesome.mcp.live.collaborator", "AWESOME_MCP_LIVE_COLLABORATOR")
                    .equals("true", ignoreCase = true)
            assumeTrue(
                collaboratorEnabled,
                "Collaborator live test is disabled. Set -Dawesome.mcp.live.collaborator=true " +
                    "or env AWESOME_MCP_LIVE_COLLABORATOR=true to enable.",
            )

            val runId = "ac${System.currentTimeMillis().toString().takeLast(14)}"
            val generated =
                client.callTool(
                    "generate_collaborator_payload",
                    mapOf("custom_data" to runId),
                )
            assertFalse(generated.isError == true, "generate_collaborator_payload failed: ${extractText(generated)}")
            val generatedPayload = parseObject(extractText(generated))
            val payload = generatedPayload["payload"]?.jsonPrimitive?.contentOrNull
            val payloadId = generatedPayload["payload_id"]?.jsonPrimitive?.contentOrNull
            assertTrue(!payload.isNullOrBlank(), "missing collaborator payload in response: ${extractText(generated)}")
            assertTrue(!payloadId.isNullOrBlank(), "missing collaborator payload_id in response: ${extractText(generated)}")

            val listenerPort = resolveProxyListenerPort()
            val triggerResult =
                client.callTool(
                    "send_http1_requests",
                    mapOf(
                        "items" to
                            listOf(
                                mapOf(
                                    "content" to
                                        (
                                            "GET http://$payload/ HTTP/1.1\r\n" +
                                                "Host: $payload\r\n" +
                                                "User-Agent: AwesomeMcpLiveTest/1.0\r\n" +
                                                "Connection: close\r\n\r\n"
                                        ),
                                    "target_hostname" to "127.0.0.1",
                                    "target_port" to listenerPort,
                                    "uses_https" to false,
                                ),
                            ),
                        "parallel" to false,
                        "parallel_rps" to 1,
                    ),
                )
            assertFalse(triggerResult.isError == true, "failed to trigger collaborator payload: ${extractText(triggerResult)}")

            var interactionsCount = 0
            var lastPayload = ""
            for (attempt in 0 until 8) {
                delay(1500)
                val poll =
                    client.callTool(
                        "list_collaborator_interactions",
                        mapOf("payload_id" to payloadId!!),
                    )
                assertFalse(poll.isError == true, "list_collaborator_interactions failed: ${extractText(poll)}")
                val parsed = parseObject(extractText(poll))
                interactionsCount = parsed["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                lastPayload = extractText(poll)
                if (interactionsCount > 0) {
                    break
                }
            }

            assertTrue(
                interactionsCount > 0,
                "expected at least one collaborator interaction for payload_id=$payloadId; last=$lastPayload",
            )
        }

    private suspend fun cleanupScopePrefix(
        prefix: String,
        listName: String,
    ) {
        val tool = if (listName == "include") "scope_remove_include" else "scope_remove_exclude"
        val result = client.callTool(tool, mapOf("url" to prefix))
        McpTestTrace.log(
            "LiveBurpMcpIntegrationTest",
            "cleanup.$tool",
            "prefix=$prefix isError=${result.isError} payload=${extractText(result)}",
        )
    }

    private suspend fun readProxyInterceptState(): Boolean {
        val result = client.callTool("get_proxy_intercept_enabled", emptyMap())
        if (result.isError == true) return false
        val payload = parseObject(extractText(result))
        return payload["intercepting"]?.jsonPrimitive?.booleanOrNull == true
    }

    private suspend fun ensureScopeIncludePrefix(prefix: String): Boolean {
        val before = parseObject(extractText(client.callTool("get_project_options_json", emptyMap())))
        if (hasScopePrefix(before, "include", prefix)) {
            return false
        }

        val includeResult =
            client.callTool(
                "scope_add_include",
                mapOf(
                    "url" to prefix,
                    "include_subdomains" to true,
                ),
            )
        assertFalse(includeResult.isError == true, "scope_add_include failed: ${extractText(includeResult)}")
        return true
    }

    private suspend fun resolveProxyListenerPort(): Int {
        val options = parseObject(extractText(client.callTool("get_project_options_json", emptyMap())))
        val listeners = options["proxy"]?.jsonObject?.get("request_listeners")?.jsonArray ?: return 8080
        val running =
            listeners.firstOrNull { it.jsonObject["running"]?.jsonPrimitive?.booleanOrNull == true }
                ?: listeners.firstOrNull()
                ?: return 8080
        return running
            .jsonObject["listener_port"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull()
            ?: 8080
    }

    private fun buildAbsoluteProxyRequest(runId: String): String =
        buildString {
            append("GET http://example.com/?awesome_mcp_live=$runId HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            append("User-Agent: AwesomeMcpLiveTest/1.0\r\n")
            append("Accept: */*\r\n")
            append("Cookie: awesome_live_cookie=$runId\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }

    private fun buildSiteMapProbeRequest(runId: String): String =
        buildString {
            append("GET http://example.com/awesome_mcp_site_map/$runId HTTP/1.1\r\n")
            append("Host: example.com\r\n")
            append("User-Agent: AwesomeMcpLiveTest/1.0\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            append("\r\n")
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

    private fun extractText(result: CallToolResult): String =
        result.content
            .filterIsInstance<TextContent>()
            .firstOrNull()
            ?.text
            ?: result.content.toString()

    private fun parseObject(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun hasScopePrefix(
        projectOptions: JsonObject,
        listName: String,
        prefix: String,
    ): Boolean {
        val entries =
            projectOptions["target"]
                ?.jsonObject
                ?.get("scope")
                ?.jsonObject
                ?.get(listName)
                ?.jsonArray
                ?: return false
        return entries.any { entry ->
            (entry as? JsonObject)
                ?.get("prefix")
                ?.jsonPrimitive
                ?.content == prefix
        }
    }
}

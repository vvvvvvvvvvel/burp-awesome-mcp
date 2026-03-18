package net.portswigger.mcp.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.history.QueryProxyHttpHistoryInput
import net.portswigger.mcp.tools.QueryOrganizerItemsInput
import net.portswigger.mcp.tools.QueryScannerIssuesInput
import net.portswigger.mcp.tools.SendHttp1RequestInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolDslValidationTest {
    @Test
    fun `format enum decode error should include allowed values`() {
        val hints = collectEnumHints(QueryProxyHttpHistoryInput.serializer().descriptor)

        val error =
            formatToolInputError(
                "net.portswigger.mcp.history.TextOverflowMode does not contain element with name 'qwerty' " +
                    "at path: $.serialization.text_overflow_mode",
                hints,
            )

        assertEquals("invalid value 'qwerty' for 'serialization.text_overflow_mode'; allowed: [truncate, omit]", error)
    }

    @Test
    fun `format unknown key decode error should be explicit`() {
        val error = formatToolInputError("Encountered an unknown key 'qwerty' at path: $", emptyList())
        assertEquals("unknown field 'qwerty' in tool input", error)
    }

    @Test
    fun `tool json should reject unknown fields`() {
        val ex =
            runCatching {
                toolJson.decodeFromString(
                    QueryProxyHttpHistoryInput.serializer(),
                    """{"qwerty":1}""",
                )
            }.exceptionOrNull()

        assertTrue(ex != null)
        assertTrue(ex!!.message?.contains("unknown key", ignoreCase = true) == true)
    }

    @Test
    fun `tool json should keep snake case input`() {
        val parsed =
            toolJson.decodeFromString(
                QueryProxyHttpHistoryInput.serializer(),
                """{"limit":1,"start_id":0,"filter":{"in_scope_only":false}}""",
            )

        assertEquals(false, parsed.filter.inScopeOnly)
        assertEquals(0, parsed.startId)
        val encoded = toolJson.encodeToString(parsed)
        assertTrue(encoded.contains("\"in_scope_only\":false"))
    }

    @Test
    fun `query scanner issues should decode from empty object`() {
        val parsed =
            toolJson.decodeFromString(
                QueryScannerIssuesInput.serializer(),
                "{}",
            )

        assertEquals(20, parsed.limit)
        assertEquals(0, parsed.offset)
        assertEquals(3, parsed.maxRequestResponses)
        assertEquals(null, parsed.fields)
        assertEquals(null, parsed.excludeFields)
    }

    @Test
    fun `query scanner issues should ignore protocol meta field`() {
        val withMeta =
            JsonObject(
                mapOf(
                    "_meta" to JsonObject(mapOf("client" to JsonPrimitive("test"))),
                ),
            )

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryScannerIssuesInput.serializer(),
                withMeta.withoutProtocolMeta(),
            )

        assertEquals(20, parsed.limit)
        assertEquals(3, parsed.maxRequestResponses)
    }

    @Test
    fun `query scanner issues should accept scalar severity and confidence`() {
        val raw =
            Json.parseToJsonElement("""{"severity":"high","confidence":"certain"}""").let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryScannerIssuesInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals(listOf("HIGH"), parsed.severity?.map { it.name })
        assertEquals(listOf("CERTAIN"), parsed.confidence?.map { it.name })
    }

    @Test
    fun `query history should normalize scalar methods and string booleans`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"filter":{"methods":"POST","listener_ports":"8081","in_scope_only":"true"}}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryProxyHttpHistoryInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals(listOf("POST"), parsed.filter.methods)
        assertEquals(listOf(8081), parsed.filter.listenerPorts)
        assertEquals(true, parsed.filter.inScopeOnly)
    }

    @Test
    fun `query history should normalize csv listener ports and boolean aliases`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"filter":{"listener_ports":"8081, 8082,8084","has_response":"yes","in_scope_only":"off"}}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryProxyHttpHistoryInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals(listOf(8081, 8082, 8084), parsed.filter.listenerPorts)
        assertEquals(true, parsed.filter.hasResponse)
        assertEquals(false, parsed.filter.inScopeOnly)
    }

    @Test
    fun `csv splitting should not affect keys or urls`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"keys":"GET https://example.com/a,b","urls":"https://example.com/a,b"}""",
                ).let { it as JsonObject }

        val normalized = raw.normalizeAgentInput()
        assertEquals("[\"GET https://example.com/a,b\"]", normalized["keys"].toString())
        assertEquals("[\"https://example.com/a,b\"]", normalized["urls"].toString())
    }

    @Test
    fun `query history should keep null array-like fields as null`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"filter":{"methods":null,"mime_types":null,"inferred_mime_types":null,"status_codes":null}}""",
                ).let { it as JsonObject }

        val normalized = raw.normalizeAgentInput()
        val filter = normalized["filter"] as JsonObject
        assertEquals(JsonNull, filter["methods"])
        assertEquals(JsonNull, filter["mime_types"])
        assertEquals(JsonNull, filter["inferred_mime_types"])
        assertEquals(JsonNull, filter["status_codes"])

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryProxyHttpHistoryInput.serializer(),
                normalized,
            )
        assertEquals(null, parsed.filter.methods)
        assertEquals(null, parsed.filter.mimeTypes)
        assertEquals(null, parsed.filter.inferredMimeTypes)
        assertEquals(null, parsed.filter.statusCodes)
    }

    @Test
    fun `query organizer items should decode start id contract`() {
        val parsed =
            toolJson.decodeFromString(
                QueryOrganizerItemsInput.serializer(),
                """{"limit":5,"start_id":2,"id_direction":"decreasing","filter":{"in_scope_only":false}}""",
            )

        assertEquals(5, parsed.limit)
        assertEquals(2, parsed.startId)
        assertEquals("DECREASING", parsed.idDirection.name)
        assertEquals(false, parsed.filter.inScopeOnly)
    }

    @Test
    fun `query history should normalize scalar fields projection input to arrays`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"fields":"request.method","exclude_fields":null}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryProxyHttpHistoryInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals(listOf("request.method"), parsed.fields)
        assertEquals(null, parsed.excludeFields)
    }

    @Test
    fun `query history should normalize csv fields projection input to arrays`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"fields":"id,request.method,response.status_code","exclude_fields":null}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryProxyHttpHistoryInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals(listOf("id", "request.method", "response.status_code"), parsed.fields)
        assertEquals(null, parsed.excludeFields)
    }

    @Test
    fun `query history should normalize numeric strings in serialization`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"serialization":{"max_request_body_chars":"0","max_response_body_chars":"400","max_text_body_chars":"400","max_binary_body_bytes":"65536","regex_excerpt":{"context_chars":"15","regex":"abc"}}}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryProxyHttpHistoryInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals(0, parsed.serialization.maxRequestBodyChars)
        assertEquals(400, parsed.serialization.maxResponseBodyChars)
        assertEquals(400, parsed.serialization.maxTextBodyChars)
        assertEquals(65536, parsed.serialization.maxBinaryBodyBytes)
        assertEquals(15, parsed.serialization.regexExcerpt!!.contextChars)
    }

    @Test
    fun `send http1 input should normalize numeric strings`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"items":[{"content":"GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n","target_hostname":"example.com","target_port":"443","uses_https":true}],"parallel_rps":"5","request_options":{"response_timeout_ms":"1234"}}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                SendHttp1RequestInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals(443, parsed.items.single().targetPort)
        assertEquals(5, parsed.parallelRps)
        assertEquals(1234L, parsed.requestOptions!!.responseTimeoutMs)
    }

    @Test
    fun `send http1 input should parse request options from json string`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"items":[{"content":"GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n","target_hostname":"example.com","target_port":443,"uses_https":true}],"request_options":"{\"http_mode\":\"http_1\",\"response_timeout_ms\":\"1500\"}"}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                SendHttp1RequestInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals("http_1", parsed.requestOptions!!.httpMode)
        assertEquals(1500L, parsed.requestOptions!!.responseTimeoutMs)
    }

    @Test
    fun `send http1 input should normalize follow redirects alias`() {
        val raw =
            Json
                .parseToJsonElement(
                    """{"items":[{"content":"GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n","target_hostname":"example.com","target_port":443,"uses_https":true}],"request_options":{"follow_redirects":false}}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                SendHttp1RequestInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals("never", parsed.requestOptions!!.redirectionMode)
    }
}

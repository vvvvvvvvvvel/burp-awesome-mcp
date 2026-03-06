package net.portswigger.mcp.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.history.QueryProxyHttpHistoryInput
import net.portswigger.mcp.tools.QueryOrganizerItemsInput
import net.portswigger.mcp.tools.QueryScannerIssuesInput
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
        assertEquals(false, parsed.includeDetail)
        assertEquals(false, parsed.includeRemediation)
        assertEquals(false, parsed.includeRequestResponse)
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
        assertEquals(false, parsed.includeDetail)
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
                    """{"filter":{"methods":"POST","in_scope_only":"true"}}""",
                ).let { it as JsonObject }

        val parsed =
            toolJson.decodeFromJsonElement(
                QueryProxyHttpHistoryInput.serializer(),
                raw.normalizeAgentInput(),
            )

        assertEquals(listOf("POST"), parsed.filter.methods)
        assertEquals(true, parsed.filter.inScopeOnly)
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
}

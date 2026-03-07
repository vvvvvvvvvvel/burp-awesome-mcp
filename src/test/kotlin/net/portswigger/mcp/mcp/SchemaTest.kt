package net.portswigger.mcp.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.history.GetProxyHttpHistoryItemsInput
import net.portswigger.mcp.history.GetProxyWebSocketMessagesInput
import net.portswigger.mcp.history.GetSiteMapItemsInput
import net.portswigger.mcp.history.QueryProxyHttpHistoryInput
import net.portswigger.mcp.history.QueryProxyWebSocketHistoryInput
import net.portswigger.mcp.history.QuerySiteMapInput
import net.portswigger.mcp.tools.GenerateScannerReportInput
import net.portswigger.mcp.tools.GetCollaboratorInteractionsInput
import net.portswigger.mcp.tools.QueryCookieJarInput
import net.portswigger.mcp.tools.QueryOrganizerItemsInput
import net.portswigger.mcp.tools.QueryScannerIssuesInput
import net.portswigger.mcp.tools.SendHttp1RequestInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaTest {
    @Test
    fun `query history schema should not require defaulted fields`() {
        val schema = asInputSchema(QueryProxyHttpHistoryInput.serializer())
        assertTrue(schema.required.isNullOrEmpty(), "expected no required fields, got ${schema.required}")
    }

    @Test
    fun `get history items schema should require ids only`() {
        val schema = asInputSchema(GetProxyHttpHistoryItemsInput.serializer())
        assertEquals(listOf("ids"), schema.required ?: emptyList<String>())
        val properties = schema.properties as JsonObject
        assertTrue(!properties.containsKey("in_scope_only"))
        assertTrue(!properties.containsKey("force_refresh"))
    }

    @Test
    fun `get websocket and site map schemas should not expose in_scope_only`() {
        val webSocketSchema =
            asInputSchema(GetProxyWebSocketMessagesInput.serializer())
        val webSocketProperties = webSocketSchema.properties as JsonObject
        assertTrue(!webSocketProperties.containsKey("in_scope_only"))

        val siteMapSchema = asInputSchema(GetSiteMapItemsInput.serializer())
        val siteMapProperties = siteMapSchema.properties as JsonObject
        assertTrue(!siteMapProperties.containsKey("in_scope_only"))
    }

    @Test
    fun `query history schema should expose start id instead of order offset`() {
        val schema = asInputSchema(QueryProxyHttpHistoryInput.serializer())
        val properties = schema.properties as JsonObject
        assertTrue(properties.containsKey("start_id"))
        assertTrue(properties.containsKey("id_direction"))
        assertTrue(!properties.containsKey("offset"))
        assertTrue(!properties.containsKey("order"))
    }

    @Test
    fun `schema should expose snake_case property names`() {
        val schema = asInputSchema(QueryProxyHttpHistoryInput.serializer())
        val properties = schema.properties as JsonObject

        assertTrue(!properties.containsKey("in_scope_only"))
        assertTrue(properties.containsKey("serialization"))
        assertTrue(properties.containsKey("filter"))
        assertTrue(!properties.containsKey("include_binary"))
        assertTrue(!properties.containsKey("max_request_body_chars"))
        assertTrue(!properties.containsKey("max_response_body_chars"))
        assertTrue(!properties.containsKey("text_overflow_mode"))
        val filterSchema = properties["filter"] as JsonObject
        val filterProperties = filterSchema["properties"] as JsonObject
        assertTrue(filterProperties.containsKey("in_scope_only"))
        assertNull(properties["inScopeOnly"])
    }

    @Test
    fun `text overflow enum should expose omit and truncate`() {
        val schema = asInputSchema(QueryProxyHttpHistoryInput.serializer())
        val properties = schema.properties as JsonObject
        val serialization = properties["serialization"] as JsonObject
        val serializationProperties = serialization["properties"] as JsonObject
        val mode = serializationProperties["text_overflow_mode"] as JsonObject
        val enumNames =
            when {
                mode["enum"] is JsonArray -> {
                    (mode["enum"] as JsonArray).map { (it as JsonPrimitive).content }
                }

                mode["anyOf"] is JsonArray -> {
                    val enumValues = mode["anyOf"] as JsonArray
                    val enumBranch =
                        enumValues.first { element ->
                            (element as JsonObject)["enum"] != null
                        } as JsonObject
                    (enumBranch["enum"] as JsonArray).map { (it as JsonPrimitive).content }
                }

                else -> emptyList()
            }

        assertEquals(listOf("truncate", "omit"), enumNames)
    }

    @Test
    fun `schema should expose serialization defaults`() {
        val schema = asInputSchema(QueryProxyHttpHistoryInput.serializer())
        val properties = schema.properties as JsonObject

        val serialization = properties["serialization"] as JsonObject
        val serializationDefault = serialization["default"] as JsonObject
        assertEquals(JsonPrimitive(true), serializationDefault["include_headers"])
        assertEquals(JsonPrimitive(true), serializationDefault["include_request_body"])
        assertEquals(JsonPrimitive(true), serializationDefault["include_response_body"])
        assertEquals(JsonPrimitive(false), serializationDefault["include_raw_request"])
        assertEquals(JsonPrimitive(false), serializationDefault["include_raw_response"])
        assertEquals(JsonPrimitive(false), serializationDefault["include_binary"])
        assertEquals(JsonPrimitive(1024), serializationDefault["max_text_body_chars"])
        assertEquals(JsonPrimitive("omit"), serializationDefault["text_overflow_mode"])
        assertEquals(JsonPrimitive(65_536), serializationDefault["max_binary_body_bytes"])

        val serializationProperties = serialization["properties"] as JsonObject
        val maxText = serializationProperties["max_text_body_chars"] as JsonObject
        val maxBinary = serializationProperties["max_binary_body_bytes"] as JsonObject
        val overflowMode = serializationProperties["text_overflow_mode"] as JsonObject
        assertEquals(JsonPrimitive(1024), maxText["default"])
        assertEquals(JsonPrimitive(65_536), maxBinary["default"])
        assertEquals(JsonPrimitive("omit"), overflowMode["default"])
        assertEquals(JsonPrimitive(0), (properties["start_id"] as JsonObject)["default"])
        assertEquals(JsonPrimitive("increasing"), (properties["id_direction"] as JsonObject)["default"])
    }

    @Test
    fun `query scanner issues schema should expose triage defaults`() {
        val schema = asInputSchema(QueryScannerIssuesInput.serializer())
        val properties = schema.properties as JsonObject

        assertEquals(JsonPrimitive(20), (properties["limit"] as JsonObject)["default"])
        assertEquals(JsonPrimitive(0), (properties["offset"] as JsonObject)["default"])
        assertEquals(JsonPrimitive(false), (properties["include_request_response"] as JsonObject)["default"])
        assertEquals(JsonPrimitive(3), (properties["max_request_responses"] as JsonObject)["default"])
        assertTrue(properties.containsKey("severity"))
        assertTrue(properties.containsKey("confidence"))
        assertTrue(properties.containsKey("serialization"))

        val serialization = properties["serialization"] as JsonObject
        val serializationDefault = serialization["default"] as JsonObject
        assertEquals(JsonPrimitive(false), serializationDefault["include_request_body"])
        assertEquals(JsonPrimitive(false), serializationDefault["include_response_body"])
        val serializationProperties = serialization["properties"] as JsonObject
        assertEquals(JsonPrimitive(false), (serializationProperties["include_request_body"] as JsonObject)["default"])
        assertEquals(JsonPrimitive(false), (serializationProperties["include_response_body"] as JsonObject)["default"])
    }

    @Test
    fun `generate scanner report schema should keep severity confidence as arrays`() {
        val schema = asInputSchema(GenerateScannerReportInput.serializer())
        val properties = schema.properties as JsonObject

        val severity = properties["severity"] as JsonObject
        val severityAnyOf = severity["anyOf"] as JsonArray
        val severityArrayBranch =
            severityAnyOf
                .map { it as JsonObject }
                .first { (it["type"] as JsonPrimitive).content == "array" }
        assertTrue((severityArrayBranch["items"] as JsonObject).containsKey("enum"))
        assertTrue(severityAnyOf.none { (it as JsonObject)["type"] == JsonPrimitive("string") })

        val confidence = properties["confidence"] as JsonObject
        val confidenceAnyOf = confidence["anyOf"] as JsonArray
        val confidenceArrayBranch =
            confidenceAnyOf
                .map { it as JsonObject }
                .first { (it["type"] as JsonPrimitive).content == "array" }
        assertTrue((confidenceArrayBranch["items"] as JsonObject).containsKey("enum"))
        assertTrue(confidenceAnyOf.none { (it as JsonObject)["type"] == JsonPrimitive("string") })
    }

    @Test
    fun `query organizer items schema should keep status as array`() {
        val schema = asInputSchema(QueryOrganizerItemsInput.serializer())
        val properties = schema.properties as JsonObject
        assertTrue(properties.containsKey("start_id"))
        assertTrue(properties.containsKey("id_direction"))
        assertTrue(!properties.containsKey("offset"))
        assertTrue(!properties.containsKey("order"))
        val status = properties["status"] as JsonObject
        val statusAnyOf = status["anyOf"] as JsonArray
        val statusArrayBranch =
            statusAnyOf
                .map { it as JsonObject }
                .first { (it["type"] as JsonPrimitive).content == "array" }
        assertTrue((statusArrayBranch["items"] as JsonObject).containsKey("enum"))
        assertTrue(statusAnyOf.none { (it as JsonObject)["type"] == JsonPrimitive("string") })
        assertEquals(JsonPrimitive(0), (properties["start_id"] as JsonObject)["default"])
        assertEquals(JsonPrimitive("increasing"), (properties["id_direction"] as JsonObject)["default"])
    }

    @Test
    fun `query cookie jar schema should expose include expired default`() {
        val schema = asInputSchema(QueryCookieJarInput.serializer())
        val properties = schema.properties as JsonObject
        assertEquals(JsonPrimitive(false), (properties["include_expired"] as JsonObject)["default"])
    }

    @Test
    fun `collaborator interactions schema should expose payload and secret key inputs`() {
        val schema = asInputSchema(GetCollaboratorInteractionsInput.serializer())
        val properties = schema.properties as JsonObject
        assertTrue(properties.containsKey("payload_id"))
        assertTrue(properties.containsKey("payload"))
        assertTrue(properties.containsKey("secret_key"))
    }

    @Test
    fun `websocket query schema should expose structured filter defaults`() {
        val schema = asInputSchema(QueryProxyWebSocketHistoryInput.serializer())
        val properties = schema.properties as JsonObject
        assertTrue(properties.containsKey("filter"))
        assertTrue(properties.containsKey("id_direction"))
        assertTrue(!properties.containsKey("force_refresh"))
        assertEquals(JsonPrimitive("increasing"), (properties["id_direction"] as JsonObject)["default"])

        val filter = properties["filter"] as JsonObject
        val filterDefault = filter["default"] as JsonObject
        assertEquals(JsonPrimitive(true), filterDefault["in_scope_only"])
        assertEquals(JsonNull, filterDefault["regex"])
        assertEquals(JsonNull, filterDefault["direction"])
        assertEquals(JsonNull, filterDefault["web_socket_ids"])
        assertEquals(JsonNull, filterDefault["host_regex"])
        assertEquals(JsonNull, filterDefault["listener_ports"])
        assertEquals(JsonNull, filterDefault["has_edited_payload"])
        assertEquals(JsonNull, filterDefault["time_from"])
        assertEquals(JsonNull, filterDefault["time_to"])
    }

    @Test
    fun `history, site map, and websocket query schemas should expose correct filter defaults`() {
        val historySchema = asInputSchema(QueryProxyHttpHistoryInput.serializer())
        val historyFilterSchema = (historySchema.properties as JsonObject)["filter"] as JsonObject
        val historyFilterProperties = historyFilterSchema["properties"] as JsonObject
        val historyFilter = historyFilterSchema["default"] as JsonObject
        assertTrue(historyFilterProperties.containsKey("listener_ports"))
        assertEquals(JsonPrimitive(true), historyFilter["in_scope_only"])
        assertEquals(JsonNull, historyFilter["regex"])
        assertEquals(JsonNull, historyFilter["methods"])
        assertEquals(JsonNull, historyFilter["host_regex"])
        assertEquals(JsonNull, historyFilter["mime_types"])
        assertEquals(JsonNull, historyFilter["inferred_mime_types"])
        assertEquals(JsonNull, historyFilter["status_codes"])
        assertEquals(JsonNull, historyFilter["has_response"])
        assertEquals(JsonNull, historyFilter["time_from"])
        assertEquals(JsonNull, historyFilter["time_to"])
        assertEquals(JsonNull, historyFilter["listener_ports"])

        val siteMapSchema = asInputSchema(QuerySiteMapInput.serializer())
        val siteMapProperties = siteMapSchema.properties as JsonObject
        assertTrue(siteMapProperties.containsKey("start_after_key"))
        assertEquals(JsonNull, (siteMapProperties["start_after_key"] as JsonObject)["default"])
        val siteMapFilterSchema = ((siteMapSchema.properties as JsonObject)["filter"] as JsonObject)
        val siteMapFilterProperties = siteMapFilterSchema["properties"] as JsonObject
        val siteMapFilter = siteMapFilterSchema["default"] as JsonObject
        assertEquals(JsonPrimitive(true), siteMapFilter["in_scope_only"])
        assertEquals(JsonNull, siteMapFilter["regex"])
        assertEquals(JsonNull, siteMapFilter["methods"])
        assertEquals(JsonNull, siteMapFilter["host_regex"])
        assertEquals(JsonNull, siteMapFilter["mime_types"])
        assertEquals(JsonNull, siteMapFilter["inferred_mime_types"])
        assertEquals(JsonNull, siteMapFilter["status_codes"])
        assertEquals(JsonNull, siteMapFilter["has_response"])
        assertEquals(JsonNull, siteMapFilter["time_from"])
        assertEquals(JsonNull, siteMapFilter["time_to"])
        assertTrue(!siteMapFilterProperties.containsKey("listener_ports"))
        assertTrue(!siteMapFilter.containsKey("listener_ports"))
    }

    @Test
    fun `site map key schema should describe expected key format`() {
        val schema = asInputSchema(GetSiteMapItemsInput.serializer())
        val properties = schema.properties as JsonObject
        val keys = properties["keys"] as JsonObject
        val items = keys["items"] as JsonObject
        assertEquals(JsonPrimitive("^[0-9a-f]{24}$"), items["pattern"])
        assertEquals(
            JsonPrimitive("Use key values returned by list_site_map results[].key"),
            items["description"],
        )
    }

    @Test
    fun `send request options schema should remain object`() {
        val schema = asInputSchema(SendHttp1RequestInput.serializer())
        val properties = schema.properties as JsonObject
        val requestOptions = properties["request_options"] as JsonObject
        val anyOf = requestOptions["anyOf"] as JsonArray
        val hasObject =
            anyOf
                .map { it as JsonObject }
                .any { (it["type"] as JsonPrimitive).content == "object" }
        assertTrue(hasObject)
    }
}

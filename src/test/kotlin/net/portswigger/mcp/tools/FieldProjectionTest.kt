package net.portswigger.mcp.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.history.GetProxyHttpHistoryItemsInput
import net.portswigger.mcp.history.QueryProxyHttpHistoryInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FieldProjectionTest {
    @Test
    fun `fields projection should keep only selected nested item paths`() {
        val payload =
            JsonObject(
                mapOf(
                    "total" to JsonPrimitive(1),
                    "next" to JsonPrimitive("ignored"),
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(42),
                                        "listener_port" to JsonPrimitive(8081),
                                        "edited" to JsonPrimitive(false),
                                        "in_scope" to JsonPrimitive(true),
                                        "request" to
                                            JsonObject(
                                                mapOf(
                                                    "method" to JsonPrimitive("GET"),
                                                    "url" to JsonPrimitive("https://example.com/a"),
                                                    "headers" to JsonObject(mapOf("host" to JsonPrimitive("example.com"))),
                                                ),
                                            ),
                                        "response" to
                                            JsonObject(
                                                mapOf(
                                                    "status_code" to JsonPrimitive(200),
                                                    "mime_type" to JsonPrimitive("HTML"),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val projected =
            applyItemFieldProjection(
                payload,
                FieldProjection(
                    fields = setOf("id", "request.method", "response.status_code"),
                ),
            )

        val result = projected["results"]!!.let { it as JsonArray }[0] as JsonObject
        assertEquals(setOf("id", "request", "response"), result.keys)
        assertEquals("GET", ((result["request"] as JsonObject)["method"] as JsonPrimitive).content)
        assertEquals("200", ((result["response"] as JsonObject)["status_code"] as JsonPrimitive).content)
        assertFalse((result["request"] as JsonObject).containsKey("url"))
        assertFalse(result.containsKey("listener_port"))
    }

    @Test
    fun `exclude fields projection should remove nested item paths`() {
        val payload =
            JsonObject(
                mapOf(
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(42),
                                        "listener_port" to JsonPrimitive(8081),
                                        "request" to
                                            JsonObject(
                                                mapOf(
                                                    "method" to JsonPrimitive("GET"),
                                                    "headers" to JsonObject(mapOf("host" to JsonPrimitive("example.com"))),
                                                ),
                                            ),
                                        "response" to
                                            JsonObject(
                                                mapOf(
                                                    "status_code" to JsonPrimitive(200),
                                                    "body" to JsonObject(mapOf("text" to JsonPrimitive("hello"))),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val projected =
            applyItemFieldProjection(
                payload,
                FieldProjection(
                    excludeFields = setOf("request.headers", "response.body"),
                ),
            )

        val result = projected["results"]!!.let { it as JsonArray }[0] as JsonObject
        assertFalse(result.containsKey("listener_port"))
        assertFalse((result["request"] as JsonObject).containsKey("headers"))
        assertFalse((result["response"] as JsonObject).containsKey("body"))
    }

    @Test
    fun `default item shape should prune redundant http branches`() {
        val payload =
            JsonObject(
                mapOf(
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(42),
                                        "listener_port" to JsonPrimitive(8081),
                                        "edited" to JsonPrimitive(false),
                                        "request" to
                                            JsonObject(
                                                mapOf(
                                                    "method" to JsonPrimitive("GET"),
                                                    "url" to JsonPrimitive("https://example.com/a?x=1"),
                                                    "path" to JsonPrimitive("/a"),
                                                    "query" to JsonPrimitive("x=1"),
                                                    "in_scope" to JsonPrimitive(true),
                                                ),
                                            ),
                                        "response" to
                                            JsonObject(
                                                mapOf(
                                                    "status_code" to JsonPrimitive(200),
                                                    "mime_type" to JsonPrimitive("JSON"),
                                                    "stated_mime_type" to JsonPrimitive("JSON"),
                                                    "inferred_mime_type" to JsonPrimitive("JSON"),
                                                    "cookies" to JsonArray(emptyList()),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val projected = applyItemFieldProjection(payload, null)
        val result = (projected["results"] as JsonArray)[0] as JsonObject
        val request = result["request"] as JsonObject
        val response = result["response"] as JsonObject

        assertFalse(result.containsKey("listener_port"))
        assertFalse(result.containsKey("edited"))
        assertFalse(result.containsKey("in_scope"))
        assertFalse(request.containsKey("path"))
        assertFalse(request.containsKey("query"))
        assertFalse(request.containsKey("in_scope"))
        assertEquals("JSON", (response["mime_type"] as JsonPrimitive).content)
        assertFalse(response.containsKey("stated_mime_type"))
        assertFalse(response.containsKey("inferred_mime_type"))
        assertFalse(response.containsKey("cookies"))
    }

    @Test
    fun `explicit fields should preserve optimized-away branches when requested`() {
        val payload =
            JsonObject(
                mapOf(
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "listener_port" to JsonPrimitive(8081),
                                        "edited" to JsonPrimitive(false),
                                        "request" to
                                            JsonObject(
                                                mapOf(
                                                    "path" to JsonPrimitive("/a"),
                                                    "query" to JsonPrimitive("x=1"),
                                                    "in_scope" to JsonPrimitive(true),
                                                ),
                                            ),
                                        "response" to
                                            JsonObject(
                                                mapOf(
                                                    "mime_type" to JsonPrimitive("JSON"),
                                                    "stated_mime_type" to JsonPrimitive("JSON"),
                                                    "inferred_mime_type" to JsonPrimitive("JSON"),
                                                    "cookies" to JsonArray(emptyList()),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val projected =
            applyItemFieldProjection(
                payload,
                FieldProjection(
                    fields =
                        setOf(
                            "listener_port",
                            "edited",
                            "request.path",
                            "request.query",
                            "request.in_scope",
                            "response.mime_type",
                            "response.stated_mime_type",
                            "response.inferred_mime_type",
                            "response.cookies",
                        ),
                ),
            )

        val result = (projected["results"] as JsonArray)[0] as JsonObject
        val request = result["request"] as JsonObject
        val response = result["response"] as JsonObject

        assertEquals("8081", (result["listener_port"] as JsonPrimitive).content)
        assertEquals("false", (result["edited"] as JsonPrimitive).content)
        assertEquals("/a", (request["path"] as JsonPrimitive).content)
        assertEquals("x=1", (request["query"] as JsonPrimitive).content)
        assertEquals("true", (request["in_scope"] as JsonPrimitive).content)
        assertEquals("JSON", (response["mime_type"] as JsonPrimitive).content)
        assertEquals("JSON", (response["stated_mime_type"] as JsonPrimitive).content)
        assertEquals("JSON", (response["inferred_mime_type"] as JsonPrimitive).content)
        assertEquals(0, (response["cookies"] as JsonArray).size)
    }

    @Test
    fun `projection should only affect nested item inside lookup wrappers`() {
        val payload =
            JsonObject(
                mapOf(
                    "requested" to JsonPrimitive(1),
                    "found" to JsonPrimitive(1),
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(99),
                                        "item" to
                                            JsonObject(
                                                mapOf(
                                                    "id" to JsonPrimitive(99),
                                                    "time" to JsonPrimitive("2026-03-15T10:00:00Z"),
                                                    "request" to JsonObject(mapOf("method" to JsonPrimitive("POST"))),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val projected =
            applyItemFieldProjection(
                payload,
                FieldProjection(fields = setOf("request.method")),
            )

        val wrapper = (projected["results"] as JsonArray)[0] as JsonObject
        assertEquals("99", (wrapper["id"] as JsonPrimitive).content)
        val item = wrapper["item"] as JsonObject
        assertEquals(setOf("request"), item.keys)
        assertEquals("POST", ((item["request"] as JsonObject)["method"] as JsonPrimitive).content)
    }

    @Test
    fun `projection should ignore missing optional branches without error`() {
        val payload =
            JsonObject(
                mapOf(
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(42),
                                        "request" to JsonObject(mapOf("method" to JsonPrimitive("GET"))),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val projected =
            applyItemFieldProjection(
                payload,
                FieldProjection(
                    fields = setOf("id", "request.headers", "response.body"),
                ),
            )

        val result = (projected["results"] as JsonArray)[0] as JsonObject
        assertEquals(setOf("id"), result.keys)
    }

    @Test
    fun `exclude projection should ignore missing optional branches without error`() {
        val payload =
            JsonObject(
                mapOf(
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(42),
                                        "request" to JsonObject(mapOf("method" to JsonPrimitive("GET"))),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val projected =
            applyItemFieldProjection(
                payload,
                FieldProjection(
                    excludeFields = setOf("request.headers", "response.body"),
                ),
            )

        val result = (projected["results"] as JsonArray)[0] as JsonObject
        assertEquals(setOf("id", "request"), result.keys)
        assertEquals("GET", (((result["request"] as JsonObject)["method"]) as JsonPrimitive).content)
    }

    @Test
    fun `projection should treat match context like a normal optional branch`() {
        val payload =
            JsonObject(
                mapOf(
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(42),
                                        "request" to JsonObject(mapOf("method" to JsonPrimitive("GET"))),
                                        "match_context" to
                                            JsonObject(
                                                mapOf(
                                                    "excerpts" to
                                                        JsonArray(
                                                            listOf(
                                                                JsonObject(
                                                                    mapOf(
                                                                        "path" to JsonPrimitive("request.body.text"),
                                                                        "text" to JsonPrimitive("...token=abc..."),
                                                                    ),
                                                                ),
                                                            ),
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val included =
            applyItemFieldProjection(
                payload,
                FieldProjection(fields = setOf("id", "match_context.excerpts.text")),
            )
        val includedResult = (included["results"] as JsonArray)[0] as JsonObject
        val includedMatchContext = includedResult["match_context"] as JsonObject
        val includedExcerpts = includedMatchContext["excerpts"] as JsonArray
        val includedText = ((includedExcerpts[0] as JsonObject)["text"] as JsonPrimitive).content
        assertEquals("...token=abc...", includedText)
        assertFalse(includedResult.containsKey("request"))

        val excluded =
            applyItemFieldProjection(
                payload,
                FieldProjection(excludeFields = setOf("match_context", "request.method")),
            )
        val excludedResult = (excluded["results"] as JsonArray)[0] as JsonObject
        assertFalse(excludedResult.containsKey("match_context"))
        assertFalse((excludedResult["request"] as JsonObject).containsKey("method"))
    }

    @Test
    fun `projection should preserve nested arrays when selecting scanner request response paths`() {
        val payload =
            JsonObject(
                mapOf(
                    "results" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "name" to JsonPrimitive("finding"),
                                        "request_responses" to
                                            JsonArray(
                                                listOf(
                                                    JsonObject(
                                                        mapOf(
                                                            "request" to
                                                                JsonObject(
                                                                    mapOf(
                                                                        "method" to JsonPrimitive("GET"),
                                                                        "url" to JsonPrimitive("https://example.com/a"),
                                                                    ),
                                                                ),
                                                            "response" to JsonObject(mapOf("status_code" to JsonPrimitive(200))),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        ),
                ),
            )

        val projected =
            applyItemFieldProjection(
                payload,
                FieldProjection(
                    fields = setOf("name", "request_responses.request.method"),
                ),
            )

        val result = (projected["results"] as JsonArray)[0] as JsonObject
        val requestResponses = result["request_responses"] as JsonArray
        val first = requestResponses[0] as JsonObject
        assertEquals("GET", (((first["request"] as JsonObject)["method"]) as JsonPrimitive).content)
        assertFalse(first.containsKey("response"))
    }

    @Test
    fun `match context should be accepted as an explicit field path`() {
        val projection = QueryProxyHttpHistoryInput(fields = listOf("match_context.excerpts.text")).toFieldProjection()

        assertEquals(setOf("match_context.excerpts.text"), projection!!.fields)
    }

    @Test
    fun `field projection should reject both fields and exclude fields at once`() {
        val input =
            QueryProxyHttpHistoryInput(
                fields = listOf("id"),
                excludeFields = listOf("notes"),
            )

        assertThrows(IllegalArgumentException::class.java) {
            input.toFieldProjection()
        }
    }

    @Test
    fun `field projection should reject unsupported paths`() {
        val input =
            GetProxyHttpHistoryItemsInput(
                ids = listOf(1),
                fields = listOf("request.cookies.name"),
            )

        assertThrows(IllegalArgumentException::class.java) {
            input.toFieldProjection()
        }
    }

    @Test
    fun `bulk projection should leave error and null results unchanged without crashing`() {
        val payload =
            BulkToolResponse(
                results =
                    listOf(
                        BulkToolItemResult(
                            ok = true,
                            result =
                                JsonObject(
                                    mapOf(
                                        "status_code" to JsonPrimitive(200),
                                        "request" to
                                            JsonObject(
                                                mapOf(
                                                    "method" to JsonPrimitive("GET"),
                                                    "url" to JsonPrimitive("https://example.com"),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                        BulkToolItemResult(
                            ok = false,
                            result = null,
                            error = "timeout",
                        ),
                        BulkToolItemResult(
                            ok = true,
                            result = JsonNull,
                            error = null,
                        ),
                    ),
            )

        val encoded =
            encodeWithBulkResultFieldProjection(
                payload,
                FieldProjection(fields = setOf("request.method")),
            )

        val projected =
            kotlinx.serialization.json.Json
                .parseToJsonElement(encoded) as JsonObject
        val results = projected["results"] as JsonArray

        val first = results[0] as JsonObject
        val firstResult = first["result"] as JsonObject
        assertEquals(setOf("request"), firstResult.keys)
        assertEquals("GET", (((firstResult["request"] as JsonObject)["method"]) as JsonPrimitive).content)

        val second = results[1] as JsonObject
        assertEquals("false", (second["ok"] as JsonPrimitive).content)
        assertEquals("timeout", (second["error"] as JsonPrimitive).content)
        assertFalse(second.containsKey("result"))

        val third = results[2] as JsonObject
        assertEquals("true", (third["ok"] as JsonPrimitive).content)
        assertEquals(JsonNull, third["result"])
    }
}

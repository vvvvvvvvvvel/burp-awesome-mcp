package net.portswigger.mcp.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpOutputLoggerTest {
    @AfterEach
    fun tearDown() {
        McpOutputLogger.clear()
    }

    @Test
    fun `tool log should keep key inputs and mask heavy payload fields`() {
        var line: String? = null
        var debugEvent: String? = null
        McpOutputLogger.configureForTests(
            output = { message -> line = message },
            debugEvent = { message -> debugEvent = message },
        )

        McpOutputLogger.logTool(
            name = "send_http1_requests",
            status = McpActivityStatus.OK,
            request =
                JsonObject(
                    mapOf(
                        "parallel" to JsonPrimitive(true),
                        "parallel_rps" to JsonPrimitive(7),
                        "items" to
                            JsonArray(
                                listOf(
                                    JsonObject(
                                        mapOf(
                                            "content" to JsonPrimitive("GET / HTTP/1.1"),
                                            "target_hostname" to JsonPrimitive("example.com"),
                                            "target_port" to JsonPrimitive(443),
                                            "uses_https" to JsonPrimitive(true),
                                        ),
                                    ),
                                ),
                            ),
                    ),
                ),
            durationMs = 12,
            responseBytes = 2048,
        )

        val text = line ?: error("expected logger output")
        assertTrue(text.startsWith("MCP "))

        val payload = Json.parseToJsonElement(text.removePrefix("MCP ")) as JsonObject
        assertEquals("tool", payload["type"]?.jsonPrimitive?.content)
        assertEquals("ok", payload["status"]?.jsonPrimitive?.content)
        assertTrue(debugEvent?.startsWith("MCP ") == true)

        val req = payload["req"] as JsonObject
        assertEquals("7", req["parallel_rps"]?.jsonPrimitive?.content)

        val items = req["items"] as JsonObject
        assertEquals("1", items["count"]?.jsonPrimitive?.content)

        val sample = (items["sample"] as JsonArray).first() as JsonObject
        assertEquals("<content>", sample["content"]?.jsonPrimitive?.content)
        assertEquals("example.com", sample["target_hostname"]?.jsonPrimitive?.content)
    }

    @Test
    fun `server log should route info to debug and errors to error sink`() {
        var debugEvent: String? = null
        var errorEvent: String? = null
        McpOutputLogger.configureForTests(
            debugEvent = { debugEvent = it },
            errorEvent = { errorEvent = it },
        )

        McpOutputLogger.logServer(
            name = "start",
            status = McpActivityStatus.INFO,
            request = buildJsonObject { put("port", JsonPrimitive(26001)) },
        )
        McpOutputLogger.logServer(
            name = "start",
            status = McpActivityStatus.ERROR,
            request = buildJsonObject { put("port", JsonPrimitive(26001)) },
            note = "bind failed",
        )

        assertTrue(debugEvent?.contains("\"status\":\"info\"") == true)
        assertTrue(errorEvent?.contains("\"status\":\"error\"") == true)
    }

    @Test
    fun `debug event sink should be disabled when debug logging is off but output preserved`() {
        val outputLines = mutableListOf<String>()
        var debugEvent: String? = null
        var errorEvent: String? = null
        McpOutputLogger.configureForTests(
            output = { outputLines.add(it) },
            debugEvent = { debugEvent = it },
            errorEvent = { errorEvent = it },
            debugLoggingEnabled = false,
        )

        McpOutputLogger.logServer(
            name = "start",
            status = McpActivityStatus.INFO,
            request = buildJsonObject { put("port", JsonPrimitive(26001)) },
        )
        McpOutputLogger.logServer(
            name = "start",
            status = McpActivityStatus.ERROR,
            request = buildJsonObject { put("port", JsonPrimitive(26001)) },
            note = "bind failed",
        )

        assertEquals(2, outputLines.size)
        assertTrue(outputLines.any { it.contains("\"status\":\"info\"") })
        assertTrue(outputLines.any { it.contains("\"status\":\"error\"") })
        assertEquals(null, debugEvent, "debug event sink should be muted when debug logging is disabled")
        assertTrue(errorEvent?.contains("\"status\":\"error\"") == true)
    }
}

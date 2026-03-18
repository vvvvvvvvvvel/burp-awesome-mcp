package net.portswigger.mcp.core

import burp.api.montoya.logging.Logging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicReference

object McpOutputLogger {
    const val DEBUG_LOGGING_PROPERTY = "awesome.mcp.debug"

    private data class Sink(
        val output: (String) -> Unit = {},
        val debugEvent: (String) -> Unit = {},
        val errorEvent: (String) -> Unit = {},
    )

    private val sinkRef = AtomicReference(Sink())
    private val debugLoggingEnabledRef = AtomicReference(defaultDebugLoggingEnabled())

    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    fun configure(logging: Logging) {
        debugLoggingEnabledRef.set(defaultDebugLoggingEnabled())
        sinkRef.set(
            Sink(
                output = logging::logToOutput,
                debugEvent = logging::raiseDebugEvent,
                errorEvent = logging::raiseErrorEvent,
            ),
        )
    }

    @Suppress("unused")
    internal fun configureForTests(
        output: (String) -> Unit = {},
        debugEvent: (String) -> Unit = {},
        errorEvent: (String) -> Unit = {},
        debugLoggingEnabled: Boolean = true,
    ) {
        debugLoggingEnabledRef.set(debugLoggingEnabled)
        sinkRef.set(
            Sink(
                output = output,
                debugEvent = debugEvent,
                errorEvent = errorEvent,
            ),
        )
    }

    fun clear() {
        debugLoggingEnabledRef.set(defaultDebugLoggingEnabled())
        sinkRef.set(Sink())
    }

    fun logServer(
        name: String,
        status: McpActivityStatus,
        request: JsonObject? = null,
        durationMs: Long? = null,
        note: String? = null,
    ) {
        val payload =
            buildJsonObject {
                put("type", JsonPrimitive("server"))
                put("name", JsonPrimitive(name))
                put("status", JsonPrimitive(status.name.lowercase()))
                if (durationMs != null) {
                    put("ms", JsonPrimitive(durationMs))
                }
                if (request != null) {
                    put("req", sanitizeElement(request, keyHint = null, depth = 0))
                }
                if (!note.isNullOrBlank()) {
                    put("note", JsonPrimitive(note.compact(220)))
                }
            }
        emit(payload, status)
    }

    fun logTool(
        name: String,
        status: McpActivityStatus,
        request: JsonObject,
        durationMs: Long? = null,
        responseBytes: Int? = null,
        note: String? = null,
    ) {
        val payload =
            buildJsonObject {
                put("type", JsonPrimitive("tool"))
                put("name", JsonPrimitive(name))
                put("status", JsonPrimitive(status.name.lowercase()))
                if (durationMs != null) {
                    put("ms", JsonPrimitive(durationMs))
                }
                put("req", sanitizeElement(request, keyHint = null, depth = 0))
                if (responseBytes != null) {
                    put("resp_bytes", JsonPrimitive(responseBytes))
                }
                if (!note.isNullOrBlank()) {
                    put("note", JsonPrimitive(note.compact(220)))
                }
            }
        emit(payload, status)
    }

    private fun emit(
        payload: JsonObject,
        status: McpActivityStatus,
    ) {
        val message = "MCP ${json.encodeToString(payload)}"
        val sink = sinkRef.get()
        val debugLoggingEnabled = debugLoggingEnabledRef.get()
        runCatching {
            sink.output(message)
        }
        runCatching {
            when (status) {
                McpActivityStatus.ERROR -> sink.errorEvent(message)
                McpActivityStatus.OK -> if (debugLoggingEnabled) sink.debugEvent(message)
                McpActivityStatus.INFO -> if (debugLoggingEnabled) sink.debugEvent(message)
            }
        }
    }

    private fun sanitizeElement(
        element: JsonElement,
        keyHint: String?,
        depth: Int,
    ): JsonElement {
        if (depth > MAX_DEPTH) {
            return JsonPrimitive("<depth_limit>")
        }

        val normalizedKey = keyHint.normalizeKey()

        if (normalizedKey in REDACTED_VALUE_KEYS) {
            return JsonPrimitive("<redacted>")
        }

        if (normalizedKey in MASKED_BLOB_KEYS) {
            return JsonPrimitive("<${keyHint ?: "masked"}>")
        }

        return when (element) {
            is JsonObject -> {
                val properties =
                    element.mapValues { (key, value) ->
                        sanitizeElement(value, keyHint = key, depth = depth + 1)
                    }
                JsonObject(properties)
            }

            is JsonArray -> {
                if (normalizedKey == "items") {
                    val sample =
                        element
                            .take(MAX_ITEMS_SAMPLE)
                            .map { sanitizeElement(it, keyHint = "item", depth = depth + 1) }
                    buildJsonObject {
                        put("count", JsonPrimitive(element.size))
                        put("sample", JsonArray(sample))
                        if (element.size > MAX_ITEMS_SAMPLE) {
                            put("omitted", JsonPrimitive(element.size - MAX_ITEMS_SAMPLE))
                        }
                    }
                } else if (element.size > MAX_ARRAY_SAMPLE) {
                    val sample =
                        element
                            .take(MAX_ARRAY_SAMPLE)
                            .map { sanitizeElement(it, keyHint = keyHint, depth = depth + 1) }
                    buildJsonObject {
                        put("count", JsonPrimitive(element.size))
                        put("sample", JsonArray(sample))
                        put("omitted", JsonPrimitive(element.size - MAX_ARRAY_SAMPLE))
                    }
                } else {
                    JsonArray(element.map { sanitizeElement(it, keyHint = keyHint, depth = depth + 1) })
                }
            }

            JsonNull -> JsonNull

            is JsonPrimitive -> {
                if (!element.isString) {
                    element
                } else {
                    JsonPrimitive(element.content.compact(MAX_STRING_LENGTH))
                }
            }
        }
    }
}

private fun defaultDebugLoggingEnabled(): Boolean {
    val override = System.getProperty(McpOutputLogger.DEBUG_LOGGING_PROPERTY)
    if (override != null) {
        return override.equals("true", ignoreCase = true)
    }
    return BuildFlags.debugBuildEnabled()
}

private fun String?.normalizeKey(): String {
    if (this == null) return ""
    return lowercase().replace("_", "").replace("-", "")
}

private fun String.compact(limit: Int): String {
    val normalized = replace("\n", " ").replace("\r", " ").replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= limit) {
        return normalized
    }
    return "${normalized.take(limit - 3)}..."
}

private const val MAX_DEPTH = 6
private const val MAX_STRING_LENGTH = 220
private const val MAX_ARRAY_SAMPLE = 8
private const val MAX_ITEMS_SAMPLE = 3

private val MASKED_BLOB_KEYS =
    normalizedKeySet(
        "content",
        "request_body",
        "body",
        "raw",
        "data",
        "payload",
        "text",
        "base64",
    )

private val REDACTED_VALUE_KEYS =
    normalizedKeySet(
        "authorization",
        "cookie",
        "set_cookie",
        "password",
        "token",
        "secret",
        "api_key",
        "access_token",
    )

private fun normalizedKeySet(vararg values: String): Set<String> = values.map { it.normalizeKey() }.toSet()

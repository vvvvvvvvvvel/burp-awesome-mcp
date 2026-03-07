package net.portswigger.mcp.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.serializer
import net.portswigger.mcp.core.McpActivityStatus
import net.portswigger.mcp.core.McpOutputLogger

@PublishedApi
@OptIn(ExperimentalSerializationApi::class)
internal val toolJson =
    Json {
        ignoreUnknownKeys = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

private val EMPTY_TOOL_ARGUMENTS = JsonObject(emptyMap())

@OptIn(InternalSerializationApi::class)
internal inline fun <reified I : Any> Server.registerTool(
    name: String,
    description: String,
    crossinline handler: I.() -> String,
) {
    val serializer: KSerializer<I> = I::class.serializer()
    val enumHints = collectEnumHints(serializer.descriptor)

    addTool(
        name = name,
        description = description,
        inputSchema = asInputSchema(serializer),
        handler = { request ->
            val startedNanos = System.nanoTime()
            val requestName = request.name.ifBlank { name }
            val requestArgs = request.arguments ?: EMPTY_TOOL_ARGUMENTS
            val decodeArgs = requestArgs.withoutProtocolMeta().normalizeAgentInput()

            val parsedInput =
                try {
                    toolJson.decodeFromJsonElement(serializer, decodeArgs)
                } catch (e: Exception) {
                    val durationMs = elapsedMillis(startedNanos)
                    val formattedError = formatToolInputError(e.message, enumHints)
                    val errorMessage =
                        if (formattedError == "invalid tool input") {
                            val errorType = e::class.simpleName ?: "ToolInputDecodeError"
                            "$formattedError ($errorType)"
                        } else {
                            formattedError
                        }

                    McpOutputLogger.logTool(
                        name = requestName,
                        status = McpActivityStatus.ERROR,
                        request = requestArgs,
                        durationMs = durationMs,
                        note = errorMessage,
                    )

                    return@addTool toolErrorResult(errorMessage)
                }

            executeToolHandler(
                requestName = requestName,
                requestArgs = requestArgs,
                startedNanos = startedNanos,
            ) {
                handler(parsedInput)
            }
        },
    )
}

internal fun Server.registerNoInputTool(
    name: String,
    description: String,
    handler: () -> String,
) {
    addTool(
        name = name,
        description = description,
        inputSchema = ToolSchema(),
        handler = { request ->
            val startedNanos = System.nanoTime()
            val requestName = request.name.ifBlank { name }
            val requestArgs = request.arguments ?: EMPTY_TOOL_ARGUMENTS

            executeToolHandler(
                requestName = requestName,
                requestArgs = requestArgs,
                startedNanos = startedNanos,
            ) {
                handler()
            }
        },
    )
}

internal fun JsonObject.withoutProtocolMeta(): JsonObject {
    if (!containsKey("_meta") && !containsKey("meta")) return this
    return JsonObject(entries.filterNot { (key, _) -> key == "_meta" || key == "meta" }.associate { it.toPair() })
}

private val SCALAR_TO_ARRAY_FIELDS =
    setOf(
        "methods",
        "mime_types",
        "inferred_mime_types",
        "status_codes",
        "direction",
        "web_socket_ids",
        "listener_ports",
        "severity",
        "confidence",
        "status",
        "ids",
        "keys",
        "seed_urls",
        "urls",
    )

private val CSV_STRING_TO_ARRAY_FIELDS =
    setOf(
        "methods",
        "mime_types",
        "inferred_mime_types",
        "status_codes",
        "direction",
        "web_socket_ids",
        "listener_ports",
        "severity",
        "confidence",
        "status",
        "ids",
    )

private val STRING_BOOLEAN_FIELDS =
    setOf(
        "in_scope_only",
        "has_response",
        "include_headers",
        "include_request_body",
        "include_response_body",
        "include_raw_request",
        "include_raw_response",
        "include_binary",
        "include_edited_payload",
        "parallel",
        "intercepting",
        "running",
        "include_values",
        "include_expired",
        "include_subdomains",
        "force_refresh",
        "include_detail",
        "include_remediation",
        "include_definition",
        "include_request_response",
        "headless",
        "active_tests",
    )

internal fun JsonObject.normalizeAgentInput(): JsonObject = normalizeJsonElement(this) as JsonObject

private fun normalizeJsonElement(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> {
            val normalized =
                element.entries.associate { (key, value) ->
                    val normalizedValue = normalizeJsonElement(value)
                    val coercedArray =
                        if (
                            key in SCALAR_TO_ARRAY_FIELDS &&
                            normalizedValue !is JsonArray &&
                            normalizedValue !is JsonNull
                        ) {
                            coerceScalarToArray(key, normalizedValue)
                        } else {
                            normalizedValue
                        }
                    key to coerceBooleanString(key, coercedArray)
                }
            JsonObject(normalized)
        }

        is JsonArray -> JsonArray(element.map(::normalizeJsonElement))
        else -> element
    }

private fun coerceScalarToArray(
    key: String,
    value: JsonElement,
): JsonArray {
    val scalarContent = (value as? JsonPrimitive)?.contentOrNull
    if (key !in CSV_STRING_TO_ARRAY_FIELDS || scalarContent == null || ',' !in scalarContent) {
        return JsonArray(listOf(value))
    }
    val items =
        scalarContent
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::JsonPrimitive)
    return JsonArray(items.ifEmpty { listOf(value) })
}

private fun coerceBooleanString(
    key: String,
    value: JsonElement,
): JsonElement {
    if (key !in STRING_BOOLEAN_FIELDS) return value
    if (value !is JsonPrimitive) return value
    if (value.booleanOrNull != null) return value
    val lower = value.contentOrNull?.lowercase() ?: return value
    return when (lower) {
        "true", "yes", "1", "on" -> JsonPrimitive(true)
        "false", "no", "0", "off" -> JsonPrimitive(false)
        else -> value
    }
}

fun escapeJson(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

private fun toolErrorJson(message: String): String = "{\"error\":\"${escapeJson(message)}\"}"

private fun toolErrorResult(message: String): CallToolResult =
    CallToolResult(
        content = listOf(TextContent(toolErrorJson(message))),
        isError = true,
    )

private inline fun executeToolHandler(
    requestName: String,
    requestArgs: JsonObject,
    startedNanos: Long,
    block: () -> String,
): CallToolResult =
    try {
        val resultText = block()
        val durationMs = elapsedMillis(startedNanos)
        val responseBytes = resultText.toByteArray().size
        val note = summarizeText(resultText)

        McpOutputLogger.logTool(
            name = requestName,
            status = McpActivityStatus.OK,
            request = requestArgs,
            durationMs = durationMs,
            responseBytes = responseBytes,
            note = note,
        )

        CallToolResult(content = listOf(TextContent(resultText)))
    } catch (e: Exception) {
        val durationMs = elapsedMillis(startedNanos)
        val errorMessage = formatRuntimeToolError(e)

        McpOutputLogger.logTool(
            name = requestName,
            status = McpActivityStatus.ERROR,
            request = requestArgs,
            durationMs = durationMs,
            note = errorMessage,
        )

        toolErrorResult(errorMessage)
    }

@PublishedApi
internal fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

@PublishedApi
internal fun summarizeText(value: String): String {
    val compact =
        value
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    if (compact.isEmpty()) return "(empty)"
    return if (compact.length <= 120) compact else "${compact.take(117)}..."
}

internal fun formatToolInputError(
    message: String?,
    enumHints: List<EnumHint>,
): String {
    val trimmed =
        message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    if (trimmed.isBlank()) {
        return "invalid tool input"
    }

    UNKNOWN_KEY_REGEX.find(trimmed)?.let { match ->
        val field = match.groupValues[1]
        return "unknown field '$field' in tool input"
    }

    ENUM_VALUE_REGEX.find(trimmed)?.let { match ->
        val enumTypeRaw = match.groupValues[1]
        val enumType = enumTypeRaw.substringAfterLast('.')
        val invalidValue = match.groupValues[2]
        val pathFromMessage = PATH_REGEX.find(trimmed)?.groupValues?.getOrNull(1)

        val candidates =
            enumHints.filter {
                it.enumType == enumType || it.enumType == enumTypeRaw
            }

        val hint =
            when {
                candidates.size == 1 -> candidates.first()
                !pathFromMessage.isNullOrBlank() -> candidates.firstOrNull { it.path == pathFromMessage }
                else -> null
            }

        if (hint != null) {
            val allowed = hint.allowedValues.joinToString(", ")
            return "invalid value '$invalidValue' for '${hint.path}'; allowed: [$allowed]"
        }

        return "invalid enum value '$invalidValue'; allowed values are defined in tool schema"
    }

    return trimmed
}

internal fun formatRuntimeToolError(error: Throwable): String {
    val firstLine =
        error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    if (firstLine.isNotBlank()) {
        return firstLine
    }
    return error::class.simpleName ?: "tool execution failed"
}

internal data class EnumHint(
    val path: String,
    val enumType: String,
    val allowedValues: List<String>,
)

@OptIn(ExperimentalSerializationApi::class)
internal fun collectEnumHints(descriptor: SerialDescriptor): List<EnumHint> {
    val hints = mutableListOf<EnumHint>()
    val visited = mutableSetOf<String>()

    fun walk(
        current: SerialDescriptor,
        path: String,
    ) {
        when (val kind = current.kind) {
            SerialKind.ENUM -> {
                val values = (0 until current.elementsCount).map { current.getElementName(it) }
                hints += EnumHint(path = path, enumType = current.serialName.substringAfterLast('.'), allowedValues = values)
            }

            StructureKind.CLASS,
            StructureKind.OBJECT,
            -> {
                val serialName = current.serialName
                if (!visited.add(serialName)) {
                    return
                }

                for (index in 0 until current.elementsCount) {
                    val elementName = current.getElementName(index).camelToSnakeCase()
                    val nextPath = if (path.isBlank()) elementName else "$path.$elementName"
                    walk(current.getElementDescriptor(index), nextPath)
                }

                visited.remove(serialName)
            }

            StructureKind.LIST -> {
                if (current.elementsCount > 0) {
                    walk(current.getElementDescriptor(0), path)
                }
            }

            StructureKind.MAP -> {
                if (current.elementsCount > 1) {
                    walk(current.getElementDescriptor(1), path)
                }
            }

            is PolymorphicKind -> Unit
            else -> Unit
        }
    }

    walk(descriptor, "")
    return hints
}

private val UNKNOWN_KEY_REGEX = Regex("unknown key ['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
private val ENUM_VALUE_REGEX = Regex("([A-Za-z0-9_$.]+) does not contain element with name ['\"]([^'\"]+)['\"]")
private val PATH_REGEX = Regex("at path: \\$(?:\\.|\\[')([^'\\]]+)")

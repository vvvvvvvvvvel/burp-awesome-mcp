package net.portswigger.mcp.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
fun asInputSchema(serializer: KSerializer<*>): ToolSchema {
    val descriptor = serializer.descriptor
    val properties = mutableMapOf<String, JsonElement>()
    val required = mutableListOf<String>()

    for (index in 0 until descriptor.elementsCount) {
        val name = descriptor.getElementName(index).camelToSnakeCase()
        val fieldSchema = schemaForDescriptor(descriptor.getElementDescriptor(index), mutableSetOf())
        val fieldDefault = knownPropertyDefault(descriptor.serialName, name)
        properties[name] =
            if (fieldDefault != null) {
                appendDefault(fieldSchema, fieldDefault)
            } else {
                fieldSchema
            }
        if (!descriptor.isElementOptional(index)) {
            required += name
        }
    }
    applyKnownPropertySchemaHints(descriptor.serialName, properties)
    return ToolSchema(
        properties = JsonObject(properties),
        required = required,
    )
}

private fun applyKnownPropertySchemaHints(
    serialName: String,
    properties: MutableMap<String, JsonElement>,
) {
    when (serialName.substringAfterLast('.')) {
        "GetSiteMapItemsInput" -> {
            val keysSchema = properties["keys"] as? JsonObject ?: return
            val itemsSchema = (keysSchema["items"] as? JsonObject) ?: jsonType("string")
            val hintedItems =
                JsonObject(
                    itemsSchema +
                        mapOf(
                            "pattern" to JsonPrimitive("^[0-9a-f]{24}$"),
                            "description" to JsonPrimitive("Use key values returned by list_site_map results[].key"),
                        ),
                )
            properties["keys"] = JsonObject(keysSchema + ("items" to hintedItems))
        }

        "QueryScannerIssuesInput" -> {
            val serializationSchema = properties["serialization"] as? JsonObject
            if (serializationSchema != null) {
                val serializationDefault = SCANNER_HTTP_SERIALIZATION_DEFAULT
                val serializationProperties = (serializationSchema["properties"] as? JsonObject)?.toMutableMap()
                if (serializationProperties != null) {
                    serializationProperties["include_request_body"] =
                        appendDefault(
                            serializationProperties["include_request_body"] ?: jsonType("boolean"),
                            JsonPrimitive(false),
                        )
                    serializationProperties["include_response_body"] =
                        appendDefault(
                            serializationProperties["include_response_body"] ?: jsonType("boolean"),
                            JsonPrimitive(false),
                        )
                    properties["serialization"] =
                        JsonObject(
                            serializationSchema +
                                mapOf(
                                    "default" to serializationDefault,
                                    "properties" to JsonObject(serializationProperties),
                                ),
                        )
                } else {
                    properties["serialization"] = appendDefault(serializationSchema, serializationDefault)
                }
            }
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun schemaForDescriptor(
    descriptor: SerialDescriptor,
    visited: MutableSet<String>,
): JsonElement {
    val coreSchema =
        when (descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> jsonType("string")
            PrimitiveKind.BOOLEAN -> jsonType("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> jsonType("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> jsonType("number")
            StructureKind.LIST -> {
                val itemSchema =
                    if (descriptor.elementsCount > 0) {
                        schemaForDescriptor(descriptor.getElementDescriptor(0), visited)
                    } else {
                        jsonType("object")
                    }

                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "items" to itemSchema,
                    ),
                )
            }

            StructureKind.MAP -> {
                val valueSchema =
                    if (descriptor.elementsCount > 1) {
                        schemaForDescriptor(descriptor.getElementDescriptor(1), visited)
                    } else {
                        jsonType("object")
                    }

                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "additionalProperties" to valueSchema,
                    ),
                )
            }

            SerialKind.ENUM -> {
                val variants =
                    (0 until descriptor.elementsCount)
                        .map { JsonPrimitive(descriptor.getElementName(it)) }

                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "enum" to JsonArray(variants),
                    ),
                )
            }

            StructureKind.CLASS, StructureKind.OBJECT -> objectSchema(descriptor, visited)

            is PolymorphicKind -> jsonType("object")

            else -> jsonType("object")
        }

    return if (!descriptor.isNullable) {
        coreSchema
    } else {
        JsonObject(
            mapOf(
                "anyOf" to
                    JsonArray(
                        listOf(
                            coreSchema,
                            JsonObject(mapOf("type" to JsonPrimitive("null"))),
                        ),
                    ),
            ),
        )
    }
}

private fun objectSchema(
    descriptor: SerialDescriptor,
    visited: MutableSet<String>,
): JsonElement {
    val serialName = descriptor.serialName
    if (!visited.add(serialName)) {
        return jsonType("object")
    }

    val nestedProperties = mutableMapOf<String, JsonElement>()
    val nestedRequired = mutableListOf<JsonElement>()

    for (index in 0 until descriptor.elementsCount) {
        val name = descriptor.getElementName(index).camelToSnakeCase()
        val propertySchema = schemaForDescriptor(descriptor.getElementDescriptor(index), visited)
        val propertyDefault = knownPropertyDefault(serialName, name)
        nestedProperties[name] =
            if (propertyDefault != null) {
                appendDefault(propertySchema, propertyDefault)
            } else {
                propertySchema
            }
        if (!descriptor.isElementOptional(index)) {
            nestedRequired += JsonPrimitive(name)
        }
    }

    visited.remove(serialName)

    val values =
        mutableMapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(nestedProperties),
            "additionalProperties" to JsonPrimitive(false),
        )
    if (nestedRequired.isNotEmpty()) {
        values["required"] = JsonArray(nestedRequired)
    }
    knownObjectDefault(serialName)?.let { values["default"] = it }

    return JsonObject(values)
}

private fun jsonType(type: String): JsonObject = JsonObject(mapOf("type" to JsonPrimitive(type)))

private fun appendDefault(
    schema: JsonElement,
    defaultValue: JsonElement,
): JsonElement {
    if (schema !is JsonObject) return schema
    return JsonObject(schema + ("default" to defaultValue))
}

private val HTTP_SERIALIZATION_DEFAULT = httpSerializationDefault()
private val SCANNER_HTTP_SERIALIZATION_DEFAULT = scannerHttpSerializationDefault()
private val WEBSOCKET_SERIALIZATION_DEFAULT = webSocketSerializationDefault()
private val REQUEST_RESPONSE_FILTER_DEFAULT = requestResponseFilterDefault()
private val PROXY_HTTP_HISTORY_FILTER_DEFAULT = proxyHttpHistoryFilterDefault()
private val WEBSOCKET_FILTER_DEFAULT = webSocketFilterDefault()

private fun httpSerializationDefault(): JsonObject =
    JsonObject(
        mapOf(
            "include_headers" to JsonPrimitive(true),
            "include_request_body" to JsonPrimitive(true),
            "include_response_body" to JsonPrimitive(true),
            "include_raw_request" to JsonPrimitive(false),
            "include_raw_response" to JsonPrimitive(false),
            "include_binary" to JsonPrimitive(false),
            "max_text_body_chars" to JsonPrimitive(1024),
            "max_request_body_chars" to JsonNull,
            "max_response_body_chars" to JsonNull,
            "text_overflow_mode" to JsonPrimitive("omit"),
            "max_binary_body_bytes" to JsonPrimitive(65_536),
        ),
    )

private fun scannerHttpSerializationDefault(): JsonObject =
    JsonObject(
        HTTP_SERIALIZATION_DEFAULT +
            mapOf(
                "include_request_body" to JsonPrimitive(false),
                "include_response_body" to JsonPrimitive(false),
            ),
    )

private fun webSocketSerializationDefault(): JsonObject =
    JsonObject(
        mapOf(
            "include_binary" to JsonPrimitive(false),
            "include_edited_payload" to JsonPrimitive(false),
            "max_text_payload_chars" to JsonPrimitive(4000),
            "max_binary_payload_bytes" to JsonPrimitive(65536),
        ),
    )

private fun requestResponseFilterDefault(): JsonObject =
    JsonObject(
        mapOf(
            "in_scope_only" to JsonPrimitive(true),
            "regex" to JsonNull,
            "methods" to JsonNull,
            "host_regex" to JsonNull,
            "mime_types" to JsonNull,
            "inferred_mime_types" to JsonNull,
            "status_codes" to JsonNull,
            "has_response" to JsonNull,
            "time_from" to JsonNull,
            "time_to" to JsonNull,
        ),
    )

private fun proxyHttpHistoryFilterDefault(): JsonObject =
    JsonObject(
        REQUEST_RESPONSE_FILTER_DEFAULT +
            mapOf(
                "listener_ports" to JsonNull,
            ),
    )

private fun webSocketFilterDefault(): JsonObject =
    JsonObject(
        mapOf(
            "in_scope_only" to JsonPrimitive(true),
            "regex" to JsonNull,
            "direction" to JsonNull,
            "web_socket_ids" to JsonNull,
            "host_regex" to JsonNull,
            "listener_ports" to JsonNull,
            "has_edited_payload" to JsonNull,
            "time_from" to JsonNull,
            "time_to" to JsonNull,
        ),
    )

private fun knownObjectDefault(serialName: String): JsonElement? =
    when (serialName.substringAfterLast('.')) {
        "HttpSerializationOptionsInput" -> HTTP_SERIALIZATION_DEFAULT
        "WebSocketSerializationOptionsInput" -> WEBSOCKET_SERIALIZATION_DEFAULT
        "HttpRequestResponseFilterInput" -> REQUEST_RESPONSE_FILTER_DEFAULT
        "ProxyHttpHistoryFilterInput" -> PROXY_HTTP_HISTORY_FILTER_DEFAULT
        "WebSocketHistoryFilterInput" -> WEBSOCKET_FILTER_DEFAULT

        else -> null
    }

private fun knownPropertyDefault(
    serialName: String,
    propertyName: String,
): JsonElement? =
    when (serialName.substringAfterLast('.')) {
        "QueryProxyHttpHistoryInput" ->
            when (propertyName) {
                "limit" -> JsonPrimitive(20)
                "start_id" -> JsonPrimitive(0)
                "id_direction" -> JsonPrimitive("increasing")
                "filter" -> PROXY_HTTP_HISTORY_FILTER_DEFAULT
                "serialization" -> HTTP_SERIALIZATION_DEFAULT
                else -> null
            }

        "GetProxyHttpHistoryItemsInput" ->
            when (propertyName) {
                "serialization" -> HTTP_SERIALIZATION_DEFAULT
                else -> null
            }

        "QueryProxyWebSocketHistoryInput" ->
            when (propertyName) {
                "limit" -> JsonPrimitive(20)
                "start_id" -> JsonPrimitive(0)
                "id_direction" -> JsonPrimitive("increasing")
                "filter" -> WEBSOCKET_FILTER_DEFAULT
                "serialization" -> WEBSOCKET_SERIALIZATION_DEFAULT
                else -> null
            }

        "GetProxyWebSocketMessagesInput" ->
            when (propertyName) {
                "serialization" -> WEBSOCKET_SERIALIZATION_DEFAULT
                else -> null
            }

        "QuerySiteMapInput" ->
            when (propertyName) {
                "limit" -> JsonPrimitive(20)
                "start_after_key" -> JsonNull
                "filter" -> REQUEST_RESPONSE_FILTER_DEFAULT
                "serialization" -> HTTP_SERIALIZATION_DEFAULT
                else -> null
            }

        "GetSiteMapItemsInput" ->
            when (propertyName) {
                "serialization" -> HTTP_SERIALIZATION_DEFAULT
                else -> null
            }

        "SendHttp1RequestInput" ->
            when (propertyName) {
                "request_options" -> JsonNull
                "serialization" -> HTTP_SERIALIZATION_DEFAULT
                "parallel" -> JsonPrimitive(false)
                "parallel_rps" -> JsonPrimitive(10)
                else -> null
            }

        "SendHttp2RequestInput" ->
            when (propertyName) {
                "request_options" -> JsonNull
                "serialization" -> HTTP_SERIALIZATION_DEFAULT
                "parallel" -> JsonPrimitive(false)
                "parallel_rps" -> JsonPrimitive(10)
                else -> null
            }

        "QueryScannerIssuesInput" ->
            when (propertyName) {
                "limit" -> JsonPrimitive(20)
                "offset" -> JsonPrimitive(0)
                "severity" -> JsonNull
                "confidence" -> JsonNull
                "name_regex" -> JsonNull
                "url_regex" -> JsonNull
                "include_detail" -> JsonPrimitive(false)
                "include_remediation" -> JsonPrimitive(false)
                "include_definition" -> JsonPrimitive(false)
                "include_request_response" -> JsonPrimitive(false)
                "max_request_responses" -> JsonPrimitive(3)
                "serialization" -> SCANNER_HTTP_SERIALIZATION_DEFAULT

                else -> null
            }

        "QueryOrganizerItemsInput" ->
            when (propertyName) {
                "limit" -> JsonPrimitive(20)
                "start_id" -> JsonPrimitive(0)
                "id_direction" -> JsonPrimitive("increasing")
                "status" -> JsonNull
                "filter" -> REQUEST_RESPONSE_FILTER_DEFAULT
                "serialization" -> HTTP_SERIALIZATION_DEFAULT
                else -> null
            }

        "QueryCookieJarInput" ->
            when (propertyName) {
                "limit" -> JsonPrimitive(100)
                "offset" -> JsonPrimitive(0)
                "order" -> JsonPrimitive("desc")
                "domain_regex" -> JsonNull
                "name_regex" -> JsonNull
                "include_expired" -> JsonPrimitive(false)
                "include_values" -> JsonPrimitive(false)
                "max_value_chars" -> JsonPrimitive(200)
                else -> null
            }

        "HttpSerializationOptionsInput" -> HTTP_SERIALIZATION_DEFAULT[propertyName]

        "WebSocketSerializationOptionsInput" -> WEBSOCKET_SERIALIZATION_DEFAULT[propertyName]

        else -> null
    }

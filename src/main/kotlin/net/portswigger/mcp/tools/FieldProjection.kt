package net.portswigger.mcp.tools

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.portswigger.mcp.history.GetProxyHttpHistoryItemsInput
import net.portswigger.mcp.history.GetSiteMapItemsInput
import net.portswigger.mcp.history.QueryProxyHttpHistoryInput
import net.portswigger.mcp.history.QuerySiteMapInput
import net.portswigger.mcp.history.SerializedHttpHistoryEntry
import net.portswigger.mcp.history.SerializedHttpRequest
import net.portswigger.mcp.history.SerializedHttpResponse
import net.portswigger.mcp.history.SerializedSiteMapEntry
import net.portswigger.mcp.mcp.camelToSnakeCase

internal data class FieldProjection(
    val fields: Set<String>? = null,
    val excludeFields: Set<String>? = null,
)

private val projectionJson =
    kotlinx.serialization.json.Json {
        encodeDefaults = true
        explicitNulls = false
    }

private val fieldPathPattern = Regex("^[a-z0-9_]+(?:\\.[a-z0-9_]+)*$")
internal val HTTP_HISTORY_FIELD_PATHS = collectProjectablePaths(SerializedHttpHistoryEntry.serializer()) + matchContextFieldPaths()
internal val SITE_MAP_FIELD_PATHS = collectProjectablePaths(SerializedSiteMapEntry.serializer()) + matchContextFieldPaths()
internal val ORGANIZER_FIELD_PATHS = collectProjectablePaths(OrganizerItemSummary.serializer()) + matchContextFieldPaths()
internal val SENT_REQUEST_FIELD_PATHS = collectProjectablePaths(SentRequestSummary.serializer()) + matchContextFieldPaths()
internal val SCANNER_ISSUE_FIELD_PATHS =
    collectProjectablePaths(ScannerIssueSummary.serializer()) + scannerRequestResponseFieldPaths()

internal fun QueryProxyHttpHistoryInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        HTTP_HISTORY_FIELD_PATHS,
    )

internal fun GetProxyHttpHistoryItemsInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        HTTP_HISTORY_FIELD_PATHS,
    )

internal fun QuerySiteMapInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        SITE_MAP_FIELD_PATHS,
    )

internal fun GetSiteMapItemsInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        SITE_MAP_FIELD_PATHS,
    )

internal fun QueryOrganizerItemsInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        ORGANIZER_FIELD_PATHS,
    )

internal fun GetOrganizerItemsInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        ORGANIZER_FIELD_PATHS,
    )

internal fun SendHttp1RequestInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        SENT_REQUEST_FIELD_PATHS,
    )

internal fun SendHttp2RequestInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        SENT_REQUEST_FIELD_PATHS,
    )

internal fun QueryScannerIssuesInput.toFieldProjection(): FieldProjection? =
    resolveFieldProjection(
        fields,
        excludeFields,
        SCANNER_ISSUE_FIELD_PATHS,
    )

internal fun applyItemFieldProjection(
    payload: JsonObject,
    projection: FieldProjection?,
): JsonObject {
    val optimized = optimizeDefaultItemShapes(payload, projection)
    if (projection == null) {
        return optimized
    }

    val results = optimized["results"] as? JsonArray ?: return optimized
    val projectedResults =
        JsonArray(
            results.map { element ->
                when (element) {
                    is JsonObject -> {
                        val nestedItem = element["item"]
                        if (nestedItem is JsonObject) {
                            JsonObject(element + ("item" to applyProjection(nestedItem, projection)))
                        } else {
                            applyProjection(element, projection)
                        }
                    }

                    else -> element
                }
            },
        )
    return JsonObject(optimized + ("results" to projectedResults))
}

internal inline fun <reified T> encodeWithItemFieldProjection(
    result: T,
    projection: FieldProjection?,
): String {
    val payload = projectionJson.parseToJsonElement(projectionJson.encodeToString(result))
    if (payload !is JsonObject) {
        return projectionJson.encodeToString(JsonElement.serializer(), payload)
    }
    val projected = applyItemFieldProjection(payload, projection)
    return projectionJson.encodeToString(JsonElement.serializer(), projected)
}

internal inline fun <reified T> encodeWithBulkResultFieldProjection(
    result: T,
    projection: FieldProjection?,
): String {
    val payload = projectionJson.parseToJsonElement(projectionJson.encodeToString(result))
    if (payload !is JsonObject) {
        return projectionJson.encodeToString(JsonElement.serializer(), payload)
    }
    val optimized = optimizeDefaultBulkResultShapes(payload, projection)
    if (projection == null) {
        return projectionJson.encodeToString(JsonElement.serializer(), optimized)
    }
    val results =
        optimized["results"] as? JsonArray
            ?: return projectionJson.encodeToString(JsonElement.serializer(), optimized)
    val projectedResults =
        JsonArray(
            results.map { element ->
                val item = element as? JsonObject ?: return@map element
                val nestedResult = item["result"] as? JsonObject ?: return@map element
                JsonObject(item + ("result" to applyProjection(nestedResult, projection)))
            },
        )
    return projectionJson.encodeToString(JsonElement.serializer(), JsonObject(optimized + ("results" to projectedResults)))
}

private fun optimizeDefaultItemShapes(
    payload: JsonObject,
    projection: FieldProjection?,
): JsonObject {
    if (projection?.fields != null) {
        return payload
    }

    val results = payload["results"] as? JsonArray ?: return payload
    val optimizedResults =
        JsonArray(
            results.map { element ->
                when (element) {
                    is JsonObject -> {
                        val nestedItem = element["item"] as? JsonObject
                        if (nestedItem != null) {
                            JsonObject(element + ("item" to optimizeHttpLikeShape(nestedItem)))
                        } else {
                            optimizeHttpLikeShape(element)
                        }
                    }

                    else -> element
                }
            },
        )
    return JsonObject(payload + ("results" to optimizedResults))
}

private fun optimizeDefaultBulkResultShapes(
    payload: JsonObject,
    projection: FieldProjection?,
): JsonObject {
    if (projection?.fields != null) {
        return payload
    }

    val results = payload["results"] as? JsonArray ?: return payload
    val optimizedResults =
        JsonArray(
            results.map { element ->
                val item = element as? JsonObject ?: return@map element
                val nestedResult = item["result"] as? JsonObject ?: return@map element
                JsonObject(item + ("result" to optimizeHttpLikeShape(nestedResult)))
            },
        )
    return JsonObject(payload + ("results" to optimizedResults))
}

private fun optimizeHttpLikeShape(source: JsonObject): JsonObject {
    val optimized = source.toMutableMap()
    val request = optimized["request"] as? JsonObject
    val response = optimized["response"] as? JsonObject
    val requestResponses = optimized["request_responses"] as? JsonArray

    if (request != null) {
        optimized["request"] = optimizeRequestShape(request)
    }
    if (response != null) {
        optimized["response"] = optimizeResponseShape(response)
    }
    if (requestResponses != null) {
        optimized["request_responses"] =
            JsonArray(
                requestResponses.map { entry ->
                    val requestResponse = entry as? JsonObject ?: return@map entry
                    optimizeHttpLikeShape(requestResponse)
                },
            )
    }

    optimized.remove("listener_port")
    optimized.remove("edited")
    optimized.remove("in_scope")
    return JsonObject(optimized)
}

private fun optimizeRequestShape(source: JsonObject): JsonObject {
    val optimized = source.toMutableMap()
    optimized.remove("path")
    optimized.remove("query")
    optimized.remove("in_scope")
    return JsonObject(optimized)
}

private fun optimizeResponseShape(source: JsonObject): JsonObject {
    val optimized = source.toMutableMap()
    val mimeType = (optimized["mime_type"] as? JsonPrimitive)?.contentOrNull
    val statedMimeType = (optimized["stated_mime_type"] as? JsonPrimitive)?.contentOrNull
    val inferredMimeType = (optimized["inferred_mime_type"] as? JsonPrimitive)?.contentOrNull
    if (mimeType != null && statedMimeType == mimeType && inferredMimeType == mimeType) {
        optimized.remove("stated_mime_type")
        optimized.remove("inferred_mime_type")
    }

    val cookies = optimized["cookies"] as? JsonArray
    if (cookies != null && cookies.isEmpty()) {
        optimized.remove("cookies")
    }

    return JsonObject(optimized)
}

private fun resolveFieldProjection(
    fields: List<String>?,
    excludeFields: List<String>?,
    allowedPaths: Set<String>,
): FieldProjection? {
    val normalizedFields = normalizeFieldPaths(fields, "fields", allowedPaths)
    val normalizedExcludeFields = normalizeFieldPaths(excludeFields, "exclude_fields", allowedPaths)
    require(normalizedFields == null || normalizedExcludeFields == null) {
        "only one of fields or exclude_fields may be non-null"
    }

    return when {
        normalizedFields != null -> FieldProjection(fields = normalizedFields)
        normalizedExcludeFields != null -> FieldProjection(excludeFields = normalizedExcludeFields)
        else -> null
    }
}

private fun normalizeFieldPaths(
    values: List<String>?,
    fieldName: String,
    allowedPaths: Set<String>,
): Set<String>? {
    val normalized =
        values
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: return null

    if (normalized.isEmpty()) {
        return null
    }

    normalized.forEach { path ->
        require(fieldPathPattern.matches(path)) {
            "$fieldName contains invalid path '$path'"
        }
        require(path in allowedPaths) {
            "$fieldName contains unsupported path '$path'"
        }
    }

    return normalized.toSet()
}

private fun applyProjection(
    source: JsonObject,
    projection: FieldProjection,
): JsonObject =
    when {
        projection.fields != null -> includeFields(source, projection.fields)
        projection.excludeFields != null -> excludeFields(source, projection.excludeFields)
        else -> source
    }

private fun includeFields(
    source: JsonObject,
    fields: Set<String>,
): JsonObject {
    val target = linkedMapOf<String, JsonElement>()
    fields.forEach { path ->
        mergeIncludedPath(target, source, path.split('.'))
    }
    return JsonObject(target)
}

private fun mergeIncludedPath(
    target: MutableMap<String, JsonElement>,
    source: JsonObject,
    segments: List<String>,
) {
    val head = segments.first()
    val value = source[head] ?: return

    if (segments.size == 1) {
        target[head] = mergeJsonElements(target[head], value)
        return
    }

    when (value) {
        is JsonObject -> {
            val existing = target[head] as? JsonObject
            val nestedTarget = linkedMapOf<String, JsonElement>()
            if (existing != null) {
                nestedTarget.putAll(existing)
            }
            mergeIncludedPath(nestedTarget, value, segments.drop(1))
            if (nestedTarget.isNotEmpty()) {
                target[head] = JsonObject(nestedTarget)
            }
        }

        is JsonArray -> {
            val projectedArray =
                JsonArray(
                    value.map { element ->
                        when (element) {
                            is JsonObject -> includeFields(element, setOf(segments.drop(1).joinToString(".")))
                            else -> element
                        }
                    },
                )
            target[head] = mergeJsonElements(target[head], projectedArray)
        }

        else -> {
            target[head] = mergeJsonElements(target[head], value)
        }
    }
}

private fun excludeFields(
    source: JsonObject,
    fields: Set<String>,
): JsonObject =
    fields
        .sortedByDescending { it.count { char -> char == '.' } }
        .fold(source) { current, path ->
            removePath(current, path.split('.'))
        }

private fun removePath(
    source: JsonObject,
    segments: List<String>,
): JsonObject {
    val head = segments.first()
    val current = source[head] ?: return source

    if (segments.size == 1) {
        return JsonObject(source - head)
    }

    return when (current) {
        is JsonObject -> JsonObject(source + (head to removePath(current, segments.drop(1))))
        is JsonArray -> {
            val updatedArray =
                JsonArray(
                    current.map { element ->
                        if (element is JsonObject) {
                            removePath(element, segments.drop(1))
                        } else {
                            element
                        }
                    },
                )
            JsonObject(source + (head to updatedArray))
        }

        else -> source
    }
}

private fun mergeJsonElements(
    existing: JsonElement?,
    incoming: JsonElement,
): JsonElement =
    when {
        existing is JsonObject && incoming is JsonObject -> {
            val merged = existing.toMutableMap()
            incoming.forEach { (key, value) ->
                merged[key] = mergeJsonElements(merged[key], value)
            }
            JsonObject(merged)
        }

        else -> incoming
    }

private fun collectProjectablePaths(serializer: KSerializer<*>): Set<String> =
    collectProjectablePaths(serializer.descriptor, prefix = null, visited = mutableSetOf())

private fun collectProjectablePaths(
    descriptor: SerialDescriptor,
    prefix: String?,
    visited: MutableSet<String>,
): Set<String> {
    val serialName = descriptor.serialName
    if (!visited.add(serialName)) {
        return emptySet()
    }

    val paths = linkedSetOf<String>()
    for (index in 0 until descriptor.elementsCount) {
        val childName = descriptor.getElementName(index).camelToSnakeCase()
        val path = if (prefix == null) childName else "$prefix.$childName"
        paths += path

        val childDescriptor = descriptor.getElementDescriptor(index)
        if (childDescriptor.kind == StructureKind.CLASS || childDescriptor.kind == StructureKind.OBJECT) {
            paths += collectProjectablePaths(childDescriptor, path, visited)
        }
    }
    visited.remove(serialName)
    return paths
}

private fun scannerRequestResponseFieldPaths(): Set<String> {
    val requestPaths = collectProjectablePaths(SerializedHttpRequest.serializer())
    val responsePaths = collectProjectablePaths(SerializedHttpResponse.serializer())
    return buildSet {
        add("request_responses")
        add("request_responses.request")
        add("request_responses.response")
        requestPaths.forEach { add("request_responses.request.$it") }
        responsePaths.forEach { add("request_responses.response.$it") }
    }
}

private fun matchContextFieldPaths(): Set<String> =
    setOf(
        "match_context",
        "match_context.excerpts",
        "match_context.excerpts.path",
        "match_context.excerpts.text",
    )

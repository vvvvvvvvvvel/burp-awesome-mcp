package net.portswigger.mcp.history

import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

internal data class CompiledRequestResponseFilter(
    val inScopeOnly: Boolean,
    val methods: Set<String>?,
    val hostPattern: Pattern?,
    val mimeTypes: Set<String>?,
    val inferredMimeTypes: Set<String>?,
    val statusCodes: Set<Int>?,
    val hasResponse: Boolean?,
    val timeFrom: Instant?,
    val timeTo: Instant?,
)

internal data class CompiledProxyHttpHistoryFilter(
    val requestResponse: CompiledRequestResponseFilter,
    val listenerPorts: Set<Int>?,
)

internal data class CompiledWebSocketFilter(
    val inScopeOnly: Boolean,
    val directions: Set<String>?,
    val webSocketIds: Set<Int>?,
    val hostPattern: Pattern?,
    val listenerPorts: Set<Int>?,
    val hasEditedPayload: Boolean?,
    val timeFrom: Instant?,
    val timeTo: Instant?,
)

internal data class FilteredPageSelection<T>(
    val items: List<T>,
    val nextItem: T? = null,
)

internal fun compileRequestResponseFilter(input: HttpRequestResponseFilterInput): CompiledRequestResponseFilter {
    val methods =
        input.methods
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?.ifEmpty { null }
    val hostPattern = input.hostRegex?.takeIf { it.isNotBlank() }?.let { compilePattern(it, "filter.host_regex") }
    val mimeTypes =
        input.mimeTypes
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?.ifEmpty { null }
    val inferredMimeTypes =
        input.inferredMimeTypes
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?.ifEmpty { null }
    val statusCodes =
        input.statusCodes
            ?.toSet()
            ?.ifEmpty { null }
            ?.also { codes ->
                require(codes.all { it in 100..999 }) {
                    "filter.status_codes must contain valid HTTP codes in range 100..999"
                }
            }
    val (timeFrom, timeTo) = parseTimeRange(input.timeFrom, input.timeTo)

    return CompiledRequestResponseFilter(
        inScopeOnly = input.inScopeOnly,
        methods = methods,
        hostPattern = hostPattern,
        mimeTypes = mimeTypes,
        inferredMimeTypes = inferredMimeTypes,
        statusCodes = statusCodes,
        hasResponse = input.hasResponse,
        timeFrom = timeFrom,
        timeTo = timeTo,
    )
}

internal fun compileProxyHttpHistoryFilter(input: ProxyHttpHistoryFilterInput): CompiledProxyHttpHistoryFilter =
    CompiledProxyHttpHistoryFilter(
        requestResponse = compileRequestResponseFilter(input.toRequestResponseFilterInput()),
        listenerPorts = compileListenerPorts(input.listenerPorts),
    )

internal fun compileWebSocketFilter(input: WebSocketHistoryFilterInput): CompiledWebSocketFilter {
    val directions =
        input.direction
            ?.map { it.name }
            ?.toSet()
            ?.ifEmpty { null }
    val webSocketIds =
        input.webSocketIds
            ?.toSet()
            ?.ifEmpty { null }
            ?.also { ids ->
                require(ids.all { it >= 0 }) { "filter.web_socket_ids must contain non-negative values" }
            }
    val hostPattern = input.hostRegex?.takeIf { it.isNotBlank() }?.let { compilePattern(it, "filter.host_regex") }
    val (timeFrom, timeTo) = parseTimeRange(input.timeFrom, input.timeTo)

    return CompiledWebSocketFilter(
        inScopeOnly = input.inScopeOnly,
        directions = directions,
        webSocketIds = webSocketIds,
        hostPattern = hostPattern,
        listenerPorts = compileListenerPorts(input.listenerPorts),
        hasEditedPayload = input.hasEditedPayload,
        timeFrom = timeFrom,
        timeTo = timeTo,
    )
}

private fun compileListenerPorts(listenerPorts: List<Int>?): Set<Int>? =
    listenerPorts
        ?.toSet()
        ?.ifEmpty { null }
        ?.also { ports ->
            require(ports.all { it in 1..65535 }) { "filter.listener_ports must be in range 1..65535" }
        }

internal fun matchesRequestResponseFilter(
    filter: CompiledRequestResponseFilter,
    request: HttpRequest,
    requestHost: String,
    hasResponse: Boolean,
    responseProvider: () -> HttpResponse?,
    sentAtProvider: () -> Instant?,
): Boolean {
    if (filter.methods != null && request.method().uppercase() !in filter.methods) return false
    if (filter.hostPattern != null && !filter.hostPattern.matcher(requestHost).find()) return false
    if (filter.hasResponse != null && hasResponse != filter.hasResponse) return false

    val responseNeeded = filter.statusCodes != null || filter.mimeTypes != null || filter.inferredMimeTypes != null
    val response = if (responseNeeded) responseProvider() else null

    if (filter.statusCodes != null) {
        val statusCode = response?.statusCode()?.toInt() ?: return false
        if (statusCode !in filter.statusCodes) return false
    }

    if (filter.mimeTypes != null) {
        val candidates = response?.let(::responseMimeCandidates) ?: return false
        if (candidates.none { it in filter.mimeTypes }) return false
    }

    if (filter.inferredMimeTypes != null) {
        val inferred = response?.let(::responseInferredMimeCandidate) ?: return false
        if (inferred !in filter.inferredMimeTypes) return false
    }

    if (filter.timeFrom != null || filter.timeTo != null) {
        val sentAt = sentAtProvider() ?: return false
        if (filter.timeFrom != null && sentAt.isBefore(filter.timeFrom)) return false
        if (filter.timeTo != null && sentAt.isAfter(filter.timeTo)) return false
    }

    return true
}

internal fun matchesRequestResponseFilter(
    item: HttpRequestResponse,
    filter: CompiledRequestResponseFilter,
): Boolean {
    val request = item.request()
    val host = runCatching { request.httpService().host() }.getOrDefault("")
    val hasResponse = runCatching { item.hasResponse() }.getOrDefault(false)

    return matchesRequestResponseFilter(
        filter = filter,
        request = request,
        requestHost = host,
        hasResponse = hasResponse,
        responseProvider = {
            runCatching {
                if (hasResponse) item.response() else null
            }.getOrNull()
        },
        sentAtProvider = {
            runCatching {
                item
                    .timingData()
                    .orElse(null)
                    ?.timeRequestSent()
                    ?.toInstant()
            }.getOrNull()
        },
    )
}

internal fun responseMimeCandidates(response: HttpResponse): Set<String> {
    val candidates = linkedSetOf<String>()
    runCatching { response.mimeType().name.uppercase() }.getOrNull()?.let { candidates += it }
    runCatching { response.statedMimeType().name.uppercase() }.getOrNull()?.let { candidates += it }
    runCatching { response.headerValue("Content-Type") }
        .getOrNull()
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase()
        ?.let { contentType ->
            candidates += contentType
            contentType
                .substringAfter('/', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
                ?.let { subtype ->
                    candidates += subtype
                    subtype
                        .substringAfterLast('+', missingDelimiterValue = subtype)
                        .takeIf { it.isNotBlank() }
                        ?.let { candidates += it }
                }
        }

    // Inferred MIME can be noisy. Use it only as a fallback when no explicit MIME signals are available.
    if (candidates.isEmpty()) {
        runCatching { response.inferredMimeType().name.uppercase() }.getOrNull()?.let { candidates += it }
    }

    return candidates
}

internal fun responseInferredMimeCandidate(response: HttpResponse): String? =
    runCatching { response.inferredMimeType().name.uppercase() }.getOrNull()

internal fun compileOptionalPattern(
    regex: String?,
    field: String,
): Pattern? {
    val value = regex?.trim().orEmpty()
    if (value.isEmpty()) return null
    return compilePattern(value, field)
}

private fun compilePattern(
    regex: String,
    field: String = "regex",
): Pattern =
    try {
        Pattern.compile(regex)
    } catch (e: PatternSyntaxException) {
        throw IllegalArgumentException("Invalid $field: ${e.description}")
    }

private fun parseTime(
    raw: String?,
    field: String,
): Instant? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null

    return try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        try {
            ZonedDateTime.parse(value).toInstant()
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Invalid $field: ${e.message ?: "must be ISO-8601 date-time"}")
        }
    }
}

private fun parseTimeRange(
    timeFromRaw: String?,
    timeToRaw: String?,
): Pair<Instant?, Instant?> {
    val timeFrom = parseTime(timeFromRaw, "filter.time_from")
    val timeTo = parseTime(timeToRaw, "filter.time_to")
    if (timeFrom != null && timeTo != null) {
        require(!timeFrom.isAfter(timeTo)) { "filter.time_from must be <= filter.time_to" }
    }
    return timeFrom to timeTo
}

internal inline fun <T> collectFilteredPage(
    items: List<T>,
    startIndex: Int,
    limit: Int,
    idDirection: IdDirection,
    predicate: (T) -> Boolean,
): FilteredPageSelection<T> {
    if (items.isEmpty()) {
        return FilteredPageSelection(items = emptyList())
    }

    if (idDirection == IdDirection.INCREASING && startIndex >= items.size) {
        return FilteredPageSelection(items = emptyList())
    }
    if (idDirection == IdDirection.DECREASING && startIndex < 0) {
        return FilteredPageSelection(items = emptyList())
    }

    val selected = mutableListOf<T>()
    var next: T? = null

    if (idDirection == IdDirection.INCREASING) {
        for (index in startIndex.coerceAtLeast(0) until items.size) {
            val item = items[index]
            if (!predicate(item)) continue

            if (selected.size < limit) {
                selected += item
                continue
            }

            next = item
            break
        }
    } else {
        for (index in startIndex.coerceAtMost(items.lastIndex) downTo 0) {
            val item = items[index]
            if (!predicate(item)) continue

            if (selected.size < limit) {
                selected += item
                continue
            }

            next = item
            break
        }
    }

    return FilteredPageSelection(items = selected, nextItem = next)
}

internal inline fun <T> findStartIndexById(
    items: List<T>,
    startId: Int,
    idDirection: IdDirection,
    idSelector: (T) -> Int,
): Int {
    if (items.isEmpty()) return 0

    if (idDirection == IdDirection.DECREASING) {
        if (startId < 0) {
            return (items.size + startId).coerceIn(0, items.lastIndex)
        }
        if (startId == 0) return items.lastIndex

        var low = 0
        var high = items.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (idSelector(items[mid]) <= startId) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low - 1
    }

    if (startId < 0) {
        return (items.size + startId).coerceAtLeast(0)
    }
    if (startId == 0) return 0

    var low = 0
    var high = items.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (idSelector(items[mid]) >= startId) {
            high = mid
        } else {
            low = mid + 1
        }
    }
    return low
}

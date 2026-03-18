@file:Suppress("UsePropertyAccessSyntax")

package net.portswigger.mcp.history

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.proxy.ProxyWebSocketMessage

class HistoryQueryService(
    private val api: MontoyaApi,
) {
    internal fun queryHttpHistory(
        input: QueryProxyHttpHistoryInput,
        options: HttpSerializationOptions =
            input.serialization.normalized(
                resolveProjectedHttpMaterialization(
                    input.fields?.toSet(),
                    input.excludeFields?.toSet(),
                    regexExcerptEnabled = input.serialization.regexExcerpt != null,
                ),
            ),
        regexExcerpt: RegexExcerptConfig? = null,
    ): QueryHttpHistoryResult {
        val normalizedLimit = input.limit.coerceIn(1, 500)
        val filter = compileProxyHttpHistoryFilter(input.filter)
        val regexControls = resolveListToolRegexControls(input.filter.regex, input.serialization)
        val regexPattern = regexControls.filterPattern
        val effectiveExcerpt = regexControls.excerptConfig ?: regexExcerpt

        val history = fetchHttpHistory(regex = null)
        val startIndex = findStartIndexById(history, input.startId, input.idDirection) { it.id() }
        val selected =
            collectFilteredPage(
                items = history,
                startIndex = startIndex,
                limit = normalizedLimit,
                idDirection = input.idDirection,
            ) { item ->
                if (regexPattern != null && !item.contains(regexPattern)) return@collectFilteredPage false
                (!filter.requestResponse.inScopeOnly || isHttpHistoryItemInScope(item)) &&
                    matchesHttpHistoryFilter(item, filter)
            }

        val hydratedSelection =
            hydratePendingHttpResponses(
                items = selected.items,
            )

        val mapped =
            hydratedSelection.map { item ->
                mapHttpHistoryItem(
                    item,
                    options,
                    matchContext = effectiveExcerpt?.let { buildProxyHttpMatchContext(item, it) },
                )
            }
        val next = selected.nextItem?.let { input.copy(startId = it.id()) }

        return QueryHttpHistoryResult(
            total = history.size,
            next = next,
            results = mapped,
        )
    }

    internal fun getHttpHistoryItems(
        input: GetProxyHttpHistoryItemsInput,
        options: HttpSerializationOptions =
            input.serialization.normalized(
                resolveProjectedHttpMaterialization(
                    input.fields?.toSet(),
                    input.excludeFields?.toSet(),
                    regexExcerptEnabled = input.serialization.regexExcerpt != null,
                ),
            ),
        regexExcerpt: RegexExcerptConfig? = null,
    ): GetHttpHistoryItemsResult {
        val ids = input.ids
        val foundById = fetchHttpHistoryByIds(ids.toSet())

        val results =
            ids.map { id ->
                val item = foundById[id]
                if (item == null) {
                    HttpHistoryLookupItem(id = id, error = "not found")
                } else {
                    HttpHistoryLookupItem(
                        id = id,
                        item =
                            mapHttpHistoryItem(
                                item,
                                options,
                                matchContext = regexExcerpt?.let { buildProxyHttpMatchContext(item, it) },
                            ),
                    )
                }
            }

        return GetHttpHistoryItemsResult(
            requested = ids.size,
            found = results.count { it.item != null },
            results = results,
        )
    }

    fun queryWebSocketHistory(input: QueryProxyWebSocketHistoryInput): QueryWebSocketHistoryResult {
        val normalizedLimit = input.limit.coerceIn(1, 500)
        val options = input.toWebSocketSerializationOptions()
        val filter = compileWebSocketFilter(input.filter)
        val regexPattern = compileOptionalPattern(input.filter.regex, "filter.regex")

        val history = fetchWebSocketHistory()
        val startIndex = findStartIndexById(history, input.startId, input.idDirection) { it.id() }
        val selected =
            collectFilteredPage(
                items = history,
                startIndex = startIndex,
                limit = normalizedLimit,
                idDirection = input.idDirection,
            ) { item ->
                if (regexPattern != null && !item.contains(regexPattern)) return@collectFilteredPage false
                (!filter.inScopeOnly || isWebSocketItemInScope(item)) &&
                    matchesWebSocketFilter(item, filter)
            }

        val mapped = selected.items.map { mapWebSocketHistoryItem(it, options) }
        val next = selected.nextItem?.let { input.copy(startId = it.id()) }

        return QueryWebSocketHistoryResult(
            total = history.size,
            next = next,
            results = mapped,
        )
    }

    internal fun querySiteMap(
        input: QuerySiteMapInput,
        options: HttpSerializationOptions =
            input.serialization.normalized(
                resolveProjectedHttpMaterialization(
                    input.fields?.toSet(),
                    input.excludeFields?.toSet(),
                    regexExcerptEnabled = input.serialization.regexExcerpt != null,
                ),
            ),
        regexExcerpt: RegexExcerptConfig? = resolveRegexExcerptConfig(input.serialization),
    ): QuerySiteMapResult {
        val normalizedLimit = input.limit.coerceIn(1, 500)
        val filter = compileRequestResponseFilter(input.filter)
        val regexControls = resolveListToolRegexControls(input.filter.regex, input.serialization)
        val regexPattern = regexControls.filterPattern
        val effectiveExcerpt = regexControls.excerptConfig ?: regexExcerpt

        val siteMapItems = fetchSiteMap()
        val startIndex = resolveStartAfterKey(siteMapItems, input.startAfterKey)
        val selected =
            collectFilteredPage(
                items = siteMapItems,
                startIndex = startIndex,
                limit = normalizedLimit,
                idDirection = IdDirection.INCREASING,
            ) { item ->
                if (regexPattern != null && !runCatching { item.contains(regexPattern) }.getOrDefault(false)) {
                    return@collectFilteredPage false
                }
                (!filter.inScopeOnly || item.request().isInScope()) &&
                    matchesRequestResponseFilter(item, filter)
            }

        val mapped =
            selected.items.map { item ->
                mapSiteMapItem(
                    item,
                    options,
                    matchContext = effectiveExcerpt?.let { buildSiteMapMatchContext(item, it) },
                )
            }
        val next = selected.nextItem?.let { input.copy(startAfterKey = siteMapStableKey(it)) }
        return QuerySiteMapResult(
            total = siteMapItems.size,
            next = next,
            results = mapped,
        )
    }

    internal fun getSiteMapItems(
        input: GetSiteMapItemsInput,
        options: HttpSerializationOptions =
            input.serialization.normalized(
                resolveProjectedHttpMaterialization(
                    input.fields?.toSet(),
                    input.excludeFields?.toSet(),
                    regexExcerptEnabled = input.serialization.regexExcerpt != null,
                ),
            ),
        regexExcerpt: RegexExcerptConfig? = resolveRegexExcerptConfig(input.serialization),
    ): GetSiteMapItemsResult {
        val keys = input.keys
        val siteMapItems = fetchSiteMap()
        val foundByKey = indexByRequestedKeys(keys, siteMapItems) { siteMapStableKey(it) }

        val results =
            keys.map { key ->
                val item = foundByKey[key]
                if (item == null) {
                    SiteMapLookupItem(key = key, error = "not found")
                } else {
                    SiteMapLookupItem(
                        key = key,
                        item =
                            mapSiteMapItem(
                                item,
                                options,
                                matchContext = regexExcerpt?.let { buildSiteMapMatchContext(item, it) },
                            ),
                    )
                }
            }

        return GetSiteMapItemsResult(
            requested = keys.size,
            found = results.count { it.item != null },
            results = results,
        )
    }

    fun getWebSocketMessages(input: GetProxyWebSocketMessagesInput): GetWebSocketMessagesResult {
        val ids = input.ids
        val options = input.toWebSocketSerializationOptions()
        val foundById = fetchWebSocketHistoryByIds(ids.toSet())

        val results =
            ids.map { id ->
                val item = foundById[id]
                if (item == null) {
                    WebSocketLookupItem(id = id, error = "not found")
                } else {
                    WebSocketLookupItem(id = id, item = mapWebSocketHistoryItem(item, options))
                }
            }

        return GetWebSocketMessagesResult(
            requested = ids.size,
            found = results.count { it.item != null },
            results = results,
        )
    }

    fun extractCookies(input: ExtractCookiesFromHistoryInput): ExtractCookiesFromHistoryResult {
        val entries =
            selectPage(
                items = fetchHttpHistory(regex = input.regex),
                order = input.order,
                offset = input.offset.coerceAtLeast(0),
                limit = input.limit.coerceIn(1, 500),
                predicate = { item -> !input.inScopeOnly || isHttpHistoryItemInScope(item) },
            ).items

        data class Agg(
            val source: String,
            val name: String,
            var preview: String,
            var count: Int,
            var firstSeen: Int,
            var lastSeen: Int,
        )

        val byKey = linkedMapOf<String, Agg>()

        fun observe(
            source: String,
            name: String,
            value: String,
            historyId: Int,
        ) {
            val key = "$source::$name"
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] =
                    Agg(
                        source = source,
                        name = name,
                        preview = previewValue(value),
                        count = 1,
                        firstSeen = historyId,
                        lastSeen = historyId,
                    )
                return
            }
            existing.count += 1
            existing.lastSeen = maxOf(existing.lastSeen, historyId)
            existing.firstSeen = minOf(existing.firstSeen, historyId)
        }

        for (entry in entries) {
            val request = runCatching { entry.finalRequest() }.getOrElse { entry.request() }
            val requestCookies =
                request
                    .headers()
                    .filter { it.name().equals("Cookie", ignoreCase = true) }
                    .flatMap { parseCookieHeader(it.value()) }

            requestCookies.forEach { (name, value) ->
                observe(source = "request_cookie", name = name, value = value, historyId = entry.id())
            }

            val response =
                runCatching {
                    if (entry.hasResponse()) entry.response() else null
                }.getOrNull()

            response?.cookies()?.forEach { cookie ->
                observe(
                    source = "response_set_cookie",
                    name = cookie.name(),
                    value = cookie.value(),
                    historyId = entry.id(),
                )
            }
        }

        val observations =
            byKey.values
                .sortedWith(compareByDescending<Agg> { it.count }.thenBy { it.name })
                .map {
                    CookieObservation(
                        source = it.source,
                        name = it.name,
                        valuePreview = it.preview,
                        count = it.count,
                        firstSeenHistoryId = it.firstSeen,
                        lastSeenHistoryId = it.lastSeen,
                    )
                }

        return ExtractCookiesFromHistoryResult(
            totalEntriesScanned = entries.size,
            uniqueCookies = observations.size,
            observations = observations,
        )
    }

    fun extractAuthHeaders(input: ExtractAuthHeadersFromHistoryInput): ExtractAuthHeadersFromHistoryResult {
        val entries =
            selectPage(
                items = fetchHttpHistory(regex = input.regex),
                order = input.order,
                offset = input.offset.coerceAtLeast(0),
                limit = input.limit.coerceIn(1, 500),
                predicate = { item -> !input.inScopeOnly || isHttpHistoryItemInScope(item) },
            ).items

        data class Agg(
            val header: String,
            var preview: String,
            var count: Int,
            var firstSeen: Int,
            var lastSeen: Int,
        )

        val byHeader = linkedMapOf<String, Agg>()

        fun observe(
            header: String,
            value: String,
            historyId: Int,
        ) {
            val key = header.lowercase()
            val existing = byHeader[key]
            if (existing == null) {
                byHeader[key] =
                    Agg(
                        header = header,
                        preview = previewValue(value),
                        count = 1,
                        firstSeen = historyId,
                        lastSeen = historyId,
                    )
                return
            }
            existing.count += 1
            existing.firstSeen = minOf(existing.firstSeen, historyId)
            existing.lastSeen = maxOf(existing.lastSeen, historyId)
        }

        for (entry in entries) {
            val request = runCatching { entry.finalRequest() }.getOrElse { entry.request() }
            request.headers().forEach { header ->
                val headerNameLower = header.name().lowercase()
                if (
                    headerNameLower == "authorization" ||
                    headerNameLower == "proxy-authorization" ||
                    headerNameLower == "x-api-key" ||
                    headerNameLower == "api-key" ||
                    headerNameLower.contains("auth")
                ) {
                    observe(header.name(), header.value(), entry.id())
                }
            }
        }

        val observations =
            byHeader.values
                .sortedWith(compareByDescending<Agg> { it.count }.thenBy { it.header })
                .map {
                    AuthHeaderObservation(
                        header = it.header,
                        valuePreview = it.preview,
                        count = it.count,
                        firstSeenHistoryId = it.firstSeen,
                        lastSeenHistoryId = it.lastSeen,
                    )
                }

        return ExtractAuthHeadersFromHistoryResult(
            totalEntriesScanned = entries.size,
            uniqueHeaders = observations.size,
            observations = observations,
        )
    }

    private fun fetchHttpHistory(regex: String?): List<ProxyHttpRequestResponse> =
        if (regex.isNullOrBlank()) {
            api.proxy().history()
        } else {
            val pattern = compileOptionalPattern(regex, "regex") ?: return api.proxy().history()
            api.proxy().history { item -> item.contains(pattern) }
        }

    private fun fetchHttpHistoryByIds(ids: Set<Int>): Map<Int, ProxyHttpRequestResponse> {
        if (ids.isEmpty()) return emptyMap()
        return api.proxy().history { item -> item.id() in ids }.associateBy { it.id() }
    }

    private fun fetchWebSocketHistory(): List<ProxyWebSocketMessage> = api.proxy().webSocketHistory()

    private fun fetchWebSocketHistoryByIds(ids: Set<Int>): Map<Int, ProxyWebSocketMessage> {
        if (ids.isEmpty()) return emptyMap()
        return api.proxy().webSocketHistory { item -> item.id() in ids }.associateBy { it.id() }
    }

    private fun fetchSiteMap(): List<HttpRequestResponse> = api.siteMap().requestResponses()

    private fun matchesHttpHistoryFilter(
        item: ProxyHttpRequestResponse,
        filter: CompiledProxyHttpHistoryFilter,
    ): Boolean {
        if (filter.listenerPorts != null && item.listenerPort() !in filter.listenerPorts) return false

        val request = runCatching { item.finalRequest() }.getOrElse { item.request() }
        val host = runCatching { request.httpService().host() }.getOrDefault("")

        val hasResponse = runCatching { item.hasResponse() }.getOrDefault(false)
        return matchesRequestResponseFilter(
            filter = filter.requestResponse,
            request = request,
            requestHost = host,
            hasResponse = hasResponse,
            responseProvider = {
                runCatching {
                    if (hasResponse) item.response() else null
                }.getOrNull()
            },
            sentAtProvider = { runCatching { item.time().toInstant() }.getOrNull() },
        )
    }

    private fun matchesWebSocketFilter(
        item: ProxyWebSocketMessage,
        filter: CompiledWebSocketFilter,
    ): Boolean {
        if (filter.directions != null && item.direction().name !in filter.directions) return false
        if (filter.webSocketIds != null && item.webSocketId() !in filter.webSocketIds) return false
        if (filter.listenerPorts != null && item.listenerPort() !in filter.listenerPorts) return false
        if (filter.hostPattern != null) {
            val host = runCatching { item.upgradeRequest().httpService().host() }.getOrDefault("")
            if (!filter.hostPattern.matcher(host).find()) return false
        }
        if (filter.hasEditedPayload != null) {
            val hasEdited = runCatching { item.editedPayload().length() > 0 }.getOrDefault(false)
            if (hasEdited != filter.hasEditedPayload) return false
        }
        if (filter.timeFrom != null || filter.timeTo != null) {
            val sentAt = runCatching { item.time().toInstant() }.getOrNull() ?: return false
            if (filter.timeFrom != null && sentAt.isBefore(filter.timeFrom)) return false
            if (filter.timeTo != null && sentAt.isAfter(filter.timeTo)) return false
        }

        return true
    }

    private fun hydratePendingHttpResponses(items: List<ProxyHttpRequestResponse>): List<ProxyHttpRequestResponse> {
        if (items.isEmpty()) return items

        val pendingIds =
            items
                .filter { item -> runCatching { !item.hasResponse() }.getOrDefault(false) }
                .map { it.id() }
                .toSet()
        if (pendingIds.isEmpty()) return items

        val refreshedById = fetchHttpHistoryByIds(pendingIds)
        if (refreshedById.isEmpty()) return items

        return items.map { item -> refreshedById[item.id()] ?: item }
    }
}

private data class PageSelection<T>(
    val total: Int,
    val items: List<T>,
)

private inline fun <K, T> indexByRequestedKeys(
    requestedKeys: List<K>,
    items: List<T>,
    keySelector: (T) -> K,
): Map<K, T> {
    if (requestedKeys.isEmpty()) return emptyMap()
    val requiredKeys = requestedKeys.toSet()
    val foundByKey = mutableMapOf<K, T>()

    for (item in items) {
        val key = keySelector(item)
        if (key !in requiredKeys) continue
        foundByKey.putIfAbsent(key, item)
        if (foundByKey.size == requiredKeys.size) break
    }

    return foundByKey
}

private inline fun <T> selectPage(
    items: List<T>,
    order: SortOrder,
    offset: Int,
    limit: Int,
    predicate: (T) -> Boolean,
): PageSelection<T> {
    val selected = mutableListOf<T>()
    var matched = 0

    val indices =
        when (order) {
            SortOrder.ASC -> items.indices
            SortOrder.DESC -> items.indices.reversed()
        }

    for (index in indices) {
        val item = items[index]
        if (!predicate(item)) continue

        if (matched >= offset && selected.size < limit) {
            selected += item
        }

        matched += 1
    }

    return PageSelection(total = matched, items = selected)
}

private fun resolveStartAfterKey(
    items: List<HttpRequestResponse>,
    startAfterKey: String?,
): Int {
    val normalizedKey = startAfterKey?.trim()
    if (normalizedKey.isNullOrEmpty()) {
        return 0
    }

    for ((index, item) in items.withIndex()) {
        if (siteMapStableKey(item) == normalizedKey) {
            return index + 1
        }
    }

    throw IllegalArgumentException("start_after_key not found: $normalizedKey")
}

private fun parseCookieHeader(headerValue: String): List<Pair<String, String>> {
    if (headerValue.isBlank()) return emptyList()

    return headerValue
        .split(";")
        .mapNotNull { segment ->
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val parts = trimmed.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val name = parts[0].trim()
            val value = parts[1].trim()
            if (name.isEmpty()) return@mapNotNull null
            name to value
        }
}

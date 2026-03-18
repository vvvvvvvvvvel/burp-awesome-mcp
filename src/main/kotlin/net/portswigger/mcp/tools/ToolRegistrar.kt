@file:Suppress("UsePropertyAccessSyntax")

package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.core.Range
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.RedirectionMode
import burp.api.montoya.http.RequestOptions
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.intruder.HttpRequestTemplate
import burp.api.montoya.intruder.HttpRequestTemplateGenerationOptions
import burp.api.montoya.organizer.OrganizerItem
import burp.api.montoya.scanner.audit.issues.AuditIssue
import burp.api.montoya.sitemap.SiteMapFilter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.history.ExtractAuthHeadersFromHistoryInput
import net.portswigger.mcp.history.ExtractCookiesFromHistoryInput
import net.portswigger.mcp.history.GetProxyHttpHistoryItemsInput
import net.portswigger.mcp.history.GetProxyWebSocketMessagesInput
import net.portswigger.mcp.history.GetSiteMapItemsInput
import net.portswigger.mcp.history.HistoryQueryService
import net.portswigger.mcp.history.ProjectedHttpSerializationOptionsInput
import net.portswigger.mcp.history.QueryProxyHttpHistoryInput
import net.portswigger.mcp.history.QueryProxyWebSocketHistoryInput
import net.portswigger.mcp.history.QuerySiteMapInput
import net.portswigger.mcp.history.buildHttpMatchContext
import net.portswigger.mcp.history.collectFilteredPage
import net.portswigger.mcp.history.compileOptionalPattern
import net.portswigger.mcp.history.compileRequestResponseFilter
import net.portswigger.mcp.history.findStartIndexById
import net.portswigger.mcp.history.isLikelyBinaryPayload
import net.portswigger.mcp.history.matchesRequestResponseFilter
import net.portswigger.mcp.history.normalized
import net.portswigger.mcp.history.resolveListToolRegexControls
import net.portswigger.mcp.history.resolveProjectedHttpMaterialization
import net.portswigger.mcp.history.resolveRegexExcerptConfig
import net.portswigger.mcp.history.serializeHttpRequest
import net.portswigger.mcp.history.serializeHttpResponse
import net.portswigger.mcp.history.validateRegexExcerptProjection
import net.portswigger.mcp.mcp.registerNoInputTool
import net.portswigger.mcp.mcp.registerTool
import java.awt.KeyboardFocusManager
import java.net.URI
import java.util.regex.Pattern
import javax.swing.text.JTextComponent

@OptIn(ExperimentalSerializationApi::class)
private val json =
    Json {
        encodeDefaults = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

private val plainJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

fun Server.registerBurpTools(api: MontoyaApi) {
    val history = HistoryQueryService(api)
    val cookieJar = CookieJarService(api)
    val collaborator = CollaboratorSessionService(api)
    val scannerTasks = ScannerTaskService(api)

    registerTool<QueryProxyHttpHistoryInput>(
        name = "list_proxy_http_history",
        description =
            "List Proxy HTTP history with inclusive start_id cursor pagination, id_direction traversal, " +
                "structured filter, serialization output controls, and optional item field projection.",
    ) {
        val projection = toFieldProjection()
        val regexControls = resolveListToolRegexControls(filter.regex, serialization)
        validateRegexExcerptProjection(regexControls.excerptConfig, projection)
        val serializationOptions =
            resolveProjectedSerializationOptions(
                serialization,
                projection,
                regexExcerptEnabled = regexControls.excerptConfig != null,
            )
        encodeQueryResultWithNext(
            history.queryHttpHistory(this, serializationOptions, regexControls.excerptConfig),
            projection,
        )
    }

    registerTool<GetProxyHttpHistoryItemsInput>(
        name = "get_proxy_http_history_by_ids",
        description = "Fetch exact Proxy HTTP history entries by Burp IDs with optional item field projection.",
    ) {
        require(ids.isNotEmpty()) { "ids must not be empty" }
        val projection = toFieldProjection()
        val regexExcerpt = resolveRegexExcerptConfig(serialization)
        validateRegexExcerptProjection(regexExcerpt, projection)
        val serializationOptions =
            resolveProjectedSerializationOptions(
                serialization,
                projection,
                regexExcerptEnabled = regexExcerpt != null,
            )
        encodeWithItemFieldProjection(history.getHttpHistoryItems(this, serializationOptions, regexExcerpt), projection)
    }

    registerTool<QueryProxyWebSocketHistoryInput>(
        name = "list_proxy_websocket_history",
        description =
            "List Proxy WebSocket history with inclusive start_id cursor pagination, id_direction traversal, " +
                "structured filter, and serialization output controls.",
    ) {
        encodeQueryResultWithNext(history.queryWebSocketHistory(this))
    }

    registerTool<GetProxyWebSocketMessagesInput>(
        name = "get_proxy_websocket_messages_by_ids",
        description = "Fetch exact Proxy WebSocket messages by Burp IDs.",
    ) {
        require(ids.isNotEmpty()) { "ids must not be empty" }
        json.encodeToString(history.getWebSocketMessages(this))
    }

    registerTool<QuerySiteMapInput>(
        name = "list_site_map",
        description =
            "List Site Map entries using start_after_key cursor pagination, structured filter, " +
                "request/response serialization output controls, and optional item field projection.",
    ) {
        val projection = toFieldProjection()
        val regexControls = resolveListToolRegexControls(filter.regex, serialization)
        validateRegexExcerptProjection(regexControls.excerptConfig, projection)
        val serializationOptions =
            resolveProjectedSerializationOptions(
                serialization,
                projection,
                regexExcerptEnabled = regexControls.excerptConfig != null,
            )
        encodeQueryResultWithNext(history.querySiteMap(this, serializationOptions, regexControls.excerptConfig), projection)
    }

    registerTool<GetSiteMapItemsInput>(
        name = "get_site_map_by_keys",
        description =
            "Fetch exact Site Map entries by keys returned in list_site_map.results[].key with optional item field projection.",
    ) {
        require(keys.isNotEmpty()) { "keys must not be empty" }
        val projection = toFieldProjection()
        val regexExcerpt = resolveRegexExcerptConfig(serialization)
        validateRegexExcerptProjection(regexExcerpt, projection)
        val serializationOptions =
            resolveProjectedSerializationOptions(
                serialization,
                projection,
                regexExcerptEnabled = regexExcerpt != null,
            )
        encodeWithItemFieldProjection(history.getSiteMapItems(this, serializationOptions, regexExcerpt), projection)
    }

    registerTool<ExtractCookiesFromHistoryInput>(
        name = "summarize_http_history_cookies",
        description = "Aggregate Cookie and Set-Cookie observations from HTTP history for session analysis.",
    ) {
        json.encodeToString(history.extractCookies(this))
    }

    registerTool<ExtractAuthHeadersFromHistoryInput>(
        name = "summarize_http_history_auth_headers",
        description = "Aggregate likely authentication headers (Authorization, API keys, etc.) from HTTP history.",
    ) {
        json.encodeToString(history.extractAuthHeaders(this))
    }

    registerTool<SendHttp1RequestInput>(
        name = "send_http1_requests",
        description =
            "Send one or many HTTP/1.1 requests with optional parallel execution, request options, " +
                "response serialization controls, and optional result field projection.",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }
        val projection = toFieldProjection()
        val regexExcerpt = resolveRegexExcerptConfig(serialization)
        validateRegexExcerptProjection(regexExcerpt, projection)
        val serializationOptions =
            resolveProjectedSerializationOptions(
                serialization,
                projection,
                regexExcerptEnabled = regexExcerpt != null,
            )

        val results =
            runBlocking {
                executeBulk(items, parallel, parallelRps) { item ->
                    try {
                        val request =
                            HttpRequest.httpRequest(
                                HttpService.httpService(item.targetHostname, item.targetPort, item.usesHttps),
                                item.content,
                            )
                        val response = sendRequestWithOptions(api, request, requestOptions)
                        sentRequestResult(request, response?.response(), serializationOptions, regexExcerpt)
                    } catch (e: Exception) {
                        BulkToolItemResult(ok = false, error = e.message ?: "request failed")
                    }
                }
            }

        encodeWithBulkResultFieldProjection(BulkToolResponse(results), projection)
    }

    registerTool<SendHttp2RequestInput>(
        name = "send_http2_requests",
        description =
            "Send one or many HTTP/2 requests with optional parallel execution, request options, " +
                "response serialization controls, and optional result field projection. " +
                "request_options.http_mode must be http_2 or http_2_ignore_alpn (or omitted). " +
                "Use headers_list to preserve duplicate header names.",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }
        val projection = toFieldProjection()
        val regexExcerpt = resolveRegexExcerptConfig(serialization)
        validateRegexExcerptProjection(regexExcerpt, projection)
        val serializationOptions =
            resolveProjectedSerializationOptions(
                serialization,
                projection,
                regexExcerptEnabled = regexExcerpt != null,
            )

        val results =
            runBlocking {
                executeBulk(items, parallel, parallelRps) { item ->
                    try {
                        // Validate/normalize request options before constructing Montoya HTTP/2 objects.
                        val effectiveOptions = mergeHttp2Mode(requestOptions)

                        val pseudoHeaderOrder = listOf(":scheme", ":method", ":path", ":authority")
                        val normalizedPseudoHeaders = linkedMapOf<String, String>()

                        for (name in pseudoHeaderOrder) {
                            val keyWithoutColon = name.removePrefix(":")
                            val value = item.pseudoHeaders[name] ?: item.pseudoHeaders[keyWithoutColon]
                            if (value != null) normalizedPseudoHeaders[name] = value
                        }

                        item.pseudoHeaders.forEach { (name, value) ->
                            val normalizedName = if (name.startsWith(":")) name else ":$name"
                            normalizedPseudoHeaders.putIfAbsent(normalizedName, value)
                        }

                        val normalizedHeaders = mutableListOf<Pair<String, String>>()
                        item.headers.forEach { (name, value) -> normalizedHeaders += name to value }
                        item.headersList.orEmpty().forEach { entry ->
                            val headerName = entry.name.trim()
                            require(headerName.isNotEmpty()) { "headers_list.name must not be blank" }
                            normalizedHeaders += headerName to entry.value
                        }

                        val headerList =
                            normalizedPseudoHeaders
                                .map { (name, value) -> HttpHeader.httpHeader(name.lowercase(), value) } +
                                normalizedHeaders.map { (name, value) -> HttpHeader.httpHeader(name.lowercase(), value) }

                        val request =
                            HttpRequest.http2Request(
                                HttpService.httpService(item.targetHostname, item.targetPort, item.usesHttps),
                                headerList,
                                item.requestBody,
                            )

                        val response = sendRequestWithOptions(api, request, effectiveOptions)
                        sentRequestResult(request, response?.response(), serializationOptions, regexExcerpt)
                    } catch (e: Exception) {
                        BulkToolItemResult(ok = false, error = e.message ?: "request failed")
                    }
                }
            }

        encodeWithBulkResultFieldProjection(BulkToolResponse(results), projection)
    }

    registerTool<CreateRepeaterTabInput>(
        name = "create_repeater_tabs",
        description = "Create Repeater tabs from raw HTTP requests (bulk).",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }

        val results =
            items.map { item ->
                try {
                    val request =
                        HttpRequest.httpRequest(
                            HttpService.httpService(item.targetHostname, item.targetPort, item.usesHttps),
                            item.content,
                        )
                    api.repeater().sendToRepeater(request, item.tabName)
                    BulkToolItemResult(ok = true, result = JsonPrimitive("sent"))
                } catch (e: Exception) {
                    BulkToolItemResult(ok = false, error = e.message ?: "failed")
                }
            }

        json.encodeToString(BulkToolResponse(results))
    }

    registerTool<SendToIntruderInput>(
        name = "send_requests_to_intruder",
        description = "Send requests to Intruder (bulk, without explicit insertion ranges).",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }

        val results =
            items.map { item ->
                try {
                    val request =
                        HttpRequest.httpRequest(
                            HttpService.httpService(item.targetHostname, item.targetPort, item.usesHttps),
                            item.content,
                        )
                    api.intruder().sendToIntruder(request, item.tabName)
                    BulkToolItemResult(ok = true, result = JsonPrimitive("sent"))
                } catch (e: Exception) {
                    BulkToolItemResult(ok = false, error = e.message ?: "failed")
                }
            }

        json.encodeToString(BulkToolResponse(results))
    }

    registerTool<SendToIntruderTemplateInput>(
        name = "send_requests_to_intruder_template",
        description = "Send requests to Intruder with explicit insertion ranges or generated insertion points (bulk).",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }

        val results =
            items.map { item ->
                try {
                    val service = HttpService.httpService(item.targetHostname, item.targetPort, item.usesHttps)
                    val request = HttpRequest.httpRequest(service, item.content)
                    val template = buildIntruderTemplate(request, item.insertionPoints, item.generationMode)

                    if (item.tabName.isNullOrBlank()) {
                        api.intruder().sendToIntruder(service, template)
                    } else {
                        api.intruder().sendToIntruder(service, template, item.tabName)
                    }

                    BulkToolItemResult(ok = true, result = JsonPrimitive("sent"))
                } catch (e: Exception) {
                    BulkToolItemResult(ok = false, error = e.message ?: "failed")
                }
            }

        json.encodeToString(BulkToolResponse(results))
    }

    registerTool<SendToOrganizerInput>(
        name = "send_requests_to_organizer",
        description = "Send HTTP requests to Burp Organizer (bulk).",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }

        val results =
            items.map { item ->
                try {
                    val request =
                        HttpRequest.httpRequest(
                            HttpService.httpService(item.targetHostname, item.targetPort, item.usesHttps),
                            item.content,
                        )
                    api.organizer().sendToOrganizer(request)
                    BulkToolItemResult(ok = true, result = JsonPrimitive("sent"))
                } catch (e: Exception) {
                    BulkToolItemResult(ok = false, error = e.message ?: "failed")
                }
            }

        json.encodeToString(BulkToolResponse(results))
    }

    registerTool<QueryOrganizerItemsInput>(
        name = "list_organizer_items",
        description =
            "List Organizer items with inclusive start_id cursor pagination, id_direction traversal, optional status filters, " +
                "request/response serialization output controls, and optional item field projection.",
    ) {
        val projection = toFieldProjection()
        val normalizedLimit = limit.coerceIn(1, 500)
        val regexControls = resolveListToolRegexControls(filter.regex, serialization)
        validateRegexExcerptProjection(regexControls.excerptConfig, projection)
        val options =
            resolveProjectedSerializationOptions(
                serialization,
                projection,
                regexExcerptEnabled = regexControls.excerptConfig != null,
            )

        val regexPattern = regexControls.filterPattern
        val statusSet = status?.map { it.name }?.toSet()
        val rrFilter = compileRequestResponseFilter(filter)

        val sourceItems = api.organizer().items()

        val startIndex = findStartIndexById(sourceItems, startId, idDirection) { it.id() }
        val selected =
            collectFilteredPage(
                items = sourceItems,
                startIndex = startIndex,
                limit = normalizedLimit,
                idDirection = idDirection,
            ) { item ->
                if (regexPattern != null && !item.contains(regexPattern)) return@collectFilteredPage false
                if (filter.inScopeOnly && !item.request().isInScope()) return@collectFilteredPage false
                if (statusSet != null && item.status().name !in statusSet) return@collectFilteredPage false
                matchesRequestResponseFilter(item, rrFilter)
            }

        val mapped = selected.items.map { organizerItemToSummary(it, options, regexControls.excerptConfig) }
        val next = selected.nextItem?.let { copy(startId = it.id()) }
        encodeQueryResultWithNext(
            QueryOrganizerItemsResult(
                total = sourceItems.size,
                next = next,
                results = mapped,
            ),
            projection,
        )
    }

    registerTool<GetOrganizerItemsInput>(
        name = "get_organizer_items_by_ids",
        description = "Fetch exact Organizer entries by Organizer IDs with optional item field projection.",
    ) {
        require(ids.isNotEmpty()) { "ids must not be empty" }
        val projection = toFieldProjection()
        val options = resolveProjectedSerializationOptions(serialization, projection)
        val byId = api.organizer().items().associateBy { it.id() }
        val results =
            ids.map { id ->
                val item = byId[id]
                if (item == null) {
                    OrganizerLookupItem(id = id, error = "not found")
                } else {
                    OrganizerLookupItem(id = id, item = organizerItemToSummary(item, options))
                }
            }
        encodeWithItemFieldProjection(
            GetOrganizerItemsResult(
                requested = ids.size,
                found = results.count { it.item != null },
                results = results,
            ),
            projection,
        )
    }

    registerTool<ScopeIncludeUrlInput>(
        name = "scope_add_include",
        description =
            "Add URL/prefix to Burp include-scope rules. Accepts absolute URL, host, host:port, or short prefix. " +
                "Set include_subdomains=true to add a host-style scope rule.",
    ) {
        encodeScopeUpdateResult(api, listName = "include", url = url, includeSubdomains = includeSubdomains)
    }

    registerTool<ScopeExcludeUrlInput>(
        name = "scope_add_exclude",
        description =
            "Add URL/prefix to Burp exclude-scope rules. Accepts absolute URL, host, host:port, or short prefix. " +
                "Set include_subdomains=true to add a host-style scope rule.",
    ) {
        encodeScopeUpdateResult(api, listName = "exclude", url = url, includeSubdomains = includeSubdomains)
    }

    registerTool<ScopeRemoveIncludeUrlInput>(
        name = "scope_remove_include",
        description =
            "Remove matching rules from target.scope.include by URL/host prefix. " +
                "Set include_subdomains to remove only one variant when both exist for same prefix.",
    ) {
        val target = parseScopeTarget(url)
        val updated = removeScopeRules(api, "include", target, includeSubdomains)
        json.encodeToString(
            ScopeUrlResult(
                url = target.displayUrl,
                inScope = isTargetInScope(api, target),
                scopeRuleUpdated = updated,
            ),
        )
    }

    registerTool<ScopeRemoveExcludeUrlInput>(
        name = "scope_remove_exclude",
        description =
            "Remove matching rules from target.scope.exclude by URL/host prefix. " +
                "Set include_subdomains to remove only one variant when both exist for same prefix.",
    ) {
        val target = parseScopeTarget(url)
        val updated = removeScopeRules(api, "exclude", target, includeSubdomains)
        json.encodeToString(
            ScopeUrlResult(
                url = target.displayUrl,
                inScope = isTargetInScope(api, target),
                scopeRuleUpdated = updated,
            ),
        )
    }

    registerTool<ScopeCheckUrlInput>(
        name = "scope_is_url_in_scope",
        description = "Check whether URL/host/prefix is currently in Burp scope.",
    ) {
        val target = parseScopeTarget(url)
        json.encodeToString(ScopeUrlResult(url = target.displayUrl, inScope = isTargetInScope(api, target)))
    }

    registerTool<UrlCodecInput>(
        name = "url_encode",
        description = "URL-encode strings in bulk mode.",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }

        val results =
            items.map { item ->
                try {
                    BulkToolItemResult(ok = true, result = JsonPrimitive(api.utilities().urlUtils().encode(item.content)))
                } catch (e: Exception) {
                    BulkToolItemResult(ok = false, error = e.message ?: "failed")
                }
            }
        json.encodeToString(BulkToolResponse(results))
    }

    registerTool<UrlCodecInput>(
        name = "url_decode",
        description = "URL-decode strings in bulk mode.",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }

        val results =
            items.map { item ->
                try {
                    BulkToolItemResult(ok = true, result = JsonPrimitive(api.utilities().urlUtils().decode(item.content)))
                } catch (e: Exception) {
                    BulkToolItemResult(ok = false, error = e.message ?: "failed")
                }
            }
        json.encodeToString(BulkToolResponse(results))
    }

    registerTool<Base64CodecInput>(
        name = "base64_encode",
        description = "Base64-encode strings in bulk mode.",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }

        val results =
            items.map { item ->
                try {
                    BulkToolItemResult(ok = true, result = JsonPrimitive(api.utilities().base64Utils().encodeToString(item.content)))
                } catch (e: Exception) {
                    BulkToolItemResult(ok = false, error = e.message ?: "failed")
                }
            }
        json.encodeToString(BulkToolResponse(results))
    }

    registerTool<Base64CodecInput>(
        name = "base64_decode",
        description = "Base64-decode strings in bulk mode. Returns text when UTF-8-like, otherwise base64 wrapper.",
    ) {
        require(items.isNotEmpty()) { "items must not be empty" }

        val results =
            items.map { item ->
                try {
                    val bytes =
                        api
                            .utilities()
                            .base64Utils()
                            .decode(item.content)
                            .bytes
                    val decoded =
                        if (isLikelyBinaryPayload(bytes)) {
                            Base64DecodeResult(
                                encoding = "base64",
                                size = bytes.size,
                                base64 =
                                    java.util.Base64
                                        .getEncoder()
                                        .encodeToString(bytes),
                            )
                        } else {
                            Base64DecodeResult(
                                encoding = "text",
                                size = bytes.size,
                                text = bytes.toString(Charsets.UTF_8),
                            )
                        }
                    BulkToolItemResult(ok = true, result = json.parseToJsonElement(json.encodeToString(decoded)))
                } catch (e: Exception) {
                    BulkToolItemResult(ok = false, error = e.message ?: "failed")
                }
            }
        json.encodeToString(BulkToolResponse(results))
    }

    registerTool<GenerateRandomStringInput>(
        name = "generate_random_string",
        description = "Generate a random string with the provided length and character set.",
    ) {
        json.encodeToString(api.utilities().randomUtils().randomString(length, characterSet))
    }

    registerNoInputTool(
        name = "get_project_options_json",
        description = "Export Burp project options as JSON.",
    ) {
        api.burpSuite().exportProjectOptionsAsJson()
    }

    registerNoInputTool(
        name = "get_user_options_json",
        description = "Export Burp user options as JSON.",
    ) {
        api.burpSuite().exportUserOptionsAsJson()
    }

    registerNoInputTool(
        name = "list_proxy_request_listeners",
        description = "Return configured Proxy request listeners from project options without exporting full options JSON.",
    ) {
        val root = parseProjectOptionsRoot(api)
        val listeners = root["proxy"]?.jsonObject?.get("request_listeners")?.jsonArray ?: JsonArray(emptyList())
        plainJson.encodeToString(
            JsonObject(
                mapOf(
                    "count" to JsonPrimitive(listeners.size),
                    "listeners" to listeners,
                ),
            ),
        )
    }

    registerNoInputTool(
        name = "get_project_scope_rules",
        description = "Return target scope include/exclude rules from project options.",
    ) {
        val root = parseProjectOptionsRoot(api)
        val scope = root["target"]?.jsonObject?.get("scope")?.jsonObject
        val include = scope?.get("include")?.jsonArray ?: JsonArray(emptyList())
        val exclude = scope?.get("exclude")?.jsonArray ?: JsonArray(emptyList())
        val advancedMode = scope?.get("advanced_mode")?.jsonPrimitive?.booleanOrNull ?: false
        plainJson.encodeToString(
            JsonObject(
                mapOf(
                    "advanced_mode" to JsonPrimitive(advancedMode),
                    "include_count" to JsonPrimitive(include.size),
                    "exclude_count" to JsonPrimitive(exclude.size),
                    "include" to include,
                    "exclude" to exclude,
                ),
            ),
        )
    }

    registerTool<SetProjectOptionsInput>(
        name = "set_project_options_json",
        description = "Import and merge Burp project options JSON.",
    ) {
        api.burpSuite().importProjectOptionsFromJson(json)
        "{\"result\":\"ok\"}"
    }

    registerTool<SetUserOptionsInput>(
        name = "set_user_options_json",
        description = "Import and merge Burp user options JSON.",
    ) {
        api.burpSuite().importUserOptionsFromJson(json)
        "{\"result\":\"ok\"}"
    }

    registerTool<SetTaskExecutionEngineStateInput>(
        name = "set_task_engine_state",
        description = "Set Burp task execution engine state (running or paused).",
    ) {
        api.burpSuite().taskExecutionEngine().state =
            if (running) {
                TaskExecutionEngine.TaskExecutionEngineState.RUNNING
            } else {
                TaskExecutionEngine.TaskExecutionEngineState.PAUSED
            }

        json.encodeToString(TaskExecutionEngineStateResult(running = running))
    }

    registerNoInputTool(
        name = "get_task_engine_state",
        description = "Get Burp task execution engine state.",
    ) {
        val running = api.burpSuite().taskExecutionEngine().state == TaskExecutionEngine.TaskExecutionEngineState.RUNNING
        json.encodeToString(TaskExecutionEngineStateResult(running = running))
    }

    registerTool<SetProxyInterceptStateInput>(
        name = "set_proxy_intercept_enabled",
        description = "Enable or disable Proxy Intercept.",
    ) {
        if (intercepting) {
            api.proxy().enableIntercept()
        } else {
            api.proxy().disableIntercept()
        }

        json.encodeToString(ProxyInterceptStateResult(intercepting = intercepting))
    }

    registerNoInputTool(
        name = "get_proxy_intercept_enabled",
        description = "Get current Proxy Intercept state.",
    ) {
        json.encodeToString(ProxyInterceptStateResult(intercepting = api.proxy().isInterceptEnabled()))
    }

    registerNoInputTool(
        name = "get_active_text_editor_contents",
        description = "Get text from the currently focused Burp text component.",
    ) {
        val editor = getActiveEditor(api)
        json.encodeToString(editor?.text ?: "")
    }

    registerTool<SetActiveEditorContentsInput>(
        name = "set_active_text_editor_contents",
        description = "Set text in the currently focused editable Burp text component.",
    ) {
        val editor = getActiveEditor(api) ?: throw IllegalStateException("no active editor")
        require(editor.isEditable) { "active editor is read-only" }
        editor.text = text
        "{\"result\":\"ok\"}"
    }

    registerTool<StartScannerCrawlInput>(
        name = "start_scanner_crawl",
        description = "Start Burp Scanner crawl task for provided seed URLs (Professional edition only).",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "scanner tools require Burp Professional"
        }
        json.encodeToString(scannerTasks.startCrawl(seedUrls))
    }

    registerTool<StartScannerAuditInput>(
        name = "start_scanner_audit",
        description =
            "Start Burp Scanner audit task using built-in preset and optionally queue initial URLs " +
                "(Professional edition only).",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "scanner tools require Burp Professional"
        }
        json.encodeToString(scannerTasks.startAudit(preset, urls))
    }

    registerTool<GetScannerTaskStatusInput>(
        name = "get_scanner_task_status",
        description = "Get status of a scanner task started by start_scanner_crawl/start_scanner_audit.",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "scanner tools require Burp Professional"
        }
        json.encodeToString(scannerTasks.status(taskId))
    }

    registerNoInputTool(
        name = "list_scanner_tasks",
        description =
            "List scanner tasks tracked by Awesome MCP only (current MCP runtime and only tasks started via this MCP instance).",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "scanner tools require Burp Professional"
        }
        json.encodeToString(scannerTasks.list())
    }

    registerTool<CancelScannerTaskInput>(
        name = "cancel_scanner_task",
        description = "Cancel and remove a tracked scanner task by task_id.",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "scanner tools require Burp Professional"
        }
        json.encodeToString(scannerTasks.cancel(taskId))
    }

    registerTool<GenerateScannerReportInput>(
        name = "generate_scanner_report",
        description =
            "Generate Burp Scanner report file from issues with optional filters " +
                "(Professional edition only).",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "scanner tools require Burp Professional"
        }
        val selectors = buildScannerIssueSelectors(api, nameRegex, urlRegex, severity, confidence)

        val matchedIssues =
            selectors.sourceIssues.filter { issue ->
                scannerIssueMatches(
                    issue = issue,
                    severities = selectors.severities,
                    confidences = selectors.confidences,
                    namePattern = selectors.namePattern,
                )
            }

        json.encodeToString(scannerTasks.generateReport(matchedIssues, format, outputFile))
    }

    registerTool<QueryScannerIssuesInput>(
        name = "list_scanner_issues",
        description =
            "List Burp scanner issues with pagination, filters, optional projected context, " +
                "and optional item field projection " +
                "(Professional edition only).",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "scanner issues require Burp Professional"
        }

        val normalizedLimit = limit.coerceIn(1, 500)
        val normalizedOffset = offset.coerceAtLeast(0)
        val normalizedMaxRequestResponses = maxRequestResponses.coerceIn(0, 20)
        val projection = toFieldProjection()
        val materialization = resolveScannerIssueMaterialization(projection)
        val options = serialization.normalized(materialization.requestResponseHttp)
        val selectors = buildScannerIssueSelectors(api, nameRegex, urlRegex, severity, confidence)
        val selected = mutableListOf<AuditIssue>()
        val matched =
            if (!selectors.hasLocalFilters) {
                val total = selectors.sourceIssues.size
                selected += selectors.sourceIssues.drop(normalizedOffset).take(normalizedLimit)
                total
            } else {
                var total = 0
                selectors.sourceIssues.forEach { issue ->
                    if (
                        !scannerIssueMatches(
                            issue = issue,
                            severities = selectors.severities,
                            confidences = selectors.confidences,
                            namePattern = selectors.namePattern,
                        )
                    ) {
                        return@forEach
                    }
                    if (total >= normalizedOffset && selected.size < normalizedLimit) {
                        selected += issue
                    }
                    total += 1
                }
                total
            }
        val mapped =
            selected.map { issue ->
                mapScannerIssueSummary(issue, materialization, options, normalizedMaxRequestResponses)
            }

        encodeWithItemFieldProjection(
            QueryScannerIssuesResult(
                total = matched,
                returned = mapped.size,
                offset = normalizedOffset,
                limit = normalizedLimit,
                hasMore = normalizedOffset + mapped.size < matched,
                results = mapped,
            ),
            projection,
        )
    }

    registerTool<GenerateCollaboratorPayloadInput>(
        name = "generate_collaborator_payload",
        description =
            "Generate a Burp Collaborator payload URL for OOB testing " +
                "and return payload_id + secret_key for later polling (Professional edition only). " +
                "custom_data must be 1-16 alphanumeric characters (Burp runtime limit).",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "collaborator tools require Burp Professional"
        }
        json.encodeToString(collaborator.generatePayload(customData))
    }

    registerTool<GetCollaboratorInteractionsInput>(
        name = "list_collaborator_interactions",
        description =
            "Poll Burp Collaborator interactions (Professional edition only). " +
                "Use payload_id for tracked payloads in current session, or pass payload (+ optional secret_key) " +
                "to query after restart/extension reload.",
    ) {
        require(api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
            "collaborator tools require Burp Professional"
        }
        json.encodeToString(collaborator.getInteractions(payloadId = payloadId, payload = payload, secretKey = secretKey))
    }

    registerTool<QueryCookieJarInput>(
        name = "list_cookie_jar",
        description = "List Burp Cookie Jar entries with pagination, ordering, and regex filters.",
    ) {
        json.encodeToString(cookieJar.query(this))
    }

    registerTool<SetCookieJarCookieInput>(
        name = "set_cookie_jar_cookie",
        description = "Set or overwrite a cookie in Burp Cookie Jar.",
    ) {
        json.encodeToString(cookieJar.setCookie(this))
    }

    registerTool<DeleteCookieJarCookieInput>(
        name = "expire_cookie_jar_cookie",
        description =
            "Expire matching Cookie Jar entries by cookie name and domain, optionally scoped to path " +
                "(Montoya Cookie Jar does not provide hard delete).",
    ) {
        json.encodeToString(cookieJar.deleteCookie(this))
    }
}

private fun nonBlankValueOrNull(block: () -> String?): String? =
    runCatching { block() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

private fun <T> whenMaterialized(
    enabled: Boolean,
    block: () -> T?,
): T? = if (enabled) block() else null

private fun nonBlankWhenMaterialized(
    enabled: Boolean,
    block: () -> String?,
): String? = whenMaterialized(enabled) { nonBlankValueOrNull(block) }

private fun mapScannerIssueSummary(
    issue: AuditIssue,
    materialization: ScannerIssueMaterialization,
    options: net.portswigger.mcp.history.HttpSerializationOptions,
    maxRequestResponses: Int,
): ScannerIssueSummary {
    val definition = whenMaterialized(materialization.includeDefinition) { runCatching { issue.definition() }.getOrNull() }

    return ScannerIssueSummary(
        name = issue.name() ?: "",
        severity = scannerSeverityToWire(issue.severity().name),
        confidence = scannerConfidenceToWire(issue.confidence().name),
        baseUrl = issue.baseUrl(),
        detail = nonBlankWhenMaterialized(materialization.includeDetail) { issue.detail() },
        remediation = nonBlankWhenMaterialized(materialization.includeRemediation) { issue.remediation() },
        issueBackground = nonBlankWhenMaterialized(definition != null) { definition?.background() },
        remediationBackground = nonBlankWhenMaterialized(definition != null) { definition?.remediation() },
        typicalSeverity = definition?.typicalSeverity()?.name?.let(::scannerSeverityToWire),
        typeIndex = definition?.typeIndex(),
        requestResponses = materializeScannerIssueRequestResponses(issue, materialization, options, maxRequestResponses),
    )
}

private fun materializeScannerIssueRequestResponses(
    issue: AuditIssue,
    materialization: ScannerIssueMaterialization,
    options: net.portswigger.mcp.history.HttpSerializationOptions,
    maxRequestResponses: Int,
): List<ScannerIssueRequestResponse>? {
    if (!materialization.includeRequestResponses || maxRequestResponses <= 0) {
        return null
    }

    return issue.requestResponses().take(maxRequestResponses).map { requestResponse ->
        ScannerIssueRequestResponse(
            request = serializeHttpRequest(requestResponse.request(), options),
            response =
                runCatching {
                    if (requestResponse.hasResponse()) {
                        serializeHttpResponse(requestResponse.response(), options)
                    } else {
                        null
                    }
                }.getOrNull(),
        )
    }
}

private fun resolveProjectedSerializationOptions(
    serialization: ProjectedHttpSerializationOptionsInput,
    projection: FieldProjection?,
    regexExcerptEnabled: Boolean = false,
): net.portswigger.mcp.history.HttpSerializationOptions =
    serialization.normalized(
        resolveProjectedHttpMaterialization(
            projection?.fields,
            projection?.excludeFields,
            regexExcerptEnabled = regexExcerptEnabled,
        ),
    )

private fun sendRequestWithOptions(
    api: MontoyaApi,
    request: HttpRequest,
    optionsInput: SendRequestOptionsInput?,
) = when {
    optionsInput == null -> api.http().sendRequest(request)
    else -> {
        val requestOptions = optionsInput.toRequestOptions()
        if (requestOptions != null) {
            api.http().sendRequest(request, requestOptions)
        } else {
            api.http().sendRequest(request)
        }
    }
}

private fun sentRequestResult(
    request: HttpRequest,
    responseMessage: HttpResponse?,
    serializationOptions: net.portswigger.mcp.history.HttpSerializationOptions,
    regexExcerpt: net.portswigger.mcp.history.RegexExcerptConfig? = null,
): BulkToolItemResult {
    val summary =
        SentRequestSummary(
            statusCode = runCatching { responseMessage?.statusCode()?.toInt() }.getOrNull(),
            hasResponse = responseMessage != null,
            request = serializeHttpRequest(request, serializationOptions),
            response = responseMessage?.let { serializeHttpResponse(it, serializationOptions) },
            matchContext = regexExcerpt?.let { buildHttpMatchContext(request, responseMessage, notes = null, it) },
        )
    return BulkToolItemResult(ok = true, result = json.parseToJsonElement(json.encodeToString(summary)))
}

private fun SendRequestOptionsInput.toRequestOptions(): RequestOptions? {
    var requestOptions: RequestOptions? = null

    fun ensure(): RequestOptions {
        if (requestOptions == null) {
            requestOptions = RequestOptions.requestOptions()
        }
        return requestOptions!!
    }

    val mode = httpMode?.let(::parseHttpMode)
    if (mode != null) {
        requestOptions = ensure().withHttpMode(mode)
    }

    if (!connectionId.isNullOrBlank()) {
        requestOptions = ensure().withConnectionId(connectionId)
    }

    val redirectMode = redirectionMode?.let(::parseRedirectionMode)
    if (redirectMode != null) {
        requestOptions = ensure().withRedirectionMode(redirectMode)
    }

    if (responseTimeoutMs != null) {
        require(responseTimeoutMs >= 0) { "responseTimeoutMs must be >= 0" }
        requestOptions = ensure().withResponseTimeout(responseTimeoutMs)
    }

    if (!serverNameIndicator.isNullOrBlank()) {
        requestOptions = ensure().withServerNameIndicator(serverNameIndicator)
    }

    if (upstreamTlsVerification) {
        requestOptions = ensure().withUpstreamTLSVerification()
    }

    return requestOptions
}

private fun parseHttpMode(input: String): HttpMode =
    when (input.trim().lowercase()) {
        "auto" -> HttpMode.AUTO
        "http_1", "http1", "h1" -> HttpMode.HTTP_1
        "http_2", "http2", "h2" -> HttpMode.HTTP_2
        "http_2_ignore_alpn", "http2_ignore_alpn", "h2_ignore_alpn" -> HttpMode.HTTP_2_IGNORE_ALPN
        else -> throw IllegalArgumentException("unsupported httpMode: $input")
    }

private fun parseRedirectionMode(input: String): RedirectionMode =
    when (input.trim().lowercase()) {
        "always" -> RedirectionMode.ALWAYS
        "never" -> RedirectionMode.NEVER
        "same_host" -> RedirectionMode.SAME_HOST
        "in_scope" -> RedirectionMode.IN_SCOPE
        else -> throw IllegalArgumentException("unsupported redirectionMode: $input")
    }

private fun mergeHttp2Mode(options: SendRequestOptionsInput?): SendRequestOptionsInput {
    if (options == null) {
        return SendRequestOptionsInput(httpMode = "http_2")
    }
    val mode =
        options.httpMode
            ?.trim()
            ?.lowercase()

    return when {
        mode.isNullOrBlank() -> options.copy(httpMode = "http_2")
        mode in setOf("http_2", "http2", "h2", "http_2_ignore_alpn", "http2_ignore_alpn", "h2_ignore_alpn") -> options
        else -> throw IllegalArgumentException(
            "send_http2_requests requires request_options.http_mode=http_2 or http_2_ignore_alpn",
        )
    }
}

private fun buildIntruderTemplate(
    request: HttpRequest,
    insertionPoints: List<InsertionPointRangeInput>?,
    generationMode: String?,
): HttpRequestTemplate {
    val explicitRanges =
        insertionPoints?.map { point ->
            require(point.start >= 0) { "insertion point start must be >= 0" }
            require(point.end > point.start) { "insertion point end must be > start" }
            Range.range(point.start, point.end)
        }

    if (!explicitRanges.isNullOrEmpty()) {
        return HttpRequestTemplate.httpRequestTemplate(request, explicitRanges)
    }

    val mode = parseTemplateGenerationMode(generationMode)
    return HttpRequestTemplate.httpRequestTemplate(request, mode)
}

private fun parseTemplateGenerationMode(input: String?): HttpRequestTemplateGenerationOptions {
    if (input.isNullOrBlank()) {
        return HttpRequestTemplateGenerationOptions.REPLACE_BASE_PARAMETER_VALUE_WITH_OFFSETS
    }

    return when (input.trim().lowercase()) {
        "replace_base_parameter_value_with_offsets", "replace" ->
            HttpRequestTemplateGenerationOptions.REPLACE_BASE_PARAMETER_VALUE_WITH_OFFSETS

        "append_offsets_to_base_parameter_value", "append" ->
            HttpRequestTemplateGenerationOptions.APPEND_OFFSETS_TO_BASE_PARAMETER_VALUE

        else -> throw IllegalArgumentException("unsupported generationMode: $input")
    }
}

private data class ScopeTarget(
    val displayUrl: String,
    val normalizedUrl: String?,
    val host: String?,
    val rulePrefix: String,
    val candidatePrefixes: Set<String>,
)

private fun parseScopeTarget(input: String): ScopeTarget {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "url must not be blank" }
    require(trimmed.any { it.isLetterOrDigit() }) { "url must contain at least one letter or digit" }
    val hasExplicitScheme = trimmed.contains("://")

    val absoluteUri =
        if (hasExplicitScheme) {
            runCatching { URI(trimmed) }.getOrNull()?.takeIf { it.host != null }
        } else {
            runCatching { URI(trimmed) }.getOrNull()?.takeIf { it.host != null }
                ?: runCatching { URI("https://${trimmed.removePrefix("//")}") }.getOrNull()?.takeIf { it.host != null }
        }
    if (absoluteUri == null) {
        if (hasExplicitScheme) {
            throw IllegalArgumentException("invalid absolute url: host is required")
        }
        val literal = trimmed.removePrefix("//")
        val hostCandidate = extractHostCandidate(literal)
        return ScopeTarget(
            displayUrl = trimmed,
            normalizedUrl = null,
            host = hostCandidate,
            rulePrefix = trimmed,
            candidatePrefixes =
                setOf(
                    trimmed.normalizeScopePrefix(),
                    literal.normalizeScopePrefix(),
                ).filter { it.isNotBlank() }
                    .toSet(),
        )
    }

    val scheme = (absoluteUri.scheme ?: "https").lowercase()

    val host =
        absoluteUri.host
            ?.trim()
            ?.lowercase()
            ?.removePrefix(".")
            ?: throw IllegalArgumentException("url must contain host")
    require(host.isNotBlank()) { "url host must not be blank" }

    val normalizedUrl =
        buildScopeUrl(
            scheme = scheme,
            host = host,
            port = absoluteUri.port,
            rawPath = absoluteUri.rawPath,
            rawQuery = absoluteUri.rawQuery,
            rawFragment = absoluteUri.rawFragment,
        )
    val fullPrefix =
        buildScopeUrl(
            scheme = scheme,
            host = host,
            port = absoluteUri.port,
            rawPath = absoluteUri.rawPath,
            rawQuery = absoluteUri.rawQuery,
            rawFragment = null,
        )

    val rulePrefix = trimmed.removePrefix("//")
    return ScopeTarget(
        displayUrl = trimmed,
        normalizedUrl = normalizedUrl,
        host = host,
        rulePrefix = rulePrefix,
        candidatePrefixes =
            setOf(
                trimmed.normalizeScopePrefix(),
                fullPrefix.normalizeScopePrefix(),
                normalizedUrl.normalizeScopePrefix(),
                rulePrefix.normalizeScopePrefix(),
            ).filter { it.isNotBlank() }.toSet(),
    )
}

private fun buildScopeUrl(
    scheme: String,
    host: String,
    port: Int,
    rawPath: String? = null,
    rawQuery: String? = null,
    rawFragment: String? = null,
): String {
    val builder = StringBuilder()
    builder.append(scheme).append("://").append(host)
    if (port > 0) {
        builder.append(':').append(port)
    }

    val normalizedPath =
        when {
            rawPath.isNullOrEmpty() -> ""
            rawPath.startsWith("/") -> rawPath
            else -> "/$rawPath"
        }
    if (normalizedPath.isNotEmpty()) {
        builder.append(normalizedPath)
    } else if (!rawQuery.isNullOrEmpty() || !rawFragment.isNullOrEmpty()) {
        builder.append('/')
    }

    if (!rawQuery.isNullOrEmpty()) {
        builder.append('?').append(rawQuery)
    }
    if (!rawFragment.isNullOrEmpty()) {
        builder.append('#').append(rawFragment)
    }
    return builder.toString()
}

private fun encodeScopeUpdateResult(
    api: MontoyaApi,
    listName: String,
    url: String,
    includeSubdomains: Boolean,
): String {
    val target = parseScopeTarget(url)
    val scopeRuleUpdated =
        if (includeSubdomains) {
            val hostPrefix =
                target.host
                    ?: throw IllegalArgumentException(
                        "include_subdomains=true requires host-style input (host, host:port, or absolute URL with host)",
                    )
            updateScopePrefixRule(
                api = api,
                listName = listName,
                prefix = hostPrefix,
                includeSubdomains = true,
            )
        } else {
            updateScopePrefixRule(
                api = api,
                listName = listName,
                prefix = target.rulePrefix,
                includeSubdomains = false,
            )
        }

    return json.encodeToString(
        ScopeUrlResult(
            url = target.displayUrl,
            inScope = isTargetInScope(api, target),
            includeSubdomains = includeSubdomains,
            scopeRuleUpdated = scopeRuleUpdated,
        ),
    )
}

private fun removeScopeRules(
    api: MontoyaApi,
    listName: String,
    target: ScopeTarget,
    includeSubdomains: Boolean?,
): Boolean {
    val root = parseProjectOptionsRoot(api)

    val targetRoot = root["target"]?.jsonObject?.toMutableMap() ?: return false
    val scope = targetRoot["scope"]?.jsonObject?.toMutableMap() ?: return false
    val existing = scope[listName]?.jsonArray?.toList() ?: return false

    val filtered =
        existing.filterNot { item ->
            val obj = item as? JsonObject ?: return@filterNot false
            val prefix =
                obj["prefix"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.lowercase()
                    ?.removePrefix(".")
                    ?.removeSuffix("/")
                    ?: return@filterNot false
            if (prefix !in target.candidatePrefixes) return@filterNot false
            val subs = obj["include_subdomains"]?.jsonPrimitive?.booleanOrNull ?: false
            includeSubdomains == null || subs == includeSubdomains
        }

    if (filtered.size == existing.size) {
        return false
    }

    scope[listName] = JsonArray(filtered)
    targetRoot["scope"] = JsonObject(scope)
    root["target"] = JsonObject(targetRoot)

    api.burpSuite().importProjectOptionsFromJson(plainJson.encodeToString(JsonObject(root)))
    return true
}

private fun parseProjectOptionsRoot(api: MontoyaApi): MutableMap<String, JsonElement> {
    val projectOptionsJson = api.burpSuite().exportProjectOptionsAsJson()
    return runCatching {
        plainJson.parseToJsonElement(projectOptionsJson).jsonObject.toMutableMap()
    }.getOrElse { error ->
        throw IllegalStateException("failed to parse Burp project options JSON", error)
    }
}

private fun updateScopePrefixRule(
    api: MontoyaApi,
    listName: String,
    prefix: String,
    includeSubdomains: Boolean,
): Boolean {
    val normalizedPrefix = prefix.normalizeScopePrefix()
    require(normalizedPrefix.any(Char::isLetterOrDigit)) { "url must contain at least one letter or digit" }

    val root = parseProjectOptionsRoot(api)

    val target = root["target"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
    val scope = target["scope"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
    val existing = scope[listName]?.jsonArray?.toMutableList() ?: mutableListOf()

    val alreadyExists =
        existing.any { item ->
            val obj = item as? JsonObject ?: return@any false
            val existingPrefix =
                obj["prefix"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.normalizeScopePrefix()
            val subs = obj["include_subdomains"]?.jsonPrimitive?.booleanOrNull ?: false
            existingPrefix == normalizedPrefix && subs == includeSubdomains
        }

    if (alreadyExists) {
        return false
    }

    existing +=
        JsonObject(
            mapOf(
                "enabled" to JsonPrimitive(true),
                "include_subdomains" to JsonPrimitive(includeSubdomains),
                "prefix" to JsonPrimitive(prefix.trim()),
            ),
        )

    if (!scope.containsKey("advanced_mode")) {
        scope["advanced_mode"] = JsonPrimitive(false)
    }
    scope[listName] = JsonArray(existing)
    target["scope"] = JsonObject(scope)
    root["target"] = JsonObject(target)

    api.burpSuite().importProjectOptionsFromJson(plainJson.encodeToString(JsonObject(root)))
    return true
}

private fun isTargetInScope(
    api: MontoyaApi,
    target: ScopeTarget,
): Boolean {
    target.normalizedUrl?.let { normalizedUrl ->
        if (runCatching { api.scope().isInScope(normalizedUrl) }.getOrDefault(false)) {
            return true
        }
    }

    if (!target.rulePrefix.contains("://")) {
        return false
    }

    return runCatching { api.scope().isInScope(target.rulePrefix) }.getOrDefault(false)
}

private fun extractHostCandidate(prefix: String): String? {
    if (prefix.contains("://")) {
        return null
    }
    val candidate =
        prefix
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trim()
            .removePrefix(".")
            .lowercase()
    return candidate.takeIf { it.any(Char::isLetterOrDigit) }
}

private fun String.normalizeScopePrefix(): String = trim().lowercase().removePrefix(".").removeSuffix("/")

private fun organizerItemToSummary(
    item: OrganizerItem,
    options: net.portswigger.mcp.history.HttpSerializationOptions,
    regexExcerpt: net.portswigger.mcp.history.RegexExcerptConfig? = null,
): OrganizerItemSummary {
    val response = runCatching { if (item.hasResponse()) item.response() else null }.getOrNull()
    val notes = runCatching { item.annotations().notes() }.getOrNull()?.takeIf { it.isNotBlank() }
    return OrganizerItemSummary(
        id = item.id(),
        status = item.status().name.lowercase(),
        url = item.request().url(),
        inScope = item.request().isInScope(),
        notes = notes,
        request = serializeHttpRequest(item.request(), options),
        response = response?.let { serializeHttpResponse(it, options) },
        matchContext = regexExcerpt?.let { buildHttpMatchContext(item.request(), response, notes, it) },
    )
}

private inline fun <reified T> encodeQueryResultWithNext(
    result: T,
    projection: FieldProjection? = null,
): String {
    val payload = json.parseToJsonElement(json.encodeToString(result))
    if (payload !is JsonObject) {
        return json.encodeToString(result)
    }
    val withNext =
        if ("next" in payload) {
            payload
        } else {
            JsonObject(payload + ("next" to JsonNull))
        }
    val projected = applyItemFieldProjection(withNext, projection)
    return plainJson.encodeToString(JsonElement.serializer(), projected)
}

private fun scannerIssueMatches(
    issue: AuditIssue,
    severities: Set<String>?,
    confidences: Set<String>?,
    namePattern: Pattern?,
): Boolean {
    if (severities != null && issue.severity().name !in severities) return false
    if (confidences != null && issue.confidence().name !in confidences) return false
    if (namePattern != null && !namePattern.matcher(issue.name().orEmpty()).find()) return false
    return true
}

private data class ScannerIssueSelectors(
    val sourceIssues: List<AuditIssue>,
    val namePattern: Pattern?,
    val severities: Set<String>?,
    val confidences: Set<String>?,
    val hasLocalFilters: Boolean,
)

private fun buildScannerIssueSelectors(
    api: MontoyaApi,
    nameRegex: String?,
    urlRegex: String?,
    severity: List<ScannerIssueSeverityFilter>?,
    confidence: List<ScannerIssueConfidenceFilter>?,
): ScannerIssueSelectors {
    val namePattern = compileOptionalPattern(nameRegex, "nameRegex")
    val urlPattern = compileOptionalPattern(urlRegex, "urlRegex")
    val severities = severity?.map { it.name }?.toSet()
    val confidences = confidence?.map { it.name }?.toSet()
    return ScannerIssueSelectors(
        sourceIssues = loadScannerIssues(api, urlPattern),
        namePattern = namePattern,
        severities = severities,
        confidences = confidences,
        hasLocalFilters = severities != null || confidences != null || namePattern != null,
    )
}

private fun loadScannerIssues(
    api: MontoyaApi,
    urlPattern: Pattern?,
): List<AuditIssue> {
    if (urlPattern == null) {
        return api.siteMap().issues()
    }
    val filter = SiteMapFilter { node -> urlPattern.matcher(node.url()).find() }
    return api.siteMap().issues(filter)
}

private fun scannerSeverityToWire(value: String): String =
    when (value.uppercase()) {
        "HIGH" -> "high"
        "MEDIUM" -> "medium"
        "LOW" -> "low"
        "INFORMATION" -> "information"
        "FALSE_POSITIVE" -> "false_positive"
        else -> value.lowercase()
    }

private fun scannerConfidenceToWire(value: String): String =
    when (value.uppercase()) {
        "CERTAIN" -> "certain"
        "FIRM" -> "firm"
        "TENTATIVE" -> "tentative"
        else -> value.lowercase()
    }

private fun getActiveEditor(api: MontoyaApi): JTextComponent? {
    val suiteFrame = api.userInterface().swingUtils().suiteFrame()
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner
    val chain = generateSequence(focusOwner) { it.parent }.toList()

    val inBurpWindow = chain.any { it == suiteFrame }
    if (!inBurpWindow || focusOwner !is JTextComponent) {
        return null
    }
    if (chain.any { it.javaClass.name.startsWith("net.portswigger.mcp.") }) {
        return null
    }
    return focusOwner
}

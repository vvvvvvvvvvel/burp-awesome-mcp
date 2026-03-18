package net.portswigger.mcp.history

import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import java.util.regex.Pattern

internal data class RegexExcerptConfig(
    val pattern: Pattern,
    val contextChars: Int,
)

internal data class ListToolRegexControls(
    val filterPattern: Pattern?,
    val excerptConfig: RegexExcerptConfig?,
)

internal fun resolveRegexExcerptConfig(
    serialization: ProjectedHttpSerializationOptionsInput,
    fallbackRegex: String? = null,
): RegexExcerptConfig? {
    val regexExcerpt = serialization.regexExcerpt ?: return null
    require(regexExcerpt.contextChars >= 0) {
        "serialization.regex_excerpt.context_chars must be >= 0"
    }

    val excerptRegex = regexExcerpt.regex?.trim()?.takeIf { it.isNotEmpty() }
    val fallbackExcerptRegex = fallbackRegex?.trim()?.takeIf { it.isNotEmpty() }
    val regex = excerptRegex ?: fallbackExcerptRegex ?: throw IllegalArgumentException("serialization.regex_excerpt requires regex")

    return RegexExcerptConfig(
        pattern = compileOptionalPattern(regex, "serialization.regex_excerpt.regex")!!,
        contextChars = regexExcerpt.contextChars,
    )
}

internal fun resolveListToolRegexControls(
    filterRegex: String?,
    serialization: ProjectedHttpSerializationOptionsInput,
): ListToolRegexControls =
    ListToolRegexControls(
        filterPattern = compileOptionalPattern(filterRegex, "filter.regex"),
        excerptConfig = resolveRegexExcerptConfig(serialization, fallbackRegex = filterRegex),
    )

internal fun validateRegexExcerptProjection(
    config: RegexExcerptConfig?,
    projection: net.portswigger.mcp.tools.FieldProjection?,
) {
    if (config == null) return
    val conflicting =
        projection
            ?.fields
            ?.filter { it in REGEX_EXCERPT_CONFLICTING_FIELDS }
            .orEmpty()
    require(conflicting.isEmpty()) {
        "serialization.regex_excerpt cannot be combined with fields containing request.body, response.body, request.raw, or response.raw"
    }
}

internal fun buildHttpMatchContext(
    request: HttpRequest,
    response: HttpResponse?,
    notes: String?,
    config: RegexExcerptConfig,
): MatchContext? {
    val excerpts = mutableListOf<MatchExcerpt>()

    fun inspect(
        path: String,
        text: String?,
    ) {
        if (text.isNullOrEmpty()) return
        val fieldExcerpts = extractRegexExcerpts(text, config.pattern, config.contextChars)
        if (fieldExcerpts.isEmpty()) return
        excerpts += fieldExcerpts.map { excerpt -> MatchExcerpt(path = path, text = excerpt) }
    }

    val requestFields =
        listOf(
            "request.method" to request.method(),
            "request.url" to request.url(),
            "request.path" to request.path(),
            "request.query" to request.query(),
            "request.host" to request.httpService().host(),
            "request.http_version" to request.httpVersion(),
            "request.headers" to stringifyHeaders(request.headers()),
            "request.body.text" to extractTextBody(request.body().bytes, request.headerValue("Content-Type")),
        )
    requestFields.forEach { (path, text) -> inspect(path, text) }

    response?.let { responseMessage ->
        val responseFields =
            listOf(
                "response.status_code" to runCatching { responseMessage.statusCode().toString() }.getOrNull(),
                "response.reason_phrase" to runCatching { responseMessage.reasonPhrase() }.getOrNull(),
                "response.http_version" to runCatching { responseMessage.httpVersion() }.getOrNull(),
                "response.mime_type" to runCatching { responseMessage.mimeType().name }.getOrNull(),
                "response.stated_mime_type" to runCatching { responseMessage.statedMimeType().name }.getOrNull(),
                "response.inferred_mime_type" to runCatching { responseMessage.inferredMimeType().name }.getOrNull(),
                "response.headers" to stringifyHeaders(responseMessage.headers()),
                "response.body.text" to
                    extractTextBody(
                        responseMessage.body().bytes,
                        responseMessage.headerValue("Content-Type"),
                    ),
            )
        responseFields.forEach { (path, text) -> inspect(path, text) }
    }

    inspect("notes", notes)

    if (excerpts.isEmpty()) {
        return null
    }

    return MatchContext(
        excerpts = excerpts,
    )
}

internal fun buildProxyHttpMatchContext(
    item: ProxyHttpRequestResponse,
    config: RegexExcerptConfig,
): MatchContext? {
    val request = runCatching { item.finalRequest() }.getOrElse { item.request() }
    val response =
        runCatching {
            if (item.hasResponse()) item.response() else null
        }.getOrNull()
    val notes = runCatching { item.annotations().notes() }.getOrNull()?.takeIf { it.isNotBlank() }
    return buildHttpMatchContext(request, response, notes, config)
}

internal fun buildSiteMapMatchContext(
    item: HttpRequestResponse,
    config: RegexExcerptConfig,
): MatchContext? {
    val response =
        runCatching {
            if (item.hasResponse()) item.response() else null
        }.getOrNull()
    val notes = runCatching { item.annotations().notes() }.getOrNull()?.takeIf { it.isNotBlank() }
    return buildHttpMatchContext(item.request(), response, notes, config)
}

private fun stringifyHeaders(headers: List<HttpHeader>): String =
    headers.joinToString("\n") { header -> "${header.name()}: ${header.value()}" }

private fun extractTextBody(
    bytes: ByteArray,
    contentType: String?,
): String? {
    if (bytes.isEmpty()) return ""
    val textLike =
        if (contentType.isNullOrBlank()) {
            !isLikelyBinaryPayload(bytes)
        } else {
            isLikelyTextContentType(contentType)
        }
    return if (textLike) bytes.toString(Charsets.UTF_8) else null
}

private fun extractRegexExcerpts(
    text: String,
    pattern: Pattern,
    contextChars: Int,
): List<String> {
    val excerpts = mutableListOf<String>()
    val matcher = pattern.matcher(text)

    while (matcher.find()) {
        val start = (matcher.start() - contextChars).coerceAtLeast(0)
        val end = (matcher.end() + contextChars).coerceAtMost(text.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        excerpts += prefix + text.substring(start, end) + suffix
    }

    return excerpts
}

private fun isLikelyTextContentType(contentType: String?): Boolean {
    if (contentType.isNullOrBlank()) {
        return false
    }

    val lower = contentType.lowercase()
    return lower.startsWith("text/") ||
        lower.startsWith("application/json") ||
        lower.startsWith("application/xml") ||
        lower.startsWith("application/javascript") ||
        lower.startsWith("application/x-www-form-urlencoded") ||
        lower.startsWith("application/graphql") ||
        lower.startsWith("application/soap+xml") ||
        lower.startsWith("application/x-ndjson") ||
        lower.contains("+json") ||
        lower.contains("+xml")
}

private val REGEX_EXCERPT_CONFLICTING_FIELDS =
    setOf(
        "request.body",
        "response.body",
        "request.raw",
        "response.raw",
    )

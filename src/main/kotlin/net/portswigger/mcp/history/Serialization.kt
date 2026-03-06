@file:Suppress("UsePropertyAccessSyntax")

package net.portswigger.mcp.history

import burp.api.montoya.http.message.Cookie
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.proxy.ProxyWebSocketMessage
import java.security.MessageDigest
import java.util.Base64

private val textContentTypePrefixes =
    listOf(
        "text/",
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-www-form-urlencoded",
        "application/graphql",
        "application/soap+xml",
        "application/x-ndjson",
    )

data class HttpSerializationOptions(
    val includeHeaders: Boolean,
    val includeRequestBody: Boolean,
    val includeResponseBody: Boolean,
    val includeRawRequest: Boolean,
    val includeRawResponse: Boolean,
    val includeBinary: Boolean,
    val maxRequestBodyChars: Int,
    val maxResponseBodyChars: Int,
    val maxRawBodyChars: Int,
    val textOverflowMode: TextOverflowMode,
    val maxBinaryBodyBytes: Int,
)

data class WebSocketSerializationOptions(
    val includeBinary: Boolean,
    val includeEditedPayload: Boolean,
    val maxTextPayloadChars: Int,
    val maxBinaryPayloadBytes: Int,
)

fun HttpSerializationOptionsInput.normalized(): HttpSerializationOptions =
    normalized(
        includeBinaryOverride = null,
        maxRequestBodyCharsOverride = null,
        maxResponseBodyCharsOverride = null,
        textOverflowModeOverride = null,
    )

fun HttpSerializationOptionsInput.normalized(
    includeBinaryOverride: Boolean? = null,
    maxRequestBodyCharsOverride: Int? = null,
    maxResponseBodyCharsOverride: Int? = null,
    textOverflowModeOverride: TextOverflowMode? = null,
): HttpSerializationOptions {
    val normalizedMaxText = maxTextBodyChars.coerceAtLeast(0)
    val normalizedRequestMax = (maxRequestBodyCharsOverride ?: maxRequestBodyChars ?: normalizedMaxText).coerceAtLeast(0)
    val normalizedResponseMax = (maxResponseBodyCharsOverride ?: maxResponseBodyChars ?: normalizedMaxText).coerceAtLeast(0)

    return HttpSerializationOptions(
        includeHeaders = includeHeaders,
        includeRequestBody = includeRequestBody,
        includeResponseBody = includeResponseBody,
        includeRawRequest = includeRawRequest,
        includeRawResponse = includeRawResponse,
        includeBinary = includeBinaryOverride ?: includeBinary,
        maxRequestBodyChars = normalizedRequestMax,
        maxResponseBodyChars = normalizedResponseMax,
        maxRawBodyChars = maxOf(normalizedRequestMax, normalizedResponseMax),
        textOverflowMode = textOverflowModeOverride ?: textOverflowMode,
        maxBinaryBodyBytes = maxBinaryBodyBytes.coerceAtLeast(0),
    )
}

fun WebSocketSerializationOptionsInput.normalized(): WebSocketSerializationOptions =
    WebSocketSerializationOptions(
        includeBinary = includeBinary,
        includeEditedPayload = includeEditedPayload,
        maxTextPayloadChars = maxTextPayloadChars.coerceAtLeast(0),
        maxBinaryPayloadBytes = maxBinaryPayloadBytes.coerceAtLeast(0),
    )

fun QueryProxyHttpHistoryInput.toHttpSerializationOptions(): HttpSerializationOptions = serialization.normalized()

fun GetProxyHttpHistoryItemsInput.toHttpSerializationOptions(): HttpSerializationOptions = serialization.normalized()

fun QuerySiteMapInput.toHttpSerializationOptions(): HttpSerializationOptions = serialization.normalized()

fun GetSiteMapItemsInput.toHttpSerializationOptions(): HttpSerializationOptions = serialization.normalized()

fun QueryProxyWebSocketHistoryInput.toWebSocketSerializationOptions(): WebSocketSerializationOptions = serialization.normalized()

fun GetProxyWebSocketMessagesInput.toWebSocketSerializationOptions(): WebSocketSerializationOptions = serialization.normalized()

fun mapHttpHistoryItem(
    item: ProxyHttpRequestResponse,
    options: HttpSerializationOptions,
): SerializedHttpHistoryEntry {
    val request = runCatching { item.finalRequest() }.getOrElse { item.request() }
    val response =
        runCatching {
            if (item.hasResponse()) item.response() else null
        }.getOrNull()

    return SerializedHttpHistoryEntry(
        id = item.id(),
        time = item.time().toString(),
        edited = runCatching { item.edited() }.getOrDefault(false),
        notes = runCatching { item.annotations().notes() }.getOrNull()?.takeIf { it.isNotBlank() },
        request = mapRequest(request, options),
        response = response?.let { mapResponse(it, options) },
    )
}

fun mapWebSocketHistoryItem(
    item: ProxyWebSocketMessage,
    options: WebSocketSerializationOptions,
): SerializedWebSocketMessage {
    val upgradeRequest = item.upgradeRequest()
    val payloadBytes = runCatching { item.payload().bytes }.getOrDefault(byteArrayOf())

    val payload =
        serializeBody(
            bytes = payloadBytes,
            contentType = null,
            includeBinary = options.includeBinary,
            maxTextChars = options.maxTextPayloadChars,
            maxBinaryBytes = options.maxBinaryPayloadBytes,
        ) ?: MessageBodyView(
            encoding = BodyEncoding.OMITTED,
            size = payloadBytes.size,
            omittedReason = "empty",
        )

    val editedPayload =
        if (options.includeEditedPayload) {
            val editedBytes = runCatching { item.editedPayload().bytes }.getOrDefault(byteArrayOf())
            serializeBody(
                bytes = editedBytes,
                contentType = null,
                includeBinary = options.includeBinary,
                maxTextChars = options.maxTextPayloadChars,
                maxBinaryBytes = options.maxBinaryPayloadBytes,
            )
        } else {
            null
        }

    return SerializedWebSocketMessage(
        id = item.id(),
        webSocketId = item.webSocketId(),
        time = item.time().toString(),
        direction = item.direction().name,
        notes = runCatching { item.annotations().notes() }.getOrNull()?.takeIf { it.isNotBlank() },
        payload = payload,
        editedPayload = editedPayload,
        upgradeRequest =
            SerializedUpgradeRequest(
                method = upgradeRequest.method(),
                url = upgradeRequest.url(),
                host = upgradeRequest.httpService().host(),
                port = upgradeRequest.httpService().port(),
                secure = upgradeRequest.httpService().secure(),
                inScope = upgradeRequest.isInScope(),
            ),
    )
}

fun mapSiteMapItem(
    item: HttpRequestResponse,
    options: HttpSerializationOptions,
): SerializedSiteMapEntry {
    val request = item.request()
    val response =
        runCatching {
            if (item.hasResponse()) item.response() else null
        }.getOrNull()

    return SerializedSiteMapEntry(
        key = siteMapStableKey(item),
        url = request.url(),
        inScope = request.isInScope(),
        notes = runCatching { item.annotations().notes() }.getOrNull()?.takeIf { it.isNotBlank() },
        request = mapRequest(request, options),
        response = response?.let { mapResponse(it, options) },
    )
}

fun siteMapStableKey(item: HttpRequestResponse): String {
    val request = item.request()
    val requestRaw = runCatching { request.toByteArray().bytes }.getOrDefault(byteArrayOf())
    val responseRaw =
        runCatching {
            if (item.hasResponse()) {
                item.response().toByteArray().bytes
            } else {
                byteArrayOf()
            }
        }.getOrDefault(byteArrayOf())
    val notes = runCatching { item.annotations().notes().orEmpty() }.getOrDefault("")

    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .apply {
                update(request.method().uppercase().toByteArray(Charsets.UTF_8))
                update(byteArrayOf(0))
                update(request.url().toByteArray(Charsets.UTF_8))
                update(byteArrayOf(0))
                update(requestRaw)
                update(byteArrayOf(0))
                update(responseRaw)
                update(byteArrayOf(0))
                update(notes.toByteArray(Charsets.UTF_8))
            }.digest()

    return digest.take(12).joinToString("") { byte -> "%02x".format(byte) }
}

fun isHttpHistoryItemInScope(item: ProxyHttpRequestResponse): Boolean {
    val finalRequestScope = runCatching { item.finalRequest().isInScope() }.getOrNull()
    if (finalRequestScope != null) {
        return finalRequestScope
    }
    return runCatching { item.request().isInScope() }.getOrDefault(false)
}

fun isWebSocketItemInScope(item: ProxyWebSocketMessage): Boolean = runCatching { item.upgradeRequest().isInScope() }.getOrDefault(false)

fun serializeHttpRequest(
    request: HttpRequest,
    options: HttpSerializationOptions,
): SerializedHttpRequest = mapRequest(request, options)

fun serializeHttpResponse(
    response: HttpResponse,
    options: HttpSerializationOptions,
): SerializedHttpResponse = mapResponse(response, options)

private fun mapRequest(
    request: HttpRequest,
    options: HttpSerializationOptions,
): SerializedHttpRequest {
    val payloadCapture = capturePayload(request, options.includeHeaders)

    return SerializedHttpRequest(
        method = request.method(),
        url = request.url(),
        path = request.path(),
        query = request.query(),
        host = request.httpService().host(),
        port = request.httpService().port(),
        secure = request.httpService().secure(),
        inScope = request.isInScope(),
        httpVersion = request.httpVersion(),
        headers = payloadCapture.headers,
        body =
            serializeOptionalBody(
                payloadCapture = payloadCapture,
                options = options,
                includeBody = options.includeRequestBody,
                maxTextChars = options.maxRequestBodyChars,
                omittedReason = "request body excluded by includeRequestBody=false",
            ),
        raw = serializeOptionalRaw(payloadCapture, options.includeRawRequest, options.maxRawBodyChars),
    )
}

private fun mapResponse(
    response: HttpResponse,
    options: HttpSerializationOptions,
): SerializedHttpResponse {
    val payloadCapture = capturePayload(response, options.includeHeaders)

    return SerializedHttpResponse(
        statusCode = response.statusCode().toInt(),
        reasonPhrase = response.reasonPhrase(),
        httpVersion = response.httpVersion(),
        mimeType = runCatching { response.mimeType().name }.getOrNull(),
        statedMimeType = runCatching { response.statedMimeType().name }.getOrNull(),
        inferredMimeType = runCatching { response.inferredMimeType().name }.getOrNull(),
        headers = payloadCapture.headers,
        cookies =
            if (options.includeHeaders) {
                response.cookies().map(::mapCookie)
            } else {
                null
            },
        body =
            serializeOptionalBody(
                payloadCapture = payloadCapture,
                options = options,
                includeBody = options.includeResponseBody,
                maxTextChars = options.maxResponseBodyChars,
                omittedReason = "response body excluded by includeResponseBody=false",
            ),
        raw = serializeOptionalRaw(payloadCapture, options.includeRawResponse, options.maxRawBodyChars),
    )
}

fun headersToMap(headers: List<HttpHeader>): Map<String, List<String>> {
    val out = linkedMapOf<String, MutableList<String>>()
    headers.forEach { header ->
        val values = out.getOrPut(header.name()) { mutableListOf() }
        values += header.value()
    }
    return out
}

private class SerializedPayloadCapture(
    val headers: Map<String, List<String>>?,
    val bodyBytes: ByteArray,
    val rawBytes: ByteArray,
    val contentType: String?,
)

private fun capturePayload(
    request: HttpRequest,
    includeHeaders: Boolean,
): SerializedPayloadCapture =
    captureSerializedPayload(
        includeHeaders = includeHeaders,
        headersSupplier = { request.headers() },
        bodySupplier = { request.body().bytes },
        rawSupplier = { request.toByteArray().bytes },
        contentTypeSupplier = { request.headerValue("Content-Type") },
    )

private fun capturePayload(
    response: HttpResponse,
    includeHeaders: Boolean,
): SerializedPayloadCapture =
    captureSerializedPayload(
        includeHeaders = includeHeaders,
        headersSupplier = { response.headers() },
        bodySupplier = { response.body().bytes },
        rawSupplier = { response.toByteArray().bytes },
        contentTypeSupplier = { response.headerValue("Content-Type") },
    )

private fun captureSerializedPayload(
    includeHeaders: Boolean,
    headersSupplier: () -> List<HttpHeader>,
    bodySupplier: () -> ByteArray,
    rawSupplier: () -> ByteArray,
    contentTypeSupplier: () -> String?,
): SerializedPayloadCapture =
    SerializedPayloadCapture(
        headers = if (includeHeaders) headersToMap(headersSupplier()) else null,
        bodyBytes = runCatching(bodySupplier).getOrDefault(byteArrayOf()),
        rawBytes = runCatching(rawSupplier).getOrDefault(byteArrayOf()),
        contentType = runCatching(contentTypeSupplier).getOrNull(),
    )

private fun serializeOptionalBody(
    payloadCapture: SerializedPayloadCapture,
    options: HttpSerializationOptions,
    includeBody: Boolean,
    maxTextChars: Int,
    omittedReason: String,
): MessageBodyView =
    if (includeBody) {
        serializeBody(
            bytes = payloadCapture.bodyBytes,
            contentType = payloadCapture.contentType,
            includeBinary = options.includeBinary,
            maxTextChars = maxTextChars,
            textOverflowMode = options.textOverflowMode,
            maxBinaryBytes = options.maxBinaryBodyBytes,
        ) ?: MessageBodyView(
            encoding = BodyEncoding.OMITTED,
            size = payloadCapture.bodyBytes.size,
            omittedReason = "body serialization returned null",
        )
    } else {
        MessageBodyView(
            encoding = BodyEncoding.OMITTED,
            size = payloadCapture.bodyBytes.size,
            omittedReason = omittedReason,
        )
    }

private fun serializeOptionalRaw(
    payloadCapture: SerializedPayloadCapture,
    includeRaw: Boolean,
    maxRawBodyChars: Int,
): MessageBodyView? =
    if (includeRaw) {
        serializeBody(
            bytes = payloadCapture.rawBytes,
            contentType = "text/plain",
            includeBinary = true,
            maxTextChars = maxRawBodyChars,
            textOverflowMode = TextOverflowMode.TRUNCATE,
            maxBinaryBytes = Int.MAX_VALUE,
        )
    } else {
        null
    }

fun serializeBody(
    bytes: ByteArray,
    contentType: String?,
    includeBinary: Boolean,
    maxTextChars: Int,
    textOverflowMode: TextOverflowMode = TextOverflowMode.TRUNCATE,
    maxBinaryBytes: Int,
): MessageBodyView? {
    if (bytes.isEmpty()) {
        return MessageBodyView(
            encoding = BodyEncoding.TEXT,
            size = 0,
            text = "",
        )
    }

    val textLike =
        if (contentType.isNullOrBlank()) {
            !isLikelyBinaryPayload(bytes)
        } else {
            isLikelyText(contentType)
        }
    if (textLike) {
        val text = bytes.toString(Charsets.UTF_8)
        if (maxTextChars > 0 && text.length > maxTextChars) {
            return when (textOverflowMode) {
                TextOverflowMode.TRUNCATE ->
                    MessageBodyView(
                        encoding = BodyEncoding.TEXT,
                        size = bytes.size,
                        truncated = true,
                        text = text.take(maxTextChars),
                    )

                TextOverflowMode.OMIT ->
                    MessageBodyView(
                        encoding = BodyEncoding.OMITTED,
                        size = bytes.size,
                        omittedReason = "text body omitted (exceeds max chars limit)",
                    )
            }
        }
        return MessageBodyView(
            encoding = BodyEncoding.TEXT,
            size = bytes.size,
            text = text,
        )
    }

    if (!includeBinary) {
        return MessageBodyView(
            encoding = BodyEncoding.OMITTED,
            size = bytes.size,
            omittedReason = "binary body omitted (includeBinary=false)",
        )
    }

    if (maxBinaryBytes > 0 && bytes.size > maxBinaryBytes) {
        return MessageBodyView(
            encoding = BodyEncoding.OMITTED,
            size = bytes.size,
            omittedReason = "binary body omitted (exceeds maxBinaryBodyBytes)",
        )
    }

    return MessageBodyView(
        encoding = BodyEncoding.BASE64,
        size = bytes.size,
        base64 = Base64.getEncoder().encodeToString(bytes),
    )
}

private fun isLikelyText(contentType: String?): Boolean {
    if (contentType.isNullOrBlank()) {
        return false
    }

    val lower = contentType.lowercase()
    if (textContentTypePrefixes.any { lower.startsWith(it) }) {
        return true
    }
    if (lower.contains("+json") || lower.contains("+xml")) {
        return true
    }
    return false
}

internal fun isLikelyBinaryPayload(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false

    val sampleSize = minOf(bytes.size, 512)
    var suspicious = 0
    for (i in 0 until sampleSize) {
        val value = bytes[i].toInt() and 0xFF
        if (value == 0) return true
        val isAllowedControl = value == 9 || value == 10 || value == 13
        if (value < 32 && !isAllowedControl) suspicious += 1
    }

    return suspicious * 100 / sampleSize >= 12
}

private fun mapCookie(cookie: Cookie): SerializedCookie =
    SerializedCookie(
        name = cookie.name(),
        value = cookie.value(),
        domain = cookie.domain(),
        path = cookie.path(),
        expiration = cookie.expiration().orElse(null)?.toString(),
    )

fun previewValue(
    value: String,
    maxChars: Int = 120,
): String {
    val trimmed = value.trim()
    if (trimmed.length <= maxChars) return trimmed
    return trimmed.take(maxChars) + "..."
}

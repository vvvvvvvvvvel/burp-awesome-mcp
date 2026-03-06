package net.portswigger.mcp.history

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SortOrder {
    @SerialName("asc")
    ASC,

    @SerialName("desc")
    DESC,
}

@Serializable
enum class IdDirection {
    @SerialName("increasing")
    INCREASING,

    @SerialName("decreasing")
    DECREASING,
}

@Serializable
enum class BodyEncoding {
    @SerialName("text")
    TEXT,

    @SerialName("base64")
    BASE64,

    @SerialName("omitted")
    OMITTED,
}

@Serializable
enum class TextOverflowMode {
    @SerialName("truncate")
    TRUNCATE,

    @SerialName("omit")
    OMIT,
}

@Serializable
data class MessageBodyView(
    val encoding: BodyEncoding,
    val size: Int,
    val truncated: Boolean = false,
    val text: String? = null,
    val base64: String? = null,
    val omittedReason: String? = null,
)

@Serializable
data class SerializedCookie(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val expiration: String? = null,
)

@Serializable
data class SerializedHttpRequest(
    val method: String,
    val url: String,
    val path: String,
    val query: String? = null,
    val host: String,
    val port: Int,
    val secure: Boolean,
    val inScope: Boolean,
    val httpVersion: String? = null,
    val headers: Map<String, List<String>>? = null,
    val body: MessageBodyView? = null,
    val raw: MessageBodyView? = null,
)

@Serializable
data class SerializedHttpResponse(
    val statusCode: Int,
    val reasonPhrase: String? = null,
    val httpVersion: String? = null,
    val mimeType: String? = null,
    val statedMimeType: String? = null,
    val inferredMimeType: String? = null,
    val headers: Map<String, List<String>>? = null,
    val cookies: List<SerializedCookie>? = null,
    val body: MessageBodyView? = null,
    val raw: MessageBodyView? = null,
)

@Serializable
data class SerializedHttpHistoryEntry(
    val id: Int,
    val time: String,
    val edited: Boolean,
    val notes: String? = null,
    val request: SerializedHttpRequest,
    val response: SerializedHttpResponse? = null,
)

@Serializable
data class QueryHttpHistoryResult(
    val total: Int,
    val next: QueryProxyHttpHistoryInput? = null,
    val results: List<SerializedHttpHistoryEntry>,
)

@Serializable
data class HttpHistoryLookupItem(
    val id: Int,
    val item: SerializedHttpHistoryEntry? = null,
    val error: String? = null,
)

@Serializable
data class GetHttpHistoryItemsResult(
    val requested: Int,
    val found: Int,
    val results: List<HttpHistoryLookupItem>,
)

@Serializable
data class SerializedUpgradeRequest(
    val method: String,
    val url: String,
    val host: String,
    val port: Int,
    val secure: Boolean,
    val inScope: Boolean,
)

@Serializable
data class SerializedWebSocketMessage(
    val id: Int,
    val webSocketId: Int,
    val time: String,
    val direction: String,
    val notes: String? = null,
    val payload: MessageBodyView,
    val editedPayload: MessageBodyView? = null,
    val upgradeRequest: SerializedUpgradeRequest,
)

@Serializable
data class QueryWebSocketHistoryResult(
    val total: Int,
    val next: QueryProxyWebSocketHistoryInput? = null,
    val results: List<SerializedWebSocketMessage>,
)

@Serializable
data class SerializedSiteMapEntry(
    val key: String,
    val url: String,
    val inScope: Boolean,
    val notes: String? = null,
    val request: SerializedHttpRequest,
    val response: SerializedHttpResponse? = null,
)

@Serializable
data class QuerySiteMapResult(
    val total: Int,
    val next: QuerySiteMapInput? = null,
    val results: List<SerializedSiteMapEntry>,
)

@Serializable
data class SiteMapLookupItem(
    val key: String,
    val item: SerializedSiteMapEntry? = null,
    val error: String? = null,
)

@Serializable
data class GetSiteMapItemsResult(
    val requested: Int,
    val found: Int,
    val results: List<SiteMapLookupItem>,
)

@Serializable
data class WebSocketLookupItem(
    val id: Int,
    val item: SerializedWebSocketMessage? = null,
    val error: String? = null,
)

@Serializable
data class GetWebSocketMessagesResult(
    val requested: Int,
    val found: Int,
    val results: List<WebSocketLookupItem>,
)

@Serializable
data class HttpSerializationOptionsInput(
    val includeHeaders: Boolean = true,
    val includeRequestBody: Boolean = true,
    val includeResponseBody: Boolean = true,
    val includeRawRequest: Boolean = false,
    val includeRawResponse: Boolean = false,
    val includeBinary: Boolean = false,
    val maxTextBodyChars: Int = 1_024,
    val maxRequestBodyChars: Int? = null,
    val maxResponseBodyChars: Int? = null,
    val textOverflowMode: TextOverflowMode = TextOverflowMode.OMIT,
    val maxBinaryBodyBytes: Int = 65_536,
)

@Serializable
data class WebSocketSerializationOptionsInput(
    val includeBinary: Boolean = false,
    val includeEditedPayload: Boolean = false,
    val maxTextPayloadChars: Int = 4_000,
    val maxBinaryPayloadBytes: Int = 65_536,
)

@Serializable
data class HttpRequestResponseFilterInput(
    val inScopeOnly: Boolean = true,
    val regex: String? = null,
    val methods: List<String>? = null,
    val hostRegex: String? = null,
    val mimeTypes: List<String>? = null,
    val inferredMimeTypes: List<String>? = null,
    val statusCodes: List<Int>? = null,
    val hasResponse: Boolean? = null,
    val timeFrom: String? = null,
    val timeTo: String? = null,
)

@Serializable
data class QueryProxyHttpHistoryInput(
    val startId: Int = 0,
    val idDirection: IdDirection = IdDirection.INCREASING,
    val limit: Int = 20,
    val filter: HttpRequestResponseFilterInput = HttpRequestResponseFilterInput(),
    val serialization: HttpSerializationOptionsInput = HttpSerializationOptionsInput(),
)

@Serializable
data class GetProxyHttpHistoryItemsInput(
    val ids: List<Int>,
    val serialization: HttpSerializationOptionsInput = HttpSerializationOptionsInput(),
)

@Serializable
@Suppress("unused")
enum class WebSocketDirectionFilter {
    @SerialName("client_to_server")
    CLIENT_TO_SERVER,

    @SerialName("server_to_client")
    SERVER_TO_CLIENT,
}

@Serializable
data class WebSocketHistoryFilterInput(
    val inScopeOnly: Boolean = true,
    val regex: String? = null,
    val direction: List<WebSocketDirectionFilter>? = null,
    val webSocketIds: List<Int>? = null,
    val hostRegex: String? = null,
    val listenerPorts: List<Int>? = null,
    val hasEditedPayload: Boolean? = null,
    val timeFrom: String? = null,
    val timeTo: String? = null,
)

@Serializable
data class QueryProxyWebSocketHistoryInput(
    val startId: Int = 0,
    val idDirection: IdDirection = IdDirection.INCREASING,
    val limit: Int = 20,
    val filter: WebSocketHistoryFilterInput = WebSocketHistoryFilterInput(),
    val serialization: WebSocketSerializationOptionsInput = WebSocketSerializationOptionsInput(),
)

@Serializable
data class GetProxyWebSocketMessagesInput(
    val ids: List<Int>,
    val serialization: WebSocketSerializationOptionsInput = WebSocketSerializationOptionsInput(),
)

@Serializable
data class QuerySiteMapInput(
    val limit: Int = 20,
    val startAfterKey: String? = null,
    val filter: HttpRequestResponseFilterInput = HttpRequestResponseFilterInput(),
    val serialization: HttpSerializationOptionsInput = HttpSerializationOptionsInput(),
)

@Serializable
data class GetSiteMapItemsInput(
    val keys: List<String>,
    val serialization: HttpSerializationOptionsInput = HttpSerializationOptionsInput(),
)

@Serializable
data class ExtractCookiesFromHistoryInput(
    val limit: Int = 50,
    val offset: Int = 0,
    val order: SortOrder = SortOrder.DESC,
    val inScopeOnly: Boolean = true,
    val regex: String? = null,
)

@Serializable
data class CookieObservation(
    val source: String,
    val name: String,
    val valuePreview: String,
    val count: Int,
    val firstSeenHistoryId: Int,
    val lastSeenHistoryId: Int,
)

@Serializable
data class ExtractCookiesFromHistoryResult(
    val totalEntriesScanned: Int,
    val uniqueCookies: Int,
    val observations: List<CookieObservation>,
)

@Serializable
data class ExtractAuthHeadersFromHistoryInput(
    val limit: Int = 50,
    val offset: Int = 0,
    val order: SortOrder = SortOrder.DESC,
    val inScopeOnly: Boolean = true,
    val regex: String? = null,
)

@Serializable
data class AuthHeaderObservation(
    val header: String,
    val valuePreview: String,
    val count: Int,
    val firstSeenHistoryId: Int,
    val lastSeenHistoryId: Int,
)

@Serializable
data class ExtractAuthHeadersFromHistoryResult(
    val totalEntriesScanned: Int,
    val uniqueHeaders: Int,
    val observations: List<AuthHeaderObservation>,
)

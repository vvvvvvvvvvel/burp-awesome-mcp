package net.portswigger.mcp.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import net.portswigger.mcp.history.HttpRequestResponseFilterInput
import net.portswigger.mcp.history.HttpSerializationOptionsInput
import net.portswigger.mcp.history.IdDirection
import net.portswigger.mcp.history.SerializedHttpRequest
import net.portswigger.mcp.history.SerializedHttpResponse
import net.portswigger.mcp.history.SortOrder

@Serializable
data class SendHttp1RequestItem(
    val content: String,
    val targetHostname: String,
    val targetPort: Int,
    val usesHttps: Boolean,
)

@Serializable
data class SendHttp1RequestInput(
    val items: List<SendHttp1RequestItem>,
    val requestOptions: SendRequestOptionsInput? = null,
    val serialization: HttpSerializationOptionsInput = HttpSerializationOptionsInput(),
    val parallel: Boolean = false,
    val parallelRps: Int = 10,
)

@Serializable
data class SendHttp2RequestItem(
    val pseudoHeaders: Map<String, String>,
    val headers: Map<String, String>,
    val headersList: List<HttpHeaderNameValueInput>? = null,
    val requestBody: String,
    val targetHostname: String,
    val targetPort: Int,
    val usesHttps: Boolean,
)

@Serializable
data class HttpHeaderNameValueInput(
    val name: String,
    val value: String,
)

@Serializable
data class SendHttp2RequestInput(
    val items: List<SendHttp2RequestItem>,
    val requestOptions: SendRequestOptionsInput? = null,
    val serialization: HttpSerializationOptionsInput = HttpSerializationOptionsInput(),
    val parallel: Boolean = false,
    val parallelRps: Int = 10,
)

@Serializable
data class CreateRepeaterTabItem(
    val content: String,
    val targetHostname: String,
    val targetPort: Int,
    val usesHttps: Boolean,
    val tabName: String? = null,
)

@Serializable
data class CreateRepeaterTabInput(
    val items: List<CreateRepeaterTabItem>,
)

@Serializable
data class SendToIntruderItem(
    val content: String,
    val targetHostname: String,
    val targetPort: Int,
    val usesHttps: Boolean,
    val tabName: String? = null,
)

@Serializable
data class SendToIntruderInput(
    val items: List<SendToIntruderItem>,
)

@Serializable
data class InsertionPointRangeInput(
    val start: Int,
    val end: Int,
)

@Serializable
data class SendToIntruderTemplateItem(
    val content: String,
    val targetHostname: String,
    val targetPort: Int,
    val usesHttps: Boolean,
    val tabName: String? = null,
    val insertionPoints: List<InsertionPointRangeInput>? = null,
    val generationMode: String? = null,
)

@Serializable
data class SendToIntruderTemplateInput(
    val items: List<SendToIntruderTemplateItem>,
)

@Serializable
data class SendRequestOptionsInput(
    val httpMode: String? = null,
    val connectionId: String? = null,
    val redirectionMode: String? = null,
    val responseTimeoutMs: Long? = null,
    val serverNameIndicator: String? = null,
    val upstreamTlsVerification: Boolean = false,
)

@Serializable
data class ScopeIncludeUrlInput(
    val url: String,
    val includeSubdomains: Boolean = false,
)

@Serializable
data class ScopeExcludeUrlInput(
    val url: String,
    val includeSubdomains: Boolean = false,
)

@Serializable
data class ScopeRemoveIncludeUrlInput(
    val url: String,
    val includeSubdomains: Boolean? = null,
)

@Serializable
data class ScopeRemoveExcludeUrlInput(
    val url: String,
    val includeSubdomains: Boolean? = null,
)

@Serializable
data class ScopeCheckUrlInput(
    val url: String,
)

@Serializable
data class ScopeUrlResult(
    val url: String,
    val inScope: Boolean,
    val includeSubdomains: Boolean? = null,
    val scopeRuleUpdated: Boolean? = null,
)

@Serializable
data class UrlCodecItem(
    val content: String,
)

@Serializable
data class UrlCodecInput(
    val items: List<UrlCodecItem>,
)

@Serializable
data class Base64CodecInput(
    val items: List<UrlCodecItem>,
)

@Serializable
data class GenerateRandomStringInput(
    val length: Int,
    val characterSet: String,
)

@Serializable
data class SetProjectOptionsInput(
    val json: String,
)

@Serializable
data class SetUserOptionsInput(
    val json: String,
)

@Serializable
data class SetTaskExecutionEngineStateInput(
    val running: Boolean,
)

@Serializable
data class SetProxyInterceptStateInput(
    val intercepting: Boolean,
)

@Serializable
data class SetActiveEditorContentsInput(
    val text: String,
)

@Serializable
data class GenerateCollaboratorPayloadInput(
    val customData: String? = null,
)

@Serializable
data class GetCollaboratorInteractionsInput(
    val payloadId: String? = null,
    val payload: String? = null,
    val secretKey: String? = null,
)

@Serializable
data class QueryScannerIssuesInput(
    val limit: Int = 20,
    val offset: Int = 0,
    val severity: List<ScannerIssueSeverityFilter>? = null,
    val confidence: List<ScannerIssueConfidenceFilter>? = null,
    val nameRegex: String? = null,
    val urlRegex: String? = null,
    val includeDetail: Boolean = false,
    val includeRemediation: Boolean = false,
    val includeDefinition: Boolean = false,
    val includeRequestResponse: Boolean = false,
    val maxRequestResponses: Int = 3,
    val serialization: HttpSerializationOptionsInput =
        HttpSerializationOptionsInput(
            includeRequestBody = false,
            includeResponseBody = false,
        ),
)

@Serializable
@Suppress("unused")
enum class ScannerIssueSeverityFilter {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW,

    @SerialName("information")
    INFORMATION,

    @SerialName("false_positive")
    FALSE_POSITIVE,
}

@Serializable
@Suppress("unused")
enum class ScannerIssueConfidenceFilter {
    @SerialName("certain")
    CERTAIN,

    @SerialName("firm")
    FIRM,

    @SerialName("tentative")
    TENTATIVE,
}

@Serializable
data class QueryCookieJarInput(
    val limit: Int = 100,
    val offset: Int = 0,
    val order: SortOrder = SortOrder.DESC,
    val domainRegex: String? = null,
    val nameRegex: String? = null,
    val includeExpired: Boolean = false,
    val includeValues: Boolean = false,
    val maxValueChars: Int = 200,
)

@Serializable
data class CookieJarItem(
    val key: String,
    val name: String,
    val domain: String? = null,
    val path: String? = null,
    val expiration: String? = null,
    val valuePreview: String,
    val value: String? = null,
    val valueTruncated: Boolean = false,
)

@Serializable
data class QueryCookieJarResult(
    val total: Int,
    val returned: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val order: SortOrder,
    val domainRegex: String? = null,
    val nameRegex: String? = null,
    val includeExpired: Boolean,
    val includeValues: Boolean,
    val maxValueChars: Int,
    val results: List<CookieJarItem>,
)

@Serializable
data class SetCookieJarCookieInput(
    val name: String,
    val value: String,
    val domain: String,
    val path: String? = null,
    val expiration: String? = null,
    val maxValueChars: Int = 200,
)

@Serializable
data class SetCookieJarCookieResult(
    val key: String,
    val name: String,
    val domain: String,
    val path: String? = null,
    val expiration: String? = null,
    val valuePreview: String,
    val valueTruncated: Boolean,
)

@Serializable
data class DeleteCookieJarCookieInput(
    val name: String,
    val domain: String,
    val path: String? = null,
)

@Serializable
data class DeleteCookieJarCookieResult(
    val name: String,
    val domain: String,
    val path: String? = null,
    val deleted: Int,
)

@Serializable
data class BulkToolItemResult(
    val ok: Boolean,
    val result: JsonElement? = null,
    val error: String? = null,
)

@Serializable
data class BulkToolResponse(
    val results: List<BulkToolItemResult>,
)

@Serializable
data class SentRequestSummary(
    val statusCode: Int? = null,
    val hasResponse: Boolean,
    val request: SerializedHttpRequest,
    val response: SerializedHttpResponse? = null,
)

@Serializable
data class ProxyInterceptStateResult(
    val intercepting: Boolean,
)

@Serializable
data class TaskExecutionEngineStateResult(
    val running: Boolean,
)

@Serializable
data class ScannerIssueSummary(
    val name: String,
    val severity: String,
    val confidence: String,
    val baseUrl: String? = null,
    val detail: String? = null,
    val remediation: String? = null,
    val issueBackground: String? = null,
    val remediationBackground: String? = null,
    val typicalSeverity: String? = null,
    val typeIndex: Int? = null,
    val requestResponses: List<ScannerIssueRequestResponse>? = null,
)

@Serializable
data class ScannerIssueRequestResponse(
    val request: SerializedHttpRequest,
    val response: SerializedHttpResponse? = null,
)

@Serializable
data class QueryScannerIssuesResult(
    val total: Int,
    val returned: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
    val results: List<ScannerIssueSummary>,
)

@Serializable
data class CollaboratorPayloadResult(
    val payload: String,
    val payloadId: String,
    val server: String,
    val secretKey: String,
)

@Serializable
data class CollaboratorInteractionSummary(
    val id: String,
    val type: String,
    val timestamp: String,
    val clientIp: String,
    val clientPort: Int,
    val customData: String? = null,
)

@Serializable
data class CollaboratorInteractionsResult(
    val count: Int,
    val payloadId: String? = null,
    val payload: String? = null,
    val secretKey: String? = null,
    val interactions: List<CollaboratorInteractionSummary>,
)

@Serializable
data class Base64DecodeResult(
    val encoding: String,
    val size: Int,
    val text: String? = null,
    val base64: String? = null,
)

@Serializable
data class StartScannerCrawlInput(
    val seedUrls: List<String>,
)

@Serializable
enum class ScannerAuditPreset {
    @SerialName("passive_audit_checks")
    PASSIVE_AUDIT_CHECKS,

    @SerialName("active_audit_checks")
    ACTIVE_AUDIT_CHECKS,
}

@Serializable
data class StartScannerAuditInput(
    val preset: ScannerAuditPreset = ScannerAuditPreset.PASSIVE_AUDIT_CHECKS,
    val urls: List<String> = emptyList(),
)

@Serializable
data class GetScannerTaskStatusInput(
    val taskId: String,
)

@Serializable
data class CancelScannerTaskInput(
    val taskId: String,
)

@Serializable
data class ScannerTaskStatusResult(
    val taskId: String,
    val taskType: String,
    val statusMessage: String,
    val requestCount: Int,
    val errorCount: Int,
)

@Serializable
data class ListScannerTasksResult(
    val total: Int,
    val results: List<ScannerTaskStatusResult>,
)

@Serializable
data class CancelScannerTaskResult(
    val taskId: String,
    val deleted: Boolean,
)

@Serializable
enum class ScannerReportFormatInput {
    @SerialName("html")
    HTML,

    @SerialName("xml")
    XML,
}

@Serializable
data class GenerateScannerReportInput(
    val outputFile: String,
    val format: ScannerReportFormatInput = ScannerReportFormatInput.HTML,
    val severity: List<ScannerIssueSeverityFilter>? = null,
    val confidence: List<ScannerIssueConfidenceFilter>? = null,
    val nameRegex: String? = null,
    val urlRegex: String? = null,
)

@Serializable
data class GenerateScannerReportResult(
    val outputFile: String,
    val format: String,
    val includedIssues: Int,
)

@Serializable
data class SendToOrganizerItem(
    val content: String,
    val targetHostname: String,
    val targetPort: Int,
    val usesHttps: Boolean,
)

@Serializable
data class SendToOrganizerInput(
    val items: List<SendToOrganizerItem>,
)

@Serializable
@Suppress("unused")
enum class OrganizerStatusFilter {
    @SerialName("unknown")
    UNKNOWN,

    @SerialName("new")
    NEW,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("postponed")
    POSTPONED,

    @SerialName("done")
    DONE,

    @SerialName("ignored")
    IGNORED,
}

@Serializable
data class QueryOrganizerItemsInput(
    val startId: Int = 0,
    val idDirection: IdDirection = IdDirection.INCREASING,
    val limit: Int = 20,
    val status: List<OrganizerStatusFilter>? = null,
    val filter: HttpRequestResponseFilterInput = HttpRequestResponseFilterInput(),
    val serialization: HttpSerializationOptionsInput = HttpSerializationOptionsInput(),
)

@Serializable
data class GetOrganizerItemsInput(
    val ids: List<Int>,
    val serialization: HttpSerializationOptionsInput = HttpSerializationOptionsInput(),
)

@Serializable
data class OrganizerItemSummary(
    val id: Int,
    val status: String,
    val url: String,
    val inScope: Boolean,
    val notes: String? = null,
    val request: SerializedHttpRequest,
    val response: SerializedHttpResponse? = null,
)

@Serializable
data class QueryOrganizerItemsResult(
    val total: Int,
    val next: QueryOrganizerItemsInput? = null,
    val results: List<OrganizerItemSummary>,
)

@Serializable
data class OrganizerLookupItem(
    val id: Int,
    val item: OrganizerItemSummary? = null,
    val error: String? = null,
)

@Serializable
data class GetOrganizerItemsResult(
    val requested: Int,
    val found: Int,
    val results: List<OrganizerLookupItem>,
)

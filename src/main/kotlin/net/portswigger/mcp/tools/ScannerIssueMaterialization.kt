package net.portswigger.mcp.tools

import net.portswigger.mcp.history.ProjectedHttpMaterialization
import net.portswigger.mcp.history.resolveProjectedHttpMaterialization

internal data class ScannerIssueMaterialization(
    val includeDetail: Boolean,
    val includeRemediation: Boolean,
    val includeDefinition: Boolean,
    val includeRequestResponses: Boolean,
    val requestResponseHttp: ProjectedHttpMaterialization,
)

internal fun resolveScannerIssueMaterialization(projection: FieldProjection?): ScannerIssueMaterialization {
    val fields = projection?.fields ?: return defaultScannerIssueMaterialization()

    val requestResponseFields =
        fields
            .mapNotNull { path ->
                when {
                    path == "request_responses" -> "request,response"
                    path.startsWith("request_responses.request.") -> "request.${path.removePrefix("request_responses.request.")}"
                    path == "request_responses.request" -> "request"
                    path.startsWith("request_responses.response.") -> "response.${path.removePrefix("request_responses.response.")}"
                    path == "request_responses.response" -> "response"
                    else -> null
                }
            }.flatMap { it.split(',') }
            .toSet()
            .takeIf { it.isNotEmpty() }

    return ScannerIssueMaterialization(
        includeDetail = "detail" in fields,
        includeRemediation = "remediation" in fields,
        includeDefinition =
            "issue_background" in fields ||
                "remediation_background" in fields ||
                "typical_severity" in fields ||
                "type_index" in fields,
        includeRequestResponses =
            "request_responses" in fields ||
                fields.any { it.startsWith("request_responses.") },
        requestResponseHttp =
            resolveProjectedHttpMaterialization(
                fields = requestResponseFields,
                excludeFields = null,
            ),
    )
}

private fun defaultScannerIssueMaterialization(): ScannerIssueMaterialization =
    ScannerIssueMaterialization(
        includeDetail = false,
        includeRemediation = false,
        includeDefinition = false,
        includeRequestResponses = false,
        requestResponseHttp =
            resolveProjectedHttpMaterialization(
                fields = null,
                excludeFields = null,
            ),
    )

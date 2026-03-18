package net.portswigger.mcp.history

internal data class ProjectedHttpMaterialization(
    val includeHeaders: Boolean,
    val includeRequestBody: Boolean,
    val includeResponseBody: Boolean,
    val includeRawRequest: Boolean,
    val includeRawResponse: Boolean,
)

internal fun resolveProjectedHttpMaterialization(
    fields: Set<String>?,
    excludeFields: Set<String>?,
    regexExcerptEnabled: Boolean = false,
): ProjectedHttpMaterialization {
    if (fields == null || excludeFields != null) {
        return ProjectedHttpMaterialization(
            includeHeaders = true,
            includeRequestBody = !regexExcerptEnabled,
            includeResponseBody = !regexExcerptEnabled,
            includeRawRequest = false,
            includeRawResponse = false,
        )
    }

    val includeRequestSubtree = "request" in fields
    val includeResponseSubtree = "response" in fields

    return ProjectedHttpMaterialization(
        includeHeaders =
            includeRequestSubtree ||
                includeResponseSubtree ||
                "request.headers" in fields ||
                "response.headers" in fields ||
                "response.cookies" in fields,
        includeRequestBody = !regexExcerptEnabled && (includeRequestSubtree || "request.body" in fields),
        includeResponseBody = !regexExcerptEnabled && (includeResponseSubtree || "response.body" in fields),
        includeRawRequest = !regexExcerptEnabled && "request.raw" in fields,
        includeRawResponse = !regexExcerptEnabled && "response.raw" in fields,
    )
}

package net.portswigger.mcp.history

import burp.api.montoya.MontoyaApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val EMPTY_PROJECT_SCOPE_ERROR =
    "project target scope is empty; configure Burp target scope or set filter.in_scope_only=false"

internal const val PROJECT_OPTIONS_PARSE_ERROR = "failed to parse Burp project options JSON"

private val scopeConfigJson =
    Json {
        ignoreUnknownKeys = true
    }

internal fun hasConfiguredProjectScope(api: MontoyaApi): Boolean {
    val projectOptionsJson = api.burpSuite().exportProjectOptionsAsJson()
    val root =
        runCatching {
            scopeConfigJson.parseToJsonElement(projectOptionsJson).jsonObject
        }.getOrElse { error ->
            throw IllegalStateException(PROJECT_OPTIONS_PARSE_ERROR, error)
        }

    val includeRules =
        root["target"]
            ?.jsonObject
            ?.get("scope")
            ?.jsonObject
            ?.get("include")
            ?.jsonArray
            ?: return false

    return includeRules.any { rule ->
        val ruleObject = rule as? JsonObject ?: return@any false
        val enabled = ruleObject["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        val prefix = ruleObject["prefix"]?.jsonPrimitive?.contentOrNull?.trim()
        enabled && !prefix.isNullOrBlank()
    }
}

internal fun requireConfiguredProjectScopeForInScopeOnly(
    api: MontoyaApi,
    inScopeOnly: Boolean,
) {
    if (inScopeOnly && !hasConfiguredProjectScope(api)) {
        throw IllegalArgumentException(EMPTY_PROJECT_SCOPE_ERROR)
    }
}

package net.portswigger.mcp.history

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectedHttpSerializationTest {
    @Test
    fun `fields projection should auto-enable only requested branches`() {
        val materialization =
            resolveProjectedHttpMaterialization(
                fields = setOf("id", "request.method", "response.status_code"),
                excludeFields = null,
            )

        assertFalse(materialization.includeHeaders)
        assertFalse(materialization.includeRequestBody)
        assertFalse(materialization.includeResponseBody)
        assertFalse(materialization.includeRawRequest)
        assertFalse(materialization.includeRawResponse)
    }

    @Test
    fun `request subtree should auto-enable request headers and body`() {
        val materialization =
            resolveProjectedHttpMaterialization(
                fields = setOf("request"),
                excludeFields = null,
            )

        assertTrue(materialization.includeHeaders)
        assertTrue(materialization.includeRequestBody)
        assertFalse(materialization.includeResponseBody)
        assertFalse(materialization.includeRawRequest)
        assertFalse(materialization.includeRawResponse)
    }

    @Test
    fun `response cookies should auto-enable headers only`() {
        val materialization =
            resolveProjectedHttpMaterialization(
                fields = setOf("id", "response.cookies"),
                excludeFields = null,
            )

        assertTrue(materialization.includeHeaders)
        assertFalse(materialization.includeRequestBody)
        assertFalse(materialization.includeResponseBody)
        assertFalse(materialization.includeRawRequest)
        assertFalse(materialization.includeRawResponse)
    }

    @Test
    fun `raw branches should materialize only when explicitly requested`() {
        val materialization =
            resolveProjectedHttpMaterialization(
                fields = setOf("id", "request.raw", "response.raw"),
                excludeFields = null,
            )

        assertFalse(materialization.includeHeaders)
        assertFalse(materialization.includeRequestBody)
        assertFalse(materialization.includeResponseBody)
        assertTrue(materialization.includeRawRequest)
        assertTrue(materialization.includeRawResponse)
    }

    @Test
    fun `default and exclude fields should preserve full non-raw shape`() {
        val defaultMaterialization = resolveProjectedHttpMaterialization(fields = null, excludeFields = null)
        val excludeMaterialization = resolveProjectedHttpMaterialization(fields = null, excludeFields = setOf("response.body"))

        assertTrue(defaultMaterialization.includeHeaders)
        assertTrue(defaultMaterialization.includeRequestBody)
        assertTrue(defaultMaterialization.includeResponseBody)
        assertFalse(defaultMaterialization.includeRawRequest)
        assertFalse(defaultMaterialization.includeRawResponse)

        assertTrue(excludeMaterialization.includeHeaders)
        assertTrue(excludeMaterialization.includeRequestBody)
        assertTrue(excludeMaterialization.includeResponseBody)
        assertFalse(excludeMaterialization.includeRawRequest)
        assertFalse(excludeMaterialization.includeRawResponse)
    }

    @Test
    fun `regex excerpt should disable body and raw materialization by default`() {
        val materialization =
            resolveProjectedHttpMaterialization(
                fields = null,
                excludeFields = null,
                regexExcerptEnabled = true,
            )

        assertTrue(materialization.includeHeaders)
        assertFalse(materialization.includeRequestBody)
        assertFalse(materialization.includeResponseBody)
        assertFalse(materialization.includeRawRequest)
        assertFalse(materialization.includeRawResponse)
    }

    @Test
    fun `regex excerpt should keep request and response metadata while trimming bodies`() {
        val materialization =
            resolveProjectedHttpMaterialization(
                fields = setOf("request", "response"),
                excludeFields = null,
                regexExcerptEnabled = true,
            )

        assertTrue(materialization.includeHeaders)
        assertFalse(materialization.includeRequestBody)
        assertFalse(materialization.includeResponseBody)
        assertFalse(materialization.includeRawRequest)
        assertFalse(materialization.includeRawResponse)
    }

    @Test
    fun `regex excerpt config should accept fallback regex from filter`() {
        val config =
            resolveRegexExcerptConfig(
                ProjectedHttpSerializationOptionsInput(
                    regexExcerpt = RegexExcerptInput(contextChars = 32),
                ),
                fallbackRegex = "token",
            )

        assertTrue(config != null)
        assertTrue(config!!.pattern.matcher("session_token").find())
        assertTrue(config.contextChars == 32)
    }

    @Test
    fun `regex excerpt config should require regex from either serialization or filter`() {
        val ex =
            assertThrows(IllegalArgumentException::class.java) {
                resolveRegexExcerptConfig(
                    ProjectedHttpSerializationOptionsInput(
                        regexExcerpt = RegexExcerptInput(contextChars = 16),
                    ),
                )
            }

        assertTrue(ex.message!!.contains("serialization.regex_excerpt requires regex"))
    }

    @Test
    fun `regex excerpt default context chars should be compact`() {
        assertTrue(RegexExcerptInput().contextChars == 10)
    }
}

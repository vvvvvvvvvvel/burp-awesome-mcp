package net.portswigger.mcp.history

import burp.api.montoya.http.message.responses.HttpResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class FilteringTest {
    @Test
    fun `mime filter should not match inferred JSON when explicit MIME is HTML`() {
        val filter = compileRequestResponseFilter(HttpRequestResponseFilterInput(mimeTypes = listOf("JSON")))
        val response =
            mockResponse(
                mimeType = "HTML",
                statedMimeType = "HTML",
                inferredMimeType = "JSON",
                contentType = "text/html; charset=UTF-8",
            )

        val matches =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = true,
                responseProvider = { response },
                sentAtProvider = { null },
            )

        assertFalse(matches)
    }

    @Test
    fun `mime filter should match JSON subtype from content type`() {
        val filter = compileRequestResponseFilter(HttpRequestResponseFilterInput(mimeTypes = listOf("JSON")))
        val response =
            mockResponse(
                mimeType = "UNRECOGNIZED",
                statedMimeType = "UNRECOGNIZED",
                inferredMimeType = "UNRECOGNIZED",
                contentType = "application/problem+json; charset=utf-8",
            )

        val matches =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = true,
                responseProvider = { response },
                sentAtProvider = { null },
            )

        assertTrue(matches)
    }

    @Test
    fun `mime filter should use inferred MIME only when explicit MIME is unavailable`() {
        val filter = compileRequestResponseFilter(HttpRequestResponseFilterInput(mimeTypes = listOf("JSON")))
        val response =
            mockResponse(
                mimeType = null,
                statedMimeType = null,
                inferredMimeType = "JSON",
                contentType = null,
            )

        val matches =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = true,
                responseProvider = { response },
                sentAtProvider = { null },
            )

        assertTrue(matches)
    }

    @Test
    fun `inferred mime filter should match inferred MIME explicitly`() {
        val filter =
            compileRequestResponseFilter(
                HttpRequestResponseFilterInput(
                    inferredMimeTypes = listOf("JSON"),
                ),
            )
        val response =
            mockResponse(
                mimeType = "HTML",
                statedMimeType = "HTML",
                inferredMimeType = "JSON",
                contentType = "text/html; charset=UTF-8",
            )

        val matches =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = true,
                responseProvider = { response },
                sentAtProvider = { null },
            )

        assertTrue(matches)
    }

    @Test
    fun `inferred mime filter should not match different inferred MIME`() {
        val filter =
            compileRequestResponseFilter(
                HttpRequestResponseFilterInput(
                    inferredMimeTypes = listOf("JSON"),
                ),
            )
        val response =
            mockResponse(
                mimeType = "JSON",
                statedMimeType = "JSON",
                inferredMimeType = "HTML",
                contentType = "application/json",
            )

        val matches =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = true,
                responseProvider = { response },
                sentAtProvider = { null },
            )

        assertFalse(matches)
    }

    @Test
    fun `status code filter should match only requested codes`() {
        val filter = compileRequestResponseFilter(HttpRequestResponseFilterInput(statusCodes = listOf(200, 401)))
        val responseOk = mockk<HttpResponse>()
        every { responseOk.statusCode() } returns 200.toShort()

        val matchesOk =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = true,
                responseProvider = { responseOk },
                sentAtProvider = { null },
            )
        assertTrue(matchesOk)

        val responseMiss = mockk<HttpResponse>()
        every { responseMiss.statusCode() } returns 404.toShort()

        val matchesMiss =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = true,
                responseProvider = { responseMiss },
                sentAtProvider = { null },
            )
        assertFalse(matchesMiss)
    }

    @Test
    fun `time range filter should accept entries inside window and reject outside`() {
        val filter =
            compileRequestResponseFilter(
                HttpRequestResponseFilterInput(
                    timeFrom = "2026-03-01T00:00:00Z",
                    timeTo = "2026-03-01T23:59:59Z",
                ),
            )

        val inside =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = false,
                responseProvider = { null },
                sentAtProvider = { Instant.parse("2026-03-01T12:00:00Z") },
            )
        assertTrue(inside)

        val outside =
            matchesRequestResponseFilter(
                filter = filter,
                request = mockk(relaxed = true),
                requestHost = "example.com",
                hasResponse = false,
                responseProvider = { null },
                sentAtProvider = { Instant.parse("2026-03-02T00:00:00Z") },
            )
        assertFalse(outside)
    }

    @Test
    fun `status code filter should reject invalid status code values`() {
        assertThrows(IllegalArgumentException::class.java) {
            compileRequestResponseFilter(HttpRequestResponseFilterInput(statusCodes = listOf(99)))
        }
    }

    private fun mockResponse(
        mimeType: String?,
        statedMimeType: String?,
        inferredMimeType: String?,
        contentType: String?,
    ): HttpResponse {
        val response = mockk<HttpResponse>()

        mimeType?.let { value ->
            every { response.mimeType().name } returns value
        } ?: every { response.mimeType() } throws IllegalStateException("missing mimeType")

        statedMimeType?.let { value ->
            every { response.statedMimeType().name } returns value
        } ?: every { response.statedMimeType() } throws IllegalStateException("missing statedMimeType")

        inferredMimeType?.let { value ->
            every { response.inferredMimeType().name } returns value
        } ?: every { response.inferredMimeType() } throws IllegalStateException("missing inferredMimeType")

        every { response.headerValue("Content-Type") } returns contentType
        return response
    }
}

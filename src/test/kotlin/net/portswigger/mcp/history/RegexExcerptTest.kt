package net.portswigger.mcp.history

import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RegexExcerptTest {
    @Test
    fun `build http match context should extract url matches`() {
        val request = mockRequest("https://example.com/api?token=abc123")
        val context =
            buildHttpMatchContext(
                request = request,
                response = null,
                notes = null,
                config = RegexExcerptConfig(pattern = compileOptionalPattern("token=abc", "test")!!, contextChars = 6),
            )

        assertNotNull(context)
        assertEquals("request.url", context!!.excerpts.first().path)
    }

    @Test
    fun `build http match context should return null when nothing matches`() {
        val request = mockRequest("https://example.com/api?token=abc123")
        val context =
            buildHttpMatchContext(
                request = request,
                response = null,
                notes = null,
                config = RegexExcerptConfig(pattern = compileOptionalPattern("csrf", "test")!!, contextChars = 10),
            )

        assertNull(context)
    }

    private fun mockRequest(url: String): HttpRequest {
        val request = mockk<HttpRequest>()
        val service = mockk<HttpService>()

        every { request.method() } returns "GET"
        every { request.url() } returns url
        every { request.path() } returns "/api"
        every { request.query() } returns "token=abc123"
        every { request.httpVersion() } returns "HTTP/2"
        every { request.headers() } returns emptyList()
        every { request.headerValue(any()) } returns null
        every { request.body().bytes } returns byteArrayOf()
        every { request.httpService() } returns service
        every { service.host() } returns "example.com"
        every { service.port() } returns 443
        every { service.secure() } returns true

        return request
    }
}

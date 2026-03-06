package net.portswigger.mcp.history

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.core.ByteArray
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.sitemap.SiteMap
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.regex.Pattern

class SiteMapQueryServiceTest {
    private lateinit var api: MontoyaApi
    private lateinit var siteMap: SiteMap
    private lateinit var service: HistoryQueryService

    @BeforeEach
    fun setup() {
        api = mockk()
        siteMap = mockk()
        service = HistoryQueryService(api)

        every { api.siteMap() } returns siteMap
    }

    @Test
    fun `query site map should paginate in natural order and honor inScopeOnly`() {
        val all =
            listOf(
                mockSiteMapItem("https://example.com/a", inScope = true),
                mockSiteMapItem("https://example.com/b", inScope = false),
                mockSiteMapItem("https://example.com/c", inScope = true),
                mockSiteMapItem("https://example.com/d", inScope = true),
            )

        every { siteMap.requestResponses() } returns all

        val result =
            service.querySiteMap(
                QuerySiteMapInput(
                    limit = 2,
                    startAfterKey = null,
                ),
            )

        assertEquals(4, result.total)
        assertEquals(listOf("https://example.com/a", "https://example.com/c"), result.results.map { it.url })
        assertNotNull(result.next)
    }

    @Test
    fun `query site map should apply regex filtering`() {
        val all =
            listOf(
                mockSiteMapItem("https://example.com/home", inScope = true),
                mockSiteMapItem("https://example.com/admin", inScope = true),
                mockSiteMapItem("https://example.com/api", inScope = true),
            )

        every { siteMap.requestResponses() } returns all

        val result =
            service.querySiteMap(
                QuerySiteMapInput(
                    filter = HttpRequestResponseFilterInput(regex = "admin"),
                ),
            )

        assertEquals(3, result.total)
        assertEquals(listOf("https://example.com/admin"), result.results.map { it.url })
    }

    @Test
    fun `get site map items should resolve by stable key`() {
        val one = mockSiteMapItem("https://example.com/one", inScope = true)
        val two = mockSiteMapItem("https://example.com/two", inScope = true)
        val all = listOf(one, two)

        every { siteMap.requestResponses() } returns all

        val keyOne = siteMapStableKey(one)
        val result =
            service.getSiteMapItems(
                GetSiteMapItemsInput(
                    keys = listOf(keyOne, "missing"),
                ),
            )

        assertEquals(2, result.requested)
        assertEquals(1, result.found)
        assertNotNull(result.results[0].item)
        assertEquals("missing", result.results[1].key)
        assertEquals("not found", result.results[1].error)
    }

    @Test
    fun `get site map items should not filter by scope`() {
        val inScopeItem = mockSiteMapItem("https://example.com/one", inScope = true)
        val outScopeItem = mockSiteMapItem("https://example.com/two", inScope = false)
        every { siteMap.requestResponses() } returns listOf(inScopeItem, outScopeItem)

        val keyInScope = siteMapStableKey(inScopeItem)
        val keyOutScope = siteMapStableKey(outScopeItem)
        val result = service.getSiteMapItems(GetSiteMapItemsInput(keys = listOf(keyInScope, keyOutScope)))

        assertEquals(2, result.requested)
        assertEquals(2, result.found)
        assertNotNull(result.results[0].item)
        assertNotNull(result.results[1].item)
    }

    @Test
    fun `get site map items should require exact query key`() {
        val item = mockSiteMapItem("https://example.com/one", inScope = true)
        every { siteMap.requestResponses() } returns listOf(item)

        val key = siteMapStableKey(item)
        val syntheticKey = "GET https://example.com/one::$key"
        val result = service.getSiteMapItems(GetSiteMapItemsInput(keys = listOf(syntheticKey)))

        assertEquals(1, result.requested)
        assertEquals(0, result.found)
        assertEquals("not found", result.results.first().error)
    }

    @Test
    fun `query site map should generate distinct keys for same method and url when payloads differ`() {
        val one =
            mockSiteMapItem(
                url = "https://example.com/same",
                inScope = true,
                requestRawSuffix = "A",
            )
        val two =
            mockSiteMapItem(
                url = "https://example.com/same",
                inScope = true,
                requestRawSuffix = "B",
            )
        every { siteMap.requestResponses() } returns listOf(one, two)

        val result =
            service.querySiteMap(
                QuerySiteMapInput(
                    limit = 10,
                    startAfterKey = null,
                    filter = HttpRequestResponseFilterInput(inScopeOnly = false),
                ),
            )

        assertEquals(2, result.results.size)
        assertEquals(
            2,
            result.results
                .map { it.key }
                .toSet()
                .size,
        )
    }

    @Test
    fun `query site map should fail when start_after_key does not exist`() {
        every { siteMap.requestResponses() } returns listOf(mockSiteMapItem("https://example.com/a", inScope = true))

        assertThrows(IllegalArgumentException::class.java) {
            service.querySiteMap(
                QuerySiteMapInput(
                    startAfterKey = "does-not-exist",
                    filter = HttpRequestResponseFilterInput(inScopeOnly = false),
                ),
            )
        }
    }

    private fun mockSiteMapItem(
        url: String,
        inScope: Boolean,
        requestRawSuffix: String = "",
    ): HttpRequestResponse {
        val item = mockk<HttpRequestResponse>()
        val request = mockk<HttpRequest>()
        val httpService = mockk<HttpService>()
        val annotations = mockk<Annotations>()
        val uri = URI.create(url)

        every { item.request() } returns request
        every { item.hasResponse() } returns false
        every { item.annotations() } returns annotations
        every { annotations.notes() } returns null
        every { item.contains(any<Pattern>()) } answers {
            val pattern = firstArg<Pattern>()
            pattern.matcher(url).find()
        }

        every { request.method() } returns "GET"
        every { request.url() } returns url
        every { request.path() } returns uri.path
        every { request.query() } returns uri.query
        every { request.httpService() } returns httpService
        every { request.isInScope() } returns inScope
        every { request.httpVersion() } returns "HTTP/1.1"
        every { request.headers() } returns listOf(mockHeader("Host", uri.host))
        every { request.headerValue("Content-Type") } returns "text/plain"
        every { request.body() } returns mockByteArray(byteArrayOf())
        every { request.toByteArray() } returns mockByteArray("GET ${uri.path} HTTP/1.1\r\nX-Test: $requestRawSuffix\r\n\r\n".toByteArray())

        every { httpService.host() } returns uri.host
        every { httpService.port() } returns if (uri.port == -1) 443 else uri.port
        every { httpService.secure() } returns (uri.scheme == "https")

        return item
    }

    private fun mockHeader(
        name: String,
        value: String,
    ): HttpHeader {
        val header = mockk<HttpHeader>()
        every { header.name() } returns name
        every { header.value() } returns value
        return header
    }

    private fun mockByteArray(data: kotlin.ByteArray): ByteArray {
        val bytes = mockk<ByteArray>()
        every { bytes.getBytes() } returns data
        every { bytes.length() } returns data.size
        return bytes
    }
}

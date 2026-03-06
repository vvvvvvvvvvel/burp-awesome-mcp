package net.portswigger.mcp.history

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.core.ByteArray
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.proxy.Proxy
import burp.api.montoya.proxy.ProxyHistoryFilter
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.util.regex.Pattern

class HttpRequestSecurityTest {
    private lateinit var api: MontoyaApi
    private lateinit var proxy: Proxy
    private lateinit var service: HistoryQueryService

    @BeforeEach
    fun setup() {
        api = mockk()
        proxy = mockk()
        service = HistoryQueryService(api)

        every { api.proxy() } returns proxy
        every { proxy.history(any<ProxyHistoryFilter>()) } answers {
            val filter = firstArg<ProxyHistoryFilter>()
            proxy.history().filter { filter.matches(it) }
        }
    }

    @Test
    fun `query should paginate in natural order and honor inScopeOnly`() {
        val all =
            listOf(
                mockHistoryItem(id = 1, inScope = true),
                mockHistoryItem(id = 2, inScope = false),
                mockHistoryItem(id = 3, inScope = true),
                mockHistoryItem(id = 4, inScope = true),
                mockHistoryItem(id = 5, inScope = true),
            )

        every { proxy.history() } returns all

        val result =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 3,
                    startId = 0,
                ),
            )

        assertEquals(5, result.total)
        assertEquals(listOf(1, 3, 4), result.results.map { it.id })
        assertEquals(5, result.next?.startId)
    }

    @Test
    fun `query should use start id as inclusive cursor and compute next cursor`() {
        val all =
            listOf(
                mockHistoryItem(id = 1, inScope = true),
                mockHistoryItem(id = 2, inScope = false),
                mockHistoryItem(id = 3, inScope = false),
                mockHistoryItem(id = 4, inScope = true),
                mockHistoryItem(id = 5, inScope = true),
                mockHistoryItem(id = 6, inScope = true),
                mockHistoryItem(id = 7, inScope = false),
                mockHistoryItem(id = 8, inScope = false),
                mockHistoryItem(id = 9, inScope = false),
                mockHistoryItem(id = 10, inScope = false),
            )
        every { proxy.history() } returns all

        val page1 =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 3,
                    startId = 0,
                ),
            )
        assertEquals(10, page1.total)
        assertEquals(listOf(1, 4, 5), page1.results.map { it.id })
        assertEquals(6, page1.next?.startId)

        val page2 =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 3,
                    startId = page1.next!!.startId,
                ),
            )
        assertEquals(10, page2.total)
        assertEquals(listOf(6), page2.results.map { it.id })
        assertEquals(null, page2.next)
    }

    @Test
    fun `query should apply regex filtering`() {
        val all =
            listOf(
                mockHistoryItem(id = 1, inScope = true),
                mockHistoryItem(id = 2, inScope = true),
                mockHistoryItem(id = 3, inScope = true),
            )

        every { proxy.history() } returns all

        val result =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    filter = HttpRequestResponseFilterInput(regex = "id-2"),
                ),
            )

        assertEquals(3, result.total)
        assertEquals(listOf(2), result.results.map { it.id })
    }

    @Test
    fun `query should apply structured request response filter`() {
        val all =
            listOf(
                mockHistoryItem(id = 1, inScope = true, method = "GET", host = "www.example.com"),
                mockHistoryItem(id = 2, inScope = true, method = "POST", host = "api.example.com"),
                mockHistoryItem(id = 3, inScope = true, method = "PUT", host = "admin.example.com"),
            )

        every { proxy.history() } returns all

        val result =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    filter =
                        HttpRequestResponseFilterInput(
                            inScopeOnly = false,
                            methods = listOf("post"),
                            hostRegex = "^api\\.",
                            hasResponse = false,
                        ),
                ),
            )

        assertEquals(3, result.total)
        assertEquals(listOf(2), result.results.map { it.id })
    }

    @Test
    fun `query should include start id item and skip removed gaps`() {
        val all =
            listOf(
                mockHistoryItem(id = 1, inScope = true),
                mockHistoryItem(id = 2, inScope = true),
                mockHistoryItem(id = 3, inScope = true),
                mockHistoryItem(id = 4, inScope = true),
                mockHistoryItem(id = 5, inScope = true),
            )
        every { proxy.history() } returns all

        val tail =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 2,
                    startId = 4,
                    filter = HttpRequestResponseFilterInput(inScopeOnly = false),
                ),
            )
        assertEquals(listOf(4, 5), tail.results.map { it.id })
        assertEquals(null, tail.next)

        val gap =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 2,
                    startId = 6,
                    filter = HttpRequestResponseFilterInput(inScopeOnly = false),
                ),
            )
        assertEquals(emptyList<Int>(), gap.results.map { it.id })
        assertEquals(null, gap.next)
    }

    @Test
    fun `query should support negative start id like python indexing`() {
        val all =
            listOf(
                mockHistoryItem(id = 1, inScope = true),
                mockHistoryItem(id = 2, inScope = true),
                mockHistoryItem(id = 3, inScope = true),
                mockHistoryItem(id = 4, inScope = true),
                mockHistoryItem(id = 5, inScope = true),
            )
        every { proxy.history() } returns all

        val result =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 2,
                    startId = -2,
                    filter = HttpRequestResponseFilterInput(inScopeOnly = false),
                ),
            )

        assertEquals(listOf(4, 5), result.results.map { it.id })
        assertEquals(null, result.next)
    }

    @Test
    fun `query should support decreasing direction with id cursor`() {
        val all =
            listOf(
                mockHistoryItem(id = 1, inScope = true),
                mockHistoryItem(id = 2, inScope = true),
                mockHistoryItem(id = 3, inScope = true),
                mockHistoryItem(id = 4, inScope = true),
                mockHistoryItem(id = 5, inScope = true),
            )
        every { proxy.history() } returns all

        val page1 =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 2,
                    startId = 4,
                    idDirection = IdDirection.DECREASING,
                    filter = HttpRequestResponseFilterInput(inScopeOnly = false),
                ),
            )
        assertEquals(listOf(4, 3), page1.results.map { it.id })
        assertEquals(2, page1.next?.startId)
        assertEquals(IdDirection.DECREASING, page1.next?.idDirection)

        val page2 =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 2,
                    startId = page1.next!!.startId,
                    idDirection = page1.next!!.idDirection,
                    filter = HttpRequestResponseFilterInput(inScopeOnly = false),
                ),
            )
        assertEquals(listOf(2, 1), page2.results.map { it.id })
        assertEquals(null, page2.next)
    }

    @Test
    fun `query decreasing with start id zero should start from last item`() {
        val all =
            listOf(
                mockHistoryItem(id = 1, inScope = true),
                mockHistoryItem(id = 2, inScope = true),
                mockHistoryItem(id = 3, inScope = true),
            )
        every { proxy.history() } returns all

        val result =
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(
                    limit = 2,
                    startId = 0,
                    idDirection = IdDirection.DECREASING,
                    filter = HttpRequestResponseFilterInput(inScopeOnly = false),
                ),
            )

        assertEquals(listOf(3, 2), result.results.map { it.id })
        assertEquals(1, result.next?.startId)
        assertEquals(IdDirection.DECREASING, result.next?.idDirection)
    }

    @Test
    fun `get by ids should not filter by scope`() {
        val all =
            listOf(
                mockHistoryItem(id = 87, inScope = false),
                mockHistoryItem(id = 88, inScope = true),
            )

        every { proxy.history() } returns all

        val result =
            service.getHttpHistoryItems(
                GetProxyHttpHistoryItemsInput(ids = listOf(87, 88)),
            )

        assertEquals(2, result.requested)
        assertEquals(2, result.found)
        assertEquals(listOf(87, 88), result.results.map { it.id })
        assertEquals(listOf(null, null), result.results.map { it.error })
    }

    @Test
    fun `query should fail on invalid regex`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.queryHttpHistory(
                QueryProxyHttpHistoryInput(filter = HttpRequestResponseFilterInput(regex = "[invalid")),
            )
        }
    }

    private fun mockHistoryItem(
        id: Int,
        inScope: Boolean,
        method: String = "GET",
        host: String = "example.com",
    ): ProxyHttpRequestResponse {
        val item = mockk<ProxyHttpRequestResponse>()
        val request = mockk<HttpRequest>()
        val httpService = mockk<HttpService>()
        val annotations = mockk<Annotations>()

        every { item.id() } returns id
        every { item.time() } returns ZonedDateTime.parse("2026-01-01T00:00:00Z")
        every { item.listenerPort() } returns 8080
        every { item.edited() } returns false
        every { item.annotations() } returns annotations
        every { annotations.notes() } returns null

        every { item.request() } returns request
        every { item.finalRequest() } returns request
        every { item.hasResponse() } returns false
        every { item.contains(any<Pattern>()) } answers {
            val pattern = firstArg<Pattern>()
            pattern.matcher("id-$id").find()
        }

        every { request.method() } returns method
        every { request.url() } returns "https://$host/$id"
        every { request.path() } returns "/$id"
        every { request.query() } returns null
        every { request.httpService() } returns httpService
        every { request.isInScope() } returns inScope
        every { request.httpVersion() } returns "HTTP/1.1"
        every { request.headers() } returns listOf(mockHeader("Host", host))
        every { request.headerValue("Content-Type") } returns "text/plain"
        every { request.body() } returns mockByteArray(byteArrayOf())
        every { request.toByteArray() } returns mockByteArray("GET / HTTP/1.1\r\n\r\n".toByteArray())

        every { httpService.host() } returns host
        every { httpService.port() } returns 443
        every { httpService.secure() } returns true

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

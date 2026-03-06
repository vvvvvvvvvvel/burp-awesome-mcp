package net.portswigger.mcp.history

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.core.ByteArray
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.proxy.Proxy
import burp.api.montoya.proxy.ProxyWebSocketHistoryFilter
import burp.api.montoya.proxy.ProxyWebSocketMessage
import burp.api.montoya.websocket.Direction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import java.util.regex.Pattern

class WebSocketQueryServiceTest {
    private lateinit var api: MontoyaApi
    private lateinit var proxy: Proxy
    private lateinit var service: HistoryQueryService

    @BeforeEach
    fun setup() {
        api = mockk()
        proxy = mockk()
        service = HistoryQueryService(api)
        every { api.proxy() } returns proxy
    }

    @Test
    fun `query websocket history should apply structured filter`() {
        val all =
            listOf(
                mockWebSocketItem(
                    id = 1,
                    webSocketId = 100,
                    direction = Direction.CLIENT_TO_SERVER,
                    host = "api.example.com",
                    listenerPort = 8081,
                    inScope = true,
                    editedPayload = "",
                ),
                mockWebSocketItem(
                    id = 2,
                    webSocketId = 101,
                    direction = Direction.SERVER_TO_CLIENT,
                    host = "ws.example.com",
                    listenerPort = 8082,
                    inScope = true,
                    editedPayload = "patched",
                ),
                mockWebSocketItem(
                    id = 3,
                    webSocketId = 101,
                    direction = Direction.SERVER_TO_CLIENT,
                    host = "ws.example.com",
                    listenerPort = 8082,
                    inScope = false,
                    editedPayload = "patched",
                ),
            )
        every { proxy.webSocketHistory() } returns all

        val result =
            service.queryWebSocketHistory(
                QueryProxyWebSocketHistoryInput(
                    filter =
                        WebSocketHistoryFilterInput(
                            inScopeOnly = true,
                            direction = listOf(WebSocketDirectionFilter.SERVER_TO_CLIENT),
                            webSocketIds = listOf(101),
                            hostRegex = "^ws\\.",
                            listenerPorts = listOf(8082),
                            hasEditedPayload = true,
                        ),
                ),
            )

        assertEquals(3, result.total)
        assertEquals(listOf(2), result.results.map { it.id })
    }

    @Test
    fun `query websocket history should always read fresh snapshot`() {
        val first = listOf(mockWebSocketItem(id = 11, webSocketId = 201))
        val second = listOf(mockWebSocketItem(id = 12, webSocketId = 202))
        val third = listOf(mockWebSocketItem(id = 13, webSocketId = 203))
        every { proxy.webSocketHistory() } returnsMany listOf(first, second, third)

        val firstResult =
            service.queryWebSocketHistory(
                QueryProxyWebSocketHistoryInput(
                    filter = WebSocketHistoryFilterInput(inScopeOnly = false),
                ),
            )
        val cachedResult =
            service.queryWebSocketHistory(
                QueryProxyWebSocketHistoryInput(
                    filter = WebSocketHistoryFilterInput(inScopeOnly = false),
                ),
            )
        val refreshedResult =
            service.queryWebSocketHistory(
                QueryProxyWebSocketHistoryInput(
                    filter = WebSocketHistoryFilterInput(inScopeOnly = false),
                ),
            )

        assertEquals(listOf(11), firstResult.results.map { it.id })
        assertEquals(listOf(12), cachedResult.results.map { it.id })
        assertEquals(listOf(13), refreshedResult.results.map { it.id })
    }

    @Test
    fun `get websocket messages should fetch by ids via websocket history filter`() {
        val id = 77
        val target = mockWebSocketItem(id = id, webSocketId = 900)
        every { proxy.webSocketHistory(any<ProxyWebSocketHistoryFilter>()) } answers {
            val filter = firstArg<ProxyWebSocketHistoryFilter>()
            listOf(target).filter { filter.matches(it) }
        }

        val result = service.getWebSocketMessages(GetProxyWebSocketMessagesInput(ids = listOf(id)))

        assertEquals(1, result.found)
        assertEquals(id, result.results.first().id)
        verify(exactly = 1) { proxy.webSocketHistory(any<ProxyWebSocketHistoryFilter>()) }
        verify(exactly = 0) { proxy.webSocketHistory() }
    }

    @Test
    fun `query websocket history should apply regex through burp filter`() {
        val all =
            listOf(
                mockWebSocketItem(id = 1, webSocketId = 300),
                mockWebSocketItem(id = 2, webSocketId = 301),
            )
        every { proxy.webSocketHistory() } returns all

        val result =
            service.queryWebSocketHistory(
                QueryProxyWebSocketHistoryInput(
                    filter = WebSocketHistoryFilterInput(inScopeOnly = false, regex = "ws-id-2"),
                ),
            )

        assertEquals(2, result.total)
        assertEquals(listOf(2), result.results.map { it.id })
    }

    @Test
    fun `query websocket history should support decreasing id direction`() {
        val all =
            listOf(
                mockWebSocketItem(id = 1, webSocketId = 401),
                mockWebSocketItem(id = 2, webSocketId = 402),
                mockWebSocketItem(id = 3, webSocketId = 403),
                mockWebSocketItem(id = 4, webSocketId = 404),
            )
        every { proxy.webSocketHistory() } returns all

        val result =
            service.queryWebSocketHistory(
                QueryProxyWebSocketHistoryInput(
                    startId = 0,
                    idDirection = IdDirection.DECREASING,
                    limit = 2,
                    filter = WebSocketHistoryFilterInput(inScopeOnly = false),
                ),
            )

        assertEquals(listOf(4, 3), result.results.map { it.id })
        assertEquals(2, result.next?.startId)
        assertEquals(IdDirection.DECREASING, result.next?.idDirection)
    }

    private fun mockWebSocketItem(
        id: Int,
        webSocketId: Int,
        direction: Direction = Direction.CLIENT_TO_SERVER,
        host: String = "example.com",
        listenerPort: Int = 8081,
        inScope: Boolean = true,
        payload: String = "payload",
        editedPayload: String = "",
    ): ProxyWebSocketMessage {
        val item = mockk<ProxyWebSocketMessage>()
        val upgradeRequest = mockk<HttpRequest>()
        val httpService = mockk<HttpService>()
        val annotations = mockk<Annotations>()

        every { item.id() } returns id
        every { item.webSocketId() } returns webSocketId
        every { item.direction() } returns direction
        every { item.time() } returns ZonedDateTime.parse("2026-01-01T00:00:00Z")
        every { item.listenerPort() } returns listenerPort
        every { item.upgradeRequest() } returns upgradeRequest
        every { item.payload() } returns mockByteArray(payload.toByteArray())
        every { item.editedPayload() } returns mockByteArray(editedPayload.toByteArray())
        every { item.annotations() } returns annotations
        every { annotations.notes() } returns null
        every { item.contains(any<Pattern>()) } answers {
            val pattern = firstArg<Pattern>()
            pattern.matcher("ws-id-$id").find()
        }

        every { upgradeRequest.method() } returns "GET"
        every { upgradeRequest.url() } returns "wss://$host/ws"
        every { upgradeRequest.httpService() } returns httpService
        every { upgradeRequest.isInScope() } returns inScope
        every { httpService.host() } returns host
        every { httpService.port() } returns 443
        every { httpService.secure() } returns true

        return item
    }

    private fun mockByteArray(bytes: kotlin.ByteArray): ByteArray {
        val output = mockk<ByteArray>()
        every { output.getBytes() } returns bytes
        every { output.length() } returns bytes.size
        return output
    }
}

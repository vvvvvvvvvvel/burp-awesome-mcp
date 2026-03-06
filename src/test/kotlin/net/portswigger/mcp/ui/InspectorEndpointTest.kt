package net.portswigger.mcp.ui

import net.portswigger.mcp.core.McpSettingsSnapshot
import net.portswigger.mcp.core.McpTransportMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InspectorEndpointTest {
    @Test
    fun `sse endpoint should use base url`() {
        val url =
            inspectorEndpoint(
                McpSettingsSnapshot(
                    enabled = true,
                    host = "127.0.0.1",
                    port = 26001,
                    transport = McpTransportMode.SSE,
                ),
            )

        assertEquals("http://127.0.0.1:26001", url)
    }

    @Test
    fun `streamable endpoint should use mcp path`() {
        val url =
            inspectorEndpoint(
                McpSettingsSnapshot(
                    enabled = true,
                    host = "localhost",
                    port = 26001,
                    transport = McpTransportMode.STREAMABLE_HTTP,
                ),
            )

        assertEquals("http://localhost:26001/mcp", url)
    }

    @Test
    fun `ipv6 host should be bracketed in endpoint`() {
        val url =
            inspectorEndpoint(
                McpSettingsSnapshot(
                    enabled = true,
                    host = "2001:db8::1",
                    port = 26001,
                    transport = McpTransportMode.STREAMABLE_HTTP,
                ),
            )

        assertEquals("http://[2001:db8::1]:26001/mcp", url)
    }

    @Test
    fun `copy endpoint should normalize wildcard host to loopback`() {
        val url =
            inspectorEndpoint(
                McpSettingsSnapshot(
                    enabled = true,
                    host = "0.0.0.0",
                    port = 26001,
                    transport = McpTransportMode.STREAMABLE_HTTP,
                ),
            )

        assertEquals("http://127.0.0.1:26001/mcp", url)
    }
}

package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.collaborator.Collaborator
import burp.api.montoya.collaborator.CollaboratorClient
import burp.api.montoya.collaborator.CollaboratorPayload
import burp.api.montoya.collaborator.CollaboratorServer
import burp.api.montoya.collaborator.Interaction
import burp.api.montoya.collaborator.InteractionFilter
import burp.api.montoya.collaborator.InteractionId
import burp.api.montoya.collaborator.InteractionType
import burp.api.montoya.collaborator.SecretKey
import burp.api.montoya.persistence.Persistence
import burp.api.montoya.persistence.Preferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.time.ZonedDateTime
import java.util.Optional

class CollaboratorSessionServiceTest {
    private lateinit var api: MontoyaApi
    private lateinit var collaborator: Collaborator
    private lateinit var persistence: Persistence
    private lateinit var preferences: Preferences
    private lateinit var client: CollaboratorClient
    private lateinit var clientSecretKey: SecretKey
    private lateinit var service: CollaboratorSessionService

    @BeforeEach
    fun setup() {
        api = mockk()
        collaborator = mockk()
        persistence = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        client = mockk()
        clientSecretKey = mockk()

        every { api.persistence() } returns persistence
        every { persistence.preferences() } returns preferences
        every { preferences.getString(any()) } returns null
        every { api.collaborator() } returns collaborator
        every { collaborator.createClient() } returns client
        every { client.getSecretKey() } returns clientSecretKey
        every { clientSecretKey.toString() } returns "secret-1"

        service = CollaboratorSessionService(api)
    }

    @Test
    fun `generate and poll should reuse one collaborator client`() {
        val payload = mockPayload("payload-id-1", "abc123.oastify.com")
        val interaction = mockInteraction("interaction-1", "probe")
        val server = mockk<CollaboratorServer>()

        every { server.address() } returns "oastify.com"
        every { client.server() } returns server
        every { client.generatePayload() } returns payload
        every { client.getAllInteractions() } returns listOf(interaction)

        val generated = service.generatePayload(null)
        val interactions = service.getInteractions(payloadId = null, payload = null, secretKey = null)

        assertEquals("payload-id-1", generated.payloadId)
        assertEquals("abc123.oastify.com", generated.payload)
        assertEquals("secret-1", generated.secretKey)
        assertEquals(1, interactions.count)
        assertEquals(null, interactions.payloadId)
        assertEquals(null, interactions.payload)
        assertEquals("secret-1", interactions.secretKey)
        verify(exactly = 1) { collaborator.createClient() }
        verify(exactly = 1) { client.generatePayload() }
        verify(exactly = 1) { client.getAllInteractions() }
    }

    @Test
    fun `poll with unknown payload id should fail explicitly`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.getInteractions(payloadId = "unknown-id", payload = null, secretKey = null)
        }
    }

    @Test
    fun `persisted payload map should survive service restart and allow payload id polling`() {
        assumeTrue(
            runCatching { InteractionFilter.interactionPayloadFilter("probe") }.isSuccess,
            "InteractionFilter factory is unavailable outside Burp runtime; skipping persisted payload_id poll test.",
        )

        val persistedPayload = "persisted123.oastify.com"
        val persistedStateJson =
            """
            {"entries":[{"payloadId":"persisted-id","payload":"$persistedPayload"}]}
            """.trimIndent()
        val interaction = mockInteraction("interaction-persisted-1", "persisted")

        every { preferences.getString("awesome_mcp.collaborator_secret_key") } returns null
        every { preferences.getString("awesome_mcp.collaborator_payload_state") } returns persistedStateJson
        every { client.getInteractions(any()) } returns listOf(interaction)

        val restartedService = CollaboratorSessionService(api)
        val result =
            restartedService.getInteractions(
                payloadId = "persisted-id",
                payload = null,
                secretKey = null,
            )

        assertEquals(1, result.count)
        assertEquals("persisted-id", result.payloadId)
        assertEquals(persistedPayload, result.payload)
        verify(exactly = 1) { collaborator.createClient() }
        verify(exactly = 1) { client.getInteractions(any()) }
    }

    @Test
    fun `poll should restore collaborator client from provided secret key`() {
        assumeTrue(
            runCatching { SecretKey.secretKey("probe") }.isSuccess,
            "SecretKey factory is unavailable outside Burp runtime; skipping restoreClient secret-key test.",
        )

        val restoredClient = mockk<CollaboratorClient>()
        val restoredSecret = mockk<SecretKey>()
        val interaction = mockInteraction("interaction-1", "probe")

        every { collaborator.restoreClient(any()) } returns restoredClient
        every { restoredClient.getSecretKey() } returns restoredSecret
        every { restoredSecret.toString() } returns "restored-secret"
        every { restoredClient.getInteractions(any()) } returns listOf(interaction)

        val interactions =
            service.getInteractions(
                payloadId = null,
                payload = "abc123.oastify.com",
                secretKey = "restored-secret",
            )

        assertEquals(1, interactions.count)
        assertEquals("abc123.oastify.com", interactions.payload)
        assertEquals("restored-secret", interactions.secretKey)
        verify(exactly = 1) { collaborator.restoreClient(any()) }
        verify(exactly = 1) { restoredClient.getInteractions(any()) }
    }

    private fun mockPayload(
        payloadId: String,
        payloadText: String,
    ): CollaboratorPayload {
        val payload = mockk<CollaboratorPayload>()
        val id = mockk<InteractionId>()
        every { payload.id() } returns id
        every { id.toString() } returns payloadId
        every { payload.toString() } returns payloadText
        return payload
    }

    private fun mockInteraction(
        idText: String,
        customData: String,
    ): Interaction {
        val interaction = mockk<Interaction>()
        val id = mockk<InteractionId>()
        every { interaction.id() } returns id
        every { id.toString() } returns idText
        every { interaction.type() } returns InteractionType.DNS
        every { interaction.timeStamp() } returns ZonedDateTime.parse("2026-03-01T00:00:00Z")
        every { interaction.clientIp() } returns InetAddress.getByName("127.0.0.1")
        every { interaction.clientPort() } returns 53
        every { interaction.customData() } returns Optional.of(customData)
        return interaction
    }
}

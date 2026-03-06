package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.collaborator.CollaboratorClient
import burp.api.montoya.collaborator.InteractionFilter
import burp.api.montoya.collaborator.SecretKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class CollaboratorSessionService(
    private val api: MontoyaApi,
) {
    private val lock = Any()
    private var client: CollaboratorClient? = null
    private var currentSecretKey: String? = null
    private val payloadById = LinkedHashMap<String, String>()
    private val preferences = api.persistence().preferences()

    init {
        restoreState()
    }

    fun generatePayload(customData: String?): CollaboratorPayloadResult {
        if (customData != null) {
            require(COLLAB_CUSTOM_DATA_PATTERN.matches(customData)) {
                "custom_data must match ^[A-Za-z0-9]{1,16}$"
            }
        }
        val collaboratorClient = getClient()
        val payload =
            if (customData != null) {
                collaboratorClient.generatePayload(customData)
            } else {
                collaboratorClient.generatePayload()
            }

        val payloadText = payload.toString()
        val payloadId = payload.id().toString()
        val secretKey = collaboratorClient.secretKey.toString()
        synchronized(lock) {
            payloadById[payloadId] = payloadText
            trimTrackedPayloads()
            currentSecretKey = secretKey
            persistState()
        }

        return CollaboratorPayloadResult(
            payload = payloadText,
            payloadId = payloadId,
            server = collaboratorClient.server().address(),
            secretKey = secretKey,
        )
    }

    fun getInteractions(
        payloadId: String?,
        payload: String?,
        secretKey: String?,
    ): CollaboratorInteractionsResult {
        val normalizedPayloadId = payloadId?.trim()?.takeIf { it.isNotBlank() }
        val normalizedPayload = payload?.trim()?.takeIf { it.isNotBlank() }
        val normalizedSecretKey = secretKey?.trim()?.takeIf { it.isNotBlank() }
        val collaboratorClient =
            if (normalizedSecretKey == null) {
                getClient()
            } else {
                getClientBySecretKey(normalizedSecretKey)
            }

        val payloadText =
            normalizedPayload
                ?: normalizedPayloadId?.let { id ->
                    synchronized(lock) { payloadById[id] }
                        ?: throw IllegalArgumentException(
                            "unknown payload_id '$id' for current collaborator client; " +
                                "provide payload directly (and secret_key if needed) " +
                                "or generate payload via generate_collaborator_payload first",
                        )
                }

        val interactions =
            if (payloadText == null) {
                collaboratorClient.allInteractions
            } else {
                collaboratorClient.getInteractions(InteractionFilter.interactionPayloadFilter(payloadText))
            }

        val mapped =
            interactions.map {
                CollaboratorInteractionSummary(
                    id = it.id().toString(),
                    type = it.type().name,
                    timestamp = it.timeStamp().toString(),
                    clientIp = it.clientIp().hostAddress,
                    clientPort = it.clientPort(),
                    customData = it.customData().orElse(null),
                )
            }

        return CollaboratorInteractionsResult(
            count = mapped.size,
            payloadId = normalizedPayloadId,
            payload = payloadText,
            secretKey = normalizedSecretKey ?: runCatching { collaboratorClient.secretKey.toString() }.getOrNull(),
            interactions = mapped,
        )
    }

    private fun getClient(): CollaboratorClient =
        synchronized(lock) {
            client
                ?: api.collaborator().createClient().also { created ->
                    client = created
                    currentSecretKey = runCatching { created.secretKey.toString() }.getOrNull()
                    persistState()
                }
        }

    private fun trimTrackedPayloads() {
        if (payloadById.size <= MAX_TRACKED_PAYLOADS) return
        val iterator = payloadById.entries.iterator()
        while (payloadById.size > MAX_TRACKED_PAYLOADS && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private fun getClientBySecretKey(secretKey: String): CollaboratorClient =
        synchronized(lock) {
            val existing = client
            if (existing != null && currentSecretKey == secretKey) return existing

            val secretKeyObject =
                runCatching { SecretKey.secretKey(secretKey) }
                    .getOrElse {
                        throw IllegalStateException(
                            "failed to construct collaborator secret_key; this operation requires Burp runtime support",
                            it,
                        )
                    }
            val restored = api.collaborator().restoreClient(secretKeyObject)
            if (currentSecretKey != null && currentSecretKey != secretKey) {
                payloadById.clear()
            }
            client = restored
            currentSecretKey = secretKey
            persistState()
            restored
        }

    private fun restoreState() {
        synchronized(lock) {
            val persistedStateRaw = preferences.getString(KEY_COLLAB_PAYLOAD_STATE)
            val persistedState =
                runCatching {
                    if (persistedStateRaw.isNullOrBlank()) {
                        CollaboratorPayloadState()
                    } else {
                        stateJson.decodeFromString<CollaboratorPayloadState>(persistedStateRaw)
                    }
                }.getOrDefault(CollaboratorPayloadState())

            payloadById.clear()
            for (entry in persistedState.entries) {
                payloadById[entry.payloadId] = entry.payload
            }
            trimTrackedPayloads()

            val secret = preferences.getString(KEY_COLLAB_SECRET_KEY)?.trim()?.takeIf { it.isNotBlank() }
            if (secret == null) return

            runCatching { api.collaborator().restoreClient(SecretKey.secretKey(secret)) }
                .onSuccess { restored ->
                    client = restored
                    currentSecretKey = secret
                }.onFailure {
                    client = null
                    currentSecretKey = null
                    preferences.deleteString(KEY_COLLAB_SECRET_KEY)
                }
        }
    }

    private fun persistState() {
        val secret = currentSecretKey
        if (secret.isNullOrBlank()) {
            preferences.deleteString(KEY_COLLAB_SECRET_KEY)
        } else {
            preferences.setString(KEY_COLLAB_SECRET_KEY, secret)
        }

        val serializedState =
            CollaboratorPayloadState(
                entries =
                    payloadById.entries.map { (payloadId, payload) ->
                        CollaboratorPayloadEntry(payloadId = payloadId, payload = payload)
                    },
            )
        preferences.setString(KEY_COLLAB_PAYLOAD_STATE, stateJson.encodeToString(serializedState))
    }
}

private const val MAX_TRACKED_PAYLOADS = 2048
private const val KEY_COLLAB_SECRET_KEY = "awesome_mcp.collaborator_secret_key"
private const val KEY_COLLAB_PAYLOAD_STATE = "awesome_mcp.collaborator_payload_state"
private val COLLAB_CUSTOM_DATA_PATTERN = Regex("^[A-Za-z0-9]{1,16}$")

private val stateJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

@Serializable
private data class CollaboratorPayloadState(
    val entries: List<CollaboratorPayloadEntry> = emptyList(),
)

@Serializable
private data class CollaboratorPayloadEntry(
    val payloadId: String,
    val payload: String,
)

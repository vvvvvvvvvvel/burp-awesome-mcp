package net.portswigger.mcp.core

import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.persistence.Preferences

private const val KEY_ENABLED = "awesome_mcp.enabled"
private const val KEY_HOST = "awesome_mcp.host"
private const val KEY_PORT = "awesome_mcp.port"
private const val KEY_TRANSPORT = "awesome_mcp.transport"
private const val KEY_SETTINGS_PROJECT_LEVEL = "awesome_mcp.settings_project_level"

enum class McpTransportMode {
    SSE,
    STREAMABLE_HTTP,
    ;

    companion object {
        fun parse(value: String?): McpTransportMode {
            if (value.isNullOrBlank()) return STREAMABLE_HTTP
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: STREAMABLE_HTTP
        }
    }
}

data class McpSettingsSnapshot(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val transport: McpTransportMode = McpTransportMode.STREAMABLE_HTTP,
    val saveSettingsProjectLevel: Boolean = false,
)

class McpSettingsStore(
    private val projectData: PersistedObject,
    private val preferences: Preferences,
) {
    private data class StorageBackend(
        val getBoolean: (String) -> Boolean?,
        val getString: (String) -> String?,
        val getInteger: (String) -> Int?,
        val setBoolean: (String, Boolean) -> Unit,
        val setString: (String, String) -> Unit,
        val setInteger: (String, Int) -> Unit,
    )

    fun load(): McpSettingsSnapshot {
        val saveSettingsProjectLevel = projectData.getBoolean(KEY_SETTINGS_PROJECT_LEVEL) ?: false
        val backend = backend(saveSettingsProjectLevel)

        return McpSettingsSnapshot(
            enabled = backend.getBoolean(KEY_ENABLED) ?: true,
            host = backend.getString(KEY_HOST)?.ifBlank { "127.0.0.1" } ?: "127.0.0.1",
            port = (backend.getInteger(KEY_PORT) ?: 26001).coerceIn(1, 65535),
            transport = McpTransportMode.parse(backend.getString(KEY_TRANSPORT)),
            saveSettingsProjectLevel = saveSettingsProjectLevel,
        )
    }

    fun saveSettings(snapshot: McpSettingsSnapshot) {
        projectData.setBoolean(KEY_SETTINGS_PROJECT_LEVEL, snapshot.saveSettingsProjectLevel)

        val backend = backend(snapshot.saveSettingsProjectLevel)
        backend.setBoolean(KEY_ENABLED, snapshot.enabled)
        backend.setString(KEY_HOST, snapshot.host)
        backend.setInteger(KEY_PORT, snapshot.port)
        backend.setString(KEY_TRANSPORT, snapshot.transport.name)
    }

    fun saveEnabled(
        enabled: Boolean,
        saveSettingsProjectLevel: Boolean,
    ) {
        val backend = backend(saveSettingsProjectLevel)
        backend.setBoolean(KEY_ENABLED, enabled)
    }

    private fun backend(projectLevel: Boolean): StorageBackend =
        if (projectLevel) {
            StorageBackend(
                getBoolean = projectData::getBoolean,
                getString = projectData::getString,
                getInteger = projectData::getInteger,
                setBoolean = projectData::setBoolean,
                setString = projectData::setString,
                setInteger = projectData::setInteger,
            )
        } else {
            StorageBackend(
                getBoolean = preferences::getBoolean,
                getString = preferences::getString,
                getInteger = preferences::getInteger,
                setBoolean = preferences::setBoolean,
                setString = preferences::setString,
                setInteger = preferences::setInteger,
            )
        }
}

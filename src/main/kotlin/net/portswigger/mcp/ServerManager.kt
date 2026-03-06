package net.portswigger.mcp

import net.portswigger.mcp.core.McpSettingsSnapshot

sealed interface ServerState {
    data object Stopped : ServerState

    data object Starting : ServerState

    data object Running : ServerState

    data object Stopping : ServerState

    data class Failed(
        val message: String,
        val throwable: Throwable? = null,
    ) : ServerState
}

interface ServerManager {
    fun start(
        settings: McpSettingsSnapshot,
        onStateChanged: (ServerState) -> Unit,
    )

    fun stop(onStateChanged: (ServerState) -> Unit)

    fun shutdown()
}

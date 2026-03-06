package net.portswigger.mcp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import net.portswigger.mcp.core.McpOutputLogger
import net.portswigger.mcp.core.McpSettingsStore
import net.portswigger.mcp.ui.McpSettingsPanel

@Suppress("unused")
class ExtensionBase : BurpExtension {
    override fun initialize(api: MontoyaApi) {
        api.extension().setName("Awesome MCP")
        McpOutputLogger.configure(api.logging())

        val settingsStore =
            McpSettingsStore(
                projectData = api.persistence().extensionData(),
                preferences = api.persistence().preferences(),
            )
        val serverManager: ServerManager = AgentMcpServerManager(api)

        var currentSettings = settingsStore.load()
        val settingsPanel = McpSettingsPanel(currentSettings)

        settingsPanel.onApplySettings { snapshot ->
            currentSettings =
                currentSettings.copy(
                    host = snapshot.host,
                    port = snapshot.port,
                    transport = snapshot.transport,
                    saveSettingsProjectLevel = snapshot.saveSettingsProjectLevel,
                )
            settingsStore.saveSettings(currentSettings)
            settingsPanel.setSnapshot(currentSettings)

            if (currentSettings.enabled) {
                serverManager.start(currentSettings, settingsPanel::updateServerState)
            }
        }

        settingsPanel.onEnabledToggled { enabled ->
            if (enabled == currentSettings.enabled) return@onEnabledToggled

            currentSettings = currentSettings.copy(enabled = enabled)
            settingsStore.saveEnabled(
                enabled = enabled,
                saveSettingsProjectLevel = currentSettings.saveSettingsProjectLevel,
            )

            if (enabled) {
                serverManager.start(currentSettings, settingsPanel::updateServerState)
            } else {
                serverManager.stop(settingsPanel::updateServerState)
            }
        }

        api.userInterface().registerSuiteTab("Awesome MCP", settingsPanel.component)

        if (currentSettings.enabled) {
            serverManager.start(currentSettings, settingsPanel::updateServerState)
        } else {
            settingsPanel.updateServerState(ServerState.Stopped)
        }

        api.extension().registerUnloadingHandler {
            settingsPanel.dispose()
            McpOutputLogger.clear()
            serverManager.shutdown()
        }
    }
}

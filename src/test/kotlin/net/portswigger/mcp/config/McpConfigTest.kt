package net.portswigger.mcp.core

import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.persistence.Preferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpConfigTest {
    private lateinit var projectData: PersistedObject
    private lateinit var preferences: Preferences
    private lateinit var store: McpSettingsStore

    private val projectState = mutableMapOf<String, Any>()
    private val globalState = mutableMapOf<String, Any>()

    @BeforeEach
    fun setup() {
        projectData =
            mockk<PersistedObject>().apply {
                every { getBoolean(any()) } answers { projectState[firstArg()] as? Boolean }
                every { getString(any()) } answers { projectState[firstArg()] as? String }
                every { getInteger(any()) } answers { projectState[firstArg()] as? Int }

                every { setBoolean(any(), any()) } answers { projectState[firstArg()] = secondArg<Boolean>() }
                every { setString(any(), any()) } answers { projectState[firstArg()] = secondArg<String>() }
                every { setInteger(any(), any()) } answers { projectState[firstArg()] = secondArg<Int>() }
            }

        preferences =
            mockk<Preferences>().apply {
                every { getBoolean(any()) } answers { globalState[firstArg()] as? Boolean }
                every { getString(any()) } answers { globalState[firstArg()] as? String }
                every { getInteger(any()) } answers { globalState[firstArg()] as? Int }

                every { setBoolean(any(), any()) } answers { globalState[firstArg()] = secondArg<Boolean>() }
                every { setString(any(), any()) } answers { globalState[firstArg()] = secondArg<String>() }
                every { setInteger(any(), any()) } answers { globalState[firstArg()] = secondArg<Int>() }
            }

        store = McpSettingsStore(projectData = projectData, preferences = preferences)
    }

    @Test
    fun `load should return defaults with global scope by default`() {
        val loaded = store.load()

        assertTrue(loaded.enabled)
        assertEquals("127.0.0.1", loaded.host)
        assertEquals(26001, loaded.port)
        assertEquals(McpTransportMode.STREAMABLE_HTTP, loaded.transport)
        assertFalse(loaded.saveSettingsProjectLevel)
    }

    @Test
    fun `save settings should persist to global when project scope is disabled`() {
        val snapshot =
            McpSettingsSnapshot(
                enabled = true,
                host = "localhost",
                port = 8080,
                transport = McpTransportMode.STREAMABLE_HTTP,
                saveSettingsProjectLevel = false,
            )

        store.saveSettings(snapshot)

        verify { projectData.setBoolean("awesome_mcp.settings_project_level", false) }
        verify { preferences.setString("awesome_mcp.host", "localhost") }
        verify { preferences.setInteger("awesome_mcp.port", 8080) }
        verify { preferences.setString("awesome_mcp.transport", "STREAMABLE_HTTP") }
    }

    @Test
    fun `save settings should persist to project when project scope is enabled`() {
        val snapshot =
            McpSettingsSnapshot(
                enabled = false,
                host = "0.0.0.0",
                port = 31337,
                transport = McpTransportMode.SSE,
                saveSettingsProjectLevel = true,
            )

        store.saveSettings(snapshot)

        verify { projectData.setBoolean("awesome_mcp.settings_project_level", true) }
        verify { projectData.setString("awesome_mcp.host", "0.0.0.0") }
        verify { projectData.setInteger("awesome_mcp.port", 31337) }
        verify { projectData.setString("awesome_mcp.transport", "SSE") }
    }

    @Test
    fun `load should read selected backend and clamp values`() {
        projectState["awesome_mcp.settings_project_level"] = true
        projectState["awesome_mcp.enabled"] = false
        projectState["awesome_mcp.host"] = "project-host"
        projectState["awesome_mcp.port"] = 99_999
        projectState["awesome_mcp.transport"] = "invalid"

        val loaded = store.load()

        assertFalse(loaded.enabled)
        assertEquals("project-host", loaded.host)
        assertEquals(65535, loaded.port)
        assertEquals(McpTransportMode.STREAMABLE_HTTP, loaded.transport)
        assertTrue(loaded.saveSettingsProjectLevel)
    }

    @Test
    fun `save enabled should target selected backend`() {
        store.saveEnabled(enabled = false, saveSettingsProjectLevel = false)
        verify { preferences.setBoolean("awesome_mcp.enabled", false) }

        store.saveEnabled(enabled = true, saveSettingsProjectLevel = true)
        verify { projectData.setBoolean("awesome_mcp.enabled", true) }
    }
}

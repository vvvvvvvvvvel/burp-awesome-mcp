package net.portswigger.mcp.ui

import net.portswigger.mcp.core.McpSettingsSnapshot
import net.portswigger.mcp.core.McpTransportMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities

class McpSettingsPanelUiTest {
    @Test
    fun `endpoint label should update only after apply`() {
        val panel =
            McpSettingsPanel(
                McpSettingsSnapshot(
                    enabled = true,
                    host = "127.0.0.1",
                    port = 26001,
                    transport = McpTransportMode.STREAMABLE_HTTP,
                ),
            )

        val hostField = panel.readPrivateField<JTextField>("hostField")
        val applyButton = panel.readPrivateField<JButton>("applyButton")
        val endpointLabel = panel.readPrivateField<JLabel>("endpointUrlLabel")
        panel.onApplySettings { panel.setSnapshot(it) }

        assertEquals("http://127.0.0.1:26001/mcp", endpointLabel.text)
        assertFalse(applyButton.isEnabled)

        SwingUtilities.invokeAndWait {
            hostField.text = "localhost"
        }

        assertTrue(applyButton.isEnabled)
        assertEquals("http://127.0.0.1:26001/mcp", endpointLabel.text)

        SwingUtilities.invokeAndWait {
            applyButton.doClick()
        }

        assertFalse(applyButton.isEnabled)
        assertEquals("http://localhost:26001/mcp", endpointLabel.text)
    }

    @Test
    fun `toggle switch should not affect apply dirty state`() {
        val panel =
            McpSettingsPanel(
                McpSettingsSnapshot(
                    enabled = true,
                    host = "127.0.0.1",
                    port = 26001,
                    transport = McpTransportMode.SSE,
                ),
            )

        val applyButton = panel.readPrivateField<JButton>("applyButton")
        val enabledSwitch = panel.readPrivateField<javax.swing.JToggleButton>("enabledSwitch")
        var toggled: Boolean? = null
        panel.onEnabledToggled { toggled = it }

        assertFalse(applyButton.isEnabled)

        SwingUtilities.invokeAndWait {
            enabledSwitch.doClick()
        }

        assertFalse(applyButton.isEnabled)
        assertNotNull(toggled)
    }

    @Test
    fun `save scope checkbox should mark settings dirty`() {
        val panel =
            McpSettingsPanel(
                McpSettingsSnapshot(
                    enabled = true,
                    host = "127.0.0.1",
                    port = 26001,
                    transport = McpTransportMode.SSE,
                    saveSettingsProjectLevel = false,
                ),
            )

        val applyButton = panel.readPrivateField<JButton>("applyButton")
        val saveScopeCheckbox = panel.readPrivateField<JCheckBox>("saveScopeCheckbox")

        assertFalse(applyButton.isEnabled)

        SwingUtilities.invokeAndWait {
            saveScopeCheckbox.doClick()
        }

        assertTrue(applyButton.isEnabled)
    }
}

private inline fun <reified T> Any.readPrivateField(name: String): T {
    val field = this::class.java.getDeclaredField(name)
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as T
}

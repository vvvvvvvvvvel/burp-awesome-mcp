package net.portswigger.mcp.ui

import net.portswigger.mcp.ServerState
import net.portswigger.mcp.core.McpSettingsSnapshot
import net.portswigger.mcp.core.McpTransportMode
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.DocumentFilter

class McpSettingsPanel(
    initial: McpSettingsSnapshot,
) {
    private var appliedSnapshot: McpSettingsSnapshot = initial
    private var suppressEnabledEvents = false

    private val enabledSwitch =
        MiniSwitch().apply {
            isSelected = initial.enabled
            toolTipText = "Enable or disable MCP server"
        }

    private val stateLabel = JLabel("State: stopped")
    private val hostField = JTextField(initial.host, 24)
    private val portField = JTextField(initial.port.toString(), 8)
    private val transportCombo =
        JComboBox(TransportModeItem.entries.toTypedArray()).apply {
            selectedItem = TransportModeItem.fromMode(initial.transport)
        }
    private val saveScopeCheckbox = JCheckBox("Store settings in current project", initial.saveSettingsProjectLevel)

    private val endpointUrlLabel =
        JLabel().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }

    private val copyUrlButton = JButton("Copy URL")
    private val copyFeedbackLabel = JLabel(" ")
    private val applyButton = JButton("Apply")
    private val overviewBannerImage: BufferedImage? by lazy(LazyThreadSafetyMode.PUBLICATION) { loadOverviewBannerImage() }

    private var applySettingsHandler: ((McpSettingsSnapshot) -> Unit)? = null
    private var enabledToggleHandler: ((Boolean) -> Unit)? = null

    val component: JPanel =
        JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(buildHeaderPanel(), BorderLayout.NORTH)
            add(buildMainPanel(), BorderLayout.CENTER)
        }

    init {
        installPortFieldFilter()
        refreshEndpointUrlFromApplied()
        bindUiListeners()
        updateDirtyState()
    }

    fun onApplySettings(handler: (McpSettingsSnapshot) -> Unit) {
        applySettingsHandler = handler
    }

    fun onEnabledToggled(handler: (Boolean) -> Unit) {
        enabledToggleHandler = handler
    }

    fun setSnapshot(snapshot: McpSettingsSnapshot) {
        appliedSnapshot = snapshot

        suppressEnabledEvents = true
        try {
            enabledSwitch.isSelected = snapshot.enabled
        } finally {
            suppressEnabledEvents = false
        }

        hostField.text = snapshot.host
        portField.text = snapshot.port.toString()
        transportCombo.selectedItem = TransportModeItem.fromMode(snapshot.transport)
        saveScopeCheckbox.isSelected = snapshot.saveSettingsProjectLevel

        refreshEndpointUrlFromApplied()
        updateDirtyState()
    }

    fun updateServerState(state: ServerState) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { updateServerState(state) }
            return
        }
        when (state) {
            ServerState.Stopped -> {
                stateLabel.text = "State: stopped"
                stateLabel.foreground = defaultForeground()
            }

            ServerState.Starting -> {
                stateLabel.text = "State: starting"
                stateLabel.foreground = Color(196, 160, 80)
            }

            ServerState.Running -> {
                stateLabel.text = "State: running"
                stateLabel.foreground = Color(96, 186, 126)
            }

            ServerState.Stopping -> {
                stateLabel.text = "State: stopping"
                stateLabel.foreground = Color(196, 160, 80)
            }

            is ServerState.Failed -> {
                stateLabel.text = "State: failed (${state.message})"
                stateLabel.foreground = Color(224, 98, 98)
            }
        }
    }

    fun dispose() {
        applySettingsHandler = null
        enabledToggleHandler = null
    }

    private fun bindUiListeners() {
        enabledSwitch.addActionListener {
            if (suppressEnabledEvents) return@addActionListener
            enabledToggleHandler?.invoke(enabledSwitch.isSelected)
        }

        hostField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateDirtyState()

                override fun removeUpdate(e: DocumentEvent) = updateDirtyState()

                override fun changedUpdate(e: DocumentEvent) = updateDirtyState()
            },
        )

        portField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = updateDirtyState()

                override fun removeUpdate(e: DocumentEvent) = updateDirtyState()

                override fun changedUpdate(e: DocumentEvent) = updateDirtyState()
            },
        )

        transportCombo.addActionListener { updateDirtyState() }
        saveScopeCheckbox.addActionListener { updateDirtyState() }

        copyUrlButton.addActionListener {
            val url = inspectorEndpoint(appliedSnapshot)
            runCatching {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                copyFeedbackLabel.text = "Copied"
            }.onFailure {
                copyFeedbackLabel.text = "Copy failed"
            }
        }

        applyButton.addActionListener {
            applySettingsHandler?.invoke(snapshotFromUi())
        }
    }

    private fun buildHeaderPanel(): JPanel {
        val title =
            JLabel("Awesome MCP")
                .apply {
                    font = font.deriveFont(Font.BOLD, font.size2D * 1.15f)
                }.apply {
                    font = font.deriveFont(font.style, font.size2D * 1.05f)
                    alignmentX = 0f
                }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            add(title)
            add(Box.createVerticalStrut(2))
        }
    }

    private fun buildMainPanel(): JSplitPane =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeftPanel(), buildRightPanel()).apply {
            resizeWeight = 0.44
            dividerSize = 8
            border = BorderFactory.createEmptyBorder()
        }

    private fun buildLeftPanel(): JPanel =
        JPanel(BorderLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Overview"),
                    BorderFactory.createEmptyBorder(12, 14, 12, 14),
                )
            add(buildOverviewContent(), BorderLayout.NORTH)
            add(buildOverviewBannerPanel(), BorderLayout.CENTER)
            add(buildOverviewFooter(), BorderLayout.SOUTH)
        }

    private fun buildOverviewContent(): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val textSize = font.size2D * 1.05f
            add(Box.createVerticalStrut(6))

            val manifesto =
                listOf(
                    "Manual pentesting, amplified by an agent.",
                    "Burp remains the source of truth for traffic.",
                    "MCP gives the agent safe access to key actions.",
                    "Context survives across each validation step.",
                    "You make the decisions; the system speeds up routine work.",
                )
            manifesto.forEachIndexed { index, line ->
                add(
                    JLabel(line).apply {
                        font = font.deriveFont(textSize)
                        alignmentX = 0f
                    },
                )
                add(Box.createVerticalStrut(5))
            }
        }

    private fun buildOverviewFooter(): JComponent {
        val authorPrefix =
            JLabel("Author: ").apply {
                foreground = Color(136, 136, 136)
            }
        val authorLink =
            JLabel(AUTHOR_USERNAME).apply {
                foreground = Color(88, 160, 255)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = AUTHOR_URL
                font = font.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            openAuthorLink()
                        }
                    },
                )
            }

        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
            add(authorPrefix)
            add(authorLink)
        }
    }

    private fun buildOverviewBannerPanel(): JComponent {
        val image =
            overviewBannerImage ?: return JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
                add(
                    JLabel("Overview banner image is unavailable.").apply {
                        alignmentX = 0f
                    },
                    BorderLayout.NORTH,
                )
            }

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
            add(
                ScaledOverviewImagePanel(image).apply {
                    border = BorderFactory.createLineBorder(Color(88, 88, 88))
                },
                BorderLayout.CENTER,
            )
        }
    }

    private fun buildRightPanel(): JPanel {
        val stack =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(buildServerPanel())
                add(Box.createVerticalStrut(10))
                add(buildEndpointPanel())
            }

        return JPanel(BorderLayout()).apply {
            add(stack, BorderLayout.NORTH)
        }
    }

    private fun buildServerPanel(): JPanel =
        JPanel(GridBagLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("Server"),
                    BorderFactory.createEmptyBorder(6, 6, 6, 6),
                )

            val gbc =
                GridBagConstraints().apply {
                    insets = Insets(4, 4, 4, 4)
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.HORIZONTAL
                }

            gbc.gridx = 0
            gbc.gridy = 0
            gbc.weightx = 0.0
            add(JLabel("Enabled"), gbc)

            gbc.gridx = 1
            gbc.gridy = 0
            gbc.weightx = 0.0
            add(enabledSwitch, gbc)

            gbc.gridx = 2
            gbc.gridy = 0
            gbc.weightx = 1.0
            add(stateLabel, gbc)

            var row = 1
            addLabeledWideRow(this, gbc, row, "Host", hostField)
            row += 1
            addLabeledWideRow(this, gbc, row, "Port", portField)
            row += 1
            addLabeledWideRow(this, gbc, row, "Transport", transportCombo)
            row += 1
            addWideRow(this, gbc, row, saveScopeCheckbox)

            row += 1
            gbc.gridx = 0
            gbc.gridy = row
            gbc.gridwidth = 3
            gbc.weightx = 1.0
            gbc.insets = Insets(8, 4, 4, 4)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    add(applyButton)
                },
                gbc,
            )
        }

    private fun buildEndpointPanel(): JPanel =
        JPanel(GridBagLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder("MCP Endpoint"),
                    BorderFactory.createEmptyBorder(6, 6, 6, 6),
                )

            val gbc =
                GridBagConstraints().apply {
                    insets = Insets(4, 4, 4, 4)
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.HORIZONTAL
                }

            placeGridItem(
                panel = this,
                gbc = gbc,
                row = 0,
                col = 0,
                gridWidth = 1,
                weightX = 0.0,
                component = JLabel("URL"),
            )
            placeGridItem(
                panel = this,
                gbc = gbc,
                row = 0,
                col = 1,
                gridWidth = 1,
                weightX = 1.0,
                component = buildStaticField(endpointUrlLabel),
            )
            placeGridItem(
                panel = this,
                gbc = gbc,
                row = 0,
                col = 2,
                gridWidth = 1,
                weightX = 0.0,
                component = copyUrlButton,
            )
            addWideRow(this, gbc, row = 1, component = copyFeedbackLabel)
        }

    private fun buildStaticField(content: JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(110, 110, 110)),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8),
                )
            add(content, BorderLayout.CENTER)
        }

    private fun updateDirtyState() {
        val ui = snapshotFromUi()
        val dirty = settingsFingerprint(ui) != settingsFingerprint(appliedSnapshot)
        applyButton.isEnabled = dirty

        if (dirty) {
            applyButton.isOpaque = true
            applyButton.background = BURP_ORANGE
            applyButton.foreground = Color(24, 24, 24)
            applyButton.border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BURP_ORANGE_DARK),
                    BorderFactory.createEmptyBorder(5, 14, 5, 14),
                )
        } else {
            applyButton.isOpaque = false
            applyButton.background = UIManager.getColor("Button.background")
            applyButton.foreground = UIManager.getColor("Button.foreground")
            applyButton.border = UIManager.getBorder("Button.border")
        }
    }

    private fun snapshotFromUi(): McpSettingsSnapshot {
        val host = hostField.text.trim().ifEmpty { "127.0.0.1" }
        val parsedPort = portField.text.trim().toIntOrNull()
        val port = (parsedPort ?: DEFAULT_PORT).coerceIn(1, 65535)

        return McpSettingsSnapshot(
            enabled = enabledSwitch.isSelected,
            host = host,
            port = port,
            transport = (transportCombo.selectedItem as? TransportModeItem)?.mode ?: McpTransportMode.STREAMABLE_HTTP,
            saveSettingsProjectLevel = saveScopeCheckbox.isSelected,
        )
    }

    private fun refreshEndpointUrlFromApplied() {
        endpointUrlLabel.text = inspectorEndpoint(appliedSnapshot)
        copyFeedbackLabel.text = " "
    }

    private fun installPortFieldFilter() {
        (portField.document as? AbstractDocument)?.documentFilter = DigitsOnlyFilter(maxLength = 5)
    }

    private fun defaultForeground(): Color = JLabel().foreground

    private fun loadOverviewBannerImage(): BufferedImage? =
        McpSettingsPanel::class.java
            .getResourceAsStream(OVERVIEW_BANNER_RESOURCE_PATH)
            ?.use { stream ->
                runCatching { ImageIO.read(stream) }.getOrNull()
            }

    private fun openAuthorLink() {
        if (!Desktop.isDesktopSupported()) return
        runCatching { Desktop.getDesktop().browse(URI(AUTHOR_URL)) }
    }

    companion object {
        private const val OVERVIEW_BANNER_RESOURCE_PATH = "/ui/mcp-overview-banner.png"
        private const val AUTHOR_URL = "https://github.com/vvvvvvvvvvel"

        @Suppress("SpellCheckingInspection")
        private const val AUTHOR_USERNAME = "vvvvvvvvvvel"
    }
}

private data class SettingsFingerprint(
    val host: String,
    val port: Int,
    val transport: McpTransportMode,
    val saveSettingsProjectLevel: Boolean,
)

private fun settingsFingerprint(snapshot: McpSettingsSnapshot): SettingsFingerprint =
    SettingsFingerprint(
        host = snapshot.host,
        port = snapshot.port,
        transport = snapshot.transport,
        saveSettingsProjectLevel = snapshot.saveSettingsProjectLevel,
    )

@Suppress("HttpUrlsUsage")
internal fun inspectorEndpoint(settings: McpSettingsSnapshot): String {
    val host = normalizeCopyHost(settings.host)
    val normalizedHost = normalizeHostForUrl(host)
    val base = "http://$normalizedHost:${settings.port}"
    return if (settings.transport == McpTransportMode.STREAMABLE_HTTP) "$base/mcp" else base
}

private fun normalizeCopyHost(host: String): String {
    val trimmed = host.trim()
    return when {
        trimmed.isEmpty() -> "127.0.0.1"
        trimmed == "0.0.0.0" -> "127.0.0.1"
        trimmed == "::" || trimmed == "[::]" -> "127.0.0.1"
        else -> trimmed
    }
}

private fun normalizeHostForUrl(host: String): String {
    if (host.startsWith("[") && host.endsWith("]")) return host
    return if (host.contains(':')) "[$host]" else host
}

private enum class TransportModeItem(
    val mode: McpTransportMode,
    private val label: String,
) {
    SSE(McpTransportMode.SSE, "SSE"),
    STREAMABLE_HTTP(McpTransportMode.STREAMABLE_HTTP, "Streamable HTTP"),
    ;

    override fun toString(): String = label

    companion object {
        fun fromMode(mode: McpTransportMode): TransportModeItem = entries.firstOrNull { it.mode == mode } ?: STREAMABLE_HTTP
    }
}

private class DigitsOnlyFilter(
    private val maxLength: Int,
) : DocumentFilter() {
    override fun insertString(
        fb: FilterBypass,
        offset: Int,
        string: String?,
        attr: AttributeSet?,
    ) {
        if (string == null) return
        replace(fb, offset, 0, string, attr)
    }

    override fun replace(
        fb: FilterBypass,
        offset: Int,
        length: Int,
        text: String?,
        attrs: AttributeSet?,
    ) {
        val incoming = text.orEmpty()
        if (!incoming.all { it.isDigit() }) {
            return
        }

        val current = fb.document.getText(0, fb.document.length)
        val candidate =
            buildString {
                append(current.take(offset))
                append(incoming)
                append(current.drop(offset + length))
            }

        if (candidate.length > maxLength) {
            return
        }

        try {
            fb.replace(offset, length, incoming, attrs)
        } catch (_: BadLocationException) {
            // ignore invalid operation
        }
    }
}

private fun addLabeledWideRow(
    panel: JPanel,
    gbc: GridBagConstraints,
    row: Int,
    label: String,
    field: JComponent,
) {
    placeGridItem(
        panel = panel,
        gbc = gbc,
        row = row,
        col = 0,
        gridWidth = 1,
        weightX = 0.0,
        component = JLabel(label),
    )
    placeGridItem(
        panel = panel,
        gbc = gbc,
        row = row,
        col = 1,
        gridWidth = 2,
        weightX = 1.0,
        component = field,
    )
}

private fun addWideRow(
    panel: JPanel,
    gbc: GridBagConstraints,
    row: Int,
    component: JComponent,
) {
    placeGridItem(
        panel = panel,
        gbc = gbc,
        row = row,
        col = 1,
        gridWidth = 2,
        weightX = 1.0,
        component = component,
    )
}

private fun placeGridItem(
    panel: JPanel,
    gbc: GridBagConstraints,
    row: Int,
    col: Int,
    gridWidth: Int,
    weightX: Double,
    component: JComponent,
) {
    gbc.insets = Insets(4, 4, 4, 4)
    gbc.gridy = row
    gbc.gridx = col
    gbc.gridwidth = gridWidth
    gbc.weightx = weightX
    panel.add(component, gbc)
}

private class MiniSwitch : JToggleButton() {
    init {
        isBorderPainted = false
        isFocusPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(40, 22)
        minimumSize = preferredSize
        maximumSize = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val width = width - 1
        val height = height - 1
        val arc = height

        val trackColor = if (isSelected) Color(52, 132, 207) else Color(110, 110, 110)
        g2.color = trackColor
        g2.fillRoundRect(0, 0, width, height, arc, arc)

        g2.color = Color(26, 26, 26)
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(0, 0, width, height, arc, arc)

        val knobSize = height - 6
        val knobX = if (isSelected) width - knobSize - 3 else 3
        val knobY = 3

        g2.color = Color(244, 244, 244)
        g2.fillOval(knobX, knobY, knobSize, knobSize)
        g2.color = Color(58, 58, 58)
        g2.drawOval(knobX, knobY, knobSize, knobSize)

        g2.dispose()
    }
}

private class ScaledOverviewImagePanel(
    private val image: BufferedImage,
) : JComponent() {
    init {
        isOpaque = true
    }

    override fun getPreferredSize(): Dimension = Dimension(560, 360)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.color = background
            g2.fillRect(0, 0, width, height)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val availableWidth = (width - insets.left - insets.right).coerceAtLeast(1)
            val availableHeight = (height - insets.top - insets.bottom).coerceAtLeast(1)
            val scale = minOf(availableWidth.toDouble() / image.width, availableHeight.toDouble() / image.height)

            val drawWidth = (image.width * scale).toInt().coerceAtLeast(1)
            val drawHeight = (image.height * scale).toInt().coerceAtLeast(1)
            val drawX = insets.left + (availableWidth - drawWidth) / 2
            val drawY = insets.top + (availableHeight - drawHeight) / 2

            g2.drawImage(image, drawX, drawY, drawWidth, drawHeight, null)
        } finally {
            g2.dispose()
        }
    }
}

private const val DEFAULT_PORT = 26001
private val BURP_ORANGE = Color(224, 129, 46)
private val BURP_ORANGE_DARK = Color(197, 103, 21)

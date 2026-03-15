package com.androidstudio.mcpserver.server

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.table.DefaultTableCellRenderer

class McpToolWindowPanel : JPanel(BorderLayout()), Disposable {

    private val statusIcon = JBLabel()
    private val statusText = JBLabel()
    private val urlLabel = JBLabel()

    private val cursorStatusIcon = JBLabel()
    private val cursorStatusLabel = JBLabel()
    private val cursorActionButton = JButton()
    private val copyJsonButton = JButton("Copy MCP Config JSON")

    private val footerLabel = JBLabel()

    private val restartButton = JButton("Restart Server")
    private val stopButton = JButton("Stop Server")

    private val toolNames = McpServerManager.TOOL_REGISTRY.map { it.name }
    private val tableModel = ToolTableModel(toolNames)
    private val toolTable = JBTable(tableModel)

    private val startTimeMs = System.currentTimeMillis()
    private val uptimeTimer: Timer

    private val stateListener = ServerStateListener { state ->
        ApplicationManager.getApplication().invokeLater { updateServerState(state) }
    }

    private val metricsListener = ToolMetricsListener { toolName ->
        tableModel.updateRow(toolName)
        updateFooter()
    }

    init {
        border = JBUI.Borders.empty(6)
        buildUI()
        wireActions()

        val manager = McpServerManager.getInstance()
        manager.addStateListener(stateListener)
        ToolMetricsService.addListener(metricsListener)
        updateServerState(manager.getState())
        refreshCursorStatus()

        uptimeTimer = Timer(60_000) { updateFooter() }
        uptimeTimer.isRepeats = true
        uptimeTimer.start()
    }

    private fun buildUI() {
        val mainPanel = JPanel()
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)

        mainPanel.add(buildHeaderPanel())
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
        mainPanel.add(buildClientConfigPanel())
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
        mainPanel.add(buildTablePanel())
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        mainPanel.add(buildFooterPanel())
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(6)))
        mainPanel.add(buildActionPanel())

        add(JBScrollPane(mainPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)
    }

    // ---- Header: server status + URL ----

    private fun buildHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.border = JBUI.Borders.empty(4, 4, 4, 4)

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        statusIcon.icon = AllIcons.RunConfigurations.TestPassed
        statusText.font = statusText.font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
        left.add(statusIcon)
        left.add(statusText)

        urlLabel.foreground = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY)
        urlLabel.font = urlLabel.font.deriveFont(JBUI.scale(12).toFloat())
        left.add(Box.createHorizontalStrut(JBUI.scale(8)))
        left.add(urlLabel)

        panel.add(left, BorderLayout.CENTER)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    // ---- Client Configuration ----

    private fun buildClientConfigPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0),
            JBUI.Borders.empty(6, 4, 6, 4)
        )

        val titleLabel = JBLabel("Client Configuration")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val cursorRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        cursorRow.alignmentX = LEFT_ALIGNMENT
        cursorRow.add(JBLabel("Cursor:"))
        cursorRow.add(cursorStatusIcon)
        cursorRow.add(cursorStatusLabel)
        cursorRow.add(cursorActionButton)
        panel.add(cursorRow)

        panel.add(Box.createVerticalStrut(JBUI.scale(4)))

        val copyRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        copyRow.alignmentX = Component.LEFT_ALIGNMENT
        copyRow.add(copyJsonButton)
        panel.add(copyRow)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    // ---- Tool metrics table ----

    private fun buildTablePanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.alignmentX = Component.LEFT_ALIGNMENT

        val titleLabel = JBLabel("Registered Tools (${toolNames.size})")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
        titleLabel.border = JBUI.Borders.empty(0, 4, 4, 0)
        panel.add(titleLabel, BorderLayout.NORTH)

        configureTable()
        val scrollPane = JBScrollPane(toolTable)
        scrollPane.preferredSize = Dimension(0, JBUI.scale(260))
        panel.add(scrollPane, BorderLayout.CENTER)

        panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(320))
        return panel
    }

    private fun configureTable() {
        toolTable.setShowGrid(false)
        toolTable.intercellSpacing = Dimension(0, 0)
        toolTable.rowHeight = JBUI.scale(24)
        toolTable.isStriped = true
        toolTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        toolTable.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS

        toolTable.columnModel.getColumn(ToolTableModel.COL_TOOL).preferredWidth = JBUI.scale(140)
        toolTable.columnModel.getColumn(ToolTableModel.COL_CALLS).preferredWidth = JBUI.scale(50)
        toolTable.columnModel.getColumn(ToolTableModel.COL_TOKENS).preferredWidth = JBUI.scale(60)
        toolTable.columnModel.getColumn(ToolTableModel.COL_LAST_MS).preferredWidth = JBUI.scale(60)
        toolTable.columnModel.getColumn(ToolTableModel.COL_STATUS).preferredWidth = JBUI.scale(70)

        val rightRenderer = object : DefaultTableCellRenderer() {
            init { horizontalAlignment = SwingConstants.RIGHT }
        }
        toolTable.columnModel.getColumn(ToolTableModel.COL_CALLS).cellRenderer = rightRenderer
        toolTable.columnModel.getColumn(ToolTableModel.COL_TOKENS).cellRenderer = rightRenderer
        toolTable.columnModel.getColumn(ToolTableModel.COL_LAST_MS).cellRenderer = rightRenderer

        toolTable.columnModel.getColumn(ToolTableModel.COL_STATUS).cellRenderer = StatusCellRenderer()
    }

    // ---- Footer stats ----

    private fun buildFooterPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
        panel.alignmentX = Component.LEFT_ALIGNMENT
        footerLabel.foreground = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY)
        footerLabel.font = footerLabel.font.deriveFont(JBUI.scale(11).toFloat())
        panel.add(footerLabel)
        updateFooter()
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    // ---- Action buttons ----

    private fun buildActionPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(restartButton)
        panel.add(stopButton)
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    // ---- Actions ----

    private fun wireActions() {
        cursorActionButton.addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = ClientAutoConfigurator.configureCursor()
                ApplicationManager.getApplication().invokeLater {
                    ClientAutoConfigurator.showResultNotification(result)
                    refreshCursorStatus()
                }
            }
        }

        copyJsonButton.addActionListener {
            val json = ClientAutoConfigurator.getMcpConfigJson()
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(json), null)
            val originalText = copyJsonButton.text
            copyJsonButton.text = "Copied!"
            copyJsonButton.isEnabled = false
            Timer(1500) {
                copyJsonButton.text = originalText
                copyJsonButton.isEnabled = true
            }.apply {
                isRepeats = false
                start()
            }
        }

        restartButton.addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                McpServerManager.getInstance().restart()
            }
        }

        stopButton.addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                McpServerManager.getInstance().stop()
            }
        }
    }

    // ---- State updates ----

    private fun updateServerState(state: ServerState) {
        val manager = McpServerManager.getInstance()

        when (state) {
            ServerState.RUNNING -> {
                statusIcon.icon = AllIcons.RunConfigurations.TestPassed
                statusText.text = "Running"
                urlLabel.text = manager.getUrl()
                setButtonsEnabled(true)
            }
            ServerState.STARTING -> {
                statusIcon.icon = AnimatedIcon.Default()
                statusText.text = "Starting..."
                urlLabel.text = "..."
                setButtonsEnabled(false)
            }
            ServerState.STOPPED -> {
                statusIcon.icon = AllIcons.RunConfigurations.TestIgnored
                statusText.text = "Stopped"
                urlLabel.text = ""
                setButtonsEnabled(false)
            }
            ServerState.ERROR -> {
                statusIcon.icon = AllIcons.RunConfigurations.TestError
                statusText.text = "Error: ${manager.getErrorMessage() ?: "unknown"}"
                urlLabel.text = ""
                setButtonsEnabled(false)
            }
        }

        refreshCursorStatus()
    }

    private fun refreshCursorStatus() {
        val status = ClientAutoConfigurator.isCursorConfigured()
        if (status.configured) {
            cursorStatusIcon.icon = AllIcons.RunConfigurations.TestPassed
            val urlMatch = status.configuredUrl == McpServerManager.getInstance().getUrl()
            cursorStatusLabel.text = if (urlMatch) {
                "Configured"
            } else {
                "Configured (URL mismatch: ${status.configuredUrl})"
            }
            cursorStatusLabel.foreground = if (urlMatch) {
                JBColor.namedColor("Label.foreground", JBColor.foreground())
            } else {
                JBColor.ORANGE
            }
            cursorActionButton.text = "Reconfigure"
        } else {
            cursorStatusIcon.icon = AllIcons.RunConfigurations.TestIgnored
            cursorStatusLabel.text = "Not configured"
            cursorStatusLabel.foreground = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY)
            cursorActionButton.text = "Configure Cursor"
        }
    }

    private fun setButtonsEnabled(running: Boolean) {
        cursorActionButton.isEnabled = running
        copyJsonButton.isEnabled = running
        stopButton.isEnabled = running
        restartButton.isEnabled = true
    }

    private fun updateFooter() {
        val totalCalls = ToolMetricsService.getTotalInvocations()
        val totalTokens = ToolMetricsService.getTotalTokens()
        val uptimeMs = System.currentTimeMillis() - startTimeMs
        val uptimeStr = formatUptime(uptimeMs)
        val tokensStr = formatTokensCompact(totalTokens)
        footerLabel.text = "Total: $totalCalls calls  |  $tokensStr tokens  |  Uptime: $uptimeStr"
    }

    private fun formatUptime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun formatTokensCompact(tokens: Long): String = when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }

    override fun dispose() {
        uptimeTimer.stop()
        McpServerManager.getInstance().removeStateListener(stateListener)
        ToolMetricsService.removeListener(metricsListener)
    }

    // ---- Status column cell renderer with animated icon ----

    private class StatusCellRenderer : DefaultTableCellRenderer() {
        private val executingIcon = AnimatedIcon.Default()
        private val errorIcon = AllIcons.General.Error

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = super.getTableCellRendererComponent(
                table, "", isSelected, hasFocus, row, column
            ) as JLabel
            val status = value as? String ?: ""
            when (status) {
                "EXECUTING" -> {
                    label.icon = executingIcon
                    label.text = "Running"
                    label.foreground = JBColor.namedColor("Label.foreground", JBColor.foreground())
                }
                "ERROR" -> {
                    label.icon = errorIcon
                    label.text = "Error"
                    label.foreground = JBColor.RED
                }
                else -> {
                    label.icon = null
                    label.text = ""
                }
            }
            label.horizontalAlignment = SwingConstants.LEFT
            return label
        }
    }
}

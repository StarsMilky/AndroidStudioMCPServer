package com.androidstudio.mcpserver.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class McpToolWindowPanel : JPanel(BorderLayout()), Disposable {

    private val statusDot = JBLabel()
    private val statusText = JBLabel()
    private val urlLabel = JBLabel()
    private val copyUrlButton = JButton("Copy URL")
    private val configureCursorButton = JButton("Configure Cursor")
    private val configureClaudeButton = JButton("Configure Claude Desktop")
    private val restartButton = JButton("Restart Server")
    private val stopButton = JButton("Stop Server")
    private val toolListModel = DefaultListModel<String>()

    private val stateListener = ServerStateListener { state ->
        ApplicationManager.getApplication().invokeLater { updateUI(state) }
    }

    init {
        border = JBUI.Borders.empty(8)
        buildUI()
        wireActions()

        val manager = McpServerManager.getInstance()
        manager.addStateListener(stateListener)
        updateUI(manager.getState())
    }

    private fun buildUI() {
        val content = Box.createVerticalBox()

        content.add(buildStatusSection())
        content.add(Box.createVerticalStrut(12))
        content.add(buildAutoConfigSection())
        content.add(Box.createVerticalStrut(12))
        content.add(buildToolListSection())
        content.add(Box.createVerticalStrut(12))
        content.add(buildActionSection())

        add(JBScrollPane(content).apply {
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)
    }

    private fun buildStatusSection(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.alignmentX = LEFT_ALIGNMENT
        panel.border = JBUI.Borders.empty(4)
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 4)
        }

        statusDot.font = statusDot.font.deriveFont(14f)
        statusText.font = statusText.font.deriveFont(Font.BOLD)

        gbc.gridx = 0; gbc.gridy = 0
        panel.add(JBLabel("Status:"), gbc)
        gbc.gridx = 1
        panel.add(statusDot, gbc)
        gbc.gridx = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(statusText, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        panel.add(JBLabel("URL:"), gbc)
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        urlLabel.foreground = JBColor.BLUE
        urlLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        panel.add(urlLabel, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        panel.add(copyUrlButton, gbc)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun buildAutoConfigSection(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Auto Configure"),
                JBUI.Borders.empty(4)
            )
        }

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        buttonRow.alignmentX = LEFT_ALIGNMENT
        buttonRow.add(configureCursorButton)
        buttonRow.add(configureClaudeButton)
        panel.add(buttonRow)

        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun buildToolListSection(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder("Registered Tools (${McpServerManager.TOOL_REGISTRY.size})")
        }

        for (tool in McpServerManager.TOOL_REGISTRY) {
            toolListModel.addElement("${tool.name}  —  ${tool.description}")
        }

        val list = JBList(toolListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 12
            cellRenderer = ToolListCellRenderer()
        }

        panel.add(JBScrollPane(list).apply {
            preferredSize = Dimension(0, 240)
        }, BorderLayout.CENTER)

        panel.maximumSize = Dimension(Int.MAX_VALUE, 300)
        return panel
    }

    private fun buildActionSection(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        panel.alignmentX = LEFT_ALIGNMENT
        panel.add(restartButton)
        panel.add(stopButton)
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        return panel
    }

    private fun wireActions() {
        copyUrlButton.addActionListener {
            val url = McpServerManager.getInstance().getUrl()
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
        }

        configureCursorButton.addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = ClientAutoConfigurator.configureCursor()
                ApplicationManager.getApplication().invokeLater {
                    ClientAutoConfigurator.showResultNotification(result)
                }
            }
        }

        configureClaudeButton.addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = ClientAutoConfigurator.configureClaude()
                ApplicationManager.getApplication().invokeLater {
                    ClientAutoConfigurator.showResultNotification(result)
                }
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

        urlLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                val url = McpServerManager.getInstance().getUrl()
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
            }
        })
    }

    private fun updateUI(state: ServerState) {
        val manager = McpServerManager.getInstance()

        when (state) {
            ServerState.RUNNING -> {
                statusDot.text = "\u25CF"
                statusDot.foreground = JBColor(Color(0x59A869), Color(0x59A869))
                statusText.text = "Running"
                urlLabel.text = manager.getUrl()
                setButtonsEnabled(running = true)
            }
            ServerState.STARTING -> {
                statusDot.text = "\u25CF"
                statusDot.foreground = JBColor.YELLOW
                statusText.text = "Starting..."
                urlLabel.text = "..."
                setButtonsEnabled(running = false)
            }
            ServerState.STOPPED -> {
                statusDot.text = "\u25CF"
                statusDot.foreground = JBColor.GRAY
                statusText.text = "Stopped"
                urlLabel.text = "—"
                setButtonsEnabled(running = false)
            }
            ServerState.ERROR -> {
                statusDot.text = "\u25CF"
                statusDot.foreground = JBColor.RED
                statusText.text = "Error: ${manager.getErrorMessage() ?: "unknown"}"
                urlLabel.text = "—"
                setButtonsEnabled(running = false)
            }
        }
    }

    private fun setButtonsEnabled(running: Boolean) {
        copyUrlButton.isEnabled = running
        configureCursorButton.isEnabled = running
        configureClaudeButton.isEnabled = running
        stopButton.isEnabled = running
        restartButton.isEnabled = true
    }

    override fun dispose() {
        McpServerManager.getInstance().removeStateListener(stateListener)
    }

    private class ToolListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            border = JBUI.Borders.empty(2, 8)
            font = font.deriveFont(12f)
            return comp
        }
    }
}

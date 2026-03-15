package com.codeintel.mcpserver.server

import javax.swing.table.AbstractTableModel

class ToolTableModel(private val toolNames: List<String>) : AbstractTableModel() {

    companion object {
        val COLUMNS = arrayOf("Tool", "Calls", "Tokens", "Last (ms)", "Status")
        const val COL_TOOL = 0
        const val COL_CALLS = 1
        const val COL_TOKENS = 2
        const val COL_LAST_MS = 3
        const val COL_STATUS = 4
    }

    override fun getRowCount(): Int = toolNames.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_TOOL -> String::class.java
        COL_CALLS -> Int::class.javaObjectType
        COL_TOKENS -> String::class.java
        COL_LAST_MS -> Long::class.javaObjectType
        COL_STATUS -> String::class.java
        else -> Any::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val toolName = toolNames[rowIndex]
        val record = ToolMetricsService.getRecord(toolName)

        return when (columnIndex) {
            COL_TOOL -> toolName
            COL_CALLS -> record?.invocations?.get() ?: 0
            COL_TOKENS -> formatTokens(record?.totalTokens?.get() ?: 0L)
            COL_LAST_MS -> record?.lastDurationMs?.get() ?: 0L
            COL_STATUS -> when {
                record?.isExecuting?.get() == true -> "EXECUTING"
                record?.lastError?.get() != null -> "ERROR"
                else -> ""
            }
            else -> ""
        }
    }

    fun updateRow(toolName: String) {
        val idx = toolNames.indexOf(toolName)
        if (idx >= 0) {
            fireTableRowsUpdated(idx, idx)
        }
    }

    fun updateAll() {
        fireTableDataChanged()
    }

    private fun formatTokens(tokens: Long): String = when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }
}

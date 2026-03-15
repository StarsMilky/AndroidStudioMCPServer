package com.codeintel.mcpserver.server

import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ToolCallRecord(
    val toolName: String,
    val invocations: AtomicInteger = AtomicInteger(0),
    val totalTokens: AtomicLong = AtomicLong(0),
    val lastTokens: AtomicInteger = AtomicInteger(0),
    val lastDurationMs: AtomicLong = AtomicLong(0),
    val isExecuting: AtomicBoolean = AtomicBoolean(false),
    val lastError: AtomicReference<String?> = AtomicReference(null)
)

fun interface ToolMetricsListener {
    fun onMetricsUpdated(toolName: String)
}

object ToolMetricsService {

    private val records = ConcurrentHashMap<String, ToolCallRecord>()
    private val listeners = CopyOnWriteArrayList<ToolMetricsListener>()
    private val startTimes = ConcurrentHashMap<String, Long>()

    fun markStart(toolName: String) {
        val record = records.computeIfAbsent(toolName) { ToolCallRecord(it) }
        record.isExecuting.set(true)
        record.lastError.set(null)
        startTimes[toolName] = System.currentTimeMillis()
        notifyListeners(toolName)
    }

    fun markComplete(toolName: String, responseJson: String, durationMs: Long, isError: Boolean) {
        val record = records.computeIfAbsent(toolName) { ToolCallRecord(it) }
        record.isExecuting.set(false)
        record.invocations.incrementAndGet()
        record.lastDurationMs.set(durationMs)
        startTimes.remove(toolName)

        val tokens = estimateTokens(responseJson)
        record.lastTokens.set(tokens)
        record.totalTokens.addAndGet(tokens.toLong())

        if (isError) {
            record.lastError.set(responseJson.take(200))
        }
        notifyListeners(toolName)
    }

    fun getRecord(toolName: String): ToolCallRecord? = records[toolName]

    fun getAllRecords(): Map<String, ToolCallRecord> = records.toMap()

    fun getTotalInvocations(): Int = records.values.sumOf { it.invocations.get() }

    fun getTotalTokens(): Long = records.values.sumOf { it.totalTokens.get() }

    fun addListener(listener: ToolMetricsListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ToolMetricsListener) {
        listeners.remove(listener)
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun notifyListeners(toolName: String) {
        ApplicationManager.getApplication().invokeLater {
            for (listener in listeners) {
                try {
                    listener.onMetricsUpdated(toolName)
                } catch (_: Exception) {
                }
            }
        }
    }
}

package com.xjbcode.espwatch.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Application-wide logging system with UI display support
 */
object AppLogger {
    private const val TAG = "ESPWatch"
    private const val MAX_LOG_ENTRIES = 500
    private const val LOG_FILE_MAX_SIZE = 5 * 1024 * 1024 // 5MB

    enum class Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                .format(Date(timestamp))
            val levelStr = level.name.padEnd(5)
            return "[$time] $levelStr/$tag: $message"
        }
    }

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private var logFile: File? = null
    private var isFileLoggingEnabled = false

    fun init(context: Context, enableFileLogging: Boolean = true) {
        if (enableFileLogging) {
            isFileLoggingEnabled = true
            val logDir = File(context.filesDir, "logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            logFile = File(logDir, "app.log")
            rotateLogFileIfNeeded()
        }
    }

    fun v(tag: String, message: String) {
        log(Level.VERBOSE, tag, message)
    }

    fun d(tag: String, message: String) {
        log(Level.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
    }

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )

        // Add to queue
        logQueue.offer(entry)

        // Update StateFlow (limit size)
        val current = _logEntries.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_LOG_ENTRIES) {
            current.removeAt(0)
        }
        _logEntries.value = current

        // Android Log
        when (level) {
            Level.VERBOSE -> Log.v(tag, message, throwable)
            Level.DEBUG -> Log.d(tag, message, throwable)
            Level.INFO -> Log.i(tag, message, throwable)
            Level.WARN -> Log.w(tag, message, throwable)
            Level.ERROR -> Log.e(tag, message, throwable)
        }

        // File logging
        if (isFileLoggingEnabled) {
            writeToFile(entry)
        }
    }

    private fun writeToFile(entry: LogEntry) {
        try {
            logFile?.let { file ->
                if (file.length() > LOG_FILE_MAX_SIZE) {
                    rotateLogFileIfNeeded()
                }
                file.appendText(entry.format() + "\n")
                entry.throwable?.let { throwable ->
                    file.appendText(Log.getStackTraceString(throwable) + "\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to log file", e)
        }
    }

    private fun rotateLogFileIfNeeded() {
        logFile?.let { file ->
            if (file.exists() && file.length() > LOG_FILE_MAX_SIZE) {
                val backupFile = File(file.parent, "app.log.old")
                if (backupFile.exists()) {
                    backupFile.delete()
                }
                file.renameTo(backupFile)
            }
        }
    }

    fun clearLogs() {
        _logEntries.value = emptyList()
        logQueue.clear()
    }

    fun getLogFilePath(): String? {
        return logFile?.absolutePath
    }

    fun exportLogs(): String {
        return _logEntries.value.joinToString("\n") { it.format() }
    }

    fun getLogsForLevel(level: Level): List<LogEntry> {
        return _logEntries.value.filter { it.level.ordinal >= level.ordinal }
    }
}

package com.example.ipotech

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Comprehensive system logging for industrial monitoring and debugging
 */
object SystemLogger {
    
    private const val TAG = "SystemLogger"
    private const val MAX_LOG_QUEUE_SIZE = 100
    private const val BATCH_UPLOAD_SIZE = 20
    
    // Log levels
    enum class LogLevel(val priority: Int) {
        VERBOSE(0),
        DEBUG(1),
        INFO(2),
        WARNING(3),
        ERROR(4),
        CRITICAL(5)
    }
    
    // Log categories
    enum class LogCategory(val displayName: String) {
        SYSTEM("System"),
        NETWORK("Network"),
        DATABASE("Database"),
        USER_ACTION("User Action"),
        DEVICE_CONTROL("Device Control"),
        SCHEDULER("Scheduler"),
        TEMPERATURE("Temperature"),
        ERROR_RECOVERY("Error Recovery"),
        CONFIGURATION("Configuration"),
        PERFORMANCE("Performance")
    }
    
    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val category: LogCategory,
        val message: String,
        val details: Map<String, Any> = emptyMap(),
        val deviceInfo: Map<String, String> = emptyMap(),
        val stackTrace: String? = null
    )
    
    // In-memory queue for batching
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private var isUploading = false
    
    // Device information cache
    private var deviceInfoCache: Map<String, String> = emptyMap()
    
    /**
     * Initialize SystemLogger with context
     */
    fun initialize(context: Context) {
        deviceInfoCache = collectDeviceInfo(context)
        Log.i(TAG, "SystemLogger initialized")
        logInfo(LogCategory.SYSTEM, "SystemLogger initialized", mapOf("version" to "1.0.0"))
    }
    
    /**
     * Log verbose message
     */
    fun logVerbose(category: LogCategory, message: String, details: Map<String, Any> = emptyMap()) {
        addLog(LogLevel.VERBOSE, category, message, details)
    }
    
    /**
     * Log debug message
     */
    fun logDebug(category: LogCategory, message: String, details: Map<String, Any> = emptyMap()) {
        addLog(LogLevel.DEBUG, category, message, details)
    }
    
    /**
     * Log info message
     */
    fun logInfo(category: LogCategory, message: String, details: Map<String, Any> = emptyMap()) {
        addLog(LogLevel.INFO, category, message, details)
    }
    
    /**
     * Log warning message
     */
    fun logWarning(category: LogCategory, message: String, details: Map<String, Any> = emptyMap()) {
        addLog(LogLevel.WARNING, category, message, details)
    }
    
    /**
     * Log error message
     */
    fun logError(category: LogCategory, message: String, details: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        addLog(LogLevel.ERROR, category, message, details, throwable)
    }
    
    /**
     * Log critical error message
     */
    fun logCritical(category: LogCategory, message: String, details: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        addLog(LogLevel.CRITICAL, category, message, details, throwable)
    }
    
    /**
     * Log user action
     */
    fun logUserAction(action: String, details: Map<String, Any> = emptyMap()) {
        logInfo(LogCategory.USER_ACTION, action, details)
    }
    
    /**
     * Log device control action
     */
    fun logDeviceControl(device: String, action: String, details: Map<String, Any> = emptyMap()) {
        logInfo(LogCategory.DEVICE_CONTROL, "$device: $action", details + mapOf("device" to device))
    }
    
    /**
     * Log network operation
     */
    fun logNetwork(operation: String, success: Boolean, details: Map<String, Any> = emptyMap()) {
        val level = if (success) LogLevel.INFO else LogLevel.WARNING
        val message = "Network: $operation - ${if (success) "SUCCESS" else "FAILED"}"
        addLog(level, LogCategory.NETWORK, message, details + mapOf("operation" to operation, "success" to success))
    }
    
    /**
     * Log database operation
     */
    fun logDatabase(operation: String, path: String, success: Boolean, details: Map<String, Any> = emptyMap()) {
        val level = if (success) LogLevel.DEBUG else LogLevel.ERROR
        val message = "DB: $operation on $path - ${if (success) "SUCCESS" else "FAILED"}"
        addLog(level, LogCategory.DATABASE, message, details + mapOf("operation" to operation, "path" to path, "success" to success))
    }
    
    /**
     * Log performance metric
     */
    fun logPerformance(operation: String, durationMs: Long, details: Map<String, Any> = emptyMap()) {
        val level = if (durationMs > 5000) LogLevel.WARNING else LogLevel.DEBUG
        logInfo(LogCategory.PERFORMANCE, "Performance: $operation took ${durationMs}ms", details + mapOf("operation" to operation, "duration_ms" to durationMs))
    }
    
    /**
     * Log temperature reading
     */
    fun logTemperature(temperature: Double, status: String, details: Map<String, Any> = emptyMap()) {
        logDebug(LogCategory.TEMPERATURE, "Temperature: ${temperature}°C - $status", details + mapOf("temperature" to temperature, "status" to status))
    }
    
    /**
     * Log scheduler event
     */
    fun logScheduler(event: String, details: Map<String, Any> = emptyMap()) {
        logInfo(LogCategory.SCHEDULER, "Scheduler: $event", details + mapOf("event" to event))
    }
    
    /**
     * Log error recovery event
     */
    fun logErrorRecovery(operation: String, attempt: Int, maxAttempts: Int, success: Boolean, details: Map<String, Any> = emptyMap()) {
        val level = if (success) LogLevel.INFO else LogLevel.WARNING
        val message = "Error Recovery: $operation - Attempt $attempt/$maxAttempts - ${if (success) "SUCCESS" else "RETRYING"}"
        addLog(level, LogCategory.ERROR_RECOVERY, message, details + mapOf("operation" to operation, "attempt" to attempt, "max_attempts" to maxAttempts, "success" to success))
    }
    
    /**
     * Log configuration change
     */
    fun logConfiguration(setting: String, oldValue: Any?, newValue: Any?, details: Map<String, Any> = emptyMap()) {
        logInfo(LogCategory.CONFIGURATION, "Config: $setting changed from $oldValue to $newValue", details + mapOf("setting" to setting, "old_value" to (oldValue ?: "null"), "new_value" to newValue) as Map<String, Any>)
    }
    
    /**
     * Add log entry to queue and upload
     */
    private fun addLog(level: LogLevel, category: LogCategory, message: String, details: Map<String, Any> = emptyMap(), throwable: Throwable? = null) {
        val logEntry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            message = message,
            details = details,
            deviceInfo = deviceInfoCache,
            stackTrace = throwable?.let { getStackTrace(it) }
        )
        
        // Add to Android log
        when (level) {
            LogLevel.VERBOSE -> Log.v(TAG, formatLogMessage(logEntry))
            LogLevel.DEBUG -> Log.d(TAG, formatLogMessage(logEntry))
            LogLevel.INFO -> Log.i(TAG, formatLogMessage(logEntry))
            LogLevel.WARNING -> Log.w(TAG, formatLogMessage(logEntry))
            LogLevel.ERROR -> Log.e(TAG, formatLogMessage(logEntry))
            LogLevel.CRITICAL -> Log.e(TAG, "CRITICAL: ${formatLogMessage(logEntry)}")
        }
        
        // Add to queue for Firebase upload
        logQueue.offer(logEntry)
        
        // Maintain queue size
        while (logQueue.size > MAX_LOG_QUEUE_SIZE) {
            logQueue.poll()
        }
        
        // Trigger upload for critical errors
        if (level == LogLevel.CRITICAL) {
            uploadLogs()
        }
    }
    
    /**
     * Upload logs to Firebase in batches
     */
    fun uploadLogs() {
        if (isUploading || logQueue.isEmpty()) {
            return
        }
        
        isUploading = true
        
        Thread {
            try {
                val batch = mutableListOf<LogEntry>()
                
                // Collect batch
                repeat(BATCH_UPLOAD_SIZE) {
                    val log = logQueue.poll()
                    if (log != null) {
                        batch.add(log)
                    } else {
                        return@repeat
                    }
                }
                
                if (batch.isEmpty()) {
                    isUploading = false
                    return@Thread
                }
                
                // Upload to Firebase
                val database = FirebaseDatabase.getInstance(ConfigManager.getDatabaseUrl())
                
                batch.forEach { log ->
                    val logData = mapOf(
                        "timestamp" to log.timestamp,
                        "level" to log.level.name,
                        "category" to log.category.displayName,
                        "message" to log.message,
                        "details" to log.details,
                        "device_info" to log.deviceInfo,
                        "stack_trace" to (log.stackTrace ?: "")
                    )
                    
                    database.reference.child("system_logs").push().setValue(logData)
                        .addOnSuccessListener {
                            Log.d(TAG, "Log uploaded successfully")
                        }
                        .addOnFailureListener { error ->
                            Log.e(TAG, "Failed to upload log: ${error.message}")
                            // Re-add to queue for retry
                            logQueue.offer(log)
                        }
                }
                
                Log.d(TAG, "Uploaded ${batch.size} logs to Firebase")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading logs: ${e.message}")
            } finally {
                isUploading = false
            }
        }.start()
    }
    
    /**
     * Get current log queue size
     */
    fun getQueueSize(): Int = logQueue.size
    
    /**
     * Clear all logs (for development)
     */
    fun clearLogs() {
        logQueue.clear()
        Log.i(TAG, "Log queue cleared")
    }
    
    /**
     * Format log message for Android log
     */
    private fun formatLogMessage(log: LogEntry): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val time = sdf.format(Date(log.timestamp))
        return "[$time] [${log.category.displayName}] ${log.message}"
    }
    
    /**
     * Get stack trace as string
     */
    private fun getStackTrace(throwable: Throwable): String {
        return Log.getStackTraceString(throwable)
    }
    
    /**
     * Collect device information
     */
    private fun collectDeviceInfo(context: Context): Map<String, String> {
        return try {
            mapOf(
                "device_model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "os_version" to "${android.os.Build.VERSION.RELEASE}",
                "app_version" to "1.0.0",
                "environment" to ConfigManager.getEnvironment(),
                "timestamp" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to collect device info: ${e.message}")
            mapOf(
                "error" to "Failed to collect device info",
                "timestamp" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            )
        }
    }
    
    /**
     * Get system health summary
     */
    fun getSystemHealth(): Map<String, Any> {
        return mapOf(
            "log_queue_size" to logQueue.size,
            "is_uploading" to isUploading,
            "device_info" to deviceInfoCache,
            "environment" to ConfigManager.getEnvironment(),
            "config_valid" to ConfigManager.validateConfiguration(),
            "timestamp" to System.currentTimeMillis()
        )
    }
}

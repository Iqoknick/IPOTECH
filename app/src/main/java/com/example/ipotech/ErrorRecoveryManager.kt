package com.example.ipotech

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import java.util.concurrent.ConcurrentHashMap

// SystemLogger import
import com.example.ipotech.SystemLogger
import com.example.ipotech.SystemLogger.LogCategory

/**
 * Handles automatic retry for failed Firebase operations with exponential backoff
 */
object ErrorRecoveryManager {
    
    private const val TAG = "ErrorRecoveryManager"
    private const val MAX_RETRIES = 3
    private const val INITIAL_DELAY_MS = 1000L
    private const val MAX_DELAY_MS = 10000L
    
    private val pendingOperations = ConcurrentHashMap<String, RetryOperation>()
    private val handler = Handler(Looper.getMainLooper())
    
    data class RetryOperation(
        val path: String,
        val value: Any,
        val operation: (String, Any) -> Unit,
        var retryCount: Int = 0,
        var nextRetryTime: Long = 0
    )
    
    /**
     * Execute Firebase write with automatic retry
     */
    fun safeWrite(database: DatabaseReference, path: String, value: Any, onComplete: ((Boolean) -> Unit)? = null) {
        val operationKey = "$path:${System.currentTimeMillis()}"
        
        val retryOp = RetryOperation(
            path = path,
            value = value,
            operation = { p, v -> executeWrite(database, p, v, onComplete) }
        )
        
        pendingOperations[operationKey] = retryOp
        
        // Log operation start
        SystemLogger.logDebug(LogCategory.ERROR_RECOVERY, "Starting Firebase operation", mapOf(
            "path" to path,
            "operation_key" to operationKey
        ))
        
        executeWrite(database, path, value, onComplete)
    }
    
    private fun executeWrite(database: DatabaseReference, path: String, value: Any, onComplete: ((Boolean) -> Unit)?) {
        val targetRef = if (path.isEmpty()) database else database.child(path)
        
        when (value) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                targetRef.updateChildren(value as Map<String, Any>)
            }
            else -> {
                targetRef.setValue(value)
            }
        }.addOnSuccessListener {
            Log.d(TAG, "Successfully wrote to $path")
            SystemLogger.logDatabase("write", path, true, mapOf(
                "operation_type" to if (value is Map<*, *>) "updateChildren" else "setValue"
            ))
            removePendingOperation(path)
            onComplete?.invoke(true)
        }.addOnFailureListener { error ->
            Log.e(TAG, "Failed to write to $path: ${error.message ?: "Unknown error"}")
            SystemLogger.logDatabase("write", path, false, mapOf(
                "operation_type" to if (value is Map<*, *>) "updateChildren" else "setValue",
                "error_message" to (error.message ?: "Unknown error")
            ))
            handleFailure(database, path, value, error, onComplete)
        }
    }
    
    private fun handleFailure(database: DatabaseReference, path: String, value: Any, error: Exception, onComplete: ((Boolean) -> Unit)?) {
        val operation = findPendingOperation(path)
        
        if (operation == null || operation.retryCount >= MAX_RETRIES) {
            Log.e(TAG, "Max retries exceeded for $path or operation not found")
            
            // Log final failure
            SystemLogger.logError(LogCategory.ERROR_RECOVERY, "Firebase operation failed permanently", mapOf(
                "path" to path,
                "retry_count" to (operation?.retryCount ?: 0),
                "max_retries" to MAX_RETRIES,
                "error_message" to (error.message ?: "Unknown error")
            ), error)
            
            removePendingOperation(path)
            onComplete?.invoke(false)
            logSystemError("Firebase write failed permanently", path, error.message ?: "Unknown error")
            return
        }
        
        // Schedule retry with exponential backoff
        operation.retryCount++
        val delay = calculateBackoffDelay(operation.retryCount)
        operation.nextRetryTime = System.currentTimeMillis() + delay
        
        Log.w(TAG, "Scheduling retry #$operation.retryCount for $path in ${delay}ms")
        
        // Log retry attempt
        SystemLogger.logErrorRecovery("firebase_write", operation.retryCount, MAX_RETRIES, false, mapOf(
            "path" to path,
            "delay_ms" to delay,
            "error_message" to (error.message ?: "Unknown error")
        ))
        
        handler.postDelayed({
            if (pendingOperations.contains(path)) {
                executeWrite(database, path, value, onComplete)
            }
        }, delay)
    }
    
    private fun calculateBackoffDelay(retryCount: Int): Long {
        val delay = INITIAL_DELAY_MS * (1 shl (retryCount - 1)) // 1s, 2s, 4s, 8s...
        return minOf(delay, MAX_DELAY_MS)
    }
    
    private fun findPendingOperation(path: String): RetryOperation? {
        return pendingOperations.values.find { it.path == path }
    }
    
    private fun removePendingOperation(path: String) {
        pendingOperations.values.removeAll { it.path == path }
    }
    
    /**
     * Cancel all pending operations (call when app is closing)
     */
    fun cancelAllOperations() {
        pendingOperations.clear()
        handler.removeCallbacksAndMessages(null)
    }
    
    /**
     * Get count of currently failing operations
     */
    fun getFailingOperationCount(): Int {
        return pendingOperations.size
    }
    
    /**
     * Log system errors to Firebase for debugging
     */
    private fun logSystemError(error: String, context: String, details: String) {
        try {
            // Use SystemLogger instead of manual Firebase logging
            SystemLogger.logError(LogCategory.DATABASE, "Firebase operation failed", mapOf(
                "error_type" to error,
                "context" to context,
                "details" to details
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log system error: ${e.message}")
        }
    }
    
    /**
     * Check if operations are currently failing for a specific path
     */
    fun isOperationFailing(path: String): Boolean {
        return pendingOperations.values.any { it.path == path }
    }
}

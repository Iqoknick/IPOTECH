package com.example.ipotech

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.ConcurrentLinkedQueue

// SystemLogger import
import com.example.ipotech.SystemLogger
import com.example.ipotech.SystemLogger.LogCategory

/**
 * Offline mode manager for local caching and operation queuing
 */
object OfflineManager {
    
    private const val TAG = "OfflineManager"
    
    // Local cache storage with size limits and expiration
    private val deviceStatusCache = mutableMapOf<String, Any>()
    private val temperatureCache = mutableMapOf<String, Any>()
    private val scheduleCache = mutableMapOf<String, Any>()
    private val logsCache = mutableListOf<Map<String, Any>>()
    
    // Cache limits
    private const val MAX_CACHE_SIZE = 1000
    private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 minutes
    private const val MAX_LOG_CACHE_SIZE = 100
    
    // Operation queue for offline actions
    private val operationQueue = ConcurrentLinkedQueue<OfflineOperation>()
    
    // Sync state
    private var isOnline = true
    private var lastSyncTime = 0L
    
    data class OfflineOperation(
        val id: String,
        val type: OperationType,
        val path: String,
        val value: Any,
        val timestamp: Long,
        val retryCount: Int = 0
    )
    
    enum class OperationType {
        SET_VALUE,
        UPDATE_CHILDREN,
        PUSH_LOG
    }
    
    /**
     * Initialize offline manager
     */
    fun initialize(context: Context) {
        Log.i(TAG, "OfflineManager initialized")
        SystemLogger.logInfo(LogCategory.SYSTEM, "Offline manager initialized", mapOf(
            "cache_enabled" to true,
            "operation_queue_enabled" to true
        ))
    }
    
    /**
     * Set online/offline status
     */
    fun setOnlineStatus(online: Boolean) {
        val wasOffline = !isOnline
        isOnline = online
        
        if (online && wasOffline) {
            // Just came back online - start sync
            Log.i(TAG, "Back online - starting sync")
            SystemLogger.logInfo(LogCategory.NETWORK, "Network restored - starting sync")
            syncQueuedOperations()
        } else if (!online && !wasOffline) {
            // Just went offline
            Log.i(TAG, "Went offline - enabling offline mode")
            SystemLogger.logWarning(LogCategory.NETWORK, "Network lost - offline mode enabled")
        }
    }
    
    /**
     * Cache device status
     */
    fun cacheDeviceStatus(device: String, status: Boolean, stopAt: Long = 0L, manualOverride: Boolean = false) {
        // Clean expired entries before adding new ones
        cleanExpiredCache()
        
        val deviceData = mapOf(
            "status" to status,
            "stop_at" to stopAt,
            "manual_override" to manualOverride,
            "cached_at" to System.currentTimeMillis(),
            "source" to "firebase"
        )
        
        deviceStatusCache[device] = deviceData
        
        // Enforce cache size limit
        if (deviceStatusCache.size > MAX_CACHE_SIZE) {
            val oldestKey = deviceStatusCache.keys.first()
            deviceStatusCache.remove(oldestKey)
        }
        
        SystemLogger.logDebug(LogCategory.DATABASE, "Device status cached", mapOf(
            "device" to device,
            "status" to status,
            "offline_mode" to !isOnline
        ) as Map<String, Any>)
    }
    
    /**
     * Clean expired cache entries
     */
    private fun cleanExpiredCache() {
        val currentTime = System.currentTimeMillis()
        
        // Clean device status cache
        deviceStatusCache.entries.removeIf { entry ->
            val data = entry.value as? Map<String, Any>
            val cachedAt = data?.get("cached_at") as? Long ?: 0L
            (currentTime - cachedAt) > CACHE_EXPIRY_MS
        }
        
        // Clean temperature cache
        temperatureCache.entries.removeIf { entry ->
            val data = entry.value as? Map<String, Any>
            val timestamp = data?.get("timestamp") as? Long ?: 0L
            (currentTime - timestamp) > CACHE_EXPIRY_MS
        }
        
        // Clean schedule cache
        scheduleCache.entries.removeIf { entry ->
            val data = entry.value as? Map<String, Any>
            val cachedAt = data?.get("cached_at") as? Long ?: 0L
            (currentTime - cachedAt) > CACHE_EXPIRY_MS
        }
        
        // Clean log cache (keep only recent logs)
        logsCache.removeIf { log ->
            val timestamp = log["timestamp"] as? Long ?: 0L
            (currentTime - timestamp) > CACHE_EXPIRY_MS
        }
        
        // Enforce log cache size limit
        if (logsCache.size > MAX_LOG_CACHE_SIZE) {
            logsCache.subList(0, logsCache.size - MAX_LOG_CACHE_SIZE).clear()
        }
    }
    
    /**
     * Cache temperature reading
     */
    fun cacheTemperature(temperature: Double) {
        val tempData = mapOf(
            "value" to temperature,
            "timestamp" to System.currentTimeMillis(),
            "source" to "firebase"
        )
        
        temperatureCache["current"] = tempData
        
        SystemLogger.logDebug(LogCategory.TEMPERATURE, "Temperature cached", mapOf(
            "temperature" to temperature,
            "offline_mode" to !isOnline
        ))
    }
    
    /**
     * Cache schedule data
     */
    fun cacheScheduleData(path: String, data: Any) {
        scheduleCache[path] = mapOf(
            "value" to data,
            "cached_at" to System.currentTimeMillis(),
            "source" to "firebase"
        )
    }
    
    /**
     * Cache log entry
     */
    fun cacheLogEntry(logEntry: Map<String, Any>) {
        logsCache.add(logEntry)
        
        // Keep only last 100 logs in cache
        if (logsCache.size > 100) {
            logsCache.removeAt(0)
        }
    }
    
    /**
     * Get cached device status
     */
    fun getCachedDeviceStatus(device: String): Map<String, Any>? {
        val data = deviceStatusCache[device] as? Map<String, Any>
        return data?.let { 
            // Add cache metadata
            it + mapOf(
                "is_cached" to true,
                "cache_age_ms" to (System.currentTimeMillis() - (it["cached_at"] as Long))
            )
        }
    }
    
    /**
     * Get cached temperature
     */
    fun getCachedTemperature(): Map<String, Any>? {
        val data = temperatureCache["current"] as? Map<String, Any>
        return data?.let { 
            it + mapOf(
                "is_cached" to true,
                "cache_age_ms" to (System.currentTimeMillis() - (it["timestamp"] as Long))
            )
        }
    }
    
    /**
     * Get cached logs
     */
    fun getCachedLogs(limit: Int = 20): List<Map<String, Any>> {
        return logsCache.takeLast(limit).map { log ->
            log + mapOf("is_cached" to true)
        }
    }
    
    /**
     * Queue operation for offline execution
     */
    fun queueOperation(type: OperationType, path: String, value: Any) {
        if (isOnline) {
            // Online - execute immediately
            return
        }
        
        val operation = OfflineOperation(
            id = "${path}_${System.currentTimeMillis()}",
            type = type,
            path = path,
            value = value,
            timestamp = System.currentTimeMillis()
        )
        
        operationQueue.offer(operation)
        
        SystemLogger.logInfo(LogCategory.DATABASE, "Operation queued for offline sync", mapOf(
            "operation_id" to operation.id,
            "type" to operation.type.name,
            "path" to path,
            "queue_size" to operationQueue.size
        ) as Map<String, Any>)
    }
    
    /**
     * Execute Firebase write with offline support
     */
    fun executeWrite(database: DatabaseReference, path: String, value: Any, onComplete: ((Boolean) -> Unit)? = null) {
        if (isOnline) {
            // Online - execute immediately
            executeFirebaseWrite(database, path, value, onComplete)
        } else {
            // Offline - queue for later
            queueOperation(OperationType.SET_VALUE, path, value)
            
            // Update local cache immediately
            if (path.contains("status") || path.contains("stop_at") || path.contains("manual_override")) {
                val device = path.split("/").firstOrNull { it.isNotEmpty() }
                if (device != null) {
                    when {
                        path.contains("status") -> cacheDeviceStatus(device, value as Boolean)
                        path.contains("stop_at") -> {
                            val current = getCachedDeviceStatus(device) ?: emptyMap()
                            cacheDeviceStatus(device, current["status"] as Boolean, value as Long)
                        }
                        path.contains("manual_override") -> {
                            val current = getCachedDeviceStatus(device) ?: emptyMap()
                            cacheDeviceStatus(device, current["status"] as Boolean, current["stop_at"] as Long, value as Boolean)
                        }
                    }
                }
            }
            
            // Return success to user (will sync when online)
            onComplete?.invoke(true)
        }
    }
    
    /**
     * Execute actual Firebase write
     */
    private fun executeFirebaseWrite(database: DatabaseReference, path: String, value: Any, onComplete: ((Boolean) -> Unit)?) {
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
            onComplete?.invoke(true)
        }.addOnFailureListener { error ->
            Log.e(TAG, "Firebase write failed: ${error.message}")
            SystemLogger.logError(LogCategory.DATABASE, "Firebase write failed", mapOf(
                "path" to path,
                "error" to (error.message ?: "Unknown error")
            ) as Map<String, Any>)
            
            // Queue for retry if online
            if (isOnline) {
                queueOperation(OperationType.SET_VALUE, path, value)
            }
            onComplete?.invoke(false)
        }
    }
    
    /**
     * Sync queued operations when back online
     */
    private fun syncQueuedOperations() {
        if (operationQueue.isEmpty()) {
            return
        }
        
        Log.i(TAG, "Syncing ${operationQueue.size} queued operations")
        SystemLogger.logInfo(LogCategory.DATABASE, "Starting offline sync", mapOf(
            "operations_to_sync" to operationQueue.size
        ))
        
        Thread {
            val syncedOperations = mutableListOf<OfflineOperation>()
            
            while (operationQueue.isNotEmpty()) {
                val operation = operationQueue.poll()
                if (operation != null) {
                    val success = syncOperation(operation)
                    if (success) {
                        syncedOperations.add(operation)
                    } else {
                        // Put back in queue for retry
                        operationQueue.offer(operation.copy(retryCount = operation.retryCount + 1))
                    }
                }
            }
            
            lastSyncTime = System.currentTimeMillis()
            
            SystemLogger.logInfo(LogCategory.DATABASE, "Offline sync completed", mapOf(
                "synced_operations" to syncedOperations.size,
                "remaining_operations" to operationQueue.size,
                "sync_duration_ms" to (System.currentTimeMillis() - lastSyncTime)
            ))
        }.start()
    }
    
    /**
     * Sync individual operation
     */
    private fun syncOperation(operation: OfflineOperation): Boolean {
        return try {
            val database = FirebaseDatabase.getInstance(ConfigManager.getDatabaseUrl()).reference
            val targetRef = if (operation.path.isEmpty()) database else database.child(operation.path)
            
            // Use proper async handling instead of blocking sleep
            var operationSuccess = false
            var operationComplete = false
            
            when (operation.type) {
                OperationType.SET_VALUE -> {
                    targetRef.setValue(operation.value).addOnSuccessListener {
                        operationSuccess = true
                        operationComplete = true
                    }.addOnFailureListener {
                        operationSuccess = false
                        operationComplete = true
                    }
                }
                OperationType.UPDATE_CHILDREN -> {
                    @Suppress("UNCHECKED_CAST")
                    targetRef.updateChildren(operation.value as Map<String, Any>).addOnSuccessListener {
                        operationSuccess = true
                        operationComplete = true
                    }.addOnFailureListener {
                        operationSuccess = false
                        operationComplete = true
                    }
                }
                OperationType.PUSH_LOG -> {
                    targetRef.push().setValue(operation.value).addOnSuccessListener {
                        operationSuccess = true
                        operationComplete = true
                    }.addOnFailureListener {
                        operationSuccess = false
                        operationComplete = true
                    }
                }
            }
            
            // Wait for completion with timeout (max 5 seconds)
            val startTime = System.currentTimeMillis()
            while (!operationComplete && (System.currentTimeMillis() - startTime) < 5000) {
                Thread.sleep(50)
            }
            
            operationSuccess
            
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing operation: ${e.message}")
            false
        }
    }
    
    /**
     * Get offline status information
     */
    fun getOfflineStatus(): Map<String, Any> {
        return mapOf(
            "is_online" to isOnline,
            "last_sync_time" to lastSyncTime,
            "queued_operations" to operationQueue.size,
            "cached_devices" to deviceStatusCache.size,
            "cached_temperature" to temperatureCache.isNotEmpty(),
            "cached_logs" to logsCache.size
        )
    }
    
    /**
     * Clear all caches (for development)
     */
    fun clearCaches() {
        deviceStatusCache.clear()
        temperatureCache.clear()
        scheduleCache.clear()
        logsCache.clear()
        operationQueue.clear()
        
        Log.i(TAG, "All caches cleared")
        SystemLogger.logInfo(LogCategory.SYSTEM, "All offline caches cleared")
    }
    
    /**
     * Force sync all queued operations
     */
    fun forceSync() {
        if (isOnline) {
            syncQueuedOperations()
        } else {
            Log.w(TAG, "Cannot sync - currently offline")
            SystemLogger.logWarning(LogCategory.NETWORK, "Force sync failed - offline")
        }
    }
}

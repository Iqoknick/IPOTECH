package com.example.ipotech

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// SystemLogger import
import com.example.ipotech.SystemLogger
import com.example.ipotech.SystemLogger.LogCategory

/**
 * Utility functions for system monitoring and health checks
 */
object MonitoringUtils {
    
    private const val TAG = "MonitoringUtils"
    private var scheduledExecutor: ScheduledExecutorService? = null
    
    /**
     * Start periodic monitoring tasks
     */
    fun startMonitoring(context: Context) {
        if (scheduledExecutor != null) {
            Log.w(TAG, "Monitoring already started")
            return
        }
        
        scheduledExecutor = Executors.newScheduledThreadPool(2)
        
        // Upload logs every 5 minutes
        scheduledExecutor?.scheduleAtFixedRate({
            try {
                SystemLogger.uploadLogs()
                Log.d(TAG, "Periodic log upload triggered")
            } catch (e: Exception) {
                Log.e(TAG, "Error in periodic log upload: ${e.message}")
            }
        }, 1, 5, TimeUnit.MINUTES)
        
        // System health check every 10 minutes
        scheduledExecutor?.scheduleAtFixedRate({
            try {
                performHealthCheck(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error in health check: ${e.message}")
            }
        }, 2, 10, TimeUnit.MINUTES)
        
        Log.i(TAG, "System monitoring started")
        SystemLogger.logInfo(LogCategory.SYSTEM, "System monitoring started", mapOf(
            "log_upload_interval" to "5_minutes",
            "health_check_interval" to "10_minutes"
        ) as Map<String, Any>)
    }
    
    /**
     * Stop periodic monitoring tasks
     */
    fun stopMonitoring() {
        scheduledExecutor?.shutdown()
        scheduledExecutor = null
        Log.i(TAG, "System monitoring stopped")
        SystemLogger.logInfo(LogCategory.SYSTEM, "System monitoring stopped")
    }
    
    /**
     * Perform comprehensive system health check
     */
    fun performHealthCheck(context: Context) {
        val health = SystemLogger.getSystemHealth()
        
        // Check log queue size
        if (health["log_queue_size"] as Int > 50) {
            SystemLogger.logWarning(LogCategory.SYSTEM, "High log queue size detected", mapOf(
                "queue_size" to health["log_queue_size"],
                "threshold" to 50
            ) as Map<String, Any>)
        }
        
        // Check configuration
        if (!(health["config_valid"] as Boolean)) {
            SystemLogger.logError(LogCategory.CONFIGURATION, "Invalid configuration detected", mapOf(
                "environment" to ConfigManager.getEnvironment()
            ))
        }
        
        // Check error recovery operations
        val failingOps = ErrorRecoveryManager.getFailingOperationCount()
        if (failingOps > 0) {
            SystemLogger.logWarning(LogCategory.ERROR_RECOVERY, "Active error recovery operations", mapOf(
                "failing_operations" to failingOps
            ))
        }
        
        // Log overall health status
        val isHealthy = (health["log_queue_size"] as Int) < 50 && 
                       (health["config_valid"] as Boolean) && 
                       failingOps == 0
        
        SystemLogger.logInfo(LogCategory.SYSTEM, "Health check completed", mapOf(
            "healthy" to isHealthy,
            "log_queue_size" to health["log_queue_size"],
            "config_valid" to health["config_valid"],
            "failing_operations" to failingOps,
            "environment" to ConfigManager.getEnvironment()
        ) as Map<String, Any>)
        
        Log.i(TAG, "Health check completed - Healthy: $isHealthy")
    }
    
    /**
     * Get detailed system status report
     */
    fun getSystemStatusReport(): Map<String, Any> {
        val health = SystemLogger.getSystemHealth()
        val config = ConfigManager.getAllConfig()
        
        return mapOf(
            "timestamp" to System.currentTimeMillis(),
            "health" to health,
            "configuration" to config,
            "error_recovery" to mapOf(
                "failing_operations" to ErrorRecoveryManager.getFailingOperationCount()
            ),
            "monitoring" to mapOf(
                "active" to (scheduledExecutor != null && !scheduledExecutor!!.isShutdown),
                "log_queue_size" to SystemLogger.getQueueSize()
            )
        )
    }
    
    /**
     * Force immediate log upload
     */
    fun forceLogUpload() {
        SystemLogger.uploadLogs()
        Log.i(TAG, "Forced log upload triggered")
    }
    
    /**
     * Get system diagnostics for debugging
     */
    fun getDiagnostics(): Map<String, Any> {
        return mapOf(
            "system_health" to SystemLogger.getSystemHealth(),
            "config_validation" to ConfigManager.validateConfiguration(),
            "error_recovery_status" to mapOf(
                "failing_operations" to ErrorRecoveryManager.getFailingOperationCount(),
                "max_retries" to 3
            ),
            "monitoring_status" to mapOf(
                "active" to (scheduledExecutor != null && !scheduledExecutor!!.isShutdown),
                "log_queue_size" to SystemLogger.getQueueSize()
            ),
            "timestamp" to System.currentTimeMillis()
        )
    }
}

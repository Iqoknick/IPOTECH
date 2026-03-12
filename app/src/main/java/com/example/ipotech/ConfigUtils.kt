package com.example.ipotech

import android.content.Context
import android.util.Log

/**
 * Utility functions for configuration management
 */
object ConfigUtils {
    
    private const val TAG = "ConfigUtils"
    
    /**
     * Set production configuration (call this in production builds)
     */
    fun setProductionConfig(context: Context, productionDbUrl: String, productionApiKey: String) {
        ConfigManager.initialize(context)
        ConfigManager.setDatabaseUrl(productionDbUrl)
        ConfigManager.setDatabaseApiKey(productionApiKey)
        ConfigManager.setEnvironment("production")
        
        Log.i(TAG, "Production configuration set")
        Log.i(TAG, "Environment: ${ConfigManager.getEnvironment()}")
        Log.i(TAG, "Database URL: ${ConfigManager.getDatabaseUrl()}")
        Log.i(TAG, "API Key set: ${ConfigManager.getDatabaseApiKey().isNotBlank()}")
    }
    
    /**
     * Set staging configuration
     */
    fun setStagingConfig(context: Context, stagingDbUrl: String, stagingApiKey: String) {
        ConfigManager.initialize(context)
        ConfigManager.setDatabaseUrl(stagingDbUrl)
        ConfigManager.setDatabaseApiKey(stagingApiKey)
        ConfigManager.setEnvironment("staging")
        
        Log.i(TAG, "Staging configuration set")
    }
    
    /**
     * Reset to development configuration
     */
    fun resetToDevelopment(context: Context) {
        ConfigManager.initialize(context)
        ConfigManager.resetToDefaults()
        
        Log.i(TAG, "Reset to development configuration")
    }
    
    /**
     * Print current configuration (for debugging)
     */
    fun printCurrentConfig() {
        val config = ConfigManager.getAllConfig()
        Log.i(TAG, "Current Configuration:")
        config.forEach { (key, value) ->
            Log.i(TAG, "  $key: $value")
        }
        
        Log.i(TAG, "Validation: ${ConfigManager.validateConfiguration()}")
    }
    
    /**
     * Validate and report configuration status
     */
    fun validateAndReport(): Boolean {
        val isValid = ConfigManager.validateConfiguration()
        
        if (!isValid) {
            Log.e(TAG, "Configuration validation failed!")
            Log.e(TAG, "Current config: ${ConfigManager.getAllConfig()}")
        } else {
            Log.i(TAG, "Configuration validation passed")
        }
        
        return isValid
    }
}

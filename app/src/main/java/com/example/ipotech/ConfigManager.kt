package com.example.ipotech

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Secure configuration management for production deployment
 */
object ConfigManager {
    
    private const val TAG = "ConfigManager"
    private const val PREFS_NAME = "ipotech_config"
    private const val KEY_DB_URL = "firebase_db_url"
    private const val KEY_DB_API_KEY = "firebase_api_key"
    private const val KEY_ENVIRONMENT = "environment"
    
    // Default values for development
    private const val DEFAULT_DB_URL = "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
    private const val DEFAULT_DB_API_KEY = "AIzaSyD_ZlLH0tFkZJ0DzXIeGLZP_ZOz3UhLng8"
    private const val DEFAULT_ENVIRONMENT = "development"
    
    private lateinit var sharedPreferences: SharedPreferences
    
    /**
     * Initialize ConfigManager with context
     */
    fun initialize(context: Context) {
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Set defaults if not already set
        setDefaultsIfNotSet()
    }
    
    /**
     * Get Firebase Database URL
     */
    fun getDatabaseUrl(): String {
        if (!::sharedPreferences.isInitialized) {
            Log.e(TAG, "ConfigManager not initialized, using default")
            return DEFAULT_DB_URL
        }
        
        return sharedPreferences.getString(KEY_DB_URL, DEFAULT_DB_URL) ?: DEFAULT_DB_URL
    }
    
    /**
     * Get Firebase API Key
     */
    fun getDatabaseApiKey(): String {
        if (!::sharedPreferences.isInitialized) {
            Log.e(TAG, "ConfigManager not initialized, using default")
            return DEFAULT_DB_API_KEY
        }
        
        return sharedPreferences.getString(KEY_DB_API_KEY, DEFAULT_DB_API_KEY) ?: DEFAULT_DB_API_KEY
    }
    
    /**
     * Get current environment
     */
    fun getEnvironment(): String {
        if (!::sharedPreferences.isInitialized) {
            Log.e(TAG, "ConfigManager not initialized, using default")
            return DEFAULT_ENVIRONMENT
        }
        
        return sharedPreferences.getString(KEY_ENVIRONMENT, DEFAULT_ENVIRONMENT) ?: DEFAULT_ENVIRONMENT
    }
    
    /**
     * Set Firebase Database URL
     */
    fun setDatabaseUrl(url: String) {
        if (!::sharedPreferences.isInitialized) {
            Log.e(TAG, "ConfigManager not initialized")
            return
        }
        
        if (url.isBlank()) {
            Log.e(TAG, "Database URL cannot be blank")
            return
        }
        
        sharedPreferences.edit()
            .putString(KEY_DB_URL, url.trim())
            .apply()
        
        Log.i(TAG, "Database URL updated")
    }
    
    /**
     * Set Firebase API Key
     */
    fun setDatabaseApiKey(apiKey: String) {
        if (!::sharedPreferences.isInitialized) {
            Log.e(TAG, "ConfigManager not initialized")
            return
        }
        
        if (apiKey.isBlank()) {
            Log.e(TAG, "API Key cannot be blank")
            return
        }
        
        sharedPreferences.edit()
            .putString(KEY_DB_API_KEY, apiKey.trim())
            .apply()
        
        Log.i(TAG, "Database API Key updated")
    }
    
    /**
     * Set environment (development, staging, production)
     */
    fun setEnvironment(environment: String) {
        if (!::sharedPreferences.isInitialized) {
            Log.e(TAG, "ConfigManager not initialized")
            return
        }
        
        val validEnvironments = listOf("development", "staging", "production")
        if (environment !in validEnvironments) {
            Log.e(TAG, "Invalid environment: $environment. Valid: $validEnvironments")
            return
        }
        
        sharedPreferences.edit()
            .putString(KEY_ENVIRONMENT, environment)
            .apply()
        
        Log.i(TAG, "Environment set to: $environment")
    }
    
    /**
     * Check if running in production
     */
    fun isProduction(): Boolean {
        return getEnvironment() == "production"
    }
    
    /**
     * Check if running in development
     */
    fun isDevelopment(): Boolean {
        return getEnvironment() == "development"
    }
    
    /**
     * Get all current configuration (for debugging)
     */
    fun getAllConfig(): Map<String, String> {
        return mapOf(
            "database_url" to getDatabaseUrl(),
            "environment" to getEnvironment(),
            "api_key_set" to (getDatabaseApiKey() != DEFAULT_DB_API_KEY).toString()
        )
    }
    
    /**
     * Reset to defaults (for development)
     */
    fun resetToDefaults() {
        if (!::sharedPreferences.isInitialized) {
            Log.e(TAG, "ConfigManager not initialized")
            return
        }
        
        sharedPreferences.edit()
            .clear()
            .apply()
        
        setDefaultsIfNotSet()
        Log.i(TAG, "Configuration reset to defaults")
    }
    
    /**
     * Set default values if not already set
     */
    private fun setDefaultsIfNotSet() {
        if (!sharedPreferences.contains(KEY_DB_URL)) {
            sharedPreferences.edit()
                .putString(KEY_DB_URL, DEFAULT_DB_URL)
                .putString(KEY_DB_API_KEY, DEFAULT_DB_API_KEY)
                .putString(KEY_ENVIRONMENT, DEFAULT_ENVIRONMENT)
                .apply()
            Log.i(TAG, "Default configuration set")
        }
    }
    
    /**
     * Validate current configuration
     */
    fun validateConfiguration(): Boolean {
        val url = getDatabaseUrl()
        val apiKey = getDatabaseApiKey()
        val environment = getEnvironment()
        
        // Basic validation
        if (url.isBlank()) {
            Log.e(TAG, "Database URL is blank")
            return false
        }
        
        if (!url.startsWith("https://") || !url.endsWith(".firebaseio.com/") && !url.endsWith(".firebaseio.com") && !url.endsWith(".rtdb.firebaseio.com/") && !url.endsWith(".rtdb.firebaseio.com")) {
            Log.e(TAG, "Invalid database URL format: $url")
            return false
        }
        
        if (apiKey.isBlank()) {
            Log.e(TAG, "API Key is blank")
            return false
        }
        
        if (apiKey.length < 20) {
            Log.e(TAG, "API Key seems too short")
            return false
        }
        
        val validEnvironments = listOf("development", "staging", "production")
        if (environment !in validEnvironments) {
            Log.e(TAG, "Invalid environment: $environment")
            return false
        }
        
        return true
    }
}

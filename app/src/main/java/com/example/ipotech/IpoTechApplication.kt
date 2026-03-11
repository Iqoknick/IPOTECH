package com.example.ipotech

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class IpoTechApplication : Application() {
    
    companion object {
        const val CHANNEL_TEMP_ALERTS = "temperature_alerts"
        private const val DB_URL = "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
        const val PREFS_NAME = "ipotech_settings"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_VIBRATION = "vibration"
        const val KEY_CRITICAL_THRESHOLD = "critical_threshold"
        const val KEY_ALERT_COOLDOWN = "alert_cooldown"
        const val KEY_SAVE_INTERVAL = "save_interval"
        const val KEY_AUTO_CLEANUP = "auto_cleanup"
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Apply dark mode setting
        applyDarkModeSetting()
        
        // Enable offline persistence so the app can read schedules and queue logs while offline
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        
        // Keep all critical paths synced even when the app is in background or offline
        val database = FirebaseDatabase.getInstance().reference
        database.child("schedule").keepSynced(true)
        database.child("conveyor").keepSynced(true)
        database.child("heater").keepSynced(true)
        database.child("pulverizer").keepSynced(true)
        database.child("temperature").keepSynced(true)
        
        // Create notification channels
        createNotificationChannels()
        
        // Run auto cleanup if enabled
        runAutoCleanupIfEnabled()
    }
    
    private fun applyDarkModeSetting() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }
    
    private fun runAutoCleanupIfEnabled() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoCleanup = prefs.getBoolean(KEY_AUTO_CLEANUP, true)
        
        if (!autoCleanup) return
        
        val database = FirebaseDatabase.getInstance(DB_URL).reference
        val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000) // 30 days ago
        
        // Cleanup temperature history older than 30 days
        database.child("temperature_history")
            .orderByChild("timestamp")
            .endAt(cutoffTime.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        child.ref.removeValue()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        
        // Cleanup logs older than 30 days
        database.child("logs")
            .orderByChild("timestamp")
            .endAt(cutoffTime.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        child.ref.removeValue()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val tempChannel = NotificationChannel(
                CHANNEL_TEMP_ALERTS,
                "Temperature Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when temperature exceeds safe thresholds"
                enableVibration(true)
                // Short triple burst pattern for easy noticing (Industrial Alert Style)
                vibrationPattern = longArrayOf(0, 200, 100, 200, 100, 200)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(tempChannel)
        }
    }
}

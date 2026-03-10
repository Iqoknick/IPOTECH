package com.example.ipotech

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.firebase.database.FirebaseDatabase

class IpoTechApplication : Application() {
    
    companion object {
        const val CHANNEL_TEMP_ALERTS = "temperature_alerts"
    }
    
    override fun onCreate() {
        super.onCreate()
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
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(tempChannel)
        }
    }
}

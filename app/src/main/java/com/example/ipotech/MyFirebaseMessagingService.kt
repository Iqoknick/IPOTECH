package com.example.ipotech

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.ipotech.IpoTechApplication.Companion.CHANNEL_TEMP_ALERTS
import com.example.ipotech.IpoTechApplication.Companion.KEY_NOTIFICATIONS
import com.example.ipotech.IpoTechApplication.Companion.PREFS_NAME

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "Message received: ${remoteMessage.data}")

        // Check if notifications are enabled in settings
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        
        if (!notificationsEnabled) {
            Log.d("FCM", "Notifications are disabled in settings. Skipping...")
            return
        }

        val title = remoteMessage.notification?.title ?: "IPOTech Alert"
        val body = remoteMessage.notification?.body ?: "Critical temperature detected!"

        val manager = getSystemService(NotificationManager::class.java)
        
        // Channel is created in IpoTechApplication, so we just use the constant
        val notification = NotificationCompat.Builder(this, CHANNEL_TEMP_ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(2001, notification)
    }
}

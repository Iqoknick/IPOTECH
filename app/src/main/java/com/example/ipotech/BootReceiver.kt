package com.example.ipotech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

/**
 * BroadcastReceiver that reschedules alarms after device boot
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        private const val DB_URL = "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        
        Log.d(TAG, "Device booted, rescheduling alarms...")
        
        // Read schedule from Firebase and reschedule alarms
        val database = FirebaseDatabase.getInstance(DB_URL).reference
        
        database.child("schedule").get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                Log.d(TAG, "No schedule found")
                return@addOnSuccessListener
            }
            
            val masterEnabled = snapshot.child("masterEnabled").getValue(Boolean::class.java) ?: true
            if (!masterEnabled) {
                Log.d(TAG, "Master schedule disabled, not rescheduling")
                return@addOnSuccessListener
            }
            
            // Schedule morning alarm
            val morningEnabled = snapshot.child("morning/enabled").getValue(Boolean::class.java) ?: false
            if (morningEnabled) {
                val startTime = snapshot.child("morning/start").getValue(String::class.java) ?: "08:00"
                val duration = snapshot.child("morning/duration").value?.toString()?.toIntOrNull() ?: 0
                
                if (duration > 0) {
                    val timeParts = AlarmScheduler.parseTime(startTime)
                    if (timeParts != null) {
                        AlarmScheduler.scheduleStart(
                            context,
                            timeParts.first,
                            timeParts.second,
                            duration,
                            "Morning",
                            AlarmScheduler.REQUEST_MORNING_START
                        )
                        Log.d(TAG, "Rescheduled morning alarm for $startTime")
                    }
                }
            }
            
            // Schedule afternoon/evening alarm
            val afternoonEnabled = snapshot.child("afternoon/enabled").getValue(Boolean::class.java) ?: false
            if (afternoonEnabled) {
                val startTime = snapshot.child("afternoon/start").getValue(String::class.java) ?: "13:00"
                val duration = snapshot.child("afternoon/duration").value?.toString()?.toIntOrNull() ?: 0
                
                if (duration > 0) {
                    val timeParts = AlarmScheduler.parseTime(startTime)
                    if (timeParts != null) {
                        AlarmScheduler.scheduleStart(
                            context,
                            timeParts.first,
                            timeParts.second,
                            duration,
                            "Evening",
                            AlarmScheduler.REQUEST_AFTERNOON_START
                        )
                        Log.d(TAG, "Rescheduled evening alarm for $startTime")
                    }
                }
            }
            
        }.addOnFailureListener {
            Log.e(TAG, "Failed to read schedule for rescheduling: ${it.message}")
        }
    }
}

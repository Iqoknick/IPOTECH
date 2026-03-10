package com.example.ipotech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.util.*

/**
 * BroadcastReceiver that handles exact alarm triggers for conveyor scheduling
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ScheduleAlarmReceiver"
        private const val DB_URL = "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "Scheduled"
        
        Log.d(TAG, "Received alarm action: $action for $label")
        
        when (action) {
            AlarmScheduler.ACTION_START_CONVEYOR -> {
                val durationMinutes = intent.getIntExtra(AlarmScheduler.EXTRA_DURATION_MINUTES, 0)
                handleStartConveyor(context, label, durationMinutes)
            }
            AlarmScheduler.ACTION_STOP_CONVEYOR -> {
                handleStopConveyor(context, label)
            }
        }
    }
    
    private fun handleStartConveyor(context: Context, label: String, durationMinutes: Int) {
        val database = FirebaseDatabase.getInstance(DB_URL).reference
        
        // Check if schedule is still enabled and not manually overridden
        database.child("schedule/masterEnabled").get().addOnSuccessListener { masterSnapshot ->
            val masterEnabled = masterSnapshot.getValue(Boolean::class.java) ?: true
            
            if (!masterEnabled) {
                Log.d(TAG, "Master schedule disabled, skipping start")
                return@addOnSuccessListener
            }
            
            // Check for manual override
            database.child("conveyor/manual_override").get().addOnSuccessListener { overrideSnapshot ->
                val isOverride = overrideSnapshot.getValue(Boolean::class.java) ?: false
                
                if (isOverride) {
                    Log.d(TAG, "Manual override active, skipping scheduled start")
                    return@addOnSuccessListener
                }
                
                // Check if it's an active day
                val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).toString()
                database.child("schedule/activeDays").get().addOnSuccessListener { daysSnapshot ->
                    val activeDays = daysSnapshot.getValue(String::class.java) ?: "1234567"
                    
                    if (!activeDays.contains(today)) {
                        Log.d(TAG, "Not an active day, skipping start")
                        // Reschedule for tomorrow
                        rescheduleForTomorrow(context, label)
                        return@addOnSuccessListener
                    }
                    
                    // Check date range
                    checkDateRangeAndStart(context, database, label, durationMinutes)
                }
            }
        }.addOnFailureListener {
            Log.e(TAG, "Failed to check master enabled: ${it.message}")
        }
    }
    
    private fun checkDateRangeAndStart(
        context: Context,
        database: com.google.firebase.database.DatabaseReference,
        label: String,
        durationMinutes: Int
    ) {
        database.child("schedule").get().addOnSuccessListener { snapshot ->
            val startDate = snapshot.child("startDate").getValue(String::class.java) ?: ""
            val endDate = snapshot.child("endDate").getValue(String::class.java) ?: ""
            
            val sdfDate = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val currentDate = sdfDate.format(Date())
            
            // Check if within date range
            if (startDate.isNotEmpty() && startDate != "---" && currentDate < startDate) {
                Log.d(TAG, "Before start date, skipping")
                return@addOnSuccessListener
            }
            if (endDate.isNotEmpty() && endDate != "---" && currentDate > endDate) {
                Log.d(TAG, "After end date, skipping")
                return@addOnSuccessListener
            }
            
            // All checks passed - start the conveyor!
            startConveyor(context, database, label, durationMinutes)
            
        }.addOnFailureListener {
            Log.e(TAG, "Failed to check date range: ${it.message}")
        }
    }
    
    private fun startConveyor(
        context: Context,
        database: com.google.firebase.database.DatabaseReference,
        label: String,
        durationMinutes: Int
    ) {
        // Calculate stop time
        val stopTimeMillis = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        
        // Update Firebase
        database.child("conveyor/status").setValue(true)
        database.child("conveyor/stop_at").setValue(stopTimeMillis)
        
        // Log the activity
        val log = LogEntry(System.currentTimeMillis(), "Conveyor", "$label Schedule Started (Exact Alarm)")
        database.child("logs").push().setValue(log)
        
        // Schedule the stop alarm
        val stopRequestCode = if (label.contains("Morning", ignoreCase = true)) {
            AlarmScheduler.REQUEST_MORNING_STOP
        } else {
            AlarmScheduler.REQUEST_AFTERNOON_STOP
        }
        
        AlarmScheduler.scheduleStop(context, stopTimeMillis, label, stopRequestCode)
        
        Log.d(TAG, "Started conveyor for $label, will stop at ${Date(stopTimeMillis)}")
        
        // Reschedule start for tomorrow
        rescheduleForTomorrow(context, label)
    }
    
    private fun handleStopConveyor(context: Context, label: String) {
        val database = FirebaseDatabase.getInstance(DB_URL).reference
        
        // Check for manual override before stopping
        database.child("conveyor/manual_override").get().addOnSuccessListener { overrideSnapshot ->
            val isOverride = overrideSnapshot.getValue(Boolean::class.java) ?: false
            
            if (isOverride) {
                Log.d(TAG, "Manual override active, not stopping conveyor")
                return@addOnSuccessListener
            }
            
            // Stop the conveyor
            database.child("conveyor/status").setValue(false)
            database.child("conveyor/stop_at").setValue(0L)
            
            // Log the activity
            val log = LogEntry(System.currentTimeMillis(), "Conveyor", "$label Schedule Finished (Exact Alarm)")
            database.child("logs").push().setValue(log)
            
            Log.d(TAG, "Stopped conveyor for $label")
            
        }.addOnFailureListener {
            Log.e(TAG, "Failed to check override for stop: ${it.message}")
            // Stop anyway on failure
            database.child("conveyor/status").setValue(false)
            database.child("conveyor/stop_at").setValue(0L)
        }
    }
    
    private fun rescheduleForTomorrow(context: Context, label: String) {
        // Re-read schedule from Firebase and reschedule
        val database = FirebaseDatabase.getInstance(DB_URL).reference
        
        database.child("schedule").get().addOnSuccessListener { snapshot ->
            val isMorning = label.contains("Morning", ignoreCase = true)
            val scheduleKey = if (isMorning) "morning" else "afternoon"
            
            val enabled = snapshot.child("$scheduleKey/enabled").getValue(Boolean::class.java) ?: false
            if (!enabled) return@addOnSuccessListener
            
            val startTime = snapshot.child("$scheduleKey/start").getValue(String::class.java) ?: return@addOnSuccessListener
            val duration = snapshot.child("$scheduleKey/duration").value?.toString()?.toIntOrNull() ?: 0
            
            val timeParts = AlarmScheduler.parseTime(startTime) ?: return@addOnSuccessListener
            
            val requestCode = if (isMorning) {
                AlarmScheduler.REQUEST_MORNING_START
            } else {
                AlarmScheduler.REQUEST_AFTERNOON_START
            }
            
            AlarmScheduler.scheduleStart(
                context,
                timeParts.first,
                timeParts.second,
                duration,
                label,
                requestCode
            )
            
            Log.d(TAG, "Rescheduled $label for tomorrow")
        }
    }
}

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
    
    private val TAG = "ScheduleAlarmReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: "Scheduled"
        
        Log.d(TAG, "Received alarm action: $action for $label")
        
        // Use goAsync to keep receiver alive for background Firebase operations
        val pendingResult = goAsync()
        
        when (action) {
            AlarmScheduler.ACTION_START_CONVEYOR -> {
                val durationMinutes = intent.getIntExtra(AlarmScheduler.EXTRA_DURATION_MINUTES, 0)
                handleStartConveyor(context, label, durationMinutes, pendingResult)
            }
            AlarmScheduler.ACTION_STOP_CONVEYOR -> {
                handleStopConveyor(context, label, pendingResult)
            }
            else -> pendingResult.finish()
        }
    }
    
    private fun handleStartConveyor(context: Context, label: String, durationMinutes: Int, pendingResult: android.content.BroadcastReceiver.PendingResult? = null) {
        val database = FirebaseDatabase.getInstance(ConfigManager.getDatabaseUrl()).reference
        
        // Check if schedule is still enabled and not manually overridden
        database.child("schedule/masterEnabled").get().addOnSuccessListener { masterSnapshot ->
            val masterEnabled = masterSnapshot.getValue(Boolean::class.java) ?: true
            
            if (!masterEnabled) {
                Log.d(TAG, "Master schedule disabled, skipping start")
                pendingResult?.finish()
                return@addOnSuccessListener
            }
            
            // Check for manual override
            database.child("conveyor/manual_override").get().addOnSuccessListener { overrideSnapshot ->
                val isOverride = overrideSnapshot.getValue(Boolean::class.java) ?: false
                
                if (isOverride) {
                    Log.d(TAG, "Manual override active, skipping scheduled start")
                    pendingResult?.finish()
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
                        pendingResult?.finish()
                        return@addOnSuccessListener
                    }
                    
                    // Check date range
                    checkDateRangeAndStart(context, database, label, durationMinutes, pendingResult)
                }.addOnFailureListener {
                    Log.e(TAG, "Failed to check active days: ${it.message}")
                    pendingResult?.finish()
                }
            }.addOnFailureListener {
                Log.e(TAG, "Failed to check manual override: ${it.message}")
                pendingResult?.finish()
            }
        }.addOnFailureListener {
            Log.e(TAG, "Failed to check master enabled: ${it.message}")
            pendingResult?.finish()
        }
    }
    
    private fun checkDateRangeAndStart(
        context: Context,
        database: com.google.firebase.database.DatabaseReference,
        label: String,
        durationMinutes: Int,
        pendingResult: android.content.BroadcastReceiver.PendingResult? = null
    ) {
        database.child("schedule").get().addOnSuccessListener { snapshot ->
            val startDate = snapshot.child("startDate").getValue(String::class.java) ?: ""
            val endDate = snapshot.child("endDate").getValue(String::class.java) ?: ""
            
            val sdfDate = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val currentDate = sdfDate.format(Date())
            
            // Check if within date range
            if (startDate.isNotEmpty() && startDate != "---" && currentDate < startDate) {
                Log.d(TAG, "Before start date, skipping")
                pendingResult?.finish()
                return@addOnSuccessListener
            }
            if (endDate.isNotEmpty() && endDate != "---" && currentDate > endDate) {
                Log.d(TAG, "After end date, skipping")
                pendingResult?.finish()
                return@addOnSuccessListener
            }
            
            // All checks passed - start the conveyor!
            startConveyor(context, database, label, durationMinutes, pendingResult)
            
        }.addOnFailureListener {
            Log.e(TAG, "Failed to check date range: ${it.message}")
            pendingResult?.finish()
        }
    }
    
    private fun startConveyor(
        context: Context,
        database: com.google.firebase.database.DatabaseReference,
        label: String,
        durationMinutes: Int,
        pendingResult: android.content.BroadcastReceiver.PendingResult? = null
    ) {
        // Validate duration
        if (durationMinutes < 0 || durationMinutes > 480) { // Max 8 hours
            Log.e(TAG, "Invalid duration: $durationMinutes minutes")
            pendingResult?.finish()
            return
        }
        
        // Calculate stop time
        val stopTimeMillis = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        
        // Validate stop time
        val now = System.currentTimeMillis()
        if (stopTimeMillis < now || stopTimeMillis > now + 24 * 60 * 60 * 1000L) { // Max 24 hours future
            Log.e(TAG, "Invalid stop time: $stopTimeMillis")
            pendingResult?.finish()
            return
        }
        
        // Update Firebase - reset manual_override when scheduler starts conveyor
        database.child("conveyor/status").setValue(true)
        database.child("conveyor/stop_at").setValue(stopTimeMillis)
        database.child("conveyor/manual_override").setValue(false)
        
        // Log the activity
        val log = LogEntry(System.currentTimeMillis(), "Conveyor", "$label Schedule Started (Exact Alarm)")
        database.child("logs").push().setValue(log).addOnCompleteListener {
            // Finish pending result after log is written
            pendingResult?.finish()
        }
        
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
    
    private fun handleStopConveyor(context: Context, label: String, pendingResult: android.content.BroadcastReceiver.PendingResult) {
        val database = FirebaseDatabase.getInstance(ConfigManager.getDatabaseUrl()).reference
        
        // Stop the conveyor and reset manual_override to allow future scheduled operations
        database.child("conveyor/status").setValue(false)
        database.child("conveyor/stop_at").setValue(0L)
        database.child("conveyor/manual_override").setValue(false)
        
        // Log the activity and wait for it to complete before finishing
        val log = LogEntry(System.currentTimeMillis(), "Conveyor", "$label Schedule Finished (Exact Alarm)")
        database.child("logs").push().setValue(log).addOnCompleteListener {
            Log.d(TAG, "Log written for $label stop, finishing broadcast")
            pendingResult.finish()
        }
        
        Log.d(TAG, "Stopped conveyor for $label")
    }
    
    private fun rescheduleForTomorrow(context: Context, label: String) {
        // Re-read schedule from Firebase and reschedule
        val database = FirebaseDatabase.getInstance(ConfigManager.getDatabaseUrl()).reference
        
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

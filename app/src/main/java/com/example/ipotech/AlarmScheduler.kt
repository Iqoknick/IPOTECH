package com.example.ipotech

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.*

/**
 * Utility class for scheduling exact alarms for conveyor start/stop
 */
object AlarmScheduler {
    
    private const val TAG = "AlarmScheduler"
    
    const val ACTION_START_CONVEYOR = "com.example.ipotech.START_CONVEYOR"
    const val ACTION_STOP_CONVEYOR = "com.example.ipotech.STOP_CONVEYOR"
    const val EXTRA_LABEL = "label"
    const val EXTRA_DURATION_MINUTES = "duration_minutes"
    
    // Request codes for different schedule slots
    const val REQUEST_MORNING_START = 1001
    const val REQUEST_MORNING_STOP = 1002
    const val REQUEST_AFTERNOON_START = 1003
    const val REQUEST_AFTERNOON_STOP = 1004
    
    /**
     * Schedule a conveyor start at exact time
     */
    fun scheduleStart(
        context: Context,
        hour: Int,
        minute: Int,
        durationMinutes: Int,
        label: String,
        requestCode: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_START_CONVEYOR
            putExtra(EXTRA_LABEL, label)
            putExtra(EXTRA_DURATION_MINUTES, durationMinutes)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val triggerTime = getNextTriggerTime(hour, minute)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.d(TAG, "Scheduled exact START alarm for $label at ${Date(triggerTime)}")
                } else {
                    // Fallback to inexact alarm if permission not granted
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                    Log.w(TAG, "Using inexact alarm for $label (exact alarm permission not granted)")
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact START alarm for $label at ${Date(triggerTime)}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling alarm: ${e.message}")
            // Fallback
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }
    
    /**
     * Schedule a conveyor stop at exact time
     */
    fun scheduleStop(
        context: Context,
        stopTimeMillis: Long,
        label: String,
        requestCode: Int
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            action = ACTION_STOP_CONVEYOR
            putExtra(EXTRA_LABEL, label)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        stopTimeMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        stopTimeMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    stopTimeMillis,
                    pendingIntent
                )
            }
            Log.d(TAG, "Scheduled STOP alarm for $label at ${Date(stopTimeMillis)}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException scheduling stop alarm: ${e.message}")
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                stopTimeMillis,
                pendingIntent
            )
        }
    }
    
    /**
     * Cancel a scheduled alarm
     */
    fun cancelAlarm(context: Context, requestCode: Int, action: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
            this.action = action
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "Cancelled alarm with request code $requestCode")
    }
    
    /**
     * Cancel all scheduled alarms
     */
    fun cancelAllAlarms(context: Context) {
        cancelAlarm(context, REQUEST_MORNING_START, ACTION_START_CONVEYOR)
        cancelAlarm(context, REQUEST_MORNING_STOP, ACTION_STOP_CONVEYOR)
        cancelAlarm(context, REQUEST_AFTERNOON_START, ACTION_START_CONVEYOR)
        cancelAlarm(context, REQUEST_AFTERNOON_STOP, ACTION_STOP_CONVEYOR)
        Log.d(TAG, "Cancelled all alarms")
    }
    
    /**
     * Get the next trigger time for a given hour and minute
     * If the time has passed today, schedule for tomorrow
     */
    private fun getNextTriggerTime(hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // If time has passed today, schedule for tomorrow
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        return calendar.timeInMillis
    }
    
    /**
     * Parse time string "HH:mm" to hour and minute pair
     */
    fun parseTime(timeStr: String): Pair<Int, Int>? {
        return try {
            val parts = timeStr.split(":")
            if (parts.size == 2) {
                Pair(parts[0].toInt(), parts[1].toInt())
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

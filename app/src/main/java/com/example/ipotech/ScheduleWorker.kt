package com.example.ipotech

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ScheduleWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val KEY_ACTION = "action"
        const val KEY_LABEL = "label"
        const val DB_URL = "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
        
        fun enqueueNextCheck(context: Context, delayMinutes: Long = 1) {
            val nextCheck = OneTimeWorkRequestBuilder<ScheduleWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .addTag("SCHEDULE_LOOP")
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                "SCHEDULE_LOOP_WORK",
                ExistingWorkPolicy.REPLACE,
                nextCheck
            )
        }
    }

    override suspend fun doWork(): Result {
        val action = inputData.getString(KEY_ACTION) ?: ACTION_START
        val label = inputData.getString(KEY_LABEL) ?: "Scheduled"
        val database = FirebaseDatabase.getInstance(DB_URL).reference

        try {
            if (action == ACTION_STOP) {
                database.child("conveyor/status").setValue(false).await()
                database.child("conveyor/stop_at").setValue(0L).await()
                logActivity(database, "Conveyor", "$label Schedule Finished")
                return Result.success()
            }

            handleScheduleCheck(database)

        } catch (e: Exception) {
            Log.e("ScheduleWorker", "Error in doWork: ${e.message}")
        } finally {
            if (action != ACTION_STOP) {
                enqueueNextCheck(applicationContext, 1)
            }
        }
        return Result.success()
    }

    private suspend fun handleScheduleCheck(database: com.google.firebase.database.DatabaseReference) {
        val scheduleSnapshot = database.child("schedule").get().await()
        if (!scheduleSnapshot.exists()) return

        val masterEnabled = scheduleSnapshot.child("masterEnabled").getValue(Boolean::class.java) ?: true
        if (!masterEnabled) {
            stopConveyorIfRunning(database, "Master Disabled")
            return
        }

        val now = Calendar.getInstance()
        val currentDay = now.get(Calendar.DAY_OF_WEEK).toString()
        val activeDays = scheduleSnapshot.child("activeDays").getValue(String::class.java) ?: "1234567"
        
        if (!activeDays.contains(currentDay)) {
            stopConveyorIfRunning(database, "Inactive Day")
            return
        }

        val isOverride = database.child("conveyor/manual_override").get().await().getValue(Boolean::class.java) ?: false
        if (isOverride) return

        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val currentDate = sdfDate.format(now.time)
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val startDate = scheduleSnapshot.child("startDate").getValue(String::class.java) ?: ""
        val endDate = scheduleSnapshot.child("endDate").getValue(String::class.java) ?: ""

        if (startDate.isNotEmpty() && startDate != "---" && currentDate < startDate) return
        if (endDate.isNotEmpty() && endDate != "---" && currentDate > endDate) return

        checkWindow(database, scheduleSnapshot.child("morning"), currentMinutes, "Morning")
        // FIX: Changed label from "Afternoon" to "Evening"
        checkWindow(database, scheduleSnapshot.child("afternoon"), currentMinutes, "Evening")
    }

    private suspend fun stopConveyorIfRunning(database: com.google.firebase.database.DatabaseReference, reason: String) {
        val status = database.child("conveyor/status").get().await().getValue(Boolean::class.java) ?: false
        val isOverride = database.child("conveyor/manual_override").get().await().getValue(Boolean::class.java) ?: false
        
        if (status && !isOverride) {
            database.child("conveyor/status").setValue(false).await()
            database.child("conveyor/stop_at").setValue(0L).await()
            logActivity(database, "Conveyor", "Schedule Stopped ($reason)")
        }
    }

    private suspend fun checkWindow(
        database: com.google.firebase.database.DatabaseReference, 
        snapshot: com.google.firebase.database.DataSnapshot, 
        currentMinutes: Int, 
        label: String
    ) {
        val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
        val startTimeStr = snapshot.child("start").getValue(String::class.java) ?: ""
        val durationMinutes = snapshot.child("duration").value?.toString()?.toIntOrNull() ?: 0

        if (enabled && startTimeStr.contains(":") && durationMinutes > 0) {
            val parts = startTimeStr.split(":")
            val startMinutes = (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            val endMinutes = startMinutes + durationMinutes

            if (currentMinutes >= startMinutes && currentMinutes < endMinutes) {
                val isCurrentlyOn = database.child("conveyor/status").get().await().getValue(Boolean::class.java) ?: false
                
                if (!isCurrentlyOn) {
                    val stopCal = Calendar.getInstance()
                    stopCal.set(Calendar.HOUR_OF_DAY, endMinutes / 60)
                    stopCal.set(Calendar.MINUTE, endMinutes % 60)
                    stopCal.set(Calendar.SECOND, 0)
                    val targetStopAt = stopCal.timeInMillis

                    database.child("conveyor/status").setValue(true).await()
                    database.child("conveyor/stop_at").setValue(targetStopAt).await()
                    logActivity(database, "Conveyor", "$label Schedule Started")
                    enqueueStop(targetStopAt - System.currentTimeMillis(), label)
                }
            }
        }
    }

    private fun enqueueStop(delayMs: Long, label: String) {
        if (delayMs <= 0) return
        val stopRequest = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInputData(workDataOf(KEY_ACTION to ACTION_STOP, KEY_LABEL to label))
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag("STOP_CONVEYOR_$label")
            .build()
        
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "STOP_CONVEYOR_$label",
            ExistingWorkPolicy.REPLACE,
            stopRequest
        )
    }

    private suspend fun logActivity(database: com.google.firebase.database.DatabaseReference, action: String, details: String) {
        val log = LogEntry(System.currentTimeMillis(), action, details)
        database.child("logs").push().setValue(log).await()
    }
}

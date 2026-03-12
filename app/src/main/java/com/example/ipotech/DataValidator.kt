package com.example.ipotech

import android.util.Log
import com.google.firebase.database.DataSnapshot

/**
 * Validates all incoming Firebase data to prevent crashes and equipment damage
 */
object DataValidator {
    
    private const val TAG = "DataValidator"
    
    // Validation constants
    private const val MAX_TEMP_C = 300.0
    private const val MIN_TEMP_C = -50.0
    private const val MAX_STOP_TIME_FUTURE = 24 * 60 * 60 * 1000L // 24 hours in ms
    private const val MIN_STOP_TIME_PAST = -60 * 60 * 1000L // -1 hour in ms
    
    /**
     * Validate conveyor status data
     */
    fun validateConveyorData(snapshot: DataSnapshot): Boolean {
        try {
            val status = snapshot.child("status").getValue(Boolean::class.java)
            val stopAt = snapshot.child("stop_at").getValue(Long::class.java) ?: 0L
            val isOverride = snapshot.child("manual_override").getValue(Boolean::class.java) ?: false
            
            // Basic sanity checks
            if (status == null) {
                Log.e(TAG, "Invalid conveyor data: status is null")
                return false
            }
            
            if (stopAt < MIN_STOP_TIME_PAST) {
                Log.e(TAG, "Invalid conveyor data: stop_at too far in past: $stopAt")
                return false
            }
            
            if (stopAt > System.currentTimeMillis() + MAX_STOP_TIME_FUTURE) {
                Log.e(TAG, "Invalid conveyor data: stop_at too far in future: $stopAt")
                return false
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception validating conveyor data: ${e.message}")
            return false
        }
    }
    
    /**
     * Validate heater status data
     */
    fun validateHeaterData(snapshot: DataSnapshot): Boolean {
        try {
            val status = snapshot.child("status").getValue(Boolean::class.java)
            val stopAt = snapshot.child("stop_at").getValue(Long::class.java) ?: 0L
            val relayStatus = snapshot.child("relay_status").getValue(Boolean::class.java)
            val tempLow = snapshot.child("temp_low").getValue(Double::class.java)
            val tempHigh = snapshot.child("temp_high").getValue(Double::class.java)
            
            // Basic sanity checks
            if (status == null) {
                Log.e(TAG, "Invalid heater data: status is null")
                return false
            }
            
            if (stopAt < MIN_STOP_TIME_PAST || stopAt > System.currentTimeMillis() + MAX_STOP_TIME_FUTURE) {
                Log.e(TAG, "Invalid heater data: invalid stop_at: $stopAt")
                return false
            }
            
            // Validate temperature thresholds if present
            tempLow?.let { low ->
                if (low < 0 || low > 200) {
                    Log.e(TAG, "Invalid heater data: temp_low out of range: $low")
                    return false
                }
            }
            
            tempHigh?.let { high ->
                if (high < 0 || high > 250) {
                    Log.e(TAG, "Invalid heater data: temp_high out of range: $high")
                    return false
                }
            }
            
            // Validate temperature range logic
            if (tempLow != null && tempHigh != null) {
                if (tempLow >= tempHigh) {
                    Log.e(TAG, "Invalid heater data: temp_low ($tempLow) >= temp_high ($tempHigh)")
                    return false
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception validating heater data: ${e.message}")
            return false
        }
    }
    
    /**
     * Validate pulverizer status data
     */
    fun validatePulverizerData(snapshot: DataSnapshot): Boolean {
        try {
            val status = snapshot.getValue(Boolean::class.java)
            
            if (status == null) {
                Log.e(TAG, "Invalid pulverizer data: status is null")
                return false
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception validating pulverizer data: ${e.message}")
            return false
        }
    }
    
    /**
     * Validate temperature reading
     */
    fun validateTemperature(tempValue: Any?): Double? {
        return try {
            val temp = when (tempValue) {
                is Double -> tempValue
                is Long -> tempValue.toDouble()
                is Float -> tempValue.toDouble()
                is String -> tempValue.toDoubleOrNull()
                else -> null
            }
            
            // Sanity check temperature range
            if (temp == null || temp < MIN_TEMP_C || temp > MAX_TEMP_C) {
                Log.w(TAG, "Invalid temperature reading: $tempValue")
                null
            } else {
                temp
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception parsing temperature: ${e.message}")
            null
        }
    }
    
    /**
     * Validate schedule data
     */
    fun validateScheduleData(snapshot: DataSnapshot): Boolean {
        try {
            val masterEnabled = snapshot.child("masterEnabled").getValue(Boolean::class.java)
            val activeDays = snapshot.child("activeDays").getValue(String::class.java)
            val startDate = snapshot.child("startDate").getValue(String::class.java)
            val endDate = snapshot.child("endDate").getValue(String::class.java)
            
            // Validate master enabled
            if (masterEnabled == null) {
                Log.e(TAG, "Invalid schedule data: masterEnabled is null")
                return false
            }
            
            // Validate active days format
            activeDays?.let { days ->
                if (days.isNotEmpty() && !days.matches(Regex("^[1-7]*$"))) {
                    Log.e(TAG, "Invalid schedule data: activeDays format: $days")
                    return false
                }
            }
            
            // Validate date format if present
            listOf(startDate, endDate).forEach { date ->
                date?.let { d ->
                    if (d.isNotEmpty() && d != "---") {
                        try {
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)
                        } catch (e: Exception) {
                            Log.e(TAG, "Invalid schedule data: date format: $d")
                            return false
                        }
                    }
                }
            }
            
            // Validate morning schedule
            val morningEnabled = snapshot.child("morning/enabled").getValue(Boolean::class.java)
            val morningTime = snapshot.child("morning/start").getValue(String::class.java)
            val morningDuration = snapshot.child("morning/duration").value?.toString()?.toIntOrNull()
            
            if (morningEnabled == true) {
                if (!validateTimeFormat(morningTime)) {
                    Log.e(TAG, "Invalid schedule data: morning time format: $morningTime")
                    return false
                }
                if (morningDuration == null || morningDuration < 0 || morningDuration > 480) { // Max 8 hours
                    Log.e(TAG, "Invalid schedule data: morning duration: $morningDuration")
                    return false
                }
            }
            
            // Validate afternoon/evening schedule
            val afternoonEnabled = snapshot.child("afternoon/enabled").getValue(Boolean::class.java)
            val afternoonTime = snapshot.child("afternoon/start").getValue(String::class.java)
            val afternoonDuration = snapshot.child("afternoon/duration").value?.toString()?.toIntOrNull()
            
            if (afternoonEnabled == true) {
                if (!validateTimeFormat(afternoonTime)) {
                    Log.e(TAG, "Invalid schedule data: afternoon time format: $afternoonTime")
                    return false
                }
                if (afternoonDuration == null || afternoonDuration < 0 || afternoonDuration > 480) {
                    Log.e(TAG, "Invalid schedule data: afternoon duration: $afternoonDuration")
                    return false
                }
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception validating schedule data: ${e.message}")
            return false
        }
    }
    
    /**
     * Validate time format (HH:mm)
     */
    private fun validateTimeFormat(time: String?): Boolean {
        if (time == null) return false
        
        return try {
            val parts = time.split(":")
            if (parts.size != 2) return false
            
            val hour = parts[0].toIntOrNull()
            val minute = parts[1].toIntOrNull()
            
            hour != null && minute != null && hour in 0..23 && minute in 0..59
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validate log entry data
     */
    fun validateLogEntry(snapshot: DataSnapshot): Boolean {
        return try {
            val timestamp = snapshot.child("timestamp").getValue(Long::class.java)
            val action = snapshot.child("action").getValue(String::class.java)
            val details = snapshot.child("details").getValue(String::class.java)
            
            // Basic validation
            if (timestamp == null || action == null || details == null) {
                Log.e(TAG, "Invalid log entry: missing required fields")
                return false
            }
            
            // Validate timestamp is reasonable (not too far in future, not too old)
            val now = System.currentTimeMillis()
            if (timestamp > now + 60 * 60 * 1000L || timestamp < now - 30 * 24 * 60 * 60 * 1000L) {
                Log.e(TAG, "Invalid log entry: timestamp out of range: $timestamp")
                return false
            }
            
            // Validate action and details length
            if (action.length > 100 || details.length > 500) {
                Log.e(TAG, "Invalid log entry: action/details too long")
                return false
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception validating log entry: ${e.message}")
            false
        }
    }
    
    /**
     * Get safe boolean value with default
     */
    fun getSafeBoolean(snapshot: DataSnapshot, path: String, default: Boolean = false): Boolean {
        return try {
            snapshot.child(path).getValue(Boolean::class.java) ?: default
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get boolean at $path, using default: $default")
            default
        }
    }
    
    /**
     * Get safe long value with default
     */
    fun getSafeLong(snapshot: DataSnapshot, path: String, default: Long = 0L): Long {
        return try {
            snapshot.child(path).getValue(Long::class.java) ?: default
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get long at $path, using default: $default")
            default
        }
    }
    
    /**
     * Get safe double value with default
     */
    fun getSafeDouble(snapshot: DataSnapshot, path: String, default: Double = 0.0): Double {
        return try {
            snapshot.child(path).getValue(Double::class.java) ?: default
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get double at $path, using default: $default")
            default
        }
    }
    
    /**
     * Get safe string value with default
     */
    fun getSafeString(snapshot: DataSnapshot, path: String, default: String = ""): String {
        return try {
            snapshot.child(path).getValue(String::class.java) ?: default
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get string at $path, using default: $default")
            default
        }
    }
}

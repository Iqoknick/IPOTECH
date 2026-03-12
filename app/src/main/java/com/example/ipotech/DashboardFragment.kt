package com.example.ipotech

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.ipotech.databinding.FragmentDashboardBinding
import com.example.ipotech.databinding.ItemDeviceControlBinding
import com.google.firebase.database.*
import java.util.*

// SystemLogger import
import com.example.ipotech.SystemLogger
import com.example.ipotech.SystemLogger.LogCategory

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private lateinit var database: DatabaseReference
    private lateinit var logAdapter: LogAdapter
    private val logList = mutableListOf<LogEntry>()

    private var conveyorTimer: CountDownTimer? = null
    private var heaterTimer: CountDownTimer? = null
    
    private var isHeaterOn = false
    private var isConveyorOn = false
    private var isPulverizerOn = false
    
    // Default Settings (Matches your ESP32 code: 120-130°C)
    private var targetLow = 120.0
    private var targetHigh = 130.0
    
    // Connection state listener
    private var connectionListener: ValueEventListener? = null
    private var heaterSettingsListener: ValueEventListener? = null
    private var temperatureListener: ValueEventListener? = null
    private var heaterStatusListener: ValueEventListener? = null
    private var conveyorListener: ValueEventListener? = null
    private var pulverizerListener: ValueEventListener? = null
    private var logsListener: ValueEventListener? = null
    private var isConnected = true
    
    // Temperature history saving (will be loaded from settings)
    private var lastTempSaveTime = 0L
    private var tempSaveInterval = 5 * 60 * 1000L // Default 5 minutes
    
    // Temperature alert tracking (will be loaded from settings)
    private var lastAlertTime = 0L
    private var alertCooldown = 30 * 1000L // Default 30 seconds
    private var criticalThreshold = 10 // Default 10°C above target
    private var vibrationEnabled = true
    private var notificationsEnabled = true
    private var lastAlertType: String? = null
    private val NOTIFICATION_ID_TEMP = 1001
    private val TAG = "DashboardFragment"
    
    // Permission request launcher
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, alerts will now work
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = FirebaseDatabase.getInstance(ConfigManager.getDatabaseUrl()).reference

        loadSettings()
        setupRecyclerView()
        setupControls()
        setupEmergencyStop()
        observeFirebase()
        setupUIFocusHandling()
        setupConnectionListener()
        requestNotificationPermission()
        
        // Initialize temperature gauge thresholds
        binding.temperatureGauge.setThresholds(targetLow.toFloat(), targetHigh.toFloat())
        binding.temperatureGauge.setRange(0f, 200f)
    }
    
    /**
     * Load settings from SharedPreferences
     */
    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(IpoTechApplication.PREFS_NAME, Context.MODE_PRIVATE)
        
        criticalThreshold = getSafeInt(prefs, IpoTechApplication.KEY_CRITICAL_THRESHOLD, 10)
        
        // Load cooldown as Float to support 0.5 minutes (30 seconds)
        val cooldownMin = getSafeFloat(prefs, IpoTechApplication.KEY_ALERT_COOLDOWN, 0.5f)
        alertCooldown = (cooldownMin * 60 * 1000).toLong()
        
        vibrationEnabled = prefs.getBoolean(IpoTechApplication.KEY_VIBRATION, true)
        notificationsEnabled = prefs.getBoolean(IpoTechApplication.KEY_NOTIFICATIONS, true)
        
        val saveIntMin = getSafeFloat(prefs, IpoTechApplication.KEY_SAVE_INTERVAL, 5f)
        tempSaveInterval = (saveIntMin * 60 * 1000).toLong()
        
        Log.d(TAG, "Settings Loaded: Threshold=$criticalThreshold, CooldownMs=$alertCooldown, Notifications=$notificationsEnabled")
    }

    private fun getSafeInt(prefs: android.content.SharedPreferences, key: String, default: Int): Int {
        return try {
            val value = prefs.getString(key, default.toString()) ?: default.toString()
            value.toIntOrNull() ?: default
        } catch (e: Exception) {
            prefs.getInt(key, default)
        }
    }

    private fun getSafeFloat(prefs: android.content.SharedPreferences, key: String, default: Float): Float {
        return try {
            val value = prefs.getString(key, default.toString()) ?: default.toString()
            value.toFloatOrNull() ?: default
        } catch (e: Exception) {
            prefs.getFloat(key, default)
        }
    }
    
    /**
     * Request notification permission for Android 13+
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    /**
     * Vibrate the device for haptic feedback
     */
    private fun vibrate(isAlert: Boolean = false) {
        if (!vibrationEnabled) return
        val ctx = context ?: return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (isAlert) {
                // Short triple burst pattern for alerts: 200ms on, 100ms off, repeated
                val pattern = longArrayOf(0, 200, 100, 200, 100, 200)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            } else {
                // Short single tap for UI clicks
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            }
        } catch (e: Exception) {
            // Vibration not available
        }
    }
    
    /**
     * Setup Firebase connection state listener
     */
    private fun setupConnectionListener() {
        val connectedRef = FirebaseDatabase.getInstance(ConfigManager.getDatabaseUrl())
            .getReference(".info/connected")
        
        connectionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                isConnected = snapshot.getValue(Boolean::class.java) ?: false
                updateConnectionUI()
            }
            override fun onCancelled(error: DatabaseError) {
                if (_binding == null) return
                isConnected = false
                updateConnectionUI()
            }
        }
        connectedRef.addValueEventListener(connectionListener!!)
    }
    
    /**
     * Update the offline indicator UI based on connection state
     */
    private fun updateConnectionUI() {
        if (_binding == null) return
        binding.offlineIndicator.visibility = if (isConnected) View.GONE else View.VISIBLE
        
        // Change temperature card border to red when offline
        if (!isConnected) {
            binding.cardTemperature.strokeColor = ContextCompat.getColor(requireContext(), R.color.industrial_off_active)
        } else {
            binding.cardTemperature.strokeColor = ContextCompat.getColor(requireContext(), R.color.industrial_stroke)
        }
    }

    private fun setupUIFocusHandling() {
        // Clear focus and hide keyboard when clicking on the background
        binding.dashboardScrollView.setOnTouchListener { v, _ ->
            hideKeyboardAndClearFocus()
            false // return false so scroll still works
        }
    }

    private fun hideKeyboardAndClearFocus() {
        val focusedView = activity?.currentFocus ?: return
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(focusedView.windowToken, 0)
        focusedView.clearFocus()
    }

    private fun setupEmergencyStop() {
        binding.btnEmergencyStop.setOnClickListener {
            // Strong vibration for emergency stop
            vibrate(isAlert = true)
            
            // Log emergency stop action
            SystemLogger.logCritical(LogCategory.USER_ACTION, "EMERGENCY STOP ACTIVATED", mapOf(
                "trigger" to "physical_button",
                "timestamp" to System.currentTimeMillis()
            ))
            
            val stopUpdates = HashMap<String, Any>()
            stopUpdates["conveyor/status"] = false
            stopUpdates["pulverizer/status"] = false
            stopUpdates["heater/status"] = false
            stopUpdates["heater/relay_status"] = false
            stopUpdates["heater/stop_at"] = 0L
            stopUpdates["schedule/masterEnabled"] = false
            
            // Use error recovery for critical emergency stop
            ErrorRecoveryManager.safeWrite(database, "", stopUpdates) { success ->
                if (success) {
                    if (context != null) {
                        Toast.makeText(requireContext(), "EMERGENCY STOP ACTIVATED", Toast.LENGTH_LONG).show()
                        logActivity("SYSTEM", "EMERGENCY STOP ACTIVATED")
                        SystemLogger.logInfo(LogCategory.DEVICE_CONTROL, "Emergency stop successful", mapOf(
                            "devices_stopped" to listOf("conveyor", "pulverizer", "heater")
                        ))
                    }
                } else {
                    // Emergency stop failed - show critical error
                    if (context != null && _binding != null) {
                        Toast.makeText(
                            requireContext(), 
                            "EMERGENCY STOP FAILED - USE PHYSICAL BUTTONS!", 
                            Toast.LENGTH_LONG
                        ).show()
                        vibrate(isAlert = true) // Additional vibration for failed stop
                        
                        SystemLogger.logCritical(LogCategory.DEVICE_CONTROL, "EMERGENCY STOP FAILED", mapOf(
                            "error" to "firebase_write_failed",
                            "fallback_required" to "physical_buttons"
                        ))
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        logAdapter = LogAdapter(logList)
        binding.rvActivityLog.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
        }
    }

    private fun setupControls() {
        binding.controlHeater.tvDeviceName.text = getString(R.string.heater)
        binding.controlHeater.btnOn.setOnClickListener {
            vibrate()
            updateDeviceStatus("heater", true)
            SystemLogger.logUserAction("Heater turned ON", mapOf("device" to "heater"))
            logActivity("Heater", "Manual ON")
        }
        binding.controlHeater.btnOff.setOnClickListener {
            vibrate()
            updateDeviceStatus("heater", false)
            SystemLogger.logUserAction("Heater turned OFF", mapOf("device" to "heater"))
            logActivity("Heater", "Manual OFF")
        }

        binding.controlConveyor.tvDeviceName.text = getString(R.string.conveyor)
        binding.controlConveyor.btnOn.setOnClickListener {
            vibrate()
            updateDeviceStatus("conveyor", true)
            SystemLogger.logUserAction("Conveyor turned ON", mapOf("device" to "conveyor"))
            logActivity("Conveyor", "Manual ON")
        }
        binding.controlConveyor.btnOff.setOnClickListener {
            vibrate()
            updateDeviceStatus("conveyor", false)
            SystemLogger.logUserAction("Conveyor turned OFF", mapOf("device" to "conveyor"))
            logActivity("Conveyor", "Manual OFF")
        }

        binding.controlPulverizer.tvDeviceName.text = getString(R.string.pulverizer)
        binding.controlPulverizer.btnOn.setOnClickListener {
            vibrate()
            updateDeviceStatus("pulverizer", true)
            SystemLogger.logUserAction("Pulverizer turned ON", mapOf("device" to "pulverizer"))
            logActivity("Pulverizer", "Manual ON")
        }
        binding.controlPulverizer.btnOff.setOnClickListener {
            vibrate()
            updateDeviceStatus("pulverizer", false)
            SystemLogger.logUserAction("Pulverizer turned OFF", mapOf("device" to "pulverizer"))
            logActivity("Pulverizer", "Manual OFF")
        }
        
        binding.switchManualOverride.setOnCheckedChangeListener { _, isChecked ->
            vibrate() // Haptic feedback
            ErrorRecoveryManager.safeWrite(database, "conveyor/manual_override", isChecked) { success ->
                if (!success) {
                    // Show error and revert switch
                    if (_binding != null) {
                        binding.switchManualOverride.setOnCheckedChangeListener(null)
                        binding.switchManualOverride.isChecked = !isChecked
                        binding.switchManualOverride.setOnCheckedChangeListener { _, newChecked ->
                            vibrate()
                            ErrorRecoveryManager.safeWrite(database, "conveyor/manual_override", newChecked) { }
                        }
                        Toast.makeText(
                            requireContext(), 
                            "Failed to update manual override", 
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    logActivity("Manual Override", if (isChecked) "ENABLED" else "DISABLED")
                }
            }
        }

        // Update Hysteresis Button
        binding.btnUpdateHysteresis.setOnClickListener {
            vibrate() // Haptic feedback
            val low = binding.etTempLow.text.toString().toDoubleOrNull() ?: 120.0
            val high = binding.etTempHigh.text.toString().toDoubleOrNull() ?: 130.0
            
            val updates = HashMap<String, Any>()
            updates["heater/temp_low"] = low
            updates["heater/temp_high"] = high
            
            ErrorRecoveryManager.safeWrite(database, "heater", updates) { success ->
                if (success) {
                    if (context != null) {
                        Toast.makeText(requireContext(), "Hysteresis Updated: $low°C - $high°C", Toast.LENGTH_SHORT).show()
                        logActivity("Heater", "Settings Updated: $low - $high°C")
                        hideKeyboardAndClearFocus()
                        
                        // Update gauge thresholds
                        binding.temperatureGauge.setThresholds(low.toFloat(), high.toFloat())
                    }
                } else {
                    if (context != null && _binding != null) {
                        Toast.makeText(
                            requireContext(), 
                            "Failed to update hysteresis settings", 
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun updateDeviceStatus(device: String, status: Boolean) {
        val updates = HashMap<String, Any>()
        updates["status"] = status
        updates["stop_at"] = 0L 
        
        // NOTE: relay_status is now handled by the ESP32 locally to prevent flickering/loops
        if (!status && device == "heater") {
            updates["relay_status"] = false
        }
        
        // Use error recovery for critical device status updates
        ErrorRecoveryManager.safeWrite(database, device, updates) { success ->
            if (!success) {
                // Show error to user
                if (context != null && _binding != null) {
                    Toast.makeText(
                        requireContext(), 
                        "Failed to update $device - please retry", 
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun observeFirebase() {
        // Sync Hysteresis Settings from Firebase
        heaterSettingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                
                // Validate heater data
                if (!DataValidator.validateHeaterData(snapshot)) {
                    Log.e(TAG, "Invalid heater settings data received, ignoring")
                    return
                }
                
                targetLow = DataValidator.getSafeDouble(snapshot, "temp_low", 120.0)
                targetHigh = DataValidator.getSafeDouble(snapshot, "temp_high", 130.0)
                
                // Update UI fields if they are not being edited
                if (!binding.etTempLow.hasFocus()) {
                    binding.etTempLow.setText(targetLow.toInt().toString())
                }
                if (!binding.etTempHigh.hasFocus()) {
                    binding.etTempHigh.setText(targetHigh.toInt().toString())
                }
                
                // Update gauge thresholds
                binding.temperatureGauge.setThresholds(targetLow.toFloat(), targetHigh.toFloat())
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("heater").addValueEventListener(heaterSettingsListener!!)

        // Temperature Observer - Update Gauge
        temperatureListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                
                // Validate temperature data
                val tempValue = DataValidator.validateTemperature(snapshot.value)
                if (tempValue == null) {
                    Log.e(TAG, "Invalid temperature data received, ignoring")
                    SystemLogger.logWarning(LogCategory.TEMPERATURE, "Invalid temperature data received", mapOf(
                        "raw_value" to (snapshot.value?.toString() ?: "null")
                    ))
                    return
                }
                
                Log.d(TAG, "Temp Updated: $tempValue°C")
                SystemLogger.logTemperature(tempValue, "updated", mapOf(
                    "source" to "firebase",
                    "valid" to true
                ))

                // Update text (hidden, for compatibility)
                binding.tvTemperature.text = String.format("%.1f°C", tempValue)
                
                // Update the temperature gauge
                binding.temperatureGauge.setTemperature(tempValue.toFloat())
                
                // Change card border color based on temperature
                updateTemperatureCardUI(tempValue)
                
                // Save to temperature history (throttled to every 5 minutes)
                saveTemperatureHistory(tempValue)
                
                // Check for temperature alerts
                checkTemperatureAlert(tempValue)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase Temp Read Failed", error.toException())
                SystemLogger.logError(LogCategory.DATABASE, "Temperature read failed", mapOf(
                    "error" to error.message
                ), error.toException())
            }
        }
        database.child("temperature").child("current").addValueEventListener(temperatureListener!!)

        // Heater Status Observer
        heaterStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                
                // Validate data before processing
                if (!DataValidator.validateHeaterData(snapshot)) {
                    Log.e(TAG, "Invalid heater data received, ignoring")
                    return
                }
                
                val isHeaterOn = DataValidator.getSafeBoolean(snapshot, "status", false)
                val stopAt = DataValidator.getSafeLong(snapshot, "stop_at", 0L)
                
                updateControlUI(binding.controlHeater, isHeaterOn)

                if (isHeaterOn && stopAt > System.currentTimeMillis()) {
                    startHeaterTimer(stopAt)
                } else {
                    stopHeaterTimer()
                    if (isHeaterOn && stopAt > 0 && stopAt <= System.currentTimeMillis()) {
                        updateDeviceStatus("heater", false)
                        logActivity("Heater", "Cycle Complete - Automatic Shutdown")
                    } else {
                        binding.tvOvenCountdown.text = "Ready"
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("heater").addValueEventListener(heaterStatusListener!!)

        // Conveyor Status Observer
        conveyorListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                
                // Validate data before processing
                if (!DataValidator.validateConveyorData(snapshot)) {
                    Log.e(TAG, "Invalid conveyor data received, ignoring")
                    SystemLogger.logWarning(LogCategory.DATABASE, "Invalid conveyor data received", mapOf(
                        "data_snapshot" to (snapshot.value?.toString() ?: "null")
                    ) as Map<String, Any>)
                    return
                }
                
                val newStatus = DataValidator.getSafeBoolean(snapshot, "status", false)
                val stopAt = DataValidator.getSafeLong(snapshot, "stop_at", 0L)
                val isOverride = DataValidator.getSafeBoolean(snapshot, "manual_override", false)
                
                SystemLogger.logDeviceControl("conveyor", "status_updated", mapOf(
                    "status" to newStatus,
                    "stop_at" to stopAt,
                    "manual_override" to isOverride,
                    "source" to "firebase"
                ))
                
                binding.switchManualOverride.setOnCheckedChangeListener(null)
                binding.switchManualOverride.isChecked = isOverride
                binding.switchManualOverride.setOnCheckedChangeListener { _, isChecked ->
                    vibrate()
                    ErrorRecoveryManager.safeWrite(database, "conveyor/manual_override", isChecked) { }
                }
                isConveyorOn = newStatus
                updateControlUI(binding.controlConveyor, isConveyorOn)
                if (isConveyorOn && stopAt > System.currentTimeMillis()) {
                    startConveyorTimer(stopAt)
                } else {
                    stopConveyorTimer()
                    // If timer expired but status still true, update Firebase to sync state
                    if (isConveyorOn && stopAt > 0 && stopAt <= System.currentTimeMillis()) {
                        updateDeviceStatus("conveyor", false)
                        logActivity("Conveyor", "Timer Expired - Auto Stop")
                        SystemLogger.logInfo(LogCategory.DEVICE_CONTROL, "Conveyor timer expired - auto stop", mapOf(
                            "stop_at" to stopAt,
                            "current_time" to System.currentTimeMillis()
                        ))
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Conveyor data read failed", error.toException())
                SystemLogger.logError(LogCategory.DATABASE, "Conveyor data read failed", mapOf(
                    "error" to error.message
                ), error.toException())
            }
        }
        database.child("conveyor").addValueEventListener(conveyorListener!!)

        pulverizerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                
                // Validate pulverizer data
                if (!DataValidator.validatePulverizerData(snapshot)) {
                    Log.e(TAG, "Invalid pulverizer data received, ignoring")
                    return
                }
                
                isPulverizerOn = DataValidator.getSafeBoolean(snapshot, "status", false)
                updateControlUI(binding.controlPulverizer, isPulverizerOn)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("pulverizer/status").addValueEventListener(pulverizerListener!!)

        logsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                logList.clear()
                for (logSnapshot in snapshot.children) {
                    // Validate log entry before adding
                    if (DataValidator.validateLogEntry(logSnapshot)) {
                        val log = logSnapshot.getValue(LogEntry::class.java)
                        if (log != null) logList.add(0, log)
                    } else {
                        Log.w(TAG, "Skipping invalid log entry")
                    }
                }
                logAdapter.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("logs").limitToLast(20).addValueEventListener(logsListener!!)
    }

    private fun startHeaterTimer(stopAt: Long) {
        heaterTimer?.cancel()
        heaterTimer = object : CountDownTimer(stopAt - System.currentTimeMillis(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (_binding == null || !isAdded) return
                val totalSeconds = millisUntilFinished / 1000
                binding.tvOvenCountdown.text = String.format("%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
            }
            override fun onFinish() {
                if (_binding != null && isAdded) {
                    binding.tvOvenCountdown.text = "Ready"
                    updateDeviceStatus("heater", false)
                }
            }
        }.start()
    }

    private fun stopHeaterTimer() {
        heaterTimer?.cancel()
        if (_binding != null) binding.tvOvenCountdown.text = "Ready"
    }

    private fun startConveyorTimer(stopAt: Long) {
        conveyorTimer?.cancel()
        conveyorTimer = object : CountDownTimer(stopAt - System.currentTimeMillis(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (_binding == null || !isAdded) return
                val totalSeconds = millisUntilFinished / 1000
                binding.tvConveyorTimer.text = String.format("%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
            }
            override fun onFinish() {
                if (_binding != null && isAdded) {
                    binding.tvConveyorTimer.text = "00:00:00"
                    updateDeviceStatus("conveyor", false)
                }
            }
        }.start()
    }

    private fun stopConveyorTimer() {
        conveyorTimer?.cancel()
        if (_binding != null) binding.tvConveyorTimer.text = "00:00:00"
    }

    private fun updateControlUI(controlBinding: ItemDeviceControlBinding, isOn: Boolean) {
        if (_binding == null || !isAdded) return
        val context = context ?: return
        
        // Industrial LED indicator: use glow drawable when ON, regular when OFF
        if (isOn) {
            controlBinding.statusIndicator.setBackgroundResource(R.drawable.led_indicator_glow)
            controlBinding.statusIndicator.backgroundTintList = null // Use drawable colors
            val pulseAnimation = AnimationUtils.loadAnimation(context, R.anim.pulse_heartbeat)
            controlBinding.statusIndicator.startAnimation(pulseAnimation)
        } else {
            controlBinding.statusIndicator.setBackgroundResource(R.drawable.led_indicator)
            controlBinding.statusIndicator.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.led_off)
            )
            controlBinding.statusIndicator.clearAnimation()
        }
        
        // Industrial toggle button styling
        val onActiveColor = ContextCompat.getColor(context, R.color.industrial_on_active)
        val offActiveColor = ContextCompat.getColor(context, R.color.industrial_off_active)
        val inactiveColor = ContextCompat.getColor(context, R.color.industrial_on_inactive)
        val activeTextColor = ContextCompat.getColor(context, R.color.industrial_text_active)
        val inactiveTextColor = ContextCompat.getColor(context, R.color.industrial_text_inactive)

        // ON button: bright green when active, dark industrial gray when inactive
        controlBinding.btnOn.backgroundTintList = ColorStateList.valueOf(if (isOn) onActiveColor else inactiveColor)
        controlBinding.btnOn.setTextColor(if (isOn) activeTextColor else inactiveTextColor)
        
        // OFF button: red when active, dark industrial gray when inactive
        controlBinding.btnOff.backgroundTintList = ColorStateList.valueOf(if (!isOn) offActiveColor else inactiveColor)
        controlBinding.btnOff.setTextColor(if (!isOn) activeTextColor else inactiveTextColor)
    }

    private fun logActivity(action: String, details: String) {
        val log = LogEntry(System.currentTimeMillis(), action, details)
        ErrorRecoveryManager.safeWrite(database, "logs", log) { success ->
            if (!success) {
                Log.w(TAG, "Failed to log activity: $action - $details")
            }
        }
    }
    
    /**
     * Update temperature card UI based on temperature value
     */
    private fun updateTemperatureCardUI(temp: Double) {
        if (_binding == null || !isAdded) return
        val ctx = context ?: return
        
        // Don't change if offline
        if (!isConnected) return
        
        val strokeColor = when {
            temp > targetHigh -> ContextCompat.getColor(ctx, R.color.industrial_off_active) // Red - too hot
            temp < targetLow && temp > 0 -> ContextCompat.getColor(ctx, R.color.primary_teal) // Teal - warming up
            else -> ContextCompat.getColor(ctx, R.color.industrial_stroke) // Normal
        }
        binding.cardTemperature.strokeColor = strokeColor
    }
    
    /**
     * Save temperature reading to history (throttled based on settings)
     */
    private fun saveTemperatureHistory(temp: Double) {
        val currentTime = System.currentTimeMillis()
        
        // Only save if enough time has passed since last save
        if (currentTime - lastTempSaveTime < tempSaveInterval) return
        
        // Don't save invalid readings
        if (temp <= 0 || temp > 500) return
        
        lastTempSaveTime = currentTime
        
        val historyEntry = mapOf(
            "timestamp" to currentTime,
            "value" to temp
        )
        
        database.child("temperature_history").push().setValue(historyEntry)
    }
    
    /**
     * Check if temperature exceeds thresholds and show alert
     */
    private fun checkTemperatureAlert(temp: Double) {
        if (!notificationsEnabled) return

        val currentTime = System.currentTimeMillis()
        if (temp <= 0 || temp > 500) return
        
        val alertType = when {
            temp > targetHigh + criticalThreshold -> "critical_high"
            temp > targetHigh -> "high"
            temp < targetLow - 20 && temp > 0 -> "low"
            else -> null
        }
        
        if (alertType == null) {
            if (lastAlertType != null) Log.d(TAG, "Temperature is normal. Alert state reset.")
            lastAlertType = null
            return
        }
        
        val shouldAlert = when {
            lastAlertType == null -> true
            alertType == "critical_high" && lastAlertType != "critical_high" -> {
                Log.d(TAG, "Critical Alert Priority! Bypassing cooldown.")
                true
            }
            else -> {
                val elapsed = currentTime - lastAlertTime
                Log.d(TAG, "Current alert type: $alertType. Elapsed: ${elapsed/1000}s, Required: ${alertCooldown/1000}s")
                elapsed > alertCooldown
            }
        }
        
        if (shouldAlert) {
            lastAlertTime = currentTime
            lastAlertType = alertType
            showTemperatureAlert(temp, alertType)
        }
    }
    
    /**
     * Show temperature alert notification
     */
    private fun showTemperatureAlert(temp: Double, alertType: String) {
        val ctx = context ?: return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        }
        
        val (title, message, priority) = when (alertType) {
            "critical_high" -> Triple("⚠️ CRITICAL", "Temp: ${String.format("%.1f", temp)}°C - Over safe limit!", NotificationCompat.PRIORITY_MAX)
            "high" -> Triple("Temp Warning", "Temp: ${String.format("%.1f", temp)}°C - Above range.", NotificationCompat.PRIORITY_HIGH)
            "low" -> Triple("Temp Low", "Temp: ${String.format("%.1f", temp)}°C - Below range.", NotificationCompat.PRIORITY_DEFAULT)
            else -> return
        }
        
        val intent = Intent(ctx, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        val notification = NotificationCompat.Builder(ctx, IpoTechApplication.CHANNEL_TEMP_ALERTS)
            .setSmallIcon(R.drawable.ic_dashboard)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 200, 100, 200, 100, 200))
            .build()
        
        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_TEMP, notification)
        
        // Vibrate with short triple burst pattern
        vibrate(isAlert = true)

        Log.d(TAG, "NOTIFICATION SENT: $title")
    }

    private fun removeAllListeners() {
        heaterSettingsListener?.let { database.child("heater").removeEventListener(it) }
        temperatureListener?.let { database.child("temperature").child("current").removeEventListener(it) }
        heaterStatusListener?.let { database.child("heater").removeEventListener(it) }
        conveyorListener?.let { database.child("conveyor").removeEventListener(it) }
        pulverizerListener?.let { database.child("pulverizer/status").removeEventListener(it) }
        logsListener?.let { database.child("logs").removeEventListener(it) }
        connectionListener?.let {
            FirebaseDatabase.getInstance("https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/")
                .getReference(".info/connected").removeEventListener(it)
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        // Re-register listeners when fragment becomes visible (prevents leaks in back stack)
        if (::database.isInitialized) {
            observeFirebase()
            setupConnectionListener()
        }
    }

    override fun onPause() {
        super.onPause()
        // Remove listeners when fragment is not visible (prevents memory leaks)
        removeAllListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.controlHeater?.statusIndicator?.clearAnimation()
        _binding?.controlConveyor?.statusIndicator?.clearAnimation()
        _binding?.controlPulverizer?.statusIndicator?.clearAnimation()
        // Safety cleanup: remove all listeners and cancel timers
        removeAllListeners()
        _binding = null
        conveyorTimer?.cancel()
        heaterTimer?.cancel()
        // Cancel any pending error recovery operations
        ErrorRecoveryManager.cancelAllOperations()
    }
}

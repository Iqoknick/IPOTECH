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

        database = FirebaseDatabase.getInstance("https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/").reference

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
        val connectedRef = FirebaseDatabase.getInstance("https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/")
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
            
            val stopUpdates = HashMap<String, Any>()
            stopUpdates["conveyor/status"] = false
            stopUpdates["pulverizer/status"] = false
            stopUpdates["heater/status"] = false
            stopUpdates["heater/relay_status"] = false
            stopUpdates["heater/stop_at"] = 0L
            stopUpdates["schedule/masterEnabled"] = false
            
            database.updateChildren(stopUpdates).addOnSuccessListener {
                if (context != null) {
                    Toast.makeText(requireContext(), "EMERGENCY STOP ACTIVATED", Toast.LENGTH_LONG).show()
                    logActivity("SYSTEM", "EMERGENCY STOP ACTIVATED")
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
            logActivity("Heater", "Manual ON")
        }
        binding.controlHeater.btnOff.setOnClickListener {
            vibrate()
            updateDeviceStatus("heater", false)
            logActivity("Heater", "Manual OFF")
        }

        binding.controlConveyor.tvDeviceName.text = getString(R.string.conveyor)
        binding.controlConveyor.btnOn.setOnClickListener {
            vibrate()
            updateDeviceStatus("conveyor", true)
            logActivity("Conveyor", "Manual ON")
        }
        binding.controlConveyor.btnOff.setOnClickListener {
            vibrate()
            updateDeviceStatus("conveyor", false)
            logActivity("Conveyor", "Manual OFF")
        }

        binding.controlPulverizer.tvDeviceName.text = getString(R.string.pulverizer)
        binding.controlPulverizer.btnOn.setOnClickListener {
            vibrate()
            updateDeviceStatus("pulverizer", true)
            logActivity("Pulverizer", "Manual ON")
        }
        binding.controlPulverizer.btnOff.setOnClickListener {
            vibrate()
            updateDeviceStatus("pulverizer", false)
            logActivity("Pulverizer", "Manual OFF")
        }
        
        binding.switchManualOverride.setOnCheckedChangeListener { _, isChecked ->
            vibrate() // Haptic feedback
            database.child("conveyor").child("manual_override").setValue(isChecked)
            logActivity("Manual Override", if (isChecked) "ENABLED" else "DISABLED")
        }

        // Update Hysteresis Button
        binding.btnUpdateHysteresis.setOnClickListener {
            vibrate() // Haptic feedback
            val low = binding.etTempLow.text.toString().toDoubleOrNull() ?: 120.0
            val high = binding.etTempHigh.text.toString().toDoubleOrNull() ?: 130.0
            
            val updates = HashMap<String, Any>()
            updates["heater/temp_low"] = low
            updates["heater/temp_high"] = high
            
            database.updateChildren(updates).addOnSuccessListener {
                if (context != null) {
                    Toast.makeText(requireContext(), "Hysteresis Updated: $low°C - $high°C", Toast.LENGTH_SHORT).show()
                    logActivity("Heater", "Settings Updated: $low - $high°C")
                    hideKeyboardAndClearFocus()
                    
                    // Update gauge thresholds
                    binding.temperatureGauge.setThresholds(low.toFloat(), high.toFloat())
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
        database.child(device).updateChildren(updates)
    }

    private fun observeFirebase() {
        // Sync Hysteresis Settings from Firebase
        heaterSettingsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                targetLow = snapshot.child("temp_low").getValue(Double::class.java) ?: 120.0
                targetHigh = snapshot.child("temp_high").getValue(Double::class.java) ?: 130.0
                
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
                val tempValue = when (val temp = snapshot.value) {
                    is Double -> temp
                    is Long -> temp.toDouble()
                    else -> temp?.toString()?.toDoubleOrNull() ?: 0.0
                }
                
                Log.d(TAG, "Temp Updated: $tempValue°C")

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
            }
        }
        database.child("temperature").child("current").addValueEventListener(temperatureListener!!)

        // Heater Status Observer
        heaterStatusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                isHeaterOn = snapshot.child("status").getValue(Boolean::class.java) ?: false
                val stopAt = snapshot.child("stop_at").getValue(Long::class.java) ?: 0L
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
                val newStatus = snapshot.child("status").getValue(Boolean::class.java) ?: false
                val stopAt = snapshot.child("stop_at").getValue(Long::class.java) ?: 0L
                val isOverride = snapshot.child("manual_override").getValue(Boolean::class.java) ?: false
                binding.switchManualOverride.setOnCheckedChangeListener(null)
                binding.switchManualOverride.isChecked = isOverride
                binding.switchManualOverride.setOnCheckedChangeListener { _, isChecked ->
                    database.child("conveyor").child("manual_override").setValue(isChecked)
                    logActivity("Manual Override", if (isChecked) "ENABLED" else "DISABLED")
                }
                isConveyorOn = newStatus
                updateControlUI(binding.controlConveyor, isConveyorOn)
                if (isConveyorOn && stopAt > System.currentTimeMillis()) {
                    startConveyorTimer(stopAt)
                } else {
                    stopConveyorTimer()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.child("conveyor").addValueEventListener(conveyorListener!!)

        pulverizerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                isPulverizerOn = snapshot.getValue(Boolean::class.java) ?: false
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
                    val log = logSnapshot.getValue(LogEntry::class.java)
                    if (log != null) logList.add(0, log)
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
        database.child("logs").push().setValue(log)
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
    }
}

package com.example.ipotech

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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

        setupRecyclerView()
        setupControls()
        setupEmergencyStop()
        observeFirebase()
        setupUIFocusHandling()
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
        val heaterToggle = View.OnClickListener {
            val newState = !isHeaterOn
            updateDeviceStatus("heater", newState)
            logActivity("Heater", if (newState) "Manual ON" else "Manual OFF")
        }
        binding.controlHeater.btnOn.setOnClickListener(heaterToggle)
        binding.controlHeater.btnOff.setOnClickListener(heaterToggle)

        binding.controlConveyor.tvDeviceName.text = getString(R.string.conveyor)
        val conveyorToggle = View.OnClickListener {
            val newState = !isConveyorOn
            updateDeviceStatus("conveyor", newState)
            logActivity("Conveyor", if (newState) "Manual ON" else "Manual OFF")
        }
        binding.controlConveyor.btnOn.setOnClickListener(conveyorToggle)
        binding.controlConveyor.btnOff.setOnClickListener(conveyorToggle)

        binding.controlPulverizer.tvDeviceName.text = getString(R.string.pulverizer)
        val pulverizerToggle = View.OnClickListener {
            val newState = !isPulverizerOn
            updateDeviceStatus("pulverizer", newState)
            logActivity("Pulverizer", if (newState) "Manual ON" else "Manual OFF")
        }
        binding.controlPulverizer.btnOn.setOnClickListener(pulverizerToggle)
        binding.controlPulverizer.btnOff.setOnClickListener(pulverizerToggle)
        
        binding.switchManualOverride.setOnCheckedChangeListener { _, isChecked ->
            database.child("conveyor").child("manual_override").setValue(isChecked)
        }

        // Update Hysteresis Button
        binding.btnUpdateHysteresis.setOnClickListener {
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
        database.child("heater").addValueEventListener(object : ValueEventListener {
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
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Temperature Observer (Logic removed to ESP32)
        database.child("temperature").child("current").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                val tempValue = when (val temp = snapshot.value) {
                    is Double -> temp
                    is Long -> temp.toDouble()
                    else -> temp?.toString()?.toDoubleOrNull() ?: 0.0
                }
                binding.tvTemperature.text = String.format("%.1f°C", tempValue)

                /* 
                   REMOVED HYSTERESIS LOGIC FROM ANDROID APP
                   The app should not write 'relay_status' based on temperature updates.
                   This was causing a feedback loop that crashed the ESP32 (LCD Flickering).
                   The ESP32 now handles this logic internally.
                */
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Heater Status Observer
        database.child("heater").addValueEventListener(object : ValueEventListener {
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
        })

        // Conveyor Status Observer
        database.child("conveyor").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                val newStatus = snapshot.child("status").getValue(Boolean::class.java) ?: false
                val stopAt = snapshot.child("stop_at").getValue(Long::class.java) ?: 0L
                val isOverride = snapshot.child("manual_override").getValue(Boolean::class.java) ?: false
                binding.switchManualOverride.setOnCheckedChangeListener(null)
                binding.switchManualOverride.isChecked = isOverride
                binding.switchManualOverride.setOnCheckedChangeListener { _, isChecked ->
                    database.child("conveyor").child("manual_override").setValue(isChecked)
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
        })

        database.child("pulverizer/status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (_binding == null) return
                isPulverizerOn = snapshot.getValue(Boolean::class.java) ?: false
                updateControlUI(binding.controlPulverizer, isPulverizerOn)
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        database.child("logs").limitToLast(20).addValueEventListener(object : ValueEventListener {
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
        })
    }

    private fun startHeaterTimer(stopAt: Long) {
        heaterTimer?.cancel()
        heaterTimer = object : CountDownTimer(stopAt - System.currentTimeMillis(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                if (_binding == null) return
                val totalSeconds = millisUntilFinished / 1000
                binding.tvOvenCountdown.text = String.format("%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
            }
            override fun onFinish() {
                if (_binding != null) {
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
                if (_binding == null) return
                val totalSeconds = millisUntilFinished / 1000
                binding.tvConveyorTimer.text = String.format("%02d:%02d:%02d", totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
            }
            override fun onFinish() {
                if (_binding != null) {
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
        
        // Industrial LED indicator: bright green when ON, dark gray when OFF
        val ledColor = ContextCompat.getColor(context, if (isOn) R.color.led_on else R.color.led_off)
        controlBinding.statusIndicator.backgroundTintList = ColorStateList.valueOf(ledColor)
        
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        conveyorTimer?.cancel()
        heaterTimer?.cancel()
    }
}

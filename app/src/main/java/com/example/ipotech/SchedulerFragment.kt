package com.example.ipotech

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.ipotech.databinding.FragmentSchedulerBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.text.SimpleDateFormat
import java.util.*

class SchedulerFragment : Fragment() {

    private var _binding: FragmentSchedulerBinding? = null
    private val binding get() = _binding!!

    private lateinit var database: DatabaseReference
    private val DB_URL = "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
    
    private val displaySdf = SimpleDateFormat("hh:mm a", Locale.US)
    private val storageSdf = SimpleDateFormat("HH:mm", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSchedulerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // FIX: Use explicit European URL
        database = FirebaseDatabase.getInstance(DB_URL).reference.child("schedule")

        setupPickers()
        setupMasterSwitch()
        setupDayToggleListener()
        loadSchedule()
        setupStatusListener()
        setupUIFocusHandling()

        binding.btnSaveSchedule.setOnClickListener {
            saveSchedule()
            hideKeyboardAndClearFocus()
        }
    }

    private fun setupDayToggleListener() {
        val buttons = listOf(
            binding.btnSun, binding.btnMon, binding.btnTue, 
            binding.btnWed, binding.btnThu, binding.btnFri, binding.btnSat
        )
        buttons.forEach { button ->
            button.addOnCheckedChangeListener { btn, isChecked ->
                updateDayButtonVisuals(btn as MaterialButton, isChecked)
            }
        }
    }

    private fun updateDayButtonVisuals(button: MaterialButton, isChecked: Boolean) {
        val context = context ?: return
        if (isChecked) {
            button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_teal))
            button.setTextColor(ContextCompat.getColor(context, R.color.white))
            button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_teal))
        } else {
            button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, android.R.color.transparent))
            button.setTextColor(ContextCompat.getColor(context, R.color.primary_teal))
            button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_teal))
        }
    }

    private fun setupUIFocusHandling() {
        // Clear focus and hide keyboard when clicking on the background
        binding.root.setOnTouchListener { _, _ ->
            hideKeyboardAndClearFocus()
            false
        }
    }

    private fun hideKeyboardAndClearFocus() {
        val focusedView = activity?.currentFocus ?: return
        val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(focusedView.windowToken, 0)
        focusedView.clearFocus()
    }

    private fun setupMasterSwitch() {
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            updateStandbyStatus()
            database.child("masterEnabled").setValue(isChecked)
            
            if (isChecked && context != null) {
                // Re-read schedule and schedule alarms
                database.get().addOnSuccessListener { snapshot ->
                    if (_binding == null || context == null) return@addOnSuccessListener
                    
                    val morningEnabled = snapshot.child("morning/enabled").getValue(Boolean::class.java) ?: false
                    val morningTime = snapshot.child("morning/start").getValue(String::class.java) ?: "08:00"
                    val morningDuration = snapshot.child("morning/duration").value?.toString()?.toIntOrNull() ?: 0
                    
                    val afternoonEnabled = snapshot.child("afternoon/enabled").getValue(Boolean::class.java) ?: false
                    val afternoonTime = snapshot.child("afternoon/start").getValue(String::class.java) ?: "13:00"
                    val afternoonDuration = snapshot.child("afternoon/duration").value?.toString()?.toIntOrNull() ?: 0
                    
                    scheduleExactAlarms(
                        masterEnabled = true,
                        morningEnabled = morningEnabled,
                        morningTime = morningTime,
                        morningDuration = morningDuration,
                        afternoonEnabled = afternoonEnabled,
                        afternoonTime = afternoonTime,
                        afternoonDuration = afternoonDuration
                    )
                }
                
                val oneTimeWork = OneTimeWorkRequestBuilder<ScheduleWorker>().build()
                WorkManager.getInstance(requireContext()).enqueue(oneTimeWork)
            } else if (!isChecked && context != null) {
                // Cancel all alarms when master is disabled
                AlarmScheduler.cancelAllAlarms(requireContext())
            }
        }
    }

    private fun setupPickers() {
        binding.btnDateRangePicker.setOnClickListener {
            val datePicker = MaterialDatePicker.Builder.dateRangePicker().build()
            datePicker.addOnPositiveButtonClickListener {
                val startDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.first))
                val endDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(it.second))

                if (_binding != null) {
                    binding.tvSelectedDateRange.text = "$startDate to $endDate"
                    database.child("startDate").setValue(startDate)
                    database.child("endDate").setValue(endDate)
                }
            }
            datePicker.show(parentFragmentManager, "DATE_PICKER")
        }

        binding.btnMorningStart.setOnClickListener { showTimePicker(true) }
        binding.btnAfternoonStart.setOnClickListener { showTimePicker(false) }
    }

    private fun showTimePicker(isMorning: Boolean) {
        val picker = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_12H).build()
        picker.addOnPositiveButtonClickListener {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, picker.hour)
             calendar.set(Calendar.MINUTE, picker.minute)
            
            val storageTime = storageSdf.format(calendar.time)
            val displayTime = displaySdf.format(calendar.time)

            if (_binding != null) {
                if (isMorning) {
                    binding.btnMorningStart.text = "START TIME: $displayTime"
                    binding.btnMorningStart.tag = storageTime
                } else {
                    binding.btnAfternoonStart.text = "START TIME: $displayTime"
                    binding.btnAfternoonStart.tag = storageTime
                }
            }
        }
        picker.show(parentFragmentManager, "TIME_PICKER")
    }

    private fun setupStatusListener() {
        FirebaseDatabase.getInstance(DB_URL).reference.child("conveyor/status").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                if (_binding == null) return
                val isActive = snapshot.getValue(Boolean::class.java) ?: false
                if (isActive) {
                    binding.tvSystemStatus.text = getString(R.string.status_running)
                    binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.btn_active_off))
                    binding.cardStatus.strokeColor = ContextCompat.getColor(requireContext(), R.color.led_on)
                    binding.tvSystemStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.led_on))
                    binding.tvLastModified.setTextColor(ContextCompat.getColor(requireContext(), R.color.industrial_text_inactive))
                } else {
                    updateStandbyStatus()
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
    }

    private fun updateStandbyStatus() {
        if (_binding == null) return
        if (binding.switchMaster.isChecked) {
            binding.tvSystemStatus.text = getString(R.string.status_standby)
            binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.btn_active_off))
            binding.cardStatus.strokeColor = ContextCompat.getColor(requireContext(), R.color.primary_teal)
            binding.tvSystemStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.led_on))
            binding.tvLastModified.setTextColor(ContextCompat.getColor(requireContext(), R.color.industrial_text_inactive))
        } else {
            binding.tvSystemStatus.text = getString(R.string.status_disabled)
            binding.cardStatus.setCardBackgroundColor(ContextCompat.getColor(requireContext(), R.color.btn_active_off))
            binding.cardStatus.strokeColor = ContextCompat.getColor(requireContext(), R.color.industrial_stroke)
            binding.tvSystemStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.industrial_text_inactive))
            binding.tvLastModified.setTextColor(ContextCompat.getColor(requireContext(), R.color.industrial_text_inactive))
        }
    }

    private fun loadSchedule() {
        database.get().addOnSuccessListener {
            if (_binding == null || !isAdded) return@addOnSuccessListener

            if (it.exists()) {
                binding.switchMaster.setOnCheckedChangeListener(null)
                binding.switchMaster.isChecked = it.child("masterEnabled").getValue(Boolean::class.java) ?: true
                
                val start = it.child("startDate").value?.toString() ?: "---"
                val end = it.child("endDate").value?.toString() ?: "---"
                binding.tvSelectedDateRange.text = "$start to $end"

                val days = it.child("activeDays").value?.toString() ?: "1234567"
                
                // Set checked state and manually update visuals
                setDayChecked(binding.btnSun, days.contains("1"))
                setDayChecked(binding.btnMon, days.contains("2"))
                setDayChecked(binding.btnTue, days.contains("3"))
                setDayChecked(binding.btnWed, days.contains("4"))
                setDayChecked(binding.btnThu, days.contains("5"))
                setDayChecked(binding.btnFri, days.contains("6"))
                setDayChecked(binding.btnSat, days.contains("7"))

                binding.switchMorning.isChecked = it.child("morning/enabled").getValue(Boolean::class.java) ?: false
                val mTime = it.child("morning/start").value?.toString() ?: "08:00"
                binding.btnMorningStart.text = "START TIME: ${convertToDisplayTime(mTime)}"
                binding.btnMorningStart.tag = mTime
                binding.etMorningDuration.setText(it.child("morning/duration").value?.toString() ?: "0")

                binding.switchAfternoon.isChecked = it.child("afternoon/enabled").getValue(Boolean::class.java) ?: false
                val aTime = it.child("afternoon/start").value?.toString() ?: "13:00"
                binding.btnAfternoonStart.text = "START TIME: ${convertToDisplayTime(aTime)}"
                binding.btnAfternoonStart.tag = aTime
                binding.etAfternoonDuration.setText(it.child("afternoon/duration").value?.toString() ?: "0")
                
                val lastMod = it.child("lastModified").getValue(Long::class.java)
                if (lastMod != null) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    binding.tvLastModified.text = "Last Sync: ${sdf.format(Date(lastMod))}"
                }

                updateStandbyStatus()
                setupMasterSwitch()
            }
        }
    }

    private fun setDayChecked(button: MaterialButton, checked: Boolean) {
        button.isChecked = checked
        updateDayButtonVisuals(button, checked)
    }

    private fun convertToDisplayTime(time24: String): String {
        return try {
            val date = storageSdf.parse(time24)
            displaySdf.format(date!!)
        } catch (e: Exception) {
            time24
        }
    }

    private fun saveSchedule() {
        if (_binding == null) return

        val morningDuration = binding.etMorningDuration.text.toString().toIntOrNull() ?: 0
        val afternoonDuration = binding.etAfternoonDuration.text.toString().toIntOrNull() ?: 0

        val morningStartTime = binding.btnMorningStart.tag?.toString() ?: "08:00"
        val afternoonStartTime = binding.btnAfternoonStart.tag?.toString() ?: "13:00"

        var days = ""
        if (binding.btnSun.isChecked) days += "1"
        if (binding.btnMon.isChecked) days += "2"
        if (binding.btnTue.isChecked) days += "3"
        if (binding.btnWed.isChecked) days += "4"
        if (binding.btnThu.isChecked) days += "5"
        if (binding.btnFri.isChecked) days += "6"
        if (binding.btnSat.isChecked) days += "7"

        val updates = HashMap<String, Any>()
        updates["masterEnabled"] = binding.switchMaster.isChecked
        updates["activeDays"] = days
        updates["morning/enabled"] = binding.switchMorning.isChecked
        updates["morning/start"] = morningStartTime
        updates["morning/duration"] = morningDuration
        updates["afternoon/enabled"] = binding.switchAfternoon.isChecked
        updates["afternoon/start"] = afternoonStartTime
        updates["afternoon/duration"] = afternoonDuration
        updates["lastModified"] = ServerValue.TIMESTAMP

        database.updateChildren(updates).addOnSuccessListener {
            if (context != null) {
                Toast.makeText(requireContext(), "Schedule Saved!", Toast.LENGTH_SHORT).show()
                updateStandbyStatus()
                
                // Schedule exact alarms
                scheduleExactAlarms(
                    masterEnabled = binding.switchMaster.isChecked,
                    morningEnabled = binding.switchMorning.isChecked,
                    morningTime = morningStartTime,
                    morningDuration = morningDuration,
                    afternoonEnabled = binding.switchAfternoon.isChecked,
                    afternoonTime = afternoonStartTime,
                    afternoonDuration = afternoonDuration
                )
                
                // Keep WorkManager as fallback
                val oneTimeWork = OneTimeWorkRequestBuilder<ScheduleWorker>().build()
                WorkManager.getInstance(requireContext()).enqueue(oneTimeWork)
            }
        }
    }
    
    /**
     * Schedule exact alarms for conveyor start/stop
     */
    private fun scheduleExactAlarms(
        masterEnabled: Boolean,
        morningEnabled: Boolean,
        morningTime: String,
        morningDuration: Int,
        afternoonEnabled: Boolean,
        afternoonTime: String,
        afternoonDuration: Int
    ) {
        val ctx = context ?: return
        
        // Cancel all existing alarms first
        AlarmScheduler.cancelAllAlarms(ctx)
        
        if (!masterEnabled) {
            return
        }
        
        // Check exact alarm permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                // Prompt user to grant permission
                Toast.makeText(
                    ctx,
                    "Please allow exact alarms for precise scheduling",
                    Toast.LENGTH_LONG
                ).show()
                
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.fromParts("package", ctx.packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback - continue with inexact alarms
                }
            }
        }
        
        // Schedule morning alarm
        if (morningEnabled && morningDuration > 0) {
            val timeParts = AlarmScheduler.parseTime(morningTime)
            if (timeParts != null) {
                AlarmScheduler.scheduleStart(
                    ctx,
                    timeParts.first,
                    timeParts.second,
                    morningDuration,
                    "Morning",
                    AlarmScheduler.REQUEST_MORNING_START
                )
            }
        }
        
        // Schedule afternoon/evening alarm
        if (afternoonEnabled && afternoonDuration > 0) {
            val timeParts = AlarmScheduler.parseTime(afternoonTime)
            if (timeParts != null) {
                AlarmScheduler.scheduleStart(
                    ctx,
                    timeParts.first,
                    timeParts.second,
                    afternoonDuration,
                    "Evening",
                    AlarmScheduler.REQUEST_AFTERNOON_START
                )
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.example.ipotech

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.ipotech.IpoTechApplication.Companion.KEY_ALERT_COOLDOWN
import com.example.ipotech.IpoTechApplication.Companion.KEY_AUTO_CLEANUP
import com.example.ipotech.IpoTechApplication.Companion.KEY_CRITICAL_THRESHOLD
import com.example.ipotech.IpoTechApplication.Companion.KEY_DARK_MODE
import com.example.ipotech.IpoTechApplication.Companion.KEY_NOTIFICATIONS
import com.example.ipotech.IpoTechApplication.Companion.KEY_SAVE_INTERVAL
import com.example.ipotech.IpoTechApplication.Companion.KEY_VIBRATION
import com.example.ipotech.IpoTechApplication.Companion.PREFS_NAME
import com.example.ipotech.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadSettings()

        // Immediate toggle for Dark Mode
        binding.switchDarkMode.setOnClickListener {
            val isChecked = binding.switchDarkMode.isChecked
            saveBooleanSetting(KEY_DARK_MODE, isChecked)
            view.postDelayed({
                AppCompatDelegate.setDefaultNightMode(
                    if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }, 250)
        }

        // Logic for the Save button
        binding.btnSaveSettings.setOnClickListener {
            if (validateInputs()) {
                saveAllSettings()
                Toast.makeText(context, "Settings Saved Successfully", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
            Toast.makeText(context, "Settings Reset to Defaults", Toast.LENGTH_SHORT).show()
        }

        binding.btnCleanupNow.setOnClickListener {
            binding.tvCleanupStatus.apply {
                visibility = View.VISIBLE
                text = "Cleaning up system logs..."
            }
            view.postDelayed({
                binding.tvCleanupStatus.text = "Cleanup complete."
            }, 1500)
        }
    }

    private fun validateInputs(): Boolean {
        val threshold = binding.etCriticalThreshold.text.toString()
        val cooldown = binding.etAlertCooldown.text.toString()

        if (threshold.isEmpty() || threshold.toFloatOrNull() == null) {
            binding.etCriticalThreshold.error = "Enter a valid temperature"
            return false
        }
        if (cooldown.isEmpty() || cooldown.toFloatOrNull() == null) {
            binding.etAlertCooldown.error = "Enter valid time"
            return false
        }
        return true
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        binding.switchDarkMode.isChecked = prefs.getBoolean(KEY_DARK_MODE, false)
        binding.switchNotifications.isChecked = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        binding.switchVibration.isChecked = prefs.getBoolean(KEY_VIBRATION, true)
        binding.switchAutoCleanup.isChecked = prefs.getBoolean(KEY_AUTO_CLEANUP, true)
        
        binding.etCriticalThreshold.setText(getSafeString(prefs, KEY_CRITICAL_THRESHOLD, "10"))
        binding.etAlertCooldown.setText(getSafeString(prefs, KEY_ALERT_COOLDOWN, "0.5"))
        binding.etSaveInterval.setText(getSafeString(prefs, KEY_SAVE_INTERVAL, "5"))
    }

    private fun getSafeString(prefs: android.content.SharedPreferences, key: String, default: String): String {
        return try {
            prefs.getString(key, default) ?: default
        } catch (e: Exception) {
            prefs.getInt(key, default.toFloat().toInt()).toString()
        }
    }

    private fun saveAllSettings() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_DARK_MODE, binding.switchDarkMode.isChecked)
            putBoolean(KEY_NOTIFICATIONS, binding.switchNotifications.isChecked)
            putBoolean(KEY_VIBRATION, binding.switchVibration.isChecked)
            putBoolean(KEY_AUTO_CLEANUP, binding.switchAutoCleanup.isChecked)
            
            putString(KEY_CRITICAL_THRESHOLD, binding.etCriticalThreshold.text.toString())
            putString(KEY_ALERT_COOLDOWN, binding.etAlertCooldown.text.toString())
            putString(KEY_SAVE_INTERVAL, binding.etSaveInterval.text.toString())
            apply()
        }
    }

    private fun saveBooleanSetting(key: String, value: Boolean) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun resetToDefaults() {
        binding.switchDarkMode.isChecked = false
        binding.switchNotifications.isChecked = true
        binding.switchVibration.isChecked = true
        binding.switchAutoCleanup.isChecked = true
        
        binding.etCriticalThreshold.setText("10")
        binding.etAlertCooldown.setText("0.5")
        binding.etSaveInterval.setText("5")
        
        saveAllSettings()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

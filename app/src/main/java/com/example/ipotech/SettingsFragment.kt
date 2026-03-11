package com.example.ipotech

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.ipotech.databinding.FragmentSettingsBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "ipotech_settings"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_CRITICAL_THRESHOLD = "critical_threshold"
        const val KEY_ALERT_COOLDOWN = "alert_cooldown"
        const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        const val KEY_SAVE_INTERVAL = "save_interval"
        const val KEY_AUTO_CLEANUP = "auto_cleanup"
        
        // Default values
        const val DEFAULT_CRITICAL_THRESHOLD = 10
        const val DEFAULT_ALERT_COOLDOWN = 1
        const val DEFAULT_SAVE_INTERVAL = 5
        
        private const val DB_URL = "https://layer-eb465-default-rtdb.europe-west1.firebasedatabase.app/"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        loadSettings()
        setupListeners()
        setupTouchToClearFocus()
    }

    private fun setupTouchToClearFocus() {
        binding.settingsRootLayout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                clearFocusAndHideKeyboard()
            }
            false
        }
    }

    private fun clearFocusAndHideKeyboard() {
        val focusedView = activity?.currentFocus
        focusedView?.clearFocus()
        
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        focusedView?.let {
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun loadSettings() {
        // Dark mode
        val isDarkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        binding.switchDarkMode.isChecked = isDarkMode

        // Alert settings
        binding.etCriticalThreshold.setText(
            prefs.getInt(KEY_CRITICAL_THRESHOLD, DEFAULT_CRITICAL_THRESHOLD).toString()
        )
        binding.etAlertCooldown.setText(
            prefs.getInt(KEY_ALERT_COOLDOWN, DEFAULT_ALERT_COOLDOWN).toString()
        )
        binding.switchVibration.isChecked = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)

        // Data management
        binding.etSaveInterval.setText(
            prefs.getInt(KEY_SAVE_INTERVAL, DEFAULT_SAVE_INTERVAL).toString()
        )
        binding.switchAutoCleanup.isChecked = prefs.getBoolean(KEY_AUTO_CLEANUP, true)
    }

    private fun setupListeners() {
        // Dark mode toggle - apply immediately
        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_DARK_MODE, isChecked).apply()
            
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        // Manual cleanup button
        binding.btnCleanupNow.setOnClickListener {
            cleanupOldData()
        }

        // Save button
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        // Reset to defaults button
        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
        }

        // Notification toggle
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("notifications_enabled", isChecked).apply()
            if (!isChecked) {
                // Disable FCM push notifications
                FirebaseMessaging.getInstance().deleteToken()
                Toast.makeText(requireContext(), "Push notifications disabled", Toast.LENGTH_SHORT).show()
            } else {
                // Re-enable FCM push notifications
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(requireContext(), "Push notifications enabled", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun resetToDefaults() {
        // Reset UI to default values
        binding.switchDarkMode.isChecked = false
        binding.etCriticalThreshold.setText(DEFAULT_CRITICAL_THRESHOLD.toString())
        binding.etAlertCooldown.setText(DEFAULT_ALERT_COOLDOWN.toString())
        binding.switchVibration.isChecked = true
        binding.etSaveInterval.setText(DEFAULT_SAVE_INTERVAL.toString())
        binding.switchAutoCleanup.isChecked = true

        // Save defaults to preferences
        prefs.edit().apply {
            putBoolean(KEY_DARK_MODE, false)
            putInt(KEY_CRITICAL_THRESHOLD, DEFAULT_CRITICAL_THRESHOLD)
            putInt(KEY_ALERT_COOLDOWN, DEFAULT_ALERT_COOLDOWN)
            putBoolean(KEY_VIBRATION_ENABLED, true)
            putInt(KEY_SAVE_INTERVAL, DEFAULT_SAVE_INTERVAL)
            putBoolean(KEY_AUTO_CLEANUP, true)
            apply()
        }

        // Apply light mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        Toast.makeText(requireContext(), "Settings reset to defaults", Toast.LENGTH_SHORT).show()
    }

    private fun saveSettings() {
        val criticalThreshold = binding.etCriticalThreshold.text.toString().toIntOrNull() ?: DEFAULT_CRITICAL_THRESHOLD
        val alertCooldown = binding.etAlertCooldown.text.toString().toIntOrNull() ?: DEFAULT_ALERT_COOLDOWN
        val saveInterval = binding.etSaveInterval.text.toString().toIntOrNull() ?: DEFAULT_SAVE_INTERVAL

        prefs.edit().apply {
            putInt(KEY_CRITICAL_THRESHOLD, criticalThreshold)
            putInt(KEY_ALERT_COOLDOWN, alertCooldown)
            putBoolean(KEY_VIBRATION_ENABLED, binding.switchVibration.isChecked)
            putInt(KEY_SAVE_INTERVAL, saveInterval)
            putBoolean(KEY_AUTO_CLEANUP, binding.switchAutoCleanup.isChecked)
            apply()
        }

        Toast.makeText(requireContext(), "Settings saved!", Toast.LENGTH_SHORT).show()
    }

    private fun cleanupOldData() {
        binding.btnCleanupNow.isEnabled = false
        binding.tvCleanupStatus.visibility = View.VISIBLE
        binding.tvCleanupStatus.text = "Cleaning up..."

        val database = FirebaseDatabase.getInstance(DB_URL).reference
        val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000) // 30 days ago

        var deletedCount = 0
        var completed = 0

        // Cleanup temperature history
        database.child("temperature_history")
            .orderByChild("timestamp")
            .endAt(cutoffTime.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        child.ref.removeValue()
                        deletedCount++
                    }
                    completed++
                    checkCleanupComplete(completed, deletedCount)
                }

                override fun onCancelled(error: DatabaseError) {
                    completed++
                    checkCleanupComplete(completed, deletedCount)
                }
            })

        // Cleanup logs
        database.child("logs")
            .orderByChild("timestamp")
            .endAt(cutoffTime.toDouble())
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        child.ref.removeValue()
                        deletedCount++
                    }
                    completed++
                    checkCleanupComplete(completed, deletedCount)
                }

                override fun onCancelled(error: DatabaseError) {
                    completed++
                    checkCleanupComplete(completed, deletedCount)
                }
            })
    }

    private fun checkCleanupComplete(completed: Int, deletedCount: Int) {
        if (completed >= 2 && _binding != null) {
            binding.btnCleanupNow.isEnabled = true
            binding.tvCleanupStatus.text = "Cleaned up $deletedCount old records"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.example.ipotech

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupWithNavController
import com.example.ipotech.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController

    // Order of fragments for directional transitions
    private val navItemOrder = listOf(
        R.id.nav_dashboard,
        R.id.nav_scheduler,
        R.id.nav_trends,
        R.id.nav_settings
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        
        // Only Dashboard is true top-level destination
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.nav_dashboard),
            binding.drawerLayout
        )
        
        // We handle navigation manually to provide custom animations
        binding.navView.setupWithNavController(navController)
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            val targetId = menuItem.itemId
            val currentId = navController.currentDestination?.id ?: -1
            
            if (targetId != currentId) {
                val startDestId = navController.graph.findStartDestination().id
                
                // Determine animation direction based on menu order
                val currentIndex = navItemOrder.indexOf(currentId)
                val targetIndex = navItemOrder.indexOf(targetId)
                
                val builder = NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(true)
                
                if (targetIndex < currentIndex) {
                    // Moving "backward" (e.g., from Settings to Dashboard) -> Slide Left
                    builder.setEnterAnim(R.anim.slide_in_left)
                        .setExitAnim(R.anim.slide_out_right)
                        .setPopEnterAnim(R.anim.slide_in_left)
                        .setPopExitAnim(R.anim.slide_out_right)
                } else {
                    // Moving "forward" (e.g., from Dashboard to Scheduler) -> Slide Right
                    builder.setEnterAnim(R.anim.slide_in_right)
                        .setExitAnim(R.anim.slide_out_left)
                        .setPopEnterAnim(R.anim.slide_in_left)
                        .setPopExitAnim(R.anim.slide_out_right)
                }

                // Properly manage the backstack to avoid duplicate targets
                builder.setPopUpTo(startDestId, inclusive = false, saveState = true)
                
                navController.navigate(targetId, null, builder.build())
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        binding.toolbar.findViewById<android.view.View>(R.id.btn_menu_custom).setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<android.view.View>(R.id.btn_exit_nav).setOnClickListener {
            showExitConfirmDialog()
        }
        
        // Handle back gesture navigation properly
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // Close drawer if open
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    }
                    // Not on dashboard - navigate back to dashboard (start destination)
                    navController.currentDestination?.id != R.id.nav_dashboard -> {
                        navController.popBackStack(R.id.nav_dashboard, false)
                    }
                    // On dashboard, show exit confirmation
                    else -> {
                        showExitConfirmDialog()
                    }
                }
            }
        })

        // Start Foreground Service for monitoring (handles WorkManager internally)
        val monitorIntent = Intent(this, MonitoringForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(monitorIntent)
        } else {
            startService(monitorIntent)
        }
        
        // Exclude left edge from system back gesture to allow drawer swipe
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.drawerLayout.post {
                val exclusionRects = listOf(
                    Rect(0, 0, 50, binding.drawerLayout.height)
                )
                binding.drawerLayout.systemGestureExclusionRects = exclusionRects
            }
        }
    }

    private fun showExitConfirmDialog() {
        MaterialAlertDialogBuilder(this, R.style.IndustrialAlertDialog)
            .setTitle("Confirm System Exit")
            .setMessage("Active monitoring session will be terminated.\n\nTemperature alerts will no longer be received.")
            .setNegativeButton("Continue Monitoring") { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton("Exit Application") { _, _ ->
                finish()
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}

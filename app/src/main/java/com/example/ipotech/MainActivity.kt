package com.example.ipotech

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupWithNavController
import androidx.work.*
import com.example.ipotech.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var navController: NavController

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
        
        binding.navView.setupWithNavController(navController)

        binding.toolbar.findViewById<android.view.View>(R.id.btn_menu_custom).setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<android.view.View>(R.id.btn_exit_nav).setOnClickListener {
            finish()
        }

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            val handled = androidx.navigation.ui.NavigationUI.onNavDestinationSelected(menuItem, navController)
            if (handled) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
            handled
        }
        
        // Handle back gesture navigation properly
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    // Close drawer if open
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    }
                    // Not on dashboard - open drawer menu
                    navController.currentDestination?.id != R.id.nav_dashboard -> {
                        binding.drawerLayout.openDrawer(GravityCompat.START)
                    }
                    // On dashboard, exit app
                    else -> {
                        finish()
                    }
                }
            }
        })

        // Test Mode: Start background worker immediately without Auth
        startScheduleWorker()
        
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

    private fun startScheduleWorker() {
        val workRequest = PeriodicWorkRequestBuilder<ScheduleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ConveyorScheduleWork",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}

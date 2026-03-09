package com.example.ipotech

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class IpoTechApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Enable offline persistence so the app can read schedules and queue logs while offline
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        
        // Keep all critical paths synced even when the app is in background or offline
        val database = FirebaseDatabase.getInstance().reference
        database.child("schedule").keepSynced(true)
        database.child("conveyor").keepSynced(true)
        database.child("heater").keepSynced(true)
        database.child("pulverizer").keepSynced(true)
        database.child("temperature").keepSynced(true)
    }
}

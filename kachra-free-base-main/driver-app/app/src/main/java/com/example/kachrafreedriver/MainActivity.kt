package com.example.kachrafreedriver

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.kachrafreedriver.theme.KachraFreeDriverTheme
import com.example.kachrafreedriver.ui.main.MainScreen

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    // These back the screen directly, so changing them updates the UI
    // right away - no manual "re-render" calls needed.
    private var truckId by mutableStateOf("")
    private var tracking by mutableStateOf(false)
    private var latitude by mutableStateOf<Double?>(null)
    private var longitude by mutableStateOf<Double?>(null)
    private var secondsSinceUpdate by mutableStateOf<Long?>(null)

    // Re-reads LocationService's latest fix once a second so the screen
    // shows fresh coordinates and an up-to-date "X seconds ago" while open.
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshFromService()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val locationGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (!locationGranted) {
                Toast.makeText(
                    this,
                    "Location permission is required",
                    Toast.LENGTH_LONG
                ).show()

                return@registerForActivityResult
            }

            startTracking()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(
            AppPrefs.FILE_NAME,
            MODE_PRIVATE
        )

        truckId = prefs.getString(AppPrefs.KEY_TRUCK_ID, "").orEmpty()

        setContent {
            KachraFreeDriverTheme {
                MainScreen(
                    truckId = truckId,
                    onTruckIdChange = { truckId = it },
                    tracking = tracking,
                    latitude = latitude,
                    longitude = longitude,
                    secondsSinceUpdate = secondsSinceUpdate,
                    onStartTracking = {
                        requestPermissionsAndStart()
                    },
                    onStopTracking = {
                        stopTracking()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Start polling immediately (not just on the next tick) and then
        // once a second while the screen is visible.
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()

        // No point refreshing a screen the user can't see.
        uiHandler.removeCallbacks(refreshRunnable)
    }

    private fun refreshFromService() {

        // The service can stop itself in the background (GPS turned off,
        // permission revoked, the OS killing it under memory pressure).
        // Re-check its real state instead of trusting whatever we last set
        // it to.
        tracking = LocationService.isRunning

        val snapshot = LocationService.lastLocation

        if (snapshot == null) {
            latitude = null
            longitude = null
            secondsSinceUpdate = null
        } else {
            latitude = snapshot.latitude
            longitude = snapshot.longitude
            secondsSinceUpdate =
                (SystemClock.elapsedRealtime() - snapshot.receivedAtElapsedMillis) / 1000L
        }
    }

    private fun requestPermissionsAndStart() {

        if (truckId.isBlank()) {
            Toast.makeText(
                this,
                "Enter this truck's Truck ID first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        permissionLauncher.launch(
            permissions.toTypedArray()
        )
    }

    private fun startTracking() {

        prefs.edit()
            .putString(AppPrefs.KEY_TRUCK_ID, truckId)
            .apply()

        val intent = Intent(
            this,
            LocationService::class.java
        ).apply {
            action = LocationService.ACTION_START
            putExtra(LocationService.EXTRA_TRUCK_ID, truckId)
        }

        try {
            ContextCompat.startForegroundService(
                this,
                intent
            )

            tracking = true

            Toast.makeText(
                this,
                "Tracking started",
                Toast.LENGTH_SHORT
            ).show()

        } catch (e: Exception) {

            tracking = false

            Toast.makeText(
                this,
                "Could not start tracking: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopTracking() {

        val intent = Intent(
            this,
            LocationService::class.java
        ).apply {
            action = LocationService.ACTION_STOP
        }

        startService(intent)

        tracking = false

        Toast.makeText(
            this,
            "Tracking stopped",
            Toast.LENGTH_SHORT
        ).show()
    }
}

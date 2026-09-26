package com.example.kachrafreedriver

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
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

    companion object {
        // "Add stop" waits this long before sending, with an Undo button,
        // in case the button was pressed by accident.
        private const val ADD_STOP_DELAY_MS = 20_000L
    }

    private lateinit var prefs: SharedPreferences

    // These back the screen directly: changing one redraws the UI.
    private var truckId by mutableStateOf("")
    private var tracking by mutableStateOf(false)
    private var locationText by mutableStateOf<String?>(null)
    private var serverText by mutableStateOf<String?>(null)
    private var stopNames by mutableStateOf<List<String>>(emptyList())
    private var pendingStopSecondsLeft by mutableStateOf<Int?>(null)

    // The location captured when "Add stop" was pressed, waiting to be sent.
    private var pendingStop: LocationService.LocationSnapshot? = null
    private var pendingStopDeadline = 0L
    private val commitStopRunnable = Runnable { commitPendingStop() }

    // Re-reads LocationService's live state once a second while visible.
    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshFromService()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val locationGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (locationGranted) {
                startTracking()
            } else {
                Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
        truckId = prefs.getString(AppPrefs.KEY_TRUCK_ID, "").orEmpty()

        setContent {
            KachraFreeDriverTheme {
                MainScreen(
                    truckId = truckId,
                    onTruckIdChange = { truckId = it },
                    tracking = tracking,
                    locationText = locationText,
                    serverText = serverText,
                    stopNames = stopNames,
                    pendingStopSecondsLeft = pendingStopSecondsLeft,
                    onStartTracking = { requestPermissionsAndStart() },
                    onStopTracking = { stopTracking() },
                    onAddStop = { startAddingStop() },
                    onUndoStop = { undoAddingStop() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshRunnable)
    }

    private fun secondsAgo(elapsedMillis: Long): Long =
        (SystemClock.elapsedRealtime() - elapsedMillis) / 1000L

    private fun refreshFromService() {
        // The service can stop on its own (permission revoked, killed by the
        // OS), so always show its real state, never a saved flag.
        tracking = LocationService.isRunning

        // Location line.
        val fix = LocationService.lastLocation
        if (!tracking) {
            locationText = null
        } else if (fix == null) {
            locationText = "Location: waiting for GPS..."
        } else {
            locationText = "Location: %.6f, %.6f (%ds ago)".format(
                fix.latitude, fix.longitude, secondsAgo(fix.receivedAtElapsedMillis)
            )
        }

        // Server line.
        val sentAt = LocationService.lastUploadAtElapsedMillis
        val uploadOk = LocationService.lastUploadOk
        if (!tracking) {
            serverText = null
        } else if (uploadOk == null) {
            serverText = "Server: connecting..."
        } else if (uploadOk) {
            serverText = "Server: connected, last sent ${secondsAgo(sentAt ?: 0L)}s ago"
        } else if (sentAt != null) {
            serverText = "Server: unreachable, last sent ${secondsAgo(sentAt)}s ago"
        } else {
            serverText = "Server: unreachable"
        }

        // Stop list.
        val names = mutableListOf<String>()
        for (stop in LocationService.routeStops ?: emptyList()) {
            names.add(stop.name)
        }
        stopNames = names

        // "Adding stop in Ns" countdown.
        if (pendingStop == null) {
            pendingStopSecondsLeft = null
        } else {
            val secondsLeft = ((pendingStopDeadline - SystemClock.elapsedRealtime()) / 1000L).toInt()
            pendingStopSecondsLeft = maxOf(secondsLeft, 0)
        }
    }

    private fun startAddingStop() {
        val fix = LocationService.lastLocation
        if (fix == null) {
            Toast.makeText(this, "Waiting for GPS: can't add a stop yet", Toast.LENGTH_SHORT).show()
            return
        }
        // Remember where the truck was when the button was pressed, not
        // where it is 20 seconds later.
        pendingStop = fix
        pendingStopDeadline = SystemClock.elapsedRealtime() + ADD_STOP_DELAY_MS
        uiHandler.postDelayed(commitStopRunnable, ADD_STOP_DELAY_MS)
        refreshFromService()
    }

    private fun undoAddingStop() {
        uiHandler.removeCallbacks(commitStopRunnable)
        pendingStop = null
        refreshFromService()
    }

    private fun commitPendingStop() {
        val stop = pendingStop
        if (stop == null) return
        pendingStop = null
        refreshFromService()
        val id = truckId

        Thread {
            val stops = ServerApi.addStop(id, stop.latitude, stop.longitude)
            runOnUiThread {
                if (stops != null) {
                    LocationService.routeStops = stops
                    refreshFromService()
                    Toast.makeText(this, "Stop ${stops.size} added", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Couldn't reach the server, stop not added", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun requestPermissionsAndStart() {
        if (truckId.isBlank()) {
            Toast.makeText(this, "Enter this truck's Truck ID first", Toast.LENGTH_SHORT).show()
            return
        }

        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startTracking() {
        truckId = truckId.trim()
        prefs.edit().putString(AppPrefs.KEY_TRUCK_ID, truckId).apply()

        val intent = Intent(this, LocationService::class.java)
        intent.action = LocationService.ACTION_START
        intent.putExtra(LocationService.EXTRA_TRUCK_ID, truckId)

        try {
            ContextCompat.startForegroundService(this, intent)
            tracking = true
        } catch (e: Exception) {
            tracking = false
            Toast.makeText(this, "Could not start tracking: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopTracking() {
        undoAddingStop()
        val intent = Intent(this, LocationService::class.java)
        intent.action = LocationService.ACTION_STOP
        startService(intent)
        tracking = false
    }
}

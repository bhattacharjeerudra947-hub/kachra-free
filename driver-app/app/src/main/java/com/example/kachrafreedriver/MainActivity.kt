package com.example.kachrafreedriver

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
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

        // How often to check the server is reachable while not tracking, for
        // the pill at the top. (While tracking, the location uploads show it.)
        private const val PING_INTERVAL_MS = 2_000L

    }

    private lateinit var prefs: SharedPreferences

    // These back the screen directly: changing one redraws the UI.
    private var truckId by mutableStateOf("")
    private var tracking by mutableStateOf(false)
    private var locationText by mutableStateOf<String?>(null)
    private var serverText by mutableStateOf<String?>(null)
    // e.g. "3. Janpath Market - next"
    private var stopLines by mutableStateOf<List<String>>(emptyList())
    // e.g. "Next stop: 3. Janpath Market, 240 m away"
    private var nextStopText by mutableStateOf<String?>(null)
    // The stop the "Garbage collected" button is for (within 30 m), if any.
    private var collectStop by mutableStateOf<ServerApi.Stop?>(null)
    private var collectStopNumber by mutableStateOf(0)
    private var pendingStopSecondsLeft by mutableStateOf<Int?>(null)
    // null until the first ping finishes.
    private var serverReachable by mutableStateOf<Boolean?>(null)
    // true while asking the server whether the Truck ID exists.
    private var checkingTruck by mutableStateOf(false)

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

    // Checks the server is reachable every PING_INTERVAL_MS while visible
    // and not tracking. While tracking, each location upload already tells
    // us, so there's no extra request.
    private val pingRunnable = object : Runnable {
        override fun run() {
            if (!LocationService.isRunning) {
                Thread {
                    val reachable = ServerApi.ping()
                    runOnUiThread { serverReachable = reachable }
                }.start()
            }
            uiHandler.postDelayed(this, PING_INTERVAL_MS)
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
                    serverReachable = serverReachable,
                    checkingTruck = checkingTruck,
                    truckId = truckId,
                    onTruckIdChange = { truckId = it },
                    tracking = tracking,
                    locationText = locationText,
                    serverText = serverText,
                    stopLines = stopLines,
                    nextStopText = nextStopText,
                    collectStopLabel = collectStopLabel(),
                    onCollected = { markCollected() },
                    pendingStopSecondsLeft = pendingStopSecondsLeft,
                    onStartTracking = { checkTruckAndStart() },
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
        uiHandler.post(pingRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshRunnable)
        uiHandler.removeCallbacks(pingRunnable)
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

        // Server pill: while tracking, did the last upload get through?
        if (tracking && uploadOk != null) serverReachable = uploadOk

        refreshStops(fix)

        // "Adding stop in Ns" countdown.
        if (pendingStop == null) {
            pendingStopSecondsLeft = null
        } else {
            val secondsLeft = ((pendingStopDeadline - SystemClock.elapsedRealtime()) / 1000L).toInt()
            pendingStopSecondsLeft = maxOf(secondsLeft, 0)
        }
    }

    /** The stop list, the next stop, and whether to show "Garbage collected". */
    private fun refreshStops(fix: LocationService.LocationSnapshot?) {
        val stops = LocationService.routeStops ?: emptyList()

        val lines = mutableListOf<String>()
        for (i in stops.indices) {
            val stop = stops[i]
            var line = "${i + 1}. ${stop.name}"
            if (stop.status == "collected") line += "  ✓ collected"
            if (stop.status == "skipped") line += "  ⚠ skipped"
            if (stop.status == "next") line += "  ← next"
            lines.add(line)
        }
        stopLines = lines

        // Next stop and how far it is.
        nextStopText = null
        for (i in stops.indices) {
            val stop = stops[i]
            if (stop.status != "next") continue
            nextStopText = "Next stop: ${i + 1}. ${stop.name}"
            if (fix != null) {
                val metres = distanceMeters(fix.latitude, fix.longitude, stop.latitude, stop.longitude)
                nextStopText += ", ${metres.toInt()} m away"
            }
        }
        if (nextStopText == null && stops.isNotEmpty() && tracking) {
            nextStopText = "All stops done for today"
        }

        // "Garbage collected" is offered for the closest stop within the
        // admin panel's radius (30 m by default) that isn't collected yet
        // (usually the next one; another one if the driver skipped ahead).
        collectStop = null
        if (fix == null) return
        var closest = ServerApi.collectRadiusMeters
        for (i in stops.indices) {
            val stop = stops[i]
            if (stop.status == "collected") continue
            val metres = distanceMeters(fix.latitude, fix.longitude, stop.latitude, stop.longitude)
            if (metres <= closest) {
                closest = metres
                collectStop = stop
                collectStopNumber = i + 1
            }
        }
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, result)
        return result[0]
    }

    private fun collectStopLabel(): String? {
        val stop = collectStop
        if (stop == null) return null
        return "Garbage collected at $collectStopNumber. ${stop.name}"
    }

    private fun markCollected() {
        val stop = collectStop
        if (stop == null) return
        val id = truckId

        Thread {
            val stops = ServerApi.markCollected(id, stop.id)
            runOnUiThread {
                if (stops != null) {
                    LocationService.routeStops = stops
                    refreshFromService()
                    Toast.makeText(this, "${stop.name}: collected", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Couldn't reach the server. Press it again.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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

    /** "Start sharing location": first make sure the server knows this Truck ID. */
    private fun checkTruckAndStart() {
        truckId = truckId.trim()
        if (truckId.isBlank()) {
            Toast.makeText(this, "Enter this truck's Truck ID first", Toast.LENGTH_SHORT).show()
            return
        }

        checkingTruck = true
        val id = truckId
        Thread {
            val result = ServerApi.checkTruck(id)
            runOnUiThread {
                checkingTruck = false
                when (result) {
                    ServerApi.TruckCheck.FOUND -> requestPermissionsAndStart()
                    ServerApi.TruckCheck.UNKNOWN_TRUCK -> Toast.makeText(
                        this, "No truck with ID $id on the server. Ask the admin to add it.", Toast.LENGTH_LONG
                    ).show()
                    ServerApi.TruckCheck.UNREACHABLE -> Toast.makeText(
                        this, "Can't reach the server to check the Truck ID. Try again.", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun requestPermissionsAndStart() {
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

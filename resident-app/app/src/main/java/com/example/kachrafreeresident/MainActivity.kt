package com.example.kachrafreeresident

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.kachrafreeresident.theme.KachraFreeResidentTheme
import com.example.kachrafreeresident.ui.main.RegisterScreen
import com.example.kachrafreeresident.ui.main.StatusScreen
import com.example.kachrafreeresident.ui.main.TruckMapScreen
import org.osmdroid.util.GeoPoint

class MainActivity : ComponentActivity() {

    // All the actual server polling happens in AlertService, so alerts keep
    // arriving after this screen (or the whole app) is closed. This just
    // mirrors its result once a second while the screen is visible - a
    // local memory read, not a network call.
    private companion object {
        private const val UI_REFRESH_MS = 1_000L
    }

    private lateinit var prefs: SharedPreferences

    // These back the screen directly: changing one redraws the UI.
    private var registered by mutableStateOf(false)
    private var editing by mutableStateOf(false)
    private var showTruckMap by mutableStateOf(false)

    private var phoneNumber by mutableStateOf("")
    private var truckId by mutableStateOf("")
    private var latitudeText by mutableStateOf("")
    private var longitudeText by mutableStateOf("")
    private var houseAddress by mutableStateOf<String?>(null)
    private var alertMinutes by mutableStateOf(10)

    private var truckStatus by mutableStateOf<ServerApi.TruckStatus?>(null)

    // The map, permissions and search live in LocationPickerActivity. This
    // only launches it and reads back what the resident picked.
    private val locationPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) return@registerForActivityResult

            val latitude = data.getDoubleExtra(LocationPickerActivity.EXTRA_RESULT_LATITUDE, Double.NaN)
            val longitude = data.getDoubleExtra(LocationPickerActivity.EXTRA_RESULT_LONGITUDE, Double.NaN)
            if (!latitude.isNaN() && !longitude.isNaN()) {
                latitudeText = latitude.toString()
                longitudeText = longitude.toString()
                houseAddress = data.getStringExtra(LocationPickerActivity.EXTRA_RESULT_ADDRESS)
            }
        }

    // Asked for at registration, so AlertService's notifications can be shown
    // (Android 13+).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            truckStatus = AlertService.lastStatus
            uiHandler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(AppPrefs.FILE_NAME, MODE_PRIVATE)
        registered = prefs.getBoolean(AppPrefs.KEY_REGISTERED, false)
        phoneNumber = prefs.getString(AppPrefs.KEY_PHONE_NUMBER, "").orEmpty()
        truckId = prefs.getString(AppPrefs.KEY_TRUCK_ID, "").orEmpty()
        latitudeText = prefs.getString(AppPrefs.KEY_LATITUDE, "").orEmpty()
        longitudeText = prefs.getString(AppPrefs.KEY_LONGITUDE, "").orEmpty()
        houseAddress = prefs.getString(AppPrefs.KEY_ADDRESS, null)
        alertMinutes = prefs.getInt(AppPrefs.KEY_ALERT_MINUTES, 10)

        // Covers the case where the app was reopened after AlertService got
        // killed (e.g. by the OS) without the phone rebooting - BootReceiver
        // only handles the reboot case.
        if (registered) startAlertService()

        setContent {
            KachraFreeResidentTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val houseLatLng = geoPointOrNull(latitudeText.toDoubleOrNull(), longitudeText.toDoubleOrNull())
                    val status = truckStatus

                    when {
                        showTruckMap && houseLatLng != null -> TruckMapScreen(
                            houseLatLng = houseLatLng,
                            stopLatLng = geoPointOrNull(status?.stopLatitude, status?.stopLongitude),
                            stopName = status?.stopName,
                            truckLatLng = geoPointOrNull(status?.truckLatitude, status?.truckLongitude),
                            pathPoints = status?.path ?: emptyList(),
                            message = status?.message ?: "Waiting for the server...",
                            onBack = { showTruckMap = false }
                        )

                        registered && !editing -> StatusScreen(
                            phoneNumber = phoneNumber,
                            truckId = truckId,
                            houseLocationLabel = houseLocationLabel(),
                            alertMinutes = alertMinutes,
                            truckStatus = status,
                            onEdit = { editing = true },
                            onViewMap = { showTruckMap = true }
                        )

                        else -> RegisterScreen(
                            phoneNumber = phoneNumber,
                            onPhoneNumberChange = { phoneNumber = it },
                            truckId = truckId,
                            onTruckIdChange = { truckId = it },
                            houseLocationLabel = houseLocationLabel(),
                            onPickLocation = { launchLocationPicker() },
                            alertMinutes = alertMinutes,
                            onAlertMinutesChange = { alertMinutes = it },
                            isEditing = registered && editing,
                            onCancel = { editing = false },
                            onSubmit = { submitRegistration() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiRefreshRunnable)
    }

    /** A map point, or null if either number is missing. */
    private fun geoPointOrNull(latitude: Double?, longitude: Double?): GeoPoint? {
        if (latitude == null || longitude == null) return null
        return GeoPoint(latitude, longitude)
    }

    private fun houseLocationLabel(): String {
        val address = houseAddress
        if (address != null) return address
        val lat = latitudeText.toDoubleOrNull()
        val lng = longitudeText.toDoubleOrNull()
        if (lat == null || lng == null) return "Not set yet"
        return "%.6f, %.6f".format(lat, lng)
    }

    private fun launchLocationPicker() {
        val intent = Intent(this, LocationPickerActivity::class.java)
        // Open the map on the house picked last time, if there is one.
        val lat = latitudeText.toDoubleOrNull()
        val lng = longitudeText.toDoubleOrNull()
        if (lat != null && lng != null) {
            intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LATITUDE, lat)
            intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LONGITUDE, lng)
        }
        locationPickerLauncher.launch(intent)
    }

    private fun startAlertService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AlertService::class.java).apply { action = AlertService.ACTION_START }
            )
        } catch (_: Exception) {
            // Nothing more we can do here; the resident can still see status
            // whenever they reopen the app.
        }
    }

    /** Blocking; call off the main thread. */
    private fun sendRegistration(): ServerApi.RegisterResult {
        val latitude = latitudeText.toDoubleOrNull()
        val longitude = longitudeText.toDoubleOrNull()
        if (latitude == null || longitude == null) return ServerApi.RegisterResult.UNREACHABLE
        return ServerApi.register(phoneNumber, truckId, latitude, longitude, alertMinutes, houseAddress)
    }

    private fun submitRegistration() {
        phoneNumber = phoneNumber.trim()
        truckId = truckId.trim()

        if (phoneNumber.isBlank()) {
            Toast.makeText(this, "Enter your phone number", Toast.LENGTH_SHORT).show()
            return
        }
        if (truckId.isBlank()) {
            Toast.makeText(this, "Enter the Truck ID for your area", Toast.LENGTH_SHORT).show()
            return
        }
        if (latitudeText.toDoubleOrNull() == null || longitudeText.toDoubleOrNull() == null) {
            Toast.makeText(this, "Choose your house on the map first", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            val result = sendRegistration()
            runOnUiThread {
                if (result == ServerApi.RegisterResult.UNKNOWN_TRUCK) {
                    // Stay on the form so the ID can be fixed.
                    Toast.makeText(this, "No truck with ID $truckId. Check it and try again.", Toast.LENGTH_LONG).show()
                } else {
                    // Server unreachable is fine: it's saved here and re-sent
                    // automatically once the server is back.
                    saveRegistrationLocally()
                    val message = if (result == ServerApi.RegisterResult.OK) {
                        "Registered"
                    } else {
                        "Saved on this phone. Will send to the server when it's reachable."
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveRegistrationLocally() {
        prefs.edit()
            .putBoolean(AppPrefs.KEY_REGISTERED, true)
            .putString(AppPrefs.KEY_PHONE_NUMBER, phoneNumber)
            .putString(AppPrefs.KEY_TRUCK_ID, truckId)
            .putString(AppPrefs.KEY_LATITUDE, latitudeText)
            .putString(AppPrefs.KEY_LONGITUDE, longitudeText)
            .putString(AppPrefs.KEY_ADDRESS, houseAddress)
            .putInt(AppPrefs.KEY_ALERT_MINUTES, alertMinutes)
            .apply()

        registered = true
        editing = false
        truckStatus = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startAlertService()
    }
}

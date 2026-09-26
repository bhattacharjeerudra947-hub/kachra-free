package com.example.kachrafreeresident

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.kachrafreeresident.theme.KachraFreeResidentTheme
import com.example.kachrafreeresident.ui.main.RegisterScreen
import com.example.kachrafreeresident.ui.main.StatusScreen

class MainActivity : ComponentActivity() {

    companion object {
        // How often the status screen asks the server for a fresh ETA.
        private const val STATUS_POLL_INTERVAL_MS = 15_000L
    }

    private lateinit var prefs: SharedPreferences

    // These back the screen directly, so changing them updates the UI
    // right away - no manual "re-render" calls needed.
    private var registered by mutableStateOf(false)
    private var editing by mutableStateOf(false)

    private var phoneNumber by mutableStateOf("")
    private var latitudeText by mutableStateOf("")
    private var longitudeText by mutableStateOf("")
    private var alertMinutes by mutableStateOf(10)

    private var truckStatus by mutableStateOf<ResidentApi.TruckStatus?>(null)

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                captureCurrentLocation()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is required to auto-fill your house location",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    // Polls the server for this resident's truck/ETA while the status
    // screen is visible. Registration itself does not need this - only
    // showing live status does.
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshTruckStatus()
            statusHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    // Used only once, to fill in the house location fields during
    // registration - removes itself as soon as one fix arrives.
    private val oneShotLocationListener = object : LocationListener {

        override fun onLocationChanged(location: Location) {
            applyLocation(location)
            locationManager().removeUpdates(this)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?
        ) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(
            AppPrefs.FILE_NAME,
            MODE_PRIVATE
        )

        registered = prefs.getBoolean(AppPrefs.KEY_REGISTERED, false)
        phoneNumber = prefs.getString(AppPrefs.KEY_PHONE_NUMBER, "").orEmpty()
        latitudeText = prefs.getString(AppPrefs.KEY_LATITUDE, "").orEmpty()
        longitudeText = prefs.getString(AppPrefs.KEY_LONGITUDE, "").orEmpty()
        alertMinutes = prefs.getInt(AppPrefs.KEY_ALERT_MINUTES, 10)

        setContent {
            KachraFreeResidentTheme {
                if (registered && !editing) {
                    StatusScreen(
                        phoneNumber = phoneNumber,
                        latitudeText = latitudeText,
                        longitudeText = longitudeText,
                        alertMinutes = alertMinutes,
                        truckStatus = truckStatus,
                        onEdit = {
                            editing = true
                            refreshStatusPolling()
                        }
                    )
                } else {
                    RegisterScreen(
                        phoneNumber = phoneNumber,
                        onPhoneNumberChange = { phoneNumber = it },
                        latitudeText = latitudeText,
                        onLatitudeChange = { latitudeText = it },
                        longitudeText = longitudeText,
                        onLongitudeChange = { longitudeText = it },
                        onUseCurrentLocation = { requestLocationAndCapture() },
                        alertMinutes = alertMinutes,
                        onAlertMinutesChange = { alertMinutes = it },
                        isEditing = registered && editing,
                        onCancel = {
                            editing = false
                            refreshStatusPolling()
                        },
                        onSubmit = { submitRegistration() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }

    private fun refreshStatusPolling() {
        statusHandler.removeCallbacks(statusRunnable)

        if (registered && !editing) {
            statusHandler.post(statusRunnable)
        }
    }

    private fun refreshTruckStatus() {
        val phone = phoneNumber

        Thread {
            val result = ResidentApi.fetchStatus(phone)
            runOnUiThread {
                truckStatus = result
            }
        }.start()
    }

    private fun submitRegistration() {

        val phone = phoneNumber.trim()
        val latitude = latitudeText.toDoubleOrNull()
        val longitude = longitudeText.toDoubleOrNull()

        if (phone.isBlank()) {
            Toast.makeText(this, "Enter your phone number", Toast.LENGTH_SHORT).show()
            return
        }

        if (latitude == null || longitude == null) {
            Toast.makeText(this, "Set your house location first", Toast.LENGTH_SHORT).show()
            return
        }

        // Registration is local-first: this succeeds even if the server
        // is unreachable, same as the driver app's "keep the last known
        // state on this phone" approach.
        prefs.edit()
            .putBoolean(AppPrefs.KEY_REGISTERED, true)
            .putString(AppPrefs.KEY_PHONE_NUMBER, phone)
            .putString(AppPrefs.KEY_LATITUDE, latitudeText)
            .putString(AppPrefs.KEY_LONGITUDE, longitudeText)
            .putInt(AppPrefs.KEY_ALERT_MINUTES, alertMinutes)
            .apply()

        phoneNumber = phone
        registered = true
        editing = false
        truckStatus = null

        refreshStatusPolling()

        Thread {
            val success = ResidentApi.register(phone, latitude, longitude, alertMinutes)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (success) {
                        "Registered"
                    } else {
                        "Saved on this phone - server unreachable, will sync when it's back"
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun requestLocationAndCapture() {
        if (hasLocationPermission()) {
            captureCurrentLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun hasLocationPermission(): Boolean {

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun captureCurrentLocation() {

        val manager = locationManager()

        val provider = when {
            isProviderEnabled(manager, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            isProviderEnabled(manager, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            Toast.makeText(
                this,
                "Turn on Location, or enter your coordinates manually",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            val lastKnown = manager.getLastKnownLocation(provider)

            if (lastKnown != null) {
                applyLocation(lastKnown)
                return
            }

            Toast.makeText(this, "Getting your location...", Toast.LENGTH_SHORT).show()

            manager.requestLocationUpdates(
                provider,
                0L,
                0f,
                oneShotLocationListener,
                Looper.getMainLooper()
            )

        } catch (_: SecurityException) {
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyLocation(location: Location) {
        latitudeText = location.latitude.toString()
        longitudeText = location.longitude.toString()
    }

    private fun locationManager(): LocationManager {
        return getSystemService(LocationManager::class.java)
    }

    private fun isProviderEnabled(manager: LocationManager, provider: String): Boolean {
        return try {
            manager.isProviderEnabled(provider)
        } catch (_: Exception) {
            false
        }
    }
}

package com.example.kachrafreeresident

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
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
import com.example.kachrafreeresident.theme.KachraFreeResidentTheme
import com.example.kachrafreeresident.ui.main.RegisterScreen
import com.example.kachrafreeresident.ui.main.StatusScreen
import com.example.kachrafreeresident.ui.main.TruckMapScreen
import com.google.android.gms.maps.model.LatLng

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
    private var showTruckMap by mutableStateOf(false)

    private var phoneNumber by mutableStateOf("")
    private var latitudeText by mutableStateOf("")
    private var longitudeText by mutableStateOf("")
    private var houseAddress by mutableStateOf<String?>(null)
    private var alertMinutes by mutableStateOf(10)

    private var truckStatus by mutableStateOf<ResidentApi.TruckStatus?>(null)

    // The actual map UI/permissions/search live in LocationPickerActivity -
    // this just launches it and reads back what the resident picked.
    private val locationPickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult

            val latitude = data.getDoubleExtra(LocationPickerActivity.EXTRA_RESULT_LATITUDE, Double.NaN)
            val longitude = data.getDoubleExtra(LocationPickerActivity.EXTRA_RESULT_LONGITUDE, Double.NaN)

            if (!latitude.isNaN() && !longitude.isNaN()) {
                latitudeText = latitude.toString()
                longitudeText = longitude.toString()
                houseAddress = data.getStringExtra(LocationPickerActivity.EXTRA_RESULT_ADDRESS)
            }
        }

    // Polls the server for this resident's truck/ETA while the status
    // screen (or the map) is visible. Registration itself does not need
    // this - only showing live status does.
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshTruckStatus()
            statusHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
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
        houseAddress = prefs.getString(AppPrefs.KEY_ADDRESS, null)
        alertMinutes = prefs.getInt(AppPrefs.KEY_ALERT_MINUTES, 10)

        setContent {
            KachraFreeResidentTheme {
                val houseLatLng = latitudeText.toDoubleOrNull()?.let { lat ->
                    longitudeText.toDoubleOrNull()?.let { lng -> LatLng(lat, lng) }
                }

                when {
                    showTruckMap && houseLatLng != null -> TruckMapScreen(
                        houseLatLng = houseLatLng,
                        truckLatLng = truckStatus?.let { status ->
                            val lat = status.truckLatitude
                            val lng = status.truckLongitude
                            if (lat != null && lng != null) LatLng(lat, lng) else null
                        },
                        pathPoints = truckStatus?.path.orEmpty(),
                        etaMinutes = truckStatus?.etaMinutes,
                        truckId = truckStatus?.truckId,
                        onBack = { showTruckMap = false }
                    )

                    registered && !editing -> StatusScreen(
                        phoneNumber = phoneNumber,
                        houseLocationLabel = houseLocationLabel(),
                        alertMinutes = alertMinutes,
                        truckStatus = truckStatus,
                        onEdit = {
                            editing = true
                            refreshStatusPolling()
                        },
                        onViewMap = { showTruckMap = true }
                    )

                    else -> RegisterScreen(
                        phoneNumber = phoneNumber,
                        onPhoneNumberChange = { phoneNumber = it },
                        houseLocationLabel = houseLocationLabel(),
                        onPickLocation = { launchLocationPicker() },
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

    private fun houseLocationLabel(): String {
        houseAddress?.let { return it }

        val lat = latitudeText.toDoubleOrNull()
        val lng = longitudeText.toDoubleOrNull()

        return if (lat != null && lng != null) {
            "%.6f, %.6f".format(lat, lng)
        } else {
            "Not set yet"
        }
    }

    private fun launchLocationPicker() {
        val intent = Intent(this, LocationPickerActivity::class.java)

        latitudeText.toDoubleOrNull()?.let {
            intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LATITUDE, it)
        }
        longitudeText.toDoubleOrNull()?.let {
            intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LONGITUDE, it)
        }

        locationPickerLauncher.launch(intent)
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
            .putString(AppPrefs.KEY_ADDRESS, houseAddress)
            .putInt(AppPrefs.KEY_ALERT_MINUTES, alertMinutes)
            .apply()

        phoneNumber = phone
        registered = true
        editing = false
        truckStatus = null

        refreshStatusPolling()

        val address = houseAddress

        Thread {
            val success = ResidentApi.register(phone, latitude, longitude, alertMinutes, address)
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
}

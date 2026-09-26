package com.example.kachrafreeresident

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
import com.example.kachrafreeresident.ui.main.LocationPickerScreen
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * Full-screen "choose your house on a map" picker. Started for a result:
 * returns [EXTRA_RESULT_LATITUDE]/[EXTRA_RESULT_LONGITUDE] (and, when
 * reverse geocoding succeeds, [EXTRA_RESULT_ADDRESS]) on confirm.
 */
class LocationPickerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_LATITUDE = "initial_latitude"
        const val EXTRA_INITIAL_LONGITUDE = "initial_longitude"
        const val EXTRA_RESULT_LATITUDE = "result_latitude"
        const val EXTRA_RESULT_LONGITUDE = "result_longitude"
        const val EXTRA_RESULT_ADDRESS = "result_address"

        // Roughly the center of India - just a reasonable starting point
        // when there's no earlier location to open the map on.
        private const val DEFAULT_LATITUDE = 20.5937
        private const val DEFAULT_LONGITUDE = 78.9629
    }

    private var moveCameraTo by mutableStateOf<GeoPoint?>(null)
    private var pickedAddress by mutableStateOf<String?>(null)

    private var searchQuery by mutableStateOf("")
    private var hasSearched by mutableStateOf(false)
    private var searchFailed by mutableStateOf(false)
    private var searchResults by mutableStateOf<List<ServerApi.PlaceResult>>(emptyList())

    private val locationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val granted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (granted) {
                findCurrentLocation()
            } else {
                Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
            }
        }

    // One fix per "use my location" tap: it unregisters itself as soon as a
    // fix arrives. The resident is never tracked continuously.
    private val oneShotLocationListener = object : LocationListener {

        override fun onLocationChanged(location: Location) {
            moveCameraTo = GeoPoint(location.latitude, location.longitude)
            getSystemService(LocationManager::class.java).removeUpdates(this)
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

        val initialLatitude = intent.getDoubleExtra(EXTRA_INITIAL_LATITUDE, Double.NaN)
        val initialLongitude = intent.getDoubleExtra(EXTRA_INITIAL_LONGITUDE, Double.NaN)

        val initialLatLng =
            if (!initialLatitude.isNaN() && !initialLongitude.isNaN()) {
                GeoPoint(initialLatitude, initialLongitude)
            } else {
                GeoPoint(DEFAULT_LATITUDE, DEFAULT_LONGITUDE)
            }

        setContent {
            KachraFreeResidentTheme {
                LocationPickerScreen(
                    initialLatLng = initialLatLng,
                    moveCameraTo = moveCameraTo,
                    pickedAddress = pickedAddress,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onSearchSubmit = { runSearch() },
                    hasSearched = hasSearched,
                    searchFailed = searchFailed,
                    searchResults = searchResults,
                    onResultSelected = { result ->
                        moveCameraTo = result.latLng
                        hasSearched = false
                        searchQuery = result.name
                    },
                    onCameraSettled = { latLng ->
                        pickedAddress = null
                        reverseGeocode(latLng)
                    },
                    onCameraMoveDone = { moveCameraTo = null },
                    onLocateMeClick = { requestCurrentLocation() },
                    onConfirm = { latLng -> confirmSelection(latLng) }
                )
            }
        }
    }

    private fun runSearch() {
        val query = searchQuery

        if (query.isBlank()) return

        Thread {
            val results = ServerApi.searchPlaces(query)

            runOnUiThread {
                hasSearched = true
                searchFailed = results == null
                searchResults = results.orEmpty()
            }
        }.start()
    }

    private fun requestCurrentLocation() {
        if (hasLocationPermission()) {
            findCurrentLocation()
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

    private fun findCurrentLocation() {

        val manager = getSystemService(LocationManager::class.java)

        val provider = when {
            isProviderEnabled(manager, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            isProviderEnabled(manager, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            Toast.makeText(this, "Turn on Location to use this", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val lastKnown = manager.getLastKnownLocation(provider)

            if (lastKnown != null) {
                moveCameraTo = GeoPoint(lastKnown.latitude, lastKnown.longitude)
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

    private fun isProviderEnabled(manager: LocationManager, provider: String): Boolean {
        return try {
            manager.isProviderEnabled(provider)
        } catch (_: Exception) {
            false
        }
    }

    private fun reverseGeocode(latLng: GeoPoint) {
        Thread {
            val address = try {
                val geocoder = Geocoder(this, Locale.getDefault())
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                results?.firstOrNull()?.getAddressLine(0)
            } catch (_: Exception) {
                null
            }

            runOnUiThread {
                pickedAddress = address
            }
        }.start()
    }

    private fun confirmSelection(latLng: GeoPoint) {
        val result = Intent().apply {
            putExtra(EXTRA_RESULT_LATITUDE, latLng.latitude)
            putExtra(EXTRA_RESULT_LONGITUDE, latLng.longitude)
            putExtra(EXTRA_RESULT_ADDRESS, pickedAddress)
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

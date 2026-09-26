package com.example.kachrafreedriver

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that reports this truck's location to the server
 * every UPDATE_INTERVAL_MS, with no driver interaction needed.
 */
class LocationService : Service() {

    companion object {
        const val ACTION_START = "com.kachrafree.driver.START_TRACKING"
        const val ACTION_STOP = "com.kachrafree.driver.STOP_TRACKING"
        const val EXTRA_TRUCK_ID = "truck_id"

        private const val CHANNEL_ID = "kachra_free_tracking"
        private const val NOTIFICATION_ID = 1001

        // How often we ask for a location fix, and so how often one is sent
        // to the server. minDistance is 0, so a parked truck still reports.
        private const val UPDATE_INTERVAL_MS = 10_000L

        // MainActivity reads these once a second to show the live state.
        // They only live in memory: after the process dies they reset,
        // which is correct, because the service died with it.
        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastLocation: LocationSnapshot? = null
            private set

        // null until the first upload attempt finishes.
        @Volatile
        var lastUploadOk: Boolean? = null
            private set

        @Volatile
        var lastUploadAtElapsedMillis: Long? = null
            private set

        // This truck's collection stops, in order, as the server last sent
        // them. Refreshed by every upload, and by MainActivity after "Add stop".
        @Volatile
        var routeStops: List<ServerApi.Stop>? = null
    }

    data class LocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        // SystemClock.elapsedRealtime(), not wall-clock time, so "seconds
        // ago" stays right even if the phone's clock changes.
        val receivedAtElapsedMillis: Long
    )

    private lateinit var locationManager: LocationManager
    private lateinit var prefs: SharedPreferences

    private var truckId = ""
    private var lastGpsFixAtElapsedMillis = 0L

    private val locationListener = object : LocationListener {

        override fun onLocationChanged(location: Location) {
            val now = SystemClock.elapsedRealtime()

            if (location.provider == LocationManager.GPS_PROVIDER) {
                lastGpsFixAtElapsedMillis = now
            } else if (now - lastGpsFixAtElapsedMillis < UPDATE_INTERVAL_MS * 2) {
                // GPS is working, so skip the less accurate network fix
                // instead of sending two jumpy positions every interval.
                return
            }

            reportLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            updateNotification("Truck $truckId - location on, waiting for GPS...")
        }

        override fun onProviderDisabled(provider: String) {
            // Location being switched off (maybe by accident) must not end
            // tracking. The registration stays alive, and Android resumes
            // delivering fixes as soon as location is back on.
            lastLocation = null
            updateNotification("Truck $truckId - waiting for GPS (location is off)")
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        prefs = getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {

            ACTION_STOP -> stopTracking()

            ACTION_START -> startTracking(intent.getStringExtra(EXTRA_TRUCK_ID).orEmpty())

            // intent is null when Android restarts this service itself after
            // killing it (see START_STICKY). Resume with the saved Truck ID.
            null -> {
                val wasTracking = prefs.getBoolean(AppPrefs.KEY_TRACKING, false)
                val savedId = prefs.getString(AppPrefs.KEY_TRUCK_ID, null).orEmpty()
                if (wasTracking && savedId.isNotBlank()) startTracking(savedId) else stopSelf()
            }
        }

        // Ask Android to restart this service if it's killed: the truck
        // should keep reporting for the whole shift.
        return START_STICKY
    }

    private fun startTracking(id: String) {

        if (id.isBlank() || !hasLocationPermission()) {
            stopSelf()
            return
        }

        truckId = id

        prefs.edit()
            .putString(AppPrefs.KEY_TRUCK_ID, id)
            .putBoolean(AppPrefs.KEY_TRACKING, true)
            .apply()

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification("Truck $truckId - waiting for GPS..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )

            // Registering works even while a provider is switched off:
            // Android waits and delivers fixes once it's back on. GPS is the
            // accurate one; network location covers places GPS can't see.
            requestUpdatesFrom(LocationManager.GPS_PROVIDER)
            requestUpdatesFrom(LocationManager.NETWORK_PROVIDER)

            isRunning = true

        } catch (e: Exception) {
            updateNotification("Tracking failed: ${e.javaClass.simpleName}")
            isRunning = false
            stopSelf()
        }
    }

    private fun stopTracking() {

        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {
        }

        prefs.edit().putBoolean(AppPrefs.KEY_TRACKING, false).apply()

        clearLiveState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearLiveState()
    }

    private fun clearLiveState() {
        isRunning = false
        lastLocation = null
        lastUploadOk = null
        lastUploadAtElapsedMillis = null
        routeStops = null
    }

    private fun reportLocation(location: Location) {

        lastLocation = LocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            receivedAtElapsedMillis = SystemClock.elapsedRealtime()
        )

        val id = truckId

        // One small request every 10 seconds: a plain background thread is
        // enough. A failed upload (no signal, server down) is simply dropped;
        // the next fix comes 10 seconds later.
        Thread {
            val stops = ServerApi.sendLocation(id, location.latitude, location.longitude, location.time)
            val ok = stops != null

            lastUploadOk = ok
            if (ok) {
                lastUploadAtElapsedMillis = SystemClock.elapsedRealtime()
                routeStops = stops
            }

            updateNotification(
                if (ok) "Truck $id - sharing location" else "Truck $id - server unreachable, retrying"
            )
        }.start()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestUpdatesFrom(provider: String) {
        try {
            locationManager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f, locationListener)
        } catch (_: Exception) {
            // This provider doesn't exist on this device at all (e.g. no
            // network location without Play services). Skip it.
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Kachra Free Tracking", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shows when truck location tracking is active"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Kachra Free Driver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

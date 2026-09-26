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
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Keeps a foreground GPS fix running and uploads it to the server for the
 * registered truck. Everything the server needs to know how to talk back
 * to this app lives in [ServerConfig].
 */
class LocationService : Service() {

    companion object {
        const val ACTION_START =
            "com.kachrafree.driver.START_TRACKING"

        const val ACTION_STOP =
            "com.kachrafree.driver.STOP_TRACKING"

        const val EXTRA_TRUCK_ID = "truck_id"

        private const val CHANNEL_ID =
            "kachra_free_tracking"

        private const val NOTIFICATION_ID = 1001

        // True only while this process has the service actively running.
        // MainActivity reads this to know the real tracking state instead
        // of trusting a saved preference that could be stale.
        @Volatile
        var isRunning: Boolean = false
            private set

        // The most recent GPS fix. MainActivity polls this to show the
        // coordinates and how many seconds old they are on screen.
        @Volatile
        var lastLocation: LocationSnapshot? = null
            private set
    }

    data class LocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        // SystemClock.elapsedRealtime(), not wall-clock time, so "seconds
        // ago" stays correct even if the phone's clock changes.
        val receivedAtElapsedMillis: Long
    )

    private lateinit var locationManager: LocationManager
    private lateinit var prefs: SharedPreferences

    private var truckId: String = ""

    private val locationListener = object : LocationListener {

        override fun onLocationChanged(location: Location) {
            lastLocation = LocationSnapshot(
                latitude = location.latitude,
                longitude = location.longitude,
                receivedAtElapsedMillis = SystemClock.elapsedRealtime()
            )
            uploadLocation(location)
        }

        override fun onProviderEnabled(provider: String) {
            updateNotification("Truck $truckId - location enabled, waiting for GPS...")
        }

        override fun onProviderDisabled(provider: String) {
            // Location being turned off should not end tracking - the
            // driver may have done this by accident, or it may come back
            // on its own (tunnel, airplane mode toggle, etc). Just show
            // "waiting for GPS" and keep the service + registration alive;
            // Android will resume delivering fixes as soon as it's back on.
            lastLocation = null
            updateNotification("Truck $truckId - waiting for GPS (location is off)")
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?
        ) {
        }
    }

    override fun onCreate() {
        super.onCreate()

        locationManager =
            getSystemService(
                Context.LOCATION_SERVICE
            ) as LocationManager

        prefs = getSharedPreferences(
            AppPrefs.FILE_NAME,
            Context.MODE_PRIVATE
        )

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                stopTracking()
            }

            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_TRUCK_ID).orEmpty()
                startTracking(id)
            }

            // intent is null when Android restarts this service on its own
            // after killing it (see START_STICKY below). There is no fresh
            // Truck ID in that case, so fall back to the last one we saved.
            null -> {
                val wasTracking = prefs.getBoolean(AppPrefs.KEY_TRACKING, false)
                val savedId = prefs.getString(AppPrefs.KEY_TRUCK_ID, null).orEmpty()

                if (wasTracking && savedId.isNotBlank()) {
                    startTracking(savedId)
                } else {
                    stopSelf()
                }
            }
        }

        // Ask Android to restart this service if it gets killed, since the
        // truck should keep reporting its location for the whole shift.
        return START_STICKY
    }

    private fun startTracking(id: String) {

        if (id.isBlank()) {
            stopSelf()
            return
        }

        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        truckId = id

        prefs.edit()
            .putString(AppPrefs.KEY_TRUCK_ID, id)
            .putBoolean(AppPrefs.KEY_TRACKING, true)
            .apply()

        val notification =
            createNotification(
                "Truck $truckId - waiting for GPS..."
            )

        try {

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )

            // Registering for updates works even while a provider is
            // currently turned off - Android just waits and delivers fixes
            // once it's back on, which is exactly the behaviour we want.
            // GPS is the most accurate; network location is a fallback for
            // when there is no clear sky view (e.g. indoors, dense streets).
            requestUpdatesFrom(LocationManager.GPS_PROVIDER)
            requestUpdatesFrom(LocationManager.NETWORK_PROVIDER)

            isRunning = true

        } catch (e: Exception) {

            showSimpleNotification(
                "Tracking failed: ${e.javaClass.simpleName}"
            )

            isRunning = false
            stopSelf()
        }
    }

    private fun stopTracking() {

        if (::locationManager.isInitialized) {
            try {
                locationManager.removeUpdates(
                    locationListener
                )
            } catch (_: Exception) {
            }
        }

        if (::prefs.isInitialized) {
            prefs.edit()
                .putBoolean(AppPrefs.KEY_TRACKING, false)
                .apply()
        }

        isRunning = false
        lastLocation = null

        ServiceCompat.stopForeground(
            this,
            ServiceCompat.STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        lastLocation = null
    }

    private fun uploadLocation(location: Location) {

        val text =
            "Truck $truckId: %.6f, %.6f".format(
                location.latitude,
                location.longitude
            )
        updateNotification(text)

        val id = truckId
        val latitude = location.latitude
        val longitude = location.longitude
        val time = location.time

        // A plain background thread is enough here: one small HTTP request
        // every few seconds, no need for a bigger networking library yet.
        Thread {
            val success = sendLocationToServer(id, latitude, longitude, time)

            if (success) {
                updateNotification("Truck $id - last update sent")
            } else {
                updateNotification("Truck $id - server unreachable, retrying")
            }
        }.start()
    }

    /**
     * Sends one location update to the server. Returns true if the server
     * accepted it. Any network problem (no signal, server offline, wrong
     * URL) is treated as "try again on the next GPS update" rather than a
     * crash - losing one update is fine, the next one arrives in seconds.
     */
    private fun sendLocationToServer(
        truckId: String,
        latitude: Double,
        longitude: Double,
        timestamp: Long
    ): Boolean {

        return try {
            val url = URL(ServerConfig.locationUpdateUrl())
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Content-Type", "application/json")

            val body = JSONObject()
                .put("truckId", truckId)
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("timestamp", timestamp)
                .toString()

            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }

            val ok = connection.responseCode in 200..299
            connection.disconnect()
            ok

        } catch (_: Exception) {
            false
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

    private fun requestUpdatesFrom(provider: String) {

        try {
            locationManager.requestLocationUpdates(
                provider,
                10_000L,
                5f,
                locationListener
            )
        } catch (_: Exception) {
            // This provider doesn't exist on this device at all (e.g. no
            // GPS hardware, or no network location without Play services).
            // The other provider (or a later location settings change)
            // still has a chance to work, so just skip this one.
        }
    }

    private fun createNotificationChannel() {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kachra Free Tracking",
            NotificationManager.IMPORTANCE_LOW
        )

        channel.description =
            "Shows when truck location tracking is active"

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(channel)
    }

    private fun createNotification(
        text: String
    ): Notification {

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("Kachra Free Driver")
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.ic_menu_mylocation
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(
        text: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            createNotification(text)
        )
    }

    private fun showSimpleNotification(
        text: String
    ) {
        updateNotification(text)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

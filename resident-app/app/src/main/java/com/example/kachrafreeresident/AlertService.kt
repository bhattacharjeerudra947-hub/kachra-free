package com.example.kachrafreeresident

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps checking this resident's truck status even
 * after the app is closed and removed from Recents, so the "truck is N
 * minutes away" alert still arrives. This is the same kind of always-on
 * foreground service the driver app uses for GPS tracking - the ongoing
 * notification is what lets Android keep it running in the background.
 *
 * No push service (e.g. Firebase) is needed for this: the service does its
 * own polling instead of waiting for the server to wake it up. The
 * trade-off is battery use, not reliability - see docs/EXPLAINER.md.
 */
class AlertService : Service() {

    companion object {
        const val ACTION_START = "com.kachrafree.resident.START_WATCHING"
        const val ACTION_STOP = "com.kachrafree.resident.STOP_WATCHING"

        private const val WATCH_CHANNEL_ID = "kachra_free_watching"
        private const val ALERT_CHANNEL_ID = "kachra_free_alerts"
        private const val WATCH_NOTIFICATION_ID = 2001

        // How often to ask the server for a fresh status while this service
        // runs, whether or not the app's screen is open.
        private const val POLL_INTERVAL_MS = 15_000L

        // MainActivity reads this once a second to show live status without
        // running its own separate network polling.
        @Volatile
        var lastStatus: ServerApi.TruckStatus? = null
            private set

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private val pollRunnable = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopWatching()
            // ACTION_START and null (Android restarting us after being
            // killed) both mean the same thing here: watch for this phone's
            // registration, whichever that currently is.
            else -> startWatching()
        }
        // Ask Android to restart this service if it's killed: alerts should
        // keep working for as long as the resident stays registered.
        return START_STICKY
    }

    private fun startWatching() {
        val phone = prefs.getString(AppPrefs.KEY_PHONE_NUMBER, null)

        if (phone.isNullOrBlank() || !prefs.getBoolean(AppPrefs.KEY_REGISTERED, false)) {
            stopSelf()
            return
        }

        try {
            ServiceCompat.startForeground(
                this,
                WATCH_NOTIFICATION_ID,
                createWatchNotification("Watching for garbage truck alerts..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            isRunning = true
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
        } catch (e: Exception) {
            isRunning = false
            stopSelf()
        }
    }

    private fun stopWatching() {
        handler.removeCallbacks(pollRunnable)
        isRunning = false
        lastStatus = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    /** Runs on the main thread; the actual network call happens on a background thread. */
    private fun poll() {
        val phone = prefs.getString(AppPrefs.KEY_PHONE_NUMBER, null)
        if (phone == null) return

        Thread {
            var status = ServerApi.fetchStatus(phone)

            // The server doesn't know us yet - the registration probably
            // happened while it was unreachable. Send it again now.
            if (status?.notRegistered == true) {
                status = retryRegistration(phone)
            }

            lastStatus = status
            if (status != null) {
                updateWatchNotification(status)
                val alert = status.alert
                if (alert != null) showAlertNotification(alert)
            }
        }.start()
    }

    /** Blocking; called from a background thread. */
    private fun retryRegistration(phone: String): ServerApi.TruckStatus? {
        val truckId = prefs.getString(AppPrefs.KEY_TRUCK_ID, "").orEmpty()
        val latitude = prefs.getString(AppPrefs.KEY_LATITUDE, "").orEmpty().toDoubleOrNull()
        val longitude = prefs.getString(AppPrefs.KEY_LONGITUDE, "").orEmpty().toDoubleOrNull()
        if (latitude == null || longitude == null) return null

        val alertMinutes = prefs.getInt(AppPrefs.KEY_ALERT_MINUTES, 10)
        val address = prefs.getString(AppPrefs.KEY_ADDRESS, null)

        return when (ServerApi.register(phone, truckId, latitude, longitude, alertMinutes, address)) {
            ServerApi.RegisterResult.OK -> ServerApi.fetchStatus(phone)
            ServerApi.RegisterResult.UNKNOWN_TRUCK -> ServerApi.TruckStatus(
                message = "The server doesn't know truck $truckId. Check the Truck ID in Edit registration."
            )
            ServerApi.RegisterResult.UNREACHABLE -> null
        }
    }

    private fun showAlertNotification(alert: ServerApi.Alert) {
        // The server keeps returning the same alert for the whole run; only
        // notify the first time we see it.
        if (prefs.getString(AppPrefs.KEY_LAST_ALERT_ID, null) == alert.id) return
        prefs.edit().putString(AppPrefs.KEY_LAST_ALERT_ID, alert.id).apply()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Garbage truck is coming")
            .setContentText(alert.message)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(alert.id.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        // Quiet, for the always-there "watching" notification.
        val watchChannel = NotificationChannel(WATCH_CHANNEL_ID, "Kachra Free Watching", NotificationManager.IMPORTANCE_LOW)
        watchChannel.description = "Shows that the app is checking for truck alerts in the background"
        manager.createNotificationChannel(watchChannel)

        // Loud, for "the truck is coming" alerts.
        val alertChannel = NotificationChannel(ALERT_CHANNEL_ID, "Garbage truck alerts", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(alertChannel)
    }

    private fun createWatchNotification(text: String): Notification {
        return Notification.Builder(this, WATCH_CHANNEL_ID)
            .setContentTitle("Kachra Free Resident")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateWatchNotification(status: ServerApi.TruckStatus) {
        var text = "Watching for garbage truck alerts..."
        if (status.notRegistered) {
            text = "Sending your registration to the server..."
        } else if (status.message != null) {
            text = status.message
        }
        getSystemService(NotificationManager::class.java).notify(WATCH_NOTIFICATION_ID, createWatchNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

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
        // runs, whether or not the app's screen is open. (Each one is a
        // request: through ngrok's free plan, keep this from getting much
        // faster.)
        private const val POLL_INTERVAL_MS = 2_000L

        // MainActivity reads these every second to show live status without
        // running its own separate network polling.
        @Volatile
        var lastStatus: ServerApi.TruckStatus? = null
            private set

        // Did the last poll reach the server? null before the first one.
        @Volatile
        var lastPollOk: Boolean? = null
            private set

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    // true while a poll is waiting for the server; the next one is skipped
    // until it finishes, so a slow network can't pile up requests.
    @Volatile
    private var polling = false

    // The ongoing notification's current text, so it's only redrawn when it changes.
    private var watchText = ""

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
            // killed) both mean the same thing here: watch for this
            // resident's registration, whichever that currently is.
            else -> startWatching()
        }
        // Ask Android to restart this service if it's killed: alerts should
        // keep working for as long as the resident stays registered.
        return START_STICKY
    }

    private fun startWatching() {
        val username = prefs.getString(AppPrefs.KEY_USERNAME, "").orEmpty()
        if (!prefs.getBoolean(AppPrefs.KEY_REGISTERED, false) || username.isBlank()) {
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
        lastPollOk = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }

    /** Runs on the main thread; the actual network call happens on a background thread. */
    private fun poll() {
        val username = prefs.getString(AppPrefs.KEY_USERNAME, "").orEmpty()
        if (polling) return
        polling = true

        Thread {
            var status = ServerApi.fetchStatus(username)

            // The server doesn't know us yet - the registration probably
            // happened while it was unreachable. Send it again now.
            if (status?.notRegistered == true) {
                status = retryRegistration(username)
            }

            lastStatus = status
            lastPollOk = status != null
            if (status != null) {
                updateWatchNotification(status)
                val alert = status.alert
                if (alert != null) showAlertNotification(alert)
            }
            polling = false
        }.start()
    }

    /** Blocking; called from a background thread. */
    private fun retryRegistration(username: String): ServerApi.TruckStatus? {
        val truckId = prefs.getString(AppPrefs.KEY_TRUCK_ID, "").orEmpty()
        val latitude = prefs.getString(AppPrefs.KEY_LATITUDE, "").orEmpty().toDoubleOrNull()
        val longitude = prefs.getString(AppPrefs.KEY_LONGITUDE, "").orEmpty().toDoubleOrNull()
        if (latitude == null || longitude == null) return null

        val alertMinutes = prefs.getInt(AppPrefs.KEY_ALERT_MINUTES, 10)
        val address = prefs.getString(AppPrefs.KEY_ADDRESS, null)

        return when (ServerApi.register(username, truckId, latitude, longitude, alertMinutes, address)) {
            ServerApi.RegisterResult.OK -> ServerApi.fetchStatus(username)
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

    /** text: one short line. details: the full message, shown when expanded. */
    private fun createWatchNotification(text: String, details: String = text): Notification {
        // Tapping the notification opens the app.
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, WATCH_CHANNEL_ID)
            .setContentTitle("Kachra Free Resident")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateWatchNotification(status: ServerApi.TruckStatus) {
        val text = shortStatus(status)
        if (text == watchText) return // nothing new to show
        watchText = text
        val details = status.message ?: text
        getSystemService(NotificationManager::class.java)
            .notify(WATCH_NOTIFICATION_ID, createWatchNotification(text, details))
    }

    /** One line for the ongoing notification, e.g. "Truck about 8 min away · 2 stops before yours". */
    private fun shortStatus(status: ServerApi.TruckStatus): String {
        if (status.notRegistered) return "Sending your registration to the server..."
        when (status.status) {
            "on_the_way" -> {
                val stopsAway = status.stopsAway ?: 0
                var before = "your stop is next"
                if (stopsAway == 1) before = "1 stop before yours"
                if (stopsAway > 1) before = "$stopsAway stops before yours"
                return "Truck about ${status.etaMinutes} min away · $before"
            }
            "at_stop" -> return "The truck is at your stop now!"
            "collected" -> return "Garbage collected at your stop today ✓"
            "skipped" -> return "The truck skipped your stop today"
            "truck_offline" -> return "The truck isn't on the road right now"
        }
        return status.message ?: "Watching for garbage truck alerts..."
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

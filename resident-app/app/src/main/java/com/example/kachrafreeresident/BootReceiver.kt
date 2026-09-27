package com.example.kachrafreeresident

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restarts AlertService after the phone reboots, if the resident is
 * registered. START_STICKY (see AlertService) only survives the service
 * being killed while the phone stays on; a reboot needs this instead.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(AppPrefs.FILE_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AppPrefs.KEY_REGISTERED, false)) return

        val start = Intent(context, AlertService::class.java)
        start.action = AlertService.ACTION_START
        ContextCompat.startForegroundService(context, start)
    }
}

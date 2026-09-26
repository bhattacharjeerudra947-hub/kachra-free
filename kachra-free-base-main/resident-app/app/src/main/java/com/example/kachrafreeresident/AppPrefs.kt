package com.example.kachrafreeresident

/**
 * Shared preferences keys for the resident's saved registration. Everything
 * here is local-first: the app always works offline using these values,
 * and separately tries to keep the server's copy in sync (see ResidentApi).
 */
object AppPrefs {
    const val FILE_NAME = "resident_prefs"

    const val KEY_REGISTERED = "registered"
    const val KEY_PHONE_NUMBER = "phone_number"
    const val KEY_LATITUDE = "latitude"
    const val KEY_LONGITUDE = "longitude"
    const val KEY_ADDRESS = "address"
    const val KEY_ALERT_MINUTES = "alert_minutes"
}

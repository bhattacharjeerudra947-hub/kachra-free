package com.example.kachrafreeresident

/**
 * The resident's saved registration. The app always works from these
 * values, even offline, and separately keeps the server's copy in sync.
 */
object AppPrefs {
    const val FILE_NAME = "resident_prefs"

    const val KEY_REGISTERED = "registered"
    const val KEY_PHONE_NUMBER = "phone_number"
    const val KEY_TRUCK_ID = "truck_id"
    const val KEY_LATITUDE = "latitude"
    const val KEY_LONGITUDE = "longitude"
    const val KEY_ADDRESS = "address"
    const val KEY_ALERT_MINUTES = "alert_minutes"

    // Alerts fire once per collection run; remembering the last one shown
    // stops the same alert from notifying again on every poll.
    const val KEY_LAST_ALERT_ID = "last_alert_id"
}

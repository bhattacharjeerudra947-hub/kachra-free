package com.example.kachrafreeresident

/**
 * The resident's saved registration. The app always works from these
 * values, even offline, and separately keeps the server's copy in sync.
 */
object AppPrefs {
    const val FILE_NAME = "resident_prefs"

    const val KEY_REGISTERED = "registered"
    // How the server tells residents apart: the same username is the same
    // resident, on this phone or another one.
    const val KEY_USERNAME = "username"
    const val KEY_TRUCK_ID = "truck_id"
    const val KEY_LATITUDE = "latitude"
    const val KEY_LONGITUDE = "longitude"
    const val KEY_ADDRESS = "address"
    const val KEY_ALERT_MINUTES = "alert_minutes"

    // Alerts fire once per collection run; remembering the last one shown
    // stops the same alert from notifying again on every poll.
    const val KEY_LAST_ALERT_ID = "last_alert_id"

    /**
     * null if the username is fine, otherwise what's wrong with it. The same
     * rules as the server: 3 to 30 letters, digits, ".", "_" or "-".
     */
    fun usernameProblem(username: String): String? {
        if (username.length < 3 || username.length > 30) return "Username must be 3 to 30 characters"
        for (ch in username) {
            if (!(ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-')) {
                return "Username can only have letters, numbers, '.', '_' and '-'"
            }
        }
        return null
    }
}

package com.example.kachrafreedriver

/**
 * Shared preferences keys used by both MainActivity and LocationService.
 * They need the same file name (not the Activity-only `getPreferences()`
 * shortcut) so the service can still read the Truck ID if Android restarts
 * it in the background without the app screen being open.
 */
object AppPrefs {
    const val FILE_NAME = "driver_prefs"
    const val KEY_TRUCK_ID = "truck_id"
    const val KEY_TRACKING = "tracking"
}

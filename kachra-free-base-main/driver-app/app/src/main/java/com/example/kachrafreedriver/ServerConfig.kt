package com.example.kachrafreedriver

/**
 * One place to point the driver app at the real server once it exists.
 *
 * 10.0.2.2 is the special address the Android emulator uses to reach
 * "localhost" on the computer running it - handy for testing against a
 * server running on this machine. Replace BASE_URL with the real server
 * address when it's ready (e.g. "https://kachrafree.example.com").
 */
object ServerConfig {
    const val BASE_URL = "http://10.0.2.2:8080"

    fun locationUpdateUrl(): String = "$BASE_URL/api/trucks/location"
}

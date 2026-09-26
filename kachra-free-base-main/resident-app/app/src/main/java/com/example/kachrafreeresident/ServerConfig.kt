package com.example.kachrafreeresident

/**
 * One place to point the resident app at the real server once it exists.
 *
 * 10.0.2.2 is the special address the Android emulator uses to reach
 * "localhost" on the computer running it - handy for testing against a
 * server running on this machine. Replace BASE_URL with the real server
 * address when it's ready (e.g. "https://kachrafree.example.com").
 *
 * See docs/PROTOCOL.md for the full request/response shape of each endpoint.
 */
object ServerConfig {
    const val BASE_URL = "http://10.0.2.2:8080"

    fun registerUrl(): String = "$BASE_URL/api/residents/register"

    fun statusUrl(): String = "$BASE_URL/api/residents/status"

    // Proxies to whatever geocoding/places provider the server is
    // configured with (via the admin panel) - the app never holds that
    // provider's API key. See PlacesApi.kt and docs/PROTOCOL.md.
    fun placesSearchUrl(): String = "$BASE_URL/api/places/search"
}

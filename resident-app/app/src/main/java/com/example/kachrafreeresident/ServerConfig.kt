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
}

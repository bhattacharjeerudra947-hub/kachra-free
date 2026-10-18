package com.example.kachrafreeresident

/**
 * Where the server is. The one line to change when the server moves.
 *
 * 10.0.2.2 is how the Android emulator reaches "localhost" on the computer
 * running it. On a real phone use the ngrok URL (docs/SETUP.md section 8).
 * Set serverBaseUrl in the gitignored local.properties file. The driver app
 * needs the same value.
 */
object ServerConfig {
    const val BASE_URL = BuildConfig.SERVER_BASE_URL
}

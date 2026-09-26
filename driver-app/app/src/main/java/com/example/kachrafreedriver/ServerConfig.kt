package com.example.kachrafreedriver

/**
 * Where the server is. The one line to change when the server moves.
 *
 * 10.0.2.2 is how the Android emulator reaches "localhost" on the computer
 * running it. On a real phone use the ngrok URL (docs/SETUP.md section 8).
 * Set the same URL in resident-app.
 */
object ServerConfig {
    const val BASE_URL = "https://undaunted-ditzy-botany.ngrok-free.dev"
}

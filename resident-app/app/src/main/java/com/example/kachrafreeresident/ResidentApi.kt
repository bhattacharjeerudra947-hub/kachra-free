package com.example.kachrafreeresident

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * All the resident app's HTTP calls to the server, in one place. Plain
 * HttpURLConnection + org.json is enough for two small JSON calls - no
 * networking library needed. Every function here does blocking network
 * I/O, so callers must run it off the main thread (see MainActivity).
 */
object ResidentApi {

    /** What the server currently knows about the truck relevant to this resident. */
    data class TruckStatus(
        val truckId: String?,
        val truckLatitude: Double?,
        val truckLongitude: Double?,
        val etaMinutes: Int?
    )

    /**
     * Registers (or updates, if the phone number already exists) this
     * resident's house location and alert preference. Returns true only if
     * the server accepted it - a false just means "try again later", the
     * registration is already saved locally regardless.
     */
    fun register(
        phoneNumber: String,
        latitude: Double,
        longitude: Double,
        alertMinutes: Int
    ): Boolean {

        return try {
            val url = URL(ServerConfig.registerUrl())
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Content-Type", "application/json")

            val body = JSONObject()
                .put("phoneNumber", phoneNumber)
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("alertMinutes", alertMinutes)
                .toString()

            connection.outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }

            val ok = connection.responseCode in 200..299
            connection.disconnect()
            ok

        } catch (_: Exception) {
            false
        }
    }

    /**
     * Looks up the truck/ETA currently relevant to this resident. Returns
     * null if the server can't be reached or has nothing yet - callers
     * should treat that the same as "no update available right now".
     */
    fun fetchStatus(phoneNumber: String): TruckStatus? {

        return try {
            val encodedPhone = URLEncoder.encode(phoneNumber, "UTF-8")
            val url = URL("${ServerConfig.statusUrl()}?phone=$encodedPhone")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000

            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return null
            }

            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val json = JSONObject(responseText)

            TruckStatus(
                truckId = json.optString("truckId").ifBlank { null },
                truckLatitude = if (json.has("truckLatitude")) json.optDouble("truckLatitude") else null,
                truckLongitude = if (json.has("truckLongitude")) json.optDouble("truckLongitude") else null,
                etaMinutes = if (json.has("etaMinutes") && !json.isNull("etaMinutes")) {
                    json.optInt("etaMinutes")
                } else {
                    null
                }
            )

        } catch (_: Exception) {
            null
        }
    }
}

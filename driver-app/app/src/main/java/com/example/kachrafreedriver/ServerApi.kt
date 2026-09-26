package com.example.kachrafreedriver

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * The driver app's two server calls (docs/EXPLAINER.md). Plain
 * HttpURLConnection + org.json. Both block, so call them off the main thread.
 *
 * Both return this truck's current stop list from the server, or null if
 * the server couldn't be reached. That's the two-way sync: stops the admin
 * adds, renames or reorders reach the driver on the next location update.
 */
object ServerApi {

    data class Stop(val name: String, val latitude: Double, val longitude: Double)

    fun sendLocation(truckId: String, latitude: Double, longitude: Double, timestamp: Long): List<Stop>? {
        val body = JSONObject()
            .put("truckId", truckId)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timestamp", timestamp)
        return postForStops("/api/trucks/location", body)
    }

    /** Adds a collection point at the end of this truck's collection order. */
    fun addStop(truckId: String, latitude: Double, longitude: Double): List<Stop>? {
        val body = JSONObject()
            .put("truckId", truckId)
            .put("latitude", latitude)
            .put("longitude", longitude)
        return postForStops("/api/trucks/stops", body)
    }

    private fun postForStops(path: String, body: JSONObject): List<Stop>? {
        return try {
            val connection = URL(ServerConfig.BASE_URL + path).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Content-Type", "application/json")
            // ngrok's free tier otherwise answers with an HTML warning page.
            connection.setRequestProperty("ngrok-skip-browser-warning", "true")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return null
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            parseStops(JSONObject(text).optJSONArray("stops"))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStops(array: JSONArray?): List<Stop> {
        val stops = mutableListOf<Stop>()
        if (array == null) return stops
        for (i in 0 until array.length()) {
            val stop = array.getJSONObject(i)
            stops.add(Stop(stop.getString("name"), stop.getDouble("latitude"), stop.getDouble("longitude")))
        }
        return stops
    }
}

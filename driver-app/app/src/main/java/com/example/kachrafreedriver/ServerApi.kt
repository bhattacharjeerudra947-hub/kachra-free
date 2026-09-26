package com.example.kachrafreedriver

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * The driver app's server calls (docs/EXPLAINER.md). Plain
 * HttpURLConnection + org.json. They all block, so call them off the main
 * thread.
 *
 * sendLocation and addStop return this truck's current stop list from the
 * server, or null if the server couldn't be reached. That's the two-way
 * sync: stops the admin adds, renames or reorders reach the driver on the
 * next location update.
 */
object ServerApi {

    data class Stop(val name: String, val latitude: Double, val longitude: Double)

    enum class TruckCheck { FOUND, UNKNOWN_TRUCK, UNREACHABLE }

    /** true if the server answered at all. */
    fun ping(): Boolean {
        return request("GET", "/api/ping", null).code in 200..299
    }

    /** Does the server know this Truck ID? Checked before tracking starts. */
    fun checkTruck(truckId: String): TruckCheck {
        val code = request("GET", "/api/trucks?truckId=" + URLEncoder.encode(truckId, "UTF-8"), null).code
        return when {
            code in 200..299 -> TruckCheck.FOUND
            code == 404 -> TruckCheck.UNKNOWN_TRUCK
            else -> TruckCheck.UNREACHABLE
        }
    }

    fun sendLocation(truckId: String, latitude: Double, longitude: Double, timestamp: Long): List<Stop>? {
        val body = JSONObject()
            .put("truckId", truckId)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("timestamp", timestamp)
        return stopsFrom(request("POST", "/api/trucks/location", body))
    }

    /** Adds a collection point at the end of this truck's collection order. */
    fun addStop(truckId: String, latitude: Double, longitude: Double): List<Stop>? {
        val body = JSONObject()
            .put("truckId", truckId)
            .put("latitude", latitude)
            .put("longitude", longitude)
        return stopsFrom(request("POST", "/api/trucks/stops", body))
    }

    private class Response(val code: Int, val json: JSONObject?)

    /** code is 0 when there was no response at all (offline, timeout, bad URL). */
    private fun request(method: String, path: String, body: JSONObject?): Response {
        return try {
            val connection = URL(ServerConfig.BASE_URL + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            // ngrok's free tier otherwise answers with an HTML warning page.
            connection.setRequestProperty("ngrok-skip-browser-warning", "true")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            var json: JSONObject? = null
            if (code in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                json = JSONObject(text)
            }
            connection.disconnect()
            Response(code, json)
        } catch (_: Exception) {
            Response(0, null)
        }
    }

    /** The stop list from a successful answer, or null. */
    private fun stopsFrom(response: Response): List<Stop>? {
        val json = response.json
        if (json == null) return null
        val stops = mutableListOf<Stop>()
        val array: JSONArray? = json.optJSONArray("stops")
        if (array == null) return stops
        for (i in 0 until array.length()) {
            val stop = array.getJSONObject(i)
            stops.add(Stop(stop.getString("name"), stop.getDouble("latitude"), stop.getDouble("longitude")))
        }
        return stops
    }
}

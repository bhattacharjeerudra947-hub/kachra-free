package com.example.kachrafreeresident

import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every call the resident app makes to the server (docs/EXPLAINER.md).
 * Plain HttpURLConnection + org.json: three small JSON calls don't need a
 * networking library. All of these block, so call them off the main thread.
 *
 * The app only ever talks to our own server. Even location search goes
 * through it (the server uses the free OpenStreetMap search). The only other
 * service the app touches is OpenStreetMap's map tiles (see OsmMap.kt).
 */
object ServerApi {

    data class Alert(val id: String, val message: String)

    // status is today's: "collected", "skipped", "next" or "pending".
    data class Stop(val name: String, val position: GeoPoint, val status: String)

    data class TruckStatus(
        // true when the server doesn't know this username (yet).
        val notRegistered: Boolean = false,
        // "on_the_way", "at_stop", "collected", "truck_offline", "no_stops" or
        // "unknown_truck" (docs/EXPLAINER.md section 8.8).
        val status: String? = null,
        // Human-readable summary from the server, e.g. "Truck is about 8 min away".
        val message: String? = null,
        val truckPosition: GeoPoint? = null,
        // All of the truck's stops in collection order, and which one is
        // this resident's collection point (the one nearest the house).
        val stops: List<Stop> = emptyList(),
        val stopIndex: Int? = null,
        val stopName: String? = null,
        val stopDistanceMeters: Int? = null,
        val etaMinutes: Int? = null,
        // true when the route server was down and the ETA is a rough guess.
        val etaIsRough: Boolean = false,
        // How many stops the truck still visits before this resident's.
        val stopsAway: Int? = null,
        // Usual arrival time at their stop, e.g. "07:40", from history.
        val usualTime: String? = null,
        // Road the truck is expected to take to their stop.
        val path: List<GeoPoint> = emptyList(),
        val alert: Alert? = null
    )

    data class PlaceResult(val name: String, val latLng: GeoPoint)

    enum class RegisterResult { OK, UNKNOWN_TRUCK, UNREACHABLE }

    fun register(
        username: String,
        truckId: String,
        latitude: Double,
        longitude: Double,
        alertMinutes: Int,
        address: String?
    ): RegisterResult {
        val body = JSONObject()
            .put("username", username)
            .put("truckId", truckId)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("alertMinutes", alertMinutes)
        if (!address.isNullOrBlank()) body.put("address", address)

        val code = request("POST", "/api/residents/register", body).code
        return when {
            code in 200..299 -> RegisterResult.OK
            code == 404 -> RegisterResult.UNKNOWN_TRUCK
            else -> RegisterResult.UNREACHABLE
        }
    }

    /** null means the server couldn't be reached. */
    fun fetchStatus(username: String): TruckStatus? {
        val response = request("GET", "/api/residents/status?username=${encode(username)}")
        if (response.code == 404) return TruckStatus(notRegistered = true)
        val json = response.json
        if (response.code !in 200..299 || json == null) return null

        var alert: Alert? = null
        val alertJson = json.optJSONObject("alert")
        if (alertJson != null) alert = Alert(alertJson.getString("id"), alertJson.getString("message"))

        var truckPosition: GeoPoint? = null
        if (!json.isNull("truckLatitude") && !json.isNull("truckLongitude")) {
            truckPosition = GeoPoint(json.getDouble("truckLatitude"), json.getDouble("truckLongitude"))
        }

        return TruckStatus(
            status = stringOrNull(json, "status"),
            message = stringOrNull(json, "message"),
            truckPosition = truckPosition,
            stops = parseStops(json.optJSONArray("stops")),
            stopIndex = intOrNull(json, "stopIndex"),
            stopName = stringOrNull(json, "stopName"),
            stopDistanceMeters = intOrNull(json, "stopDistanceMeters"),
            etaMinutes = intOrNull(json, "etaMinutes"),
            etaIsRough = json.optBoolean("etaIsRough", false),
            stopsAway = intOrNull(json, "stopsAway"),
            usualTime = stringOrNull(json, "usualTime"),
            path = parsePath(json.optJSONArray("path")),
            alert = alert
        )
    }

    /** true if the server answered at all. */
    fun ping(): Boolean {
        return request("GET", "/api/ping").code in 200..299
    }

    /** null means search isn't available right now (server down or not set up). */
    fun searchPlaces(query: String): List<PlaceResult>? {
        return parsePlaces(request("GET", "/api/places/search?query=${encode(query)}"))
    }

    /**
     * Suggestions for a half-typed search, places near (latitude, longitude)
     * first. null means suggestions aren't available right now.
     */
    fun suggestPlaces(query: String, latitude: Double, longitude: Double): List<PlaceResult>? {
        val path = "/api/places/suggest?query=${encode(query)}&lat=$latitude&lng=$longitude"
        return parsePlaces(request("GET", path))
    }

    private fun parsePlaces(response: Response): List<PlaceResult>? {
        val results = response.json?.optJSONArray("results")
        if (response.code !in 200..299 || results == null) return null

        val places = mutableListOf<PlaceResult>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val position = GeoPoint(item.getDouble("latitude"), item.getDouble("longitude"))
            places.add(PlaceResult(item.getString("name"), position))
        }
        return places
    }

    private class Response(val code: Int, val json: JSONObject?)

    /** code is 0 when there was no response at all (offline, timeout, bad URL). */
    private fun request(method: String, path: String, body: JSONObject? = null): Response {
        return try {
            val connection = URL(ServerConfig.BASE_URL + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 20_000
            // ngrok's free tier otherwise answers with an HTML warning page.
            connection.setRequestProperty("ngrok-skip-browser-warning", "true")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }

            val code = connection.responseCode
            // Error responses (404, 400...) have their body on errorStream.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            var text: String? = null
            if (stream != null) text = stream.bufferedReader().use { it.readText() }
            connection.disconnect()

            Response(code, parseJson(text))
        } catch (_: Exception) {
            Response(0, null)
        }
    }

    /** null if the text is missing or isn't a JSON object. */
    private fun parseJson(text: String?): JSONObject? {
        if (text == null) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun parsePath(array: JSONArray?): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        if (array == null) return points
        for (i in 0 until array.length()) {
            val point = array.getJSONObject(i)
            points.add(GeoPoint(point.getDouble("latitude"), point.getDouble("longitude")))
        }
        return points
    }

    private fun parseStops(array: JSONArray?): List<Stop> {
        val stops = mutableListOf<Stop>()
        if (array == null) return stops
        for (i in 0 until array.length()) {
            val stop = array.getJSONObject(i)
            val position = GeoPoint(stop.getDouble("latitude"), stop.getDouble("longitude"))
            stops.add(Stop(stop.getString("name"), position, stop.getString("status")))
        }
        return stops
    }

    // org.json returns the string "null" / NaN / 0 for JSON nulls, so check first.
    private fun intOrNull(json: JSONObject, key: String): Int? {
        if (json.isNull(key)) return null
        return json.optInt(key)
    }

    private fun stringOrNull(json: JSONObject, key: String): String? {
        if (json.isNull(key)) return null
        return json.optString(key)
    }
}

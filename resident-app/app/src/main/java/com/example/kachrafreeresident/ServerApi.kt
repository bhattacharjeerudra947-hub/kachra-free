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

    data class TruckStatus(
        // true when the server doesn't know this phone number (yet).
        val notRegistered: Boolean = false,
        // Human-readable summary from the server, e.g. "Truck is about 8 min away".
        val message: String? = null,
        val truckLatitude: Double? = null,
        val truckLongitude: Double? = null,
        // The truck's stop nearest the house, where the resident brings their garbage.
        val stopName: String? = null,
        val stopLatitude: Double? = null,
        val stopLongitude: Double? = null,
        val etaMinutes: Int? = null,
        // Road the truck is expected to take: its remaining stops, in collection order.
        val path: List<GeoPoint> = emptyList(),
        val alert: Alert? = null
    )

    data class PlaceResult(val name: String, val latLng: GeoPoint)

    enum class RegisterResult { OK, UNKNOWN_TRUCK, UNREACHABLE }

    fun register(
        phoneNumber: String,
        truckId: String,
        latitude: Double,
        longitude: Double,
        alertMinutes: Int,
        address: String?
    ): RegisterResult {
        val body = JSONObject()
            .put("phoneNumber", phoneNumber)
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
    fun fetchStatus(phoneNumber: String): TruckStatus? {
        val response = request("GET", "/api/residents/status?phone=${encode(phoneNumber)}")
        if (response.code == 404) return TruckStatus(notRegistered = true)
        val json = response.json
        if (response.code !in 200..299 || json == null) return null

        var etaMinutes: Int? = null
        if (!json.isNull("etaMinutes")) etaMinutes = json.optInt("etaMinutes")

        var alert: Alert? = null
        val alertJson = json.optJSONObject("alert")
        if (alertJson != null) alert = Alert(alertJson.getString("id"), alertJson.getString("message"))

        return TruckStatus(
            message = stringOrNull(json, "message"),
            truckLatitude = doubleOrNull(json, "truckLatitude"),
            truckLongitude = doubleOrNull(json, "truckLongitude"),
            stopName = stringOrNull(json, "stopName"),
            stopLatitude = doubleOrNull(json, "stopLatitude"),
            stopLongitude = doubleOrNull(json, "stopLongitude"),
            etaMinutes = etaMinutes,
            path = parsePath(json.optJSONArray("path")),
            alert = alert
        )
    }

    /** null means search isn't available right now (server down or not set up). */
    fun searchPlaces(query: String): List<PlaceResult>? {
        val response = request("GET", "/api/places/search?query=${encode(query)}")
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
            connection.readTimeout = 15_000
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

    // org.json returns the string "null" / NaN for JSON nulls, so check first.
    private fun stringOrNull(json: JSONObject, key: String): String? {
        if (json.isNull(key)) return null
        return json.optString(key)
    }

    private fun doubleOrNull(json: JSONObject, key: String): Double? {
        if (json.isNull(key)) return null
        return json.optDouble(key)
    }
}

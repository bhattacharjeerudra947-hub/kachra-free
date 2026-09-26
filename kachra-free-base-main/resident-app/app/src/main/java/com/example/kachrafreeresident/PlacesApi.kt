package com.example.kachrafreeresident

import com.google.android.gms.maps.model.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Location search, proxied through the server instead of calling a
 * mapping provider's Places/geocoding API directly from the phone.
 *
 * This is deliberate: a search API key is a plain HTTP call away from
 * being extracted from an APK, and there is no good way to restrict it the
 * way the Maps *rendering* key can be (see ServerConfig / AndroidManifest
 * comments). So that key lives on the server, configured through the admin
 * panel, and this app only ever talks to our own server. See
 * docs/PROTOCOL.md "API key handling".
 */
object PlacesApi {

    data class PlaceResult(
        val name: String,
        val latLng: LatLng
    )

    /**
     * Returns matching places for [query], or null if the server couldn't
     * be reached (as opposed to an empty list, which means the server
     * responded but found no matches) - callers use that difference to
     * show "search isn't available yet" vs "no results".
     */
    fun search(query: String): List<PlaceResult>? {

        if (query.isBlank()) return emptyList()

        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("${ServerConfig.placesSearchUrl()}?query=$encodedQuery")
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
            val results = json.optJSONArray("results") ?: return emptyList()

            (0 until results.length()).mapNotNull { index ->
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name").ifBlank { null } ?: return@mapNotNull null
                if (!item.has("latitude") || !item.has("longitude")) return@mapNotNull null

                PlaceResult(
                    name = name,
                    latLng = LatLng(item.getDouble("latitude"), item.getDouble("longitude"))
                )
            }

        } catch (_: Exception) {
            null
        }
    }
}

package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

/**
 * AGENTS.md section 5: house + the relevant truck + its expected path +
 * ETA, on one map. The path is only drawn when the server actually
 * supplies one (AGENTS.md is explicit that a straight line between truck
 * and house would be misleading) - with no path data, this just shows
 * both markers.
 */
@Composable
fun TruckMapScreen(
    houseLatLng: LatLng,
    truckLatLng: LatLng?,
    pathPoints: List<LatLng>,
    etaMinutes: Int?,
    truckId: String?,
    onBack: () -> Unit
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(houseLatLng, 15f)
    }

    var mapLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(mapLoaded, truckLatLng, pathPoints) {
        if (!mapLoaded) return@LaunchedEffect

        if (truckLatLng == null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(houseLatLng, 15f)
            return@LaunchedEffect
        }

        val bounds = LatLngBounds.Builder()
        bounds.include(houseLatLng)
        bounds.include(truckLatLng)
        pathPoints.forEach { bounds.include(it) }

        try {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngBounds(bounds.build(), 150),
                durationMs = 600
            )
        } catch (_: Exception) {
            // Map hasn't been measured yet, or the two points are
            // identical - just fall back to centering on the house.
            cameraPositionState.position = CameraPosition.fromLatLngZoom(houseLatLng, 15f)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            onMapLoaded = { mapLoaded = true }
        ) {

            Marker(
                state = rememberMarkerState(position = houseLatLng),
                title = "Your house",
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
            )

            if (truckLatLng != null) {
                Marker(
                    state = rememberMarkerState(position = truckLatLng),
                    title = truckId?.let { "Truck $it" } ?: "Truck",
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)
                )
            }

            if (pathPoints.size >= 2) {
                Polyline(
                    points = pathPoints,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            tonalElevation = 4.dp
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Button(onClick = onBack) {
                    Text("Back")
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            tonalElevation = 4.dp
        ) {
            Text(
                modifier = Modifier.padding(16.dp),
                text = when {
                    truckLatLng == null -> "Truck location not available yet"
                    etaMinutes != null -> "Truck ${truckId ?: ""} - ETA: $etaMinutes minutes"
                    else -> "Truck ${truckId ?: ""} - ETA not available yet"
                },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

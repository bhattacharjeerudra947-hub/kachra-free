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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline

/**
 * AGENTS.md section 5: the house, the collection point where the resident
 * meets the truck, the truck itself, the road it's expected to take there
 * (its remaining stops in collection order, from the server) and the ETA.
 */
@Composable
fun TruckMapScreen(
    houseLatLng: GeoPoint,
    stopLatLng: GeoPoint?,
    stopName: String?,
    truckLatLng: GeoPoint?,
    pathPoints: List<GeoPoint>,
    message: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val mapView = rememberMapView()
    val roadColor = MaterialTheme.colorScheme.primary.toArgb()
    // Frame house + truck once, when the truck first shows up. Not on every
    // 15 s poll: that would yank the map away from wherever the user moved it.
    var framed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    controller.setZoom(15.0)
                    controller.setCenter(houseLatLng)
                }
            },
            // Runs again whenever new status arrives: redraw everything.
            update = { map ->
                map.overlays.clear()

                if (pathPoints.size >= 2) {
                    val road = Polyline(map)
                    road.setPoints(pathPoints)
                    road.outlinePaint.color = roadColor
                    road.outlinePaint.strokeWidth = 10f
                    map.overlays.add(road)
                }
                map.overlays.add(dotMarker(map, context, houseLatLng, "Your house", "#1C7ED6"))
                if (stopLatLng != null) {
                    map.overlays.add(dotMarker(map, context, stopLatLng, "Your collection point: ${stopName ?: ""}", "#2F9E44"))
                }
                if (truckLatLng != null) {
                    map.overlays.add(dotMarker(map, context, truckLatLng, "Garbage truck", "#F76707"))
                }
                map.invalidate()

                if (!framed && truckLatLng != null) {
                    // Everything that should fit on screen.
                    val points = mutableListOf(houseLatLng, truckLatLng)
                    if (stopLatLng != null) points.add(stopLatLng)
                    points.addAll(pathPoints)
                    map.post {
                        framed = true
                        map.zoomToBoundingBox(BoundingBox.fromGeoPointsSafe(points), false, 120)
                    }
                }
            }
        )

        Button(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
        ) {
            Text("Back")
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            tonalElevation = 4.dp
        ) {
            Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
        }

        OsmCredit(modifier = Modifier.align(Alignment.TopEnd))
    }
}

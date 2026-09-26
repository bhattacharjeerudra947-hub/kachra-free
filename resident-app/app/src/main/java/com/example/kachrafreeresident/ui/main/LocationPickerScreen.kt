package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kachrafreeresident.ServerApi
import java.util.Locale
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint

private const val DEFAULT_ZOOM = 17.0

/**
 * Full-screen "drop a pin" location picker, the same pattern most delivery
 * apps use: the map pans freely underneath a pin that's fixed at the exact
 * screen center, rather than a draggable marker. Search results and "use my
 * location" both just move the camera - the picked point is always
 * whatever's under the pin once the map stops moving.
 */
@Composable
fun LocationPickerScreen(
    initialLatLng: GeoPoint,
    moveCameraTo: GeoPoint?,
    pickedAddress: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    hasSearched: Boolean,
    searchFailed: Boolean,
    searchResults: List<ServerApi.PlaceResult>,
    onResultSelected: (ServerApi.PlaceResult) -> Unit,
    onCameraSettled: (GeoPoint) -> Unit,
    onCameraMoveDone: () -> Unit,
    onLocateMeClick: () -> Unit,
    onConfirm: (GeoPoint) -> Unit
) {
    val mapView = rememberMapView()
    // Where the pin is: updated whenever the map stops moving.
    var center by remember { mutableStateOf(initialLatLng) }
    val settled by rememberUpdatedState(onCameraSettled)

    // Runs whenever moveCameraTo changes.
    LaunchedEffect(moveCameraTo) {
        if (moveCameraTo != null) {
            mapView.controller.animateTo(moveCameraTo, DEFAULT_ZOOM, 600L)
            // Clear the request, so asking for the same spot again (e.g.
            // tapping "my location" twice) moves the camera again.
            onCameraMoveDone()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    controller.setZoom(DEFAULT_ZOOM)
                    controller.setCenter(initialLatLng)
                    // Fires once panning/zooming has stopped for 400 ms: that
                    // point under the pin is the picked location.
                    addMapListener(DelayedMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean = onStop()
                        override fun onZoom(event: ZoomEvent?): Boolean = onStop()
                        private fun onStop(): Boolean {
                            center = centerPoint()
                            settled(center)
                            return true
                        }
                    }, 400))
                    // Address for the starting position straight away.
                    post { settled(centerPoint()) }
                }
            }
        )

        OsmCredit(modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 130.dp))

        // A pin fixed at the exact screen center - the map pans underneath
        // it. Simpler to get right than a draggable marker, and it's what
        // Amazon/Swiggy/Uber-style pickers do.
        Text(
            text = "📍",
            fontSize = 40.sp,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-20).dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            Row(verticalAlignment = Alignment.CenterVertically) {

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search for a location") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(onClick = onSearchSubmit) {
                    Text("Go")
                }
            }

            if (hasSearched) {

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 4.dp
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        when {
                            searchFailed -> Text(
                                text = "Search isn't available right now. Move the map to your house instead.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            searchResults.isEmpty() -> Text(
                                text = "No matches for that search",
                                style = MaterialTheme.typography.bodySmall
                            )
                            else -> searchResults.forEach { result ->
                                TextButton(
                                    onClick = { onResultSelected(result) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = result.name,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Start
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onLocateMeClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(bottom = 120.dp)
        ) {
            Text("🧭")
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                Text(
                    text = pickedAddress ?: String.format(Locale.US, "%.6f, %.6f", center.latitude, center.longitude),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onConfirm(mapView.centerPoint()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Confirm this location")
                }
            }
        }
    }
}

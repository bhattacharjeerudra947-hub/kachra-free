package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.kachrafreeresident.R
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
 * center of the map, rather than a draggable marker. Search results and "use
 * my location" both just move the map - the picked point is always
 * whatever's under the pin once the map stops moving.
 *
 * Layout, top to bottom: the map (with the search box floating over its top
 * and the my-location button at its bottom corner), then the address panel.
 * The panel sits below the map, not on top of it, so nothing overlaps.
 */
@Composable
fun LocationPickerScreen(
    initialLatLng: GeoPoint,
    moveCameraTo: GeoPoint?,
    pickedAddress: String?,
    findingAddress: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    placeResults: List<ServerApi.PlaceResult>,
    searchMessage: String?,
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

    Column(modifier = Modifier.fillMaxSize()) {

        // ---- The map, with things floating over it ----
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                // Runs once, when the map is first shown.
                factory = {
                    mapView.controller.setZoom(DEFAULT_ZOOM)
                    mapView.controller.setCenter(initialLatLng)
                    // Fires once panning/zooming has stopped for 400 ms: that
                    // point under the pin is the picked location.
                    mapView.addMapListener(DelayedMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean = onStop()
                        override fun onZoom(event: ZoomEvent?): Boolean = onStop()
                        private fun onStop(): Boolean {
                            center = mapView.centerPoint()
                            settled(center)
                            return true
                        }
                    }, 400))
                    // Address for the starting position straight away (post:
                    // once the map has been laid out and knows its centre).
                    mapView.post { settled(mapView.centerPoint()) }
                    mapView
                }
            )

            // The pin, fixed at the center of the map. The icon's tip is near
            // its bottom edge, so it's moved up until the tip is on the center.
            Icon(
                painter = painterResource(R.drawable.ic_pin),
                contentDescription = "Picked location",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .offset(y = (-20).dp)
            )

            SearchBox(
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                onSearchSubmit = onSearchSubmit,
                placeResults = placeResults,
                searchMessage = searchMessage,
                onResultSelected = onResultSelected,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
            )

            OsmCredit(modifier = Modifier.align(Alignment.BottomStart))

            FloatingActionButton(
                onClick = onLocateMeClick,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_my_location),
                    contentDescription = "Use my current location"
                )
            }
        }

        // ---- The address panel, below the map ----
        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 4.dp) {
            Column(modifier = Modifier.navigationBarsPadding().padding(16.dp)) {
                val addressText = if (findingAddress) {
                    "Finding the address..."
                } else {
                    if (pickedAddress != null) pickedAddress else "No street address found for this spot"
                }
                Text(text = addressText, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = String.format(Locale.US, "%.6f, %.6f", center.latitude, center.longitude),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

/** The search box, with suggestions or search results in a list under it. */
@Composable
private fun SearchBox(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    placeResults: List<ServerApi.PlaceResult>,
    searchMessage: String?,
    onResultSelected: (ServerApi.PlaceResult) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {

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

        if (placeResults.isEmpty() && searchMessage == null) return@Column

        Spacer(modifier = Modifier.height(8.dp))

        Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 4.dp, shadowElevation = 4.dp) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (searchMessage != null) {
                    Text(text = searchMessage, style = MaterialTheme.typography.bodySmall)
                }
                for (result in placeResults) {
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

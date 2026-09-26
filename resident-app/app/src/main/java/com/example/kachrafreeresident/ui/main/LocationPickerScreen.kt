package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kachrafreeresident.PlacesApi
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import java.util.Locale

private const val DEFAULT_ZOOM = 16f

/**
 * Full-screen "drop a pin" location picker, the same pattern most delivery
 * apps use: the map pans freely underneath a pin that's fixed at the exact
 * screen center, rather than a draggable marker. Search results and "use my
 * location" both just move the camera - the picked point is always
 * whatever's under the pin once the map stops moving.
 */
@Composable
fun LocationPickerScreen(
    initialLatLng: LatLng,
    moveCameraTo: LatLng?,
    pickedAddress: String?,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    hasSearched: Boolean,
    searchFailed: Boolean,
    searchResults: List<PlacesApi.PlaceResult>,
    onResultSelected: (PlacesApi.PlaceResult) -> Unit,
    onCameraSettled: (LatLng) -> Unit,
    onLocateMeClick: () -> Unit,
    onConfirm: (LatLng) -> Unit
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialLatLng, DEFAULT_ZOOM)
    }

    var mapLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(moveCameraTo, mapLoaded) {
        if (mapLoaded) {
            moveCameraTo?.let { target ->
                cameraPositionState.animate(
                    update = CameraUpdateFactory.newLatLngZoom(target, DEFAULT_ZOOM),
                    durationMs = 600
                )
            }
        }
    }

    LaunchedEffect(cameraPositionState.isMoving) {
        if (mapLoaded && !cameraPositionState.isMoving) {
            onCameraSettled(cameraPositionState.position.target)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            onMapLoaded = { mapLoaded = true }
        )

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
                                text = "Search isn't available yet - the server is offline",
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
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
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
                    text = pickedAddress ?: String.format(
                        Locale.US,
                        "%.6f, %.6f",
                        cameraPositionState.position.target.latitude,
                        cameraPositionState.position.target.longitude
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onConfirm(cameraPositionState.position.target) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Confirm this location")
                }
            }
        }
    }
}

package com.example.kachrafreedriver.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    truckId: String,
    onTruckIdChange: (String) -> Unit,
    tracking: Boolean,
    locationText: String?,
    serverText: String?,
    stopNames: List<String>,
    pendingStopSecondsLeft: Int?,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
    onAddStop: () -> Unit,
    onUndoStop: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Text("Kachra Free Driver", style = MaterialTheme.typography.headlineMedium)

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = truckId,
                onValueChange = onTruckIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Truck ID") },
                singleLine = true,
                enabled = !tracking,
                // A visible fill: the plain outlined box can look invisible.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )

            Spacer(Modifier.height(24.dp))

            Text(
                if (tracking) "Location sharing: on" else "Location sharing: off",
                style = MaterialTheme.typography.bodyLarge
            )
            if (locationText != null) {
                Text(locationText, style = MaterialTheme.typography.bodyMedium)
            }
            if (serverText != null) {
                Text(serverText, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = if (tracking) onStopTracking else onStartTracking,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (tracking) "Stop sharing location" else "Start sharing location")
            }

            if (tracking) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text("Collection stops", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Park at a spot where residents bring their garbage, then press Add stop.",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(8.dp))

                if (pendingStopSecondsLeft == null) {
                    OutlinedButton(onClick = onAddStop, modifier = Modifier.fillMaxWidth()) {
                        Text("Add stop here")
                    }
                } else {
                    // The 20-second window to undo an accidental press.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Adding stop in ${pendingStopSecondsLeft}s...",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onUndoStop) { Text("Undo") }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (stopNames.isEmpty()) {
                    Text("No stops yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    stopNames.forEachIndexed { i, name ->
                        Text("${i + 1}. $name", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

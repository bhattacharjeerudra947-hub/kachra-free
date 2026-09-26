package com.example.kachrafreedriver.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    truckId: String,
    onTruckIdChange: (String) -> Unit,
    tracking: Boolean,
    latitude: Double?,
    longitude: Double?,
    secondsSinceUpdate: Long?,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "Kachra Free Driver",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(
            modifier = Modifier.height(32.dp)
        )

        OutlinedTextField(
            value = truckId,
            onValueChange = onTruckIdChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Truck ID")
            },
            singleLine = true,
            enabled = !tracking,
            // Give the box a visible fill - the plain outlined style can
            // look almost invisible against some screen backgrounds.
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        Text(
            text = if (tracking) {
                "Tracking: Active"
            } else {
                "Tracking: Inactive"
            },
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        if (tracking) {

            Text(
                text = if (latitude != null && longitude != null) {
                    "Location: %.6f, %.6f".format(latitude, longitude)
                } else {
                    "Location: waiting for GPS fix..."
                },
                style = MaterialTheme.typography.bodyMedium
            )

            if (secondsSinceUpdate != null) {
                Text(
                    text = "Last update: ${secondsSinceUpdate}s ago",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(
                modifier = Modifier.height(16.dp)
            )
        }

        Button(
            onClick = {
                if (tracking) {
                    onStopTracking()
                } else {
                    onStartTracking()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (tracking) {
                    "Stop Tracking"
                } else {
                    "Start Tracking"
                }
            )
        }
    }
}

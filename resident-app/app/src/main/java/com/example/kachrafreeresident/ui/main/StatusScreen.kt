package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kachrafreeresident.ResidentApi

@Composable
fun StatusScreen(
    phoneNumber: String,
    latitudeText: String,
    longitudeText: String,
    alertMinutes: Int,
    truckStatus: ResidentApi.TruckStatus?,
    onEdit: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {

        Text(
            text = "Kachra Free Resident",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "You're registered",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text("Phone: $phoneNumber")
        Text("House location: $latitudeText, $longitudeText")
        Text("Alert me when truck is: $alertMinutes minutes away")

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Truck status",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (truckStatus == null) {
            Text("Waiting for the server...")
        } else {

            Text("Truck: ${truckStatus.truckId ?: "unknown"}")

            Text(
                text = if (truckStatus.etaMinutes != null) {
                    "ETA: ${truckStatus.etaMinutes} minutes"
                } else {
                    "ETA: not available yet"
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Edit registration")
        }
    }
}

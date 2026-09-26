package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kachrafreeresident.ResidentApi

@Composable
fun StatusScreen(
    phoneNumber: String,
    houseLocationLabel: String,
    alertMinutes: Int,
    truckStatus: ResidentApi.TruckStatus?,
    onEdit: () -> Unit,
    onViewMap: () -> Unit
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
        Text("House location: $houseLocationLabel")
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

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onViewMap,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("View on map")
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

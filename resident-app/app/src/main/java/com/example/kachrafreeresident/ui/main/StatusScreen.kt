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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.kachrafreeresident.ServerApi

@Composable
fun StatusScreen(
    phoneNumber: String,
    truckId: String,
    houseLocationLabel: String,
    alertMinutes: Int,
    truckStatus: ServerApi.TruckStatus?,
    onEdit: () -> Unit,
    onViewMap: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {

        Text("Kachra Free Resident", style = MaterialTheme.typography.headlineMedium)

        Spacer(Modifier.height(24.dp))

        Text("Truck status", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        val alert = truckStatus?.alert
        if (alert != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(alert.message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = when {
                truckStatus == null -> "Can't reach the server right now. Retrying..."
                truckStatus.notRegistered -> "Sending your registration to the server..."
                else -> truckStatus.message ?: ""
            },
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(16.dp))

        OutlinedButton(onClick = onViewMap, modifier = Modifier.fillMaxWidth()) {
            Text("View on map")
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text("Your registration", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Phone: $phoneNumber")
        Text("Truck: $truckId")
        Text("House: $houseLocationLabel")
        Text("Alert me when the truck is $alertMinutes minutes away")

        Spacer(Modifier.height(16.dp))

        Button(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
            Text("Edit registration")
        }
    }
}

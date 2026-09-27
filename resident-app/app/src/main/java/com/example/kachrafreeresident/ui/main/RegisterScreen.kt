package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.kachrafreeresident.R

// The alert range choices from AGENTS.md section 3.
private val ALERT_OPTIONS_MINUTES = listOf(5, 10, 15, 30)

/**
 * The registration form. Shown once at the start, and later as the
 * Settings page (isEditing = true) behind the gear button on the map.
 */
@Composable
fun RegisterScreen(
    serverReachable: Boolean?,
    username: String,
    onUsernameChange: (String) -> Unit,
    truckId: String,
    onTruckIdChange: (String) -> Unit,
    houseLocationLabel: String,
    onPickLocation: () -> Unit,
    alertMinutes: Int,
    onAlertMinutesChange: (Int) -> Unit,
    isEditing: Boolean,
    onCancel: () -> Unit,
    onSubmit: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {

            // ---- Header ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isEditing) "Settings" else "Kachra Free",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isEditing) "Your registration" else "Know when your garbage truck is coming",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ServerIndicator(serverReachable)
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- Username ----
            SectionCard(title = "You") {
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    supportingText = {
                        if (isEditing) {
                            Text("Your username can't be changed.")
                        } else {
                            Text("Pick one and remember it. Registering again with the same username, on this or another phone, signs you back in.")
                        }
                    },
                    // Changing it would make a different resident.
                    enabled = !isEditing,
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }

            // ---- Truck ----
            SectionCard(title = "Your garbage truck") {
                OutlinedTextField(
                    value = truckId,
                    onValueChange = onTruckIdChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Truck ID") },
                    supportingText = { Text("The ID of the truck that serves your area, e.g. from your municipality.") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }

            // ---- House ----
            SectionCard(title = "Your home") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_pin),
                        contentDescription = null,
                        tint = Color(HOME_COLOR),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(houseLocationLabel, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = onPickLocation, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose on map")
                }
            }

            // ---- Alert ----
            SectionCard(title = "Alert me when the truck is about") {
                Row {
                    for (minutes in ALERT_OPTIONS_MINUTES) {
                        FilterChip(
                            selected = alertMinutes == minutes,
                            onClick = { onAlertMinutesChange(minutes) },
                            label = { Text("$minutes min") },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
                Text(
                    "away from your collection point.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- Buttons ----
            if (isEditing) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = onSubmit, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("Save")
                    }
                }
            } else {
                Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Register")
                }
            }
        }
    }
}

/** A white rounded card with a small title, holding one part of the form. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

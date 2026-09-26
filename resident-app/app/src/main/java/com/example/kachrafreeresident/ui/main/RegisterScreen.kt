package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// The alert range choices from AGENTS.md section 3.
private val ALERT_OPTIONS_MINUTES = listOf(5, 10, 15, 30)

@Composable
fun RegisterScreen(
    phoneNumber: String,
    onPhoneNumberChange: (String) -> Unit,
    houseLocationLabel: String,
    onPickLocation: () -> Unit,
    alertMinutes: Int,
    onAlertMinutesChange: (Int) -> Unit,
    isEditing: Boolean,
    onCancel: () -> Unit,
    onSubmit: () -> Unit
) {

    // Give every box a visible fill - the plain outlined style can look
    // almost invisible against some screen backgrounds.
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {

        Text(
            text = "Kachra Free Resident",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isEditing) {
                "Update your registration"
            } else {
                "Register to get garbage truck alerts"
            },
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = phoneNumber,
            onValueChange = onPhoneNumberChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Phone number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            colors = fieldColors
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "House location",
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = houseLocationLabel,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onPickLocation,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Choose on map")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Alert me when the truck is approximately:",
            style = MaterialTheme.typography.titleMedium
        )

        ALERT_OPTIONS_MINUTES.forEach { minutes ->

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = alertMinutes == minutes,
                        onClick = { onAlertMinutesChange(minutes) }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = alertMinutes == minutes,
                    onClick = { onAlertMinutesChange(minutes) }
                )
                Text("$minutes minutes away")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isEditing) {

            Row(modifier = Modifier.fillMaxWidth()) {

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = onSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }

        } else {

            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Register")
            }
        }
    }
}

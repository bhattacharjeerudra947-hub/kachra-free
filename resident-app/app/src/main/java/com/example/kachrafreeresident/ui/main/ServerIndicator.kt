package com.example.kachrafreeresident.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A small pill: a coloured dot + "Online" / "Offline". Can the app reach
 * the server right now? reachable is null until the first check finishes.
 */
@Composable
fun ServerIndicator(reachable: Boolean?, modifier: Modifier = Modifier) {
    val color: Color
    val text: String
    if (reachable == null) {
        color = Color(0xFFADB5BD) // grey
        text = "Connecting…"
    } else if (reachable) {
        color = Color(0xFF2F9E44) // green
        text = "Server online"
    } else {
        color = Color(0xFFE03131) // red
        text = "Server offline"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

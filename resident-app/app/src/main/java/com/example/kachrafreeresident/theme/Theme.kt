package com.example.kachrafreeresident.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed colors rather than Android 12's wallpaper-based "dynamic color",
// which can come out washed-out enough to make text fields hard to see.
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6F3E),
    secondary = Color(0xFF52634F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF94D6A0),
    secondary = Color(0xFFB9CCB4),
)

@Composable
fun KachraFreeResidentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

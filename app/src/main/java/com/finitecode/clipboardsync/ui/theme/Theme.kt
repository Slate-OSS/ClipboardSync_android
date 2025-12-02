package com.finitecode.clipboardsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF32B8C6),
    secondary = Color(0xFF80CBC4),
    tertiary = Color(0xFF80DEEA)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF208091),
    secondary = Color(0xFF26747A),
    tertiary = Color(0xFF006064)
)

@Composable
fun ClipboardSyncTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

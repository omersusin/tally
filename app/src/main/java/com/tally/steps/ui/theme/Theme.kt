package com.tally.steps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.tally.steps.data.PrefsStore

private val LightBackground = Color(0xFFFFF8F1)
private val LightSurface = Color(0xFFFFFFFF)
private val LightText = Color(0xFF1C1410)
private val LightMuted = Color(0xFF6F5D4F)
private val LightAccent = Color(0xFFB3400A)

private val DarkBackground = Color(0xFF140E0A)
private val DarkSurface = Color(0xFF201613)
private val DarkText = Color(0xFFFFF3E8)
private val DarkMuted = Color(0xFFC8B3A3)
private val DarkAccent = Color(0xFFFF8A3D)

// AMOLED tokens: pure-black bg (pixels off), near-black warm surface.
private val AmoledBackground = Color(0xFF000000)
private val AmoledSurface = Color(0xFF0A0605)
private val AmoledText = Color(0xFFFFF3E8)
private val AmoledMuted = Color(0xFFC8B3A3)
private val AmoledAccent = Color(0xFFFF8A3D)

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightBackground,
    onSurfaceVariant = LightMuted,
    outline = LightMuted,
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = Color.Black,
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = DarkBackground,
    onSurfaceVariant = DarkMuted,
    outline = DarkMuted,
)

private val AmoledColors = darkColorScheme(
    primary = AmoledAccent,
    onPrimary = Color.Black,
    background = AmoledBackground,
    onBackground = AmoledText,
    surface = AmoledSurface,
    onSurface = AmoledText,
    surfaceVariant = AmoledBackground,
    onSurfaceVariant = AmoledMuted,
    outline = AmoledMuted,
)

@Composable
fun TallyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    theme: String? = null,
    content: @Composable () -> Unit,
) {
    // When the caller doesn't pass an explicit theme, follow the stored
    // preference so Settings → Black (AMOLED) applies without rewiring
    // every call site. Explicit arg wins (previews/tests).
    val context = LocalContext.current
    val prefs = remember(context) { PrefsStore.getInstance(context) }
    val stored by prefs.theme.collectAsState(initial = theme ?: "system")
    val effective = theme ?: stored
    MaterialTheme(
        colorScheme = when (effective) {
            "amoled" -> AmoledColors
            "light" -> LightColors
            "dark" -> DarkColors
            else -> if (darkTheme) DarkColors else LightColors
        },
        content = content,
    )
}

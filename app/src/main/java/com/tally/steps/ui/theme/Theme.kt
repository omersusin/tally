package com.tally.steps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tally.steps.data.PrefsStore

private val LightBackground = Color(0xFFFFF8F1)
private val LightSurface = Color(0xFFFFFFFF)
private val LightText = Color(0xFF1C1410)
private val LightMuted = Color(0xFF6F5D4F)
private val LightAccent = Color(0xFFB3400A)
/** Tonal fill distinct from bg: ring track, muted bars, locked badges read as inset. */
private val LightSurfaceVariant = Color(0xFFF3E7D8)

private val DarkBackground = Color(0xFF140E0A)
private val DarkSurface = Color(0xFF201613)
private val DarkText = Color(0xFFFFF3E8)
private val DarkMuted = Color(0xFFC8B3A3)
private val DarkAccent = Color(0xFFFF8A3D)
/** Lifted above bg so tonal elements survive dark mode. */
private val DarkSurfaceVariant = Color(0xFF2C1E16)

// AMOLED tokens: pure-black bg (pixels off), near-black warm surface.
private val AmoledBackground = Color(0xFF000000)
private val AmoledSurface = Color(0xFF0A0605)
private val AmoledText = Color(0xFFFFF3E8)
private val AmoledMuted = Color(0xFFC8B3A3)
private val AmoledAccent = Color(0xFFFF8A3D)
/** Faint warm lift: visible on black without lighting pixels much. */
private val AmoledSurfaceVariant = Color(0xFF170D08)

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightSurfaceVariant,
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
    surfaceVariant = DarkSurfaceVariant,
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
    surfaceVariant = AmoledSurfaceVariant,
    onSurfaceVariant = AmoledMuted,
    outline = AmoledMuted,
)

/**
 * Display + headline character, system fonts only. The hero count gets
 * ExtraBold weight with tight (not touching) tracking; headlines get
 * SemiBold with a whisper of tightening. Body styles stay at M3 defaults
 * so long-form copy keeps its readable rhythm.
 */
private val TallyTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.ExtraBold,
        fontSize = 72.sp,
        lineHeight = 76.sp,
        letterSpacing = (-1.5).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.6.sp,
    ),
)

/**
 * 16dp card radius everywhere it matters: M3 Card defaults to [Shapes.medium],
 * so every plain Card() in the app picks up the elevated-card corner.
 * Bottom sheets keep a larger 24dp top radius.
 */
private val TallyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Card lift: a real shadow with offset+blur in light, a warm tonal lighten
 * in dark/AMOLED (via the default surface tint). Layered surfaces, never
 * flat black-on-black — without touching a single color token.
 */
object TallyElevation {
    val Card: Dp = 1.dp
    val CardRaised: Dp = 2.dp
}

/**
 * Tabular figures for any count text, so multi-digit numbers never jitter
 * as they change. Pair with any numeric Text style:
 * `MaterialTheme.typography.titleMedium.tabulated()`.
 */
fun TextStyle.tabulated(): TextStyle = copy(fontFeatureSettings = "tnum")

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
        typography = TallyTypography,
        shapes = TallyShapes,
        content = content,
    )
}

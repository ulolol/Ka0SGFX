package dev.kaos.kaosgfx.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0D0F15)
val SurfaceDark = Color(0xFF161922)
val SurfaceVariantDark = Color(0xFF1E222E)
val PrimaryNeon = Color(0xFF00E5FF)
val PrimaryBlue = Color(0xFF4E80EE)
val AccentPurple = Color(0xFF7C4DFF)
val SuccessGreen = Color(0xFF00E676)
val WarningOrange = Color(0xFFFFAB00)
val ErrorRed = Color(0xFFFF5252)
val TextPrimary = Color(0xFFF0F2F8)
val TextSecondary = Color(0xFF8A93A6)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryNeon,
    onPrimary = Color.Black,
    primaryContainer = PrimaryBlue,
    onPrimaryContainer = Color.White,
    secondary = PrimaryBlue,
    onSecondary = Color.White,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color.Black
)

@Composable
fun KaosGFXTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}

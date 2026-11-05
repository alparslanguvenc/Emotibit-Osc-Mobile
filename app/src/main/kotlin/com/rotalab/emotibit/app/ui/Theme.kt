package com.rotalab.emotibit.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF00677F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EAFF),
    onPrimaryContainer = Color(0xFF001F28),
    secondary = Color(0xFF4B6269),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE7EF),
    onSecondaryContainer = Color(0xFF061F25),
    tertiary = Color(0xFF5A5C7E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E0FF),
    onTertiaryContainer = Color(0xFF171A37),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF5FAFC),
    onBackground = Color(0xFF171C1E),
    surface = Color(0xFFF5FAFC),
    onSurface = Color(0xFF171C1E),
    surfaceVariant = Color(0xFFDBE4E8),
    onSurfaceVariant = Color(0xFF40484C),
    outline = Color(0xFF70787C),
    outlineVariant = Color(0xFFBFC8CC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF4F7),
    surfaceContainer = Color(0xFFE9EFF1),
    surfaceContainerHigh = Color(0xFFE3E9EB),
    surfaceContainerHighest = Color(0xFFDEE3E6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5DD5FC),
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF004D61),
    onPrimaryContainer = Color(0xFFB8EAFF),
    secondary = Color(0xFFB2CBD3),
    onSecondary = Color(0xFF1D343A),
    secondaryContainer = Color(0xFF344A51),
    onSecondaryContainer = Color(0xFFCEE7EF),
    tertiary = Color(0xFFC3C3EB),
    onTertiary = Color(0xFF2C2E4D),
    tertiaryContainer = Color(0xFF424465),
    onTertiaryContainer = Color(0xFFE0E0FF),
    background = Color(0xFF0F1416),
    onBackground = Color(0xFFDEE3E6),
    surface = Color(0xFF0F1416),
    onSurface = Color(0xFFDEE3E6),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFBFC8CC),
    outline = Color(0xFF8A9296),
    outlineVariant = Color(0xFF40484C),
    surfaceContainerLowest = Color(0xFF0A0F11),
    surfaceContainerLow = Color(0xFF171C1E),
    surfaceContainer = Color(0xFF1B2022),
    surfaceContainerHigh = Color(0xFF252B2D),
    surfaceContainerHighest = Color(0xFF303638),
)

/** True while rendering README screenshots: infinite animations are frozen so rendering terminates. */
val LocalStaticRendering = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Status colours that stay readable in both themes. */
object StatusColors {
    val record = Color(0xFFD93A3A)
    val ok = Color(0xFF1E9E7A)
    val warn = Color(0xFFE39B2A)
    val idle = Color(0xFF8A9296)

    val recordContainer: Color
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF4A1416) else Color(0xFFFFE3E0)
    val onRecordContainer: Color
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFFFFDAD6) else Color(0xFF410003)
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun RotaLabTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = AppShapes, content = content)
}

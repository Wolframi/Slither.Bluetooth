package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SlitherDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = OnNeonCyan,
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = TextPrimary,
    secondary = NeonLime,
    onSecondary = OnNeonCyan,
    secondaryContainer = DeepLimeContainer,
    onSecondaryContainer = TextPrimary,
    tertiary = MagentaPulse,
    onTertiary = TextPrimary,
    tertiaryContainer = DeepMagentaContainer,
    onTertiaryContainer = TextPrimary,
    background = AbyssBackground,
    onBackground = TextPrimary,
    surface = DeepSurface,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = ElevatedSurface,
    surfaceContainerHighest = HighestSurface,
    outline = OutlineSubtle,
    error = DangerCrimson,
    onError = TextPrimary
)

val SlitherShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SlitherBtTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SlitherDarkColorScheme,
        typography = SlitherTypography,
        shapes = SlitherShapes,
        content = content
    )
}

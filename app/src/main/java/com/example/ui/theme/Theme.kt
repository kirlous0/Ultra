package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val UltraCamDarkColorScheme = darkColorScheme(
    primary = AmberFocus,
    onPrimary = ObsidianVoid,
    primaryContainer = AmberDim,
    onPrimaryContainer = TextPrimary,
    secondary = SapphireTelemetry,
    onSecondary = ObsidianVoid,
    secondaryContainer = MachinedSlate,
    onSecondaryContainer = TextPrimary,
    tertiary = EmeraldLock,
    onTertiary = ObsidianVoid,
    background = ObsidianVoid,
    onBackground = TextPrimary,
    surface = CarbonSurface,
    onSurface = TextPrimary,
    surfaceVariant = MachinedSlate,
    onSurfaceVariant = TextSecondary,
    outline = GraphiteBorder,
    error = CrimsonRecord,
    onError = TextPrimary
)

val UltraCamShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun UltraCamTheme(
    isArabic: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = UltraCamDarkColorScheme,
        typography = buildUltraCamTypography(isArabic),
        shapes = UltraCamShapes,
        content = content
    )
}

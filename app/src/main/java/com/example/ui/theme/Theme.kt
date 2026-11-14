package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = ElectricMint,
    onPrimary = OnMintDark,
    primaryContainer = MintContainerDark,
    onPrimaryContainer = ElectricMint,
    secondary = SoftSage,
    onSecondary = OnMintDark,
    secondaryContainer = SageContainerDark,
    onSecondaryContainer = TextPrimaryDark,
    tertiary = AmberWarning,
    onTertiary = Color(0xFF281800),
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberWarning,
    background = ObsidianCanvas,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLowest = ObsidianCanvas,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceElevated,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    outlineVariant = Color(0xFF1B3029),
    error = CoralPulse,
    onError = Color.White,
    errorContainer = CoralContainerDark,
    onErrorContainer = CoralPulse,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = ForestPrimary,
    onPrimary = Color.White,
    primaryContainer = MintContainerLight,
    onPrimaryContainer = OnForestContainer,
    secondary = ElectricMintDark,
    onSecondary = Color.White,
    secondaryContainer = AlabasterSurfaceElevated,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = AlabasterCanvas,
    onBackground = TextPrimaryLight,
    surface = AlabasterSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = AlabasterSurfaceElevated,
    onSurfaceVariant = TextSecondaryLight,
    outline = AlabasterOutline,
    outlineVariant = Color(0xFFD9EAE3),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
  )

val AppShapes =
  Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
  )

@Composable
fun PingAssistTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    shapes = AppShapes,
    content = content,
  )
}

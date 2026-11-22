package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val GemBridgeDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = OnElectricCyanContainer,
    secondary = CobaltViolet,
    onSecondary = OnCobaltViolet,
    secondaryContainer = CobaltVioletContainer,
    onSecondaryContainer = OnCobaltVioletContainer,
    tertiary = EmeraldAccent,
    onTertiary = OnElectricCyan,
    tertiaryContainer = EmeraldContainer,
    background = ObsidianBase,
    onBackground = Color(0xFFF1F5F9),
    surface = ObsidianSurface,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerHigh = ObsidianSurfaceHigh,
    surfaceContainerHighest = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    error = ErrorCoral,
    onError = Color(0xFF3F0B10),
    errorContainer = ErrorContainerDark,
    onErrorContainer = Color(0xFFFECACA),
)

private val GemBridgeLightColorScheme = lightColorScheme(
    primary = ElectricCyanDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    secondary = CobaltVioletDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF312E81),
    tertiary = EmeraldAccentDark,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    background = MistLightBase,
    onBackground = InkDark,
    surface = MistLightSurface,
    onSurface = InkDark,
    surfaceVariant = MistLightSurfaceHigh,
    onSurfaceVariant = InkMuted,
    surfaceContainerHigh = MistLightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = MistLightOutline,
    error = ErrorCoralDark,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = Color(0xFF7F1D1D),
)

@Composable
fun GemBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> GemBridgeDarkColorScheme
        else -> GemBridgeLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GemBridgeTypography,
        content = content,
    )
}

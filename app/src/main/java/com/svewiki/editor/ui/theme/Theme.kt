package com.svewiki.editor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 亮色主题：清新自然
private val LightColors = lightColorScheme(
    primary = ForestGreen,
    onPrimary = Color.White,
    primaryContainer = ForestGreenLight,
    onPrimaryContainer = ForestGreenDark,
    secondary = EarthBrown,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5EDE9),
    onSecondaryContainer = Color(0xFF4E342E),
    tertiary = SproutBlue,
    onTertiary = Color.White,
    background = CloudWhite,
    onBackground = Charcoal,
    surface = Color.White,
    onSurface = Charcoal,
    surfaceVariant = Color(0xFFF0F3F0),
    onSurfaceVariant = SlateGrey,
    outline = MistGrey,
    error = BerryRed,
    onError = Color.White,
)

// 暗色主题：深夜森林
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FC48E),
    onPrimary = NightForestDark,
    primaryContainer = ForestGreenDark,
    onPrimaryContainer = Color(0xFFC8E8CE),
    secondary = Color(0xFFBCA18F),
    onSecondary = Color(0xFF3E2E26),
    tertiary = Color(0xFF8FB8D8),
    onTertiary = Color(0xFF1E3A52),
    background = NightForest,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightMuted,
    outline = Color(0xFF4A5A4F),
    error = Color(0xFFE08777),
    onError = NightForestDark,
)

@Composable
fun SveWikiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
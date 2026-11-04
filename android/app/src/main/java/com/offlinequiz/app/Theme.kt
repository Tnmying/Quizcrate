package com.offlinequiz.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFFA64B16),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEBD8),
    onPrimaryContainer = Color(0xFF482A19),
    secondary = Color(0xFF42664D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2F0E3),
    onSecondaryContainer = Color(0xFF203C29),
    tertiary = Color(0xFF795B35),
    tertiaryContainer = Color(0xFFFFEDCC),
    onTertiaryContainer = Color(0xFF493416),
    background = Color(0xFFFFF9F2),
    onBackground = Color(0xFF302A26),
    surface = Color(0xFFFFF9F2),
    onSurface = Color(0xFF302A26),
    surfaceVariant = Color(0xFFF5EDE3),
    onSurfaceVariant = Color(0xFF6F6359),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFE8DED3),
    surfaceBright = Color(0xFFFFF9F2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFFFF5EB),
    surfaceContainerHigh = Color(0xFFF7EEE4),
    surfaceContainerHighest = Color(0xFFF1E5D9),
    outline = Color(0xFF8D7D70),
    outlineVariant = Color(0xFFE4D7CA),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF362F29),
    inverseOnSurface = Color(0xFFFFF4EA),
    inversePrimary = Color(0xFFFFB77D)
)

@Composable
fun OfflineQuizTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp)
        ),
        content = content
    )
}

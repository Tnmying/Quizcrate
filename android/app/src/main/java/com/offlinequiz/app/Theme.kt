package com.offlinequiz.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal enum class ThemeMode(val label: String) {
    LIGHT("白天"),
    DARK("黑夜"),
    SYSTEM("随系统");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    companion object {
        fun fromPreference(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

internal val LocalDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFF155BC1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBFF),
    onPrimaryContainer = Color(0xFF102C57),
    secondary = Color(0xFF42664D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2F0E3),
    onSecondaryContainer = Color(0xFF203C29),
    tertiary = Color(0xFF415E91),
    tertiaryContainer = Color(0xFFDCE7FF),
    onTertiaryContainer = Color(0xFF172D58),
    background = Color(0xFFF2F7FF),
    onBackground = Color(0xFF142745),
    surface = Color(0xFFF2F7FF),
    onSurface = Color(0xFF142745),
    surfaceVariant = Color(0xFFE4EDFA),
    onSurfaceVariant = Color(0xFF455C7B),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFD7E4F5),
    surfaceBright = Color(0xFFF6FAFF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEDF4FF),
    surfaceContainerHigh = Color(0xFFE5EFFD),
    surfaceContainerHighest = Color(0xFFDDE9FB),
    outline = Color(0xFF657B9A),
    outlineVariant = Color(0xFFC8D9EF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF20344F),
    inverseOnSurface = Color(0xFFEDF5FF),
    inversePrimary = Color(0xFFA9CCFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9CCFF),
    onPrimary = Color(0xFF0A315F),
    primaryContainer = Color(0xFF1D426D),
    onPrimaryContainer = Color(0xFFDDEBFF),
    secondary = Color(0xFFA8D8B3),
    onSecondary = Color(0xFF163821),
    secondaryContainer = Color(0xFF274B32),
    onSecondaryContainer = Color(0xFFC4ECCC),
    tertiary = Color(0xFFB8CAFA),
    onTertiary = Color(0xFF213558),
    tertiaryContainer = Color(0xFF35496F),
    onTertiaryContainer = Color(0xFFDCE7FF),
    background = Color(0xFF101927),
    onBackground = Color(0xFFE6EEFC),
    surface = Color(0xFF101927),
    onSurface = Color(0xFFE6EEFC),
    surfaceVariant = Color(0xFF26364C),
    onSurfaceVariant = Color(0xFFBBCBE1),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF101927),
    surfaceBright = Color(0xFF334258),
    surfaceContainerLowest = Color(0xFF0C1420),
    surfaceContainerLow = Color(0xFF152132),
    surfaceContainer = Color(0xFF1A273A),
    surfaceContainerHigh = Color(0xFF223147),
    surfaceContainerHighest = Color(0xFF2C3C53),
    outline = Color(0xFF8EA1BE),
    outlineVariant = Color(0xFF42536B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF842024),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE6EEFC),
    inverseOnSurface = Color(0xFF233247),
    inversePrimary = Color(0xFF155BC1)
)

@Composable
fun OfflineQuizTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp)
        )
    ) {
        CompositionLocalProvider(LocalDarkTheme provides dark, LocalContentColor provides colors.onSurface, content = content)
    }
}

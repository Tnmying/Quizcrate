package com.offlinequiz.app.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.offlinequiz.app.LocalDarkTheme

/** Blue glass primary action with a high-contrast label. */
@Composable
fun WarmButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val blueGlass = when {
        !enabled -> null
        LocalDarkTheme.current -> Brush.verticalGradient(listOf(Color(0xFF315D8C), Color(0xFF204369)))
        else -> Brush.verticalGradient(listOf(Color(0xE6AED4FA), Color(0xE68EB9EF), Color(0xE677A7E3)))
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp)
            .glassSurface(24.dp, tint = colors.primary.copy(alpha = .06f), fill = blueGlass),
        enabled = enabled,
        shape = RoundedCornerShape(24.dp),
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = colors.onPrimaryContainer
        ),
        content = content
    )
}

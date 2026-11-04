package com.offlinequiz.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** One active destination only: outgoing BackHandlers and effects are disposed immediately. */
@Composable
fun DestinationTransition(destinationKey: String, style: PageMotionStyle, content: @Composable () -> Unit) {
    val progress = remember(destinationKey) { Animatable(if (style == PageMotionStyle.NONE) 0f else 1f) }
    LaunchedEffect(destinationKey) {
        progress.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
            val width = constraints.maxWidth.toFloat()
            Box(Modifier.fillMaxSize().graphicsLayer {
                val remaining = progress.value
                translationX = when (style) {
                    PageMotionStyle.FORWARD -> width * remaining
                    PageMotionStyle.BACK -> -width * 0.25f * remaining
                    else -> 0f
                }
                translationY = if (style == PageMotionStyle.CARD) -24.dp.toPx() * remaining else 0f
                alpha = if (style == PageMotionStyle.CARD) 1f - remaining else 1f
            }) { content() }
        }
    }
}

/** Short, downward reveal for a newly opened card/dialog, following system duration scale. */
@Composable
fun rememberRevealModifier(contentKey: Any): Modifier {
    val progress = remember(contentKey) { Animatable(0f) }
    LaunchedEffect(contentKey) { progress.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
    return Modifier.graphicsLayer {
        alpha = progress.value
        translationY = -12.dp.toPx() * (1f - progress.value)
    }
}

@Composable
fun MotionAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.then(rememberRevealModifier(Unit)),
        dismissButton = dismissButton,
        title = title,
        text = text
    )
}

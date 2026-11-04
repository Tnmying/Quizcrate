package com.offlinequiz.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Left swipe reveals right-hand actions. Swiping never deletes a library. */
@Composable
internal fun SwipeLibraryCard(
    revealed: Boolean,
    onReveal: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
        val actionsWidth = minOf(180.dp, maxWidth * 0.6f)
        val widthPx = with(LocalDensity.current) { actionsWidth.toPx() }
        var dragging by remember { mutableStateOf(false) }
        var drag by remember { mutableStateOf(0f) }
        val animated by animateFloatAsState(
            if (dragging) drag else if (revealed) -widthPx else 0f,
            if (dragging) snap() else tween(160), label = "librarySwipe")
        val position by rememberUpdatedState(animated)
        val reveal by rememberUpdatedState(onReveal)
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            Row(Modifier.width(actionsWidth).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF6B575))
                    .clickable(role = Role.Button, onClick = onDetails), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(LibraryInfoIcon, contentDescription = null, modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("详细信息", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFB83D3D))
                    .clickable(role = Role.Button, onClick = onDelete), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(LibraryDeleteIcon, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color.White)
                        Text("删除", color = Color.White)
                    }
                }
            }
        }
        content(Modifier.offset { IntOffset(animated.roundToInt(), 0) }
            .semantics {
                customActions = listOf(CustomAccessibilityAction("显示详细信息和删除") { reveal(true); true })
            }
            .pointerInput(widthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = position; dragging = true },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        drag = (drag + amount).coerceIn(-widthPx, 0f)
                    },
                    onDragEnd = { reveal(drag < -widthPx / 3); dragging = false },
                    onDragCancel = { dragging = false }
                )
            })
    }
}

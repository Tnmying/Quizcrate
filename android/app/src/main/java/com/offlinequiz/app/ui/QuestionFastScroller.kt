package com.offlinequiz.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Schedule the latest target for the next measure, without queued scroll jobs. */
@Composable
internal fun rememberFastScrollSeek(list: LazyListState): (Int) -> Unit {
    return remember(list) { { index -> list.requestScrollToItem(index) } }
}

/** Dedicated 48dp touch lane inset from system edge. Seek continuously while dragging. */
@Composable
internal fun QuestionFastScroller(count: Int, currentIndex: Int, onSeek: (Int) -> Unit) {
    val density = LocalDensity.current
    val seek by rememberUpdatedState(onSeek)
    val current by rememberUpdatedState(currentIndex)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.width(48.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val heightPx = with(density) { maxHeight.toPx() }
        val thumbHeight = minOf(48.dp, maxHeight)
        val thumbPx = with(density) { thumbHeight.toPx() }
        val travel = (heightPx - thumbPx).coerceAtLeast(1f)
        val fraction = dragFraction ?: (currentIndex.toFloat() / (count - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = "题目快速滚动"
                    stateDescription = "${currentIndex + 1} / $count"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { value -> seek(QuestionListPosition.dragIndex(value, count)); true }
                }
                .pointerInput(count, travel) {
                    var thumbTop = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            val oldTop = current.toFloat() / (count - 1).coerceAtLeast(1) * travel
                            // Grabbing within thumb keeps its current position without jumping.
                            thumbTop = if (offset.y in oldTop..(oldTop + thumbPx)) oldTop
                                else (offset.y - thumbPx / 2).coerceIn(0f, travel)
                            dragFraction = thumbTop / travel
                            seek(QuestionListPosition.dragIndex(thumbTop / travel, count))
                        },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            thumbTop = (thumbTop + delta).coerceIn(0f, travel)
                            dragFraction = thumbTop / travel
                            seek(QuestionListPosition.dragIndex(thumbTop / travel, count))
                        },
                        onDragEnd = {
                            dragFraction?.let { seek(QuestionListPosition.dragIndex(it, count)) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null }
                    )
                }
        ) {
            Box(Modifier.align(Alignment.Center).width(8.dp).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)))
            Box(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (fraction * travel).roundToInt()) }
                .width(18.dp).height(thumbHeight)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center) {
                Text("≡", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        if (dragFraction != null) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-52).dp).requiredWidth(112.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp
            ) {
                Text("${QuestionListPosition.dragIndex(fraction, count) + 1} / $count",
                    Modifier.padding(12.dp))
            }
        }
    }
}

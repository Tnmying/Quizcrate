package com.offlinequiz.app.ui

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Measure progress within each visible card, including the spacing between cards.
private fun LazyListState.scrollFraction(count: Int): Float {
    if (!canScrollBackward) return 0f
    if (!canScrollForward) return 1f
    val info = layoutInfo
    val first = info.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex } ?: return 0f
    val last = info.visibleItemsInfo.last()
    val firstStride = (first.size + info.mainAxisItemSpacing).coerceAtLeast(1)
    val start = first.index + (firstVisibleItemScrollOffset.toFloat() / firstStride).coerceIn(0f, 1f)
    val lastStride = (last.size + if (last.index == count - 1) info.afterContentPadding else info.mainAxisItemSpacing)
        .coerceAtLeast(1)
    val end = last.index + ((info.viewportEndOffset - last.offset).toFloat() / lastStride).coerceIn(0f, 1f)
    return (start / (start + (count - end).coerceAtLeast(0f)).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
}

private fun LazyListState.estimatedScrollDistance(count: Int): Float {
    val info = layoutInfo
    val average = info.visibleItemsInfo.map { it.size }.average().takeIf { it.isFinite() } ?: 0.0
    return (average * count + info.mainAxisItemSpacing * (count - 1).coerceAtLeast(0) +
        info.beforeContentPadding + info.afterContentPadding -
        (info.viewportEndOffset - info.viewportStartOffset)).toFloat().coerceAtLeast(1f)
}

/** Dedicated 48dp touch lane inset from the system edge. */
@Composable
internal fun QuestionFastScroller(count: Int, list: LazyListState) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val progress by remember(list, count) { derivedStateOf { list.scrollFraction(count) } }
    val currentIndex = if (!list.canScrollForward && list.canScrollBackward) count - 1
        else list.firstVisibleItemIndex.coerceIn(0, count - 1)
    var dragging by remember(count) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.width(48.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val heightPx = with(density) { maxHeight.toPx() }
        val thumbHeight = minOf(48.dp, maxHeight)
        val thumbPx = with(density) { thumbHeight.toPx() }
        val travel = (heightPx - thumbPx).coerceAtLeast(1f)
        Box(
            Modifier.fillMaxSize()
                .semantics {
                    contentDescription = "题目快速滚动"
                    stateDescription = "${currentIndex + 1} / $count"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    setProgress { value ->
                        list.requestScrollToItem(QuestionListPosition.dragIndex(value, count))
                        true
                    }
                }
                .pointerInput(list, count, travel) {
                    var thumbTop = 0f
                    var pixelsPerThumbPixel = 1f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                list.stopScroll(MutatePriority.UserInput)
                            }
                            val oldTop = list.scrollFraction(count) * travel
                            thumbTop = if (offset.y in oldTop..(oldTop + thumbPx)) oldTop
                                else (offset.y - thumbPx / 2).coerceIn(0f, travel)
                            if (thumbTop != oldTop) {
                                list.requestScrollToItem(QuestionListPosition.dragIndex(thumbTop / travel, count))
                            }
                            pixelsPerThumbPixel = list.estimatedScrollDistance(count) / travel
                            dragging = true
                        },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            val nextTop = (thumbTop + delta).coerceIn(0f, travel)
                            // Scroll by the movement delta so cards can stop at any pixel.
                            list.dispatchRawDelta((nextTop - thumbTop) * pixelsPerThumbPixel)
                            thumbTop = nextTop
                        },
                        onDragEnd = {
                            if (thumbTop == 0f && list.canScrollBackward) list.requestScrollToItem(0)
                            if (thumbTop == travel && list.canScrollForward) list.requestScrollToItem(count - 1)
                            dragging = false
                        },
                        onDragCancel = { dragging = false }
                    )
                }
        ) {
            Box(Modifier.align(Alignment.Center).width(8.dp).fillMaxHeight()
                .glassSurface(6.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .10f)))
            Box(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (progress * travel).roundToInt()) }
                .width(18.dp).height(thumbHeight)
                .glassSurface(10.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .17f)),
                contentAlignment = Alignment.Center) {
                Text("≡", color = MaterialTheme.colorScheme.primary)
            }
        }
        if (dragging) {
            Box(
                Modifier.align(Alignment.CenterEnd).offset(x = (-52).dp)
                    .requiredWidth(112.dp).glassSurface(16.dp)
            ) {
                Text("${currentIndex + 1} / $count",
                    Modifier.padding(12.dp))
            }
        }
    }
}

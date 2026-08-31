package com.verisonder.sonderlock.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Long press a tile and drag across the grid to select a run of them.
 *
 * Tapping thirty photos one at a time is the thing people give up on, and it is the
 * gesture every gallery already uses, so it needs no explaining. Tapping still toggles a
 * single tile, which is what a short press means everywhere else.
 *
 * The whole gesture is one pointerInput on the grid rather than one per tile: a drag that
 * begins on one tile and continues over its neighbours never reaches those neighbours'
 * own handlers, because the pointer was captured by the first.
 */
fun Modifier.dragToSelect(
    state: LazyGridState,
    onStart: (Int) -> Unit,
    onOver: (Int) -> Unit,
    onFinish: () -> Unit,
): Modifier = pointerInput(state) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset -> state.indexAt(offset)?.let(onStart) },
        onDrag = { change, _ ->
            change.consume()
            state.indexAt(change.position)?.let(onOver)
        },
        onDragEnd = onFinish,
        onDragCancel = onFinish,
    )
}

/**
 * Which item is under a point, or null between tiles and past the end. Only visible items
 * are considered, which is all the layout knows about.
 */
private fun LazyGridState.indexAt(offset: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { item ->
        val x = offset.x.toInt()
        val y = offset.y.toInt()
        x >= item.offset.x && x < item.offset.x + item.size.width &&
            y >= item.offset.y && y < item.offset.y + item.size.height
    }?.index

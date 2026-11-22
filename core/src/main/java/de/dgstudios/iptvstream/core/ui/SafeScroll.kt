package de.dgstudios.iptvstream.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

/**
 * Wie scrollToItem, aber nie mit negativem Index. Compose wirft sonst
 * „Index should be non-negative (-1)“ – z. B. wenn beim Profilwechsel die Liste kurz leer ist.
 */
suspend fun LazyListState.scrollToItemSafe(index: Int, scrollOffset: Int = 0) {
    if (index < 0) scrollToItem(0, 0) else scrollToItem(index, scrollOffset.coerceAtLeast(0))
}

suspend fun LazyGridState.scrollToItemSafe(index: Int, scrollOffset: Int = 0) {
    if (index < 0) scrollToItem(0, 0) else scrollToItem(index, scrollOffset.coerceAtLeast(0))
}

/** Gespeicherte Position auf eine Liste mit [size] Einträgen begrenzen (leere Liste → 0). */
fun clampIndex(index: Int, size: Int): Int = if (size <= 0) 0 else index.coerceIn(0, size - 1)

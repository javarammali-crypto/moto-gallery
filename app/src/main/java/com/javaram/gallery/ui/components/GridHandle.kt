package com.javaram.gallery.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot

/**
 * Lets the photo viewer find where a photo sits in the grid, so it can fly out of
 * and back into exactly that spot.
 */
class GridHandle {
    internal var state: LazyGridState? = null
    internal var coords: LayoutCoordinates? = null
    internal var entries: List<GridEntry> = emptyList()
    internal var headerCount: Int = 0

    /** Bounds of the photo's cell in root (screen) coordinates, or null when it isn't on screen. */
    fun rectOf(id: Long): Rect? {
        val st = state ?: return null
        val c = coords ?: return null
        if (!c.isAttached) return null
        val info = st.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return null
        val origin = c.positionInRoot()
        return Rect(
            Offset(origin.x + info.offset.x, origin.y + info.offset.y),
            Size(info.size.width.toFloat(), info.size.height.toFloat()),
        )
    }

    /** Scrolls the grid so the photo is comfortably visible (not under the top or bottom bars). */
    suspend fun ensureVisible(id: Long, topInset: Float, bottomInset: Float) {
        val st = state ?: return
        val c = coords ?: return
        val r = rectOf(id)
        val origin = if (c.isAttached) c.positionInRoot().y else 0f
        val viewTop = origin + topInset
        val viewBottom = origin + c.size.height - bottomInset
        if (r != null && r.top >= viewTop && r.bottom <= viewBottom) return
        val idx = entries.indexOfFirst { it is MediaEntry && it.media.id == id }
        if (idx < 0) return
        st.scrollToItem(idx + headerCount, -(c.size.height / 3))
        withFrameNanos { }
    }
}

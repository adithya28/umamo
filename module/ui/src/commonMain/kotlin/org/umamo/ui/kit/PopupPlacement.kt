package org.umamo.ui.kit

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.math.max

/**
 * True while composing inside a popup that already owns the outside-click and Esc dismiss.  Only one
 * popup in a tree may be focusable, so a popup opened from inside another one must not take focus and
 * fight it for the dismiss - the inner press would otherwise read as "outside" to the outer popup, which
 * would tear the inner one down mid-click.  Popups that host arbitrary caller content (the overflow
 * dropdown, a filter panel) provide this so the menus and chips inside them yield automatically.
 */
val LocalPopupDismissOwned = compositionLocalOf { false }

/**
 * Positions a popup at its anchor's bottom-left - a menu dropping straight down from its trigger (the
 * menu-bar label or the area type selector) - kept on screen: the left edge is clamped into the window,
 * and a menu that would overflow the bottom flips ABOVE its anchor instead, falling back to a clamped
 * position when it fits neither way (a menu taller than the window then starts at the top edge and
 * scrolls).  Without this a long dropdown simply ran off the bottom and its tail was unreachable.
 */
internal object BelowAnchorPositionProvider : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val left = anchorBounds.left.coerceIn(0, max(0, windowSize.width - popupContentSize.width))
		val fitsBelow = anchorBounds.bottom + popupContentSize.height <= windowSize.height
		val above = anchorBounds.top - popupContentSize.height
		val top =
			when {
				fitsBelow -> anchorBounds.bottom
				above >= 0 -> above
				else -> windowSize.height - popupContentSize.height
			}

		return IntOffset(left, top.coerceIn(0, max(0, windowSize.height - popupContentSize.height)))
	}
}

/**
 * Positions a popup at a point [localOffset] inside its anchor (the context-menu host), so the menu opens
 * at the cursor.  The point is offset by the anchor's window origin and then clamped to keep the whole
 * menu on screen.
 *
 * @property IntOffset localOffset The cursor point relative to the anchor's top-left.
 */
internal class AtPointPositionProvider(private val localOffset: IntOffset) : PopupPositionProvider {
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val x = (anchorBounds.left + localOffset.x).coerceIn(0, max(0, windowSize.width - popupContentSize.width))
		val y = (anchorBounds.top + localOffset.y).coerceIn(0, max(0, windowSize.height - popupContentSize.height))

		return IntOffset(x, y)
	}
}

/**
 * Positions a popup beside a cursor point [cursorOffset] inside its anchor, clear of the pointer on each
 * axis: after the cursor (right and below in left-to-right layouts) where the card fits, mirrored to before
 * it (left or above) on an axis where it would cross the window's [windowMargin].  The card moves wholly to
 * the other side of the cursor, so it never sits over the pointer's hotspot - a card there would take the
 * click meant for the control beneath it.  An axis where the card fits on neither side is clamped into the
 * window instead; the other axis still keeps the card clear of the hotspot.
 *
 * The two sides take different gaps because the pointer's arrow hangs right and down from its hotspot:
 * [gapAfter] has to clear the arrow itself, while [gapBefore] faces bare space, so the same gap there
 * would read as a much wider one.
 *
 * @property IntOffset cursorOffset The cursor point relative to the anchor's top-left, in pixels.
 * @property Int       gapAfter     The gap between the cursor and a card right of or below it, in pixels.
 * @property Int       gapBefore    The gap between the cursor and a card left of or above it, in pixels.
 * @property Int       windowMargin The band along the window's edges the card stays out of, in pixels.
 */
internal class CursorClearPositionProvider(
	private val cursorOffset: IntOffset,
	private val gapAfter: Int,
	private val gapBefore: Int,
	private val windowMargin: Int,
) : PopupPositionProvider {
	/**
	 * Computes the card's top-left in window coordinates, choosing a side of the cursor per axis.
	 *
	 * @param IntRect         anchorBounds     The anchor's bounds in the window; the cursor is relative to its top-left.
	 * @param IntSize         windowSize       The host window size.
	 * @param LayoutDirection layoutDirection  Which horizontal side the card prefers (the trailing one).
	 * @param IntSize         popupContentSize The measured card size.
	 * @return IntOffset The card's top-left.
	 */
	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize,
	): IntOffset {
		val x =
			startClearOfCursor(
				cursor = anchorBounds.left + cursorOffset.x,
				extent = popupContentSize.width,
				windowExtent = windowSize.width,
				prefersAfter = layoutDirection == LayoutDirection.Ltr,
			)
		val y =
			startClearOfCursor(
				cursor = anchorBounds.top + cursorOffset.y,
				extent = popupContentSize.height,
				windowExtent = windowSize.height,
				prefersAfter = true,
			)

		return IntOffset(x, y)
	}

	/**
	 * Picks the card's start on one axis: the preferred side of the cursor when the card fits there inside
	 * the margin, else the other side, else the preferred side clamped into the window.
	 *
	 * @param Int     cursor       The cursor's window coordinate on this axis.
	 * @param Int     extent       The card's size on this axis.
	 * @param Int     windowExtent The window's size on this axis.
	 * @param Boolean prefersAfter Whether the card goes after the cursor (right or below) when both sides fit.
	 * @return Int The card's start coordinate on this axis.
	 */
	private fun startClearOfCursor(cursor: Int, extent: Int, windowExtent: Int, prefersAfter: Boolean): Int {
		val after = cursor + gapAfter
		val before = cursor - gapBefore - extent
		val fitsAfter = after + extent <= windowExtent - windowMargin
		val fitsBefore = before >= windowMargin
		val preferred = if (prefersAfter) after else before

		return when {
			fitsAfter && (prefersAfter || !fitsBefore) -> after
			fitsBefore -> before
			else -> preferred.coerceIn(windowMargin, max(windowMargin, windowExtent - windowMargin - extent))
		}
	}
}
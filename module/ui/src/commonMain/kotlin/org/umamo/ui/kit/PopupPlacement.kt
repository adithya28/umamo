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
package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Rect
import org.umamo.edit.TrackKeyRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.tracks.TrackAxis
import org.umamo.ui.tracks.TrackWindow
import org.umamo.ui.tracks.flattenTrackRows
import org.umamo.ui.tracks.laneMarkOffsetX

/**
 * Every key the window-space [region] encloses, across every section.
 *
 * Resolved against the lanes' own reported bounds rather than computed from row heights: sections fold,
 * groups collapse, and the sheet scrolls, so the only reliable answer to "where is this row" is the one
 * the row gave during layout.  A mark counts when its lane overlaps the region vertically AND its drawn
 * position falls inside it horizontally.
 *
 * Two things have to match what is ON SCREEN rather than what is in the model, or the marquee selects keys
 * the user cannot see:
 *
 *   - The rows walked are the FLATTENED, currently-visible ones ([flattenTrackRows] with the sheet's own
 *     expand state, skipping folded sections), not the whole tree.  laneBounds is never pruned - a lane
 *     that leaves composition simply stops reporting - so a collapsed group's children keep their last
 *     rectangles forever, and walking the tree blind would keep hitting them.
 *   - The axis is the WINDOWED one, so a zoomed sheet maps a mark to the pixel it is actually drawn at.
 *     The full-range axis put every mark at the wrong x the moment the sheet was not framed to the whole
 *     domain.
 *
 * Internal rather than private so the resolution can be tested without a composition: it is pure over plain
 * data, and a wrong answer here - an un-windowed axis, or a walk that reaches collapsed rows - is a
 * silently wrong selection, not a crash, so a direct test is the only way to catch it.
 *
 * @param Rect region The marquee, in window coordinates.
 * @param List projections Each targeted parameter and its tracks.
 * @param Map laneBounds Each row key's last reported window bounds.
 * @param Float markRadiusPx The sheet's mark radius, which is also its lane end inset.
 * @param TrackWindow window The visible slice of each parameter's range, shared by every section.
 * @param Set<String> expandedKeys The open group rows, which decide which lanes exist.
 * @param Set<ParameterId> collapsedParameters The folded sections, whose rows are not on screen at all.
 * @return Set<TrackKeyRef> The enclosed keys.
 */
internal fun keysWithin(
	region: Rect,
	projections: List<Pair<Parameter, KeyformSheetProjection>>,
	laneBounds: Map<String, Rect>,
	markRadiusPx: Float,
	window: TrackWindow,
	expandedKeys: Set<String>,
	collapsedParameters: Set<ParameterId>,
): Set<TrackKeyRef> {
	val enclosed = mutableSetOf<TrackKeyRef>()

	for ((parameter, projection) in projections) {
		if (parameter.id in collapsedParameters) {
			continue
		}

		val (domainStart, domainEnd) = parameterDomain(parameter)
		val axis = window.axisOver(TrackAxis(domainStart, domainEnd))

		for (line in flattenTrackRows(projection.rows, expandedKeys)) {
			// Only rows with a track ref: a group header names an owner and a blend-shape row is not a
			// keyform grid, so neither has keys a selection could act on.
			val row = line.row
			val bounds = laneBounds[row.key] ?: continue

			if (!projection.tracksByRowKey.containsKey(row.key) || !bounds.overlapsVertically(region)) {
				continue
			}

			for (mark in row.marks) {
				// The same mapping the marks were drawn with, from the same function - re-deriving the end
				// inset here is how a marquee drifts by exactly one mark width.
				val drawnX = bounds.left + laneMarkOffsetX(axis, mark.position, bounds.width, markRadiusPx)

				if (drawnX in region.left..region.right) {
					enclosed.add(TrackKeyRef(parameter.id, row.key, mark.keyIndex))
				}
			}
		}
	}

	return enclosed
}

/**
 * Whether two rectangles share any vertical extent - the marquee's row test.
 *
 * @param Rect other The rectangle to test against.
 * @return Boolean True when their vertical extents overlap.
 */
private fun Rect.overlapsVertically(other: Rect): Boolean = top < other.bottom && bottom > other.top
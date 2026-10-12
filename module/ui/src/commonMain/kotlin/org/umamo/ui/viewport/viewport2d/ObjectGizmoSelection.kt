package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.render.pick.drawablesInBox
import org.umamo.render.pick.drawablesInCircle
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ObjectPickController
import org.umamo.ui.viewport.gizmo.objectMarquee
import org.umamo.ui.viewport.gizmo.resolveObjectBoxSelection
import org.umamo.ui.viewport.gizmo.screenToWorld
import org.umamo.ui.viewport.gizmo.selectableDrawableTargets
import kotlin.math.max
import kotlin.math.min

/**
 * The world point each selectable object is boxed and brushed by: the ONE source the Object overlay's
 * box and circle selection read.  Today that is each drawable's world centroid, snapshotted at the press
 * that starts a select gesture (the pose is fixed for the whole drag, so one snapshot serves every move)
 * and tested against the region each frame.  Objects that gain a viewport anchor later (a part, a
 * deformer) join here.
 *
 * One instance per area: the marquee, the pick controller, and the armed box all refresh and read this
 * same holder, so a snapshot any of them takes is the one the others test against.
 *
 * @param Function takeSnapshot Reads the current anchors (the render service's drawable centroids).
 */
internal class ObjectSelectionAnchors(
	private val takeSnapshot: () -> Map<DrawableId, FloatArray>,
) {
	/** Each drawable's world centroid as of the last [refresh]. */
	var centroids by mutableStateOf<Map<DrawableId, FloatArray>>(emptyMap())
		private set

	/** Takes a fresh snapshot, at the press that starts a select gesture. */
	fun refresh() {
		centroids = takeSnapshot()
	}
}

/**
 * The drawable ids a selection holds, for the live GPU tint preview.
 *
 * @return Set<DrawableId> The selected drawables.
 */
internal fun Selection.selectedDrawableIds(): Set<DrawableId> = targets.mapNotNull { (it as? SelectionTarget.Drawable)?.id }.toSet()

/**
 * One Circle-select brush stamp: encloses the drawables whose world centroid is within the brush, filters
 * out the unselectable ones, and adds them to (or removes them from) the working selection.  The radius is
 * in screen pixels, so it converts to world units by the zoom; the center unprojects to world space to
 * match the centroids.  A stamp that encloses nothing returns [working] itself.
 *
 * @param Selection working The stroke's working selection.
 * @param Boolean erasing True when the stroke removes what it paints.
 * @param Offset screenPos The brush center in area-local pixels.
 * @param Float radiusPx The brush radius in screen pixels.
 * @param ViewportCamera activeCamera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Map<DrawableId, FloatArray> centroids Each drawable's world centroid.
 * @param PuppetModel model The model the selectable filter reads.
 * @return Selection The painted working selection.
 */
internal fun circleStampSelection(
	working: Selection,
	erasing: Boolean,
	screenPos: Offset,
	radiusPx: Float,
	activeCamera: ViewportCamera,
	size: IntSize,
	centroids: Map<DrawableId, FloatArray>,
	model: PuppetModel,
): Selection {
	val (worldX, worldZ) = screenToWorld(screenPos.x, screenPos.y, activeCamera, size)
	val worldRadius = radiusPx / activeCamera.zoom
	val enclosed = selectableDrawableTargets(drawablesInCircle(centroids, worldX, worldZ, worldRadius), model)

	if (enclosed.isEmpty()) {
		return working
	}

	return if (erasing) {
		val remaining = working.targets - enclosed.toSet()
		Selection(remaining, working.active?.takeIf { it in remaining } ?: remaining.lastOrNull())
	} else {
		Selection(working.targets + enclosed, enclosed.last())
	}
}

/**
 * A finished box drag's selection: every selectable drawable whose world centroid the box encloses
 * (Shift adds to the current selection).  Shared by the armed (Blender B) and un-armed paths.
 *
 * @param Selection current The selection the box applies to.
 * @param Offset start The box's press corner in area-local pixels.
 * @param Offset end The box's release corner.
 * @param Boolean additive True to add to [current] rather than replace it.
 * @param ViewportCamera activeCamera The area camera.
 * @param IntSize size The area size in pixels.
 * @param Map<DrawableId, FloatArray> centroids Each drawable's world centroid.
 * @param PuppetModel model The model the selectable filter reads.
 * @return Selection The new selection.
 */
internal fun boxSelection(
	current: Selection,
	start: Offset,
	end: Offset,
	additive: Boolean,
	activeCamera: ViewportCamera,
	size: IntSize,
	centroids: Map<DrawableId, FloatArray>,
	model: PuppetModel,
): Selection {
	val (worldStartX, worldStartZ) = screenToWorld(start.x, start.y, activeCamera, size)
	val (worldEndX, worldEndZ) = screenToWorld(end.x, end.y, activeCamera, size)
	val enclosed =
		selectableDrawableTargets(
			drawablesInBox(centroids, min(worldStartX, worldEndX), min(worldStartZ, worldEndZ), max(worldStartX, worldEndX), max(worldStartZ, worldEndZ)),
			model,
		)

	return resolveObjectBoxSelection(current, enclosed, additive)
}

/**
 * This viewport's object marquee (see objectMarquee): a stamp paints drawables by centroid, a box encloses
 * them by centroid, a stroke refreshes the anchors as it begins, and the live stroke tints on the GPU.  The
 * overlay holds one per area, so every callback reads the session and [anchors] when it runs.
 *
 * @param EditorSession session The session owning the object selection and the armed tool.
 * @param ObjectSelectionAnchors anchors The area's anchor holder.
 * @return MarqueeSelectController<Selection> The marquee.
 */
internal fun viewportObjectMarquee(session: EditorSession, anchors: ObjectSelectionAnchors): MarqueeSelectController<Selection> =
	objectMarquee(
		session = session,
		stampStroke = { working, erasing, center, radiusPx, stampCamera, stampSize ->
			circleStampSelection(working, erasing, center, radiusPx, stampCamera, stampSize, anchors.centroids, session.model.value)
		},
		applyBox = { start, end, additive, boxCamera, boxSize ->
			session.setSelection(boxSelection(session.selection.value, start, end, additive, boxCamera, boxSize, anchors.centroids, session.model.value))
		},
		onStrokeBegin = { anchors.refresh() },
		previewStroke = { stroke -> session.setPreviewSelection(stroke?.selectedDrawableIds()) },
	)

/**
 * The idle click-pick / box flow over whole drawables (press rubber-bands, sub-threshold release picks,
 * Alt resolves the overlap stack, Shift+RightClick places the 2D cursor), bound to this viewport's raster
 * pickers and its anchor snapshot; see ObjectPickController.
 *
 * @param String areaId The overlay's area (the pick target).
 * @param EditorSession session The session owning the selection and the cursor.
 * @param PuppetViewportService service The render service (the raster picks).
 * @param MarqueeSelectController<Selection> marquee The area's marquee.
 * @param ObjectSelectionAnchors anchors The area's anchor holder.
 * @param Function onOverlapRequest Opens the overlap picker for an Alt click with 2+ candidates.
 * @return ObjectPickController The controller.
 */
internal fun viewportObjectPick(
	areaId: String,
	session: EditorSession,
	service: PuppetViewportService,
	marquee: MarqueeSelectController<Selection>,
	anchors: ObjectSelectionAnchors,
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
): ObjectPickController =
	ObjectPickController(
		session = session,
		marquee = marquee,
		pickTopmost = { position -> service.pickAt(areaId, position.x, position.y) },
		pickStack = { position -> service.pickAllAt(areaId, position.x, position.y) },
		onOverlapRequest = onOverlapRequest,
		placeCursor = session::setCursor2d,
		onBoxBegin = { anchors.refresh() },
	)
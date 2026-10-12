package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshTopology
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.viewport.gizmo.TransformGestureFrame
import org.umamo.ui.viewport.gizmo.slideFactorAlongEdge
import org.umamo.ui.viewport.gizmo.worldToScreen

/**
 * The Vertex Slide gesture's frozen context: the active vertex and its incident neighbor candidates.
 * Only the candidates freeze at latch - the best edge is re-picked from the live pointer every move,
 * so the slide follows the pointer across the vertex's fan instead of locking to the first edge.
 *
 * @property DrawableId drawableId The mesh the active vertex lives in.
 * @property Int activeVertex The sliding vertex's index.
 * @property IntArray neighborIndices The active vertex's incident neighbors (the edge candidates).
 */
internal class SlideContext(
	val drawableId: DrawableId,
	val activeVertex: Int,
	val neighborIndices: IntArray,
)

/**
 * Where the Vertex Slide's most recent drive landed: the edge it picked and how far along it the vertex
 * sits - what the confirm registers on the operation settings strip, whose Factor row re-slides the
 * same frozen edge.
 *
 * @property Int neighborIndex The edge's far endpoint the drive picked.
 * @property Float factor The landed factor in [0, 1].
 */
internal class SlideLanding(
	val neighborIndex: Int,
	val factor: Float,
)

/**
 * The Vertex Slide's candidates for a selection: the active vertex and its incident neighbors.  A slide
 * needs an active VERTEX with at least one incident neighbor, so an active edge or face, no active
 * element, or an isolated vertex has none.
 *
 * @param MeshSelection selection The mesh selection the slide latched over.
 * @param List<EditMeshGeometry> geometries The session meshes' live geometry.
 * @return SlideContext? The candidates, or null when there is nothing to slide.
 */
internal fun slideContextFor(selection: MeshSelection, geometries: List<EditMeshGeometry>): SlideContext? {
	val active = selection.activeElement
	val activeVertex = (active?.element as? MeshElement.Vertex)?.index
	val activeGeometry = active?.let { candidate -> geometries.firstOrNull { it.drawableId == candidate.drawableId } }

	return if (activeVertex != null && activeGeometry != null) {
		val adjacency = MeshTopology.buildVertexAdjacency(activeGeometry.mesh.vertexCount, activeGeometry.mesh.indices)
		val neighbors = adjacency.getOrElse(activeVertex) { IntArray(0) }
		if (neighbors.isNotEmpty()) SlideContext(active.drawableId, activeVertex, neighbors) else null
	} else {
		null
	}
}

/**
 * Where one pointer frame lands the slide: the incident edge whose far endpoint sits nearest the
 * (virtual) pointer on screen, and the factor the pointer projects to along it.  Re-picked every frame,
 * so the slide hops between the active vertex's edges as the pointer travels.
 *
 * @param SlideContext context The slide's frozen candidates.
 * @param FloatArray originalWorld The captured world positions of the sliding vertex's mesh.
 * @param TransformGestureFrame frame The pointer frame (its current pointer, camera, and size).
 * @return SlideLanding? The landing, or null when the context has no candidate.
 */
internal fun slideLandingToward(context: SlideContext, originalWorld: FloatArray, frame: TransformGestureFrame): SlideLanding? {
	var bestNeighbor = -1
	var bestDistance = Float.MAX_VALUE

	for (neighbor in context.neighborIndices) {
		val screen = worldToScreen(originalWorld[neighbor * 2], originalWorld[neighbor * 2 + 1], frame.camera, frame.size)
		val distance = (screen - frame.current).getDistance()

		if (distance < bestDistance) {
			bestDistance = distance
			bestNeighbor = neighbor
		}
	}

	if (bestNeighbor < 0) {
		return null
	}

	return SlideLanding(bestNeighbor, slideFactorAlongEdge(originalWorld, context.activeVertex, bestNeighbor, frame))
}
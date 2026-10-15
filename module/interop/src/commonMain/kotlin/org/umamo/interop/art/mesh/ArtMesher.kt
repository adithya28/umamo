package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaChannel
import org.umamo.format.art.AlphaField
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaChannel
import org.umamo.format.art.alphaField
import org.umamo.geometry.polyline.outermostRings
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * The art mesher: a deformation-ready triangle mesh over a layer's art, built from its signed distance
 * field - the outline and the inner ring are contours of the field, and a lattice fills between them.
 *
 * The OUTLINE is the field's contour a little outside the art - at least the minimum margin plus two
 * working cells.  A traced chord provably clears the art by the minimum margin plus a little over half
 * a cell at that level (the field's sampling and interpolation cost up to 1.42 cells), and thinning the
 * traced ring's near-duplicate vertices costs at most a quarter cell more, so every chord kept from
 * the thinned ring still clears the margin and every art pixel stays enclosed.  It is simplified to
 * the outline spacing with keypoints at sharp turns, and any simplified chord that comes too close to
 * the art, or cuts off a piece of it, is split again from the traced contour.  An INNER RING follows
 * the art's edge a margin inside it, a hexagonal LATTICE fills the interior, PINS become vertices at
 * their exact positions, and the constrained triangulation of all of it, clipped to the outline, is
 * the mesh.  A pin's SURROUNDINGS (the old triangles around a glued vertex) count as art, so a pin off
 * the art is joined to it by the surface it had rather than meshed around on its own.  With a MINIMUM
 * ANGLE the triangulation is refined toward it in what the vertex budget leaves (see assembleMesh).
 *
 * Over the VERTEX BUDGET the interior gives way first: its spacing widens while the outline keeps the
 * silhouette as asked, and only when the outline and pins alone fill the budget is the interior left
 * to refinement and the outline widened.  A mesh that fits is never coarsened; one that must be keeps
 * a share of the budget back, so refinement has room to fix what coarsening makes - unless the last
 * round only fits the whole budget, which then stands rather than fail.
 *
 * Filling holes meshes the art's silhouette instead.  The outline keeps only the rings nothing else
 * encloses, so no hole ring (and no ring of art inside a hole) ever reaches the triangulation and the
 * mesh is one solid sheet per piece; the outline itself is traced from the art's own field, so every
 * guarantee above still holds.  The inner ring and lattice come from the field with its holes
 * filled, so they run evenly across the holes instead of only through the art.
 *
 * Everything is in the layer raster's own pixels, y down, rounded to Float before it is triangulated,
 * so the triangles' orientation (orient2d > 0, the birth quad's winding) holds as stored.  The output
 * is a function of the alpha channel, the settings, the pins, and their surroundings alone.
 */

/** Rounds of thinning the interior and widening the outline to meet the vertex budget before giving up. */
private const val BUDGET_ROUNDS: Int = 8

/**
 * The share of the vertex budget held back for refinement once a mesh has to be coarsened to fit.  A
 * coarsened mesh pairs a dense outline with a thinned interior, which makes the very slivers refinement
 * fixes, so it needs real room: on the corpus a third of the budget halves what a fifth leaves below
 * the minimum angle, with fewer vertices in all.
 */
private const val REFINEMENT_RESERVE_SHARE: Double = 0.30

/**
 * Meshes one layer raster; see [generateArtMesh] over an [AlphaChannel], which is all it reads.
 *
 * @param LayerRaster     raster   The layer's pixels.
 * @param ArtMeshSettings settings How to space and place vertices.
 * @param DoubleArray     pins            Points that must become vertices, x0, y0, ..., raster px.
 * @param DoubleArray     pinSurroundings Triangles that count as art, six coordinates each, raster px.
 * @return ArtMeshResult The mesh, or why there is none, with what was adjusted.
 */
public fun generateArtMesh(raster: LayerRaster, settings: ArtMeshSettings, pins: DoubleArray = DoubleArray(0), pinSurroundings: DoubleArray = DoubleArray(0)): ArtMeshResult =
	generateArtMesh(raster.alphaChannel(), settings, pins, pinSurroundings)

/**
 * Meshes one layer's alpha channel.
 *
 * @param AlphaChannel    alpha    The layer's alpha, the only channel the mesh depends on.
 * @param ArtMeshSettings settings How to space and place vertices.
 * @param DoubleArray     pins            Points that must become vertices, x0, y0, ..., raster px (glued vertices kept through a regeneration).
 * @param DoubleArray     pinSurroundings Triangles that count as art, six coordinates each, raster px (the old surface around those glued vertices).
 * @return ArtMeshResult The mesh, or why there is none, with what was adjusted.
 */
public fun generateArtMesh(alpha: AlphaChannel, settings: ArtMeshSettings, pins: DoubleArray = DoubleArray(0), pinSurroundings: DoubleArray = DoubleArray(0)): ArtMeshResult {
	require(pins.size % 2 == 0) { "pins must hold x, y pairs, but has ${pins.size} components" }

	for (component in pins.indices) {
		require(pins[component].isFinite()) { "pin component $component is not finite: ${pins[component]}" }
	}

	require(pinSurroundings.size % 6 == 0) { "pin surroundings must hold six coordinates per triangle, but has ${pinSurroundings.size} components" }

	for (component in pinSurroundings.indices) {
		require(pinSurroundings[component].isFinite()) { "pin surroundings component $component is not finite: ${pinSurroundings[component]}" }
	}

	// The working cell follows the margins (contours move by up to about a cell), never the spacing.
	val nearestMargin = if (settings.innerMargin > 0.0) minOf(settings.outerMargin, settings.innerMargin) else settings.outerMargin
	val requestedCellSize = maxOf(1, floor(nearestMargin / 2.0).toInt())
	// Enough padding for the outline level at ANY cell size the grid cap may force: the level is at most
	// outerMargin + minimumMargin + 2 cells, and the contour must stay clear of the border.
	val paddingCells = ceil(settings.outerMargin / requestedCellSize).toInt() + ceil(settings.minimumMargin / requestedCellSize).toInt() + 4
	val field = alpha.alphaField(settings.alphaThreshold, requestedCellSize, paddingCells, pins, seedTriangles = pinSurroundings) ?: return ArtMeshResult(null, listOf(ArtMeshNotice.NothingOpaque), null)
	val notices = ArrayList<ArtMeshNotice>()

	if (field.cellSize > requestedCellSize) {
		notices.add(ArtMeshNotice.WorkingGridCoarsened(requestedCellSize, field.cellSize))
	}

	val cellSize = field.cellSize
	val outlineLevel = maxOf(settings.outerMargin, settings.minimumMargin + 2.0 * cellSize)
	val tolerance = maxOf(1.0, cellSize.toDouble())
	val thinning = cellSize / 4.0
	val tracedOutline = field.isoRings(outlineLevel)
	val outlineRings = if (settings.fillHoles) outermostRings(tracedOutline).map { index -> tracedOutline[index] } else tracedOutline
	val denseOutline = outlineRings.map { ring -> thinTracedRing(ring, thinning) }
	val interiorField = if (settings.fillHoles) field.withHolesFilled(outlineLevel) else field
	val denseInner = if (settings.innerMargin > 0.0) interiorField.isoRings(-settings.innerMargin).map { ring -> thinTracedRing(ring, thinning) } else emptyList()
	val roundedPins = DoubleArray(pins.size) { component -> pins[component].toFloat().toDouble() }
	val pinCount = pins.size / 2
	val vertexFloor = denseOutline.size * settings.minimumOutlinePoints + pinCount

	if (vertexFloor > settings.vertexBudget) {
		notices.add(ArtMeshNotice.OverBudget(vertexFloor, settings.vertexBudget))

		return ArtMeshResult(null, notices, null)
	}

	var outlineSpacing = settings.outlineSpacing
	// Null once the interior is left to refinement.
	var interiorSpacing: Double? = settings.interiorSpacing
	var target = settings.vertexBudget
	var previousInteriorCount = Int.MAX_VALUE
	var round = 0

	while (true) {
		val points = placePoints(field, interiorField, settings, denseOutline, denseInner, roundedPins, outlineSpacing, interiorSpacing, tolerance)
		// The reserve gives way on the last round, so it never turns a mesh that fits into none.
		val fits = points.vertexCount <= target || (round == BUDGET_ROUNDS && points.vertexCount <= settings.vertexBudget)

		if (fits) {
			if (round > 0) {
				notices.add(ArtMeshNotice.SpacingCoarsened(outlineSpacing, interiorSpacing))
			}

			return assemble(points, field, settings, notices, cellSize, outlineLevel, outlineSpacing, interiorSpacing, pinCount)
		}

		round++

		// With the floor filling the whole budget, no widening can help.
		if (round > BUDGET_ROUNDS || vertexFloor >= settings.vertexBudget) {
			notices.add(ArtMeshNotice.OverBudget(points.vertexCount, settings.vertexBudget))

			return ArtMeshResult(null, notices, null)
		}

		// Coarsening is needed, so keep a share of the budget back for refinement, never past the floor.
		target = settings.vertexBudget - minOf(ceil(REFINEMENT_RESERVE_SHARE * settings.vertexBudget).toInt(), settings.vertexBudget - vertexFloor)
		val interiorRoom = target - points.outlineCount
		val interiorCount = points.interiorCount
		val currentInteriorSpacing = interiorSpacing

		if (currentInteriorSpacing != null && interiorRoom > 0 && interiorCount in 1 until previousInteriorCount) {
			// Thin the interior alone: the lattice shrinks with the spacing's square and the inner ring
			// with the spacing, so the square root of the overshoot (plus a little) lands close.
			interiorSpacing = currentInteriorSpacing * sqrt(interiorCount.toDouble() / interiorRoom) * 1.05
			previousInteriorCount = interiorCount
		} else if (currentInteriorSpacing != null) {
			// The outline and pins fill the budget themselves (or the interior stopped thinning).
			interiorSpacing = null
		} else {
			// Only the outline above its floor (each ring's minimum, plus the pins) shrinks as it widens,
			// in proportion to the spacing.
			val reducible = (points.outlineCount - vertexFloor).toDouble()
			val reducibleBudget = maxOf(1.0, (target - vertexFloor).toDouble())
			outlineSpacing *= reducible / reducibleBudget * 1.05
		}
	}
}

/**
 * Every point one attempt places, before triangulation.
 *
 * @property List<SampledRing> outline    The outline rings.
 * @property List<DoubleArray> innerRings The inner rings' points.
 * @property DoubleArray       lattice    The lattice points.
 * @property DoubleArray       pins       The pins.
 */
private class PlacedPoints(val outline: List<SampledRing>, val innerRings: List<DoubleArray>, val lattice: DoubleArray, val pins: DoubleArray) {
	/** The outline's and the pins' points: what the interior cannot give way for. */
	val outlineCount: Int
		get() = outline.sumOf { ring -> ring.sampleCount } + pins.size / 2

	/** The inner rings' and the lattice's points. */
	val interiorCount: Int
		get() = innerRings.sumOf { ring -> ring.size / 2 } + lattice.size / 2

	/** The vertex count the mesh would have (an upper bound; aliasing and unused points only lower it). */
	val vertexCount: Int
		get() = outlineCount + interiorCount
}

/**
 * Places the outline, inner rings, and lattice at one pair of spacings.
 *
 * @param AlphaField        field           The art's field, which every outline chord is checked against.
 * @param AlphaField        interiorField   The field the inner rings and lattice follow (the silhouette's when filling holes).
 * @param ArtMeshSettings   settings        The settings.
 * @param List<DoubleArray> denseOutline    The traced outline contours.
 * @param List<DoubleArray> denseInner      The traced inner contours.
 * @param DoubleArray       pins            The pins, Float-rounded.
 * @param Double            outlineSpacing  The outline spacing.
 * @param Double?           interiorSpacing The interior spacing, or null to place no interior points.
 * @param Double            tolerance       The corner-finding tolerance.
 * @return PlacedPoints The points.
 */
private fun placePoints(
	field: AlphaField,
	interiorField: AlphaField,
	settings: ArtMeshSettings,
	denseOutline: List<DoubleArray>,
	denseInner: List<DoubleArray>,
	pins: DoubleArray,
	outlineSpacing: Double,
	interiorSpacing: Double?,
	tolerance: Double,
): PlacedPoints {
	val minimumMargin = settings.minimumMargin
	// A chord stands only when it keeps the minimum margin from every art pixel AND cuts no art pixel
	// off - the cap between it and the traced stretch it replaces must hold none, or even-odd would
	// erase a speck swallowed there along with its own ring.
	val coversArt =
		ChordCheck { ring, startArc, endArc, between ->
			val startX = ring.roundedX(startArc)
			val startY = ring.roundedY(startArc)
			val endX = ring.roundedX(endArc)
			val endY = ring.roundedY(endArc)

			if (!field.segmentClears(startX, startY, endX, endY, minimumMargin)) {
				false
			} else {
				val cap = DoubleArray(2 * (between.size + 2))
				cap[0] = startX
				cap[1] = startY

				for ((position, vertex) in between.withIndex()) {
					cap[2 * position + 2] = ring.denseX(vertex)
					cap[2 * position + 3] = ring.denseY(vertex)
				}

				cap[cap.size - 2] = endX
				cap[cap.size - 1] = endY
				!field.containsOpaquePixelCenter(cap)
			}
		}
	val outline =
		denseOutline.map { dense ->
			val ring = SampledRing(dense)
			ring.sampleEvenly(outlineSpacing, tolerance, settings.minimumOutlinePoints)
			ring.refine(coversArt)
			ring
		}

	if (interiorSpacing == null) {
		return PlacedPoints(outline, emptyList(), DoubleArray(0), pins)
	}

	val innerRings = innerRingPoints(denseInner, interiorField, interiorSpacing, settings.innerMargin, tolerance, pins)
	val lattice = latticePoints(interiorField, interiorSpacing, settings.innerMargin, pins)

	return PlacedPoints(outline, innerRings, lattice, pins)
}

/**
 * Assembles the placed points into the final mesh and result.
 *
 * @param PlacedPoints          points          The points.
 * @param AlphaField            field           The field.
 * @param ArtMeshSettings       settings        The settings.
 * @param MutableList           notices         The notices so far.
 * @param Int                   cellSize        The working cell size.
 * @param Double                outlineLevel    The outline's level.
 * @param Double                outlineSpacing  The outline spacing used.
 * @param Double?               interiorSpacing The interior spacing used, or null when the interior was left to refinement.
 * @param Int                   pinCount        The number of pins.
 * @return ArtMeshResult The result.
 */
private fun assemble(
	points: PlacedPoints,
	field: AlphaField,
	settings: ArtMeshSettings,
	notices: MutableList<ArtMeshNotice>,
	cellSize: Int,
	outlineLevel: Double,
	outlineSpacing: Double,
	interiorSpacing: Double?,
	pinCount: Int,
): ArtMeshResult {
	val refinedChordCount = points.outline.sumOf { ring -> ring.refinedChordCount }
	val assembled = assembleMesh(points.outline, points.innerRings, points.lattice, points.pins, field, settings, cellSize)
	val refinement = assembled.refinement

	if (assembled.densifiedChordCount > 0) {
		notices.add(ArtMeshNotice.OutlineDensified(assembled.densifiedChordCount))
	}

	if (assembled.droppedInnerEdgeCount > 0) {
		notices.add(ArtMeshNotice.InnerRingEdgesDropped(assembled.droppedInnerEdgeCount))
	}

	if (assembled.sharedPinCount > 0) {
		notices.add(ArtMeshNotice.PinsShareVertex(assembled.sharedPinCount))
	}

	if (refinement != null && refinement.stoppedByCap) {
		notices.add(ArtMeshNotice.RefinementBudgetReached(refinement.underTargetTriangleCount))
	}

	val statistics =
		ArtMeshStatistics(
			cellSize = cellSize,
			outlineLevel = outlineLevel,
			outlineSpacing = outlineSpacing,
			interiorSpacing = interiorSpacing,
			outlineRingCount = points.outline.size,
			outlineVertexCount = assembled.outlineVertexCount,
			refinedChordCount = refinedChordCount,
			innerRingCount = points.innerRings.size,
			innerVertexCount = points.innerRings.sumOf { ring -> ring.size / 2 },
			latticeVertexCount = points.lattice.size / 2,
			pinCount = pinCount,
			steinerVertexCount = refinement?.addedPointCount ?: 0,
			boundarySplitCount = refinement?.boundarySplitCount ?: 0,
			underTargetCount = refinement?.underTargetTriangleCount ?: 0,
			unfixableCount = refinement?.unfixableTriangleCount ?: 0,
		)
	val mesh = assembled.mesh

	if (mesh == null) {
		notices.add(ArtMeshNotice.StructureCheckFailed(assembled.failure ?: "unknown"))

		return ArtMeshResult(null, notices, statistics)
	}

	// Untangling crossing rings adds traced points; the budget still holds the final word.
	if (mesh.vertexCount > settings.vertexBudget) {
		notices.add(ArtMeshNotice.OverBudget(mesh.vertexCount, settings.vertexBudget))

		return ArtMeshResult(null, notices, statistics)
	}

	return ArtMeshResult(mesh, notices, statistics)
}
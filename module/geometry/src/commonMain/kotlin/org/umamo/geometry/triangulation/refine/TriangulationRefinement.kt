package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.triangulation.ConstraintKind
import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import org.umamo.geometry.triangulation.Triangulation
import org.umamo.geometry.triangulation.buildConstrained
import org.umamo.geometry.triangulation.classifyRegions

/**
 * Decides whether refinement may split one boundary edge at a point, so a caller that knows what
 * lies on either side (art the mesh must cover, a margin it must keep) can refuse.
 */
public fun interface BoundarySplitCheck {
	/**
	 * Whether the boundary edge may run through the split point instead of straight.
	 *
	 * @param Double  startX     The edge's start x.
	 * @param Double  startY     The edge's start y.
	 * @param Double  endX       The edge's end x.
	 * @param Double  endY       The edge's end y.
	 * @param Double  splitX     The split point's x (a Float value).
	 * @param Double  splitY     The split point's y.
	 * @param Boolean cutsInside Whether the split point lies on the inside of the edge, so the thin triangle between the old edge and the two new ones leaves the domain.  False for a point exactly on the edge or on its outside.
	 * @return Boolean True to allow the split.
	 */
	public fun accepts(startX: Double, startY: Double, endX: Double, endY: Double, splitX: Double, splitY: Double, cutsInside: Boolean): Boolean
}

/**
 * How [triangulateAndRefine] refines.
 *
 * @property Double              minimumAngleDegrees  The smallest angle every inside triangle should reach, 0 (no refinement) up to below 60 degrees.  Triangles whose small angle is fixed by the input (two constraints meeting at it) or that a floor below stops are left and counted.
 * @property Int                 maximumAddedPoints   The most vertices refinement may add; it stops when it reaches them.
 * @property Double              minimumSeparation    No added vertex comes closer than this to a vertex it joins.
 * @property Double              minimumSegmentLength No constraint is split into a piece shorter than this.
 * @property BoundarySplitCheck? boundarySplitCheck   Asked before any boundary edge is split; null allows every split.
 */
public class RefinementOptions(
	public val minimumAngleDegrees: Double,
	public val maximumAddedPoints: Int,
	public val minimumSeparation: Double,
	public val minimumSegmentLength: Double,
	public val boundarySplitCheck: BoundarySplitCheck? = null,
) {
	init {
		require(minimumAngleDegrees >= 0.0 && minimumAngleDegrees < 60.0) { "the minimum angle $minimumAngleDegrees is outside 0 until 60 degrees" }
		require(maximumAddedPoints >= 0) { "the added point limit $maximumAddedPoints is negative" }
		require(minimumSeparation > 0.0 && minimumSeparation.isFinite()) { "the minimum separation $minimumSeparation is not a positive distance" }
		require(minimumSegmentLength > 0.0 && minimumSegmentLength.isFinite()) { "the minimum segment length $minimumSegmentLength is not a positive distance" }
	}
}

/**
 * What one refinement did.
 *
 * @property Int     addedPointCount           Vertices added, interior and on segments.
 * @property Int     interiorPointCount        Vertices added inside the domain to fix a triangle.
 * @property Int     offCenterCount            Of those, the off-centers (the rest are circumcenters).
 * @property Int     segmentSplitCount         Constraint edges split, boundary edges included.
 * @property Int     boundarySplitCount        Of those, boundary edges.
 * @property Int     refusedBoundarySplitCount Split points the boundary split check refused.
 * @property Int     underTargetTriangleCount  Inside triangles left with an angle below the target.
 * @property Int     unfixableTriangleCount    Of those, the ones refinement gave up on: an angle between two constraints, a triangle across two segments' matching shells, or a floor.  Every one of them when neither stop below fired.
 * @property Boolean stoppedByCap              Whether it stopped at [RefinementOptions.maximumAddedPoints] with work left.
 * @property Boolean stoppedByBackstop         Whether it stopped at its operation backstop, which only a defect reaches.
 */
public data class RefinementStatistics(
	val addedPointCount: Int,
	val interiorPointCount: Int,
	val offCenterCount: Int,
	val segmentSplitCount: Int,
	val boundarySplitCount: Int,
	val refusedBoundarySplitCount: Int,
	val underTargetTriangleCount: Int,
	val unfixableTriangleCount: Int,
	val stoppedByCap: Boolean,
	val stoppedByBackstop: Boolean,
)

/**
 * A refined triangulation.
 *
 * A plain class: it holds arrays.
 *
 * @property DoubleArray          points          The input points, then every added point (each a Float value), x0, y0, x1, y1, ...
 * @property Int                  inputPointCount How many of [points] were the input.
 * @property Triangulation        triangulation   The triangles over [points], as [org.umamo.geometry.triangulation.triangulate] reports them; an added point is its own canonical index, and a split constraint appears as its pieces.
 * @property RefinementStatistics statistics      What refinement did.
 */
public class RefinedTriangulation(
	public val points: DoubleArray,
	public val inputPointCount: Int,
	public val triangulation: Triangulation,
	public val statistics: RefinementStatistics,
)

/**
 * The constrained Delaunay triangulation of a point set, then refined in place until every inside
 * triangle's smallest angle reaches a target - by Delaunay refinement with off-centers (Üngör), which
 * adds fewer vertices than circumcenters for the same bound, and with diametral lenses (Shewchuk) for
 * the segments, so a vertex a little behind a segment does not force it to split.
 *
 * The triangulation is exactly [org.umamo.geometry.triangulation.triangulate]'s, given the same
 * input; refinement then adds vertices after the input ones.  Without boundaries the convex hull's
 * edges bound the domain: they are made boundary constraints first, so they split like any other.
 * When a boundary edge is rejected or a ring is left open, inside and outside are not well defined and
 * nothing is added.
 *
 * Refinement always terminates.  Small input angles cannot be fixed, so a triangle whose small angle
 * lies between two constraints is left, as is one whose short edge joins two segments' concentric
 * shell points around the corner they share (Miller, Pav, and Walkington's rule); segments next to a
 * sharp corner split on power-of-two shells around it, so such triangles form instead of an endless
 * cascade.  The separation and segment-length floors, the added-point cap, and an operation backstop
 * bound the rest.  Every decision is exact or a deterministic floating-point inequality, so the result
 * is a function of the input alone, on every platform.
 *
 * @param DoubleArray       points     The points, x0, y0, x1, y1, ...; every component must be finite.
 * @param List<IntArray>    boundaries Closed rings of point indices, as for triangulate.
 * @param List<IntArray>    segments   Open polylines of point indices, as for triangulate.
 * @param RefinementOptions options    The target and the limits.
 * @return RefinedTriangulation The points, the triangulation over them, and what was done.
 */
public fun triangulateAndRefine(points: DoubleArray, boundaries: List<IntArray> = emptyList(), segments: List<IntArray> = emptyList(), options: RefinementOptions): RefinedTriangulation =
	refineTriangulation(points, boundaries, segments, options, SteinerPointKind.OffCenter, null)

/** Which point refinement inserts to fix a triangle. */
internal enum class SteinerPointKind {
	/** The off-center on the short edge's bisector, or the circumcenter when it is nearer. */
	OffCenter,

	/** Always the circumcenter: plain Ruppert refinement, kept as the comparison arm. */
	Circumcenter,
}

/**
 * [triangulateAndRefine] with its internal choices exposed for tests.
 *
 * @param DoubleArray                         points        The points.
 * @param List<IntArray>                      boundaries    The boundary rings.
 * @param List<IntArray>                      segments      The segment polylines.
 * @param RefinementOptions                   options       The target and the limits.
 * @param SteinerPointKind                    steinerPoints Which interior points to insert.
 * @param ((HalfEdgeTriangulation) -> Unit)? afterEachStep Called after every added vertex (tests validate here).
 * @return RefinedTriangulation The result.
 */
internal fun refineTriangulation(
	points: DoubleArray,
	boundaries: List<IntArray>,
	segments: List<IntArray>,
	options: RefinementOptions,
	steinerPoints: SteinerPointKind,
	afterEachStep: ((HalfEdgeTriangulation) -> Unit)?,
): RefinedTriangulation {
	val inputPointCount = points.size / 2
	require(inputPointCount.toLong() + options.maximumAddedPoints < MAXIMUM_PACKED_VERTICES) { "$inputPointCount points plus ${options.maximumAddedPoints} added exceed $MAXIMUM_PACKED_VERTICES vertices" }
	val built = buildConstrained(points, boundaries, segments, inputPointCount + options.maximumAddedPoints)
	val store = built.store

	if (store == null) {
		val empty = Triangulation(IntArray(0), built.canonicalIndex, IntArray(0), built.rejectedConstraints, true)

		return RefinedTriangulation(points.copyOf(), inputPointCount, empty, unrefinedStatistics(0))
	}

	if (boundaries.isEmpty()) {
		markHullAsBoundary(store)
	}

	val classification = classifyRegions(store)
	store.adoptRegions(classification.inside)
	val boundaryRejected = built.rejectedConstraints.any { rejection -> rejection.kind == ConstraintKind.Boundary }
	val refines = classification.consistent && !boundaryRejected && options.minimumAngleDegrees > 0.0 && options.maximumAddedPoints > 0
	val statistics =
		if (refines) {
			QualityRefiner(store, inputPointCount, options, steinerPoints, afterEachStep).run()
		} else {
			unrefinedStatistics(countUnderTarget(store, options.minimumAngleDegrees))
		}
	val canonical = IntArray(store.pointCount) { vertex -> if (vertex < inputPointCount) built.canonicalIndex[vertex] else vertex }
	val triangulation = Triangulation(store.realTriangles(store.regions), canonical, store.constrainedEdgePairs(), built.rejectedConstraints, classification.consistent)

	return RefinedTriangulation(store.copyPoints(), inputPointCount, triangulation, statistics)
}

/**
 * Makes every convex hull edge a boundary constraint, so a domain given without boundary rings is
 * bounded by edges refinement can split.
 *
 * @param HalfEdgeTriangulation store The unclassified store.
 */
private fun markHullAsBoundary(store: HalfEdgeTriangulation) {
	for (triangle in 0 until store.triangleCount) {
		if (store.isGhostTriangle(triangle)) {
			store.markConstraint(store.realEdgeOfGhost(triangle), true)
		}
	}
}

/**
 * Counts the inside triangles below a target angle in a store that was not refined.
 *
 * @param HalfEdgeTriangulation store        The classified store.
 * @param Double                targetDegrees The target; 0 counts nothing.
 * @return Int The count.
 */
private fun countUnderTarget(store: HalfEdgeTriangulation, targetDegrees: Double): Int {
	if (targetDegrees <= 0.0) {
		return 0
	}

	val targetSineSquared = sineSquaredOfDegrees(targetDegrees)
	val shape = TriangleShape()
	var count = 0

	for (triangle in 0 until store.triangleCount) {
		if (!store.regions[triangle]) {
			continue
		}

		val base = 3 * triangle
		val first = store.origin[base]
		val second = store.origin[base + 1]
		val third = store.origin[base + 2]
		shape.measure(store.pointX(first), store.pointY(first), store.pointX(second), store.pointY(second), store.pointX(third), store.pointY(third))

		if (shape.smallestSineSquared < targetSineSquared) {
			count++
		}
	}

	return count
}

/**
 * The statistics of a triangulation refinement left alone.
 *
 * @param Int underTargetCount The inside triangles below the target.
 * @return RefinementStatistics Nothing added.
 */
private fun unrefinedStatistics(underTargetCount: Int): RefinementStatistics = RefinementStatistics(0, 0, 0, 0, 0, 0, underTargetCount, 0, false, false)
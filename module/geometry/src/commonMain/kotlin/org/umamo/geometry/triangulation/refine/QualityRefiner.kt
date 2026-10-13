package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.packVertexPair
import org.umamo.geometry.pairFirst
import org.umamo.geometry.pairSecond
import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import kotlin.math.sqrt

/*
 * Delaunay refinement for a minimum angle, Ruppert's algorithm with Üngör's off-centers, Shewchuk's
 * diametral lenses, and Miller, Pav, and Walkington's rule for small input angles.
 *
 * Bad triangles are fixed worst first.  A bad triangle's new vertex goes on the perpendicular bisector
 * of its shortest edge, at the off-center (a little nearer than where that edge would see the target
 * angle) or the circumcenter when that is nearer still.  The vertex is refused, and the triangle waits, when a
 * segment hides it from the triangle or it would sit inside a segment's lens: that segment is split
 * first.  Segments are split only on such demand, never just because a vertex sits near them, so a
 * mesh that is already good gains nothing.
 *
 * A segment splits at its midpoint, or - when exactly one end is a sharp corner, where two constraints
 * meet under 60 degrees - on the power-of-two shell around that corner nearest its middle, so every
 * segment around a corner splits at the same radii.  A triangle whose small angle lies between two
 * constraints cannot be helped, and neither can one whose short edge joins two shell points the same
 * distance from a shared corner; both are given up on rather than refined forever.
 *
 * Work lists hold vertex pairs and triples, never slots: a slot means nothing after a flip.  A queued
 * triangle that no longer exists is skipped when it comes up.
 */

/**
 * The off-center's distance from the short edge's midpoint, per unit of the edge's length and per
 * cot(target / 2): 0.5 puts it exactly where the edge sees the target angle, and Triangle's 0.475 a
 * little nearer, so the triangle it makes clears the target by a degree or so - room for the Float
 * rounding of the vertex, and fewer cascades at targets above 30 degrees.
 */
private const val OFF_CENTER_SHARE: Double = 0.475

/** Two constraints meeting under this many degrees make a sharp corner. */
private const val SHARP_CORNER_DEGREES: Double = 60.0

/** Two shell points are the same distance from their corner when their squared distances agree this closely. */
private const val SHELL_DISTANCE_TOLERANCE: Double = 0.001

/** Backstop operations allowed per vertex slot. */
private const val BACKSTOP_OPERATIONS_PER_VERTEX: Long = 64L

/** Backstop operations allowed regardless of size. */
private const val BACKSTOP_BASE_OPERATIONS: Long = 4096L

/** A vertex added in the interior, not on any segment. */
private const val NO_SEGMENT: Long = -1L

/**
 * One refinement run over a classified store.
 *
 * @property HalfEdgeTriangulation             store           The store, regions adopted, with room for the added vertices.
 * @property Int                               inputPointCount The vertices that came from the input.
 * @property RefinementOptions                 options         The target and the limits.
 * @property SteinerPointKind                  steinerPoints   Which interior points to insert.
 * @property ((HalfEdgeTriangulation) -> Unit)? afterEachStep   Called after every added vertex.
 */
internal class QualityRefiner(
	private val store: HalfEdgeTriangulation,
	private val inputPointCount: Int,
	private val options: RefinementOptions,
	private val steinerPoints: SteinerPointKind,
	private val afterEachStep: ((HalfEdgeTriangulation) -> Unit)?,
) {
	private val steiner = SteinerInsertion(store)
	private val shape = TriangleShape()
	private val targetSineSquared = sineSquaredOfDegrees(options.minimumAngleDegrees)

	/** The off-center's distance from the short edge's midpoint, per unit of the edge's length. */
	private val offCenterParameter = OFF_CENTER_SHARE * cotangentOfHalfAngle(targetSineSquared)

	/**
	 * The lens holds the points that see a segment under more than 180 - 2 target degrees: such a point
	 * would make a triangle with the segment whose base angles sum below twice the target, which only
	 * splitting the segment can fix.  So a candidate there splits the segment instead of going in.
	 */
	private val lensCosine = lensCosineSquared(targetSineSquared)
	private val separationSquared = options.minimumSeparation * options.minimumSeparation

	private val queue = TriangleQueue()
	private val queuedTriangles = HashSet<Long>()
	private val unfixableTriangles = HashSet<Long>()
	private val pendingSegments = ArrayDeque<Long>()
	private val pendingSegmentKeys = HashSet<Long>()
	private val unsplittableSegments = HashSet<Long>()
	private val sharpCorner = BooleanArray(inputPointCount)

	/** For a vertex added on a segment, the input segment it lies on, as a packed (low, high) pair. */
	private val segmentOfVertex = LongArray(store.vertexCapacity) { NO_SEGMENT }

	/** For each split piece of a segment, the input segment it came from; an unsplit segment is its own. */
	private val pieceOrigin = HashMap<Long, Long>()

	private var addedPointCount = 0
	private var interiorPointCount = 0
	private var offCenterCount = 0
	private var segmentSplitCount = 0
	private var boundarySplitCount = 0
	private var refusedBoundarySplitCount = 0
	private var stoppedByCap = false
	private var stoppedByBackstop = false

	/** Whether the last appended interior candidate is an off-center rather than a circumcenter. */
	private var candidateIsOffCenter = false

	/**
	 * Refines until no bad triangle is left that can be helped, or a limit stops it.
	 *
	 * @return RefinementStatistics What was done.
	 */
	fun run(): RefinementStatistics {
		markSharpCorners()

		for (triangle in 0 until store.triangleCount) {
			if (store.regions[triangle]) {
				considerTriangle(triangle)
			}
		}

		val operationLimit = BACKSTOP_OPERATIONS_PER_VERTEX * store.vertexCapacity + BACKSTOP_BASE_OPERATIONS
		var operations = 0L

		while (pendingSegments.isNotEmpty() || queue.isNotEmpty()) {
			if (addedPointCount >= options.maximumAddedPoints) {
				stoppedByCap = true

				break
			}

			operations++

			if (operations > operationLimit) {
				stoppedByBackstop = true

				break
			}

			if (pendingSegments.isNotEmpty()) {
				processSegment(pendingSegments.removeFirst())
			} else {
				processTriangle(queue.pop())
			}
		}

		return finish()
	}

	/**
	 * Tries to fix one queued triangle.
	 *
	 * @param Long key The triangle's packed triple.
	 */
	private fun processTriangle(key: Long) {
		queuedTriangles.remove(key)
		val first = triangleFirst(key)
		val second = triangleSecond(key)
		val third = triangleThird(key)
		val halfEdge = store.findHalfEdge(first, second)

		if (halfEdge == HalfEdgeTriangulation.NO_HALF_EDGE || store.origin[store.previous(halfEdge)] != third) {
			return
		}

		val triangle = halfEdge / 3

		if (!store.regions[triangle] || key in unfixableTriangles) {
			return
		}

		val corners = intArrayOf(first, second, third)
		measureCorners(corners)

		if (shape.smallestSineSquared >= targetSineSquared) {
			return
		}

		val apex = corners[shape.smallestCorner]
		val edgeStart = corners[(shape.smallestCorner + 1) % 3]
		val edgeEnd = corners[(shape.smallestCorner + 2) % 3]

		// An angle between two constraints is the input's; no vertex can widen it.
		if (isConstrained(edgeEnd, apex) && isConstrained(apex, edgeStart)) {
			giveUp(key)

			return
		}

		if (isShellTriangle(edgeStart, edgeEnd)) {
			giveUp(key)

			return
		}

		val candidate = appendSteinerPoint(apex, edgeStart, edgeEnd)

		if (candidate < 0) {
			giveUp(key)

			return
		}

		when (steiner.locate(triangle, candidate)) {
			SteinerInsertion.LOCATED_INSIDE -> {
				insertInterior(key, candidate, false, steiner.locatedAt)
			}

			SteinerInsertion.LOCATED_ON_EDGE -> {
				insertInterior(key, candidate, true, steiner.locatedAt)
			}

			SteinerInsertion.LOCATED_BLOCKED -> {
				store.removeLastVertex()
				requestSplits(key, longArrayOf(segmentKeyOf(steiner.locatedAt)))
			}

			else -> {
				store.removeLastVertex()
				giveUp(key)
			}
		}
	}

	/**
	 * Checks a located interior candidate's cavity and inserts it, or refuses it in favor of the
	 * segments it would encroach.
	 *
	 * @param Long    key       The triangle being fixed.
	 * @param Int     candidate The appended candidate.
	 * @param Boolean onEdge    Whether it lies on the unconstrained half-edge [at] rather than in the triangle [at].
	 * @param Int     at        Where the walk found it.
	 */
	private fun insertInterior(key: Long, candidate: Int, onEdge: Boolean, at: Int) {
		if (onEdge) {
			steiner.collectCavity(candidate, at / 3, store.twin[at] / 3)
		} else {
			steiner.collectCavity(candidate, at, -1)
		}

		if (steiner.nearestCavityDistanceSquared(candidate) < separationSquared) {
			store.removeLastVertex()
			giveUp(key)

			return
		}

		val encroached = ArrayList<Long>()
		val candidateX = store.pointX(candidate)
		val candidateY = store.pointY(candidate)

		for (position in 0 until steiner.cavityConstraints.size) {
			val halfEdge = steiner.cavityConstraints[position]
			val start = store.origin[halfEdge]
			val end = store.destination(halfEdge)

			if (liesInLens(store.pointX(start), store.pointY(start), store.pointX(end), store.pointY(end), candidateX, candidateY, lensCosine)) {
				encroached.add(segmentKeyOf(halfEdge))
			}
		}

		if (encroached.isNotEmpty()) {
			store.removeLastVertex()
			requestSplits(key, encroached.toLongArray())

			return
		}

		steiner.insert(candidate, onEdge, at)
		addedPointCount++
		interiorPointCount++

		if (candidateIsOffCenter) {
			offCenterCount++
		}

		afterEachStep?.invoke(store)
		considerStar(candidate)
	}

	/**
	 * Queues the segments a triangle's candidate needs split, and the triangle again after them; a
	 * triangle whose segments can no longer be split is given up on.
	 *
	 * @param Long      key      The triangle.
	 * @param LongArray segments The segments, as packed (low, high) pairs.
	 */
	private fun requestSplits(key: Long, segments: LongArray) {
		var awaitsSplit = false

		for (segment in segments) {
			if (segment in unsplittableSegments) {
				continue
			}

			if (pendingSegmentKeys.add(segment)) {
				pendingSegments.addLast(segment)
			}

			awaitsSplit = true
		}

		if (awaitsSplit) {
			requeue(key)
		} else {
			giveUp(key)
		}
	}

	/**
	 * Splits one queued segment, or marks it unsplittable.
	 *
	 * @param Long key The segment's packed (low, high) pair.
	 */
	private fun processSegment(key: Long) {
		pendingSegmentKeys.remove(key)

		if (key in unsplittableSegments) {
			return
		}

		val low = pairFirst(key)
		val high = pairSecond(key)
		val halfEdge = store.findHalfEdge(low, high)

		if (halfEdge == HalfEdgeTriangulation.NO_HALF_EDGE || !store.constrained[halfEdge]) {
			return
		}

		val lowX = store.pointX(low)
		val lowY = store.pointY(low)
		val highX = store.pointX(high)
		val highY = store.pointY(high)
		val length = sqrt(squaredDistance(lowX, lowY, highX, highY))
		val lowIsCorner = low < inputPointCount && sharpCorner[low]
		val highIsCorner = high < inputPointCount && sharpCorner[high]

		// The split's distance from the low end.
		val splitDistance =
			if (lowIsCorner != highIsCorner) {
				val shell = shellRadius(length)
				if (lowIsCorner) shell else length - shell
			} else {
				length / 2.0
			}

		if (minOf(splitDistance, length - splitDistance) < options.minimumSegmentLength) {
			unsplittableSegments.add(key)

			return
		}

		val fraction = splitDistance / length
		val targetX = lowX + fraction * (highX - lowX)
		val targetY = lowY + fraction * (highY - lowY)
		val isBoundary = store.boundaryCount[halfEdge] % 2 == 1
		val outwardIsLeft = if (isBoundary) !store.regions[halfEdge / 3] else null
		val check = if (isBoundary) options.boundarySplitCheck else null
		val vertex =
			steiner.splitSegment(low, high, targetX, targetY, outwardIsLeft) { candidate, cutsInside, nearestNeighborDistanceSquared ->
				approvesSplit(low, high, candidate, cutsInside, nearestNeighborDistanceSquared, check)
			}

		if (vertex == SteinerInsertion.NO_VERTEX) {
			unsplittableSegments.add(key)

			return
		}

		addedPointCount++
		segmentSplitCount++

		if (isBoundary) {
			boundarySplitCount++
		}

		val origin = pieceOrigin.remove(key) ?: key
		segmentOfVertex[vertex] = origin
		pieceOrigin[packVertexPair(low, vertex)] = origin
		pieceOrigin[packVertexPair(high, vertex)] = origin
		afterEachStep?.invoke(store)
		considerStar(vertex)
	}

	/**
	 * Whether a segment split's candidate may go in: far enough from every vertex it would join, and
	 * allowed by the caller's check when it splits a boundary edge.
	 *
	 * @param Int                 low                            The segment's low end.
	 * @param Int                 high                           Its high end.
	 * @param Int                 candidate                      The appended candidate.
	 * @param Boolean             cutsInside                     Whether it lies on the boundary's inside.
	 * @param Double              nearestNeighborDistanceSquared The squared distance to its nearest would-be neighbor.
	 * @param BoundarySplitCheck? check                          The caller's check, for a boundary edge.
	 * @return Boolean True to insert it.
	 */
	private fun approvesSplit(low: Int, high: Int, candidate: Int, cutsInside: Boolean, nearestNeighborDistanceSquared: Double, check: BoundarySplitCheck?): Boolean {
		if (nearestNeighborDistanceSquared < separationSquared) {
			return false
		}

		if (check == null) {
			return true
		}

		val accepted = check.accepts(store.pointX(low), store.pointY(low), store.pointX(high), store.pointY(high), store.pointX(candidate), store.pointY(candidate), cutsInside)

		if (!accepted) {
			refusedBoundarySplitCount++
		}

		return accepted
	}

	/**
	 * Appends the vertex that would fix a triangle: on the perpendicular bisector of its shortest edge,
	 * at the off-center or the nearer circumcenter, rounded to Float.
	 *
	 * @param Int apex      The corner opposite the shortest edge (the smallest angle).
	 * @param Int edgeStart The shortest edge's start, in the triangle's counterclockwise order.
	 * @param Int edgeEnd   Its end.
	 * @return Int The appended vertex, or -1 when the point is not a finite Float.
	 */
	private fun appendSteinerPoint(apex: Int, edgeStart: Int, edgeEnd: Int): Int {
		val startX = store.pointX(edgeStart)
		val startY = store.pointY(edgeStart)
		val endX = store.pointX(edgeEnd)
		val endY = store.pointY(edgeEnd)
		val apexX = store.pointX(apex)
		val apexY = store.pointY(apex)
		val alongX = endX - startX
		val alongY = endY - startY
		val toStartX = startX - apexX
		val toStartY = startY - apexY
		val toEndX = endX - apexX
		val toEndY = endY - apexY

		// The circumcenter sits cot(apex angle) / 2 edge lengths from the edge's midpoint, toward the apex.
		val circumcenterParameter = (toStartX * toEndX + toStartY * toEndY) / (toStartX * toEndY - toStartY * toEndX) / 2.0
		val useCircumcenter = steinerPoints == SteinerPointKind.Circumcenter || circumcenterParameter <= offCenterParameter
		val parameter = if (useCircumcenter) circumcenterParameter else offCenterParameter
		// The apex lies left of start -> end, so the left normal (-along y, along x) points at it.
		val pointX = ((startX + endX) / 2.0 - parameter * alongY).toFloat()
		val pointY = ((startY + endY) / 2.0 + parameter * alongX).toFloat()

		if (!pointX.isFinite() || !pointY.isFinite() || store.pointCount >= store.vertexCapacity) {
			return -1
		}

		candidateIsOffCenter = !useCircumcenter

		return store.appendVertex(pointX.toDouble(), pointY.toDouble())
	}

	/**
	 * Whether a triangle's short edge joins two vertices that split two different segments at the same
	 * distance from the corner the segments share: a triangle on matching concentric shells, which
	 * refinement can only make smaller, never better.
	 *
	 * @param Int edgeStart One end of the shortest edge.
	 * @param Int edgeEnd   The other end.
	 * @return Boolean True to leave the triangle.
	 */
	private fun isShellTriangle(edgeStart: Int, edgeEnd: Int): Boolean {
		val startSegment = segmentOfVertex[edgeStart]
		val endSegment = segmentOfVertex[edgeEnd]

		if (startSegment == NO_SEGMENT || endSegment == NO_SEGMENT || startSegment == endSegment) {
			return false
		}

		val corner = sharedEnd(startSegment, endSegment)

		if (corner < 0) {
			return false
		}

		val cornerX = store.pointX(corner)
		val cornerY = store.pointY(corner)
		val startDistance = squaredDistance(cornerX, cornerY, store.pointX(edgeStart), store.pointY(edgeStart))
		val endDistance = squaredDistance(cornerX, cornerY, store.pointX(edgeEnd), store.pointY(edgeEnd))

		return startDistance < (1.0 + SHELL_DISTANCE_TOLERANCE) * endDistance && startDistance > (1.0 - SHELL_DISTANCE_TOLERANCE) * endDistance
	}

	/**
	 * The vertex two segments share.
	 *
	 * @param Long first  One segment, packed (low, high).
	 * @param Long second The other.
	 * @return Int The shared end, or -1.
	 */
	private fun sharedEnd(first: Long, second: Long): Int {
		val firstLow = pairFirst(first)
		val firstHigh = pairSecond(first)
		val secondLow = pairFirst(second)
		val secondHigh = pairSecond(second)

		return when {
			firstLow == secondLow || firstLow == secondHigh -> firstLow
			firstHigh == secondLow || firstHigh == secondHigh -> firstHigh
			else -> -1
		}
	}

	/**
	 * Marks the input vertices where two constraints meet under 60 degrees.  Pieces of a split segment
	 * keep its direction, so the marks made once hold for the whole run.
	 */
	private fun markSharpCorners() {
		val neighbors = ArrayList<Int>()
		val sharpSineSquared = sineSquaredOfDegrees(SHARP_CORNER_DEGREES)
		val sharpCosineSquared = 1.0 - sharpSineSquared

		for (vertex in 0 until inputPointCount) {
			val first = store.vertexHalfEdge[vertex]

			if (first == HalfEdgeTriangulation.NO_HALF_EDGE) {
				continue
			}

			neighbors.clear()
			var spoke = first

			do {
				val neighbor = store.destination(spoke)

				if (store.constrained[spoke] && neighbor != store.ghostVertex) {
					neighbors.add(neighbor)
				}

				spoke = store.twin[store.previous(spoke)]
			} while (spoke != first)

			sharpCorner[vertex] = hasNarrowPair(vertex, neighbors, sharpCosineSquared)
		}
	}

	/**
	 * Whether any two of a vertex's constrained neighbors lie under the sharp-corner angle apart.
	 *
	 * @param Int       vertex             The vertex.
	 * @param List<Int> neighbors          Its constrained neighbors.
	 * @param Double    sharpCosineSquared cos^2 of the sharp-corner angle.
	 * @return Boolean True for a sharp corner.
	 */
	private fun hasNarrowPair(vertex: Int, neighbors: List<Int>, sharpCosineSquared: Double): Boolean {
		val vertexX = store.pointX(vertex)
		val vertexY = store.pointY(vertex)

		for (firstPosition in neighbors.indices) {
			val firstX = store.pointX(neighbors[firstPosition]) - vertexX
			val firstY = store.pointY(neighbors[firstPosition]) - vertexY

			for (secondPosition in firstPosition + 1 until neighbors.size) {
				val secondX = store.pointX(neighbors[secondPosition]) - vertexX
				val secondY = store.pointY(neighbors[secondPosition]) - vertexY
				val dot = firstX * secondX + firstY * secondY

				if (dot > 0.0 && dot * dot > sharpCosineSquared * (firstX * firstX + firstY * firstY) * (secondX * secondX + secondY * secondY)) {
					return true
				}
			}
		}

		return false
	}

	/**
	 * Queues every bad inside triangle around a new vertex - every triangle the insertion created.
	 *
	 * @param Int vertex The vertex.
	 */
	private fun considerStar(vertex: Int) {
		val first = store.vertexHalfEdge[vertex]
		var spoke = first

		do {
			val triangle = spoke / 3

			if (store.regions[triangle]) {
				considerTriangle(triangle)
			}

			spoke = store.twin[store.previous(spoke)]
		} while (spoke != first)
	}

	/**
	 * Queues one inside triangle when its smallest angle is below the target.
	 *
	 * @param Int triangle The triangle slot.
	 */
	private fun considerTriangle(triangle: Int) {
		val base = 3 * triangle
		val corners = intArrayOf(store.origin[base], store.origin[base + 1], store.origin[base + 2])
		measureCorners(corners)

		if (shape.smallestSineSquared < targetSineSquared) {
			val key = packTriangle(corners[0], corners[1], corners[2])

			if (key !in unfixableTriangles && queuedTriangles.add(key)) {
				queue.push(shape.smallestSineSquared, key)
			}
		}
	}

	/**
	 * Queues a triangle again, measured as it stands.
	 *
	 * @param Long key The triangle.
	 */
	private fun requeue(key: Long) {
		if (queuedTriangles.add(key)) {
			measureCorners(intArrayOf(triangleFirst(key), triangleSecond(key), triangleThird(key)))
			queue.push(shape.smallestSineSquared, key)
		}
	}

	/**
	 * Gives up on a triangle; it is counted if it survives to the end.
	 *
	 * @param Long key The triangle.
	 */
	private fun giveUp(key: Long) {
		unfixableTriangles.add(key)
	}

	/**
	 * Measures a triangle given by its corners, in counterclockwise order, into [shape].
	 *
	 * @param IntArray corners The three corners.
	 */
	private fun measureCorners(corners: IntArray) {
		shape.measure(
			store.pointX(corners[0]),
			store.pointY(corners[0]),
			store.pointX(corners[1]),
			store.pointY(corners[1]),
			store.pointX(corners[2]),
			store.pointY(corners[2]),
		)
	}

	/**
	 * Whether the edge between two vertices is a constraint.
	 *
	 * @param Int start One end.
	 * @param Int end   The other.
	 * @return Boolean True when the edge exists and is constrained.
	 */
	private fun isConstrained(start: Int, end: Int): Boolean {
		val halfEdge = store.findHalfEdge(start, end)

		return halfEdge != HalfEdgeTriangulation.NO_HALF_EDGE && store.constrained[halfEdge]
	}

	/**
	 * A half-edge's edge as a packed (low, high) pair.
	 *
	 * @param Int halfEdge The half-edge.
	 * @return Long The key.
	 */
	private fun segmentKeyOf(halfEdge: Int): Long {
		val start = store.origin[halfEdge]
		val end = store.destination(halfEdge)

		return packVertexPair(minOf(start, end), maxOf(start, end))
	}

	/**
	 * Counts what is left below the target and gathers the statistics.
	 *
	 * @return RefinementStatistics The run's statistics.
	 */
	private fun finish(): RefinementStatistics {
		var underTargetCount = 0
		var unfixableCount = 0

		for (triangle in 0 until store.triangleCount) {
			if (!store.regions[triangle]) {
				continue
			}

			val base = 3 * triangle
			val corners = intArrayOf(store.origin[base], store.origin[base + 1], store.origin[base + 2])
			measureCorners(corners)

			if (shape.smallestSineSquared < targetSineSquared) {
				underTargetCount++

				if (packTriangle(corners[0], corners[1], corners[2]) in unfixableTriangles) {
					unfixableCount++
				}
			}
		}

		return RefinementStatistics(
			addedPointCount = addedPointCount,
			interiorPointCount = interiorPointCount,
			offCenterCount = offCenterCount,
			segmentSplitCount = segmentSplitCount,
			boundarySplitCount = boundarySplitCount,
			refusedBoundarySplitCount = refusedBoundarySplitCount,
			underTargetTriangleCount = underTargetCount,
			unfixableTriangleCount = unfixableCount,
			stoppedByCap = stoppedByCap,
			stoppedByBackstop = stoppedByBackstop,
		)
	}

	/**
	 * The power-of-two distance from a sharp corner that splits a segment of this length most evenly:
	 * between a third and two thirds of it.
	 *
	 * @param Double length The segment's length.
	 * @return Double The shell radius.
	 */
	private fun shellRadius(length: Double): Double {
		var radius = 1.0

		while (length > 3.0 * radius) {
			radius *= 2.0
		}

		while (length < 1.5 * radius) {
			radius *= 0.5
		}

		return radius
	}
}
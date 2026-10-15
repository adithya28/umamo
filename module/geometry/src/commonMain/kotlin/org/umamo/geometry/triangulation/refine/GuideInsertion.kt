package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.packVertexPair
import org.umamo.geometry.pairFirst
import org.umamo.geometry.pairSecond
import org.umamo.geometry.predicate.PredicateScratch
import org.umamo.geometry.predicate.orient2d
import org.umamo.geometry.triangulation.ConstraintInserter
import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import kotlin.math.sqrt

/*
 * Greedy insertion of a guide into a classified triangulation - Garland and Heckbert's greedy insertion
 * turned around: rather than approximating a height field from scratch, the triangulation already
 * covers the plane and gains, one step at a time, what holds the guide where its linear interpolation
 * strays furthest, until nothing strays past the tolerance or the share of the cap runs out.
 *
 * Two piecewise-linear surfaces over one plane differ linearly inside each cell of their overlay, so
 * they part furthest at an overlay vertex: a guide vertex, or a point where a guide edge crosses a
 * triangulation edge (the triangulation's own vertices carry the guide's values and part by nothing).
 * Both are checked exactly, inside the domain: every candidate vertex where it lies, and every guide
 * edge at each of its crossings, found by walking the edge's straight line through the triangulation.
 *
 * A vertex that strays is inserted at its exact Float point.  An edge that strays is held as a chain of
 * constraints through vertices along it: its ends go in first; from an end in the domain toward one
 * outside it, the chain follows the edge to where it leaves the domain; any constraint across the edge
 * splits where the edge crosses it and joins the chain; then each piece becomes a constraint.  The
 * triangulation then carries the guide's crease, no crossing is left, and no later flip can take it
 * away.  An edge with both ends outside the domain grows its chain both ways from its worst crossing;
 * one whose chain is whole and still strays gains a vertex at its worst crossing.  A boundary edge
 * splits only where the caller's check allows; a constraint with no room for the split gives way when
 * it bounds nothing (an inner segment) and holds no other guide edge's crease, and otherwise stops the
 * chain - two creases that cross where neither can split (a folded guide) keep the first held, the
 * other goes without.  An operation backstop bounds the run besides.
 *
 * An error is the largest, over the guide's fields, of the distance between the guide's value at a
 * point and the triangulation's interpolation there of the guide's values at its own vertices, divided
 * by the field's scale.  A vertex the guide inserts carries the guide's exact values; any other vertex
 * carries the guide's interpolation at its point, so a vertex's values never change, and a candidate's
 * error changes only when a triangle it was measured in goes.  The worst candidate goes next, ties to
 * the lowest.  A candidate that can be held no way at all is left out.
 */

/** A vertex candidate's triangle when it has none: outside the domain, already in place, or left out. */
private const val NO_TRIANGLE: Long = -1L

/** An edge candidate's error when it is left out or never meets the domain. */
private const val OUT_OF_PLAY: Double = -1.0

/** A guide vertex not yet found among the triangulation's vertices. */
private const val ABSENT: Int = -1

/** A guide vertex whose presence has not been looked up. */
private const val UNKNOWN: Int = -2

/** No store vertex. */
private const val NO_STORE_VERTEX: Int = -1

/** What [GuideInsertion.leaveVertex] returns when the line leaves through no triangle. */
private const val NO_WAY_OUT: Int = -1

/**
 * One greedy insertion over a classified store.
 *
 * @property HalfEdgeTriangulation             store              The store, regions adopted.
 * @property SteinerInsertion                  steiner            The store's insertion mechanics.
 * @property PiecewiseLinearGuide              guide              The guide.
 * @property Double                            tolerance          The largest error left alone, in the plane's units.
 * @property Double                            separationSquared  The squared separation floor.
 * @property BoundarySplitCheck?               boundarySplitCheck Decides every boundary edge split, or null to allow them all.
 * @property ((HalfEdgeTriangulation) -> Unit)? afterEachStep      Called after every inserted vertex or constraint.
 */
internal class GuideInsertion(
	private val store: HalfEdgeTriangulation,
	private val steiner: SteinerInsertion,
	private val guide: PiecewiseLinearGuide,
	private val tolerance: Double,
	private val separationSquared: Double,
	private val boundarySplitCheck: BoundarySplitCheck?,
	private val afterEachStep: ((HalfEdgeTriangulation) -> Unit)?,
) {
	private val valueCount = 2 * guide.fieldCount
	private val vertexValues = DoubleArray(store.vertexCapacity * valueCount)
	private val vertexValuesKnown = BooleanArray(store.vertexCapacity)
	private val pointValues = DoubleArray(valueCount)
	private val endValues = DoubleArray(valueCount)
	private val inserter = ConstraintInserter(store)
	private val scratch = PredicateScratch()

	/** The guide's candidate vertices, each with the triangle it was measured in and its error there. */
	private val vertexCandidateCount = guide.candidates.size
	private val vertexTriangle = LongArray(vertexCandidateCount) { NO_TRIANGLE }
	private val vertexError = DoubleArray(vertexCandidateCount)

	/**
	 * Every guide edge as a packed (low, high) pair, each with its error, the triangles its trace entered,
	 * and where its worst crossing lies: the point, on the crossed edge, and that edge's ends.
	 */
	private val guideEdges: LongArray = uniqueEdgesOf(guide.triangles)
	private val edgeError = DoubleArray(guideEdges.size) { OUT_OF_PLAY }
	private val edgeTriangles = arrayOfNulls<LongArray>(guideEdges.size)
	private val edgeWorstX = DoubleArray(guideEdges.size)
	private val edgeWorstY = DoubleArray(guideEdges.size)
	private val edgeWorstCrossed = LongArray(guideEdges.size)

	/**
	 * Per guide edge being held, the store vertices along it from its low end toward its high end, and
	 * whether each side is final: at the end's own vertex, or where the edge leaves the domain.
	 */
	private val edgeChain = arrayOfNulls<IntArray>(guideEdges.size)
	private val lowSideFinal = BooleanArray(guideEdges.size)
	private val highSideFinal = BooleanArray(guideEdges.size)

	/** Per guide vertex, the store vertex at its point, [ABSENT], or [UNKNOWN]. */
	private val storeVertexOfGuide = IntArray(guide.points.size / 2) { UNKNOWN }

	/** Per guide vertex, whether inserting it was refused, so it is not tried again. */
	private val insertionRefused = BooleanArray(guide.points.size / 2)

	/** The chain pieces made constraints, as packed (low, high) pairs, each split piece replaced by its halves. */
	private val heldPieces = HashSet<Long>()
	private val operationLimit = BACKSTOP_OPERATIONS_PER_VERTEX * store.vertexCapacity + BACKSTOP_BASE_OPERATIONS
	private var operations = 0L

	/** What the last obstacle walk met: a constrained half-edge, or a vertex on its line. */
	private var metHalfEdge = HalfEdgeTriangulation.NO_HALF_EDGE
	private var metVertex = NO_STORE_VERTEX

	/** The triangle the next locate starts from. */
	private var walkStart = 0

	/** The line the current trace follows. */
	private var lineStartX = 0.0
	private var lineStartY = 0.0
	private var lineEndX = 0.0
	private var lineEndY = 0.0

	/** The largest error the current trace has met, or [OUT_OF_PLAY] before it enters the domain, and where. */
	private var traceError = OUT_OF_PLAY
	private var traceWorstX = 0.0
	private var traceWorstY = 0.0
	private var traceWorstCrossed = 0L

	/** The triangles the current trace entered. */
	private var traced = LongArray(16)
	private var tracedCount = 0

	/** The point [intersect] found. */
	private var crossingX = 0.0
	private var crossingY = 0.0

	/** The vertices inserted: guide vertices and crossing points. */
	var insertedCount: Int = 0
		private set

	/** The vertices inserted at a guide edge's crossing. */
	var crossingPointCount: Int = 0
		private set

	/** The constraints no boundary runs along that gave way to a crease. */
	var releasedConstraintCount: Int = 0
		private set

	/** The guide edges made constraints. */
	var constrainedEdgeCount: Int = 0
		private set

	/** The largest error any candidate kept, once [run] returns. */
	var largestRemainingError: Double = 0.0
		private set

	/** Whether the cap stopped insertion with a candidate still past the tolerance. */
	var stoppedByCap: Boolean = false
		private set

	/** Whether the operation backstop stopped insertion, which only a defect reaches. */
	var stoppedByBackstop: Boolean = false
		private set

	/**
	 * Holds the worst candidate in place while it strays past the tolerance and the cap allows.
	 *
	 * @param Int cap The most vertices to insert.
	 */
	fun run(cap: Int) {
		scoreEveryCandidate()

		while (true) {
			val worst = worstCandidate()
			val error = if (worst < 0) 0.0 else errorOfCandidate(worst)
			largestRemainingError = error

			if (worst < 0 || error <= tolerance) {
				return
			}

			operations++

			if (operations > operationLimit) {
				stoppedByBackstop = true

				return
			}

			if (holdCandidate(worst, cap)) {
				afterEachStep?.invoke(store)
				refresh()

				continue
			}

			if (stoppedByCap) {
				return
			}

			leaveOut(worst)
		}
	}

	/**
	 * Whether the triangulation strays from the guide past the tolerance anywhere, inserting nothing;
	 * [run] may follow on the same insertion, after the store changed.
	 *
	 * @return Boolean True when holding the guide would change something.
	 */
	fun strays(): Boolean {
		scoreEveryCandidate()
		val worst = worstCandidate()

		return worst >= 0 && errorOfCandidate(worst) > tolerance
	}

	/**
	 * Measures every vertex candidate where it lies and traces every edge, forgetting what an earlier
	 * scoring found: the store may have changed since.
	 */
	private fun scoreEveryCandidate() {
		vertexTriangle.fill(NO_TRIANGLE)
		storeVertexOfGuide.fill(UNKNOWN)

		for (candidate in 0 until vertexCandidateCount) {
			scoreVertex(candidate)
		}

		for (edge in guideEdges.indices) {
			traceEdge(edge)
		}
	}

	/**
	 * The in-play candidate with the largest error, ties to the lowest: the vertex candidates first, then
	 * the edges.
	 *
	 * @return Int The candidate, or -1 when none is in play.
	 */
	private fun worstCandidate(): Int {
		var worst = -1
		var worstError = OUT_OF_PLAY

		for (candidate in 0 until vertexCandidateCount + guideEdges.size) {
			val error = errorOfCandidate(candidate)

			if (error > worstError) {
				worst = candidate
				worstError = error
			}
		}

		return worst
	}

	/**
	 * A candidate's current error.
	 *
	 * @param Int candidate The candidate: a vertex below the vertex candidate count, an edge from it on.
	 * @return Double The error, or [OUT_OF_PLAY].
	 */
	private fun errorOfCandidate(candidate: Int): Double {
		if (candidate < vertexCandidateCount) {
			return if (vertexTriangle[candidate] == NO_TRIANGLE) OUT_OF_PLAY else vertexError[candidate]
		}

		return edgeError[candidate - vertexCandidateCount]
	}

	/**
	 * Holds one straying candidate: a vertex is inserted; an edge takes one step toward its chain of
	 * constraints (see [holdEdge]).
	 *
	 * @param Int candidate The candidate.
	 * @param Int cap       The most vertices to insert.
	 * @return Boolean True when something changed; false when the candidate cannot be held (or the cap stops it).
	 */
	private fun holdCandidate(candidate: Int, cap: Int): Boolean {
		if (candidate < vertexCandidateCount) {
			return insertGuideVertex(guide.candidates[candidate], cap)
		}

		return holdEdge(candidate - vertexCandidateCount, cap)
	}

	/**
	 * One step toward holding a guide edge as a chain of constraints through store vertices along it:
	 * its ends first; then, from each end in the domain toward one outside it, the constraints the edge
	 * crosses until it leaves the domain, each split where the edge crosses it; then each piece of the
	 * chain as a constraint, splitting any constraint in its way.  When the chain is whole and the edge
	 * still strays, or no chain can start, a vertex goes in at its worst crossing.
	 *
	 * @param Int edge The edge's position in [guideEdges].
	 * @param Int cap  The most vertices to insert.
	 * @return Boolean True when the store changed.
	 */
	private fun holdEdge(edge: Int, cap: Int): Boolean {
		val low = pairFirst(guideEdges[edge])
		val high = pairSecond(guideEdges[edge])

		for (end in intArrayOf(low, high)) {
			if (presentVertexOf(end) == ABSENT && !insertionRefused[end]) {
				if (insertGuideVertex(end, cap)) {
					return true
				}

				if (stoppedByCap) {
					return false
				}

				insertionRefused[end] = true
			}
		}

		if (edgeChain[edge] == null) {
			val lowVertex = presentVertexOf(low)
			val highVertex = presentVertexOf(high)

			if (lowVertex == ABSENT && highVertex == ABSENT) {
				// The edge runs through the domain with both ends outside it: the chain grows both ways
				// from its worst crossing.
				val seed = insertAtWorstCrossing(edge, cap)

				if (seed == SteinerInsertion.NO_VERTEX) {
					return false
				}

				edgeChain[edge] = intArrayOf(seed)

				return true
			}

			edgeChain[edge] = listOf(lowVertex, highVertex).filter { vertex -> vertex != ABSENT }.toIntArray()
			lowSideFinal[edge] = lowVertex != ABSENT
			highSideFinal[edge] = highVertex != ABSENT
		}

		if (!lowSideFinal[edge] && extendChain(edge, low, towardLow = true, cap)) {
			return true
		}

		if (!highSideFinal[edge] && extendChain(edge, high, towardLow = false, cap)) {
			return true
		}

		if (stoppedByCap) {
			return false
		}

		var position = 0

		while (position < edgeChain[edge]!!.size - 1) {
			val chain = edgeChain[edge]!!
			val start = chain[position]
			val finish = chain[position + 1]
			val halfEdge = store.findHalfEdge(start, finish)

			if (halfEdge != HalfEdgeTriangulation.NO_HALF_EDGE && store.constrained[halfEdge]) {
				position++

				continue
			}

			if (inserter.insert(start, finish, false)) {
				constrainedEdgeCount++
				holdInsertedChain()

				return true
			}

			// A constraint lies across the piece: it splits where the piece crosses it and the split joins
			// the chain, or with no room for the split, a constraint that bounds nothing gives way.
			val blocking = store.findHalfEdge(inserter.crossedStart, inserter.crossedEnd)
			intersect(store.pointX(start), store.pointY(start), store.pointX(finish), store.pointY(finish), blocking)
			val split = splitConstraintWithin(blocking, cap)

			if (split == SteinerInsertion.NO_VERTEX) {
				return !stoppedByCap && releaseIfNeutral(blocking)
			}

			edgeChain[edge] = chain.copyOfRange(0, position + 1) + split + chain.copyOfRange(position + 1, chain.size)

			return true
		}

		return insertAtWorstCrossing(edge, cap) != SteinerInsertion.NO_VERTEX
	}

	/**
	 * Releases a constraint no boundary ring runs along and no crease holds, so a crease can cross it.
	 * A crease never gives way to another: two that cross where neither can split would otherwise
	 * release each other without end.
	 *
	 * @param Int halfEdge The constrained half-edge.
	 * @return Boolean True when released; false for a boundary or a held crease.
	 */
	private fun releaseIfNeutral(halfEdge: Int): Boolean {
		if (store.boundaryCount[halfEdge] != 0 || pieceKeyOf(store.origin[halfEdge], store.destination(halfEdge)) in heldPieces) {
			return false
		}

		inserter.release(halfEdge)
		releasedConstraintCount++

		return true
	}

	/** Notes every piece the last constraint insertion marked, so none of them gives way to another crease. */
	private fun holdInsertedChain() {
		val chain = inserter.insertedChain

		for (position in 0 until chain.size - 1) {
			heldPieces.add(pieceKeyOf(chain[position], chain[position + 1]))
		}
	}

	/**
	 * A constrained edge as a packed (low, high) pair.
	 *
	 * @param Int start One end.
	 * @param Int end   The other.
	 * @return Long The key.
	 */
	private fun pieceKeyOf(start: Int, end: Int): Long = packVertexPair(minOf(start, end), maxOf(start, end))

	/**
	 * Extends a chain from its end toward a guide end the domain lacks: along the edge's line through
	 * any vertices on it, until the line crosses a constraint, which splits there - the side is final
	 * when that constraint bounds the domain, or when the line reaches the end's point without one.
	 *
	 * @param Int     edge      The edge's position in [guideEdges].
	 * @param Int     target    The guide end the domain lacks.
	 * @param Boolean towardLow Whether the target is the edge's low end (the chain's front).
	 * @param Int     cap       The most vertices to insert.
	 * @return Boolean True when the store changed.
	 */
	private fun extendChain(edge: Int, target: Int, towardLow: Boolean, cap: Int): Boolean {
		val targetX = guide.points[2 * target].toDouble()
		val targetY = guide.points[2 * target + 1].toDouble()

		while (true) {
			val chain = edgeChain[edge]!!
			val from = if (towardLow) chain.first() else chain.last()

			if (walkToward(from, targetX, targetY)) {
				// The end's point lies in the domain, but its vertex could not go in: the chain ends short.
				finishSide(edge, towardLow)

				return false
			}

			if (metVertex != NO_STORE_VERTEX && metVertex !in chain) {
				addToChain(edge, metVertex, towardLow)

				continue
			}

			if (metHalfEdge == HalfEdgeTriangulation.NO_HALF_EDGE) {
				finishSide(edge, towardLow)

				return false
			}

			val boundsDomain = store.boundaryCount[metHalfEdge] % 2 == 1
			intersect(lineStartX, lineStartY, lineEndX, lineEndY, metHalfEdge)
			val split = splitConstraintWithin(metHalfEdge, cap)

			if (stoppedByCap) {
				return false
			}

			// With no room for the split, a constraint that bounds nothing gives way.
			if (split == SteinerInsertion.NO_VERTEX) {
				if (releaseIfNeutral(metHalfEdge)) {
					return true
				}

				finishSide(edge, towardLow)

				return false
			}

			addToChain(edge, split, towardLow)

			if (boundsDomain) {
				finishSide(edge, towardLow)
			}

			return true
		}
	}

	/**
	 * Adds a vertex at one end of a chain.
	 *
	 * @param Int     edge      The edge's position in [guideEdges].
	 * @param Int     vertex    The store vertex.
	 * @param Boolean towardLow Whether it goes at the low end (the front).
	 */
	private fun addToChain(edge: Int, vertex: Int, towardLow: Boolean) {
		val chain = edgeChain[edge]!!
		edgeChain[edge] = if (towardLow) intArrayOf(vertex) + chain else chain + vertex
	}

	/**
	 * Marks one side of a chain final.
	 *
	 * @param Int     edge      The edge's position in [guideEdges].
	 * @param Boolean towardLow Whether it is the low side.
	 */
	private fun finishSide(edge: Int, towardLow: Boolean) {
		if (towardLow) {
			lowSideFinal[edge] = true
		} else {
			highSideFinal[edge] = true
		}
	}

	/**
	 * Splits a constraint at the point [intersect] found, counting the split against the cap.
	 *
	 * @param Int halfEdge The constrained half-edge.
	 * @param Int cap      The most vertices to insert.
	 * @return Int The new vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun splitConstraintWithin(halfEdge: Int, cap: Int): Int {
		if (insertedCount >= cap) {
			stoppedByCap = true

			return SteinerInsertion.NO_VERTEX
		}

		val vertex = splitConstraint(halfEdge, crossingX, crossingY)

		if (vertex != SteinerInsertion.NO_VERTEX) {
			insertedCount++
			crossingPointCount++
			walkStart = store.vertexHalfEdge[vertex] / 3
		}

		return vertex
	}

	/**
	 * Inserts a vertex where a guide edge strays furthest, on the edge it crosses there: a constrained
	 * edge is split (a boundary edge only where the caller's check allows), any other gains the point
	 * like any vertex.  Its values are the guide's at its Float point.
	 *
	 * @param Int edge The edge's position in [guideEdges].
	 * @param Int cap  The most vertices to insert.
	 * @return Int The new vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun insertAtWorstCrossing(edge: Int, cap: Int): Int {
		if (insertedCount >= cap) {
			stoppedByCap = true

			return SteinerInsertion.NO_VERTEX
		}

		val origin = pairFirst(edgeWorstCrossed[edge])
		val destination = pairSecond(edgeWorstCrossed[edge])
		val halfEdge = store.findHalfEdge(origin, destination)

		if (halfEdge == HalfEdgeTriangulation.NO_HALF_EDGE) {
			return SteinerInsertion.NO_VERTEX
		}

		val vertex =
			if (store.constrained[halfEdge]) {
				splitConstraint(halfEdge, edgeWorstX[edge], edgeWorstY[edge])
			} else {
				insertPoint(edgeWorstX[edge].toFloat().toDouble(), edgeWorstY[edge].toFloat().toDouble())
			}

		if (vertex != SteinerInsertion.NO_VERTEX) {
			insertedCount++
			crossingPointCount++
			walkStart = store.vertexHalfEdge[vertex] / 3
		}

		return vertex
	}

	/**
	 * Inserts one guide vertex at its exact point, unless it lies outside the domain or too close to a
	 * vertex it would join; on a constraint, it splits the constraint there.
	 *
	 * @param Int guideVertex The guide vertex.
	 * @param Int cap         The most vertices to insert.
	 * @return Boolean True when inserted.
	 */
	private fun insertGuideVertex(guideVertex: Int, cap: Int): Boolean {
		if (insertedCount >= cap) {
			stoppedByCap = true

			return false
		}

		val x = guide.points[2 * guideVertex].toDouble()
		val y = guide.points[2 * guideVertex + 1].toDouble()
		val vertex = insertPoint(x, y)

		if (vertex == SteinerInsertion.NO_VERTEX) {
			storeVertexOfGuide[guideVertex] = ABSENT

			return false
		}

		insertedCount++
		walkStart = store.vertexHalfEdge[vertex] / 3

		// A split that could not take the point itself took a Float neighbor of it, which carries the
		// guide's interpolation like any other vertex; the guide vertex stays absent.
		if (store.pointX(vertex) == x && store.pointY(vertex) == y) {
			guide.valuesAtVertex(guideVertex, pointValues)
			pointValues.copyInto(vertexValues, vertex * valueCount)
			vertexValuesKnown[vertex] = true
			storeVertexOfGuide[guideVertex] = vertex
		} else {
			storeVertexOfGuide[guideVertex] = ABSENT
		}

		return true
	}

	/**
	 * Inserts a vertex at a point in the domain's closure, unless it is too close to a vertex it would
	 * join; a point on a constraint splits it there (see [splitConstraint]).
	 *
	 * @param Double x The point's x, a Float value.
	 * @param Double y The point's y, a Float value.
	 * @return Int The new vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun insertPoint(x: Double, y: Double): Int {
		val triangle = locate(x, y)

		if (triangle < 0) {
			return SteinerInsertion.NO_VERTEX
		}

		var onEdge = HalfEdgeTriangulation.NO_HALF_EDGE
		var onEdgeCount = 0

		for (offset in 0 until 3) {
			val edge = 3 * triangle + offset

			if (store.orientAt(x, y, store.origin[edge], store.destination(edge)) == 0.0) {
				onEdge = edge
				onEdgeCount++
			}
		}

		if (onEdgeCount >= 2) {
			// The point is a vertex already.
			return SteinerInsertion.NO_VERTEX
		}

		if (onEdge != HalfEdgeTriangulation.NO_HALF_EDGE && store.constrained[onEdge]) {
			val across = store.twin[onEdge] / 3
			val inDomain = store.regions[triangle] || (!store.isGhostTriangle(across) && store.regions[across])

			return if (inDomain) splitConstraint(onEdge, x, y) else SteinerInsertion.NO_VERTEX
		}

		if (!store.regions[triangle]) {
			return SteinerInsertion.NO_VERTEX
		}

		val vertex = store.appendVertex(x, y)

		if (onEdge == HalfEdgeTriangulation.NO_HALF_EDGE) {
			steiner.collectCavity(vertex, triangle, -1)
		} else {
			steiner.collectCavity(vertex, onEdge / 3, store.twin[onEdge] / 3)
		}

		if (steiner.nearestCavityDistanceSquared(vertex) < separationSquared) {
			store.removeLastVertex()

			return SteinerInsertion.NO_VERTEX
		}

		if (onEdge == HalfEdgeTriangulation.NO_HALF_EDGE) {
			steiner.insert(vertex, false, triangle)
		} else {
			steiner.insert(vertex, true, onEdge)
		}

		return vertex
	}

	/**
	 * Splits a constraint near a point on or beside it, keeping clear of every vertex the split would
	 * join; a boundary edge splits only where the caller's check allows.
	 *
	 * @param Int    halfEdge The constrained half-edge.
	 * @param Double x        The point's x.
	 * @param Double y        The point's y.
	 * @return Int The new vertex, or [SteinerInsertion.NO_VERTEX].
	 */
	private fun splitConstraint(halfEdge: Int, x: Double, y: Double): Int {
		val origin = store.origin[halfEdge]
		val destination = store.destination(halfEdge)
		val isBoundary = store.boundaryCount[halfEdge] % 2 == 1
		val outwardIsLeft = if (isBoundary) !store.regions[halfEdge / 3] else null
		val check = if (isBoundary) boundarySplitCheck else null
		val vertex =
			steiner.splitSegment(origin, destination, x, y, outwardIsLeft) { candidate, cutsInside, nearestNeighborDistanceSquared ->
				nearestNeighborDistanceSquared >= separationSquared &&
					(check == null || check.accepts(store.pointX(origin), store.pointY(origin), store.pointX(destination), store.pointY(destination), store.pointX(candidate), store.pointY(candidate), cutsInside))
			}

		// A held crease stays held through its halves.
		if (vertex != SteinerInsertion.NO_VERTEX && heldPieces.remove(pieceKeyOf(origin, destination))) {
			heldPieces.add(pieceKeyOf(origin, vertex))
			heldPieces.add(pieceKeyOf(vertex, destination))
		}

		return vertex
	}

	/** After a change: measures again every vertex candidate whose triangle went, and traces again every edge that entered one that went. */
	private fun refresh() {
		for (candidate in 0 until vertexCandidateCount) {
			val key = vertexTriangle[candidate]

			if (key != NO_TRIANGLE && !triangleExists(key)) {
				vertexTriangle[candidate] = NO_TRIANGLE
				scoreVertex(candidate)
			}
		}

		for (edge in guideEdges.indices) {
			val entered = edgeTriangles[edge] ?: continue

			if (entered.any { key -> !triangleExists(key) }) {
				traceEdge(edge)
			}
		}
	}

	/**
	 * Locates a vertex candidate and records its triangle and error; a candidate already a vertex, or
	 * outside the domain, is out of play.
	 *
	 * @param Int candidate The vertex candidate.
	 */
	private fun scoreVertex(candidate: Int) {
		val guideVertex = guide.candidates[candidate]
		val x = guide.points[2 * guideVertex].toDouble()
		val y = guide.points[2 * guideVertex + 1].toDouble()
		val triangle = locate(x, y)

		if (triangle < 0 || !store.regions[triangle]) {
			return
		}

		val base = 3 * triangle

		for (offset in 0 until 3) {
			val corner = store.origin[base + offset]

			if (store.pointX(corner) == x && store.pointY(corner) == y) {
				storeVertexOfGuide[guideVertex] = corner

				return
			}
		}

		val key = packTriangle(store.origin[base], store.origin[base + 1], store.origin[base + 2])
		vertexTriangle[candidate] = key
		guide.valuesAtVertex(guideVertex, pointValues)
		vertexError[candidate] = errorInTriangle(key, x, y)
	}

	/**
	 * The error at a point inside a triangle, against the guide's values there, already in [pointValues].
	 *
	 * @param Long   key The triangle.
	 * @param Double x   The point's x.
	 * @param Double y   The point's y.
	 * @return Double The error.
	 */
	private fun errorInTriangle(key: Long, x: Double, y: Double): Double {
		val first = triangleFirst(key)
		val second = triangleSecond(key)
		val third = triangleThird(key)
		val firstX = store.pointX(first)
		val firstY = store.pointY(first)
		val secondX = store.pointX(second) - firstX
		val secondY = store.pointY(second) - firstY
		val thirdX = store.pointX(third) - firstX
		val thirdY = store.pointY(third) - firstY
		val determinant = secondX * thirdY - secondY * thirdX
		val secondWeight = ((x - firstX) * thirdY - (y - firstY) * thirdX) / determinant
		val thirdWeight = (secondX * (y - firstY) - secondY * (x - firstX)) / determinant
		val firstWeight = 1.0 - secondWeight - thirdWeight
		val firstValues = valuesOfVertex(first) * valueCount
		val secondValues = valuesOfVertex(second) * valueCount
		val thirdValues = valuesOfVertex(third) * valueCount
		var largest = 0.0

		for (field in 0 until guide.fieldCount) {
			val component = 2 * field
			val interpolatedX = firstWeight * vertexValues[firstValues + component] + secondWeight * vertexValues[secondValues + component] + thirdWeight * vertexValues[thirdValues + component]
			val interpolatedY = firstWeight * vertexValues[firstValues + component + 1] + secondWeight * vertexValues[secondValues + component + 1] + thirdWeight * vertexValues[thirdValues + component + 1]
			val deltaX = pointValues[component] - interpolatedX
			val deltaY = pointValues[component + 1] - interpolatedY
			largest = maxOf(largest, sqrt(deltaX * deltaX + deltaY * deltaY) / guide.fieldScales[field])
		}

		return largest
	}

	/**
	 * Traces one guide edge through the triangulation, from its low end toward its high end, and records
	 * its largest error at a crossing in the domain and the triangles it entered.
	 *
	 * @param Int edge The edge's position in [guideEdges].
	 */
	private fun traceEdge(edge: Int) {
		traceError = OUT_OF_PLAY
		tracedCount = 0
		walkAlong(pairFirst(guideEdges[edge]), pairSecond(guideEdges[edge]))

		edgeError[edge] = traceError
		edgeTriangles[edge] = if (tracedCount == 0) null else traced.copyOf(tracedCount)
		edgeWorstX[edge] = traceWorstX
		edgeWorstY[edge] = traceWorstY
		edgeWorstCrossed[edge] = traceWorstCrossed
	}

	/**
	 * Walks a guide edge's straight line from one end toward the other through the domain's triangles,
	 * folding the error at every triangulation edge it crosses into [traceError].
	 *
	 * @param Int from The guide vertex the walk starts at.
	 * @param Int to   The guide vertex it heads for.
	 * @return Boolean True when the walk reached the far end; false when it left the domain first.
	 */
	private fun walkAlong(from: Int, to: Int): Boolean {
		lineStartX = guide.points[2 * from].toDouble()
		lineStartY = guide.points[2 * from + 1].toDouble()
		lineEndX = guide.points[2 * to].toDouble()
		lineEndY = guide.points[2 * to + 1].toDouble()
		guide.valuesAtVertex(from, pointValues)
		guide.valuesAtVertex(to, endValues)
		var triangle = locate(lineStartX, lineStartY)

		if (triangle < 0) {
			return enterHull()
		}

		var vertex = NO_STORE_VERTEX
		var entered = HalfEdgeTriangulation.NO_HALF_EDGE
		var startEdge = HalfEdgeTriangulation.NO_HALF_EDGE
		var startEdgeCount = 0

		for (offset in 0 until 3) {
			val halfEdge = 3 * triangle + offset

			if (store.orientAt(lineStartX, lineStartY, store.origin[halfEdge], store.destination(halfEdge)) == 0.0) {
				startEdge = halfEdge
				startEdgeCount++
			}
		}

		if (startEdgeCount >= 2) {
			vertex = (0 until 3).map { offset -> store.origin[3 * triangle + offset] }.first { corner -> store.pointX(corner) == lineStartX && store.pointY(corner) == lineStartY }
		} else if (startEdgeCount == 1) {
			val originSide = sideOf(store.origin[startEdge])
			val destinationSide = sideOf(store.destination(startEdge))

			if (originSide == 0.0 && destinationSide == 0.0) {
				// The line runs along the edge the start lies on: it carries on from the end ahead.
				val ahead = if (isAhead(lineStartX, lineStartY, store.origin[startEdge])) store.origin[startEdge] else store.destination(startEdge)
				record(startEdge / 3)

				if (endLiesBefore(ahead)) {
					return true
				}

				vertex = ahead
			} else if (originSide < 0.0 && destinationSide > 0.0) {
				// The line leaves this triangle where it starts, so it starts in the one across.
				entered = store.twin[startEdge]
				triangle = entered / 3
			} else {
				entered = startEdge
			}
		}

		return walkLine(vertex, triangle, entered, findsObstacle = false)
	}

	/**
	 * Walks the current line from a start past the convex hull: from where it first enters the hull,
	 * through a hull edge or a hull vertex on it, when it does before its end.
	 *
	 * @return Boolean True when the walk reached the line's end.
	 */
	private fun enterHull(): Boolean {
		var entry = HalfEdgeTriangulation.NO_HALF_EDGE
		var entryAlong = Double.MAX_VALUE
		var corner = NO_STORE_VERTEX
		var cornerAlong = Double.MAX_VALUE
		val directionX = lineEndX - lineStartX
		val directionY = lineEndY - lineStartY

		for (triangle in 0 until store.triangleCount) {
			if (!store.isGhostTriangle(triangle)) {
				continue
			}

			// The hull edge as its real triangle holds it: the line enters through it with its origin left.
			val hullEdge = store.twin[store.realEdgeOfGhost(triangle)]
			val originSide = sideOf(store.origin[hullEdge])

			if (originSide > 0.0 && sideOf(store.destination(hullEdge)) < 0.0) {
				val along = alongLineAt(hullEdge)

				if (along in 0.0..1.0 && along < entryAlong) {
					entry = hullEdge
					entryAlong = along
				}
			} else if (originSide == 0.0) {
				val vertex = store.origin[hullEdge]
				val along = ((store.pointX(vertex) - lineStartX) * directionX + (store.pointY(vertex) - lineStartY) * directionY) / (directionX * directionX + directionY * directionY)

				if (along in 0.0..1.0 && along < cornerAlong) {
					corner = vertex
					cornerAlong = along
				}
			}
		}

		if (corner != NO_STORE_VERTEX && cornerAlong <= entryAlong) {
			return walkLine(corner, -1, HalfEdgeTriangulation.NO_HALF_EDGE, findsObstacle = false)
		}

		if (entry == HalfEdgeTriangulation.NO_HALF_EDGE) {
			return false
		}

		if (store.regions[entry / 3]) {
			foldCrossing(entry)
		}

		return walkLine(NO_STORE_VERTEX, entry / 3, entry, findsObstacle = false)
	}

	/**
	 * Where along the current line, from 0 at its start to 1 at its end, it crosses a half-edge's line.
	 *
	 * @param Int halfEdge The half-edge.
	 * @return Double The parameter, or infinity when the two are parallel.
	 */
	private fun alongLineAt(halfEdge: Int): Double {
		val origin = store.origin[halfEdge]
		val destination = store.destination(halfEdge)
		val directionX = lineEndX - lineStartX
		val directionY = lineEndY - lineStartY
		val edgeX = store.pointX(destination) - store.pointX(origin)
		val edgeY = store.pointY(destination) - store.pointY(origin)
		val denominator = directionX * edgeY - directionY * edgeX

		if (denominator == 0.0) {
			return Double.POSITIVE_INFINITY
		}

		return ((store.pointX(origin) - lineStartX) * edgeY - (store.pointY(origin) - lineStartY) * edgeX) / denominator
	}

	/**
	 * Walks from a store vertex toward a point through the domain's triangles until something stands in
	 * the way: a constrained edge the line crosses ([metHalfEdge]), or a vertex on the line
	 * ([metVertex]); otherwise to the point.
	 *
	 * @param Int    from    The store vertex.
	 * @param Double targetX The point's x.
	 * @param Double targetY The point's y.
	 * @return Boolean True when the point was reached; false when something stood in the way, or the line leaves the domain at the vertex itself (nothing met).
	 */
	private fun walkToward(from: Int, targetX: Double, targetY: Double): Boolean {
		lineStartX = store.pointX(from)
		lineStartY = store.pointY(from)
		lineEndX = targetX
		lineEndY = targetY
		metHalfEdge = HalfEdgeTriangulation.NO_HALF_EDGE
		metVertex = NO_STORE_VERTEX

		return walkLine(from, -1, HalfEdgeTriangulation.NO_HALF_EDGE, findsObstacle = true)
	}

	/**
	 * Walks the current line from where it stands - a store vertex on it, or a triangle it lies in, entered
	 * through a half-edge or from inside - through the domain's triangles.  The line leaves a triangle
	 * through an edge whose origin lies right of it and destination left, or through a corner on it;
	 * from a vertex, it leaves along a spoke or into the one triangle around the vertex it splits.  A
	 * trace folds the error at every edge it crosses; an obstacle walk stops at the first constrained
	 * edge or vertex on the line it meets.
	 *
	 * @param Int     startVertex   The vertex it stands on, or [NO_STORE_VERTEX].
	 * @param Int     startTriangle The triangle it stands in, when not on a vertex.
	 * @param Int     startEntered  The half-edge it entered that triangle through, or none.
	 * @param Boolean findsObstacle Whether this is an obstacle walk rather than a trace.
	 * @return Boolean True when the walk reached the line's end; false when it left the domain or met something first.
	 */
	private fun walkLine(startVertex: Int, startTriangle: Int, startEntered: Int, findsObstacle: Boolean): Boolean {
		var vertex = startVertex
		var triangle = startTriangle
		var entered = startEntered
		val stepLimit = 3 * store.triangleCount + 16

		for (step in 0 until stepLimit) {
			if (vertex != NO_STORE_VERTEX) {
				if (findsObstacle && vertex != startVertex) {
					metVertex = vertex

					return false
				}

				if (store.pointX(vertex) == lineEndX && store.pointY(vertex) == lineEndY) {
					return true
				}

				val leaving = leaveVertex(vertex)

				if (leaving == NO_WAY_OUT) {
					return false
				}

				if (leaving < 0) {
					// Along a spoke: the error there is the guide's own at both ends.
					val neighbor = -(leaving + 2)

					if (endLiesBefore(neighbor)) {
						return true
					}

					vertex = neighbor

					continue
				}

				triangle = leaving
				entered = HalfEdgeTriangulation.NO_HALF_EDGE
				vertex = NO_STORE_VERTEX
			}

			if (store.isGhostTriangle(triangle)) {
				return false
			}

			val inside = store.regions[triangle]

			if (findsObstacle && !inside) {
				return false
			}

			if (!findsObstacle) {
				record(triangle)

				if (inside) {
					traceError = maxOf(traceError, 0.0)
				}
			}

			if (containsPoint(triangle, lineEndX, lineEndY)) {
				return true
			}

			var exit = HalfEdgeTriangulation.NO_HALF_EDGE
			var corner = NO_STORE_VERTEX

			for (offset in 0 until 3) {
				val halfEdge = 3 * triangle + offset

				if (halfEdge == entered) {
					continue
				}

				val originSide = sideOf(store.origin[halfEdge])

				if (originSide < 0.0 && sideOf(store.destination(halfEdge)) > 0.0) {
					exit = halfEdge
				} else if (originSide == 0.0) {
					corner = store.origin[halfEdge]
				}
			}

			if (exit != HalfEdgeTriangulation.NO_HALF_EDGE) {
				if (findsObstacle && store.constrained[exit]) {
					metHalfEdge = exit

					return false
				}

				val across = store.twin[exit] / 3

				// Only where the domain is: an edge outside it carries nothing the mesh draws.
				if (!findsObstacle && (inside || (!store.isGhostTriangle(across) && store.regions[across]))) {
					foldCrossing(exit)
				}

				entered = store.twin[exit]
				triangle = entered / 3
			} else if (corner != NO_STORE_VERTEX) {
				vertex = corner
			} else {
				return false
			}
		}

		return false
	}

	/**
	 * How the current line leaves a store vertex on it: along a spoke to a neighbor ahead, or into the
	 * one triangle around the vertex whose far edge it crosses.
	 *
	 * @param Int vertex The vertex, on the line.
	 * @return Int The triangle entered, -(neighbor + 2) along a spoke, or [NO_WAY_OUT].
	 */
	private fun leaveVertex(vertex: Int): Int {
		val first = store.vertexHalfEdge[vertex]
		var spoke = first

		do {
			val neighbor = store.destination(spoke)

			if (neighbor != store.ghostVertex) {
				val neighborSide = sideOf(neighbor)

				if (neighborSide == 0.0 && isAhead(store.pointX(vertex), store.pointY(vertex), neighbor)) {
					record(if (store.isGhostTriangle(spoke / 3)) store.twin[spoke] / 3 else spoke / 3)

					return -(neighbor + 2)
				}

				val apex = store.origin[store.previous(spoke)]

				if (apex != store.ghostVertex && neighborSide < 0.0 && sideOf(apex) > 0.0) {
					return spoke / 3
				}
			}

			spoke = store.twin[store.previous(spoke)]
		} while (spoke != first)

		return NO_WAY_OUT
	}

	/**
	 * Folds the error where the current line crosses a triangulation edge into [traceError], noting the
	 * point when it is the worst yet: the guide's value there runs linearly between the line's ends, the
	 * triangulation's between the edge's.
	 *
	 * @param Int halfEdge The crossed half-edge.
	 */
	private fun foldCrossing(halfEdge: Int) {
		val origin = store.origin[halfEdge]
		val destination = store.destination(halfEdge)
		val directionX = lineEndX - lineStartX
		val directionY = lineEndY - lineStartY
		val edgeX = store.pointX(destination) - store.pointX(origin)
		val edgeY = store.pointY(destination) - store.pointY(origin)
		val toOriginX = store.pointX(origin) - lineStartX
		val toOriginY = store.pointY(origin) - lineStartY
		val denominator = directionX * edgeY - directionY * edgeX

		if (denominator == 0.0) {
			return
		}

		val along = ((toOriginX * edgeY - toOriginY * edgeX) / denominator).coerceIn(0.0, 1.0)
		val across = ((toOriginX * directionY - toOriginY * directionX) / denominator).coerceIn(0.0, 1.0)
		val originValues = valuesOfVertex(origin) * valueCount
		val destinationValues = valuesOfVertex(destination) * valueCount

		for (field in 0 until guide.fieldCount) {
			val component = 2 * field
			val guideX = pointValues[component] + along * (endValues[component] - pointValues[component])
			val guideY = pointValues[component + 1] + along * (endValues[component + 1] - pointValues[component + 1])
			val interpolatedX = vertexValues[originValues + component] + across * (vertexValues[destinationValues + component] - vertexValues[originValues + component])
			val interpolatedY = vertexValues[originValues + component + 1] + across * (vertexValues[destinationValues + component + 1] - vertexValues[originValues + component + 1])
			val deltaX = guideX - interpolatedX
			val deltaY = guideY - interpolatedY
			val error = sqrt(deltaX * deltaX + deltaY * deltaY) / guide.fieldScales[field]

			if (error > traceError) {
				traceError = error
				traceWorstX = store.pointX(origin) + across * edgeX
				traceWorstY = store.pointY(origin) + across * edgeY
				traceWorstCrossed = packVertexPair(origin, destination)
			}
		}
	}

	/**
	 * Where a segment's line crosses a half-edge, into [crossingX] and [crossingY]: on the half-edge,
	 * clamped to it.
	 *
	 * @param Double startX   The segment's start x.
	 * @param Double startY   The segment's start y.
	 * @param Double endX     The segment's end x.
	 * @param Double endY     The segment's end y.
	 * @param Int    halfEdge The half-edge.
	 */
	private fun intersect(startX: Double, startY: Double, endX: Double, endY: Double, halfEdge: Int) {
		val origin = store.origin[halfEdge]
		val destination = store.destination(halfEdge)
		val directionX = endX - startX
		val directionY = endY - startY
		val edgeX = store.pointX(destination) - store.pointX(origin)
		val edgeY = store.pointY(destination) - store.pointY(origin)
		val toOriginX = store.pointX(origin) - startX
		val toOriginY = store.pointY(origin) - startY
		val denominator = directionX * edgeY - directionY * edgeX
		val across = if (denominator == 0.0) 0.5 else ((toOriginX * directionY - toOriginY * directionX) / denominator).coerceIn(0.0, 1.0)
		crossingX = store.pointX(origin) + across * edgeX
		crossingY = store.pointY(origin) + across * edgeY
	}

	/**
	 * Notes a triangle the current trace entered, so a change that takes it away traces the edge again.
	 *
	 * @param Int triangle The triangle slot.
	 */
	private fun record(triangle: Int) {
		if (tracedCount == traced.size) {
			traced = traced.copyOf(2 * traced.size)
		}

		val base = 3 * triangle
		traced[tracedCount++] = packTriangle(store.origin[base], store.origin[base + 1], store.origin[base + 2])
	}

	/**
	 * Which side of the current line a vertex lies on, exactly.
	 *
	 * @param Int vertex The vertex.
	 * @return Double Positive left of the line, negative right, zero on it.
	 */
	private fun sideOf(vertex: Int): Double = orient2d(lineStartX, lineStartY, lineEndX, lineEndY, store.pointX(vertex), store.pointY(vertex), scratch)

	/**
	 * Whether a vertex on the current line lies strictly ahead of a point on it, toward the line's end.
	 *
	 * @param Double fromX  The point's x.
	 * @param Double fromY  The point's y.
	 * @param Int    vertex The vertex.
	 * @return Boolean True when it lies past the point.
	 */
	private fun isAhead(fromX: Double, fromY: Double, vertex: Int): Boolean =
		(store.pointX(vertex) - fromX) * (lineEndX - lineStartX) + (store.pointY(vertex) - fromY) * (lineEndY - lineStartY) > 0.0

	/**
	 * Whether the current line's end lies no further along than a vertex on the line.
	 *
	 * @param Int vertex The vertex.
	 * @return Boolean True when the end has been reached by the vertex.
	 */
	private fun endLiesBefore(vertex: Int): Boolean =
		(store.pointX(vertex) - lineEndX) * (lineEndX - lineStartX) + (store.pointY(vertex) - lineEndY) * (lineEndY - lineStartY) >= 0.0

	/**
	 * Whether a point lies in a triangle's closure, exactly.
	 *
	 * @param Int    triangle The triangle slot.
	 * @param Double x        The point's x.
	 * @param Double y        The point's y.
	 * @return Boolean True inside or on its boundary.
	 */
	private fun containsPoint(triangle: Int, x: Double, y: Double): Boolean {
		val base = 3 * triangle

		return (0 until 3).all { offset -> store.orientAt(x, y, store.origin[base + offset], store.destination(base + offset)) >= 0.0 }
	}

	/**
	 * The store vertex standing at a guide vertex's point, looked up on first ask.
	 *
	 * @param Int guideVertex The guide vertex.
	 * @return Int The store vertex, or [ABSENT].
	 */
	private fun presentVertexOf(guideVertex: Int): Int {
		if (storeVertexOfGuide[guideVertex] != UNKNOWN) {
			return storeVertexOfGuide[guideVertex]
		}

		val x = guide.points[2 * guideVertex].toDouble()
		val y = guide.points[2 * guideVertex + 1].toDouble()
		val triangle = locate(x, y)
		var found = ABSENT

		if (triangle >= 0) {
			for (offset in 0 until 3) {
				val corner = store.origin[3 * triangle + offset]

				if (store.pointX(corner) == x && store.pointY(corner) == y) {
					found = corner
				}
			}
		}

		storeVertexOfGuide[guideVertex] = found

		return found
	}

	/**
	 * Whether a packed triangle is still one of the store's.
	 *
	 * @param Long key The triangle.
	 * @return Boolean True when it exists.
	 */
	private fun triangleExists(key: Long): Boolean {
		val halfEdge = store.findHalfEdge(triangleFirst(key), triangleSecond(key))

		return halfEdge != HalfEdgeTriangulation.NO_HALF_EDGE && store.origin[store.previous(halfEdge)] == triangleThird(key)
	}

	/**
	 * Makes sure a store vertex carries the guide's values, interpolating them at its point on first use.
	 *
	 * @param Int vertex The store vertex.
	 * @return Int The vertex, for indexing its values.
	 */
	private fun valuesOfVertex(vertex: Int): Int {
		if (!vertexValuesKnown[vertex]) {
			val offset = vertex * valueCount

			if (!guide.valuesAt(store.pointX(vertex), store.pointY(vertex), vertexValues, offset)) {
				vertexValues.fill(0.0, offset, offset + valueCount)
			}

			vertexValuesKnown[vertex] = true
		}

		return vertex
	}

	/**
	 * Drops a candidate from consideration.
	 *
	 * @param Int candidate The candidate.
	 */
	private fun leaveOut(candidate: Int) {
		if (candidate < vertexCandidateCount) {
			vertexTriangle[candidate] = NO_TRIANGLE
		} else {
			edgeError[candidate - vertexCandidateCount] = OUT_OF_PLAY
			edgeTriangles[candidate - vertexCandidateCount] = null
		}
	}

	/**
	 * The real triangle whose closure holds a point, by a visibility walk across every edge from the last
	 * triangle reached, falling back to a scan when the walk runs long.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return Int The triangle slot, or -1 when the point lies past the convex hull.
	 */
	private fun locate(x: Double, y: Double): Int {
		var triangle = if (walkStart < store.triangleCount && !store.isGhostTriangle(walkStart)) walkStart else firstRealTriangle()
		val stepLimit = 4 * store.triangleCount + 16

		walk@ for (step in 0 until stepLimit) {
			val base = 3 * triangle

			for (offset in 0 until 3) {
				val halfEdge = base + offset

				if (store.orientAt(x, y, store.origin[halfEdge], store.destination(halfEdge)) < 0.0) {
					val across = store.twin[halfEdge] / 3

					if (store.isGhostTriangle(across)) {
						return -1
					}

					triangle = across

					continue@walk
				}
			}

			walkStart = triangle

			return triangle
		}

		return locateByScan(x, y)
	}

	/**
	 * The first real triangle whose closure holds a point, scanning every slot.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return Int The triangle slot, or -1.
	 */
	private fun locateByScan(x: Double, y: Double): Int {
		for (triangle in 0 until store.triangleCount) {
			if (!store.isGhostTriangle(triangle) && containsPoint(triangle, x, y)) {
				return triangle
			}
		}

		return -1
	}

	/**
	 * The lowest real triangle slot.
	 *
	 * @return Int The slot.
	 */
	private fun firstRealTriangle(): Int = (0 until store.triangleCount).first { triangle -> !store.isGhostTriangle(triangle) }
}

/**
 * Every edge of a triangle list once, as packed (low, high) pairs, ascending.
 *
 * @param IntArray triangles Three vertex indices per triangle.
 * @return LongArray The edges.
 */
private fun uniqueEdgesOf(triangles: IntArray): LongArray {
	val edges = HashSet<Long>()

	for (entry in triangles.indices) {
		val start = triangles[entry]
		val end = triangles[if (entry % 3 == 2) entry - 2 else entry + 1]

		if (start != end) {
			edges.add(packVertexPair(minOf(start, end), maxOf(start, end)))
		}
	}

	return edges.toLongArray().also { array -> array.sort() }
}
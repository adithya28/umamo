package org.umamo.geometry.axis

import org.umamo.geometry.packVertexPair
import org.umamo.geometry.pairFirst
import org.umamo.geometry.pairSecond
import org.umamo.geometry.triangulation.Triangulation
import org.umamo.geometry.triangulation.triangulate

/*
 * The chordal axis of a shape bounded by rings (Prasad 1997; the skeleton Teddy inflates, Igarashi et
 * al. 1999): the constrained Delaunay triangulation of the rings' points, read through its CHORDS -
 * the edges two inside triangles share, each running across the shape from one side to the other.
 *
 * An inside triangle with one chord is a TIP (two of its edges are the boundary: the shape ends
 * there), with two a SLEEVE (one boundary edge: the shape runs on), and with three a JUNCTION (where
 * branches meet).  Walking from chord to chord through sleeves gives a BRANCH: the chords across one
 * limb of the shape in order, from a tip or junction to the next, or all the way around a closed band
 * (a LOOP, the shape of a ring with a hole).  A sleeve's two chords share the vertex opposite its
 * boundary edge, so every chord in a branch keeps that vertex on the same side as the last: side A and
 * side B each run along one stretch of the boundary.
 *
 * A finely sampled boundary makes a tip at every bump and every convex corner, each joined to the rest
 * by a junction, which would cut every limb into short branches.  Such tips are pruned the way Teddy
 * prunes them: walking in from a tip chord by chord, while every vertex walked past lies inside the
 * circle the current chord is a diameter of, the tip is no wider than it is long - noise, not a limb -
 * and when the walk reaches a junction that way, the tip and the sleeves behind it leave the axis and
 * the junction becomes a sleeve.  A real limb soon leaves the circle and stays.
 */

/** How far past a chord's circle a vertex may lie and still count as inside, relative to the radius squared. */
private const val CIRCLE_TOLERANCE: Double = 1e-9

/** How one end of an axis branch ends. */
public enum class AxisEnd {
	/** At a junction triangle, where the branch meets others. */
	Junction,

	/** At a tip triangle, where the shape ends. */
	Tip,

	/** Nowhere: the branch is a closed loop of sleeves. */
	Loop,
}

/**
 * One branch of a chordal axis: its chords in order across one limb of the shape, each from side A to
 * side B, and the sleeves between them.
 *
 * A plain class: it holds arrays.
 *
 * @property IntArray sideA    Per chord, its end on side A (an input point index).
 * @property IntArray sideB    Per chord, its end on side B.
 * @property IntArray sleeves  Per step between consecutive chords, the sleeve triangle's three vertices, positively oriented; around a loop, a last one closes it from the last chord back to the first.
 * @property AxisEnd  startEnd How the branch ends before its first chord.
 * @property AxisEnd  endEnd   How it ends after its last chord.
 */
public class AxisBranch(
	public val sideA: IntArray,
	public val sideB: IntArray,
	public val sleeves: IntArray,
	public val startEnd: AxisEnd,
	public val endEnd: AxisEnd,
) {
	/** The number of chords. */
	public val chordCount: Int
		get() = sideA.size
}

/**
 * A shape's chordal axis.
 *
 * @property List<AxisBranch> branches      Every branch, each once.
 * @property Triangulation    triangulation The constrained triangulation the axis was read from.
 */
public class ChordalAxis(public val branches: List<AxisBranch>, public val triangulation: Triangulation)

/**
 * The chordal axis of the shape the rings bound (even-odd, as for [triangulate]).  Branches are found
 * in triangle order, so the result is a function of the input alone.
 *
 * @param DoubleArray    points The rings' points, x0, y0, ...
 * @param List<IntArray> rings  Closed rings of point indices.
 * @return ChordalAxis The axis.
 */
public fun chordalAxis(points: DoubleArray, rings: List<IntArray>): ChordalAxis {
	val triangulation = triangulate(points, rings)
	val triangles = triangulation.triangles
	val triangleCount = triangles.size / 3
	// Per edge, the inside triangles that hold it.
	val firstHolder = HashMap<Long, Int>()
	val secondHolder = HashMap<Long, Int>()

	for (triangle in 0 until triangleCount) {
		for (offset in 0 until 3) {
			val key = edgeKey(triangles, triangle, offset)

			if (firstHolder.containsKey(key)) {
				secondHolder[key] = triangle
			} else {
				firstHolder[key] = triangle
			}
		}
	}

	val chords = AxisChords(points, triangles, firstHolder, secondHolder)
	chords.pruneInsignificantTips()
	val walker = BranchWalker(triangles, chords)
	val branches = ArrayList<AxisBranch>()

	for (triangle in 0 until triangleCount) {
		val count = chords.countOf(triangle)

		if (chords.pruned[triangle] || count == 2 || count == 0) {
			continue
		}

		for (offset in 0 until 3) {
			val key = edgeKey(triangles, triangle, offset)

			if (chords.isChord(key) && key !in walker.visited) {
				branches.add(walker.walk(triangle, offset))
			}
		}
	}

	// What is left of the sleeves runs around closed loops.
	for (triangle in 0 until triangleCount) {
		if (chords.pruned[triangle] || chords.countOf(triangle) != 2) {
			continue
		}

		for (offset in 0 until 3) {
			val key = edgeKey(triangles, triangle, offset)

			if (chords.isChord(key) && key !in walker.visited) {
				branches.add(walker.walk(triangle, offset))
			}
		}
	}

	return ChordalAxis(branches, triangulation)
}

/**
 * Which edges of the inside triangles are chords, as pruning leaves them.
 *
 * @property DoubleArray    points       The points.
 * @property IntArray       triangles    The inside triangles' vertices.
 * @property Map<Long, Int> firstHolder  Per edge, the first triangle holding it.
 * @property Map<Long, Int> secondHolder Per shared edge, the second triangle holding it.
 */
private class AxisChords(
	private val points: DoubleArray,
	private val triangles: IntArray,
	private val firstHolder: Map<Long, Int>,
	private val secondHolder: Map<Long, Int>,
) {
	/** Per triangle, whether pruning took it out of the axis. */
	val pruned = BooleanArray(triangles.size / 3)

	/** Shared edges that no longer count as chords, because what lies across them was pruned. */
	private val cut = HashSet<Long>()

	/**
	 * Whether an edge is a chord: shared by two triangles still in the axis, and not cut.
	 *
	 * @param Long key The edge.
	 * @return Boolean True for a chord.
	 */
	fun isChord(key: Long): Boolean {
		val second = secondHolder[key] ?: return false

		return key !in cut && !pruned[firstHolder.getValue(key)] && !pruned[second]
	}

	/**
	 * The triangle across a chord from another.
	 *
	 * @param Long key      The chord.
	 * @param Int  triangle One triangle holding it.
	 * @return Int The other.
	 */
	fun across(key: Long, triangle: Int): Int = if (firstHolder[key] == triangle) secondHolder.getValue(key) else firstHolder.getValue(key)

	/**
	 * A triangle's chords.
	 *
	 * @param Int triangle The triangle.
	 * @return Int How many of its edges are chords.
	 */
	fun countOf(triangle: Int): Int = (0 until 3).count { offset -> isChord(edgeKey(triangles, triangle, offset)) }

	/** Prunes every tip no wider than it is long back to its junction, until none is left to prune. */
	fun pruneInsignificantTips() {
		val tips = ArrayDeque<Int>()

		for (triangle in pruned.indices) {
			if (countOf(triangle) == 1) {
				tips.addLast(triangle)
			}
		}

		while (tips.isNotEmpty()) {
			val tip = tips.removeFirst()

			if (pruned[tip] || countOf(tip) != 1) {
				continue
			}

			val junction = pruneFrom(tip)

			if (junction >= 0 && countOf(junction) == 1) {
				tips.addLast(junction)
			}
		}
	}

	/**
	 * Walks in from one tip and prunes it when it is noise.
	 *
	 * @param Int tip The tip triangle.
	 * @return Int The junction that became a sleeve (or a tip), or -1 when nothing was pruned.
	 */
	private fun pruneFrom(tip: Int): Int {
		val walked = arrayListOf(tip)
		val vertices = arrayListOf(triangles[3 * tip], triangles[3 * tip + 1], triangles[3 * tip + 2])
		var from = tip
		var key = (0 until 3).map { offset -> edgeKey(triangles, tip, offset) }.first { candidate -> isChord(candidate) }

		while (true) {
			if (!allInsideChordCircle(vertices, key)) {
				return -1
			}

			val across = across(key, from)
			val count = countOf(across)

			if (count != 2) {
				if (count < 3) {
					// Another tip: the whole shape is one limb.
					return -1
				}

				for (triangle in walked) {
					pruned[triangle] = true
				}

				cut.add(key)

				return across
			}

			walked.add(across)
			val nextKey = (0 until 3).map { offset -> edgeKey(triangles, across, offset) }.first { candidate -> candidate != key && isChord(candidate) }
			vertices.add((0 until 3).map { offset -> triangles[3 * across + offset] }.first { vertex -> vertex != pairLow(key) && vertex != pairHigh(key) })
			from = across
			key = nextKey
		}
	}

	/**
	 * Whether every vertex lies inside the circle a chord is a diameter of.
	 *
	 * @param List<Int> vertices The vertices.
	 * @param Long      key      The chord.
	 * @return Boolean True when none lies outside.
	 */
	private fun allInsideChordCircle(vertices: List<Int>, key: Long): Boolean {
		val low = pairLow(key)
		val high = pairHigh(key)
		val centerX = (points[2 * low] + points[2 * high]) / 2.0
		val centerY = (points[2 * low + 1] + points[2 * high + 1]) / 2.0
		val halfX = (points[2 * high] - points[2 * low]) / 2.0
		val halfY = (points[2 * high + 1] - points[2 * low + 1]) / 2.0
		val radiusSquared = (halfX * halfX + halfY * halfY) * (1.0 + CIRCLE_TOLERANCE)

		return vertices.all { vertex ->
			val deltaX = points[2 * vertex] - centerX
			val deltaY = points[2 * vertex + 1] - centerY
			deltaX * deltaX + deltaY * deltaY <= radiusSquared
		}
	}
}

/**
 * Walks branches chord by chord through sleeves.
 *
 * @property IntArray   triangles The inside triangles' vertices.
 * @property AxisChords chords    Which edges are chords.
 */
private class BranchWalker(
	private val triangles: IntArray,
	private val chords: AxisChords,
) {
	/** The chords some branch already holds. */
	val visited = HashSet<Long>()

	/**
	 * Walks one branch from a triangle out through one of its chords until it reaches a triangle that is
	 * not a sleeve, or comes back around to where it started.
	 *
	 * @param Int startTriangle The triangle the walk leaves.
	 * @param Int startOffset   The offset of the chord it leaves through.
	 * @return AxisBranch The branch.
	 */
	fun walk(startTriangle: Int, startOffset: Int): AxisBranch {
		val sideA = ArrayList<Int>()
		val sideB = ArrayList<Int>()
		val sleeves = ArrayList<Int>()
		val startEnd = endOf(startTriangle)
		var from = startTriangle
		var offset = startOffset
		var endEnd: AxisEnd

		while (true) {
			val key = edgeKey(triangles, from, offset)
			visited.add(key)
			val first = triangles[3 * from + offset]
			val second = triangles[3 * from + (offset + 1) % 3]

			if (sideA.isEmpty()) {
				sideA.add(minOf(first, second))
				sideB.add(maxOf(first, second))
			} else if (first == sideA.last() || second == sideA.last()) {
				// The vertex the chords share keeps its side.
				sideA.add(sideA.last())
				sideB.add(if (first == sideA.last()) second else first)
			} else {
				sideB.add(sideB.last())
				sideA.add(if (first == sideB.last()) second else first)
			}

			val across = chords.across(key, from)

			if (chords.countOf(across) != 2) {
				endEnd = endOf(across)

				break
			}

			val nextOffset = (0 until 3).first { candidate -> candidate != offsetOf(across, key) && chords.isChord(edgeKey(triangles, across, candidate)) }

			for (corner in 0 until 3) {
				sleeves.add(triangles[3 * across + corner])
			}

			if (edgeKey(triangles, across, nextOffset) in visited) {
				// Around a closed band and back to the first chord: the sleeve just added closes it.
				endEnd = AxisEnd.Loop

				break
			}

			from = across
			offset = nextOffset
		}

		return AxisBranch(sideA.toIntArray(), sideB.toIntArray(), sleeves.toIntArray(), if (endEnd == AxisEnd.Loop) AxisEnd.Loop else startEnd, endEnd)
	}

	/**
	 * How a branch ends at a triangle that is not a sleeve.
	 *
	 * @param Int triangle The triangle.
	 * @return AxisEnd A tip for one chord, a junction for three, a loop for a sleeve.
	 */
	private fun endOf(triangle: Int): AxisEnd =
		when (chords.countOf(triangle)) {
			1 -> AxisEnd.Tip
			2 -> AxisEnd.Loop
			else -> AxisEnd.Junction
		}

	/**
	 * The offset of an edge within a triangle.
	 *
	 * @param Int  triangle The triangle.
	 * @param Long key      The edge.
	 * @return Int The offset.
	 */
	private fun offsetOf(triangle: Int, key: Long): Int = (0 until 3).first { candidate -> edgeKey(triangles, triangle, candidate) == key }
}

/**
 * A packed edge's lower vertex.
 *
 * @param Long key The edge.
 * @return Int The vertex.
 */
private fun pairLow(key: Long): Int = pairFirst(key)

/**
 * A packed edge's higher vertex.
 *
 * @param Long key The edge.
 * @return Int The vertex.
 */
private fun pairHigh(key: Long): Int = pairSecond(key)

/**
 * One edge of a triangle as a packed (low, high) vertex pair.
 *
 * @param IntArray triangles The triangles' vertices.
 * @param Int      triangle  The triangle.
 * @param Int      offset    The edge's offset: from corner [offset] to the next.
 * @return Long The key.
 */
private fun edgeKey(triangles: IntArray, triangle: Int, offset: Int): Long {
	val start = triangles[3 * triangle + offset]
	val end = triangles[3 * triangle + (offset + 1) % 3]

	return packVertexPair(minOf(start, end), maxOf(start, end))
}
package org.umamo.interop.art.mesh

import org.umamo.format.art.AlphaField
import org.umamo.geometry.axis.AxisBranch
import org.umamo.geometry.axis.AxisEnd
import org.umamo.geometry.axis.chordalAxis
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Thin strands meshed as rung strips: a hair strand, an eyebrow, a line of ink.  Where the art is too
 * narrow for an inner ring, the outline alone would be triangulated by whatever diagonals the Delaunay
 * criterion picks, fanning across the strand at odd angles.  Instead the outline is sampled in pairs
 * straight across the strand - RUNGS - so the triangles between them run across it like a ladder's, and
 * a strand wide enough for a vertex between its sides gets one on its centerline at every rung.
 *
 * The strands come from the outline's chordal axis: the chords across each limb, their art width (twice
 * the field's depth along the chord), and the runs of chords narrower than the band where no inner ring
 * fits - twice the inner margin plus half the interior spacing.  A wider stretch too short to hold an
 * inner ring of its own (under one and a half interior spacings, under twice the band) between two such
 * runs, or between one and the strand's tip, joins them: a strap that widens a little near its end, or
 * a knot in a lock of hair, stays one strip.  Rungs go at the outline spacing along a
 * run's axis, its ends kept back by half a spacing from a junction - so the strand meets the body
 * through the body's own sampling - and from a tip, where the outline turns too tightly to pair across,
 * and each rung's two ends are placed by interpolating the chords'
 * positions on the outline, so they face each other across the strand.  A rung on art at least twice
 * the inner margin wide gets a centerline vertex at the field's deepest point, unless a pin is near.
 *
 * The rungs are samples like any other: the outline's chord checks still split any chord between them
 * that comes too close to the art, so the coverage guarantee is unchanged.
 */

/** Below this many chords a run cannot hold two rungs' worth of interpolation. */
private const val MINIMUM_RUN_CHORDS: Int = 2

/**
 * One chord across the outline, read off the chordal axis.
 *
 * @property Int    ringA        The outline ring its side-A end lies on.
 * @property Double arcA         That end's arc on the ring.
 * @property Int    ringB        The ring its side-B end lies on.
 * @property Double arcB         That end's arc.
 * @property Double width        The art's width across it: twice the field's depth along it, or 0 where it crosses no art.
 * @property Double middleX      Its midpoint's x.
 * @property Double middleY      Its midpoint's y.
 * @property Double axisPosition How far along the branch's axis its midpoint lies.
 */
internal class StrandChord(
	val ringA: Int,
	val arcA: Double,
	val ringB: Int,
	val arcB: Double,
	val width: Double,
	val middleX: Double,
	val middleY: Double,
	val axisPosition: Double,
)

/**
 * A run of narrow chords along one branch of the axis.
 *
 * @property List<StrandChord> chords    The chords, in order along the axis.
 * @property Boolean           trimStart Whether the run starts at its branch's end - a junction or a tip - so its first rung keeps back.
 * @property Boolean           trimEnd   Whether it ends at one.
 * @property Boolean           closed    Whether it goes all the way around a loop.
 */
internal class StrandRun(val chords: List<StrandChord>, val trimStart: Boolean, val trimEnd: Boolean, val closed: Boolean) {
	/** The run's length along its axis; around a loop, back to the first chord. */
	val length: Double
		get() {
			val open = chords.last().axisPosition - chords.first().axisPosition

			return if (closed) open + hypot(chords.first().middleX - chords.last().middleX, chords.first().middleY - chords.last().middleY) else open
		}
}

/**
 * The strands of one layer's outline, found once and placed at every spacing the budget tries.
 *
 * @property List<StrandRun> runs The runs.
 */
internal class StrandPlan(val runs: List<StrandRun>) {
	companion object {
		/** A plan with no strands. */
		val NONE: StrandPlan = StrandPlan(emptyList())
	}
}

/**
 * Where one placement put its rungs.
 *
 * @property List<List<AnchorRun>> anchorRunsByRing Per outline ring, the runs its sampling keeps to.
 * @property DoubleArray           centers          The centerline vertices, Float-rounded, x0, y0, ...
 * @property List<DoubleArray>     quads            The strand's region between consecutive rungs, four corners each.
 * @property Int                   strandCount      The runs that got rungs.
 * @property Int                   rungCount        The rungs.
 */
internal class StrandPlacement(
	val anchorRunsByRing: List<List<AnchorRun>>,
	val centers: DoubleArray,
	val quads: List<DoubleArray>,
	val strandCount: Int,
	val rungCount: Int,
) {
	/**
	 * Whether a point lies in a strand's region.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return Boolean True inside some quad.
	 */
	fun contains(x: Double, y: Double): Boolean = quads.any { quad -> quadContains(quad, x, y) }

	companion object {
		/**
		 * A placement with no strands.
		 *
		 * @param Int ringCount The outline's ring count.
		 * @return StrandPlacement The empty placement.
		 */
		fun none(ringCount: Int): StrandPlacement = StrandPlacement(List(ringCount) { emptyList() }, DoubleArray(0), emptyList(), 0, 0)
	}
}

/**
 * Finds the outline's strands: samples each ring about a working cell apart, takes the chordal axis,
 * measures the art's width across every chord short enough to cross a strand, and keeps the runs of
 * chords narrower than twice the inner margin plus half the interior spacing.
 *
 * @param AlphaField        field        The field the widths are measured in (the silhouette's when filling holes).
 * @param List<SampledRing> outline      The outline rings.
 * @param ArtMeshSettings   settings     The settings.
 * @param Int               cellSize     The working cell size.
 * @param Double            outlineLevel The outline's level, how far outside the art it runs.
 * @return StrandPlan The runs.
 */
internal fun planStrands(field: AlphaField, outline: List<SampledRing>, settings: ArtMeshSettings, cellSize: Int, outlineLevel: Double): StrandPlan {
	val step = maxOf(1.0, cellSize.toDouble())
	val coordinates = ArrayList<Double>()
	val ringOfPoint = ArrayList<Int>()
	val arcOfPoint = ArrayList<Double>()
	val rings = ArrayList<IntArray>()

	for ((ringIndex, ring) in outline.withIndex()) {
		val count = maxOf(3, ceil(ring.perimeter / step).toInt())
		val first = ringOfPoint.size

		for (position in 0 until count) {
			val arc = ring.perimeter * position / count
			coordinates.add(ring.roundedX(arc))
			coordinates.add(ring.roundedY(arc))
			ringOfPoint.add(ringIndex)
			arcOfPoint.add(arc)
		}

		rings.add(IntArray(count) { position -> first + position })
	}

	val points = coordinates.toDoubleArray()
	val axis = chordalAxis(points, rings)

	if (!axis.triangulation.classificationConsistent || axis.triangulation.rejectedConstraints.isNotEmpty()) {
		return StrandPlan.NONE
	}

	val cutoff = 2.0 * settings.innerMargin + settings.interiorSpacing / 2.0
	// A chord across a strand, or across a stretch wide enough to join one (under twice the cutoff), is no
	// longer than that width plus the outline's margin on both sides; longer chords are not measured.
	val longestStrandChord = 2.0 * cutoff + 2.0 * (outlineLevel + 2.0 * cellSize)
	val runs = ArrayList<StrandRun>()

	for (branch in axis.branches) {
		val chords = chordsOf(branch, points, ringOfPoint, arcOfPoint, field, cellSize, longestStrandChord)
		runs.addAll(narrowRuns(branch, chords, cutoff, settings.interiorSpacing))
	}

	return StrandPlan(runs)
}

/**
 * One branch's chords, each with its ends on the outline and the art's width across it.
 *
 * @param AxisBranch   branch             The branch.
 * @param DoubleArray  points             The axis's points.
 * @param List<Int>    ringOfPoint        Per point, its ring.
 * @param List<Double> arcOfPoint         Per point, its arc.
 * @param AlphaField   field              The field.
 * @param Int          cellSize           The working cell size.
 * @param Double       longestStrandChord Chords longer than this are not measured: no strand is that wide.
 * @return List<StrandChord> The chords.
 */
private fun chordsOf(branch: AxisBranch, points: DoubleArray, ringOfPoint: List<Int>, arcOfPoint: List<Double>, field: AlphaField, cellSize: Int, longestStrandChord: Double): List<StrandChord> {
	val chords = ArrayList<StrandChord>()
	var axisPosition = 0.0
	var previousX = 0.0
	var previousY = 0.0

	for (chord in 0 until branch.chordCount) {
		val sideA = branch.sideA[chord]
		val sideB = branch.sideB[chord]
		val startX = points[2 * sideA]
		val startY = points[2 * sideA + 1]
		val endX = points[2 * sideB]
		val endY = points[2 * sideB + 1]
		val middleX = (startX + endX) / 2.0
		val middleY = (startY + endY) / 2.0

		if (chord > 0) {
			axisPosition += hypot(middleX - previousX, middleY - previousY)
		}

		val length = hypot(endX - startX, endY - startY)
		val width = if (length > longestStrandChord) Double.POSITIVE_INFINITY else -2.0 * minOf(0.0, deepest(field, startX, startY, endX, endY, cellSize).value)
		chords.add(StrandChord(ringOfPoint[sideA], arcOfPoint[sideA], ringOfPoint[sideB], arcOfPoint[sideB], width, middleX, middleY, axisPosition))
		previousX = middleX
		previousY = middleY
	}

	return chords
}

/**
 * The runs of a branch's chords over art narrower than the cutoff, at least two chords long.  A
 * stretch of wider chords joins the runs around it when it is too short for an inner ring of its own
 * and under twice the cutoff - between two runs, or between a run and a tip.  A loop narrow all the way
 * around is one closed run; otherwise a loop's runs are read from after a wide chord, so none is cut in
 * two at the loop's start.
 *
 * @param AxisBranch        branch          The branch.
 * @param List<StrandChord> chords          Its chords.
 * @param Double            cutoff          The narrowest art that is not a strand.
 * @param Double            interiorSpacing The interior spacing, which sets how short a wider stretch must be to join.
 * @return List<StrandRun> The runs.
 */
private fun narrowRuns(branch: AxisBranch, chords: List<StrandChord>, cutoff: Double, interiorSpacing: Double): List<StrandRun> {
	val narrow = BooleanArray(chords.size) { index -> chords[index].width > 0.0 && chords[index].width < cutoff }
	val loop = branch.startEnd == AxisEnd.Loop
	absorbShortWideStretches(branch, chords, narrow, cutoff, interiorSpacing)

	if (loop && narrow.all { it }) {
		return if (chords.size >= MINIMUM_RUN_CHORDS) listOf(StrandRun(chords, trimStart = false, trimEnd = false, closed = true)) else emptyList()
	}

	// Around a loop, start after a wide chord; the axis positions restart there too.
	val start = if (loop) (narrow.indexOfFirst { !it } + 1) % chords.size else 0
	val order = List(chords.size) { offset -> (start + offset) % chords.size }
	val runs = ArrayList<StrandRun>()
	var runStart = -1

	for (position in 0..order.size) {
		val isNarrow = position < order.size && narrow[order[position]]

		if (isNarrow && runStart < 0) {
			runStart = position
		} else if (!isNarrow && runStart >= 0) {
			if (position - runStart >= MINIMUM_RUN_CHORDS) {
				val runChords = reindexed((runStart until position).map { offset -> chords[order[offset]] })
				val trimStart = !loop && runStart == 0
				val trimEnd = !loop && position == order.size
				runs.add(StrandRun(runChords, trimStart, trimEnd, closed = false))
			}

			runStart = -1
		}
	}

	return runs
}

/**
 * Marks narrow every stretch of wider chords that is short enough to join the runs around it: under
 * one and a half interior spacings along the axis (an inner ring around it would be under three
 * spacings, which no inner ring keeps), every chord measured and under twice the cutoff, with a narrow
 * chord on one side and on the other a narrow chord or the branch's tip.  Around a loop the stretches
 * wrap.
 *
 * @param AxisBranch        branch          The branch.
 * @param List<StrandChord> chords          Its chords.
 * @param BooleanArray      narrow          Per chord, whether it is narrow; stretches that join are marked in place.
 * @param Double            cutoff          The narrowest art that is not a strand.
 * @param Double            interiorSpacing The interior spacing.
 */
private fun absorbShortWideStretches(branch: AxisBranch, chords: List<StrandChord>, narrow: BooleanArray, cutoff: Double, interiorSpacing: Double) {
	val count = chords.size
	val loop = branch.startEnd == AxisEnd.Loop

	if (narrow.none { it } || narrow.all { it }) {
		return
	}

	// Read from a narrow chord, so around a loop no stretch is cut in two at the start.
	val first = if (loop) narrow.indexOfFirst { it } else 0
	var offset = 0

	while (offset < count) {
		if (narrow[(first + offset) % count]) {
			offset++

			continue
		}

		var end = offset

		while (end < count && !narrow[(first + end) % count]) {
			end++
		}

		val stretch = (offset until end).map { position -> (first + position) % count }
		val before = if (loop || offset > 0) narrow[(first + offset - 1 + count) % count] else branch.startEnd == AxisEnd.Tip
		val after = if (loop || end < count) narrow[(first + end) % count] else branch.endEnd == AxisEnd.Tip
		val length = stretchLength(chords, stretch)
		val joinable = stretch.all { member -> chords[member].width.isFinite() && chords[member].width < 2.0 * cutoff }

		if (before && after && joinable && length < 1.5 * interiorSpacing) {
			for (member in stretch) {
				narrow[member] = true
			}
		}

		offset = end
	}
}

/**
 * The axis length a stretch of consecutive chords spans, from the chord before it to the chord after.
 *
 * @param List<StrandChord> chords  The branch's chords.
 * @param List<Int>         stretch The stretch's chord indices, consecutive (around a loop, wrapping).
 * @return Double The length.
 */
private fun stretchLength(chords: List<StrandChord>, stretch: List<Int>): Double {
	var length = 0.0

	for (position in 1 until stretch.size) {
		val previous = chords[stretch[position - 1]]
		val current = chords[stretch[position]]
		length += hypot(current.middleX - previous.middleX, current.middleY - previous.middleY)
	}

	return length
}

/**
 * Chords with their axis positions measured afresh from the first.
 *
 * @param List<StrandChord> chords The chords, consecutive along the axis.
 * @return List<StrandChord> The same chords, positions from zero.
 */
private fun reindexed(chords: List<StrandChord>): List<StrandChord> {
	var axisPosition = 0.0

	return chords.mapIndexed { index, chord ->
		if (index > 0) {
			axisPosition += hypot(chord.middleX - chords[index - 1].middleX, chord.middleY - chords[index - 1].middleY)
		}

		StrandChord(chord.ringA, chord.arcA, chord.ringB, chord.arcB, chord.width, chord.middleX, chord.middleY, axisPosition)
	}
}

/**
 * Places the strands' rungs at one outline spacing.
 *
 * @param StrandPlan        plan     The strands.
 * @param List<SampledRing> outline  The outline rings.
 * @param Double            spacing  The outline spacing, the distance between rungs along the axis.
 * @param AlphaField        field    The field the centerline follows.
 * @param ArtMeshSettings   settings The settings.
 * @param Int               cellSize The working cell size.
 * @param DoubleArray       pins     The pins, Float-rounded.
 * @return StrandPlacement The anchors, centerline vertices, and strand regions.
 */
internal fun placeStrands(plan: StrandPlan, outline: List<SampledRing>, spacing: Double, field: AlphaField, settings: ArtMeshSettings, cellSize: Int, pins: DoubleArray): StrandPlacement {
	val anchorRuns = List(outline.size) { ArrayList<AnchorRun>() }
	val centers = ArrayList<Double>()
	val quads = ArrayList<DoubleArray>()
	var strandCount = 0
	var rungCount = 0

	for (run in plan.runs) {
		val positions = rungPositions(run, spacing) ?: continue
		val rungs = positions.map { position -> rungAt(run, position, outline) }

		// A side must keep to one ring for its anchors to be one run.
		if (rungs.any { rung -> rung.ringA != rungs[0].ringA || rung.ringB != rungs[0].ringB }) {
			continue
		}

		val sideA = anchorRunOf(rungs.map { rung -> rung.arcA }, outline[rungs[0].ringA].perimeter, run.closed) ?: continue
		val sideB = anchorRunOf(rungs.map { rung -> rung.arcB }, outline[rungs[0].ringB].perimeter, run.closed) ?: continue
		anchorRuns[rungs[0].ringA].add(sideA)
		anchorRuns[rungs[0].ringB].add(sideB)
		strandCount++
		rungCount += rungs.size

		for (rung in rungs) {
			if (rung.width >= 2.0 * settings.innerMargin) {
				val center = deepest(field, rung.startX, rung.startY, rung.endX, rung.endY, cellSize)
				val centerX = center.x.toFloat().toDouble()
				val centerY = center.y.toFloat().toDouble()

				if (!nearAnyPin(centerX, centerY, pins, spacing / 2.0)) {
					centers.add(centerX)
					centers.add(centerY)
				}
			}
		}

		val quadCount = if (run.closed) rungs.size else rungs.size - 1

		for (index in 0 until quadCount) {
			val first = rungs[index]
			val second = rungs[(index + 1) % rungs.size]
			quads.add(doubleArrayOf(first.startX, first.startY, second.startX, second.startY, second.endX, second.endY, first.endX, first.endY))
		}
	}

	return StrandPlacement(anchorRuns, centers.toDoubleArray(), quads, strandCount, rungCount)
}

/**
 * The axis positions of a run's rungs: evenly at about the spacing, an end at a junction or a tip kept
 * back by half a spacing, around a loop evenly all the way round.
 *
 * @param StrandRun run     The run.
 * @param Double    spacing The spacing.
 * @return DoubleArray? The positions, or null when the run is too short for two rungs a spacing apart.
 */
private fun rungPositions(run: StrandRun, spacing: Double): DoubleArray? {
	if (run.closed) {
		val count = maxOf(3, (run.length / spacing).roundToInt())

		return DoubleArray(count) { index -> run.length * index / count }
	}

	val start = if (run.trimStart) spacing / 2.0 else 0.0
	val end = run.length - if (run.trimEnd) spacing / 2.0 else 0.0
	val length = end - start

	if (length < spacing) {
		return null
	}

	val count = (length / spacing).roundToInt() + 1

	return DoubleArray(count) { index -> start + length * index / (count - 1) }
}

/**
 * One rung: its two ends on the outline and the art's width across it.
 *
 * @property Int    ringA  Side A's ring.
 * @property Double arcA   Side A's arc.
 * @property Int    ringB  Side B's ring.
 * @property Double arcB   Side B's arc.
 * @property Double startX Side A's point x.
 * @property Double startY Side A's point y.
 * @property Double endX   Side B's point x.
 * @property Double endY   Side B's point y.
 * @property Double width  The art's width across it.
 */
private class Rung(
	val ringA: Int,
	val arcA: Double,
	val ringB: Int,
	val arcB: Double,
	val startX: Double,
	val startY: Double,
	val endX: Double,
	val endY: Double,
	val width: Double,
)

/**
 * The rung at an axis position, its ends interpolated between the chords on either side of it.
 *
 * @param StrandRun         run      The run.
 * @param Double            position The axis position.
 * @param List<SampledRing> outline  The outline rings.
 * @return Rung The rung.
 */
private fun rungAt(run: StrandRun, position: Double, outline: List<SampledRing>): Rung {
	val chords = run.chords
	var before = chords.size - 1
	var after = 0
	var span = run.length - chords.last().axisPosition
	var into = position - chords.last().axisPosition

	for (index in 0 until chords.size - 1) {
		if (position <= chords[index + 1].axisPosition) {
			before = index
			after = index + 1
			span = chords[after].axisPosition - chords[before].axisPosition
			into = position - chords[before].axisPosition

			break
		}
	}

	val fraction = if (span > 0.0) (into / span).coerceIn(0.0, 1.0) else 0.0
	val first = chords[before]
	val second = chords[after]
	val ringA = outline[first.ringA]
	val ringB = outline[first.ringB]
	val arcA = wrappedArc(first.arcA + fraction * wrappedDelta(first.arcA, second.arcA, ringA.perimeter), ringA.perimeter)
	val arcB = wrappedArc(first.arcB + fraction * wrappedDelta(first.arcB, second.arcB, ringB.perimeter), ringB.perimeter)

	return Rung(
		first.ringA,
		arcA,
		first.ringB,
		arcB,
		ringA.roundedX(arcA),
		ringA.roundedY(arcA),
		ringB.roundedX(arcB),
		ringB.roundedY(arcB),
		first.width + fraction * (second.width - first.width),
	)
}

/**
 * One side's rung arcs as an anchor run, ascending along its ring.
 *
 * @param List<Double> arcs      The arcs in rung order.
 * @param Double       perimeter The ring's perimeter.
 * @param Boolean      closed    Whether the run goes all the way around.
 * @return AnchorRun? The run, or null when the arcs do not step one way along the ring.
 */
private fun anchorRunOf(arcs: List<Double>, perimeter: Double, closed: Boolean): AnchorRun? {
	val deltas = (1 until arcs.size).map { index -> wrappedDelta(arcs[index - 1], arcs[index], perimeter) }

	if (deltas.all { delta -> delta > 0.0 }) {
		return AnchorRun(unwrapped(arcs, deltas), closed)
	}

	if (deltas.all { delta -> delta < 0.0 }) {
		val reversed = arcs.reversed()

		return AnchorRun(unwrapped(reversed, deltas.reversed().map { delta -> -delta }), closed)
	}

	return null
}

/**
 * Arcs unwrapped into an ascending sequence from the first.
 *
 * @param List<Double> arcs   The arcs.
 * @param List<Double> deltas The positive steps between them.
 * @return DoubleArray The unwrapped arcs.
 */
private fun unwrapped(arcs: List<Double>, deltas: List<Double>): DoubleArray {
	val result = DoubleArray(arcs.size)
	result[0] = arcs[0]

	for (index in 1 until arcs.size) {
		result[index] = result[index - 1] + deltas[index - 1]
	}

	return result
}

/**
 * The short way from one arc to another around a ring.
 *
 * @param Double from      The first arc.
 * @param Double to        The second.
 * @param Double perimeter The ring's perimeter.
 * @return Double The signed step, within half a perimeter.
 */
private fun wrappedDelta(from: Double, to: Double, perimeter: Double): Double {
	val delta = to - from

	return when {
		delta > perimeter / 2.0 -> delta - perimeter
		delta < -perimeter / 2.0 -> delta + perimeter
		else -> delta
	}
}

/**
 * An arc wrapped into [0, perimeter).
 *
 * @param Double arc       The arc.
 * @param Double perimeter The perimeter.
 * @return Double The wrapped arc.
 */
private fun wrappedArc(arc: Double, perimeter: Double): Double {
	val remainder = arc % perimeter

	return if (remainder < 0.0) remainder + perimeter else remainder
}

/**
 * A point and the field's value there.
 *
 * @property Double x     The point's x.
 * @property Double y     The point's y.
 * @property Double value The field there.
 */
private class DeepestPoint(val x: Double, val y: Double, val value: Double)

/**
 * The deepest point of the field along a segment, sampled half a working cell apart.
 *
 * @param AlphaField field    The field.
 * @param Double     startX   The segment's start x.
 * @param Double     startY   The start y.
 * @param Double     endX     The end x.
 * @param Double     endY     The end y.
 * @param Int        cellSize The working cell size.
 * @return DeepestPoint The point and the field there.
 */
private fun deepest(field: AlphaField, startX: Double, startY: Double, endX: Double, endY: Double, cellSize: Int): DeepestPoint {
	val samples = maxOf(2, ceil(hypot(endX - startX, endY - startY) / (cellSize / 2.0)).toInt())
	var best = DeepestPoint(startX, startY, field.valueAt(startX, startY))

	for (sample in 1..samples) {
		val fraction = sample.toDouble() / samples
		val x = startX + fraction * (endX - startX)
		val y = startY + fraction * (endY - startY)
		val value = field.valueAt(x, y)

		if (value < best.value) {
			best = DeepestPoint(x, y, value)
		}
	}

	return best
}

/**
 * Whether a point lies inside a quadrilateral (even-odd, so a twisted one still counts sensibly).
 *
 * @param DoubleArray quad The four corners, x0, y0, ...
 * @param Double      x    The point's x.
 * @param Double      y    The point's y.
 * @return Boolean True inside.
 */
private fun quadContains(quad: DoubleArray, x: Double, y: Double): Boolean {
	var inside = false

	for (corner in 0 until 4) {
		val next = (corner + 1) % 4
		val startX = quad[2 * corner]
		val startY = quad[2 * corner + 1]
		val endX = quad[2 * next]
		val endY = quad[2 * next + 1]

		if ((startY > y) != (endY > y) && x < startX + (y - startY) * (endX - startX) / (endY - startY)) {
			inside = !inside
		}
	}

	return inside
}
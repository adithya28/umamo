package org.umamo.interop.art.mesh

import org.umamo.format.art.LayerRaster
import org.umamo.format.art.alphaField
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.geometry.mesh.measureQuality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The art mesher on synthetic shapes, every mesh checked by the independent oracle in
 * ArtMeshTestSupport (orientation, manifold edges, used and distinct vertices, exact pins, every art
 * pixel covered, every boundary edge clear of the art by the minimum margin, and with holes filled
 * one disc per outline ring).  The presets refine toward their minimum angle, so every test below
 * that meshes at a preset checks refinement's meshes too.
 */
class ArtMesherTest {
	private val standard = ArtMeshPreset.Standard.settings

	@Test
	fun aDiscGetsAnOutlineAnInnerRingAndALattice() {
		// Big enough that the interior deeper than the inner margin plus half a spacing (32 px) holds
		// lattice points.
		val raster = rasterOf(160, 160) { column, row -> inDisc(column, row, 80.0, 80.0, 70.0) }
		val result = generateArtMesh(raster, standard)
		assertValidArtMesh(raster, standard, result)
		val statistics = result.statistics!!
		assertEquals(1, statistics.outlineRingCount)
		assertEquals(1, statistics.innerRingCount)
		assertTrue(statistics.latticeVertexCount > 0)
		assertTrue(result.notices.isEmpty(), "${result.notices}")
	}

	@Test
	fun anAnnulusKeepsItsHole() {
		val raster = rasterOf(100, 100) { column, row -> inDisc(column, row, 50.0, 50.0, 40.0) && !inDisc(column, row, 50.0, 50.0, 16.0) }
		val result = generateArtMesh(raster, standard)
		val mesh = assertValidArtMesh(raster, standard, result)
		assertEquals(2, result.statistics!!.outlineRingCount)
		assertFalse(meshCovers(mesh, 50.0, 50.0), "the hole's center stays open")
	}

	@Test
	fun aFilledAnnulusIsOneSheetWithPointsAcrossItsHole() {
		val raster = rasterOf(200, 200) { column, row -> inDisc(column, row, 100.0, 100.0, 90.0) && !inDisc(column, row, 100.0, 100.0, 40.0) }
		val inHole = { mesh: PlanarTriangleMesh ->
			(0 until mesh.vertexCount).any { vertex -> distanceFromCenter(mesh, vertex, 100.0, 100.0) < 30.0 }
		}
		val kept = assertValidArtMesh(raster, standard, generateArtMesh(raster, standard))
		val filledSettings = standard.copy(fillHoles = true)
		val filledResult = generateArtMesh(raster, filledSettings)
		val filled = assertValidArtMesh(raster, filledSettings, filledResult)
		assertEquals(1, filledResult.statistics!!.outlineRingCount)
		assertFalse(inHole(kept), "with the hole kept no vertex sits in it")
		assertTrue(inHole(filled), "with the hole filled the lattice runs across it")
		assertTrue(meshCovers(filled, 100.0, 100.0))
	}

	@Test
	fun aNetTooHoleyForTheBudgetMeshesWhenFilled() {
		// 2 px strands every 24 px: 361 holes, each a ring of at least six vertices when kept.
		val raster = rasterOf(470, 470) { column, row -> column <= 457 && row <= 457 && (column % 24 < 2 || row % 24 < 2) }
		val keptResult = generateArtMesh(raster, standard)
		assertNull(keptResult.mesh)
		assertTrue(keptResult.notices.single() is ArtMeshNotice.OverBudget, "${keptResult.notices}")
		val filledSettings = standard.copy(fillHoles = true)
		val filledResult = generateArtMesh(raster, filledSettings)
		val mesh = assertValidArtMesh(raster, filledSettings, filledResult)
		val statistics = filledResult.statistics!!
		assertEquals(1, statistics.outlineRingCount)
		assertTrue(statistics.latticeVertexCount > 0)
		assertTrue(meshCovers(mesh, 228.5, 228.5), "a hole's center is covered")
	}

	@Test
	fun anIslandInsideAFilledHoleNeedsNoRingOfItsOwn() {
		val raster =
			rasterOf(120, 120) { column, row ->
				val inAnnulus = inDisc(column, row, 60.0, 60.0, 50.0) && !inDisc(column, row, 60.0, 60.0, 30.0)
				inAnnulus || inDisc(column, row, 60.0, 60.0, 6.0)
			}
		assertEquals(3, generateArtMesh(raster, standard).statistics!!.outlineRingCount)
		val filledSettings = standard.copy(fillHoles = true)
		val filledResult = generateArtMesh(raster, filledSettings)
		assertValidArtMesh(raster, filledSettings, filledResult)
		assertEquals(1, filledResult.statistics!!.outlineRingCount)
	}

	@Test
	fun islandsCloserThanTwiceTheOutlineLevelMerge() {
		// Standard puts the outline 6 px out, so islands under 12 px apart share one outline.
		val near = rasterOf(80, 40) { column, row -> inDisc(column, row, 20.0, 20.0, 10.0) || inDisc(column, row, 50.0, 20.0, 10.0) }
		val far = rasterOf(110, 40) { column, row -> inDisc(column, row, 20.0, 20.0, 10.0) || inDisc(column, row, 80.0, 20.0, 10.0) }
		val nearResult = generateArtMesh(near, standard)
		val farResult = generateArtMesh(far, standard)
		assertValidArtMesh(near, standard, nearResult)
		assertValidArtMesh(far, standard, farResult)
		assertEquals(1, nearResult.statistics!!.outlineRingCount)
		assertEquals(2, farResult.statistics!!.outlineRingCount)
	}

	@Test
	fun aOnePixelStrandIsMeshedFromItsOutline() {
		val raster = rasterOf(90, 9) { column, row -> row == 4 && column in 5..84 }
		val result = generateArtMesh(raster, standard)
		assertValidArtMesh(raster, standard, result)
		val statistics = result.statistics!!
		assertEquals(0, statistics.innerRingCount)
		assertEquals(0, statistics.latticeVertexCount)
	}

	@Test
	fun eachSpeckGetsAnOutlineOfTheMinimumSize() {
		val specks = listOf(10 to 10, 50 to 10, 90 to 10, 10 to 50, 90 to 50)
		val raster = rasterOf(100, 60) { column, row -> (column to row) in specks }
		val result = generateArtMesh(raster, standard)
		assertValidArtMesh(raster, standard, result)
		val statistics = result.statistics!!
		assertEquals(specks.size, statistics.outlineRingCount)
		assertTrue(statistics.outlineVertexCount >= specks.size * standard.minimumOutlinePoints)
	}

	@Test
	fun nothingAtOrAboveTheThresholdGivesNoMesh() {
		val empty = rasterOf(20, 20) { _, _ -> false }
		val faint = rasterOf(20, 20, alpha = 10) { column, row -> inDisc(column, row, 10.0, 10.0, 6.0) }

		for (raster in listOf(empty, faint)) {
			val result = generateArtMesh(raster, standard)
			assertNull(result.mesh)
			assertEquals(listOf<ArtMeshNotice>(ArtMeshNotice.NothingOpaque), result.notices)
		}
	}

	@Test
	fun aFullyOpaqueRasterMeshesPastItsEdges() {
		val raster = rasterOf(40, 30) { _, _ -> true }
		val mesh = assertValidArtMesh(raster, standard, generateArtMesh(raster, standard))
		assertTrue((0 until mesh.vertexCount).any { mesh.positions[2 * it] < 0f }, "the outline runs into negative coordinates")
	}

	@Test
	fun artCutByTheRasterEdgeIsCovered() {
		val raster = rasterOf(60, 50) { column, row -> inDisc(column, row, 0.0, 25.0, 20.0) }
		assertValidArtMesh(raster, standard, generateArtMesh(raster, standard))
	}

	@Test
	fun aSpeckInTheMouthOfACIsCovered() {
		// The C's opening faces right; a lone pixel sits in its mouth, where a chord bridging the
		// opening would cut it off if the cap check did not catch it.
		val raster =
			rasterOf(120, 120) { column, row ->
				val ring = inDisc(column, row, 60.0, 60.0, 45.0) && !inDisc(column, row, 60.0, 60.0, 30.0)
				val mouth = column > 60 && abs(row + 0.5 - 60.0) < 14.0
				(ring && !mouth) || (column == 98 && row == 60)
			}
		val coarse = standard.copy(outlineSpacing = 80.0)
		assertValidArtMesh(raster, coarse, generateArtMesh(raster, coarse))
	}

	@Test
	fun anLShapeAndACombAreCovered() {
		val shapeL = rasterOf(80, 80) { column, row -> (column in 10..25 && row in 10..70) || (column in 10..70 && row in 55..70) }
		val comb = rasterOf(100, 70) { column, row -> (row in 10..20 && column in 10..90) || (row in 10..60 && (column - 10) % 16 < 5 && column in 10..90) }

		for (raster in listOf(shapeL, comb)) {
			assertValidArtMesh(raster, standard, generateArtMesh(raster, standard))
		}
	}

	@Test
	fun aLevelLandingExactlyOnAGridNodeMeshesCleanly() {
		// Cell size 2 (margins 5 and 4) and minimum margin 1: the outline level is max(5, 1 + 4) = 5,
		// exactly the value of a cell three cells off the block (3 * 2 - 1).
		val tie = standard.copy(outerMargin = 5.0, innerMargin = 4.0, minimumMargin = 1.0)
		val raster = rasterOf(40, 40) { column, row -> column in 12..27 && row in 12..27 }
		val result = generateArtMesh(raster, tie)
		assertEquals(2, result.statistics!!.cellSize)
		assertValidArtMesh(raster, tie, result)
	}

	@Test
	fun pinsBecomeVerticesAtTheirExactPositions() {
		val raster = rasterOf(100, 100) { column, row -> inDisc(column, row, 50.0, 50.0, 35.0) }
		val pins = doubleArrayOf(50.25, 50.75, 30.1, 62.3, -12.5, -8.0)
		val result = generateArtMesh(raster, standard, pins)
		val mesh = assertValidArtMesh(raster, standard, result, pins)
		assertEquals(3, mesh.pinVertices.toSet().size)
	}

	@Test
	fun aPinOffTheArtJoinsItThroughItsSurroundings() {
		// A disc of art and a pin 20 px past the raster's right edge: alone the pin is meshed around on its
		// own; with the old triangle that joined it to the art counted as art, it is one piece with the rest.
		val raster = rasterOf(80, 96) { column, row -> inDisc(column, row, 40.0, 48.0, 16.0) }
		val pins = doubleArrayOf(100.0, 48.0)
		val alone = assertValidArtMesh(raster, standard, generateArtMesh(raster, standard, pins), pins)
		assertEquals(2, pieceCount(alone), "a lone pin off the art is an island")
		val surroundings = doubleArrayOf(100.0, 48.0, 50.0, 40.0, 50.0, 56.0)
		val joined = assertValidArtMesh(raster, standard, generateArtMesh(raster, standard, pins, surroundings), pins)
		assertEquals(1, pieceCount(joined), "the pin's surroundings join it to the art")
	}

	@Test
	fun coincidentPinsShareAVertex() {
		val raster = rasterOf(60, 60) { column, row -> inDisc(column, row, 30.0, 30.0, 20.0) }
		val pins = doubleArrayOf(30.5, 30.5, 30.5, 30.5)
		val result = generateArtMesh(raster, standard, pins)
		val mesh = assertValidArtMesh(raster, standard, result, pins)
		assertEquals(mesh.pinVertices[0], mesh.pinVertices[1])
		assertTrue(ArtMeshNotice.PinsShareVertex(1) in result.notices)
	}

	@Test
	fun theBudgetWidensTheSpacing() {
		val raster = rasterOf(300, 300) { column, row -> inDisc(column, row, 150.0, 150.0, 140.0) }
		// At Standard spacing this disc needs about 64 vertices.
		val tight = standard.copy(vertexBudget = 40)
		val result = generateArtMesh(raster, tight)
		val mesh = assertValidArtMesh(raster, tight, result)
		assertTrue(mesh.vertexCount <= 40)
		assertTrue(result.notices.any { it is ArtMeshNotice.SpacingCoarsened }, "${result.notices}")
	}

	@Test
	fun theBudgetThinsTheInteriorBeforeTheOutline() {
		val raster = largeDisc()
		val placed = placedCountOf(generateArtMesh(raster, standard.copy(minimumAngle = 0.0, vertexBudget = 4096)))
		val outline = placed.first
		val budget = outline + (placed.second - outline) / 2
		val settings = standard.copy(vertexBudget = budget)
		val result = generateArtMesh(raster, settings)
		assertValidArtMesh(raster, settings, result)
		val statistics = result.statistics!!

		assertEquals(standard.outlineSpacing, statistics.outlineSpacing, "the outline keeps the spacing asked for")
		assertTrue(statistics.interiorSpacing!! > standard.interiorSpacing, "the interior thinned")
		assertTrue(result.notices.contains(ArtMeshNotice.SpacingCoarsened(statistics.outlineSpacing, statistics.interiorSpacing)), "${result.notices}")
		assertTrue(placedCountOf(result).second <= budget - reserveOf(budget), "a coarsened mesh leaves refinement its share")
	}

	@Test
	fun theOutlineWidensOnlyOnceTheInteriorIsGone() {
		val raster = largeDisc()
		val outline = placedCountOf(generateArtMesh(raster, standard.copy(minimumAngle = 0.0, vertexBudget = 4096))).first
		val settings = standard.copy(vertexBudget = outline - 4)
		val result = generateArtMesh(raster, settings)
		assertValidArtMesh(raster, settings, result)
		val statistics = result.statistics!!

		assertNull(statistics.interiorSpacing, "the interior was left to refinement")
		assertEquals(0, statistics.innerVertexCount + statistics.latticeVertexCount)
		assertTrue(statistics.outlineSpacing > standard.outlineSpacing, "then the outline widened")
		assertTrue(result.notices.contains(ArtMeshNotice.SpacingCoarsened(statistics.outlineSpacing, null)), "${result.notices}")
	}

	@Test
	fun theReserveGivesWayRatherThanLoseAMesh() {
		// A star of forty spikes: however wide the outline spacing, its corners stay keypoints.
		val raster = spikyStar()
		val leanest = placedCountOf(generateArtMesh(raster, standard.copy(minimumAngle = 0.0, outlineSpacing = 512.0, vertexBudget = 4096))).first
		val settings = standard.copy(vertexBudget = leanest + 2)
		val result = generateArtMesh(raster, settings)
		assertValidArtMesh(raster, settings, result)

		assertNull(result.statistics!!.interiorSpacing, "the interior gave way first")
		assertTrue(placedCountOf(result).second > settings.vertexBudget - reserveOf(settings.vertexBudget), "the outline alone overran the reserve, so the reserve gave way")
	}

	@Test
	fun aMeshThatFitsIsNeverCoarsenedToMakeRoom() {
		val raster = largeDisc()
		val placed = placedCountOf(generateArtMesh(raster, standard.copy(minimumAngle = 0.0, vertexBudget = 4096))).second
		val settings = standard.copy(vertexBudget = placed)
		val result = generateArtMesh(raster, settings)
		assertValidArtMesh(raster, settings, result)
		val statistics = result.statistics!!

		assertEquals(standard.outlineSpacing, statistics.outlineSpacing)
		assertEquals(standard.interiorSpacing, statistics.interiorSpacing)
		assertTrue(result.notices.none { notice -> notice is ArtMeshNotice.SpacingCoarsened }, "${result.notices}")
		assertEquals(0, statistics.steinerVertexCount, "no room was made for refinement")
	}

	@Test
	fun moreSpecksThanTheBudgetCanRingAreOverBudget() {
		val raster = rasterOf(200, 200) { column, row -> column % 20 == 10 && row % 20 == 10 }
		val tight = standard.copy(vertexBudget = 100)
		val result = generateArtMesh(raster, tight)
		assertNull(result.mesh)
		assertTrue(result.notices.single() is ArtMeshNotice.OverBudget)
	}

	@Test
	fun randomArtIsAlwaysCovered() {
		val random = Random(149)

		repeat(12) {
			val width = random.nextInt(30, 120)
			val height = random.nextInt(30, 120)
			val discs = List(random.nextInt(1, 6)) { doubleArrayOf(random.nextDouble(0.0, width.toDouble()), random.nextDouble(0.0, height.toDouble()), random.nextDouble(2.0, 25.0)) }
			val holes = List(random.nextInt(0, 3)) { doubleArrayOf(random.nextDouble(0.0, width.toDouble()), random.nextDouble(0.0, height.toDouble()), random.nextDouble(2.0, 10.0)) }
			val raster =
				rasterOf(width, height) { column, row ->
					discs.any { inDisc(column, row, it[0], it[1], it[2]) } && holes.none { inDisc(column, row, it[0], it[1], it[2]) }
				}

			if (artPixels(raster, standard.alphaThreshold).isEmpty()) {
				return@repeat
			}

			for (preset in ArtMeshPreset.entries) {
				for (fillHoles in listOf(false, true)) {
					val settings = preset.settings.copy(fillHoles = fillHoles)
					assertValidArtMesh(raster, settings, generateArtMesh(raster, settings))
				}
			}
		}
	}

	@Test
	fun theOutputIsAFunctionOfItsInput() {
		val raster = noisyBlob()
		val first = generateArtMesh(raster, standard).mesh!!
		val second = generateArtMesh(raster, standard).mesh!!
		assertContentEquals(first.positions, second.positions)
		assertContentEquals(first.triangles, second.triangles)
	}

	@Test
	fun refinementLiftsATaperedTipAndLeavesOnlyAnglesItCannotHelp() {
		val raster = taperedTip()
		val unrefinedSettings = standard.copy(minimumAngle = 0.0)
		val unrefined = assertValidArtMesh(raster, unrefinedSettings, generateArtMesh(raster, unrefinedSettings))
		val result = generateArtMesh(raster, standard)
		val refined = assertValidArtMesh(raster, standard, result)
		val statistics = result.statistics!!
		val target = standard.minimumAngle

		assertTrue(statistics.steinerVertexCount > 0, "refinement added vertices")
		assertEquals(refined.vertexCount, unrefined.vertexCount + statistics.steinerVertexCount, "refinement only adds, after the same placed points")
		assertEquals(statistics.underTargetCount, refined.measureQuality(1.0).countBelow(target), "the reported count matches the mesh")
		assertEquals(statistics.underTargetCount, statistics.unfixableCount, "what stays below the target is what refinement cannot help")
		assertTrue(unrefined.measureQuality(1.0).countBelow(target) > statistics.underTargetCount, "fewer triangles below the target than without refinement")
		assertTrue(result.notices.none { notice -> notice is ArtMeshNotice.RefinementBudgetReached })
	}

	@Test
	fun aZeroMinimumAngleAddsNothing() {
		val settings = standard.copy(minimumAngle = 0.0)
		val raster = noisyBlob()
		val result = generateArtMesh(raster, settings)
		assertValidArtMesh(raster, settings, result)
		val statistics = result.statistics!!

		assertEquals(0, statistics.steinerVertexCount)
		assertEquals(0, statistics.boundarySplitCount)
		assertEquals(0, statistics.underTargetCount)
	}

	@Test
	fun refinementStaysWithinTheBudgetAndSaysWhenItRanOut() {
		val raster = noisyBlob()
		val unrefined = generateArtMesh(raster, standard.copy(minimumAngle = 0.0)).mesh!!

		for (room in listOf(0, 3)) {
			val tight = standard.copy(minimumAngle = MAXIMUM_ART_MESH_MINIMUM_ANGLE, vertexBudget = unrefined.vertexCount + room)
			val result = generateArtMesh(raster, tight)
			val mesh = assertValidArtMesh(raster, tight, result)

			assertTrue(mesh.vertexCount <= tight.vertexBudget)
			assertTrue(result.statistics!!.steinerVertexCount <= room)
			assertTrue(result.notices.any { notice -> notice is ArtMeshNotice.RefinementBudgetReached }, "with $room spare: ${result.notices}")
		}
	}

	@Test
	fun anOutlineSplitKeepsTheMarginAndCutsOffNoArt() {
		// One art pixel square at columns 49..50, rows 49..50.
		val raster = rasterOf(100, 100) { column, row -> column in 49..50 && row in 49..50 }
		val field = raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 4)!!
		val check = boundarySplitCheckOf(field, minimumMargin = 2.0)

		// A chord 4 px above the art.
		assertTrue(check.accepts(30.0, 45.0, 70.0, 45.0, 50.0, 44.9, false), "bulging away from the art")
		assertFalse(check.accepts(30.0, 45.0, 70.0, 45.0, 50.0, 47.5, false), "new chords within the margin")

		// A wide sliver around the art whose chords pass far beside it.
		val wideCheck = boundarySplitCheckOf(field, minimumMargin = 0.5)
		assertFalse(wideCheck.accepts(30.0, 30.0, 70.0, 30.0, 50.0, 80.0, true), "cutting the art out of the domain")
		assertTrue(wideCheck.accepts(30.0, 30.0, 70.0, 30.0, 50.0, 80.0, false), "the same split on the outside cuts nothing off")
	}

	@Test
	fun malformedSettingsAndPinsAreRejected() {
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumMargin = 0.4) }
		assertFailsWith<IllegalArgumentException> { standard.copy(alphaThreshold = 0) }
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumOutlinePoints = 2) }
		assertFailsWith<IllegalArgumentException> { standard.copy(outlineSpacing = 0.0) }
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumAngle = -1.0) }
		assertFailsWith<IllegalArgumentException> { standard.copy(minimumAngle = MAXIMUM_ART_MESH_MINIMUM_ANGLE + 1.0) }
		assertFailsWith<IllegalArgumentException> { generateArtMesh(noisyBlob(), standard, doubleArrayOf(1.0)) }
		assertFailsWith<IllegalArgumentException> { generateArtMesh(noisyBlob(), standard, doubleArrayOf(Double.NaN, 1.0)) }
	}

	/**
	 * How far a mesh vertex lies from a point.
	 *
	 * @param PlanarTriangleMesh mesh    The mesh.
	 * @param Int                vertex  The vertex.
	 * @param Double             centerX The point's x.
	 * @param Double             centerY The point's y.
	 * @return Double The distance.
	 */
	private fun distanceFromCenter(mesh: PlanarTriangleMesh, vertex: Int, centerX: Double, centerY: Double): Double {
		val deltaX = mesh.positions[2 * vertex] - centerX
		val deltaY = mesh.positions[2 * vertex + 1] - centerY

		return sqrt(deltaX * deltaX + deltaY * deltaY)
	}

	/**
	 * A disc of radius 150: an outline of about thirty points around an interior of a few hundred.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun largeDisc(): LayerRaster = rasterOf(320, 320) { column, row -> inDisc(column, row, 160.0, 160.0, 150.0) }

	/**
	 * A star of forty sharp spikes between radii 100 and 150.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun spikyStar(): LayerRaster =
		rasterOf(320, 320) { column, row ->
			val deltaX = column + 0.5 - 160.0
			val deltaY = row + 0.5 - 160.0
			val turns = atan2(deltaY, deltaX) / (2.0 * PI) * 40.0
			val phase = turns - floor(turns)
			val radius = 100.0 + 50.0 * (1.0 - 2.0 * abs(phase - 0.5))
			deltaX * deltaX + deltaY * deltaY <= radius * radius
		}

	/**
	 * How many points a result placed before refinement: the outline's and the pins' first, then all
	 * of them.
	 *
	 * @param ArtMeshResult result The result.
	 * @return Pair<Int, Int> The outline-and-pins count and the total.
	 */
	private fun placedCountOf(result: ArtMeshResult): Pair<Int, Int> {
		val statistics = result.statistics!!
		val outline = statistics.outlineVertexCount + statistics.pinCount

		return outline to outline + statistics.innerVertexCount + statistics.latticeVertexCount
	}

	/**
	 * The share of a budget a coarsened mesh keeps back for refinement: thirty percent, rounded up.
	 *
	 * @param Int budget The vertex budget.
	 * @return Int The reserve.
	 */
	private fun reserveOf(budget: Int): Int = (budget * 30 + 99) / 100

	/**
	 * A parabolic taper narrowing to a point, like a hair tip.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun taperedTip(): LayerRaster =
		rasterOf(300, 80) { column, row ->
			val offset = row - 40
			row in 10 until 70 && offset * offset * 300 <= (300 - column) * 900
		}

	/**
	 * A blob with ragged edges, a hole, and a few specks.
	 *
	 * @return LayerRaster The raster.
	 */
	private fun noisyBlob(): LayerRaster {
		val random = Random(151)
		val noise = BooleanArray(80 * 80) { random.nextInt(9) == 0 }

		return rasterOf(80, 80) { column, row ->
			val body = inDisc(column, row, 40.0, 40.0, 28.0) && !inDisc(column, row, 46.0, 36.0, 6.0)
			val ragged = inDisc(column, row, 40.0, 40.0, 31.0) && noise[row * 80 + column]
			body || ragged
		}
	}
}
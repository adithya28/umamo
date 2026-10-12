package org.umamo.ui.document

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.format.art.analyzeAlpha
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.geometry.locate.TriangleLocator
import org.umamo.geometry.predicate.orient2d
import org.umamo.interop.ExportNotice
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.render.DecodedImage
import org.umamo.render.SampledRegion
import org.umamo.render.TileMeshMask
import org.umamo.render.TileOpaqueMask
import org.umamo.render.drawableMeshTrianglesOf
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.meshTrianglesOf
import org.umamo.render.sampledRegionHitsMask
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.applyUvAffine
import org.umamo.runtime.model.drawableIdsByAtlasTile
import org.umamo.runtime.model.storedToArtAffineForTile
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.mesh.GenerateMeshHost
import org.umamo.ui.model.mesh.GenerateMeshOutcome
import org.umamo.ui.model.mesh.GenerateMeshSkipReason
import org.umamo.ui.model.mesh.MeshGenerationOptions
import org.umamo.ui.model.mesh.RunTileDecoder
import org.umamo.ui.model.mesh.generateMeshOutcome
import org.umamo.ui.model.mesh.planMeshGeneration
import org.umamo.ui.model.mesh.runGenerateMesh
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Generate Mesh from Art over a whole real rig, through to the CMO3 the official editor opens: every
 * drawable of the corpus sample with a mesh and art regenerated at Standard in one step.
 *
 * It gates what must hold - nothing refused by the atlas, every skip one the rigger can act on, every
 * glue pair kept, every opaque art pixel under the new meshes, every new vertex showing its art at rest
 * where the old mesh drew it, no tile left in place sampling a neighbour's paint it did not sample
 * before, one undo restoring the rig, and an export that reads back with the same meshes and reopens as
 * a byte-identical no-op - and prints what it measures: the counts, how far art between the new vertices
 * moves at rest, and how much art split pieces of one layer now both cover.  The export is written to
 * build/auto-mesh for Cmo3ShapeGateTest and the official editor.
 *
 * Skips without the corpus sample.
 */
class GeneratedMeshCmo3ExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aWholeRigRegeneratesExportsAndReopens() =
		runBlocking {
			val file = sample

			if (file == null) {
				println("cmo3.sample not present; skipping the generated-mesh export gate")

				return@runBlocking
			}

			val startNanos = System.nanoTime()
			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(file.readBytes(), file.name, file.path)).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val base = session.model.value
			val targets = base.drawables.filter { drawable -> drawable.mesh != null && drawable.atlasTileId != null }.map { drawable -> drawable.id }
			val options = MeshGenerationOptions.of(ArtMeshPreset.Standard)
			// One decoder for the test: the pipeline's run and the probes below read the same art once.
			val decode = RunTileDecoder { tileId -> document.artRasters.decodeRaster(tileId) }

			// The pipeline once more beside the flow, for the outcome's skips and packed tiles.
			val outcome =
				withContext(Dispatchers.Default) {
					val plan = planMeshGeneration(base, targets, decode)
					generateMeshOutcome(base, plan, options.settings, decode, document.textures.premultipliedAlpha)
				}
			val generated = assertIs<GenerateMeshOutcome.Generated>(outcome, "the atlas refused nothing")
			val allowedSkips = setOf(GenerateMeshSkipReason.OverBudget, GenerateMeshSkipReason.NothingOpaque, GenerateMeshSkipReason.DegenerateOldMesh)
			assertTrue(generated.skips.values.all { reason -> reason in allowedSkips }, "only skips a rigger can act on: ${generated.skips}")

			val host = GenerateMeshHost(session, document.artRasters, sessionAtlasPages, document.textures.premultipliedAlpha, this) { _ -> }
			assertTrue(runGenerateMesh(host, targets, options, null), "the flow lands")
			val after = session.model.value

			for (drawableId in generated.generatedIds) {
				assertContentEquals(meshOf(generated.model, drawableId).positions, meshOf(after, drawableId).positions, "the flow and the pipeline agree on ${drawableId.raw}")
			}

			assertGlueKept(base, after)
			val effectiveThreshold = maxOf(options.settings.alphaThreshold, base.atlas.composition.alphaThreshold)
			val uncovered = generated.generatedIds.sumOf { drawableId -> uncoveredArtPixels(base, after, drawableId, decode, effectiveThreshold) }
			assertEquals(0, uncovered, "every opaque art pixel lies under its new mesh")
			val landings = restLanding(base, after, generated.generatedIds, decode, effectiveThreshold)
			val newHits = newPaintHits(base, after, decode)
			assertEquals(0, newHits, "no tile left in place samples a neighbour's paint it did not before")
			println(
				"[generate-mesh] ${file.name}: ${generated.generatedIds.size} regenerated, skips ${generated.skips.values.groupingBy { it }.eachCount()}, " +
					"${generated.packedTileIds.size} tile(s) packed to a new spot, vertices ${vertexTotal(base)} -> ${vertexTotal(after)}",
			)

			// At its vertices the new mesh shows its art where the old mesh drew it.  Between them the art
			// follows the new triangles, so where the rest pose bends art inside one of them the two differ by
			// that bend - the mesh's density against the rig, reported rather than gated.
			val vertexErrors = landings.flatMap { landing -> landing.vertexErrors }.sorted()
			val pixelErrors = landings.flatMap { landing -> landing.pixelErrors }.sorted()
			val bentCount = landings.count { landing -> (landing.pixelErrors.maxOrNull() ?: 0.0) > 1.0 }
			println("[generate-mesh]   rest-pose landing at the new vertices: ${summary(vertexErrors)}")
			println("[generate-mesh]   rest-pose landing over the art: ${summary(pixelErrors)}; $bentCount drawable(s) move some art more than 1 px")

			for (landing in landings.sortedByDescending { landing -> landing.pixelErrors.maxOrNull() ?: 0.0 }.take(5)) {
				println("[generate-mesh]     ${landing.drawableId.raw}, vertices ${landing.oldVertexCount} -> ${landing.newVertexCount}: ${summary(landing.pixelErrors.sorted())}")
			}

			assertTrue(percentile(vertexErrors, 0.99) <= 0.05, "the new vertices land where the old mesh drew their art (p99 ${percentile(vertexErrors, 0.99)} px)")
			println("[generate-mesh]   split-piece seam: ${seamOverlapPixels(base, after, decode, effectiveThreshold)} art pixel(s) now under two pieces' meshes that were under at most one")

			// The export: written for the shape gate and the official editor, read back, and reopened.
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== after.atlas) {
					yield()
				}
			}
			val prepared = prepareCmo3Export(document, exportedModelFor(document, session), sessionAtlasPages.binding.value.textures, "gate", 0L, 0)
			follower.cancel()
			println("[generate-mesh]   export report: ${prepared.report.notices.map { notice -> notice::class.simpleName }}")
			// A changed topology leaves the imported weld by definition, so WeldDivergence is the one notice owed.
			assertTrue(prepared.report.notices.all { notice -> notice is ExportNotice.WeldDivergence }, "the export carries every regenerated mesh: ${prepared.report.notices}")
			val exportedBytes = Cmo3.write(prepared.model)
			val exportFile = File("build/auto-mesh/${file.nameWithoutExtension}GenerateMeshExportTest.cmo3")
			exportFile.parentFile.mkdirs()
			exportFile.writeBytes(exportedBytes)
			println("[generate-mesh]   wrote ${exportFile.absolutePath}")
			val reopened = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(exportedBytes, "gate.cmo3", "/art/gate.cmo3")).document)

			for (drawableId in generated.generatedIds) {
				val mesh = meshOf(after, drawableId)
				val back = assertNotNull(reopened.puppet.drawables.firstOrNull { candidate -> candidate.id == drawableId }?.mesh, "${drawableId.raw} reopens with a mesh")
				assertEquals(mesh.vertexCount, back.vertexCount, "${drawableId.raw} keeps its vertex count")
				assertEquals(mesh.triangleCount, back.triangleCount, "${drawableId.raw} keeps its triangle count")
				val worstUv = mesh.uvs.indices.maxOf { component -> abs(mesh.uvs[component] - back.uvs[component]) }
				assertTrue(worstUv <= 1e-4f, "${drawableId.raw} keeps its UVs (off by $worstUv)")
			}

			assertEquals(after.glues.size, reopened.puppet.glues.size, "every glue reopens")

			for ((written, back) in after.glues.zip(reopened.puppet.glues)) {
				val subject = "${written.meshA.raw}+${written.meshB.raw}"
				assertEquals(written.meshA to written.meshB, back.meshA to back.meshB, "glue $subject reopens in its place")
				assertEquals(written.pairs.map { pair -> pair.indexA to pair.indexB }, back.pairs.map { pair -> pair.indexA to pair.indexB }, "glue $subject binds the regenerated vertices")
			}

			val identity = prepareCmo3Export(reopened, reopened.puppet, reopened.textures, "gate", 0L, 0)
			assertTrue(identity.report.isEmpty, "an unedited re-export owes nothing: ${identity.report.notices}")
			val sourceMainXml = CaffCodec.read(exportedBytes).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
			val reemittedMainXml = CaffCodec.read(Cmo3.write(identity.model)).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
			assertContentEquals(sourceMainXml, reemittedMainXml, "main.xml survives the reopen byte-identical")

			session.undo()
			assertSame(base, session.model.value, "one undo restores the rig")
			println("[generate-mesh]   ${(System.nanoTime() - startNanos) / 1_000_000} ms in all")
		}

	/**
	 * Asserts every glue kept its pairs and their weights.
	 *
	 * @param PuppetModel base  The rig before.
	 * @param PuppetModel after The rig after.
	 */
	private fun assertGlueKept(base: PuppetModel, after: PuppetModel) {
		assertEquals(base.glues.size, after.glues.size)

		for ((before, now) in base.glues.zip(after.glues)) {
			assertEquals(before.pairs.map { pair -> pair.weightA to pair.weightB }, now.pairs.map { pair -> pair.weightA to pair.weightB }, "glue ${before.id} keeps its pairs")
		}
	}

	/**
	 * How many opaque pixels of a drawable's art (within its region, on a shared tile) no triangle of its
	 * new mesh covers, by scan conversion with exact predicates.
	 *
	 * @param PuppetModel base       The rig before (its old mesh gives a shared tile's region).
	 * @param PuppetModel after      The rig after.
	 * @param DrawableId  drawableId The drawable.
	 * @param Function    decode     Decodes a tile's art.
	 * @param Int         threshold  The alpha that counts as art.
	 * @return Int The uncovered pixel count.
	 */
	private fun uncoveredArtPixels(base: PuppetModel, after: PuppetModel, drawableId: DrawableId, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): Int {
		val drawable = after.drawables.first { candidate -> candidate.id == drawableId }
		val tileId = drawable.atlasTileId!!
		val raster = decode(tileId) ?: return 0
		val shared = (base.drawableIdsByAtlasTile()[tileId]?.count { sharer -> base.drawables.first { candidate -> candidate.id == sharer }.mesh != null } ?: 0) > 1
		val region = if (shared) TileMeshMask.of(drawableMeshTrianglesOf(base, drawableId)) else null
		val covered = BooleanArray(raster.width * raster.height)

		for (triangle in drawableMeshTrianglesOf(after, drawableId)) {
			val minimumX = maxOf(0, floor(minOf(triangle[0], triangle[2], triangle[4]) - 0.5f).toInt())
			val maximumX = minOf(raster.width - 1, ceil(maxOf(triangle[0], triangle[2], triangle[4]) - 0.5f).toInt())
			val minimumY = maxOf(0, floor(minOf(triangle[1], triangle[3], triangle[5]) - 0.5f).toInt())
			val maximumY = minOf(raster.height - 1, ceil(maxOf(triangle[1], triangle[3], triangle[5]) - 0.5f).toInt())

			for (row in minimumY..maximumY) {
				for (column in minimumX..maximumX) {
					if (!covered[row * raster.width + column] && triangleContains(triangle, column + 0.5, row + 0.5)) {
						covered[row * raster.width + column] = true
					}
				}
			}
		}

		var uncovered = 0

		for (row in 0 until raster.height) {
			for (column in 0 until raster.width) {
				val opaque = (raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF) >= threshold
				val inRegion = region?.isCovered(column, row) ?: true

				if (opaque && inRegion && !covered[row * raster.width + column]) {
					uncovered++
				}
			}
		}

		return uncovered
	}

	/**
	 * Whether a triangle (six art-pixel floats) contains a point, edges included, whichever its winding.
	 *
	 * @param FloatArray triangle The triangle.
	 * @param Double     x        The point's x.
	 * @param Double     y        The point's y.
	 * @return Boolean True when contained.
	 */
	private fun triangleContains(triangle: FloatArray, x: Double, y: Double): Boolean {
		val first = orient2d(triangle[0].toDouble(), triangle[1].toDouble(), triangle[2].toDouble(), triangle[3].toDouble(), x, y)
		val second = orient2d(triangle[2].toDouble(), triangle[3].toDouble(), triangle[4].toDouble(), triangle[5].toDouble(), x, y)
		val third = orient2d(triangle[4].toDouble(), triangle[5].toDouble(), triangle[0].toDouble(), triangle[1].toDouble(), x, y)

		return (first >= 0.0 && second >= 0.0 && third >= 0.0) || (first <= 0.0 && second <= 0.0 && third <= 0.0)
	}

	/**
	 * How far one regenerated drawable's art moves at the default pose.
	 *
	 * @property DrawableId   drawableId     The drawable.
	 * @property Int          oldVertexCount Its vertices before.
	 * @property Int          newVertexCount Its vertices after.
	 * @property List<Double> vertexErrors   Per new vertex inside the old mesh, from where the old mesh drew that art point.
	 * @property List<Double> pixelErrors    Per sampled opaque art pixel both meshes hold, between the two meshes' drawings of it.
	 */
	private class RestLanding(
		val drawableId: DrawableId,
		val oldVertexCount: Int,
		val newVertexCount: Int,
		val vertexErrors: List<Double>,
		val pixelErrors: List<Double>,
	)

	/**
	 * How far each regenerated drawable's art moves at the default pose: a point's world position blended
	 * from the old mesh's deformed vertices against the new mesh's, taken at every new vertex the old mesh
	 * holds and on a 3 px stride over the opaque pixels both meshes hold.
	 *
	 * @param PuppetModel      base         The rig before.
	 * @param PuppetModel      after        The rig after.
	 * @param List<DrawableId> drawableIds  The regenerated drawables.
	 * @param Function         decode       Decodes a tile's art.
	 * @param Int              threshold    The alpha that counts as art.
	 * @return List<RestLanding> One landing per drawable measured.
	 */
	private fun restLanding(base: PuppetModel, after: PuppetModel, drawableIds: List<DrawableId>, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): List<RestLanding> {
		val oldWorld = CpuDeformationEvaluator().evaluate(base, emptyMap()).worldPositions
		val newWorld = CpuDeformationEvaluator().evaluate(after, emptyMap()).worldPositions
		val landings = ArrayList<RestLanding>()

		for (drawableId in drawableIds) {
			val oldPositions = oldWorld[drawableId] ?: continue
			val newPositions = newWorld[drawableId] ?: continue
			val raster = decode(meshTileOf(base, drawableId)) ?: continue
			val oldMesh = meshOf(base, drawableId)
			val newMesh = meshOf(after, drawableId)
			val oldLocator = TriangleLocator(artPointsOf(base, drawableId) ?: continue, oldMesh.indices)
			val newPoints = artPointsOf(after, drawableId) ?: continue
			val newLocator = TriangleLocator(newPoints, newMesh.indices)
			val vertexErrors = ArrayList<Double>()
			val pixelErrors = ArrayList<Double>()

			for (vertex in 0 until newMesh.vertexCount) {
				val oldPoint = blendedWorld(oldLocator, oldMesh.indices, oldPositions, newPoints[2 * vertex].toDouble(), newPoints[2 * vertex + 1].toDouble()) ?: continue
				vertexErrors.add(hypot(oldPoint.first - newPositions[2 * vertex], oldPoint.second - newPositions[2 * vertex + 1]))
			}

			for (row in 0 until raster.height step 3) {
				for (column in 0 until raster.width step 3) {
					if ((raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF) < threshold) {
						continue
					}

					val oldPoint = blendedWorld(oldLocator, oldMesh.indices, oldPositions, column + 0.5, row + 0.5) ?: continue
					val newPoint = blendedWorld(newLocator, newMesh.indices, newPositions, column + 0.5, row + 0.5) ?: continue
					pixelErrors.add(hypot(oldPoint.first - newPoint.first, oldPoint.second - newPoint.second))
				}
			}

			landings.add(RestLanding(drawableId, oldMesh.vertexCount, newMesh.vertexCount, vertexErrors, pixelErrors))
		}

		return landings
	}

	/**
	 * A drawable's mesh vertices laid out in its tile's art pixels.
	 *
	 * @param PuppetModel model      The rig.
	 * @param DrawableId  drawableId The drawable.
	 * @return FloatArray? The interleaved points, or null when the tile has no art frame.
	 */
	private fun artPointsOf(model: PuppetModel, drawableId: DrawableId): FloatArray? {
		val tile = model.atlas.tileById.getValue(meshTileOf(model, drawableId))
		val artUvs = applyUvAffine(meshOf(model, drawableId).uvs, model.atlas.storedToArtAffineForTile(tile.id) ?: return null)

		return FloatArray(artUvs.size) { component -> artUvs[component] * if (component % 2 == 0) tile.width else tile.height }
	}

	/**
	 * The tile a drawable samples.
	 *
	 * @param PuppetModel model      The rig.
	 * @param DrawableId  drawableId The drawable.
	 * @return AtlasTileId The tile.
	 */
	private fun meshTileOf(model: PuppetModel, drawableId: DrawableId): AtlasTileId = model.drawables.first { drawable -> drawable.id == drawableId }.atlasTileId!!

	/**
	 * The world position of an art point inside a mesh: its containing triangle's deformed corners
	 * blended by its weights.
	 *
	 * @param TriangleLocator locator   The mesh's art-pixel locator.
	 * @param IntArray        indices   The mesh's triangles.
	 * @param FloatArray      positions The mesh's deformed world positions.
	 * @param Double          x         The art x.
	 * @param Double          y         The art y.
	 * @return Pair<Double, Double>? The world point, or null outside the mesh.
	 */
	private fun blendedWorld(locator: TriangleLocator, indices: IntArray, positions: FloatArray, x: Double, y: Double): Pair<Double, Double>? {
		val location = locator.locate(x, y) ?: return null

		if (!location.isInside) {
			return null
		}

		val corners = IntArray(3) { corner -> indices[3 * location.triangleIndex + corner] }
		val weights = doubleArrayOf(location.weightA, location.weightB, location.weightC)
		var worldX = 0.0
		var worldY = 0.0

		for (corner in 0 until 3) {
			worldX += weights[corner] * positions[2 * corners[corner]]
			worldY += weights[corner] * positions[2 * corners[corner] + 1]
		}

		return worldX to worldY
	}

	/**
	 * How many (tile, neighbour) pairs on one page have the tile's sampled region on the neighbour's
	 * paint after the run but not before, among tiles that kept their placement.
	 *
	 * @param PuppetModel base   The rig before.
	 * @param PuppetModel after  The rig after.
	 * @param Function    decode Decodes a tile's art.
	 * @return Int The new hits.
	 */
	private fun newPaintHits(base: PuppetModel, after: PuppetModel, decode: (AtlasTileId) -> DecodedImage?): Int {
		if (!after.atlas.storedUvsAddressPages) {
			return 0
		}

		val composition = after.atlas.composition
		val paintByTile = HashMap<AtlasTileId, TileOpaqueMask?>()
		var hits = 0

		fun paintOf(tile: AtlasTile): TileOpaqueMask? =
			paintByTile.getOrPut(tile.id) {
				val raster = decode(tile.id)
				val trim = raster?.let { decoded -> analyzeAlpha(decoded.width, decoded.height, decoded.rgba, composition.alphaThreshold)?.opaqueBounds }

				if (raster == null || trim == null) null else TileOpaqueMask.of(raster, trim, composition.alphaThreshold)
			}

		for (tile in after.atlas.tiles) {
			val placement = tile.placement ?: continue

			if (base.atlas.tileById[tile.id]?.placement != placement) {
				continue
			}

			val newMask = TileMeshMask.of(meshTrianglesOf(after, tile.id)) ?: continue
			val oldMask = TileMeshMask.of(meshTrianglesOf(base, tile.id))
			val newRegion = SampledRegion(placement, newMask)
			val oldRegion = oldMask?.let { mask -> SampledRegion(placement, mask) }

			for (other in after.atlas.tiles) {
				val otherPlacement = other.placement ?: continue

				if (other.id == tile.id || otherPlacement.pageIndex != placement.pageIndex) {
					continue
				}

				val paint = paintOf(other) ?: continue
				val hitsNow = sampledRegionHitsMask(newRegion, otherPlacement, paint, composition.extrude)
				val hitBefore = oldRegion != null && sampledRegionHitsMask(oldRegion, otherPlacement, paint, composition.extrude)

				if (hitsNow && !hitBefore) {
					hits++
				}
			}
		}

		return hits
	}

	/**
	 * Over every tile split between several drawables, how many opaque art pixels the new meshes of two
	 * or more of them cover where the old meshes covered at most one - art a seam now draws twice.
	 *
	 * @param PuppetModel base      The rig before.
	 * @param PuppetModel after     The rig after.
	 * @param Function    decode    Decodes a tile's art.
	 * @param Int         threshold The alpha that counts as art.
	 * @return Int The pixel count.
	 */
	private fun seamOverlapPixels(base: PuppetModel, after: PuppetModel, decode: (AtlasTileId) -> DecodedImage?, threshold: Int): Int {
		var overlap = 0

		for ((tileId, sharers) in base.drawableIdsByAtlasTile()) {
			if (sharers.size < 2) {
				continue
			}

			val raster = decode(tileId) ?: continue
			val oldMasks = sharers.mapNotNull { drawableId -> TileMeshMask.of(drawableMeshTrianglesOf(base, drawableId)) }
			val newMasks = sharers.mapNotNull { drawableId -> TileMeshMask.of(drawableMeshTrianglesOf(after, drawableId)) }

			for (row in 0 until raster.height) {
				for (column in 0 until raster.width) {
					if ((raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF) < threshold) {
						continue
					}

					val oldCount = oldMasks.count { mask -> mask.isCovered(column, row) }
					val newCount = newMasks.count { mask -> mask.isCovered(column, row) }

					if (newCount >= 2 && oldCount <= 1) {
						overlap++
					}
				}
			}
		}

		return overlap
	}

	/**
	 * A drawable's mesh.
	 *
	 * @param PuppetModel model      The rig.
	 * @param DrawableId  drawableId The drawable.
	 * @return DrawableMesh The mesh.
	 */
	private fun meshOf(model: PuppetModel, drawableId: DrawableId): DrawableMesh = model.drawables.first { drawable -> drawable.id == drawableId }.mesh!!

	/**
	 * Every vertex in a rig.
	 *
	 * @param PuppetModel model The rig.
	 * @return Int The vertex count.
	 */
	private fun vertexTotal(model: PuppetModel): Int = model.drawables.sumOf { drawable -> drawable.mesh?.vertexCount ?: 0 }

	/**
	 * The value at a fraction of the way through sorted distances.
	 *
	 * @param List<Double> sorted   The distances, ascending.
	 * @param Double       fraction The fraction, 0 to 1.
	 * @return Double The value, or 0 when there are none.
	 */
	private fun percentile(sorted: List<Double>, fraction: Double): Double = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * fraction).toInt()]

	/**
	 * n, median, 90th and 99th percentiles, and maximum of some distances.
	 *
	 * @param List<Double> errors The distances.
	 * @return String The summary.
	 */
	private fun summary(errors: List<Double>): String {
		if (errors.isEmpty()) {
			return "n 0"
		}

		val sorted = errors.sorted()

		return String.format(Locale.ROOT, "n %d, p50 %.4f, p90 %.4f, p99 %.4f, max %.3f px", sorted.size, percentile(sorted, 0.5), percentile(sorted, 0.9), percentile(sorted, 0.99), sorted.last())
	}
}
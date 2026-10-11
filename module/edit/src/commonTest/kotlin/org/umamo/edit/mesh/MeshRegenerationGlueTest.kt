package org.umamo.edit.mesh

import org.umamo.edit.mesh.RegenerationFixtures.drawableId
import org.umamo.edit.mesh.RegenerationFixtures.gridIndices
import org.umamo.edit.mesh.RegenerationFixtures.gridPoints
import org.umamo.edit.mesh.RegenerationFixtures.partnerId
import org.umamo.geometry.mesh.PlanarTriangleMesh
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Glue through mesh regeneration: the glued vertices go to the mesher as pins and come back as the
 * old vertices themselves, so every pair survives - across two regenerated drawables in either order,
 * on a drawable glued to itself, and when two pins land on one point - and an input or a generated
 * mesh that no longer matches is refused rather than misplacing a pair.
 */
class MeshRegenerationGlueTest {
	private val secondId = DrawableId("second")

	@Test
	fun pinnedGlueVerticesKeepTheirPairs() {
		val model = RegenerationFixtures.model()
		val input = RegenerationFixtures.readyInput(model)
		assertContentEquals(RegenerationFixtures.gluedVertices, input.pinnedOldVertices)
		val generated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(4.0, 12.0, 5), input.pinPoints)
		val built = RegenerationFixtures.built(model, generated)
		val edited = model.withMeshTopologyEdit(drawableId, built.edit)
		val glue = edited.glues.single()
		assertEquals("seam", glue.id)
		assertEquals(listOf(generated.pinVertices[0] to 0, generated.pinVertices[1] to 1), glue.pairs.map { it.indexA to it.indexB })
		assertEquals(listOf(0.25f to 0.75f, 0.5f to 0.5f), glue.pairs.map { it.weightA to it.weightB })
		val oldCanvas = model.drawables.first { it.id == drawableId }.mesh!!.positions
		val newCanvas = edited.drawables.first { it.id == drawableId }.mesh!!.positions

		for ((pin, oldVertex) in input.pinnedOldVertices.withIndex()) {
			val newVertex = generated.pinVertices[pin]
			assertEquals(oldCanvas[2 * oldVertex], newCanvas[2 * newVertex], "pin $pin x")
			assertEquals(oldCanvas[2 * oldVertex + 1], newCanvas[2 * newVertex + 1], "pin $pin y")
		}
	}

	@Test
	fun glueBetweenTwoRegeneratedDrawablesSurvivesEitherOrder() {
		val atlas = RegenerationFixtures.unplacedAtlas()
		val first = RegenerationFixtures.riggedDrawable(RegenerationFixtures.meshOver(atlas, gridPoints, gridIndices))
		val second = first.copy(id = secondId, name = "second")
		val glues = listOf(RegenerationFixtures.partnerGlue(), Glue(drawableId, secondId, listOf(GluePair(6, 0, 0.5f, 0.5f), GluePair(8, 2, 0.5f, 0.5f))))
		val model = RegenerationFixtures.modelOf(listOf(first, second, RegenerationFixtures.partnerDrawable()), glues, atlas)
		val firstInput = model.regenerationInputFor(drawableId) as RegenerationInput.Ready
		val secondInput = model.regenerationInputFor(secondId) as RegenerationInput.Ready
		assertContentEquals(intArrayOf(2, 4, 6, 8), firstInput.pinnedOldVertices)
		assertContentEquals(intArrayOf(0, 2), secondInput.pinnedOldVertices)
		val firstGenerated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(4.0, 12.0, 5), firstInput.pinPoints)
		val secondGenerated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(10.0, 15.0, 4), secondInput.pinPoints)
		val firstEdit = (model.meshRegeneration(firstInput, firstGenerated) as MeshRegeneration.Built).edit
		val secondEdit = (model.meshRegeneration(secondInput, secondGenerated) as MeshRegeneration.Built).edit
		val forward = model.withMeshTopologyEdits(linkedMapOf(drawableId to firstEdit, secondId to secondEdit))!!
		val backward = model.withMeshTopologyEdits(linkedMapOf(secondId to secondEdit, drawableId to firstEdit))!!
		val expected =
			listOf(
				firstGenerated.pinVertices[2] to secondGenerated.pinVertices[0],
				firstGenerated.pinVertices[3] to secondGenerated.pinVertices[1],
			)
		assertEquals(expected, forward.glues[1].pairs.map { it.indexA to it.indexB })
		assertEquals(expected, backward.glues[1].pairs.map { it.indexA to it.indexB })
		assertEquals(forward.glues[0].pairs.map { it.indexA to it.indexB }, backward.glues[0].pairs.map { it.indexA to it.indexB })
	}

	@Test
	fun aSelfGlueRemapsBothSides() {
		val atlas = RegenerationFixtures.unplacedAtlas()
		val drawable = RegenerationFixtures.riggedDrawable(RegenerationFixtures.meshOver(atlas, gridPoints, gridIndices))
		val model = RegenerationFixtures.modelOf(listOf(drawable), listOf(Glue(drawableId, drawableId, listOf(GluePair(0, 8, 0.5f, 0.5f)))), atlas)
		val input = RegenerationFixtures.readyInput(model)
		assertContentEquals(intArrayOf(0, 8), input.pinnedOldVertices)
		val generated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(4.0, 12.0, 5), input.pinPoints)
		val edited = model.withMeshTopologyEdit(drawableId, RegenerationFixtures.built(model, generated).edit)
		assertEquals(listOf(generated.pinVertices[0] to generated.pinVertices[1]), edited.glues.single().pairs.map { it.indexA to it.indexB })
	}

	@Test
	fun coincidentPinsAverageAndKeepEveryPair() {
		// Vertex 9 sits on vertex 4's art point with a canvas position 2 px apart - a ripped seam.
		val atlas = RegenerationFixtures.unplacedAtlas()
		val grid = RegenerationFixtures.meshOver(atlas, gridPoints + floatArrayOf(32f, 32f), gridIndices)
		val canvas = grid.positions.copyOf()
		canvas[18] += 2f
		val mesh = DrawableMesh(canvas, grid.localPositions, grid.uvs, grid.indices)
		val glue = Glue(drawableId, partnerId, listOf(GluePair(4, 0, 0.5f, 0.5f), GluePair(9, 1, 0.5f, 0.5f)))
		val model = RegenerationFixtures.modelOf(listOf(RegenerationFixtures.riggedDrawable(mesh), RegenerationFixtures.partnerDrawable()), listOf(glue), atlas)
		val input = RegenerationFixtures.readyInput(model)
		val generated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(4.0, 12.0, 5), input.pinPoints)
		assertEquals(generated.pinVertices[0], generated.pinVertices[1], "the two pins share one vertex")
		val edited = model.withMeshTopologyEdit(drawableId, RegenerationFixtures.built(model, generated).edit)
		val shared = generated.pinVertices[0]
		assertEquals(listOf(shared to 0, shared to 1), edited.glues.single().pairs.map { it.indexA to it.indexB })
		assertEquals((canvas[8] + canvas[18]) / 2f, edited.drawables.first { it.id == drawableId }.mesh!!.positions[2 * shared], "the shared vertex averages both")
	}

	@Test
	fun aStalePinSetIsRefused() {
		val model = RegenerationFixtures.model()
		val input = RegenerationFixtures.readyInput(model)
		val generated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(4.0, 12.0, 5), input.pinPoints)
		val moreGlue: PuppetModel = model.copy(glues = model.glues + Glue(drawableId, partnerId, listOf(GluePair(0, 2, 0.5f, 0.5f))))
		assertEquals(MeshRegeneration.Refused(RegenerationRefusal.PinMismatch), moreGlue.meshRegeneration(input, generated))
	}

	@Test
	fun aGeneratedMeshThatMovedOrLostAPinIsRefused() {
		val model = RegenerationFixtures.model()
		val input = RegenerationFixtures.readyInput(model)
		val generated = RegenerationFixtures.generated(RegenerationFixtures.pointGrid(4.0, 12.0, 5), input.pinPoints)
		val moved = generated.positions.copyOf()
		moved[2 * generated.pinVertices[0]] += 0.5f
		val movedPin = PlanarTriangleMesh(moved, generated.triangles, generated.pinVertices)
		val lostPin = PlanarTriangleMesh(generated.positions, generated.triangles, generated.pinVertices.copyOf(1))
		assertEquals(MeshRegeneration.Refused(RegenerationRefusal.PinMismatch), model.meshRegeneration(input, movedPin))
		assertEquals(MeshRegeneration.Refused(RegenerationRefusal.PinMismatch), model.meshRegeneration(input, lostPin))
	}
}
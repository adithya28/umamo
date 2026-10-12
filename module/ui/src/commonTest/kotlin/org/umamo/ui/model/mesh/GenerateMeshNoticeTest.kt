package org.umamo.ui.model.mesh

import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which notice a generation reports when drawables are skipped: the skips a rigger can act on lead,
 * then drawables glue holds throughout, then any other skip.
 */
class GenerateMeshNoticeTest {
	@Test
	fun drawablesGlueHoldsThroughoutAreNamedAfterTheActionableSkips() {
		val glued = DrawableId("glued")
		val over = DrawableId("over")
		val missing = DrawableId("missing")

		assertEquals(
			GenerateMeshNotice("notice.mesh.gluedThroughout", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout))),
		)
		assertEquals(
			GenerateMeshNotice("notice.mesh.overBudget", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout, over to GenerateMeshSkipReason.OverBudget))),
		)
		assertEquals(
			GenerateMeshNotice("notice.mesh.gluedThroughout", listOf("0", "1")),
			generateMeshNoticeOf(GenerateMeshOutcome.NothingGenerated(mapOf(glued to GenerateMeshSkipReason.GluedThroughout, missing to GenerateMeshSkipReason.NoRaster))),
		)
	}
}
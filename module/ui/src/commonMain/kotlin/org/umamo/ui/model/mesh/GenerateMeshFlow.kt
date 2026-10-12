package org.umamo.ui.model.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.edit.AdjustableOperation
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.edit.choiceValue
import org.umamo.edit.mesh.commitGeneratedMeshes
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.ui.model.SessionAtlasPages

/*
 * Generate Mesh from Art as an operation: the run on the UI thread around the headless pipeline
 * (GenerateMeshPipeline.kt), committed as ONE undo step and registered on the operation settings strip,
 * whose adjustments re-mesh from the same starting model.  The Add Artwork flow's shape: the model is
 * read at the start, the work runs on the default dispatcher, and the result lands only if the model is
 * still the one it was built from.
 */

/**
 * What a Generate Mesh from Art run needs from the shell.
 *
 * @property EditorSession      session            The document's session.
 * @property SourceArtRasters   artRasters         The document's raster store.
 * @property SessionAtlasPages? sessionAtlasPages  The page resolver to pre-warm after a pack, or null.
 * @property Boolean            premultipliedAlpha The document's texture-convention flag.
 * @property CoroutineScope     scope              Where the strip's reruns run.
 * @property Function           rememberOptions    Remembers the options a run or an adjustment landed with.
 */
internal class GenerateMeshHost(
	val session: EditorSession,
	val artRasters: SourceArtRasters,
	val sessionAtlasPages: SessionAtlasPages?,
	val premultipliedAlpha: Boolean,
	val scope: CoroutineScope,
	val rememberOptions: (MeshGenerationOptions) -> Unit,
)

/**
 * The status notice one outcome reports.
 *
 * @property String       messageKey The notice key.
 * @property List<String> arguments  Its counts.
 */
internal data class GenerateMeshNotice(val messageKey: String, val arguments: List<String> = emptyList())

/**
 * The notice an outcome reports: a refused pack first, then the skips a rigger can do something about
 * (pinned art with no room, a mesh over the budget), then the plain count.
 *
 * @param GenerateMeshOutcome outcome The outcome.
 * @return GenerateMeshNotice The notice.
 */
internal fun generateMeshNoticeOf(outcome: GenerateMeshOutcome): GenerateMeshNotice {
	if (outcome is GenerateMeshOutcome.PackRefused) {
		return GenerateMeshNotice("notice.mesh.packRefused")
	}

	val generatedCount = (outcome as? GenerateMeshOutcome.Generated)?.generatedIds?.size ?: 0
	val pinnedCount = outcome.skips.values.count { reason -> reason == GenerateMeshSkipReason.PinnedWithoutRoom }
	val overBudgetCount = outcome.skips.values.count { reason -> reason == GenerateMeshSkipReason.OverBudget }

	return when {
		pinnedCount > 0 -> GenerateMeshNotice("notice.mesh.pinnedWithoutRoom", listOf(generatedCount.toString(), pinnedCount.toString()))
		overBudgetCount > 0 -> GenerateMeshNotice("notice.mesh.overBudget", listOf(generatedCount.toString(), overBudgetCount.toString()))
		generatedCount == 0 -> GenerateMeshNotice("notice.mesh.noneGenerated")
		outcome.skips.isNotEmpty() -> GenerateMeshNotice("notice.mesh.generatedWithSkips", listOf(generatedCount.toString(), outcome.skips.size.toString()))
		else -> GenerateMeshNotice("notice.mesh.generated", listOf(generatedCount.toString()))
	}
}

/**
 * Generates meshes from art for [targets] (and every drawable sharing their art) as ONE undo step and
 * registers the step on the operation settings strip.
 *
 * Runs on the UI thread; the planning, the meshing, and any pack run on the default dispatcher.  The
 * meshes are built against the model current at the start, so any edit landing meanwhile supersedes
 * them.
 *
 * @param GenerateMeshHost      host    The session, art, resolver, scope, and options memory.
 * @param List<DrawableId>      targets The drawables asked for.
 * @param MeshGenerationOptions options The options to run with.
 * @param String?               areaId  The area the command was dispatched over, or null - where the strip shows.
 * @return Boolean Whether any mesh landed.
 */
internal suspend fun runGenerateMesh(host: GenerateMeshHost, targets: List<DrawableId>, options: MeshGenerationOptions, areaId: String?): Boolean {
	val session = host.session
	val modelAtStart = session.model.value
	val minimumAlphaThreshold = modelAtStart.atlas.composition.alphaThreshold
	val landedOptions = MeshGenerationOptions(options.preset, clampedMeshSettings(options.settings, minimumAlphaThreshold))
	val fallbackDecode: (AtlasTileId) -> DecodedImage? = { tileId -> host.artRasters.decodeRaster(tileId) }
	val planned =
		try {
			withContext(Dispatchers.Default) {
				val plan = planMeshGeneration(modelAtStart, targets, fallbackDecode)
				plan to generateMeshOutcome(modelAtStart, plan, landedOptions.settings, plan.decoder(fallbackDecode), host.premultipliedAlpha)
			}
		} catch (invalid: IllegalArgumentException) {
			UmamoLog.error("generate mesh: the run failed; nothing was applied", invalid)
			session.emitNotice("notice.mesh.noneGenerated", NoticePlacement.StatusBar)

			return false
		}
	val plan = planned.first
	val outcome = planned.second
	logOutcome(modelAtStart, outcome)

	if (outcome !is GenerateMeshOutcome.Generated) {
		emitNotice(session, generateMeshNoticeOf(outcome))

		return false
	}

	if (!session.commitGeneratedMeshes(modelAtStart, outcome.model, plan.drawableIds)) {
		UmamoLog.warn("generate mesh: the document changed while the meshes were generated; nothing was applied")
		session.emitNotice("notice.mesh.superseded", NoticePlacement.StatusBar)

		return false
	}

	outcome.textures?.let { textures -> host.sessionAtlasPages?.prewarm(outcome.model.atlas, textures) }
	emitNotice(session, generateMeshNoticeOf(outcome))
	host.rememberOptions(landedOptions)
	registerGenerateMesh(host, outcome.model, areaId, plan, landedOptions, minimumAlphaThreshold)

	return true
}

/**
 * Registers a landed run on the operation settings strip.  Choosing another preset re-seeds the density
 * rows before the rerun reads them; a newer adjustment cancels the rerun still in flight.
 *
 * @param GenerateMeshHost      host                  The host.
 * @param PuppetModel           committed             The model the run committed.
 * @param String?               areaId                Where the strip shows.
 * @param GenerateMeshPlan      plan                  The run's plan, kept for the reruns.
 * @param MeshGenerationOptions options               The options the run landed with.
 * @param Int                   minimumAlphaThreshold The atlas's alpha threshold.
 */
private fun registerGenerateMesh(
	host: GenerateMeshHost,
	committed: PuppetModel,
	areaId: String?,
	plan: GenerateMeshPlan,
	options: MeshGenerationOptions,
	minimumAlphaThreshold: Int,
) {
	val session = host.session
	var seededPreset = options.preset
	var adjustmentJob: Job? = null
	session.registerAdjustableOperation(committed, areaId, meshGenerationParameters(options, minimumAlphaThreshold)) { record ->
		var current = record
		val rowPreset = ArtMeshPreset.fromKey(record.parameters.choiceValue(MeshGenerationParameterKeys.PRESET, seededPreset.key))

		if (rowPreset != seededPreset) {
			seededPreset = rowPreset
			current = session.reviseAdjustableParameters(record, reseededMeshGenerationParameters(record.parameters, rowPreset, minimumAlphaThreshold)) ?: return@registerAdjustableOperation
		}

		adjustmentJob?.cancel()
		val adjusting = current
		adjustmentJob = host.scope.launch { adjustGenerateMesh(host, adjusting, plan, options, minimumAlphaThreshold) }
	}
}

/**
 * Re-meshes for an adjustment of the strip: the record's rows become the options, the SAME plan meshes
 * again over the record's base model, and the result lands over the operation's own step.  An outcome
 * that generates nothing leaves the previous result standing; a newer adjustment, or a record cleared
 * while this one ran, drops it.
 *
 * @param GenerateMeshHost      host                  The host.
 * @param AdjustableOperation   record                The record with the adjusted rows.
 * @param GenerateMeshPlan      plan                  The first run's plan.
 * @param MeshGenerationOptions fallback              The options the first run used.
 * @param Int                   minimumAlphaThreshold The atlas's alpha threshold.
 */
internal suspend fun adjustGenerateMesh(
	host: GenerateMeshHost,
	record: AdjustableOperation,
	plan: GenerateMeshPlan,
	fallback: MeshGenerationOptions,
	minimumAlphaThreshold: Int,
) {
	val session = host.session
	val base = record.baseSnapshot.model
	val options = meshGenerationOptionsOf(record.parameters, fallback, minimumAlphaThreshold)
	val fallbackDecode: (AtlasTileId) -> DecodedImage? = { tileId -> host.artRasters.decodeRaster(tileId) }
	val outcome =
		try {
			withContext(Dispatchers.Default) {
				generateMeshOutcome(base, plan, options.settings, plan.decoder(fallbackDecode), host.premultipliedAlpha)
			}
		} catch (invalid: IllegalArgumentException) {
			UmamoLog.error("generate mesh: the adjustment failed; the previous result stands", invalid)

			return
		}
	logOutcome(base, outcome)

	if (outcome !is GenerateMeshOutcome.Generated) {
		UmamoLog.warn("generate mesh: the adjusted settings generate nothing; the previous result stands")
		emitNotice(session, generateMeshNoticeOf(outcome))

		return
	}

	// Two quick edits of a row can finish out of order; only the result for the rows on show lands.
	if (session.adjustableOperation.value?.parameters != record.parameters) {
		UmamoLog.info("generate mesh: a newer adjustment superseded this one; nothing was applied")

		return
	}

	outcome.textures?.let { textures -> host.sessionAtlasPages?.prewarm(outcome.model.atlas, textures) }

	if (!session.amendLastCommit(record, outcome.model)) {
		UmamoLog.info("generate mesh: the adjustment was superseded before it landed; nothing was applied")

		return
	}

	emitNotice(session, generateMeshNoticeOf(outcome))
	host.rememberOptions(options)
}

/**
 * Logs one outcome: every skipped drawable with its reason, every pack refusal, and the counts.
 *
 * @param PuppetModel         base    The model the run started on, for the names.
 * @param GenerateMeshOutcome outcome The outcome.
 */
private fun logOutcome(base: PuppetModel, outcome: GenerateMeshOutcome) {
	val nameById = base.drawables.associate { drawable -> drawable.id to drawable.name }

	for ((drawableId, reason) in outcome.skips) {
		UmamoLog.info("generate mesh: '${nameById[drawableId] ?: drawableId.raw}' skipped ($reason)")
	}

	when (outcome) {
		is GenerateMeshOutcome.PackRefused ->
			for (refusal in outcome.refusals) {
				UmamoLog.warn("generate mesh: the atlas has no room for '${refusal.tileName}' (${refusal.reason}); nothing was applied")
			}

		is GenerateMeshOutcome.Generated ->
			UmamoLog.info(
				"generate mesh: ${outcome.generatedIds.size} mesh(es) generated, ${outcome.skips.size} skipped, ${outcome.packedTileIds.size} tile(s) packed to a new spot",
			)

		is GenerateMeshOutcome.NothingGenerated -> UmamoLog.warn("generate mesh: nothing generated; ${outcome.skips.size} skipped")
	}
}

/**
 * Emits one outcome's notice on the status bar.
 *
 * @param EditorSession      session The session.
 * @param GenerateMeshNotice notice  The notice.
 */
private fun emitNotice(session: EditorSession, notice: GenerateMeshNotice) {
	session.emitNotice(notice.messageKey, NoticePlacement.StatusBar, notice.arguments)
}
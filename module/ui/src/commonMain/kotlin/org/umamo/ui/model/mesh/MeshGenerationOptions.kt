package org.umamo.ui.model.mesh

import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterChoice
import org.umamo.edit.ParameterUnit
import org.umamo.edit.booleanValue
import org.umamo.edit.choiceValue
import org.umamo.edit.floatValue
import org.umamo.edit.intValue
import org.umamo.interop.art.mesh.ArtMeshPreset
import org.umamo.interop.art.mesh.ArtMeshSettings

/*
 * The settings of Generate Mesh from Art as the operation strip shows them: a Preset row, one row per
 * mesher setting, and Fill Holes.  Every row is clamped into what the mesher accepts, so no value typed
 * into the strip can make the settings refuse.  A preset fills in the density rows (spacings, margins,
 * minimum outline points) and leaves the threshold, the budget, and Fill Holes as they were.
 *
 * The values a run used are remembered for the rest of the app session (never saved), and a first run
 * starts from the preset the settings name.
 */

/** The parameter keys the Generate Mesh from Art rows carry (the strip maps them to strings). */
internal object MeshGenerationParameterKeys {
	const val PRESET = "meshGenerate.preset"
	const val ALPHA_THRESHOLD = "meshGenerate.alphaThreshold"
	const val OUTLINE_SPACING = "meshGenerate.outlineSpacing"
	const val INTERIOR_SPACING = "meshGenerate.interiorSpacing"
	const val OUTER_MARGIN = "meshGenerate.outerMargin"
	const val INNER_MARGIN = "meshGenerate.innerMargin"
	const val MINIMUM_MARGIN = "meshGenerate.minimumMargin"
	const val MINIMUM_OUTLINE_POINTS = "meshGenerate.minimumOutlinePoints"
	const val VERTEX_BUDGET = "meshGenerate.vertexBudget"
	const val FILL_HOLES = "meshGenerate.fillHoles"

	/** The prefix a Preset entry's label key carries in front of its [ArtMeshPreset.key]. */
	const val PRESET_CHOICE_PREFIX = "meshGenerate.preset."
}

/** The widest spacing the strip offers, in art pixels. */
private const val MAXIMUM_SPACING: Float = 512f

/** The widest outer margin the strip offers, in art pixels. */
private const val MAXIMUM_OUTER_MARGIN: Float = 64f

/** The deepest inner margin the strip offers, in art pixels. */
private const val MAXIMUM_INNER_MARGIN: Float = 256f

/** The smallest minimum margin the mesher accepts, in art pixels. */
private const val SMALLEST_MINIMUM_MARGIN: Float = 0.5f

/** The most outline points one ring may be asked for. */
private const val MAXIMUM_OUTLINE_POINTS: Int = 256

/** The smallest vertex budget the strip offers. */
private const val SMALLEST_VERTEX_BUDGET: Int = 16

/** The largest vertex budget the strip offers: a delta texture this tall draws on any desktop GPU and most tablets. */
internal const val MAXIMUM_VERTEX_BUDGET: Int = 4096

/**
 * What one Generate Mesh from Art run uses: the preset its rows were last seeded from, and the
 * settings the mesher receives.
 *
 * @property ArtMeshPreset   preset   The preset the Preset row shows.
 * @property ArtMeshSettings settings The mesher settings, already clamped.
 */
internal data class MeshGenerationOptions(val preset: ArtMeshPreset, val settings: ArtMeshSettings) {
	companion object {
		/**
		 * The options a preset stands for, as they are.
		 *
		 * @param ArtMeshPreset preset The preset.
		 * @return MeshGenerationOptions The options.
		 */
		fun of(preset: ArtMeshPreset): MeshGenerationOptions = MeshGenerationOptions(preset, preset.settings)
	}
}

/**
 * The settings clamped into what the strip offers and the mesher accepts, with the alpha threshold
 * never below the atlas's own (art the atlas would not draw is never meshed as art).
 *
 * @param ArtMeshSettings settings              The settings to clamp.
 * @param Int             minimumAlphaThreshold The atlas's alpha threshold.
 * @return ArtMeshSettings The clamped settings.
 */
internal fun clampedMeshSettings(settings: ArtMeshSettings, minimumAlphaThreshold: Int): ArtMeshSettings =
	clampedMeshSettingsOf(
		settings.alphaThreshold,
		settings.outlineSpacing,
		settings.interiorSpacing,
		settings.outerMargin,
		settings.innerMargin,
		settings.minimumMargin,
		settings.minimumOutlinePoints,
		settings.vertexBudget,
		settings.fillHoles,
		minimumAlphaThreshold,
	)

/**
 * Mesher settings from raw row values, each clamped into what the strip offers before the settings
 * are built (they refuse some of the values a row can carry); a value that is not finite falls back to
 * its range's floor.
 *
 * @param Int     alphaThreshold        The threshold.
 * @param Double  outlineSpacing        The outline spacing, art pixels.
 * @param Double  interiorSpacing       The interior spacing.
 * @param Double  outerMargin           The outer margin.
 * @param Double  innerMargin           The inner margin.
 * @param Double  minimumMargin         The minimum margin.
 * @param Int     minimumOutlinePoints  The minimum outline points.
 * @param Int     vertexBudget          The vertex budget.
 * @param Boolean fillHoles             Whether holes are filled.
 * @param Int     minimumAlphaThreshold The atlas's alpha threshold, the threshold's floor.
 * @return ArtMeshSettings The settings.
 */
private fun clampedMeshSettingsOf(
	alphaThreshold: Int,
	outlineSpacing: Double,
	interiorSpacing: Double,
	outerMargin: Double,
	innerMargin: Double,
	minimumMargin: Double,
	minimumOutlinePoints: Int,
	vertexBudget: Int,
	fillHoles: Boolean,
	minimumAlphaThreshold: Int,
): ArtMeshSettings {
	val minimumPoints = minimumOutlinePoints.coerceIn(3, MAXIMUM_OUTLINE_POINTS)

	return ArtMeshSettings(
		alphaThreshold = alphaThreshold.coerceIn(minimumAlphaThreshold.coerceIn(1, 255), 255),
		outlineSpacing = finiteOr(outlineSpacing, 1.0).coerceIn(1.0, MAXIMUM_SPACING.toDouble()),
		interiorSpacing = finiteOr(interiorSpacing, 1.0).coerceIn(1.0, MAXIMUM_SPACING.toDouble()),
		outerMargin = finiteOr(outerMargin, 0.0).coerceIn(0.0, MAXIMUM_OUTER_MARGIN.toDouble()),
		innerMargin = finiteOr(innerMargin, 0.0).coerceIn(0.0, MAXIMUM_INNER_MARGIN.toDouble()),
		minimumMargin = finiteOr(minimumMargin, SMALLEST_MINIMUM_MARGIN.toDouble()).coerceIn(SMALLEST_MINIMUM_MARGIN.toDouble(), MAXIMUM_OUTER_MARGIN.toDouble()),
		minimumOutlinePoints = minimumPoints,
		vertexBudget = vertexBudget.coerceIn(maxOf(SMALLEST_VERTEX_BUDGET, minimumPoints), MAXIMUM_VERTEX_BUDGET),
		fillHoles = fillHoles,
	)
}

/**
 * A value, or a fallback when it is not finite.
 *
 * @param Double value    The value.
 * @param Double fallback The fallback.
 * @return Double The value when finite, else the fallback.
 */
private fun finiteOr(value: Double, fallback: Double): Double = if (value.isFinite()) value else fallback

/**
 * The strip's rows for a run with [options].
 *
 * @param MeshGenerationOptions options               The options the rows show.
 * @param Int                   minimumAlphaThreshold The atlas's alpha threshold, the threshold row's floor.
 * @return List<OperatorParameter> The rows.
 */
internal fun meshGenerationParameters(options: MeshGenerationOptions, minimumAlphaThreshold: Int): List<OperatorParameter> {
	val settings = clampedMeshSettings(options.settings, minimumAlphaThreshold)

	return listOf(
		OperatorParameter.ChoiceParameter(
			MeshGenerationParameterKeys.PRESET,
			MeshGenerationParameterKeys.PRESET,
			options.preset.key,
			ArtMeshPreset.entries.map { preset -> ParameterChoice(preset.key, MeshGenerationParameterKeys.PRESET_CHOICE_PREFIX + preset.key) },
		),
		OperatorParameter.IntParameter(MeshGenerationParameterKeys.ALPHA_THRESHOLD, MeshGenerationParameterKeys.ALPHA_THRESHOLD, settings.alphaThreshold, minimumAlphaThreshold.coerceIn(1, 255), 255),
		pixelsRow(MeshGenerationParameterKeys.OUTLINE_SPACING, settings.outlineSpacing, 1f, MAXIMUM_SPACING),
		pixelsRow(MeshGenerationParameterKeys.INTERIOR_SPACING, settings.interiorSpacing, 1f, MAXIMUM_SPACING),
		pixelsRow(MeshGenerationParameterKeys.OUTER_MARGIN, settings.outerMargin, 0f, MAXIMUM_OUTER_MARGIN),
		pixelsRow(MeshGenerationParameterKeys.INNER_MARGIN, settings.innerMargin, 0f, MAXIMUM_INNER_MARGIN),
		pixelsRow(MeshGenerationParameterKeys.MINIMUM_MARGIN, settings.minimumMargin, SMALLEST_MINIMUM_MARGIN, MAXIMUM_OUTER_MARGIN, step = 0.5f),
		OperatorParameter.IntParameter(MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS, MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS, settings.minimumOutlinePoints, 3, MAXIMUM_OUTLINE_POINTS),
		OperatorParameter.IntParameter(MeshGenerationParameterKeys.VERTEX_BUDGET, MeshGenerationParameterKeys.VERTEX_BUDGET, settings.vertexBudget, SMALLEST_VERTEX_BUDGET, MAXIMUM_VERTEX_BUDGET, step = 16),
		OperatorParameter.BooleanParameter(MeshGenerationParameterKeys.FILL_HOLES, MeshGenerationParameterKeys.FILL_HOLES, settings.fillHoles),
	)
}

/**
 * One row of art pixels.
 *
 * @param String key     The row's key, also its label key.
 * @param Double value   The value.
 * @param Float  minimum The smallest value.
 * @param Float  maximum The largest value.
 * @param Float  step    The step the field nudges by.
 * @return OperatorParameter The row.
 */
private fun pixelsRow(key: String, value: Double, minimum: Float, maximum: Float, step: Float = 1f): OperatorParameter =
	OperatorParameter.FloatParameter(key, key, value.toFloat(), minimum, maximum, step, ParameterUnit.Pixels)

/**
 * The options the strip's rows describe, over [fallback] for anything a row does not carry, clamped.
 *
 * @param List<OperatorParameter> parameters            The strip's rows.
 * @param MeshGenerationOptions   fallback              The options the first run used.
 * @param Int                     minimumAlphaThreshold The atlas's alpha threshold.
 * @return MeshGenerationOptions The options.
 */
internal fun meshGenerationOptionsOf(parameters: List<OperatorParameter>, fallback: MeshGenerationOptions, minimumAlphaThreshold: Int): MeshGenerationOptions {
	val base = fallback.settings
	val settings =
		clampedMeshSettingsOf(
			alphaThreshold = parameters.intValue(MeshGenerationParameterKeys.ALPHA_THRESHOLD, base.alphaThreshold),
			outlineSpacing = parameters.floatValue(MeshGenerationParameterKeys.OUTLINE_SPACING, base.outlineSpacing.toFloat()).toDouble(),
			interiorSpacing = parameters.floatValue(MeshGenerationParameterKeys.INTERIOR_SPACING, base.interiorSpacing.toFloat()).toDouble(),
			outerMargin = parameters.floatValue(MeshGenerationParameterKeys.OUTER_MARGIN, base.outerMargin.toFloat()).toDouble(),
			innerMargin = parameters.floatValue(MeshGenerationParameterKeys.INNER_MARGIN, base.innerMargin.toFloat()).toDouble(),
			minimumMargin = parameters.floatValue(MeshGenerationParameterKeys.MINIMUM_MARGIN, base.minimumMargin.toFloat()).toDouble(),
			minimumOutlinePoints = parameters.intValue(MeshGenerationParameterKeys.MINIMUM_OUTLINE_POINTS, base.minimumOutlinePoints),
			vertexBudget = parameters.intValue(MeshGenerationParameterKeys.VERTEX_BUDGET, base.vertexBudget),
			fillHoles = parameters.booleanValue(MeshGenerationParameterKeys.FILL_HOLES, base.fillHoles),
			minimumAlphaThreshold = minimumAlphaThreshold,
		)
	val preset = ArtMeshPreset.fromKey(parameters.choiceValue(MeshGenerationParameterKeys.PRESET, fallback.preset.key))

	return MeshGenerationOptions(preset, settings)
}

/**
 * The rows re-seeded from a preset: the Preset row shows it and the density rows take its numbers,
 * while the threshold, the budget, and Fill Holes keep what the rows held.
 *
 * @param List<OperatorParameter> parameters            The rows as they stand.
 * @param ArtMeshPreset           preset                The preset to seed from.
 * @param Int                     minimumAlphaThreshold The atlas's alpha threshold.
 * @return List<OperatorParameter> The re-seeded rows.
 */
internal fun reseededMeshGenerationParameters(parameters: List<OperatorParameter>, preset: ArtMeshPreset, minimumAlphaThreshold: Int): List<OperatorParameter> {
	val current = meshGenerationOptionsOf(parameters, MeshGenerationOptions.of(preset), minimumAlphaThreshold).settings
	val seeded =
		preset.settings.copy(
			alphaThreshold = current.alphaThreshold,
			vertexBudget = current.vertexBudget,
			fillHoles = current.fillHoles,
		)

	return meshGenerationParameters(MeshGenerationOptions(preset, seeded), minimumAlphaThreshold)
}

/**
 * The options Generate Mesh from Art remembers between runs for the rest of the app session - never
 * saved, the same for every document (every value is in art pixels).  Before any run it starts from the
 * preset the settings name.
 */
internal class MeshGenerationSessionOptions {
	private var remembered: MeshGenerationOptions? = null

	/**
	 * The options the next run starts from.
	 *
	 * @param String? seedPresetKey The preset key the settings hold, or null.
	 * @return MeshGenerationOptions The remembered options, or the seeded preset's.
	 */
	fun optionsFor(seedPresetKey: String?): MeshGenerationOptions = remembered ?: MeshGenerationOptions.of(ArtMeshPreset.fromKey(seedPresetKey))

	/**
	 * Remembers the options a run landed with.
	 *
	 * @param MeshGenerationOptions options The options.
	 */
	fun record(options: MeshGenerationOptions) {
		remembered = options
	}
}
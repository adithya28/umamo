package org.umamo.format.art

/**
 * A layer-sized image read one alpha byte at a time - the only channel the alpha analysis and the
 * meshing field look at.  A [LayerRaster] answers through [alphaChannel]; a caller that meshes part
 * of a tile answers with the tile's alpha inside its region and zero outside it, without copying the
 * tile's pixels.
 */
public interface AlphaChannel {
	/** The width in pixels. */
	public val width: Int

	/** The height in pixels. */
	public val height: Int

	/**
	 * One pixel's alpha.
	 *
	 * @param Int column The pixel's column, 0 until [width].
	 * @param Int row    The pixel's row, 0 until [height].
	 * @return Int The alpha, 0..255.
	 */
	public fun alphaAt(column: Int, row: Int): Int
}

/**
 * This raster's alpha channel.
 *
 * @return AlphaChannel The channel, reading the raster's alpha bytes in place.
 */
public fun LayerRaster.alphaChannel(): AlphaChannel {
	require(rgba.size.toLong() == width.toLong() * height.toLong() * 4L) { "rgba size ${rgba.size} does not match $width x $height x 4" }

	return RasterAlphaChannel(this)
}

/**
 * A raster's alpha bytes, read in place.
 *
 * @property LayerRaster raster The raster.
 */
private class RasterAlphaChannel(private val raster: LayerRaster) : AlphaChannel {
	override val width: Int
		get() = raster.width

	override val height: Int
		get() = raster.height

	// The `and 0xFF` is load-bearing: Kotlin Byte is signed.
	override fun alphaAt(column: Int, row: Int): Int = raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF
}
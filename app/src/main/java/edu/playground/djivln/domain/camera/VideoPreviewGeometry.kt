package edu.playground.djivln.domain.camera

import kotlin.math.roundToInt

/** Layout only: never crops or resizes saved frames or model inputs. */
object VideoPreviewGeometry {
    data class Size(val width: Int, val height: Int) {
        init { require(width > 0 && height > 0) }
    }

    fun fitInside(source: Size, maxWidth: Int, maxHeight: Int): Size {
        require(maxWidth > 0 && maxHeight > 0)
        val scale = minOf(maxWidth.toDouble() / source.width, maxHeight.toDouble() / source.height)
        return Size(
            (source.width * scale).roundToInt().coerceIn(1, maxWidth),
            (source.height * scale).roundToInt().coerceIn(1, maxHeight),
        )
    }
}

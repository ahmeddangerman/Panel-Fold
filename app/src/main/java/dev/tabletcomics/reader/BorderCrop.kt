package dev.tabletcomics.reader

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

internal object BorderCrop {
    /** Read a small preview on a worker dispatcher; keep a safety rim and at least 80% per axis. */
    suspend fun detect(bitmap: Bitmap): Rect = withContext(Dispatchers.Default) {
        val columns = min(bitmap.width, 512)
        val rows = min(bitmap.height, 512)
        val pixels = IntArray(columns * rows)
        for (y in 0 until rows) {
            coroutineContext.ensureActive()
            for (x in 0 until columns) pixels[y * columns + x] = bitmap.getPixel(x * bitmap.width / columns, y * bitmap.height / rows)
        }
        detect(columns, rows, pixels)
    }

    fun detect(width: Int, height: Int, pixels: IntArray): Rect {
        if (width < 32 || height < 32 || pixels.size != width * height) return ReaderGeometry.fullCrop
        fun tone(pixel: Int): Int {
            if ((pixel ushr 24) < 250) return -1
            val r = pixel shr 16 and 255
            val g = pixel shr 8 and 255
            val b = pixel and 255
            return when {
                min(r, min(g, b)) >= 246 -> 1
                max(r, max(g, b)) <= 9 -> 0
                else -> -1
            }
        }
        val border = tone(pixels[0])
        if (border < 0 || tone(pixels[width - 1]) != border || tone(pixels[(height - 1) * width]) != border || tone(pixels.last()) != border) return ReaderGeometry.fullCrop
        fun blankRow(y: Int) = (0 until width).all { tone(pixels[y * width + it]) == border }
        fun blankColumn(x: Int) = (0 until height).all { tone(pixels[it * width + x]) == border }
        val maxX = width / 10
        val maxY = height / 10
        fun scan(limit: Int, blank: (Int) -> Boolean): Int {
            var amount = 0
            while (amount <= limit && blank(amount)) amount++
            // A margin extending beyond the conservative limit might be intentional art.
            return if (amount > limit) 0 else (amount - 2).coerceAtLeast(0)
        }
        val left = scan(maxX, ::blankColumn)
        val right = scan(maxX) { blankColumn(width - 1 - it) }
        val top = scan(maxY, ::blankRow)
        val bottom = scan(maxY) { blankRow(height - 1 - it) }
        return Rect(left.toFloat() / width, top.toFloat() / height, 1f - right.toFloat() / width, 1f - bottom.toFloat() / height)
    }
}

package dev.tabletcomics.reader

import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

/** The stationary face and reflected lifted paper use the same image renderer and sharp tiles. */
internal fun DrawScope.drawCornerPageTurn(
    bounds: Rect,
    corner: PageCorner,
    draggedCorner: Offset,
    drawIncoming: DrawScope.() -> Unit,
    drawCurrent: DrawScope.() -> Unit,
) {
    val fold = CornerFoldGeometry.calculate(bounds.size, corner, draggedCorner)
    if (fold == null) {
        drawCurrent()
        return
    }
    drawIncoming()
    withTransform({ translate(bounds.left, bounds.top) }) {
        val front = polygonPath(fold.frontPolygon)
        if (fold.frontPolygon.isNotEmpty()) clipPath(front) {
            withTransform({ translate(-bounds.left, -bounds.top) }) { drawCurrent() }
        }
        val back = polygonPath(fold.backPolygon)
        if (fold.backPolygon.isNotEmpty()) {
            // Narrow shadows along the lifted sheet make the bend visible without warping its art.
            for ((width, alpha) in listOf(18f to .025f, 10f to .045f, 4f to .12f)) {
                drawPath(back, Color.Black.copy(alpha = alpha), style = Stroke(width.dp.toPx()))
            }
            clipPath(back) {
                val canvas = drawContext.canvas.nativeCanvas
                val checkpoint = canvas.save()
                try {
                    canvas.concat(Matrix().apply { setValues(fold.reflection.matrixValues()) })
                    withTransform({ translate(-bounds.left, -bounds.top) }) { drawCurrent() }
                } finally {
                    canvas.restoreToCount(checkpoint)
                }
                // Paper is opaque, with a little of the reflected printing showing through its back.
                drawPath(back, Color(0xFFF5F1E8).copy(alpha = .86f))
            }
            drawPath(back, Color.Black.copy(alpha = .16f), style = Stroke(.75.dp.toPx()))
        }
        fold.crease?.let { crease ->
            val radius = 7.dp.toPx()
            val normal = fold.normal * radius
            val band = polygonPath(listOf(crease.start - normal, crease.end - normal,
                crease.end + normal, crease.start + normal))
            drawPath(band, Brush.linearGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = .16f), Color.White.copy(alpha = .28f), Color.Transparent),
                start = fold.midpoint - normal, end = fold.midpoint + normal,
            ))
            drawLine(Color.White.copy(alpha = .42f), crease.start, crease.end, .75.dp.toPx())
        }
    }
}

private fun polygonPath(points: List<Offset>): Path = Path().apply {
    if (points.isNotEmpty()) {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
}

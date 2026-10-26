package dev.tabletcomics.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

internal object ZoomMotion {
    fun frame(start: ZoomTransform, target: ZoomTransform, fraction: Float): ZoomTransform {
        val t = fraction.coerceIn(0f, 1f)
        return ZoomTransform(start.scale + (target.scale - start.scale) * t, start.pan + (target.pan - start.pan) * t)
    }

    fun glideTarget(start: ZoomTransform, velocity: Offset, viewport: Size, content: Rect, momentumStrength: Float = 1f): ZoomTransform {
        val strength = if (momentumStrength.isFinite()) momentumStrength.coerceIn(0f, 3f) else 1f
        val limited = Offset(velocity.x.coerceIn(-6000f, 6000f), velocity.y.coerceIn(-6000f, 6000f))
        return start.copy(pan = ZoomBounds.clampPan(viewport, content, start.scale, start.pan + limited * (.18f * strength)))
    }
}

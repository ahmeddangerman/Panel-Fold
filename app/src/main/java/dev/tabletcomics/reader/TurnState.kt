package dev.tabletcomics.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.math.abs
import kotlin.math.sign

internal data class TurnSnapshot(val current: ReaderUnit, val previous: ReaderUnit?, val next: ReaderUnit?) {
    /** Null is captured availability too; a late prefetch belongs to the next gesture. */
    fun neighbor(offset: Float, rightToLeft: Boolean): ReaderUnit? =
        if ((offset < 0f) xor rightToLeft) next else previous
}
internal data class TurnSettle(val start: Float, val destination: Float, val target: ReaderUnit?)

/** Owns one gesture's page snapshot and guarantees every finish/reset path clears it. */
internal class TurnState {
    var snapshot: TurnSnapshot? by mutableStateOf(null)
        private set
    var offset: Float by mutableFloatStateOf(0f)
        private set
    var settling: Boolean by mutableStateOf(false)
        private set
    private var pendingTarget: ReaderUnit? = null
    var committedUnit: ReaderUnit? by mutableStateOf(null)
        private set
    private var committedOriginIndex: Int? = null

    val hasSnapshot: Boolean get() = snapshot != null

    fun capture(current: ReaderUnit, previous: ReaderUnit?, next: ReaderUnit?) {
        if (settling || snapshot != null || committedUnit != null) return
        snapshot = TurnSnapshot(current, previous, next)
    }

    fun dragTo(value: Float, hasNeighbor: Boolean) {
        if (snapshot == null || settling) return
        offset = if (hasNeighbor) value else 0f
    }

    fun release(
        velocity: Float,
        width: Float,
        rightToLeft: Boolean,
        cancelled: Boolean = false,
    ): TurnSettle? {
        val start = offset
        val frame = snapshot
        if (frame == null || start == 0f || width <= 0f) {
            reset()
            return null
        }
        val neighbor = frame.neighbor(start, rightToLeft)
        val target = neighbor?.takeIf {
            !cancelled && TurnNavigation.shouldComplete(start, velocity, width)
        }
        settling = true
        pendingTarget = target
        val destination = if (target != null) sign(start) * width else 0f
        return TurnSettle(start, destination, target)
    }

    fun updateAnimatedOffset(value: Float) {
        if (settling) offset = value
    }

    fun completeSettle(onCommit: (ReaderUnit) -> Unit = {}): ReaderUnit? = Snapshot.withMutableSnapshot {
        val target = pendingTarget
        // Publish the incoming page and its zoom before removing the outgoing frame.
        if (target != null) {
            committedOriginIndex = snapshot?.current?.startIndex
            committedUnit = target
            onCommit(target)
        }
        clearGesture()
        target
    }

    /** Keep the settled frame until the parent publishes a new current page (or an explicit seek). */
    fun acknowledge(currentIndex: Int) {
        if (committedUnit != null && currentIndex != committedOriginIndex) {
            committedUnit = null
            committedOriginIndex = null
        }
    }

    fun reset() {
        committedUnit = null
        committedOriginIndex = null
        clearGesture()
    }

    fun clearGesture() {
        snapshot = null
        offset = 0f
        settling = false
        pendingTarget = null
    }
}

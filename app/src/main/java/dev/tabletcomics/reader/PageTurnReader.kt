package dev.tabletcomics.reader

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

enum class PageTurnStyle(val label: String) {
    PHOTO_SLIDE("Photo swipe"), PAPER_CURL("Paper curl"), SLIDING_SHEET("Sliding sheet")
}

data class FullResolutionPage(val pageIndex: Int, val sourceFile: File, val width: Int, val height: Int)

data class ReaderUnit(
    val startIndex: Int,
    val pages: List<Pair<Int, Bitmap>>,
    val fullResolutionPages: Map<Int, FullResolutionPage> = emptyMap(),
)

private data class DetailTile(val pageIndex: Int, val region: Rect, val bitmap: Bitmap)

/** Neighbors are decoded before they may be dragged into view. Progress is committed only after settling. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageTurnReader(
    current: ReaderUnit,
    previous: ReaderUnit?,
    next: ReaderUnit?,
    rightToLeft: Boolean,
    style: PageTurnStyle,
    layoutKey: String,
    onTurn: (target: ReaderUnit) -> Unit,
    onToggleControls: () -> Unit,
    onEndReached: () -> Unit,
    showZoomReset: Boolean = true,
    onDecodeRegions: suspend (FullResolutionPage, List<Rect>) -> List<Bitmap?>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var width by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val turnState = remember { TurnState() }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var committedUnit by remember { mutableStateOf<ReaderUnit?>(null) }
    var zoomTransform by remember(current.startIndex, layoutKey) { mutableStateOf(ZoomTransform.Identity) }
    var detailTiles by remember(current.startIndex, layoutKey) { mutableStateOf<List<DetailTile>>(emptyList()) }
    val latestCurrent = rememberUpdatedState(current)
    val latestPrevious = rememberUpdatedState(previous)
    val latestNext = rememberUpdatedState(next)
    val latestSettling = rememberUpdatedState(turnState.settling)
    val turn = rememberUpdatedState(onTurn)
    val toggle = rememberUpdatedState(onToggleControls)
    val endReached = rememberUpdatedState(onEndReached)
    val decodeRegions = rememberUpdatedState(onDecodeRegions)
    val tracker = remember { VelocityTracker() }
    var motionJob by remember { mutableStateOf<Job?>(null) }
    fun animateZoom(target: ZoomTransform, duration: Int = 280) {
        motionJob?.cancel()
        val start = zoomTransform
        motionJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(duration, easing = LinearOutSlowInEasing)) { fraction, _ ->
                zoomTransform = ZoomMotion.frame(start, target, fraction)
            }
        }
    }
    fun resetZoom() {
        animateZoom(ZoomTransform.Identity)
    }
    fun glide(velocity: Offset) {
        if (zoomTransform.scale <= 1.01f) return
        val fitted = ZoomBounds.fittedContentRect(viewport, latestCurrent.value.pages.map { (_, bitmap) ->
            Size(bitmap.width.toFloat(), bitmap.height.toFloat())
        })
        val target = ZoomMotion.glideTarget(zoomTransform, velocity * 1.5f, viewport, fitted)
        if (target.pan != zoomTransform.pan) animateZoom(target, 420)
    }

    fun zoomToPageWidth(tapPosition: Offset) {
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val pageSizes = latestCurrent.value.pages.map { (_, bitmap) ->
            Size(bitmap.width.toFloat(), bitmap.height.toFloat())
        }
        if (pageSizes.isEmpty()) return
        val slotWidth = viewport.width / pageSizes.size
        val pageIndex = (tapPosition.x / slotWidth).toInt().coerceIn(pageSizes.indices)
        animateZoom(ZoomBounds.fitPageToWidth(viewport, pageSizes, pageIndex))
    }

    fun onDoubleTap(position: Offset) {
        if (zoomTransform.scale > 1.01f) resetZoom() else zoomToPageWidth(position)
    }

    val transform = rememberTransformableState { scale, translation, _ ->
        motionJob?.cancel()
        val fitted = ZoomBounds.fittedContentRect(viewport, latestCurrent.value.pages.map { (_, bitmap) ->
            Size(bitmap.width.toFloat(), bitmap.height.toFloat())
        })
        zoomTransform = zoomTransform.transformBy(scale, translation, viewport, fitted)
    }

    LaunchedEffect(viewport, current.startIndex, layoutKey) {
        val fitted = ZoomBounds.fittedContentRect(viewport, current.pages.map { (_, bitmap) ->
            Size(bitmap.width.toFloat(), bitmap.height.toFloat())
        })
        zoomTransform = zoomTransform.copy(
            pan = ZoomBounds.clampPan(viewport, fitted, zoomTransform.scale, zoomTransform.pan),
        )
    }

    LaunchedEffect(current.startIndex, layoutKey, style) {
        settleJob?.cancel()
        motionJob?.cancel()
        turnState.reset()
        committedUnit = null
    }

    LaunchedEffect(current.startIndex, layoutKey, viewport, zoomTransform.scale, zoomTransform.pan, current.fullResolutionPages) {
        if (zoomTransform.scale <= 1.05f || viewport.width <= 0f || viewport.height <= 0f) {
            detailTiles = emptyList()
            return@LaunchedEffect
        }
        delay(90)
        detailTiles = emptyList()
        val result = mutableListOf<DetailTile>()
        val slotWidth = viewport.width / current.pages.size.coerceAtLeast(1)
        val viewportLeft = -zoomTransform.pan.x / zoomTransform.scale
        val viewportTop = -zoomTransform.pan.y / zoomTransform.scale
        val viewportRight = (viewport.width - zoomTransform.pan.x) / zoomTransform.scale
        val viewportBottom = (viewport.height - zoomTransform.pan.y) / zoomTransform.scale
        current.pages.forEachIndexed { pageSlot, (pageIndex, preview) ->
            val source = current.fullResolutionPages[pageIndex] ?: return@forEachIndexed
            val imageScale = min(slotWidth / preview.width, viewport.height / preview.height)
            val imageWidth = preview.width * imageScale
            val imageHeight = preview.height * imageScale
            val imageLeft = slotWidth * pageSlot + (slotWidth - imageWidth) / 2f
            val imageTop = (viewport.height - imageHeight) / 2f
            val left = maxOf(imageLeft, viewportLeft)
            val top = maxOf(imageTop, viewportTop)
            val right = minOf(imageLeft + imageWidth, viewportRight)
            val bottom = minOf(imageTop + imageHeight, viewportBottom)
            if (right <= left || bottom <= top) return@forEachIndexed
            val region = Rect(
                ((left - imageLeft) / imageScale * source.width / preview.width).toInt().coerceIn(0, source.width - 1),
                ((top - imageTop) / imageScale * source.height / preview.height).toInt().coerceIn(0, source.height - 1),
                (((right - imageLeft) / imageScale * source.width / preview.width).toInt() + 1).coerceIn(1, source.width),
                (((bottom - imageTop) / imageScale * source.height / preview.height).toInt() + 1).coerceIn(1, source.height),
            )
            val tileSize = 512
            val regions = mutableListOf<Rect>()
            tileLoop@ for (top in region.top until region.bottom step tileSize) {
                for (left in region.left until region.right step tileSize) {
                    if (result.size + regions.size >= 20) break@tileLoop
                    val tileRegion = Rect(left, top, minOf(left + tileSize, region.right), minOf(top + tileSize, region.bottom))
                    regions += tileRegion
                }
            }
            decodeRegions.value(source, regions).forEachIndexed { index, bitmap ->
                bitmap?.let { result += DetailTile(pageIndex, regions[index], it) }
            }
        }
        detailTiles = result
    }

    fun neighborFor(offset: Float): ReaderUnit? {
        val forward = (offset < 0f) xor rightToLeft
        val frame = turnState.snapshot
        return if (forward) frame?.next ?: latestNext.value else frame?.previous ?: latestPrevious.value
    }

    fun settle(velocity: Float, cancelled: Boolean = false) {
        val captured = turnState.release(velocity / density, width, rightToLeft, cancelled)
            ?: return
        val remaining = (abs(captured.destination - captured.start) / width).coerceIn(0f, 1f)
        settleJob = scope.launch {
            try {
                animate(captured.start, captured.destination, animationSpec = tween((100 + remaining * 140).toInt())) { value, _ ->
                    turnState.updateAnimatedOffset(value)
                }
                val target = turnState.completeSettle()
                if (target != null) {
                    committedUnit = target
                    turn.value(target)
                }
            } finally {
                if (turnState.settling) turnState.reset()
            }
        }
    }

    Box(modifier.fillMaxSize().clipToBounds().onSizeChanged {
        width = it.width.toFloat()
        viewport = Size(it.width.toFloat(), it.height.toFloat())
    }.pointerInput(current.startIndex, layoutKey, style, rightToLeft) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                motionJob?.cancel()
                if (latestSettling.value) return@awaitEachGesture
                tracker.resetTracking()
                tracker.addPosition(down.uptimeMillis, down.position)
                var dragging = false
                var dragOrigin = 0f
                var lastPosition = down.position
                val zoomEdgeSwipe = ZoomEdgeSwipe()
                var cancelled = false
                var forwardBoundaryOffset: Float? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    if (event.changes.count { it.pressed } > 1 || (change.isConsumed && dragging)) {
                        cancelled = true
                        break
                    }
                    val pointerDelta = change.position - lastPosition
                    lastPosition = change.position
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val movement = change.position - down.position
                    if (!change.pressed) {
                        val velocity = tracker.calculateVelocity()
                        if (dragging) {
                            val boundaryOffset = forwardBoundaryOffset
                            settle(velocity.x)
                            if (boundaryOffset != null && TurnNavigation.shouldComplete(boundaryOffset / density,
                                    velocity.x / density, width / density)) endReached.value()
                        }
                        else if (movement.getDistance() > viewConfiguration.touchSlop) glide(Offset(velocity.x, velocity.y))
                        break
                    }
                    if (zoomTransform.scale > 1.01f && !dragging) {
                        val fitted = ZoomBounds.fittedContentRect(viewport, latestCurrent.value.pages.map { (_, bitmap) ->
                            Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                        })
                        val horizontal = abs(pointerDelta.x) > abs(pointerDelta.y)
                        val canTurn = horizontal && ZoomBounds.canTurnAtHorizontalEdge(
                            viewport, fitted, zoomTransform.scale, zoomTransform.pan, pointerDelta.x,
                        )
                        val edgeOffset = zoomEdgeSwipe.drag(pointerDelta.x, canTurn, viewConfiguration.touchSlop)
                            ?: continue
                        dragging = true
                        dragOrigin = movement.x - edgeOffset
                        turnState.capture(latestCurrent.value, latestPrevious.value, latestNext.value)
                    }
                    if (!dragging && abs(movement.x) > viewConfiguration.touchSlop && abs(movement.x) > abs(movement.y)) {
                        dragging = true
                        dragOrigin = sign(movement.x) * viewConfiguration.touchSlop
                        turnState.capture(latestCurrent.value, latestPrevious.value, latestNext.value)
                    }
                    if (dragging) {
                        val proposed = TurnNavigation.offsetAfterTouchSlop(movement.x, dragOrigin).coerceIn(-width, width)
                        val hasNeighbor = neighborFor(proposed) != null
                        val forward = (proposed < 0f) xor rightToLeft
                        forwardBoundaryOffset = if (!hasNeighbor && forward) proposed else null
                        turnState.dragTo(proposed, hasNeighbor = hasNeighbor)
                        change.consume()
                    }
                }
                if (cancelled) settle(0f, cancelled = true)
            }
        }.pointerInput(current.startIndex, layoutKey) {
            detectTapGestures(
                onTap = { toggle.value() },
                onDoubleTap = ::onDoubleTap,
            )
        }) {
        Canvas(Modifier.fillMaxSize().transformable(state = transform, canPan = { zoomTransform.scale > 1.01f }).graphicsLayer {
            scaleX = zoomTransform.scale; scaleY = zoomTransform.scale
            translationX = zoomTransform.pan.x; translationY = zoomTransform.pan.y
        }) {
            drawRect(Color.Black)
            val frame = turnState.snapshot
            val drag = ZoomBounds.canvasOffsetForViewportDrag(turnState.offset, zoomTransform.scale)
            val turnWidth = ZoomBounds.canvasTurnWidth(size.width, zoomTransform.scale)
            val drawnCurrent = committedUnit ?: frame?.current ?: current
            val drawnPrevious = frame?.previous ?: previous
            val drawnNext = frame?.next ?: next
            val target = if ((drag < 0f) xor rightToLeft) drawnNext else drawnPrevious
            if (abs(drag) < 0.1f || target == null) {
                drawUnit(drawnCurrent)
            } else when (style) {
                PageTurnStyle.PHOTO_SLIDE -> {
                    drawUnit(target, ZoomBounds.pageTurnNeighborOffset(turnState.offset, size.width, zoomTransform.scale))
                    drawUnit(drawnCurrent, drag)
                }
                PageTurnStyle.SLIDING_SHEET -> {
                    drawUnit(target)
                    val edge = if (drag < 0f) turnWidth + drag else drag
                    val shadowWidth = min(24f * density / zoomTransform.scale, turnWidth * 0.05f)
                    val left = if (drag < 0f) edge else edge - shadowWidth
                    val colors = if (drag < 0f) listOf(Color.Black.copy(alpha = .5f), Color.Transparent)
                        else listOf(Color.Transparent, Color.Black.copy(alpha = .5f))
                    drawRect(Brush.horizontalGradient(colors, left, left + shadowWidth), Offset(left, 0f), Size(shadowWidth, size.height))
                    drawUnit(drawnCurrent, drag)
                }
                PageTurnStyle.PAPER_CURL -> drawCurl(drawnCurrent, target, drag, turnWidth)
            }
            if (abs(drag) < 0.1f) {
                detailTiles.forEach { tile ->
                    drawRegionTile(drawnCurrent, tile.pageIndex, tile.region, tile.bitmap)
                }
            }
        }
        if (zoomTransform.scale > 1.01f && showZoomReset) {
            Surface(Modifier.align(Alignment.TopEnd).padding(14.dp), color = Color(0xB811151E),
                shape = RoundedCornerShape(50)) {
                TextButton(onClick = ::resetZoom) { Text("Reset zoom") }
            }
        }
    }
}

private fun DrawScope.drawUnit(unit: ReaderUnit, offset: Float = 0f, alpha: Float = 1f) {
    if (unit.pages.isEmpty()) return
    drawRect(Color.Black.copy(alpha = alpha), topLeft = Offset(offset, 0f), size = size)
    val slot = size.width / unit.pages.size
    unit.pages.forEachIndexed { index, (_, bitmap) ->
        val scale = min(slot / bitmap.width, size.height / bitmap.height)
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset((offset + slot * index + (slot - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w, h), alpha = alpha, filterQuality = FilterQuality.Medium)
    }
}

private fun DrawScope.drawRegionTile(
    unit: ReaderUnit,
    pageIndex: Int,
    region: Rect,
    bitmap: Bitmap,
) {
    val slot = size.width / unit.pages.size.coerceAtLeast(1)
    val slotIndex = unit.pages.indexOfFirst { it.first == pageIndex }
    if (slotIndex < 0) return
    val preview = unit.pages[slotIndex].second
    val source = unit.fullResolutionPages[pageIndex] ?: return
    val fit = min(slot / preview.width, size.height / preview.height)
    val imageWidth = preview.width * fit
    val imageHeight = preview.height * fit
    val left = slot * slotIndex + (slot - imageWidth) / 2f + region.left.toFloat() / source.width * imageWidth
    val top = (size.height - imageHeight) / 2f + region.top.toFloat() / source.height * imageHeight
    val width = region.width().toFloat() / source.width * imageWidth
    val height = region.height().toFloat() / source.height * imageHeight
    drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(width.roundToInt().coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.High)
}

/** A clipped front sheet and curved, reflected paper band reveal the ready neighbor beneath. */
private fun DrawScope.drawCurl(front: ReaderUnit, underneath: ReaderUnit, offset: Float, turnWidth: Float) {
    drawUnit(underneath)
    val leftward = offset < 0f
    val travel = abs(offset).coerceIn(0f, turnWidth)
    val crease = if (leftward) turnWidth - travel else travel
    val fold = min(turnWidth * .18f, min(travel * .6f, (turnWidth - travel) * .6f))
    if (leftward) clipRect(right = crease) { drawUnit(front) }
    else clipRect(left = crease) { drawUnit(front) }
    if (fold < 1f) return
    val direction = if (leftward) -1f else 1f
    val path = Path().apply {
        moveTo(crease, 0f)
        cubicTo(crease + direction * fold, size.height * .08f, crease + direction * fold, size.height * .92f, crease, size.height)
        cubicTo(crease - direction * fold * .25f, size.height * .8f, crease - direction * fold * .25f, size.height * .2f, crease, 0f)
        close()
    }
    val shadowStart = crease - fold * .3f
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .35f), Color.Transparent), shadowStart, crease + fold * .3f),
        Offset(shadowStart, 0f), Size(fold * .6f, size.height))
    drawPath(path, Color(0xFFF5F0E4))
    clipPath(path) {
        withTransform({ scale(-1f, 1f, pivot = Offset(crease, size.height / 2)) }) { drawUnit(front, alpha = .18f) }
        drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .25f), Color.Transparent, Color.White.copy(alpha = .45f)), crease - fold, crease + fold))
    }
}

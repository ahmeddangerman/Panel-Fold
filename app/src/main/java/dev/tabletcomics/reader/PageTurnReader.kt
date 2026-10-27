package dev.tabletcomics.reader

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as FloatRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sign

/** Original, untouched page bytes, retained by the reader session for region decoding. */
data class FullResolutionPage(val pageIndex: Int, val sourceFile: File, val width: Int, val height: Int)

data class ReaderUnit(
    val startIndex: Int,
    val pages: List<Pair<Int, Bitmap>>,
    val fullResolutionPages: Map<Int, FullResolutionPage> = emptyMap(),
)

/** Neighbors are captured at gesture start. Progress is committed only after settling. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageTurnReader(
    current: ReaderUnit,
    previous: ReaderUnit?,
    next: ReaderUnit?,
    totalPages: Int,
    rightToLeft: Boolean,
    layoutKey: String,
    bookKey: String,
    verticalReading: Boolean = false,
    initialZoomScale: Float = 1f,
    onZoomScaleChanged: (Float) -> Unit = {},
    onTurn: (target: ReaderUnit) -> Unit,
    onToggleControls: () -> Unit,
    onEndReached: () -> Unit,
    showZoomReset: Boolean = true,
    fitMode: PageFit = PageFit.PAGE,
    cropBorders: Boolean = false,
    keepZoom: Boolean = true,
    panSensitivity: Float = 1.5f,
    momentumStrength: Float = 1f,
    pageDimming: Float = 0f,
    onDecodeRegions: suspend (FullResolutionPage, List<Rect>, Int) -> List<Bitmap?>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var width by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val turnState = remember(bookKey) { TurnState() }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var zoomTransform by rememberSaveable(bookKey, stateSaver = Saver<ZoomTransform, Float>(
        save = { it.scale }, restore = { ZoomTransform(it, Offset.Zero) },
    )) { mutableStateOf(ZoomTransform(if (initialZoomScale.isFinite()) initialZoomScale.coerceIn(1f, 5f) else 1f)) }
    val zoomChanged = rememberUpdatedState(onZoomScaleChanged)
    LaunchedEffect(zoomTransform.scale) {
        delay(250)
        zoomChanged.value(zoomTransform.scale)
    }
    var cropsByBitmap by remember(bookKey) { mutableStateOf<Map<Bitmap, FloatRect>>(emptyMap()) }
    val latestCrops = rememberUpdatedState(cropsByBitmap)
    val latestFit = rememberUpdatedState(fitMode)
    val latestCropEnabled = rememberUpdatedState(cropBorders)
    val latestKeepZoom = rememberUpdatedState(keepZoom)
    val latestPanSensitivity = rememberUpdatedState(panSensitivity)
    val latestMomentumStrength = rememberUpdatedState(momentumStrength)
    val latestTotalPages = rememberUpdatedState(totalPages)
    val latestCurrent = rememberUpdatedState(current)
    val latestPrevious = rememberUpdatedState(previous)
    val latestNext = rememberUpdatedState(next)
    val latestSettling = rememberUpdatedState(turnState.settling)
    val turn = rememberUpdatedState(onTurn)
    val toggle = rememberUpdatedState(onToggleControls)
    val endReached = rememberUpdatedState(onEndReached)
    val flowRightToLeft = rightToLeft && !verticalReading
    val tracker = remember { VelocityTracker() }
    var motionJob by remember { mutableStateOf<Job?>(null) }

    fun geometryFor(unit: ReaderUnit): List<PageGeometry> = ReaderGeometry.layout(
        viewport,
        unit.pages.map { (_, bitmap) -> Size(bitmap.width.toFloat(), bitmap.height.toFloat()) },
        unit.pages.map { (_, bitmap) ->
            if (latestCropEnabled.value) latestCrops.value[bitmap] ?: ReaderGeometry.fullCrop else ReaderGeometry.fullCrop
        },
        latestFit.value,
    )
    fun contentFor(unit: ReaderUnit): FloatRect = ReaderGeometry.bounds(geometryFor(unit))
    fun startZoomFor(unit: ReaderUnit, scale: Float): ZoomTransform = ZoomBounds.pageStart(scale, viewport, contentFor(unit))
    fun screenContentBounds(unit: ReaderUnit): FloatRect {
        val content = contentFor(unit)
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        return FloatRect(center + (content.topLeft - center) * zoomTransform.scale + zoomTransform.pan,
            center + (content.bottomRight - center) * zoomTransform.scale + zoomTransform.pan)
            .intersect(FloatRect(Offset.Zero, viewport))
    }

    // Examine only small previews, and retain crop results only for the three live units.
    LaunchedEffect(bookKey, cropBorders, current.pages, previous?.pages, next?.pages) {
        if (!cropBorders) {
            cropsByBitmap = emptyMap()
            return@LaunchedEffect
        }
        val bitmaps = (current.pages + previous?.pages.orEmpty() + next?.pages.orEmpty()).map { it.second }.distinct()
        val known = cropsByBitmap
        cropsByBitmap = withContext(Dispatchers.Default) {
            bitmaps.associateWith { bitmap -> known[bitmap] ?: BorderCrop.detect(bitmap) }
        }
    }

    fun animateZoom(target: ZoomTransform, duration: Int = 280) {
        motionJob?.cancel()
        val start = zoomTransform
        motionJob = scope.launch {
            animate(0f, 1f, animationSpec = tween(duration, easing = LinearOutSlowInEasing)) { fraction, _ ->
                zoomTransform = ZoomMotion.frame(start, target, fraction)
            }
        }
    }
    fun resetZoom() = animateZoom(startZoomFor(latestCurrent.value, 1f))
    fun glide(velocity: Offset) {
        val content = contentFor(latestCurrent.value)
        if (!ZoomBounds.hasOverflow(viewport, content, zoomTransform.scale)) return
        val target = ZoomMotion.glideTarget(
            zoomTransform, velocity * latestPanSensitivity.value, viewport, content, latestMomentumStrength.value,
        )
        if (target.pan != zoomTransform.pan) animateZoom(target, 420)
    }

    fun zoomToPageWidth(tapPosition: Offset) {
        if (viewport.width <= 0f || viewport.height <= 0f) return
        val geometry = geometryFor(latestCurrent.value)
        if (geometry.isEmpty()) return
        val slotWidth = viewport.width / geometry.size
        val pageIndex = (tapPosition.x / slotWidth).toInt().coerceIn(geometry.indices)
        val fitted = ZoomBounds.fitPageToWidth(viewport, geometry, pageIndex, ReaderGeometry.bounds(geometry))
        val targetScale = if (fitted.scale <= zoomTransform.scale + .01f) {
            (zoomTransform.scale * 2f).coerceAtLeast(2f).coerceAtMost(5f)
        } else fitted.scale
        animateZoom(ZoomBounds.pageStart(targetScale, viewport, ReaderGeometry.bounds(geometry), fitted.pan.x))
    }

    fun onDoubleTap(position: Offset) {
        if (turnState.settling || turnState.committedUnit != null) return
        if (zoomTransform.scale > 1.01f) resetZoom() else zoomToPageWidth(position)
    }

    val transform = rememberTransformableState { scale, translation, _ ->
        if (!turnState.settling && turnState.committedUnit == null) {
            motionJob?.cancel()
            zoomTransform = zoomTransform.transformBy(scale, translation, viewport, contentFor(latestCurrent.value), latestPanSensitivity.value)
        }
    }
    val currentGeometry = geometryFor(current)
    var zoomPageIndex by remember(bookKey) { mutableIntStateOf(current.startIndex) }
    LaunchedEffect(viewport, current.startIndex, layoutKey, bookKey, currentGeometry) {
        val scale = if (current.startIndex != zoomPageIndex && !latestKeepZoom.value) 1f else zoomTransform.scale
        zoomPageIndex = current.startIndex
        zoomTransform = ZoomBounds.pageStart(scale, viewport, ReaderGeometry.bounds(currentGeometry))
    }

    LaunchedEffect(current.startIndex, bookKey) {
        settleJob?.cancel()
        motionJob?.cancel()
        turnState.acknowledge(current.startIndex)
        turnState.clearGesture()
    }
    LaunchedEffect(layoutKey, bookKey, fitMode, cropBorders) {
        settleJob?.cancel()
        motionJob?.cancel()
        turnState.reset()
    }

    // One cache follows the book, including the frozen turn frame and asynchronous page handoff.
    val displayUnit = turnState.committedUnit ?: turnState.snapshot?.current ?: current
    val revealedUnit = turnState.snapshot?.neighbor(turnState.offset, flowRightToLeft)
    val detailUnits = listOfNotNull(displayUnit, revealedUnit, next, previous,
        turnState.snapshot?.next, turnState.snapshot?.previous).distinctBy { it.startIndex }
    val detailDemands = detailUnits.flatMapIndexed { index, unit ->
        val geometry = geometryFor(unit)
        val zoom = if (unit.startIndex == displayUnit.startIndex) zoomTransform else
            startZoomFor(unit, if (keepZoom) zoomTransform.scale else 1f)
        unit.pages.mapIndexedNotNull { slot, (pageIndex, _) ->
            val source = unit.fullResolutionPages[pageIndex] ?: return@mapIndexedNotNull null
            val page = geometry.getOrNull(slot) ?: return@mapIndexedNotNull null
            DetailPageDemand(source, page, viewport, zoom, index)
        }
    }
    val detailTiles = rememberReaderDetailTiles(bookKey, detailDemands, onDecodeRegions)

    fun neighborFor(offset: Float): ReaderUnit? {
        val frame = turnState.snapshot
        if (frame != null) return frame.neighbor(offset, flowRightToLeft)
        return if ((offset < 0f) xor flowRightToLeft) latestNext.value else latestPrevious.value
    }

    fun settle(velocity: Float, cancelled: Boolean = false) {
        val frame = turnState.snapshot
        val corner = frame?.corner
        val bounds = frame?.foldBounds
        val extent = bounds?.width ?: if (verticalReading) viewport.height else width
        val captured = turnState.release(velocity / density, extent, flowRightToLeft, cancelled) ?: return
        val remaining = (abs(captured.destination - captured.start) / extent).coerceIn(0f, 1f)
        val cornerStart = turnState.draggedCorner
        settleJob = scope.launch {
            try {
                if (corner != null && bounds != null && cornerStart != null) {
                    val original = corner.position(bounds.size)
                    val destination = if (captured.target == null) original else
                        Offset(if (corner.isRight) -bounds.width else bounds.width * 2f, original.y)
                    animate(0f, 1f, animationSpec = tween((180 + remaining * 160).toInt(), easing = LinearOutSlowInEasing)) { fraction, _ ->
                        turnState.updateAnimatedOffset(captured.start + (captured.destination - captured.start) * fraction)
                        turnState.moveCorner(cornerStart + (destination - cornerStart) * fraction)
                    }
                } else {
                    animate(captured.start, captured.destination, animationSpec = tween((100 + remaining * 140).toInt())) { value, _ ->
                        turnState.updateAnimatedOffset(value)
                    }
                }
                turnState.completeSettle { target ->
                    zoomTransform = startZoomFor(target, if (latestKeepZoom.value) zoomTransform.scale else 1f)
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
    }.pointerInput(current.startIndex, layoutKey, rightToLeft, verticalReading, bookKey, fitMode, cropBorders) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            motionJob?.cancel()
            // The settled frame remains visible until the asynchronous parent acknowledges it.
            if (latestSettling.value || turnState.committedUnit != null) return@awaitEachGesture
            tracker.resetTracking()
            tracker.addPosition(down.uptimeMillis, down.position)
            var dragging = false
            var panning = false
            var dragOrigin = 0f
            var lastPosition = down.position
            val zoomEdgeSwipe = ZoomEdgeSwipe()
            var cancelled = false
            var forwardBoundaryOffset: Float? = null
            val extent = if (verticalReading) viewport.height else width
            val cornerSize = 88f * density
            val foldBounds = screenContentBounds(latestCurrent.value)
            val selectedCorner = if (verticalReading || foldBounds.isEmpty) null else
                PageCorner.fromTouch(down.position - foldBounds.topLeft, foldBounds.size, cornerSize)
                    ?: PageCorner.fromTouch(down.position, viewport, cornerSize)
            val downCurrent = latestCurrent.value
            val downPrevious = latestPrevious.value
            val downNext = latestNext.value
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
                val axisMovement = if (verticalReading) movement.y else movement.x
                val axisDelta = if (verticalReading) pointerDelta.y else pointerDelta.x
                val crossMovement = if (verticalReading) movement.x else movement.y
                val crossDelta = if (verticalReading) pointerDelta.x else pointerDelta.y
                if (!change.pressed) {
                    val velocity = tracker.calculateVelocity()
                    if (dragging) {
                        val boundaryOffset = forwardBoundaryOffset
                        val corner = turnState.snapshot?.corner
                        val releaseVelocity = if (corner != null) {
                            val horizontal = abs(movement.x) >= abs(movement.y) * foldBounds.width / foldBounds.height
                            val inward = if (horizontal) velocity.x * (if (corner.isRight) -1f else 1f) else
                                velocity.y * (if (corner.isBottom) -1f else 1f) * foldBounds.width / foldBounds.height
                            inward * (if (corner.isRight) -1f else 1f)
                        } else if (verticalReading) velocity.y else velocity.x
                        settle(releaseVelocity)
                        if (boundaryOffset != null && TurnNavigation.shouldComplete(
                                boundaryOffset / density,
                                releaseVelocity / density,
                                (if (corner != null) foldBounds.width else extent) / density,
                            )) endReached.value()
                    } else if (panning) glide(Offset(velocity.x, velocity.y))
                    break
                }
                val content = contentFor(latestCurrent.value)
                if (!dragging && selectedCorner != null && movement.getDistance() > viewConfiguration.touchSlop) {
                    val inwardX = movement.x * (if (selectedCorner.isRight) -1f else 1f)
                    val inwardY = movement.y * (if (selectedCorner.isBottom) -1f else 1f)
                    if (maxOf(inwardX, inwardY) > viewConfiguration.touchSlop) {
                        dragging = true
                        turnState.capture(downCurrent, downPrevious, downNext, selectedCorner, foldBounds)
                    }
                }
                if (ZoomBounds.hasOverflow(viewport, content, zoomTransform.scale) && !dragging) {
                    val alongAxis = abs(axisMovement) > abs(crossMovement) * 1.2f && abs(axisDelta) > abs(crossDelta)
                    val canTurn = alongAxis && if (verticalReading) {
                        ZoomBounds.canTurnAtVerticalEdge(viewport, content, zoomTransform.scale, zoomTransform.pan, axisDelta)
                    } else {
                        ZoomBounds.canTurnAtHorizontalEdge(viewport, content, zoomTransform.scale, zoomTransform.pan, axisDelta)
                    }
                    val edgeOffset = zoomEdgeSwipe.drag(axisDelta, canTurn, viewConfiguration.touchSlop)
                    if (edgeOffset == null) {
                        if (panning || movement.getDistance() > viewConfiguration.touchSlop) {
                            val delta = if (panning) pointerDelta else movement * (1f - viewConfiguration.touchSlop / movement.getDistance())
                            panning = true
                            zoomTransform = zoomTransform.transformBy(1f, delta, viewport, content, latestPanSensitivity.value)
                            change.consume()
                        }
                        continue
                    }
                    dragging = true
                    dragOrigin = axisMovement - edgeOffset
                    turnState.capture(downCurrent, downPrevious, downNext)
                }
                if (!dragging && abs(axisMovement) > viewConfiguration.touchSlop && abs(axisMovement) > abs(crossMovement)) {
                    dragging = true
                    dragOrigin = sign(axisMovement) * viewConfiguration.touchSlop
                    turnState.capture(downCurrent, downPrevious, downNext)
                }
                if (dragging) {
                    val corner = turnState.snapshot?.corner
                    val proposed = if (corner == null) TurnNavigation.offsetAfterTouchSlop(axisMovement, dragOrigin).coerceIn(-extent, extent) else {
                        val inwardX = movement.x * (if (corner.isRight) -1f else 1f)
                        val inwardY = movement.y * (if (corner.isBottom) -1f else 1f) * foldBounds.width / foldBounds.height
                        maxOf(inwardX, inwardY, 0f).coerceAtMost(foldBounds.width) * (if (corner.isRight) -1f else 1f)
                    }
                    val hasNeighbor = neighborFor(proposed) != null
                    val forward = if (corner != null) corner.isRight xor flowRightToLeft else (proposed < 0f) xor flowRightToLeft
                    val frame = turnState.snapshot
                    forwardBoundaryOffset = if (!hasNeighbor && forward && frame != null && isActualForwardEnd(frame.current, latestTotalPages.value)) proposed else null
                    turnState.dragTo(proposed, hasNeighbor = hasNeighbor)
                    if (corner != null && hasNeighbor) turnState.moveCorner(corner.position(foldBounds.size) + movement)
                    change.consume()
                }
            }
            if (cancelled) settle(0f, cancelled = true)
        }
    }.pointerInput(current.startIndex, layoutKey, bookKey, fitMode, cropBorders) {
        detectTapGestures(onTap = { toggle.value() }, onDoubleTap = ::onDoubleTap)
    }) {
        // Pinch recognition leaves single-finger pan/turn/fling to the viewport handler.
        Canvas(Modifier.fillMaxSize().transformable(state = transform, canPan = { false })) {
            drawRect(Color.Black)
            val frame = turnState.snapshot
            val drag = turnState.offset
            val drawnCurrent = turnState.committedUnit ?: frame?.current ?: latestCurrent.value
            val target = if (frame != null) frame.neighbor(drag, flowRightToLeft) else neighborFor(drag)
            val geometry = geometryFor(drawnCurrent)
            if (abs(drag) < .1f || target == null) {
                drawViewportUnit(drawnCurrent, geometry, 0f, zoomTransform, detailTiles, verticalReading)
            } else {
                val targetGeometry = geometryFor(target)
                val axisSize = if (verticalReading) size.height else size.width
                val targetZoom = ZoomBounds.pageStart(
                    if (latestKeepZoom.value) zoomTransform.scale else 1f, size, ReaderGeometry.bounds(targetGeometry),
                )
                val corner = frame?.corner
                val bounds = frame?.foldBounds
                val draggedCorner = turnState.draggedCorner
                if (corner != null && bounds != null && draggedCorner != null) {
                    drawCornerPageTurn(bounds, corner, draggedCorner,
                        drawIncoming = { drawViewportUnit(target, targetGeometry, 0f, targetZoom, detailTiles) },
                        drawCurrent = { drawViewportUnit(drawnCurrent, geometry, 0f, zoomTransform, detailTiles) })
                } else {
                    drawViewportUnit(target, targetGeometry, ZoomBounds.pageTurnNeighborOffset(drag, axisSize, 1f), targetZoom, detailTiles, verticalReading)
                    drawViewportUnit(drawnCurrent, geometry, drag, zoomTransform, detailTiles, verticalReading)
                }
            }
            // One final pass dims every page layer equally, including detail tiles and folds.
            // Black margins stay black; controls are drawn outside this Canvas.
            val dimming = safePageDimming(pageDimming)
            if (dimming > 0f) drawRect(Color.Black.copy(alpha = dimming))
        }
        if (zoomTransform.scale > 1.01f && showZoomReset) {
            Surface(Modifier.align(Alignment.TopEnd).padding(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                shape = RoundedCornerShape(50)) {
                TextButton(onClick = ::resetZoom) { Text("Reset zoom") }
            }
        }
    }
}

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

/** Clip before scaling so a zoomed outgoing page cannot overlap the incoming viewport. */
private fun DrawScope.drawViewportUnit(
    unit: ReaderUnit,
    geometry: List<PageGeometry>,
    screenOffset: Float,
    zoom: ZoomTransform,
    tiles: List<DetailTile> = emptyList(),
    verticalReading: Boolean = false,
) {
    if (verticalReading) clipRect(left = 0f, top = screenOffset, right = size.width, bottom = screenOffset + size.height) {
        drawUnitContent(unit, geometry, screenOffset, zoom, tiles, true)
    } else clipRect(left = screenOffset, top = 0f, right = screenOffset + size.width, bottom = size.height) {
        drawUnitContent(unit, geometry, screenOffset, zoom, tiles, false)
    }
}

private fun DrawScope.drawUnitContent(
    unit: ReaderUnit, geometry: List<PageGeometry>, screenOffset: Float, zoom: ZoomTransform,
    tiles: List<DetailTile>, verticalReading: Boolean,
) {
        withTransform({
            translate(left = if (verticalReading) zoom.pan.x else screenOffset + zoom.pan.x,
                top = if (verticalReading) screenOffset + zoom.pan.y else zoom.pan.y)
            scale(zoom.scale, zoom.scale, pivot = Offset(size.width / 2f, size.height / 2f))
        }) {
            drawRect(Color.Black)
            unit.pages.forEachIndexed { slot, (_, bitmap) ->
                val page = geometry.getOrNull(slot) ?: return@forEachIndexed
                val source = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
                val full = page.sourceToDisplay(FloatRect(Offset.Zero, source), source)
                clipRect(page.destination.left, page.destination.top, page.destination.right, page.destination.bottom) {
                    drawFloatingBitmap(bitmap, full)
                }
            }
            tiles.sortedByDescending { it.sampleSize }.forEach { tile ->
                val slot = unit.pages.indexOfFirst { it.first == tile.pageIndex }
                val page = geometry.getOrNull(slot) ?: return@forEach
                val source = unit.fullResolutionPages[tile.pageIndex] ?: return@forEach
                if (tile.sourcePath != source.sourceFile.absolutePath) return@forEach
                val destination = page.sourceToDisplay(tile.region.asFloatRect(), Size(source.width.toFloat(), source.height.toFloat()))
                clipRect(page.destination.left, page.destination.top, page.destination.right, page.destination.bottom) {
                    drawFloatingBitmap(tile.bitmap, destination)
                }
            }
        }
}

/** Android's RectF destinations preserve shared source boundaries without independent integer rounding. */
private fun DrawScope.drawFloatingBitmap(bitmap: Bitmap, destination: FloatRect) {
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmap(bitmap, null, RectF(destination.left, destination.top, destination.right, destination.bottom), bitmapPaint)
    }
}

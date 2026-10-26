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
import kotlinx.coroutines.ensureActive
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

private data class DetailTile(val pageIndex: Int, val region: PixelRegion, val bitmap: Bitmap)

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
    var detailTiles by remember(current.startIndex, layoutKey, bookKey) { mutableStateOf<List<DetailTile>>(emptyList()) }
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
    val decodeRegions = rememberUpdatedState(onDecodeRegions)
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

    LaunchedEffect(current.startIndex, layoutKey, bookKey, viewport, zoomTransform, currentGeometry, current.fullResolutionPages) {
        if (viewport.width <= 0f || viewport.height <= 0f) return@LaunchedEffect
        // Decode a small buffer around the screen so a normal pan exposes already-sharp pixels.
        val visible = ReaderGeometry.visibleViewport(viewport, zoomTransform, detailOverscanFraction = .25f)
        val regions = current.pages.mapIndexed { slot, (pageIndex, preview) ->
            val source = current.fullResolutionPages[pageIndex]
            val geometry = currentGeometry.getOrNull(slot)
            val previewScale = geometry?.let { it.destination.width / it.crop.width / preview.width * zoomTransform.scale } ?: 0f
            if (source == null || geometry == null || previewScale <= 1.05f) null
            else geometry.visibleSource(visible, Size(source.width.toFloat(), source.height.toFloat()))
        }
        val plan = DetailTilePlanner.plan(regions)
        if (plan.requests.isEmpty()) return@LaunchedEffect
        delay(90)
        val result = mutableListOf<DetailTile>()
        for ((slot, requests) in plan.requests.groupBy { it.pageSlot }) {
            ensureActive()
            val pageIndex = current.pages[slot].first
            val source = current.fullResolutionPages[pageIndex] ?: continue
            val androidRegions = requests.map { it.region.let { region -> Rect(region.left, region.top, region.right, region.bottom) } }
            decodeRegions.value(source, androidRegions, plan.sampleSize).forEachIndexed { index, bitmap ->
                if (bitmap != null && index < requests.size) result += DetailTile(pageIndex, requests[index].region, bitmap)
            }
        }
        ensureActive()
        detailTiles = result
    }

    fun neighborFor(offset: Float): ReaderUnit? {
        val frame = turnState.snapshot
        if (frame != null) return frame.neighbor(offset, rightToLeft)
        return if ((offset < 0f) xor rightToLeft) latestNext.value else latestPrevious.value
    }

    fun settle(velocity: Float, cancelled: Boolean = false) {
        val captured = turnState.release(velocity / density, width, rightToLeft, cancelled) ?: return
        val remaining = (abs(captured.destination - captured.start) / width).coerceIn(0f, 1f)
        settleJob = scope.launch {
            try {
                animate(captured.start, captured.destination, animationSpec = tween((100 + remaining * 140).toInt())) { value, _ ->
                    turnState.updateAnimatedOffset(value)
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
    }.pointerInput(current.startIndex, layoutKey, rightToLeft, bookKey, fitMode, cropBorders) {
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
                        if (boundaryOffset != null && TurnNavigation.shouldComplete(
                                boundaryOffset / density, velocity.x / density, width / density,
                            )) endReached.value()
                    } else if (panning) glide(Offset(velocity.x, velocity.y))
                    break
                }
                val content = contentFor(latestCurrent.value)
                if (ZoomBounds.hasOverflow(viewport, content, zoomTransform.scale) && !dragging) {
                    // A mostly vertical reading drag must not become an edge turn due to jitter.
                    val horizontal = abs(movement.x) > abs(movement.y) * 1.2f && abs(pointerDelta.x) > abs(pointerDelta.y)
                    val canTurn = horizontal && ZoomBounds.canTurnAtHorizontalEdge(
                        viewport, content, zoomTransform.scale, zoomTransform.pan, pointerDelta.x,
                    )
                    val edgeOffset = zoomEdgeSwipe.drag(pointerDelta.x, canTurn, viewConfiguration.touchSlop)
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
                    val frame = turnState.snapshot
                    forwardBoundaryOffset = if (!hasNeighbor && forward && frame != null && isActualForwardEnd(frame.current, latestTotalPages.value)) proposed else null
                    turnState.dragTo(proposed, hasNeighbor = hasNeighbor)
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
            val target = if (frame != null) frame.neighbor(drag, rightToLeft) else neighborFor(drag)
            val geometry = geometryFor(drawnCurrent)
            if (abs(drag) < .1f || target == null) {
                drawViewportUnit(drawnCurrent, geometry, 0f, zoomTransform, detailTiles)
            } else {
                val targetGeometry = geometryFor(target)
                val targetZoom = ZoomBounds.pageStart(
                    if (latestKeepZoom.value) zoomTransform.scale else 1f, size, ReaderGeometry.bounds(targetGeometry),
                )
                drawViewportUnit(target, targetGeometry, ZoomBounds.pageTurnNeighborOffset(drag, size.width, 1f), targetZoom)
                drawViewportUnit(drawnCurrent, geometry, drag, zoomTransform)
            }
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
) {
    clipRect(left = screenOffset, top = 0f, right = screenOffset + size.width, bottom = size.height) {
        withTransform({
            translate(left = screenOffset + zoom.pan.x, top = zoom.pan.y)
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
            tiles.forEach { tile ->
                val slot = unit.pages.indexOfFirst { it.first == tile.pageIndex }
                val page = geometry.getOrNull(slot) ?: return@forEach
                val source = unit.fullResolutionPages[tile.pageIndex] ?: return@forEach
                val destination = page.sourceToDisplay(tile.region.asFloatRect(), Size(source.width.toFloat(), source.height.toFloat()))
                clipRect(page.destination.left, page.destination.top, page.destination.right, page.destination.bottom) {
                    drawFloatingBitmap(tile.bitmap, destination)
                }
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

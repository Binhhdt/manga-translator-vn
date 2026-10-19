package vn.mangatranslate.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import vn.mangatranslate.app.core.model.BoxF
import vn.mangatranslate.app.core.model.TextRegion
import vn.mangatranslate.app.ui.theme.BoxBlue
import vn.mangatranslate.app.ui.theme.BoxGreen
import vn.mangatranslate.app.ui.theme.BoxOrange
import vn.mangatranslate.app.ui.theme.BoxRed
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Trình xem trang: vừa khít chiều ngang màn hình, kéo để cuộn dọc, chụm hai ngón để phóng to (1×–8×),
 * chạm đúp để phóng/thu. Chạm một lần trả về tọa độ theo ẢNH GỐC.
 * Ở [drawMode], kéo một ngón để khoanh vùng chữ (tọa độ cũng quy đổi về ảnh gốc).
 */
@Composable
fun ZoomableMangaCanvas(
    chunks: List<ImageBitmap>,
    imageWidth: Int,
    imageHeight: Int,
    regions: List<TextRegion>,
    overflowIds: Set<Int>,
    showRegionBoxes: Boolean,
    drawMode: Boolean,
    resetKey: Any,
    onTapImage: (Float, Float) -> Unit,
    onRegionDrawn: (BoxF) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var currentScale by remember(resetKey) { mutableFloatStateOf(1f) }
    var currentOffset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    val imgW by rememberUpdatedState(imageWidth)
    val imgH by rememberUpdatedState(imageHeight)
    val tapCallback by rememberUpdatedState(onTapImage)
    val drawnCallback by rememberUpdatedState(onRegionDrawn)

    fun contentHeight(): Float =
        if (imgW > 0) viewport.width * imgH.toFloat() / imgW else 0f

    fun clamp(o: Offset, s: Float): Offset {
        val vw = viewport.width.toFloat()
        val vh = viewport.height.toFloat()
        val cw = vw * s
        val ch = contentHeight() * s
        val x = if (cw <= vw) (vw - cw) / 2f else o.x.coerceIn(vw - cw, 0f)
        val y = if (ch <= vh) (vh - ch) / 2f else o.y.coerceIn(vh - ch, 0f)
        return Offset(x, y)
    }

    fun toImage(p: Offset): Offset {
        val k = viewport.width.toFloat() / max(1, imgW)
        return Offset(
            (p.x - currentOffset.x) / currentScale / k,
            (p.y - currentOffset.y) / currentScale / k
        )
    }

    LaunchedEffect(viewport, resetKey, imageWidth, imageHeight) {
        currentOffset = clamp(currentOffset, currentScale)
    }

    val gestures = if (drawMode) {
        Modifier.pointerInput("draw", resetKey) {
            detectDragGestures(
                onDragStart = { p -> dragStart = p; dragEnd = p },
                onDragEnd = {
                    val a = dragStart
                    val b = dragEnd
                    dragStart = null
                    dragEnd = null
                    if (a != null && b != null) {
                        val ia = toImage(a)
                        val ib = toImage(b)
                        drawnCallback(BoxF.of(ia.x, ia.y, ib.x, ib.y))
                    }
                },
                onDragCancel = { dragStart = null; dragEnd = null },
                onDrag = { change, _ ->
                    change.consume()
                    dragEnd = change.position
                }
            )
        }
    } else {
        Modifier
            .pointerInput("tap", resetKey) {
                detectTapGestures(
                    onTap = { p ->
                        val ip = toImage(p)
                        tapCallback(ip.x, ip.y)
                    },
                    onDoubleTap = { p ->
                        if (currentScale > 1.5f) {
                            currentOffset = clamp(currentOffset, 1f)
                            currentScale = 1f
                        } else {
                            val target = 2.5f
                            currentOffset = clamp((currentOffset - p) * (target / currentScale) + p, target)
                            currentScale = target
                        }
                    }
                )
            }
            .pointerInput("transform", resetKey) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (currentScale * zoom).coerceIn(1f, 8f)
                    val factor = newScale / currentScale
                    currentOffset = clamp((currentOffset - centroid) * factor + centroid + pan, newScale)
                    currentScale = newScale
                }
            }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .then(gestures)
    ) {
        if (chunks.isEmpty() || imageWidth <= 0) return@Canvas
        val vw = size.width
        val previewWidth = chunks.first().width
        val cs = vw / previewWidth
        val s = currentScale
        val o = currentOffset

        withTransform({
            translate(o.x, o.y)
            scale(s, s, Offset.Zero)
        }) {
            var y = 0
            for (c in chunks) {
                val top = (y * cs).roundToInt()
                val bottom = ((y + c.height) * cs).roundToInt()
                drawImage(
                    image = c,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(c.width, c.height),
                    dstOffset = IntOffset(0, top),
                    dstSize = IntSize(vw.roundToInt(), max(1, bottom - top)),
                    filterQuality = FilterQuality.Medium
                )
                y += c.height
            }

            if (showRegionBoxes) {
                val k = vw / imageWidth
                val stroke = 2.dp.toPx() / s
                for (r in regions) {
                    val color = when {
                        !r.enabled -> Color.Gray
                        r.id in overflowIds -> BoxRed
                        r.needsReview -> BoxOrange
                        r.manual -> BoxGreen
                        else -> BoxBlue
                    }
                    val b = r.bubble?.box ?: r.layoutBox ?: r.box
                    drawRect(
                        color = color,
                        topLeft = Offset(b.left * k, b.top * k),
                        size = Size(b.width * k, b.height * k),
                        style = Stroke(width = stroke)
                    )
                }
            }
        }

        val a = dragStart
        val b = dragEnd
        if (a != null && b != null) {
            val tl = Offset(min(a.x, b.x), min(a.y, b.y))
            val sz = Size(abs(b.x - a.x), abs(b.y - a.y))
            drawRect(color = BoxBlue.copy(alpha = 0.25f), topLeft = tl, size = sz)
            drawRect(color = BoxBlue, topLeft = tl, size = sz, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

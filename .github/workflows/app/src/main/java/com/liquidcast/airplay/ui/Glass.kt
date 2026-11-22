package com.liquidcast.airplay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 全域動畫時間（弧度），讓背景光斑與每張玻璃卡片裡的「折射背景」同步流動。 */
val LocalSceneTime = compositionLocalOf<State<Float>> { error("LocalSceneTime not provided") }

/** 流動彩色光斑背景（玻璃的「折射」來源）。 */
@Composable
fun GlassScene() {
    val t = LocalSceneTime.current
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF070B24), Color(0xFF1A1060), Color(0xFF08394F))))
        val w = size.width
        val h = size.height
        val a = t.value
        blob(Color(0xFF5B8CFF), Offset(w * (0.25f + 0.12f * sin(a)), h * (0.16f + 0.06f * cos(a))), w * 0.65f)
        blob(Color(0xFFB45CFF), Offset(w * (0.82f + 0.10f * cos(a)), h * (0.42f + 0.08f * sin(a))), w * 0.60f)
        blob(Color(0xFF2EE6C5), Offset(w * (0.30f + 0.10f * sin(a + 2f)), h * (0.76f + 0.05f * cos(a + 1f))), w * 0.65f)
        blob(Color(0xFFFF7AB8), Offset(w * (0.85f + 0.06f * sin(a + 4f)), h * (0.90f + 0.04f * cos(a))), w * 0.45f)
    }
}

private fun DrawScope.blob(c: Color, center: Offset, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(listOf(c.copy(alpha = 0.85f), Color.Transparent), center = center, radius = r),
        radius = r,
        center = center,
    )
}

/**
 * 液態玻璃卡片：
 *  1. 內部放一份「模糊後的背景」並依卡片位置反向位移 → 看起來像透過玻璃看到背景（API 31+ 為真模糊）
 *  2. 半透明白色漸層 + 高光
 *  3. 漸層描邊（邊緣折光）
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val cfg = LocalConfiguration.current
    var pos by remember { mutableStateOf(IntOffset.Zero) }
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Box(
        modifier
            .onGloballyPositioned {
                val p = it.positionInRoot()
                pos = IntOffset(p.x.roundToInt(), p.y.roundToInt())
            }
            .clip(shape)
            .then(clickMod)
    ) {
        Box(Modifier.matchParentSize().blur(24.dp)) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(cfg.screenWidthDp.dp, cfg.screenHeightDp.dp)
                    .offset { IntOffset(-pos.x, -pos.y) }
            ) { GlassScene() }
        }
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.linearGradient(listOf(Color(0x40FFFFFF), Color(0x12FFFFFF))))
                .drawBehind {
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x55FFFFFF), Color.Transparent),
                            center = Offset(size.width * 0.12f, 0f),
                            radius = size.width * 0.9f,
                        )
                    )
                }
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color(0xCCFFFFFF), Color(0x1AFFFFFF), Color(0x66FFFFFF))),
                    shape,
                )
        )
        content()
    }
}

/** 玻璃質感音量滑桿。 */
@Composable
fun GlassSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var w by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    val thumb = 32.dp
    val thumbPx = with(density) { thumb.toPx() }
    val v = value.coerceIn(0f, 1f)

    Box(
        modifier
            .height(44.dp)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(w) { detectTapGestures { cb((it.x / w).coerceIn(0f, 1f)) } }
            .pointerInput(w) { detectHorizontalDragGestures { c, _ -> cb((c.position.x / w).coerceIn(0f, 1f)) } }
    ) {
        val track = RoundedCornerShape(50)
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().height(14.dp).clip(track)
                .background(Color(0x33FFFFFF))
                .border(1.dp, Color(0x40FFFFFF), track)
        )
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(v).height(14.dp).clip(track)
                .background(Brush.horizontalGradient(listOf(Color(0xFF7AA2FF), Color(0xFFB45CFF), Color(0xFFFF7AB8))))
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(((w - thumbPx) * v).roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xCCE6ECFF))))
                .border(1.dp, Color(0x99FFFFFF), CircleShape)
        )
    }
}

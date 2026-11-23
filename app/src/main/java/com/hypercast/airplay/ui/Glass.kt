// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import com.hypercast.airplay.Haptics
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
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// © DylanLiao — HyperCast

/** 全域動畫時間（弧度），讓背景光斑與每張玻璃裡的「折射背景」同步流動。 */
val LocalSceneTime = compositionLocalOf<State<Float>> { error("LocalSceneTime not provided") }

@Composable
fun GText(
    text: String,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
    secondary: Boolean = false,
) {
    val p = LocalPalette.current
    val base = if (secondary) p.text2 else p.text
    BasicText(
        text, modifier,
        style = TextStyle(color = base.copy(alpha = base.alpha * alpha), fontSize = size.sp, fontWeight = weight, textAlign = align),
    )
}

/** 按壓狀態（回彈縮放、觸點光暈共用）。 */
class PressState {
    var pressed by mutableStateOf(false)
    var touch by mutableStateOf(Offset.Zero)
}

private fun Modifier.pressDetect(st: PressState, click: Boolean, onTap: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectTapGestures(
            onPress = { o ->
                st.touch = o
                st.pressed = true
                Haptics.press()
                tryAwaitRelease()
                st.pressed = false
            },
            onTap = { if (click) Haptics.click(); onTap() },
        )
    }

/** 通用 Alive 點擊：按下時彈性縮小、放開時回彈，並帶觸覺（click=false 時由呼叫端自行處理觸覺）。 */
@Composable
fun Modifier.hapticClickable(click: Boolean = true, onClick: () -> Unit): Modifier {
    val st = remember { PressState() }
    val cb by rememberUpdatedState(onClick)
    val sc by animateFloatAsState(
        if (st.pressed) 0.955f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 520f), label = "press",
    )
    return this
        .graphicsLayer { scaleX = sc; scaleY = sc }
        .pressDetect(st, click) { cb() }
}

/** 漂浮的玻璃氣泡：被 GlassCard 的模糊背景折射，讓玻璃有東西可以「折」。 */
private fun DrawScope.bubble(c: Offset, r: Float, tint: Color, strength: Float) {
    drawCircle(
        Brush.radialGradient(
            listOf(Color.Transparent, tint.copy(alpha = 0.10f * strength), tint.copy(alpha = 0.34f * strength)),
            center = c, radius = r,
        ),
        radius = r, center = c,
    )
    drawCircle(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.9f * strength), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.5f * strength)),
            start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r),
        ),
        radius = r, center = c, style = Stroke(1.6f),
    )
    drawArc(
        Color.White.copy(alpha = 0.75f * strength), startAngle = 198f, sweepAngle = 52f, useCenter = false,
        topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f), size = Size(r * 1.56f, r * 1.56f),
        style = Stroke(r * 0.07f, cap = StrokeCap.Round),
    )
}

/** 流動背景（玻璃的「折射」來源），依外觀切換：幻彩 / 純白 / 純黑。 */
@Composable
fun GlassScene() {
    val t = LocalSceneTime.current
    val theme = LocalAppTheme.current
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val a = t.value
        when (theme) {
            AppTheme.Iridescent -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFFFFF6FA), Color(0xFFF1EEFF), Color(0xFFE8F6FF))))
                blob(Color(0xFFFF9EC7), Offset(w * (0.20f + 0.12f * sin(a)), h * (0.14f + 0.06f * cos(a))), w * 0.75f, 0.75f)
                blob(Color(0xFFB79CFF), Offset(w * (0.88f + 0.10f * cos(a)), h * (0.36f + 0.08f * sin(a))), w * 0.70f, 0.70f)
                blob(Color(0xFF8EC5FF), Offset(w * (0.22f + 0.10f * sin(a + 2f)), h * (0.62f + 0.06f * cos(a + 1f))), w * 0.75f, 0.70f)
                blob(Color(0xFF9BF0D0), Offset(w * (0.80f + 0.08f * sin(a + 4f)), h * (0.84f + 0.05f * cos(a))), w * 0.65f, 0.65f)
                blob(Color(0xFFFFC999), Offset(w * (0.50f + 0.15f * cos(a + 1f)), h * 0.99f), w * 0.55f, 0.65f)
            }
            AppTheme.White -> {
                drawRect(Color.White)
                blob(Color(0xFFE3E8F1), Offset(w * (0.30f + 0.10f * sin(a)), h * (0.20f + 0.05f * cos(a))), w * 0.85f, 0.9f)
                blob(Color(0xFFEDEFF4), Offset(w * (0.85f + 0.08f * cos(a)), h * (0.75f + 0.05f * sin(a))), w * 0.75f, 0.9f)
            }
            AppTheme.Black -> {
                drawRect(Color.Black)
                blob(Color(0xFF3A3F6B), Offset(w * (0.25f + 0.10f * sin(a)), h * (0.20f + 0.05f * cos(a))), w * 0.75f, 0.45f)
                blob(Color(0xFF5B2E6B), Offset(w * (0.88f + 0.08f * cos(a)), h * (0.55f + 0.06f * sin(a))), w * 0.65f, 0.35f)
                blob(Color(0xFF1F5B6B), Offset(w * (0.30f + 0.08f * sin(a + 2f)), h * (0.90f + 0.03f * cos(a))), w * 0.75f, 0.30f)
            }
        }
        // 漂浮玻璃氣泡（三種外觀共用，強度不同）
        val k = when (theme) { AppTheme.Iridescent -> 1f; AppTheme.White -> 0.55f; AppTheme.Black -> 0.7f }
        val tint = when (theme) { AppTheme.Iridescent -> Color(0xFFB79CFF); AppTheme.White -> Color(0xFF9AA6C0); AppTheme.Black -> Color(0xFF8EC5FF) }
        bubble(Offset(w * (0.82f + 0.05f * sin(a * 0.9f)), h * (0.12f + 0.03f * cos(a * 0.7f))), w * 0.13f, tint, k)
        bubble(Offset(w * (0.10f + 0.04f * cos(a * 0.8f)), h * (0.47f + 0.04f * sin(a * 0.6f))), w * 0.085f, tint, k)
        bubble(Offset(w * (0.70f + 0.06f * sin(a * 0.5f + 1f)), h * (0.70f + 0.04f * cos(a * 0.9f))), w * 0.17f, tint, k * 0.9f)
        bubble(Offset(w * (0.30f + 0.05f * cos(a * 0.7f + 2f)), h * (0.93f + 0.02f * sin(a))), w * 0.10f, tint, k * 0.8f)
    }
}

private fun DrawScope.blob(c: Color, center: Offset, r: Float, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(listOf(c.copy(alpha = alpha), Color.Transparent), center = center, radius = r),
        radius = r,
        center = center,
    )
}

/**
 * 液態玻璃：內部放一份「高斯模糊後的背景」並依位置反向位移（API 31+ 為真模糊），
 * 疊上半透明漸層、高光與漸層描邊。可傳入任意 [shape]（例如 HomePod 外形）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    shape: Shape? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val sh = shape ?: RoundedCornerShape(radius)
    val p = LocalPalette.current
    val theme = LocalAppTheme.current
    val cfg = LocalConfiguration.current
    var pos by remember { mutableStateOf(IntOffset.Zero) }
    val st = remember { PressState() }
    val cb by rememberUpdatedState(onClick)
    val clickMod = if (onClick != null) Modifier.pressDetect(st, true) { cb?.invoke() } else Modifier
    val sc by animateFloatAsState(
        if (onClick != null && st.pressed) 0.965f else 1f,
        spring(dampingRatio = 0.5f, stiffness = 480f), label = "card-press",
    )
    val glowA by animateFloatAsState(if (st.pressed) 1f else 0f, tween(if (st.pressed) 120 else 420), label = "card-glow")
    val sceneT = LocalSceneTime.current
    val shadowMod = if (theme == AppTheme.White) {
        Modifier.shadow(10.dp, sh, clip = false, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
    } else Modifier

    Box(
        modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .then(shadowMod)
            .onGloballyPositioned {
                val q = it.positionInRoot()
                pos = IntOffset(q.x.roundToInt(), q.y.roundToInt())
            }
            .clip(sh)
            .then(clickMod)
    ) {
        // 1) 高斯模糊的背景（折射來源）
        Box(Modifier.matchParentSize().blur(18.dp)) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(cfg.screenWidthDp.dp, cfg.screenHeightDp.dp)
                    .offset { IntOffset(-pos.x, -pos.y) }
            ) { GlassScene() }
        }
        // 2) 薄薄的色調、頂部高光、邊緣折光
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.linearGradient(listOf(p.fillA, p.fillB)))
                .drawBehind {
                    // 頂部鏡面高光（隨時間緩緩「呼吸」）
                    val breathe = 0.86f + 0.14f * sin(sceneT.value * 1.6f)
                    drawRect(
                        Brush.verticalGradient(
                            listOf(p.highlight.copy(alpha = p.highlight.alpha * breathe), Color.Transparent),
                            startY = 0f, endY = size.height * 0.45f,
                        )
                    )
                    // 左上角柔光
                    drawRect(
                        Brush.radialGradient(
                            listOf(p.highlight.copy(alpha = p.highlight.alpha * 0.8f), Color.Transparent),
                            center = Offset(size.width * 0.1f, 0f),
                            radius = size.width * 0.7f,
                        )
                    )
                    // 定時掃過的鏡面光帶（所有玻璃同步，像光掠過）
                    val ph = (sceneT.value * 0.26f) % 3.6f
                    if (ph < 1f) {
                        val x = size.width * (-0.3f + 1.6f * ph)
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = if (p.dark) 0.10f else 0.22f), Color.Transparent),
                                start = Offset(x - size.width * 0.18f, 0f), end = Offset(x + size.width * 0.18f, size.height * 0.6f),
                            )
                        )
                    }
                    // 觸點光暈：手指按下的位置亮起，放開後慢慢散去
                    if (glowA > 0.01f) {
                        drawRect(
                            Brush.radialGradient(
                                listOf(
                                    p.accent.copy(alpha = 0.26f * glowA),
                                    Color.White.copy(alpha = 0.20f * glowA),
                                    Color.Transparent,
                                ),
                                center = st.touch, radius = size.minDimension * 1.15f,
                            )
                        )
                    }
                    val outline = sh.createOutline(size, layoutDirection, this)
                    val rim = Brush.linearGradient(
                        listOf(p.borderA, p.borderB, p.borderA.copy(alpha = p.borderA.alpha * 0.45f)),
                        start = Offset.Zero, end = Offset(size.width, size.height),
                    )
                    // 內側柔和光暈（外半圈會被裁切掉）
                    drawOutline(outline, rim, alpha = 0.35f, style = Stroke(width = 4.dp.toPx()))
                    // 極細邊緣線
                    drawOutline(outline, rim, style = Stroke(width = 0.75.dp.toPx()))
                }
        )
        content()
    }
}

/** 玻璃質感音量滑桿。 */
@Composable
fun GlassSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    var w by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onChange)
    val density = LocalDensity.current
    val thumb = 30.dp
    val thumbPx = with(density) { thumb.toPx() }
    val v = value.coerceIn(0f, 1f)
    var dragging by remember { mutableStateOf(false) }
    var lastStep by remember { mutableIntStateOf(-1) }
    val thumbScale by animateFloatAsState(
        if (dragging) 1.32f else 1f, spring(dampingRatio = 0.45f, stiffness = 420f), label = "thumb",
    )
    val trackH by animateFloatAsState(if (dragging) 16f else 12f, spring(0.6f, 500f), label = "track")
    fun emit(x: Float) {
        val f = (x / w).coerceIn(0f, 1f)
        val step = (f * 20).roundToInt()
        if (step != lastStep) {
            lastStep = step
            if (step == 0 || step == 20) Haptics.edge() else Haptics.tick()
        }
        cb(f)
    }

    Box(
        modifier
            .height(44.dp)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(w) {
                detectTapGestures(
                    onPress = { dragging = true; tryAwaitRelease(); dragging = false },
                    onTap = { emit(it.x) },
                )
            }
            .pointerInput(w) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { c, _ -> emit(c.position.x) }
            }
    ) {
        val track = RoundedCornerShape(50)
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().height(trackH.dp).clip(track).background(p.track)
        )
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(v).height(trackH.dp).clip(track)
                .background(
                    if (p.sliderFill.size > 1) Brush.horizontalGradient(p.sliderFill)
                    else Brush.horizontalGradient(listOf(p.sliderFill[0], p.sliderFill[0]))
                )
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(((w - thumbPx) * v).roundToInt(), 0) }
                .size(thumb)
                .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE9ECF5))))
                .border(1.dp, Color(0x33000000), CircleShape)
        )
    }
}

/** 向量圖示（以色彩著色）。 */
@Composable
fun GIcon(res: Int, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Image(painterResource(res), null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

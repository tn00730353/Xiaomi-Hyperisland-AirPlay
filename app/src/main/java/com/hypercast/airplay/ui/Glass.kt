// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

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
    val clickMod = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val shadowMod = if (theme == AppTheme.White) {
        Modifier.shadow(10.dp, sh, clip = false, ambientColor = Color(0x22000000), spotColor = Color(0x33000000))
    } else Modifier

    Box(
        modifier
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
                    // 頂部鏡面高光
                    drawRect(
                        Brush.verticalGradient(
                            listOf(p.highlight, Color.Transparent),
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

    Box(
        modifier
            .height(44.dp)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(w) { detectTapGestures { cb((it.x / w).coerceIn(0f, 1f)) } }
            .pointerInput(w) { detectHorizontalDragGestures { c, _ -> cb((c.position.x / w).coerceIn(0f, 1f)) } }
    ) {
        val track = RoundedCornerShape(50)
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth().height(12.dp).clip(track).background(p.track)
        )
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(v).height(12.dp).clip(track)
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

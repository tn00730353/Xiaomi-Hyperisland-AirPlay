// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hypercast.airplay.DiagCategory

/**
 * HyperOS 4 風格圓潤超橢圓（Squircle）漸層圖示：
 *  - 柔和飽和雙色漸層底 + 頂部液態玻璃微霧面高光 + 雙層半透明白字形。
 */
@Composable
fun HyperOsDiagIcon(cat: DiagCategory, size: Dp = 46.dp, modifier: Modifier = Modifier) {
    val (c1, c2) = when (cat) {
        DiagCategory.DEVICE_OS -> Color(0xFF5BA4FF) to Color(0xFF2563EB)
        DiagCategory.NETWORK_WIFI -> Color(0xFF38D4F5) to Color(0xFF0284C7)
        DiagCategory.MDNS_AIRPLAY -> Color(0xFFA78BFA) to Color(0xFF6366F1)
        DiagCategory.STREAM_PROTOCOL -> Color(0xFF34D399) to Color(0xFF059669)
        DiagCategory.AUDIO_CAPTURE -> Color(0xFFFF7A99) to Color(0xFFE11D48)
        DiagCategory.ISLAND_NOTIF -> Color(0xFFFFB347) to Color(0xFFEA580C)
        DiagCategory.POWER_BACKGROUND -> Color(0xFF6EE76B) to Color(0xFF16A34A)
        DiagCategory.APPROX_LOCATION -> Color(0xFF38BDF8) to Color(0xFF0369A1)
    }
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(c1, c2)))
            .border(
                width = 0.8.dp,
                brush = Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x18FFFFFF))),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            // HyperOS 4 頂部微高光層
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0x40FFFFFF), Color.Transparent),
                    startY = 0f,
                    endY = h * 0.52f,
                ),
                size = Size(w, h * 0.52f),
                cornerRadius = CornerRadius(w * 0.30f, w * 0.30f),
            )

            val white = Color.White
            val soft = Color(0x99FFFFFF)
            val strokeW = w * 0.075f

            when (cat) {
                DiagCategory.DEVICE_OS -> {
                    drawRoundRect(
                        color = soft,
                        topLeft = Offset(w * 0.28f, h * 0.17f),
                        size = Size(w * 0.44f, h * 0.66f),
                        cornerRadius = CornerRadius(w * 0.11f, w * 0.11f),
                    )
                    drawRoundRect(
                        color = white,
                        topLeft = Offset(w * 0.36f, h * 0.33f),
                        size = Size(w * 0.28f, h * 0.28f),
                        cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                    )
                    drawLine(white, Offset(w * 0.44f, h * 0.74f), Offset(w * 0.56f, h * 0.74f), strokeW * 0.8f, StrokeCap.Round)
                }

                DiagCategory.NETWORK_WIFI -> {
                    val stroke = Stroke(width = strokeW, cap = StrokeCap.Round)
                    drawArc(soft, 222f, 96f, false, Offset(w * 0.14f, h * 0.24f), Size(w * 0.72f, h * 0.72f), style = stroke)
                    drawArc(white, 222f, 96f, false, Offset(w * 0.27f, h * 0.38f), Size(w * 0.46f, h * 0.46f), style = stroke)
                    drawCircle(white, w * 0.075f, Offset(w * 0.50f, h * 0.72f))
                }

                DiagCategory.MDNS_AIRPLAY -> {
                    drawRoundRect(
                        color = soft,
                        topLeft = Offset(w * 0.18f, h * 0.22f),
                        size = Size(w * 0.64f, h * 0.42f),
                        cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                        style = Stroke(width = strokeW, join = StrokeJoin.Round),
                    )
                    val tri = Path().apply {
                        moveTo(w * 0.50f, h * 0.56f)
                        lineTo(w * 0.69f, h * 0.79f)
                        lineTo(w * 0.31f, h * 0.79f)
                        close()
                    }
                    drawPath(tri, white)
                }

                DiagCategory.STREAM_PROTOCOL -> {
                    val p = Path().apply {
                        moveTo(w * 0.16f, h * 0.52f)
                        lineTo(w * 0.31f, h * 0.52f)
                        lineTo(w * 0.41f, h * 0.26f)
                        lineTo(w * 0.56f, h * 0.74f)
                        lineTo(w * 0.68f, h * 0.44f)
                        lineTo(w * 0.84f, h * 0.44f)
                    }
                    drawPath(p, white, style = Stroke(width = strokeW * 1.05f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawCircle(soft, w * 0.08f, Offset(w * 0.82f, h * 0.44f))
                }

                DiagCategory.AUDIO_CAPTURE -> {
                    val bars = listOf(0.28f to 0.34f, 0.43f to 0.58f, 0.58f to 0.46f, 0.73f to 0.28f)
                    bars.forEachIndexed { i, (xRatio, hRatio) ->
                        val top = h * (0.5f - hRatio / 2f)
                        val bot = h * (0.5f + hRatio / 2f)
                        drawLine(
                            color = if (i % 2 == 1) white else soft,
                            start = Offset(w * xRatio, top),
                            end = Offset(w * xRatio, bot),
                            strokeWidth = strokeW * 1.25f,
                            cap = StrokeCap.Round,
                        )
                    }
                }

                DiagCategory.ISLAND_NOTIF -> {
                    drawRoundRect(
                        color = white,
                        topLeft = Offset(w * 0.16f, h * 0.34f),
                        size = Size(w * 0.68f, h * 0.32f),
                        cornerRadius = CornerRadius(h * 0.16f, h * 0.16f),
                    )
                    drawCircle(Color(0xFFEA580C), w * 0.085f, Offset(w * 0.32f, h * 0.50f))
                    drawRoundRect(
                        color = Color(0x88EA580C),
                        topLeft = Offset(w * 0.47f, h * 0.45f),
                        size = Size(w * 0.26f, h * 0.10f),
                        cornerRadius = CornerRadius(h * 0.05f, h * 0.05f),
                    )
                }

                DiagCategory.POWER_BACKGROUND -> {
                    drawRoundRect(
                        color = soft,
                        topLeft = Offset(w * 0.17f, h * 0.30f),
                        size = Size(w * 0.60f, h * 0.40f),
                        cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                    )
                    drawRoundRect(
                        color = soft,
                        topLeft = Offset(w * 0.79f, h * 0.42f),
                        size = Size(w * 0.07f, h * 0.16f),
                        cornerRadius = CornerRadius(w * 0.03f, w * 0.03f),
                    )
                    val bolt = Path().apply {
                        moveTo(w * 0.50f, h * 0.25f)
                        lineTo(w * 0.36f, h * 0.53f)
                        lineTo(w * 0.48f, h * 0.53f)
                        lineTo(w * 0.44f, h * 0.76f)
                        lineTo(w * 0.60f, h * 0.46f)
                        lineTo(w * 0.48f, h * 0.46f)
                        close()
                    }
                    drawPath(bolt, white)
                }

                DiagCategory.APPROX_LOCATION -> {
                    val pin = Path().apply {
                        moveTo(w * 0.50f, h * 0.82f)
                        cubicTo(w * 0.25f, h * 0.60f, w * 0.22f, h * 0.42f, w * 0.24f, h * 0.35f)
                        cubicTo(w * 0.28f, h * 0.20f, w * 0.72f, h * 0.20f, w * 0.76f, h * 0.35f)
                        cubicTo(w * 0.78f, h * 0.42f, w * 0.75f, h * 0.60f, w * 0.50f, h * 0.82f)
                        close()
                    }
                    drawPath(pin, white)
                    drawCircle(Color(0xFF0284C7), w * 0.11f, Offset(w * 0.50f, h * 0.42f))
                }
            }
        }
    }
}

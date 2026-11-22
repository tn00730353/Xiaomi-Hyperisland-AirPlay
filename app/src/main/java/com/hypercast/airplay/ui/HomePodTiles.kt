// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hypercast.airplay.DeviceItem
import com.hypercast.airplay.Phase
import com.hypercast.airplay.PodKind
import com.hypercast.airplay.podKind

// © DylanLiao — HyperCast

/** 小小的狀態標籤。 */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(p.chip)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) { GText(text, 12, FontWeight.Medium, 0.9f) }
}

/**
 * 選單中的裝置：
 *  - HomePod（1 / 2 代）→ 大顆　- HomePod mini → 小顆　- 立體聲組 → 兩顆　- 其他 → 一般圓角卡片
 */
@Composable
fun DeviceTile(item: DeviceItem, active: Boolean, phase: Phase, onClick: () -> Unit) {
    val rx = item.primary
    val status = when {
        active && phase == Phase.Streaming -> "播放中"
        active -> "連線中"
        rx.password -> "需要密碼"
        rx.ap2 -> "AirPlay 2"
        else -> "AirPlay"
    }
    val kind = rx.podKind

    when {
        item.pair -> Column(Modifier.width(272.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PodBody(Modifier.size(131.dp, 156.dp), RoundedCornerShape(58.dp), active, onClick)
                PodBody(Modifier.size(131.dp, 156.dp), RoundedCornerShape(58.dp), active, onClick)
            }
            Spacer(Modifier.height(8.dp))
            GText(rx.name, 16, FontWeight.SemiBold, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Chip("立體聲組 · $status")
        }

        kind == PodKind.Large -> Column(Modifier.width(148.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PodBody(Modifier.size(148.dp, 172.dp), RoundedCornerShape(64.dp), active, onClick)
            Spacer(Modifier.height(8.dp))
            GText(rx.name, 16, FontWeight.SemiBold, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Chip(status)
        }

        kind == PodKind.Mini -> Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PodBody(Modifier.size(104.dp, 98.dp), RoundedCornerShape(50.dp), active, onClick)
            Spacer(Modifier.height(8.dp))
            GText(rx.name, 15, FontWeight.SemiBold, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Chip(status)
        }

        else -> GlassCard(Modifier.fillMaxWidth(), radius = 26.dp, onClick = onClick) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(LocalPalette.current.chip), contentAlignment = Alignment.Center) {
                    GText("♪", 20, FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    GText(rx.name, 17, FontWeight.SemiBold)
                    GText(listOf(rx.model, rx.host).filter { it.isNotBlank() }.joinToString(" · "), 12, secondary = true)
                }
                Chip(status)
            }
        }
    }
}

/**
 * HomePod 的玻璃本體：外形即玻璃，上面有網格織布點紋與頂部觸控光環
 * （播放時光環會跟著幻彩流動，閒置時為柔和的灰白）。
 */
@Composable
private fun PodBody(modifier: Modifier, shape: Shape, active: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val t = LocalSceneTime.current
    GlassCard(modifier, shape = shape, onClick = onClick) {
        Box(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val step = 7.dp.toPx()
                    val pts = ArrayList<Offset>()
                    var y = size.height * 0.24f
                    var row = 0
                    while (y < size.height * 0.95f) {
                        var x = size.width * 0.12f + (if (row % 2 == 0) 0f else step / 2f)
                        while (x < size.width * 0.88f) { pts.add(Offset(x, y)); x += step }
                        y += step * 0.86f
                        row++
                    }
                    onDrawBehind {
                        drawPoints(pts, PointMode.Points, p.podDot, 2.2.dp.toPx(), StrokeCap.Round)

                        // 頂部觸控光環
                        val gw = size.width * 0.50f
                        val gh = 9.dp.toPx()
                        val topLeft = Offset((size.width - gw) / 2f, size.height * 0.09f)
                        val brush = if (active) {
                            val a = t.value * 57.29578f
                            Brush.horizontalGradient(
                                listOf(
                                    Color.hsv((a) % 360f, 0.65f, 1f),
                                    Color.hsv((a + 90f) % 360f, 0.65f, 1f),
                                    Color.hsv((a + 200f) % 360f, 0.65f, 1f),
                                ),
                                startX = topLeft.x, endX = topLeft.x + gw,
                            )
                        } else {
                            Brush.horizontalGradient(
                                listOf(p.text2.copy(alpha = 0.10f), p.text2.copy(alpha = 0.30f), p.text2.copy(alpha = 0.10f)),
                                startX = topLeft.x, endX = topLeft.x + gw,
                            )
                        }
                        if (active) {
                            drawOval(
                                Brush.radialGradient(
                                    listOf(Color(0x66FFFFFF), Color.Transparent),
                                    center = Offset(size.width / 2f, topLeft.y + gh / 2f),
                                    radius = gw * 0.8f,
                                ),
                                topLeft = Offset(topLeft.x - gw * 0.2f, topLeft.y - gh * 1.6f),
                                size = Size(gw * 1.4f, gh * 4.2f),
                            )
                        }
                        drawOval(brush, topLeft = topLeft, size = Size(gw, gh))
                    }
                }
        )
    }
}

// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hypercast.airplay.DeviceItem
import com.hypercast.airplay.Phase
import com.hypercast.airplay.PodKind
import com.hypercast.airplay.R
import com.hypercast.airplay.podKind

/** 小小的狀態標籤。 */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(p.chip)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) { GText(text, 12, FontWeight.Medium, 0.9f) }
}

/**
 * 選單中的一台裝置：左邊是小小的 HomePod 圖示，右邊是名稱與狀態。
 *  HomePod（1 / 2 代）→ 大顆小圖　HomePod mini → 小顆小圖　立體聲組 → 兩顆
 */
@Composable
fun DeviceRow(item: DeviceItem, active: Boolean, phase: Phase, onClick: () -> Unit) {
    val p = LocalPalette.current
    val rx = item.primary
    val status = when {
        active && phase == Phase.Streaming -> "播放中"
        active -> "連線中"
        rx.password -> "需要密碼"
        rx.ap2 -> "AirPlay 2"
        else -> "AirPlay"
    }
    val sub = buildList {
        if (item.pair) add("立體聲組")
        if (rx.model.isNotBlank()) add(rx.model)
    }.joinToString(" · ").ifBlank { rx.host }

    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp, onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(80.dp).size(80.dp, 56.dp), contentAlignment = Alignment.Center) {
                PodIcon(rx.podKind, item.pair, active)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                GText(rx.name, 17, FontWeight.SemiBold)
                GText(sub, 12, secondary = true)
            }
            Chip(status)
        }
    }
}

/** 小型 HomePod 圖示：機身 + 網格織布點紋 + 頂部觸控光環。 */
@Composable
private fun PodIcon(kind: PodKind, pair: Boolean, active: Boolean) {
    val p = LocalPalette.current
    when {
        pair -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            Pod(Modifier.size(34.dp, 42.dp), RoundedCornerShape(15.dp), active)
            Pod(Modifier.size(34.dp, 42.dp), RoundedCornerShape(15.dp), active)
        }
        kind == PodKind.Large -> Pod(Modifier.size(44.dp, 52.dp), RoundedCornerShape(19.dp), active)
        kind == PodKind.Mini -> Pod(Modifier.size(36.dp, 34.dp), RoundedCornerShape(17.dp), active)
        else -> Box(Modifier.size(40.dp).clip(CircleShape).background(p.chip), contentAlignment = Alignment.Center) {
            GIcon(R.drawable.ic_airplay, p.text, 22.dp)
        }
    }
}

@Composable
private fun Pod(modifier: Modifier, shape: Shape, active: Boolean) {
    val p = LocalPalette.current
    val t = LocalSceneTime.current
    val body = if (p.dark) listOf(Color(0xFF55555C), Color(0xFF1C1C20)) else listOf(Color(0xFFFAFAFC), Color(0xFFD3D4DB))
    val edge = if (p.dark) Color(0x55FFFFFF) else Color(0x26000000)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(body))
            .border(0.5.dp, edge, shape)
            .drawWithCache {
                val step = 3.4.dp.toPx()
                val pts = ArrayList<Offset>()
                var y = size.height * 0.26f
                var row = 0
                while (y < size.height * 0.94f) {
                    var x = size.width * 0.14f + (if (row % 2 == 0) 0f else step / 2f)
                    while (x < size.width * 0.86f) { pts.add(Offset(x, y)); x += step }
                    y += step * 0.86f
                    row++
                }
                onDrawBehind {
                    drawPoints(pts, PointMode.Points, p.podDot, 1.2.dp.toPx(), StrokeCap.Round)
                    val gw = size.width * 0.52f
                    val gh = 3.5.dp.toPx()
                    val tl = Offset((size.width - gw) / 2f, size.height * 0.10f)
                    val brush = if (active) {
                        val a = t.value * 57.29578f
                        Brush.horizontalGradient(
                            listOf(
                                Color.hsv(a % 360f, 0.7f, 1f),
                                Color.hsv((a + 100f) % 360f, 0.7f, 1f),
                                Color.hsv((a + 210f) % 360f, 0.7f, 1f),
                            ),
                            startX = tl.x, endX = tl.x + gw,
                        )
                    } else Brush.horizontalGradient(
                        listOf(Color(0x33808088), Color(0x66808088), Color(0x33808088)),
                        startX = tl.x, endX = tl.x + gw,
                    )
                    drawOval(brush, tl, Size(gw, gh))
                }
            }
    )
}

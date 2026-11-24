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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hypercast.airplay.DeviceItem
import com.hypercast.airplay.Phase
import com.hypercast.airplay.Role
import com.hypercast.airplay.Target
import com.hypercast.airplay.TargetState
import com.hypercast.airplay.appleDeviceIcon
import com.hypercast.airplay.applePairLabel

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
 * 選單中的一台裝置：左邊使用蘋果官方 SF Symbols 風格圖示（單顆／兩顆 HomePod 立體聲組／HomePod mini／Apple TV），
 * 右邊為名稱、官方標示與狀態。
 */
@Composable
fun DeviceRow(item: DeviceItem, target: Target?, phase: Phase, onClick: () -> Unit) {
    val rx = item.primary
    val active = target != null
    val status = when {
        target != null && target.state == TargetState.Streaming -> "已連線"
        target != null -> "連線中"
        rx.password -> "需要密碼"
        rx.ap2 -> "AirPlay 2"
        else -> "AirPlay"
    }
    val sub = buildList {
        if (item.pair) add(applePairLabel(item.members))
        if (rx.model.isNotBlank()) add(rx.model)
    }.joinToString(" · ").ifBlank { rx.host }

    val iconRes = appleDeviceIcon(item.members, item.pair)

    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp, onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppleDeviceBadge(iconRes = iconRes, active = active, isPair = item.pair)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                GText(item.name, 17, FontWeight.SemiBold)
                GText(sub, 12, secondary = true)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip(status)
                // 只有兩台單顆喇叭分左右時才顯示聲道標籤
                if (target != null && target.role != Role.Stereo) Chip(target.role.label)
            }
        }
    }
}

/**
 * 蘋果官方風格裝置圖示徽章（單顆／兩顆 HomePod 皆採 Apple SF Symbols 官方輪廓）：
 *  - 未連線：柔和液態玻璃圓角徽章 + 單色官方符號
 *  - 連線中／串流中：流動彩光外環 + 高亮官方符號
 */
@Composable
fun AppleDeviceBadge(iconRes: Int, active: Boolean, isPair: Boolean = false) {
    val p = LocalPalette.current
    val t = LocalSceneTime.current
    val shape = RoundedCornerShape(16.dp)
    val bgBrush = if (active) {
        val a = t.value * 57.29578f
        Brush.linearGradient(
            listOf(
                Color.hsv(a % 360f, 0.52f, 0.98f),
                Color.hsv((a + 110f) % 360f, 0.52f, 0.98f),
                Color.hsv((a + 220f) % 360f, 0.52f, 0.98f),
            )
        )
    } else {
        Brush.verticalGradient(
            if (p.dark) listOf(Color(0x30FFFFFF), Color(0x14FFFFFF))
            else listOf(Color(0xB8FFFFFF), Color(0x55E5E7F0))
        )
    }
    val borderColor = if (active) Color(0xAAFFFFFF) else if (p.dark) Color(0x40FFFFFF) else Color(0x22000000)

    Box(
        Modifier
            .size(54.dp)
            .clip(shape)
            .background(bgBrush)
            .border(if (active) 1.dp else 0.6.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        GIcon(
            res = iconRes,
            tint = if (active) Color.White else p.text,
            size = if (isPair) 32.dp else 28.dp,
        )
    }
}

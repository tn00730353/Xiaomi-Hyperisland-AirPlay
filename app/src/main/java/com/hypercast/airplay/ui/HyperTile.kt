// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * HyperOS 4 風格圖示磚：圓角方塊、2.5D 厚度（上緣柔光、底部內陰影、下方投影）、細亮邊、白色圖示帶淡淡陰影。
 * 對照 HyperOS 4 公開的設計方向：柔光玻璃質感、2.5D 圓角圖示、較明顯的陰影層次。
 *
 * @param top    漸層上緣色（ARGB）
 * @param bottom 漸層下緣色（ARGB）
 */
@Composable
fun HyperTile(icon: Int, top: Long, bottom: Long, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val t = Color(top)
    val b = Color(bottom)
    val shape = RoundedCornerShape(size * 0.30f)
    Box(
        modifier
            .size(size)
            .shadow(8.dp, shape, ambientColor = b.copy(alpha = 0.45f), spotColor = b.copy(alpha = 0.65f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(t, b)))
            .drawWithContent {
                drawContent()
                // 上半部柔光
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.38f), 0.55f to Color.Transparent))
                // 底部內陰影：做出 2.5D 的厚度
                drawRect(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.16f)))
            }
            .border(
                0.8.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.06f))),
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        GIcon(icon, Color(0x40000000), size * 0.5f, Modifier.offset(y = 1.5.dp))
        GIcon(icon, Color.White, size * 0.5f)
    }
}

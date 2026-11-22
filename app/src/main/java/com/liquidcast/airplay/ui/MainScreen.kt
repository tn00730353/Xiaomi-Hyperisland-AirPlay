package com.liquidcast.airplay.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liquidcast.airplay.Phase
import com.liquidcast.airplay.Receiver
import com.liquidcast.airplay.UiState
import kotlin.math.PI
import kotlin.math.roundToInt

@Composable
private fun GText(
    text: String,
    size: Int = 15,
    weight: FontWeight = FontWeight.Normal,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
    align: TextAlign = TextAlign.Start,
) = BasicText(
    text,
    modifier,
    style = TextStyle(
        color = Color.White.copy(alpha = alpha),
        fontSize = size.sp,
        fontWeight = weight,
        textAlign = align,
    ),
)

@Composable
private fun GlassButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassCard(modifier.height(48.dp), radius = 24.dp, onClick = onClick) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            GText(text, 15, FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Chip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x26FFFFFF))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) { GText(text, 12, FontWeight.Medium, 0.9f) }
}

@Composable
fun MainScreen(
    state: UiState,
    hyperIslandOn: Boolean,
    onConnect: (Receiver) -> Unit,
    onDisconnect: () -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "scene").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )

    CompositionLocalProvider(LocalSceneTime provides t) {
        Box(Modifier.fillMaxSize()) {
            GlassScene()
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    GText("LiquidCast", 36, FontWeight.Bold)
                    GText("把 Android 的聲音串流到 AirPlay 裝置", 14, alpha = 0.7f)
                }

                NowCasting(state, hyperIslandOn, onDisconnect, onVolume, onMute)

                GText("附近的 AirPlay 裝置", 13, FontWeight.SemiBold, 0.7f, Modifier.padding(start = 6.dp, top = 4.dp))

                if (state.receivers.isEmpty()) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        GText(
                            "正在搜尋裝置…\n請確認手機與 AirPlay 裝置在同一個 Wi-Fi",
                            14, alpha = 0.8f,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
                state.receivers.forEach { rx ->
                    val active = state.selected?.id == rx.id && state.phase != Phase.Idle
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        radius = 24.dp,
                        onClick = { if (state.phase != Phase.Connecting) onConnect(rx) },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(42.dp).clip(CircleShape).background(Color(0x33FFFFFF)),
                                contentAlignment = Alignment.Center,
                            ) { GText("♪", 20, FontWeight.Bold) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                GText(rx.name, 17, FontWeight.SemiBold)
                                GText(
                                    listOf(rx.model, rx.host).filter { it.isNotBlank() }.joinToString(" · "),
                                    12, alpha = 0.65f,
                                )
                            }
                            when {
                                active -> Chip(if (state.phase == Phase.Streaming) "播放中" else "連線中")
                                rx.password -> Chip("需要密碼")
                                rx.ap2 -> Chip("AirPlay 2")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun NowCasting(
    state: UiState,
    hyperIslandOn: Boolean,
    onDisconnect: () -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), radius = 34.dp) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val title = when (state.phase) {
                Phase.Streaming -> state.selected?.name ?: "AirPlay"
                Phase.Connecting -> "正在連線 ${state.selected?.name ?: ""}"
                Phase.Error -> "連線失敗"
                Phase.Idle -> "尚未連線"
            }
            val sub = when (state.phase) {
                Phase.Streaming -> "正在串流系統音訊"
                Phase.Connecting -> "建立 RAOP 連線…"
                Phase.Error -> state.message ?: "請再試一次"
                Phase.Idle -> state.message ?: "從下方選一台裝置開始"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape).background(
                        when (state.phase) {
                            Phase.Streaming -> Color(0xFF4CFFA8)
                            Phase.Connecting -> Color(0xFFFFD24C)
                            Phase.Error -> Color(0xFFFF6B6B)
                            Phase.Idle -> Color(0x66FFFFFF)
                        }
                    )
                )
                Spacer(Modifier.width(10.dp))
                GText(title, 22, FontWeight.Bold)
            }
            GText(sub, 14, alpha = 0.75f)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("立體聲")
                Chip("44.1 kHz")
                Chip(if (state.selected?.ap2 == true) "AirPlay 2" else "ALAC")
                Chip(if (hyperIslandOn) "HyperIsland ✓" else "HyperIsland —")
            }

            val pct = if (state.muted) 0 else (state.volume * 100).roundToInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText("音量", 13, FontWeight.SemiBold, 0.7f)
                Spacer(Modifier.weight(1f))
                GText(if (state.muted) "靜音" else "$pct%", 15, FontWeight.Bold)
            }
            GlassSlider(if (state.muted) 0f else state.volume, onVolume, Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GlassButton(if (state.muted) "取消靜音" else "靜音", Modifier.weight(1f), onMute)
                GlassButton("中斷連線", Modifier.weight(1f), onDisconnect)
            }
        }
    }
}

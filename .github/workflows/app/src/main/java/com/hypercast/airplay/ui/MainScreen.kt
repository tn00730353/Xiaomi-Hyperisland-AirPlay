// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import android.app.Activity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.hypercast.airplay.BuildConfig
import com.hypercast.airplay.DeviceItem
import com.hypercast.airplay.Phase
import com.hypercast.airplay.PodKind
import com.hypercast.airplay.Receiver
import com.hypercast.airplay.UiState
import com.hypercast.airplay.buildItems
import com.hypercast.airplay.podKind
import kotlin.math.PI
import kotlin.math.roundToInt

// © DylanLiao — HyperCast

@Composable
fun MainScreen(
    state: UiState,
    theme: AppTheme,
    hyperIslandOn: Boolean,
    onTheme: (AppTheme) -> Unit,
    onConnect: (Receiver) -> Unit,
    onDisconnect: () -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
) {
    val palette = paletteFor(theme)
    val t = rememberInfiniteTransition(label = "scene").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )

    // 狀態列 / 導覽列圖示顏色跟著外觀切換
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !palette.dark
            c.isAppearanceLightNavigationBars = !palette.dark
        }
    }

    CompositionLocalProvider(
        LocalSceneTime provides t,
        LocalAppTheme provides theme,
        LocalPalette provides palette,
    ) {
        Box(Modifier.fillMaxSize()) {
            GlassScene()
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column {
                    GText("HyperCast", 38, FontWeight.Bold)
                    GText("把 Android 的聲音串流到 AirPlay 裝置", 14, secondary = true)
                }

                ThemePicker(theme, onTheme)
                NowPlaying(state, hyperIslandOn, onDisconnect, onVolume, onMute)

                GText("我的裝置", 24, FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Devices(state, onConnect)

                Spacer(Modifier.height(6.dp))
                GText(
                    "Designed & developed by ${BuildConfig.DEVELOPER}",
                    11, alpha = 0.55f, secondary = true,
                    modifier = Modifier.fillMaxWidth(), align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ThemePicker(current: AppTheme, onPick: (AppTheme) -> Unit) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        AppTheme.values().forEach { t ->
            val sel = t == current
            GlassCard(Modifier.weight(1f).height(46.dp), radius = 23.dp, onClick = { onPick(t) }) {
                Box(
                    Modifier
                        .matchParentSize()
                        .then(if (sel) Modifier.background(p.accent.copy(alpha = 0.16f)) else Modifier)
                        .then(if (sel) Modifier.border(1.5.dp, p.accent.copy(alpha = 0.85f), RoundedCornerShape(23.dp)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { GText(t.label, 15, if (sel) FontWeight.Bold else FontWeight.Medium) }
            }
        }
    }
}

/** 仿 Apple Music「正在播放」：大封面、標題、拉桿與圓形控制鍵。 */
@Composable
private fun NowPlaying(
    state: UiState,
    hyperIslandOn: Boolean,
    onDisconnect: () -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
) {
    val p = LocalPalette.current
    val t = LocalSceneTime.current
    GlassCard(Modifier.fillMaxWidth(), radius = 36.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val streaming = state.phase == Phase.Streaming
            val title = when (state.phase) {
                Phase.Streaming -> state.selected?.name ?: "AirPlay"
                Phase.Connecting -> "正在連線…"
                Phase.Error -> "連線失敗"
                Phase.Idle -> "尚未連線"
            }
            val sub = when (state.phase) {
                Phase.Streaming -> "正在串流系統音訊"
                Phase.Connecting -> state.selected?.name ?: ""
                Phase.Error -> state.message ?: "請再試一次"
                Phase.Idle -> state.message ?: "從下方選一台裝置開始"
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 封面：串流時為流動幻彩，閒置為柔和灰
                val art = if (streaming) {
                    val a = t.value * 57.29578f
                    Brush.linearGradient(
                        listOf(Color.hsv(a % 360f, 0.55f, 1f), Color.hsv((a + 120f) % 360f, 0.55f, 1f), Color.hsv((a + 240f) % 360f, 0.55f, 1f))
                    )
                } else Brush.linearGradient(listOf(p.chip, p.track))
                Box(
                    Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)).background(art),
                    contentAlignment = Alignment.Center,
                ) { GText("♪", 34, FontWeight.Bold, alpha = if (streaming) 0.9f else 0.5f) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    GText(title, 22, FontWeight.Bold)
                    GText(sub, 14, secondary = true)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("立體聲")
                        Chip(if (state.selected?.ap2 == true) "AirPlay 2" else "44.1 kHz")
                    }
                }
            }

            val pct = if (state.muted) 0 else (state.volume * 100).roundToInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText("🔈", 16)
                GlassSlider(if (state.muted) 0f else state.volume, onVolume, Modifier.weight(1f).padding(horizontal = 8.dp))
                GText("🔊", 16)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText(if (state.muted) "靜音" else "音量 $pct%", 13, FontWeight.SemiBold, secondary = true)
                Spacer(Modifier.weight(1f))
                GText(if (hyperIslandOn) "HyperIsland ✓" else "HyperIsland —", 12, secondary = true)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RoundButton(if (state.muted) "🔇" else "🔈", null, onMute)
                RoundButton("■", p.accent, onDisconnect)
            }
        }
    }
}

@Composable
private fun RoundButton(label: String, tint: Color?, onClick: () -> Unit) {
    GlassCard(Modifier.size(60.dp), radius = 30.dp, onClick = onClick) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            if (tint != null) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(tint))
            } else GText(label, 22)
        }
    }
}

/** 依序排列裝置：每列最多兩顆，立體聲組獨佔一列。 */
@Composable
private fun Devices(state: UiState, onConnect: (Receiver) -> Unit) {
    val items = buildItems(state.receivers)
    if (items.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth()) {
            GText(
                "正在搜尋裝置…\n請確認手機與 AirPlay 裝置在同一個 Wi-Fi",
                14, secondary = true, modifier = Modifier.padding(20.dp),
            )
        }
        return
    }
    val rows = ArrayList<List<DeviceItem>>()
    var cur = ArrayList<DeviceItem>()
    for (d in items) {
        val wide = d.pair || d.primary.isGenericDevice()
        if (wide) {
            if (cur.isNotEmpty()) { rows.add(cur); cur = ArrayList() }
            rows.add(listOf(d))
        } else {
            cur.add(d)
            if (cur.size == 2) { rows.add(cur); cur = ArrayList() }
        }
    }
    if (cur.isNotEmpty()) rows.add(cur)

    Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.fillMaxWidth()) {
        for (row in rows) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
            ) {
                for (d in row) {
                    val active = state.selected?.id == d.primary.id && state.phase != Phase.Idle && state.phase != Phase.Error
                    DeviceTile(d, active, state.phase) {
                        if (state.phase != Phase.Connecting) onConnect(d.primary)
                    }
                }
            }
        }
    }
}

private fun Receiver.isGenericDevice() = podKind == PodKind.Generic

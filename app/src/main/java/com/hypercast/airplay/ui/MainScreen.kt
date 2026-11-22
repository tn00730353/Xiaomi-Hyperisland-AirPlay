// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.hypercast.airplay.BuildConfig
import com.hypercast.airplay.DeviceItem
import com.hypercast.airplay.Phase
import com.hypercast.airplay.PodKind
import com.hypercast.airplay.R
import com.hypercast.airplay.Receiver
import com.hypercast.airplay.SystemInfo
import com.hypercast.airplay.UiState
import com.hypercast.airplay.buildItems
import com.hypercast.airplay.podKind
import kotlin.math.PI
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    state: UiState,
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    onConnect: (Receiver) -> Unit,
    onDisconnect: () -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
) {
    val palette = paletteFor(theme)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val t = rememberInfiniteTransition(label = "scene").animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { w ->
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !palette.dark
            c.isAppearanceLightNavigationBars = !palette.dark
        }
    }
    BackHandler(enabled = showSettings) { showSettings = false }

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
                if (showSettings) {
                    SettingsPage(theme, onTheme) { showSettings = false }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            GText("HyperCast", 38, FontWeight.Bold)
                            GText("把 Android 的聲音串流到 AirPlay 裝置", 14, secondary = true)
                        }
                        CircleButton(R.drawable.ic_settings) { showSettings = true }
                    }
                    NowPlaying(state, onDisconnect, onVolume, onMute)
                    GText("我的裝置", 24, FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Devices(state, onConnect)
                }
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

// ---------------------------------------------------------------- 設定頁

@Composable
private fun SettingsPage(theme: AppTheme, onTheme: (AppTheme) -> Unit, onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current

    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleButton(R.drawable.ic_back, onClick = onBack)
        Spacer(Modifier.width(14.dp))
        GText("設定", 32, FontWeight.Bold)
    }

    GText("外觀", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            val all = AppTheme.values()
            all.forEachIndexed { i, t ->
                val desc = when (t) {
                    AppTheme.Iridescent -> "柔和的微彩色流動光斑"
                    AppTheme.White -> "純白背景"
                    AppTheme.Black -> "純黑背景"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { onTheme(t) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        GText(t.label, 17, FontWeight.SemiBold)
                        GText(desc, 12, secondary = true)
                    }
                    if (t == theme) GIcon(R.drawable.ic_check, p.accent, 22.dp)
                }
                if (i < all.size - 1) Divider()
            }
        }
    }

    GText("系統", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            val rows = SystemInfo.rows(ctx)
            rows.forEachIndexed { i, (k, v) ->
                InfoRow(k, v)
                if (i < rows.size - 1) Divider()
            }
        }
    }

    GText("HyperIsland", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            InfoRow("狀態", SystemInfo.hyperIslandStatus(ctx))
            Divider()
            GText(
                "若尚未允許：系統設定 → 通知與控制中心 → HyperCast，開啟「焦點通知 / 超級島」。",
                12, secondary = true, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        GText(label, 15, secondary = true, modifier = Modifier.width(96.dp))
        GText(value, 15, FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Divider() {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(0.5.dp).background(p.text2.copy(alpha = 0.25f)))
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

// ---------------------------------------------------------------- 主頁元件

/** 圓形玻璃圖示按鈕。 */
@Composable
private fun CircleButton(icon: Int, size: Int = 46, onClick: () -> Unit) {
    val p = LocalPalette.current
    GlassCard(Modifier.size(size.dp), radius = (size / 2).dp, onClick = onClick) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            GIcon(icon, p.text, 22.dp)
        }
    }
}

/** 仿 Apple Music「正在播放」：大封面、標題、拉桿與圓形控制鍵。 */
@Composable
private fun NowPlaying(
    state: UiState,
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
                val art = if (streaming) {
                    val a = t.value * 57.29578f
                    Brush.linearGradient(
                        listOf(Color.hsv(a % 360f, 0.5f, 1f), Color.hsv((a + 120f) % 360f, 0.5f, 1f), Color.hsv((a + 240f) % 360f, 0.5f, 1f))
                    )
                } else Brush.linearGradient(listOf(p.chip, p.track))
                Box(
                    Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)).background(art),
                    contentAlignment = Alignment.Center,
                ) { GIcon(R.drawable.ic_music, if (streaming) Color.White else p.text2, 34.dp) }
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
                GIcon(R.drawable.ic_vol_down, p.text2, 20.dp)
                GlassSlider(if (state.muted) 0f else state.volume, onVolume, Modifier.weight(1f).padding(horizontal = 8.dp))
                GIcon(R.drawable.ic_vol_up, p.text2, 20.dp)
            }
            GText(if (state.muted) "靜音" else "音量 $pct%", 13, FontWeight.SemiBold, secondary = true)

            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CircleButton(if (state.muted) R.drawable.ic_vol_off else R.drawable.ic_vol_up, 58, onMute)
                GlassCard(Modifier.size(58.dp), radius = 29.dp, onClick = onDisconnect) {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(p.accent))
                    }
                }
            }
        }
    }
}

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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        for (d in items) {
            val active = state.selected?.id == d.primary.id && state.phase != Phase.Idle && state.phase != Phase.Error
            DeviceRow(d, active, state.phase) {
                if (state.phase != Phase.Connecting) onConnect(d.primary)
            }
        }
    }
}

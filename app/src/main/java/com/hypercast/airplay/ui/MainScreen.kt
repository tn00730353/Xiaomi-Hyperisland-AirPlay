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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.hypercast.airplay.PinBroker
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.hypercast.airplay.AirPlayStore
import com.hypercast.airplay.BuildConfig
import com.hypercast.airplay.ChannelMode
import com.hypercast.airplay.Role
import com.hypercast.airplay.DeviceItem
import com.hypercast.airplay.DelayStore
import com.hypercast.airplay.IslandMode
import com.hypercast.airplay.PrefsStore
import com.hypercast.airplay.DiagLog
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
    onToggle: (DeviceItem) -> Unit,
    onChannelMode: (ChannelMode) -> Unit,
    onDisconnect: () -> Unit,
    onVolume: (Float) -> Unit,
    onMute: () -> Unit,
    delayMs: Int,
    onDelay: (Int) -> Unit,
    diag: DiagLog.UiState,
    onDiagEnable: () -> Unit,
    onDiagDisable: () -> Unit,
    onDiagExport: () -> Unit,
    onDiagClear: () -> Unit,
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
            val pinReq by PinBroker.request.collectAsState()
            pinReq?.let { PinDialog(it) }
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
                    SettingsPage(theme, onTheme, delayMs, onDelay, diag, onDiagEnable, onDiagDisable, onDiagExport, onDiagClear) { showSettings = false }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            GText("HyperCast", 38, FontWeight.Bold)
                            GText("把 Android 的聲音串流到 AirPlay 裝置", 14, secondary = true)
                        }
                        CircleButton(R.drawable.ic_settings) { showSettings = true }
                    }
                    NowPlaying(state, onDisconnect, onVolume, onMute)
                    if (state.phase == Phase.Streaming || state.phase == Phase.Connecting) {
                        ChannelCard(state, onChannelMode)
                    }
                    GText("我的裝置", 24, FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    if (state.phase == Phase.Streaming) {
                        GText(
                            "點另一台裝置可同時串流（最多 2 台），再點一次即移除。",
                            12, secondary = true, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                    Devices(state, onToggle)
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
private fun SettingsPage(
    theme: AppTheme,
    onTheme: (AppTheme) -> Unit,
    delayMs: Int,
    onDelay: (Int) -> Unit,
    diag: DiagLog.UiState,
    onDiagEnable: () -> Unit,
    onDiagDisable: () -> Unit,
    onDiagExport: () -> Unit,
    onDiagClear: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var showConsent by rememberSaveable { mutableStateOf(false) }
    val prefs by PrefsStore.state.collectAsState()

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

    GText("音訊延遲", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                GText(if (delayMs > 0) "+$delayMs ms" else "$delayMs ms", 17, FontWeight.SemiBold)
                GText("正值＝聲音更晚播出（對畫面太快時使用），負值＝更早；串流中可即時調整", 12, secondary = true)
            }
            Box(Modifier.clickableNoRipple { onDelay(delayMs - DelayStore.STEP) }.padding(12.dp)) { GText("－", 24, FontWeight.Bold) }
            Box(Modifier.clickableNoRipple { onDelay(0) }.padding(8.dp)) { GText("0", 15, secondary = true) }
            Box(Modifier.clickableNoRipple { onDelay(delayMs + DelayStore.STEP) }.padding(12.dp)) { GText("＋", 24, FontWeight.Bold) }
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

    GText("診斷", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { if (diag.enabled) onDiagDisable() else showConsent = true }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    GText("日誌記錄", 17, FontWeight.SemiBold)
                    GText(
                        if (diag.enabled) "記錄中 · ${diag.entries} 筆 · ${diag.sizeKb} KB"
                        else "關閉。開啟後會記錄連線過程，協助找出連線失敗的原因",
                        12, secondary = true,
                    )
                }
                Spacer(Modifier.width(12.dp))
                GlassToggle(diag.enabled)
            }
            if (diag.enabled || diag.entries > 0) {
                Divider()
                ActionRow(
                    "匯出日誌檔（.log）",
                    "檔名含時間日期與地點：" + if (diag.place.isBlank()) "未知地點" else diag.place,
                    onDiagExport,
                )
                Divider()
                ActionRow("清除日誌", "刪除本機已記錄的所有內容", onDiagClear)
            }
            Divider()
            GText(
                "日誌只存在這支手機，不會自動上傳；只有你自己匯出並傳給別人，資料才會離開手機。",
                12, secondary = true, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }

    if (showConsent) {
        ConsentDialog(
            onAgree = { showConsent = false; onDiagEnable() },
            onCancel = { showConsent = false },
        )
    }

    GText("音訊", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { PrefsStore.setMirrorVolume(ctx, !prefs.mirrorVolume) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    GText("同步手機音量鍵", 17, FontWeight.SemiBold)
                    GText(
                        "按手機音量鍵時，接收端音量一起加減。若換歌時音量忽大忽小，可先關掉這個試試（有些播放器 / 系統功能會自己改手機音量）",
                        12, secondary = true,
                    )
                }
                Spacer(Modifier.width(12.dp))
                GlassToggle(prefs.mirrorVolume)
            }
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { PrefsStore.setLeveler(ctx, !prefs.leveler) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    GText("音量平衡", 17, FontWeight.SemiBold)
                    GText(
                        "把不同歌曲的整體響度拉到接近，並避免加大後爆音。有些歌本來就錄得比較大聲或小聲，開啟後換歌差距會變小（會輕微改變原始動態）",
                        12, secondary = true,
                    )
                }
                Spacer(Modifier.width(12.dp))
                GlassToggle(prefs.leveler)
            }
        }
    }

    GText("HyperIsland", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            val modes = IslandMode.values()
            modes.forEachIndexed { i, mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { PrefsStore.setIsland(ctx, mode) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        GText(mode.label, 17, FontWeight.SemiBold)
                        GText(mode.desc, 12, secondary = true)
                    }
                    if (mode == prefs.island) GIcon(R.drawable.ic_check, p.accent, 22.dp)
                }
                Divider()
            }
            InfoRow("狀態", SystemInfo.hyperIslandStatus(ctx))
            Divider()
            GText(
                "若尚未允許：系統設定 → 通知與控制中心 → HyperCast，開啟「焦點通知 / 超級島」。\n" +
                    "若其他 App 的通知在島上顯示不出來，請選「短暫顯示」或「關閉」。",
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

@Composable
private fun GlassToggle(on: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (on) p.accent else p.track),
    ) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun ActionRow(title: String, desc: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        GText(title, 17, FontWeight.SemiBold)
        if (desc != null) GText(desc, 12, secondary = true)
    }
}

@Composable
private fun ConsentBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GText(title, 15, FontWeight.Bold)
        GText(body, 13, secondary = true)
    }
}

@Composable
private fun DialogButton(text: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (filled) p.accent else p.chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (filled) {
            BasicText(text, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        } else {
            GText(text, 16, FontWeight.SemiBold)
        }
    }
}

/** 開啟日誌前的同意書：說明收集的資料、用途與使用者權益。 */
@Composable
private fun ConsentDialog(onAgree: () -> Unit, onCancel: () -> Unit) {
    val p = LocalPalette.current
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 18.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(if (p.dark) Color(0xF21C1C1E) else Color(0xF7FFFFFF))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GText("開啟日誌記錄前，請先閱讀", 20, FontWeight.Bold)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ConsentBlock(
                    "用途",
                    "只用來分析 AirPlay 連線失敗或沒有聲音的原因，協助開發者除錯。不會用於廣告、行銷或其他用途。",
                )
                ConsentBlock(
                    "將會收集的資料",
                    "1. 手機資料：品牌、型號、Android 與 HyperOS 版本、App 版本、語言、時區。\n" +
                        "2. 網路狀態：目前是 Wi-Fi 或行動網路、是否使用 VPN、Wi-Fi 頻段與訊號強度（不含 Wi-Fi 名稱與密碼）。\n" +
                        "3. AirPlay 裝置資料：裝置名稱、型號、功能旗標、區網廣播內容、連接埠；IP 位址最後一段與 MAC 位址會被遮蔽。\n" +
                        "4. 連線過程：配對與各階段的回應碼、耗時、錯誤訊息與例外堆疊、串流封包統計、近 10 秒音訊最大振幅（只是一個數字，用來判斷是否擷取到靜音）。\n" +
                        "5. 概略位置（選用）：需要你授權「概略位置」，每次連線時讀取一次，座標四捨五入到約 1 公里，只用來為日誌檔命名；不會持續追蹤。轉換成地名時，系統的地理編碼服務可能會處理這組座標。不授權也能使用，檔名會標示為「未知地點」。",
                )
                ConsentBlock(
                    "不會收集",
                    "播放的音訊內容、通知內容、通訊錄、相簿、帳號密碼、配對碼與加密金鑰、Wi-Fi 名稱與密碼。",
                )
                ConsentBlock(
                    "資料如何處理",
                    "日誌只存放在本機 App 私有空間，不會自動上傳。只有你按下「匯出日誌檔」並自行分享給別人，內容才會離開手機。容量上限約 1 MB，超過時會自動捨棄最舊的紀錄。解除安裝 App 時會一併刪除。",
                )
                ConsentBlock(
                    "你的權益",
                    "不同意不影響 App 的任何功能。你可以隨時在「設定 → 診斷」關閉記錄、清除已記錄的內容，也可以在系統設定撤回位置權限。匯出前請自行確認日誌內容再決定是否分享。",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                DialogButton("不同意", false, Modifier.weight(1f), onCancel)
                DialogButton("同意並開始記錄", true, Modifier.weight(1.4f), onAgree)
            }
        }
    }
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
            val names = state.targets.joinToString(" ＋ ") { it.rx.name }.ifBlank { "AirPlay" }
            val title = when (state.phase) {
                Phase.Streaming -> names
                Phase.Connecting -> "正在連線…"
                Phase.Error -> "連線失敗"
                Phase.Idle -> "尚未連線"
            }
            val sub = when (state.phase) {
                Phase.Streaming -> state.message ?: "正在串流系統音訊"
                Phase.Connecting -> names
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
                        val split = state.targets.size == 2 && state.targets.any { it.role != Role.Stereo }
                        Chip(if (split) "左右聲道" else "立體聲")
                        if (state.targets.size >= 2) Chip("${state.targets.size} 台")
                        else Chip(if (state.selected?.ap2 == true) "AirPlay 2" else "44.1 kHz")
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
private fun Devices(state: UiState, onToggle: (DeviceItem) -> Unit) {
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
            val target = state.targets.firstOrNull { t -> d.members.any { it.id == t.rx.id } }
            DeviceRow(d, target, state.phase) {
                if (state.phase != Phase.Connecting) onToggle(d)
            }
        }
    }
}

/**
 * 串流中的聲道卡片：
 *  - 任一台本身就是 HomePod 立體聲組 → 提醒「已配對立體聲喇叭」，左右聲道由組內兩顆負責，不能再拆
 *  - 兩台都是單顆喇叭 → 讓你選：兩台都播完整立體聲 / 左右聲道分開（A左 B右 / A右 B左）
 */
@Composable
private fun ChannelCard(state: UiState, onMode: (ChannelMode) -> Unit) {
    val p = LocalPalette.current
    val t = state.targets
    val pairs = t.filter { it.pair }
    if (t.size < 2 && pairs.isEmpty()) return

    GlassCard(Modifier.fillMaxWidth(), radius = 30.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pairs.isNotEmpty()) {
                GText("已配對立體聲喇叭", 17, FontWeight.Bold)
                GText(
                    pairs.joinToString("、") { "「${it.rx.name}」" } +
                        "是 HomePod 組合：App 會直接連到組內每一顆 HomePod（和 Base 相同，不經過 Apple TV），" +
                        "左右聲道由 HomePod 自己分配，所以不能再拆成左 / 右。" +
                        if (t.size >= 2) "另一台會同時播放完整立體聲。" else "",
                    13, secondary = true,
                )
            } else {
                GText("聲道設定", 17, FontWeight.Bold)
                GText(
                    "兩台都是單顆喇叭：可以讓它們各播一邊，自己組成立體聲。左右位置請依實際擺放選擇。",
                    13, secondary = true,
                )
                val mode = AirPlayStore.channelMode(state)
                val a = t[0].rx.name
                val b = t[1].rx.name
                ModeRow("兩台都播立體聲", "適合多房間同播", mode == ChannelMode.Both) { onMode(ChannelMode.Both) }
                ModeRow("$a 左 ／ $b 右", "左右聲道分開", mode == ChannelMode.LeftRight) { onMode(ChannelMode.LeftRight) }
                ModeRow("$a 右 ／ $b 左", "左右對調", mode == ChannelMode.RightLeft) { onMode(ChannelMode.RightLeft) }
                GText(
                    "兩台的延遲可能有些微差異；若聽起來左右不同步，請到設定微調音訊延遲。",
                    11, secondary = true, alpha = 0.8f,
                )
            }
        }
    }
}

@Composable
private fun ModeRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) p.chip else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GText(title, 15, FontWeight.SemiBold)
            GText(desc, 12, secondary = true)
        }
        if (selected) GIcon(R.drawable.ic_check, p.accent, 20.dp)
    }
}

/** 輸入裝置螢幕上的配對碼，或 AirPlay 密碼。 */
@Composable
private fun PinDialog(req: com.hypercast.airplay.PinRequest) {
    val p = LocalPalette.current
    var text by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = { PinBroker.cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(if (p.dark) Color(0xF21C1C1E) else Color(0xF7FFFFFF))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GText(if (req.password) "輸入 AirPlay 密碼" else "輸入配對碼", 22, FontWeight.Bold)
            GText(
                if (req.password) "「${req.deviceName}」設定了 AirPlay 密碼。"
                else "請輸入「${req.deviceName}」螢幕上顯示的配對碼。若沒有顯示，請輸入裝置說明書或 App 中的配對碼（常見為 3939）。",
                14, secondary = true,
            )
            if (req.retry) GText("上一次輸入不正確，請再試一次。", 13, FontWeight.SemiBold)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.chip)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = if (req.password) it else it.filter { c -> c.isDigit() }.take(4) },
                    singleLine = true,
                    textStyle = TextStyle(color = p.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(p.accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (req.password) KeyboardType.Password else KeyboardType.NumberPassword
                    ),
                    visualTransformation = if (req.password) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                DialogButton("取消", false, Modifier.weight(1f)) { PinBroker.cancel() }
                DialogButton("連線", true, Modifier.weight(1f)) { if (text.isNotEmpty()) PinBroker.submit(text) }
            }
        }
    }
}

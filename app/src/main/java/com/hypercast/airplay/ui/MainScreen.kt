// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import com.hypercast.airplay.AirPlayStore
import com.hypercast.airplay.BuildConfig
import com.hypercast.airplay.ChannelMode
import com.hypercast.airplay.DelayStore
import com.hypercast.airplay.DeviceItem
import com.hypercast.airplay.DiagCategory
import com.hypercast.airplay.DiagLog
import com.hypercast.airplay.Haptics
import com.hypercast.airplay.IslandMode
import com.hypercast.airplay.Phase
import com.hypercast.airplay.PinBroker
import com.hypercast.airplay.PrefsStore
import com.hypercast.airplay.R
import com.hypercast.airplay.Role
import com.hypercast.airplay.SystemInfo
import com.hypercast.airplay.UiState
import com.hypercast.airplay.appleDeviceIcon
import com.hypercast.airplay.buildItems
import com.hypercast.airplay.isFocus
import com.hypercast.airplay.isHomePod
import com.hypercast.airplay.setHaptics
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
    permVersion: Int,
    onDiagCategoryConsent: (DiagCategory, Boolean) -> Unit,
    onDiagAgreeAll: (Boolean) -> Unit,
    onDiagRequestPermission: (String) -> Unit,
    onDiagEnable: () -> Unit,
    onDiagDisable: () -> Unit,
    onDiagRefresh: () -> Unit,
    onDiagExport: () -> Unit,
    onDiagClear: () -> Unit,
    onFeedback: () -> Unit,
    onQuit: () -> Unit,
) {
    val palette = paletteFor(theme)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

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
    BackHandler(enabled = showDiagnostics || showSettings) {
        if (showDiagnostics) showDiagnostics = false else showSettings = false
    }
    var firstPhase by remember { mutableStateOf(true) }
    LaunchedEffect(state.phase) {
        if (firstPhase) { firstPhase = false; return@LaunchedEffect }
        when (state.phase) {
            Phase.Streaming -> Haptics.success()
            Phase.Error -> Haptics.fail()
            else -> {}
        }
    }

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
                when {
                    showDiagnostics -> {
                        DiagnosticsPage(
                            diag = diag,
                            permVersion = permVersion,
                            onCategoryConsent = onDiagCategoryConsent,
                            onAgreeAll = onDiagAgreeAll,
                            onRequestPermission = onDiagRequestPermission,
                            onEnable = onDiagEnable,
                            onDisable = onDiagDisable,
                            onRefresh = onDiagRefresh,
                            onExport = onDiagExport,
                            onClear = onDiagClear,
                            onFeedback = onFeedback,
                            onBack = { showDiagnostics = false },
                        )
                    }
                    showSettings -> {
                        SettingsPage(
                            theme = theme,
                            onTheme = onTheme,
                            delayMs = delayMs,
                            onDelay = onDelay,
                            diag = diag,
                            onOpenDiagnostics = { showDiagnostics = true },
                            onDiagDisable = onDiagDisable,
                            onDiagExport = onDiagExport,
                            onDiagClear = onDiagClear,
                            onFeedback = onFeedback,
                            onQuit = onQuit,
                            onBack = { showSettings = false },
                        )
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                GText("HyperCast", 38, FontWeight.Bold)
                                GText("把 Android 的聲音串流到 AirPlay 裝置", 14, secondary = true)
                            }
                            CircleButton(R.drawable.ic_settings) { showSettings = true }
                        }
                        StatusPill(state)
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
                }
                Spacer(Modifier.height(6.dp))
                GText(
                    "HyperCast v${BuildConfig.VERSION_NAME} · Designed & developed by ${BuildConfig.DEVELOPER}",
                    11, alpha = 0.55f, secondary = true,
                    modifier = Modifier.fillMaxWidth(), align = TextAlign.Center,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 診斷日誌與權限同意頁（HyperOS 4 風格）

@Composable
private fun DiagnosticsPage(
    diag: DiagLog.UiState,
    permVersion: Int,
    onCategoryConsent: (DiagCategory, Boolean) -> Unit,
    onAgreeAll: (Boolean) -> Unit,
    onRequestPermission: (String) -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onFeedback: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val totalCount = DiagCategory.entries.size
    val agreedCount = diag.consented.size
    val allAgreed = diag.allConsented
    // 讀取 permVersion 確保系統權限視窗返回後立即更新每項授權標籤
    val ignoredPermTick = permVersion

    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleButton(R.drawable.ic_back, onClick = onBack)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            GText("診斷日誌與權限", 28, FontWeight.Bold)
            GText("v${BuildConfig.VERSION_NAME} · 同意全部項目後才開始搜集", 12, secondary = true)
        }
    }

    // 狀態與總控卡片
    GlassCard(Modifier.fillMaxWidth(), radius = 28.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GText(
                        when {
                            diag.enabled -> "正在搜集診斷資料"
                            allAgreed -> "已同意全部項目，可開始搜集"
                            else -> "尚未開始搜集資料"
                        },
                        18, FontWeight.Bold,
                    )
                    GText(
                        if (diag.enabled) "已記錄 ${diag.entries} 筆 · ${diag.sizeKb} KB · 地點：${diag.place.ifBlank { "未知地點" }}"
                        else "已同意 $agreedCount / $totalCount 項。須逐項開啟同意（或點「全部同意」）才可啟用搜集。",
                        12, secondary = true,
                    )
                }
                Chip(if (diag.enabled) "記錄中" else "$agreedCount/$totalCount")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                DialogButton(
                    text = if (allAgreed) "全部取消" else "全部同意",
                    filled = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        Haptics.toggle(!allAgreed)
                        onAgreeAll(!allAgreed)
                    },
                )
                if (diag.enabled) {
                    DialogButton(
                        text = "停止搜集",
                        filled = true,
                        modifier = Modifier.weight(1.35f),
                        onClick = {
                            Haptics.toggle(false)
                            onDisable()
                        },
                    )
                } else {
                    DialogButton(
                        text = if (allAgreed) "開始搜集資料" else "請先同意全部項目",
                        filled = true,
                        enabled = allAgreed,
                        modifier = Modifier.weight(1.35f),
                        onClick = {
                            if (allAgreed) onEnable()
                        },
                    )
                }
            }
        }
    }

    // 搜集資料清單與 HyperOS 4 風格圖示 + 權限狀態
    GText(
        "搜集項目與所需權限（共 $totalCount 項）",
        13, FontWeight.SemiBold, secondary = true,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp),
    )

    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            DiagCategory.entries.forEachIndexed { idx, cat ->
                val agreed = cat in diag.consented
                val needsPrompt = cat.requiresRuntimePrompt()
                val granted = remember(cat, ignoredPermTick) { cat.isPermissionGranted(ctx) }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .hapticClickable(click = false) {
                            Haptics.toggle(!agreed)
                            onCategoryConsent(cat, !agreed)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    HyperOsDiagIcon(cat = cat, size = 46.dp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        GText(cat.title, 16, FontWeight.SemiBold)
                        GText(cat.summary, 12, secondary = true)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            val badgeColor = when {
                                needsPrompt && !granted -> p.accent
                                needsPrompt && granted -> Color(0xFF16A34A)
                                else -> p.text2
                            }
                            val badgeBg = when {
                                needsPrompt && !granted -> p.accent.copy(alpha = 0.16f)
                                needsPrompt && granted -> Color(0xFF16A34A).copy(alpha = 0.16f)
                                else -> p.chip
                            }
                            val permStatus = when {
                                needsPrompt && !granted -> "未授權 · 點此授權"
                                needsPrompt && granted -> "已授權"
                                else -> "自動 / 無需額外授權"
                            }
                            val perm = cat.runtimePermission
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(badgeBg)
                                    .border(0.6.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(50))
                                    .then(
                                        if (needsPrompt && !granted && perm != null) {
                                            Modifier.hapticClickable { onRequestPermission(perm) }
                                        } else Modifier
                                    )
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                            ) {
                                BasicText(
                                    permStatus,
                                    style = TextStyle(color = badgeColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                        GText("要求權限：${cat.permissionLabel}", 11, secondary = true, alpha = 0.85f)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.padding(top = 8.dp)) {
                        GlassToggle(agreed)
                    }
                }
                if (idx < DiagCategory.entries.lastIndex) Divider()
            }
        }
    }

    // 匯出與回報操作卡片
    GText("日誌操作與回報", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            if (diag.enabled) {
                ActionRow("立即重新採樣系統快照", "把目前的網路、Wi-Fi、音訊、通知、電源與裝置狀態再記錄一次", onRefresh)
                Divider()
            }
            ActionRow(
                "傳送意見與日誌給開發者",
                if (diag.entries > 0) "開啟郵件 App，自動附上本次診斷日誌（收件人：${com.hypercast.airplay.FeedbackMail.EMAIL}）"
                else "尚無日誌資料，請先同意所有項目並開始搜集",
                onFeedback,
            )
            Divider()
            ActionRow(
                "匯出日誌檔（.log）",
                if (diag.entries > 0) "檔名含版本、時間與概略地點：${diag.place.ifBlank { "未知地點" }}"
                else "尚無可匯出的紀錄",
                onExport,
            )
            if (diag.entries > 0) {
                Divider()
                ActionRow("清除本機日誌", "立即刪除手機上已記錄的所有診斷內容", onClear)
            }
        }
    }

    // 隱私與安全說明
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GText("隱私與資料保護", 15, FontWeight.Bold)
            GText(
                "• 日誌僅儲存在本機 App 私有空間，絕不會自動上傳；只有你自行按「匯出」或「傳送」才會離開手機。\n" +
                    "• 寫入前一律自動遮蔽：IPv4 末段、IPv6 位址、MAC 位址、UUID。\n" +
                    "• 絕不搜集：播放中的音訊內容、通知內文、通訊錄、相簿、配對碼（PIN）、加密金鑰、Wi-Fi 名稱（SSID）與密碼。\n" +
                    "• 取消任一項同意會立即停止搜集；容量上限約 1.5 MB，超過自動捨棄最舊紀錄。",
                12, secondary = true,
            )
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
    onOpenDiagnostics: () -> Unit,
    onDiagDisable: () -> Unit,
    onDiagExport: () -> Unit,
    onDiagClear: () -> Unit,
    onFeedback: () -> Unit,
    onQuit: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
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
            Box(Modifier.clickableNoRipple { if (delayMs - DelayStore.STEP < DelayStore.MIN) Haptics.edge(); onDelay(delayMs - DelayStore.STEP) }.padding(12.dp)) { GText("－", 24, FontWeight.Bold) }
            Box(Modifier.clickableNoRipple { if (delayMs != 0) Haptics.detent(); onDelay(0) }.padding(8.dp)) { GText("0", 15, secondary = true) }
            Box(Modifier.clickableNoRipple { if (delayMs + DelayStore.STEP > DelayStore.MAX) Haptics.edge(); onDelay(delayMs + DelayStore.STEP) }.padding(12.dp)) { GText("＋", 24, FontWeight.Bold) }
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

    GText("診斷與回報日誌", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .hapticClickable { onOpenDiagnostics() }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    GText("診斷日誌與權限管理", 17, FontWeight.SemiBold)
                    GText(
                        if (diag.enabled) "記錄中 · ${diag.entries} 筆 · ${diag.sizeKb} KB · 點此管理項目與權限"
                        else "已同意 ${diag.consented.size}/${DiagCategory.entries.size} 項 · 須逐項或全部同意後才開始搜集",
                        12, secondary = true,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Chip(if (diag.enabled) "記錄中" else "前往設定")
            }
            if (diag.enabled) {
                Divider()
                ActionRow("暫停日誌記錄", "停止記錄新的事件（保留已記錄資料）", onDiagDisable)
            }
            if (diag.enabled || diag.entries > 0) {
                Divider()
                ActionRow(
                    "匯出日誌檔（.log）",
                    "檔名含版本、時間日期與地點：" + if (diag.place.isBlank()) "未知地點" else diag.place,
                    onDiagExport,
                )
                Divider()
                ActionRow("清除日誌", "刪除本機已記錄的所有內容", onDiagClear)
            }
            Divider()
            GText(
                "日誌只存在這支手機，不會自動上傳；必須在診斷頁面完成所有項目同意後才會開始搜集。",
                12, secondary = true, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }

    GText("音訊", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .hapticClickable(click = false) { Haptics.toggle(!prefs.mirrorVolume); PrefsStore.setMirrorVolume(ctx, !prefs.mirrorVolume) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    GText("同步手機音量鍵", 17, FontWeight.SemiBold)
                    GText(
                        "按手機音量鍵時，接收端音量一起加減。關閉後 HyperCast 完全不監聽手機音量（不註冊任何接收器）。若換歌時音量忽大忽小，或想排查通知異常，可先關掉試試",
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
                    .hapticClickable(click = false) { Haptics.toggle(!prefs.leveler); PrefsStore.setLeveler(ctx, !prefs.leveler) }
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
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .hapticClickable(click = false) { Haptics.toggle(!prefs.haptics); PrefsStore.setHaptics(ctx, !prefs.haptics) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    GText("觸感", 17, FontWeight.SemiBold)
                    GText(
                        "每次點觸都有對應的震動：按下輕、放開實，滑桿逐格輕敲，到最小 / 最大 / 50% 或延遲上下限時有明顯的臨界回饋，連線成功像呼吸般上揚。線性馬達的手機效果最好",
                        12, secondary = true,
                    )
                }
                Spacer(Modifier.width(12.dp))
                GlassToggle(prefs.haptics)
            }
        }
    }

    GText("HyperIsland", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            val modes = IslandMode.values()
            modes.forEachIndexed { _, mode ->
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
            if (prefs.island.isFocus) {
                IslandToggle(
                    "閒置時顯示「未連接」",
                    "沒在串流、App 開著時，島彈出約 6 秒顯示「未連接」，通知 15 秒後自動消失。只在 HyperOS 3 以上有效",
                    prefs.idleIsland,
                ) { PrefsStore.setIdleIsland(ctx, it) }
                Divider()
                GText(
                    "焦點通知進階（用來排查「其他 App 的一般通知被隱藏」，一次只改一項測試最容易找出原因）",
                    12, FontWeight.SemiBold, secondary = true,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp),
                )
                IslandToggle(
                    "首次出現時自動展開（islandFirstFloat）",
                    "官方預設是開：島一出現就展開成大卡片，可能蓋住 / 擠掉其他 App 的橫幅通知。這裡預設關閉，只出摘要態的小島",
                    prefs.focusFirstFloat,
                ) { PrefsStore.setFocusFirstFloat(ctx, it) }
                Divider()
                IslandToggle(
                    "獨立焦點通知（可滑掉）",
                    "把島的資料放在另一則通知，前景服務那一則維持普通玻璃通知。島或那則通知有問題時，滑掉它不會中斷串流",
                    prefs.focusSeparate,
                ) { PrefsStore.setFocusSeparate(ctx, it) }
                Divider()
                IslandToggle(
                    "低重要性頻道（不彈橫幅）",
                    "焦點通知改走低重要性頻道，不彈橫幅、不響。若開了之後島不出現，請關掉",
                    prefs.focusLowChannel,
                ) { PrefsStore.setFocusLowChannel(ctx, it) }
                Divider()
                IslandToggle(
                    "持續性焦點通知（updatable）",
                    "官方欄位「是否為持續性通知」。關閉後焦點通知變成一次性，可能不再跟著狀態更新",
                    prefs.focusUpdatable,
                ) { PrefsStore.setFocusUpdatable(ctx, it) }
                Divider()
                if (prefs.island == IslandMode.FocusBrief) {
                    val steps = listOf(3, 6, 10, 15, 30)
                    val idx = steps.indexOf(prefs.focusSeconds).coerceAtLeast(0)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            GText("島停留時間", 17, FontWeight.SemiBold)
                            GText("彈出這麼久之後，明確送出 dismissIsland 把摘要態的島收起來", 12, secondary = true)
                        }
                        Box(Modifier.clickableNoRipple { PrefsStore.setFocusSeconds(ctx, steps[(idx - 1).coerceAtLeast(0)]) }.padding(12.dp)) { GText("－", 24, FontWeight.Bold) }
                        GText("${prefs.focusSeconds} 秒", 16, FontWeight.SemiBold)
                        Box(Modifier.clickableNoRipple { PrefsStore.setFocusSeconds(ctx, steps[(idx + 1).coerceAtMost(steps.lastIndex)]) }.padding(12.dp)) { GText("＋", 24, FontWeight.Bold) }
                    }
                    Divider()
                }
            }
            if (prefs.island == IslandMode.Media) {
                IslandToggle(
                    "播放・暫停 = 靜音・取消靜音",
                    "預設關閉：系統或其他 App 自己送來的「暫停」不會讓聲音停掉。打開後，島與通知上的暫停鍵會靜音接收端",
                    prefs.mediaKeys,
                ) { PrefsStore.setMediaKeys(ctx, it) }
                Divider()
            }
            InfoRow("狀態", SystemInfo.hyperIslandStatus(ctx))
            Divider()
            GText(
                "排查順序：先選「一般通知」，確認其他 App 的訊息通知正常 → 再選「焦點通知・純顯示」，" +
                    "若又被隱藏，到上面「焦點通知進階」一次改一項（先試「首次出現時自動展開」維持關閉、再試「獨立焦點通知」），" +
                    "並開診斷日誌：每次附上島參數都會記一筆 ISLAND，匯出後可以對照時間點。\n" +
                    "若島上沒有出現，請確認 系統設定 → 通知與控制中心 → HyperCast 已允許通知與焦點通知。",
                12, secondary = true, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }

    // ---------------- 意見回饋
    var fbStep by rememberSaveable { mutableStateOf(0) }       // 1 需先開啟記錄 · 2 還沒有內容 · 3 寄信前提醒
    GText("意見回饋", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                GText("聯絡開發者", 17, FontWeight.SemiBold)
                GText(com.hypercast.airplay.FeedbackMail.EMAIL, 14, secondary = true)
            }
            Divider()
            ActionRow(
                "傳送意見與日誌給開發者",
                "開啟郵件 App，自動附上日誌檔；信件主旨會自動帶入版本與日誌時間",
            ) { fbStep = when { !diag.enabled -> 1; diag.entries == 0 -> 2; else -> 3 } }
        }
    }
    when (fbStep) {
        1 -> NoticeDialog(
            "尚未開啟日誌記錄",
            "要附上診斷日誌，請先前往「診斷日誌與權限」頁面，檢視各項搜集資料與所需權限並完成同意，再重現一次問題。",
            "前往診斷頁面", "取消",
            onConfirm = { fbStep = 0; onOpenDiagnostics() },
            onCancel = { fbStep = 0 },
        )
        2 -> NoticeDialog(
            "還沒有記錄到內容",
            "請先回到首頁重現一次問題（例如連線一次），再回來傳送。",
            "知道了", null, onConfirm = { fbStep = 0 }, onCancel = { fbStep = 0 },
        )
        3 -> NoticeDialog(
            "寄信前請注意",
            "這封信會附上你已同意搜集的診斷日誌：IP 末段、IPv6、MAC、UUID 已遮蔽，不含配對碼、金鑰、音訊內容與 Wi-Fi 名稱。\n\n信件主旨會自動帶入版本與日誌時間，請不要修改，方便對照。",
            "同意並寄信", "取消",
            onConfirm = { fbStep = 0; onFeedback() },
            onCancel = { fbStep = 0 },
            neutral = "檢視搜集項目與權限",
            onNeutral = { fbStep = 0; onOpenDiagnostics() },
        )
    }

    // ---------------- 應用程式
    GText("應用程式", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        ActionRow("完全關閉程式", "中斷連接、結束所有背景服務並關閉 HyperCast", onQuit)
    }

    GText(
        "使用 Claude 以及人工修改及審查技術製作",
        12, secondary = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), align = TextAlign.Center,
    )
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

/** HyperIsland 區塊裡的開關列（點下去有觸感，與其他設定列一致）。 */
@Composable
private fun IslandToggle(title: String, desc: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .hapticClickable(click = false) { Haptics.toggle(!on); onChange(!on) }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GText(title, 17, FontWeight.SemiBold)
            GText(desc, 12, secondary = true)
        }
        Spacer(Modifier.width(12.dp))
        GlassToggle(on)
    }
}

@Composable
private fun GlassToggle(on: Boolean) {
    val p = LocalPalette.current
    val x by animateDpAsState(if (on) 22.dp else 0.dp, spring(dampingRatio = 0.5f, stiffness = 420f), label = "tg")
    val grow by animateFloatAsState(if (on) 1f else 0f, tween(260), label = "tg2")
    Box(
        Modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                Brush.horizontalGradient(listOf(p.track, p.accent.copy(alpha = 0.35f + 0.65f * grow)))
                    .let { if (on) it else Brush.horizontalGradient(listOf(p.track, p.track)) }
            ),
    ) {
        Box(
            Modifier
                .offset(x = x)
                .padding(3.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE6E9F2))))
        )
    }
}

@Composable
private fun ActionRow(title: String, desc: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .hapticClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        GText(title, 17, FontWeight.SemiBold)
        if (desc != null) GText(desc, 12, secondary = true)
    }
}

@Composable
private fun DialogButton(
    text: String,
    filled: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(24.dp))
            .background(if (filled) p.accent else p.chip)
            .then(if (enabled) Modifier.hapticClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (filled) {
            BasicText(text, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        } else {
            GText(text, 15, FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.hapticClickable(onClick = onClick)

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

/** 仿 Apple Music「正在播放」：大封面（顯示蘋果官方裝置圖示）、標題、拉桿與圓形控制鍵。 */
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
            val artIcon = if (state.targets.isNotEmpty()) {
                appleDeviceIcon(
                    state.targets.map { it.rx },
                    isPair = state.targets.any { it.pair } || state.targets.count { it.rx.isHomePod } >= 2,
                )
            } else {
                R.drawable.ic_music
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
                ) { GIcon(artIcon, if (streaming) Color.White else p.text2, 40.dp) }
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
                GlassCard(Modifier.height(58.dp), radius = 29.dp, onClick = onDisconnect) {
                    Row(
                        Modifier.height(58.dp).padding(horizontal = 26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(p.accent))
                        GText("中斷連接", 16, FontWeight.SemiBold)
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
 *  - 任一台本身就是 HomePod 立體聲組 → 顯示「HomePod 立體聲組」，左右聲道由組內兩顆負責，不能再拆
 *  - 兩台都是單顆喇叭 → 讓你選：兩台都播完整立體聲 / 左右聲道分開（A左 B右 / A右 B左）
 */
@Composable
private fun ChannelCard(state: UiState, onMode: (ChannelMode) -> Unit) {
    val t = state.targets
    val pairs = t.filter { it.pair }
    if (t.size < 2 && pairs.isEmpty()) return

    GlassCard(Modifier.fillMaxWidth(), radius = 30.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pairs.isNotEmpty()) {
                GText("HomePod 立體聲組", 17, FontWeight.Bold)
                GText(
                    pairs.joinToString("、") { "「${it.rx.name}」" } +
                        "屬於 HomePod 立體聲組：App 會直接連到組內每一顆 HomePod（不經過 Apple TV），" +
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
            .hapticClickable(onClick = onClick)
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

/** 連線狀態膠囊：呼吸的狀態點 + 文字（HyperOS 4「生命感」的小呼吸）。 */
@Composable
private fun StatusPill(state: UiState) {
    val (label, dot) = when (state.phase) {
        Phase.Idle -> "未連接" to Color(0xFF9AA0AC)
        Phase.Connecting -> "連線中…" to Color(0xFFFFB84D)
        Phase.Streaming -> "已連線 · " + state.targets.joinToString(" ＋ ") { it.rx.name }.ifBlank { "AirPlay" } to Color(0xFF34D399)
        Phase.Error -> "連線失敗" to Color(0xFFFF5A5F)
    }
    val tr = rememberInfiniteTransition(label = "pill")
    val period = if (state.phase == Phase.Connecting) 700 else 2200
    val pulse by tr.animateFloat(
        0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    GlassCard(Modifier.wrapContentWidth(), radius = 22.dp) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size((8 + 10 * pulse).dp)
                        .clip(CircleShape)
                        .background(dot.copy(alpha = 0.35f * (1f - pulse)))
                )
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            }
            Spacer(Modifier.width(8.dp))
            GText(label, 14, FontWeight.SemiBold)
        }
    }
}

/** 通用提示視窗（寄信前提醒等）。 */
@Composable
private fun NoticeDialog(
    title: String,
    body: String,
    confirm: String,
    cancel: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    neutral: String? = null,
    onNeutral: () -> Unit = {},
) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) { Haptics.tick() }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(30.dp))
                .background(if (p.dark) Color(0xF21C1C1E) else Color(0xF7FFFFFF))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GText(title, 20, FontWeight.Bold)
            GText(body, 14, secondary = true)
            if (neutral != null) DialogButton(neutral, false, Modifier.fillMaxWidth(), onClick = onNeutral)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (cancel != null) DialogButton(cancel, false, Modifier.weight(1f), onClick = onCancel)
                DialogButton(confirm, true, Modifier.weight(1.3f), onClick = onConfirm)
            }
        }
    }
}

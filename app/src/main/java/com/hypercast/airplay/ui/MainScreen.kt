// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hypercast.airplay.DiagItem
import com.hypercast.airplay.DiagPerm
import com.hypercast.airplay.HyperIsland
import com.hypercast.airplay.PermKind
import com.hypercast.airplay.ScreenShareGuard
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.hypercast.airplay.Haptics
import com.hypercast.airplay.setHaptics
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
    onDiagEnable: (Set<DiagItem>) -> Unit,
    onDiagDisable: () -> Unit,
    onDiagExport: () -> Unit,
    onDiagClear: () -> Unit,
    onFeedback: () -> Unit,
    onQuit: () -> Unit,
) {
    val palette = paletteFor(theme)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var consentMode by rememberSaveable { mutableStateOf(0) }   // 0 無　1 開始記錄前的逐項同意　2 查看已同意項目
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
    BackHandler(enabled = showSettings || consentMode != 0) {
        if (consentMode != 0) consentMode = 0 else showSettings = false
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
                if (consentMode != 0) {
                    DiagConsentPage(
                        viewOnly = consentMode == 2,
                        onBack = { consentMode = 0 },
                        onAgree = { items -> consentMode = 0; onDiagEnable(items) },
                    )
                } else if (showSettings) {
                    SettingsPage(theme, onTheme, delayMs, onDelay, diag, { consentMode = it }, onDiagDisable, onDiagExport, onDiagClear, onFeedback, onQuit) { showSettings = false }
                } else {
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
    onOpenConsent: (Int) -> Unit,   // 1 = 開始前的同意頁　2 = 只查看
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

    GText("診斷", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .hapticClickable(click = false) { Haptics.toggle(!diag.enabled); if (diag.enabled) onDiagDisable() else onOpenConsent(1) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    GText("日誌記錄", 17, FontWeight.SemiBold)
                    GText(
                        if (diag.enabled) "記錄中 · ${diag.entries} 筆 · ${diag.sizeKb} KB"
                        else "關閉。開啟前會先列出每一項要收集的資料與所需權限，全部同意後才開始",
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

    // ---------------- 超級島 / 通知
    GText("超級島與通知", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            InfoRow("狀態", SystemInfo.hyperIslandStatus(ctx))
        }
    }
    ScreenShareCard()

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
                "開啟郵件 App，自動附上日誌檔；信件主旨會自動使用日誌的時間",
            ) { fbStep = when { !diag.enabled -> 1; diag.entries == 0 -> 2; else -> 3 } }
        }
    }
    when (fbStep) {
        1 -> NoticeDialog(
            "尚未開啟日誌記錄",
            "要附上日誌，需要先開啟記錄，並重現一次問題。\n\n開啟前請先閱讀並同意《日誌同意書》。開啟後約 5 分鐘，我會通知你回來儲存或傳送日誌。",
            "閱讀同意書", "取消", onConfirm = { fbStep = 0; onOpenConsent(1) }, onCancel = { fbStep = 0 },
        )
        2 -> NoticeDialog(
            "還沒有記錄到內容",
            "請先回到首頁重現一次問題（例如連線一次），再回來傳送。",
            "知道了", null, onConfirm = { fbStep = 0 }, onCancel = { fbStep = 0 },
        )
        3 -> NoticeDialog(
            "寄信前請注意",
            "寄出前請確認你已閱讀並同意《日誌同意書》。這封信會附上日誌檔：IP 末段、MAC、UUID 已遮蔽，不含配對碼、金鑰、音訊內容與 Wi-Fi 名稱。\n\n信件主旨會自動使用日誌的時間，請不要修改，方便對照。",
            "同意並寄信", "取消", onConfirm = { fbStep = 0; onFeedback() }, onCancel = { fbStep = 0 },
            neutral = "查看同意書", onNeutral = { fbStep = 0; onOpenConsent(2) },
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
            .background(Brush.horizontalGradient(listOf(p.track, p.accent.copy(alpha = 0.35f + 0.65f * grow)))
                .let { if (on) it else Brush.horizontalGradient(listOf(p.track, p.track)) }),
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
private fun ConsentBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GText(title, 15, FontWeight.Bold)
        GText(body, 13, secondary = true)
    }
}

@Composable
private fun DialogButton(text: String, filled: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled && filled) p.accent else p.chip)
            .then(if (enabled) Modifier.hapticClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (filled && enabled) {
            BasicText(text, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        } else {
            GText(text, 16, FontWeight.SemiBold, alpha = if (enabled) 1f else 0.55f)
        }
    }
}

// ---------------------------------------------------------------- 診斷資料與權限（逐項同意）

/** 權限目前的狀態文字。 */
private fun permStatus(ctx: Context, perm: DiagPerm): String = when {
    perm.kind == PermKind.None -> "不需要"
    perm.manifest == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33 -> "此版本不需要"
    perm.kind == PermKind.Normal -> "已授予"
    perm.manifest != null ->
        if (ContextCompat.checkSelfPermission(ctx, perm.manifest) == PackageManager.PERMISSION_GRANTED) "已授權" else "尚未授權"
    perm.name.startsWith("焦點通知") -> when {
        HyperIsland.protocol(ctx) <= 0 -> "此系統不適用"
        HyperIsland.canShow(ctx) -> "已允許"
        else -> "尚未允許"
    }
    else -> "每次由系統詢問"
}

@Composable
private fun PermRow(ctx: Context, perm: DiagPerm) {
    val p = LocalPalette.current
    val status = permStatus(ctx, perm)
    val col = when {
        status.startsWith("已") || status.startsWith("不需要") || status == "此版本不需要" -> Color(0xFF2BA655)
        status.startsWith("尚未") -> Color(0xFFE59210)
        else -> p.text2
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            GText(perm.name, 13, FontWeight.SemiBold)
            GText(perm.kind.label + if (perm.note.isNotBlank()) "｜${perm.note}" else "", 11, secondary = true)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(50)).background(p.chip).padding(horizontal = 9.dp, vertical = 3.dp)) {
            BasicText(status, style = TextStyle(color = col, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

/**
 * 診斷資料與權限頁（取代舊的同意書對話框）：
 * 每一項資料一張卡片（HyperOS 4 風格圖示、收集內容、需要的權限與目前狀態、同意開關）。
 * **每一項都同意（逐項開啟或按「全部同意」）之後，才能按「同意並開始記錄」**；同意之前不會收集任何資料。
 * @param viewOnly true＝只查看目前已同意的項目（不能改）。
 */
@Composable
private fun DiagConsentPage(viewOnly: Boolean, onBack: () -> Unit, onAgree: (Set<DiagItem>) -> Unit) {
    val ctx = LocalContext.current
    val items = DiagItem.values()
    val full = (1 shl items.size) - 1
    var mask by rememberSaveable { mutableStateOf(if (viewOnly) full else 0) }
    val count = Integer.bitCount(mask)
    val allOn = mask == full

    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleButton(R.drawable.ic_back, onClick = onBack)
        Spacer(Modifier.width(14.dp))
        GText("診斷資料與權限", 28, FontWeight.Bold)
    }

    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GText(if (viewOnly) "目前正在記錄的資料" else "先看清楚，再決定", 17, FontWeight.SemiBold)
            GText(
                if (viewOnly) "以下是你已同意的每一項資料與所需權限。要撤回，請到「設定 → 診斷」關閉日誌記錄，關閉時會一併撤回全部同意。"
                else "HyperCast 只在你同意之後才開始記錄，同意之前一筆資料都不會收集。下面每一項都要同意：可以逐項開啟，也可以按「全部同意」。少同意一項就不會開始。日誌只存放在這支手機，不會自動上傳。",
                13, secondary = true,
            )
        }
    }

    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (viewOnly) Modifier
                    else Modifier.hapticClickable(click = false) { Haptics.toggle(!allOn); mask = if (allOn) 0 else full }
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                GText("全部同意", 17, FontWeight.SemiBold)
                GText("已同意 $count / ${items.size} 項", 12, secondary = true)
            }
            Spacer(Modifier.width(12.dp))
            GlassToggle(allOn)
        }
    }

    items.forEachIndexed { i, item ->
        val on = ((mask shr i) and 1) == 1
        GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (viewOnly) Modifier
                            else Modifier.hapticClickable(click = false) { Haptics.toggle(!on); mask = mask xor (1 shl i) }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HyperTile(item.icon, item.top, item.bottom, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        GText(item.title, 17, FontWeight.SemiBold)
                        GText(item.summary, 12, secondary = true)
                    }
                    Spacer(Modifier.width(10.dp))
                    GlassToggle(on)
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    GText("會收集", 12, FontWeight.SemiBold, secondary = true)
                    item.fields.forEach { GText("・$it", 13) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GText("需要的權限", 12, FontWeight.SemiBold, secondary = true)
                    item.perms.forEach { PermRow(ctx, it) }
                }
            }
        }
    }

    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ConsentBlock(
                "用途",
                "只用來分析 AirPlay 連線失敗、沒有聲音、通知被擋住等問題，協助開發者除錯。不會用於廣告、行銷或其他用途。",
            )
            ConsentBlock(
                "一律不會收集",
                "播放的音訊內容、任何通知的標題與內容、其他 App 的資料、通訊錄、相簿、帳號密碼、配對碼與加密金鑰、Wi-Fi 名稱 / 密碼 / BSSID。",
            )
            ConsentBlock(
                "資料如何處理",
                "日誌只存放在本機 App 私有空間，不會自動上傳。只有你按下「匯出日誌檔」或「傳送意見」並自行分享給別人，內容才會離開手機。IP 末段、MAC、UUID 寫入前就會遮蔽。容量上限約 1 MB，超過時自動捨棄最舊的紀錄。解除安裝 App 時一併刪除。",
            )
            ConsentBlock(
                "你的權益",
                "不同意不影響 App 的任何功能。你可以隨時在「設定 → 診斷」關閉記錄（同時撤回全部同意）、清除已記錄的內容，也可以在系統設定撤回權限。匯出前請自行確認內容再決定是否分享。",
            )
        }
    }

    if (viewOnly) {
        DialogButton("關閉", true, Modifier.fillMaxWidth(), onClick = onBack)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            DialogButton("不同意", false, Modifier.weight(1f), onClick = onBack)
            DialogButton(
                if (allOn) "同意並開始記錄" else "還有 ${items.size - count} 項未同意",
                allOn, Modifier.weight(1.6f),
                onClick = { onAgree(items.toSet()) },
                enabled = allOn,
            )
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
    val p = LocalPalette.current
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

// ---------------------------------------------------------------- 通知被隱藏？（螢幕分享保護）

private fun openSystemSettings(ctx: Context, action: String, fallback: String) {
    try {
        ctx.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        android.widget.Toast.makeText(ctx, fallback, android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * 串流時其他 App 的通知被隱藏、滑不掉：原因是 Android 15 起的「螢幕分享保護」——
 * HyperCast 擷取系統聲音要啟動螢幕擷取授權（MediaProjection），系統就會把它當成螢幕分享，隱藏通知內容。
 * 解法：到開發者選項打開「停用螢幕分享保護」（一次設定、永久有效）；
 * 或用 adb 授權一次後，在這裡一鍵切換（進階）。
 */
@Composable
private fun ScreenShareCard() {
    val ctx = LocalContext.current
    var tick by rememberSaveable { mutableStateOf(0) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val can = remember(tick) { ScreenShareGuard.canWrite(ctx) }
    val cur = remember(tick) { ScreenShareGuard.read(ctx) }

    GText("通知被隱藏？", 13, FontWeight.SemiBold, secondary = true, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
    GlassCard(Modifier.fillMaxWidth(), radius = 26.dp) {
        Column {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GText("串流時其他 App 的通知被擋住、滑不掉？", 17, FontWeight.SemiBold)
                GText(
                    "HyperCast 要擷取系統聲音，必須啟動 Android 的螢幕擷取授權（只擷取聲音，不擷取畫面）。" +
                        "Android 15 起，系統把它當成螢幕分享，會隱藏所有通知的內容，這不是 HyperCast 的通知造成的。" +
                        "到「開發者選項」打開「停用螢幕分享保護」即可，只要設定一次。",
                    12, secondary = true,
                )
            }
            Divider()
            ActionRow(
                "開啟「開發者選項」",
                "沒有這個選單時，先到「關於手機」連點「OS 版本」7 次開啟開發者模式",
            ) {
                openSystemSettings(
                    ctx, android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                    "打不開開發者選項，請手動到 設定 → 更多設定 → 開發者選項",
                )
            }
            Divider()
            ActionRow("開啟「關於手機」", null) {
                openSystemSettings(ctx, android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS, "請手動到 設定 → 關於手機")
            }
            Divider()
            ActionRow(
                if (advanced) "收起進階" else "進階：用 adb 授權後，由 HyperCast 一鍵切換",
                if (advanced) null else "找不到開發者選項裡的開關時使用",
            ) { advanced = !advanced }
            if (advanced) {
                Divider()
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GText(
                        "系統預設不允許 App 修改這個開關。在電腦上執行一次下面的指令授權後，就能在這裡切換。" +
                            "停用後，螢幕分享 / 錄製時系統不再隱藏通知與 App 的敏感內容（例如驗證碼）；" +
                            "之後若要用別的 App 把螢幕分享給別人，請先按「還原」。",
                        12, secondary = true,
                    )
                }
                Divider()
                InfoRow("授權狀態", if (can) "已授權" else "尚未授權")
                Divider()
                InfoRow(
                    "目前值",
                    when (cur) {
                        "1" -> "1（已停用保護）"
                        "0" -> "0（保護啟用）"
                        null -> "未設定（系統預設：保護啟用）"
                        else -> cur
                    },
                )
                Divider()
                if (!can) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GText("在電腦上執行一次：", 12, secondary = true)
                        GText(ScreenShareGuard.ADB_CMD, 13, FontWeight.Medium)
                        GText("小米手機需要先在開發者選項開啟「USB 偵錯」與「USB 偵錯（安全設定）」，才能授權。", 12, secondary = true)
                    }
                    Divider()
                    ActionRow("複製指令", null) {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("adb", ScreenShareGuard.ADB_CMD))
                        android.widget.Toast.makeText(ctx, "已複製指令", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogButton("停用保護", true, Modifier.weight(1f), onClick = {
                            val ok = ScreenShareGuard.set(ctx, true)
                            tick++
                            android.widget.Toast.makeText(
                                ctx, if (ok) "已停用。重新串流後生效" else "寫入失敗，請確認 adb 授權", android.widget.Toast.LENGTH_LONG,
                            ).show()
                        })
                        DialogButton("還原", false, Modifier.weight(1f), onClick = {
                            val ok = ScreenShareGuard.set(ctx, false)
                            tick++
                            android.widget.Toast.makeText(ctx, if (ok) "已還原為啟用" else "寫入失敗", android.widget.Toast.LENGTH_SHORT).show()
                        })
                    }
                }
            }
        }
    }
}

// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Looper
import android.os.Handler
import android.app.NotificationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hypercast.airplay.ui.AppTheme
import com.hypercast.airplay.ui.MainScreen
import com.hypercast.airplay.ui.ThemeStore

class MainActivity : ComponentActivity() {

    private lateinit var discovery: RaopDiscovery
    private var pendingTargets: List<Target> = emptyList()

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val targets = pendingTargets
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null && targets.isNotEmpty()) {
                CastService.start(this, targets, r.resultCode, data)
            } else {
                AirPlayStore.update { it.copy(message = "需要授權擷取音訊才能串流") }
            }
        }

    /** 使用者在同意頁已逐項同意的項目（等系統的位置權限對話框回來後才真正開始記錄）。 */
    private var pendingConsent: Set<DiagItem> = emptySet()

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            startDiag(pendingConsent)
            DiagLog.d("LOC", "概略位置權限：${if (granted) "已授權" else "未授權（檔名地點將顯示為未知地點）"}")
            DiagLog.refreshLocation(this)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri == null) return@registerForActivityResult
            Thread {
                val ok = try {
                    DiagLog.flush()
                    contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(DiagLog.snapshot().toByteArray(Charsets.UTF_8))
                    }
                    true
                } catch (_: Exception) {
                    false
                }
                runOnUiThread {
                    Toast.makeText(this, if (ok) "日誌已匯出" else "匯出失敗", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

    // ---------------- Beta4：通知被擋診斷實驗（只擷取、不串流）
    private val probeProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data
            if (r.resultCode == RESULT_OK && d != null) ProbeService.start(this, r.resultCode, d)
            else ProbeStore.set(ProbeStage.Idle, "沒有取得螢幕擷取授權")
        }
    private val probeMicLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchProbeProjection() }

    private fun startProbe() {
        if (AirPlayStore.state.value.phase != Phase.Idle) {
            Toast.makeText(this, "請先中斷串流，再做診斷實驗", Toast.LENGTH_LONG).show()
            return
        }
        ProbeStore.reset()
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (mic) launchProbeProjection() else probeMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchProbeProjection() {
        val pm = getSystemService(MediaProjectionManager::class.java)
        probeProjectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    private fun probeAnswer(blocked: Boolean) {
        val st = ProbeStore.state.value
        when (st.stage) {
            ProbeStage.A -> {
                ProbeStore.state.value = st.copy(answerA = blocked)
                DiagLog.d("PROBE", "階段 A 回答：通知${if (blocked) "被擋住" else "正常"}")
                if (blocked) finishProbe() else ProbeService.audio(this)
            }
            ProbeStage.B -> {
                ProbeStore.state.value = st.copy(answerB = blocked)
                DiagLog.d("PROBE", "階段 B 回答：通知${if (blocked) "被擋住" else "正常"}")
                finishProbe()
            }
            else -> {}
        }
    }

    private fun finishProbe() {
        val st = ProbeStore.state.value
        ProbeStore.state.value = st.copy(stage = ProbeStage.Done)
        ProbeService.stop(this)
        fun r(v: Boolean?) = when (v) { true -> "被擋住"; false -> "正常"; null -> "未做" }
        DiagLog.d("PROBE", "實驗結果：階段A（只有螢幕擷取授權）=${r(st.answerA)}｜階段B（再加音訊擷取）=${r(st.answerB)}")
    }

    private fun abortProbe() {
        ProbeService.stop(this)
        ProbeStore.reset()
    }

    private fun markIssue() {
        if (!DiagLog.enabled) return
        DiagCollector.marker(this)
        Toast.makeText(this, "已標記，並記下目前的通知 / 鎖屏狀態", Toast.LENGTH_SHORT).show()
    }

    /** 同意頁按下「同意並開始記錄」：每一項都已同意才會走到這裡。 */
    private fun enableDiag(items: Set<DiagItem>) {
        if (!items.containsAll(DiagItem.all)) return          // 少一項都不開始
        pendingConsent = items
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startDiag(items) else locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** 開始記錄，並在 5 分鐘後提醒使用者回來儲存 / 傳送日誌。 */
    private fun startDiag(items: Set<DiagItem>) {
        if (!DiagLog.enable(this, items)) return
        LogReminder.schedule(this)
        Toast.makeText(this, "已開始記錄。請重現問題，約 5 分鐘後會提醒你回來傳送日誌", Toast.LENGTH_LONG).show()
    }

    private fun sendFeedback() {
        if (!DiagLog.hasData()) {
            Toast.makeText(this, "目前沒有日誌可傳送", Toast.LENGTH_SHORT).show()
            return
        }
        if (!FeedbackMail.send(this)) Toast.makeText(this, "找不到可用的郵件 App", Toast.LENGTH_LONG).show()
    }

    /** 完全關閉：中斷串流、移除所有通知、結束程序。 */
    private fun quitApp() {
        val streaming = AirPlayStore.state.value.phase != Phase.Idle
        if (streaming) CastService.stop(this)
        DiagLog.flush()
        runCatching { discovery.stop() }
        finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { getSystemService(NotificationManager::class.java).cancelAll() }
            android.os.Process.killProcess(android.os.Process.myPid())
        }, if (streaming) 1500L else 300L)
    }

    private fun exportDiag() {
        if (!DiagLog.hasData()) {
            Toast.makeText(this, "目前沒有日誌可匯出", Toast.LENGTH_SHORT).show()
            return
        }
        exportLauncher.launch(DiagLog.exportFileName())
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true || hasMic()) {
                projectionLauncher.launch(
                    getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
                )
            } else {
                AirPlayStore.update { it.copy(message = "需要「錄音」權限才能擷取系統音訊") }
            }
        }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 先要權限與螢幕音訊擷取授權，再開始串流到 [targets]。 */
    private fun startCasting(targets: List<Target>) {
        pendingTargets = targets
        val need = mutableListOf<String>()
        if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) {
            projectionLauncher.launch(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
            )
        } else {
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    /**
     * 點一台裝置：
     *  - 閒置時 → 開始串流到它
     *  - 串流中且它不在清單 → 加入成第二台（最多 2 台）
     *  - 串流中且它已在清單 → 把它移除（只剩一台時就是中斷）
     */
    private fun toggle(item: DeviceItem) {
        val s = AirPlayStore.state.value
        val ts = item.members.map { Target(it, Role.Stereo, item.pair) }
        when (s.phase) {
            Phase.Connecting -> Unit
            Phase.Streaming -> when {
                item.members.any { m -> s.targets.any { it.rx.id == m.id } } ->
                    item.members.filter { m -> s.targets.any { it.rx.id == m.id } }.forEach { CastService.remove(this, it.id) }
                s.targets.size + ts.size > AirPlayStore.MAX_TARGETS ->
                    AirPlayStore.update { it.copy(message = "最多同時串流到 ${AirPlayStore.MAX_TARGETS} 台裝置，請先移除一台") }
                else -> ts.forEach { CastService.add(this, it) }
            }
            else -> startCasting(ts.take(AirPlayStore.MAX_TARGETS))
        }
    }

    // ---- 從 HyperIsland / 通知的「連線」重新連線到上次的裝置 ----
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectTries = 0

    private fun handleIntent(i: android.content.Intent?) {
        val intent = i ?: return
        if (!intent.getBooleanExtra(CastService.EXTRA_RECONNECT, false)) return
        intent.removeExtra(CastService.EXTRA_RECONNECT)
        if (AirPlayStore.state.value.phase == Phase.Streaming || AirPlayStore.state.value.phase == Phase.Connecting) return
        reconnectTries = 0
        tryReconnect()
    }

    /** 探索剛啟動，裝置清單可能還是空的：最多等 10 秒。 */
    private fun tryReconnect() {
        val last = LastStore.load(this)
        val rcv = AirPlayStore.state.value.receivers
        val found = last.mapNotNull { (id, _, role) -> rcv.firstOrNull { it.id == id }?.let { it to role } }
        if (last.isEmpty()) {
            AirPlayStore.update { it.copy(message = "沒有上次的連線紀錄，請從清單選擇裝置") }
            return
        }
        if (found.size == last.size || (found.isNotEmpty() && reconnectTries >= 20)) {
            startCasting(found.take(AirPlayStore.MAX_TARGETS).map { (rx, role) ->
                Target(rx, role, isStereoPair(rx, rcv))
            })
            return
        }
        if (reconnectTries++ < 20) handler.postDelayed({ tryReconnect() }, 500)
        else AirPlayStore.update { it.copy(message = "找不到上次的裝置，請從清單重新選擇") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DiagLog.init(this)
        VolumeStore.init(this)
        PrefsStore.init(this)
        Haptics.init(this)
        AirPlayStore.update { it.copy(volume = VolumeStore.load()) }
        discovery = RaopDiscovery(this)
        // 記錄已開啟時，每次 App 啟動補一份快照（含系統記錄的「上次為什麼結束」）；旋轉畫面等重建不重複記
        if (DiagLog.enabled && savedInstanceState == null) {
            DiagLog.d("LOG", "App 啟動")
            DiagCollector.snapshotAll(this, "App 啟動")
        }

        android.util.Log.i("HyperCast", "Developed by ${BuildConfig.DEVELOPER}")
        handleIntent(intent)
        var theme by mutableStateOf(ThemeStore.load(this))
        var delayMs by mutableStateOf(DelayStore.load(this))

        setContent {
            val state by AirPlayStore.state.collectAsState()
            val diag by DiagLog.state.collectAsState()
            MainScreen(
                state = state,
                diag = diag,
                onDiagEnable = ::enableDiag,
                onDiagDisable = { DiagLog.disable(); LogReminder.cancel(this) },
                onDiagExport = ::exportDiag,
                onDiagClear = { DiagLog.clear() },
                onFeedback = ::sendFeedback,
                onDiagMark = ::markIssue,
                onProbeStart = ::startProbe,
                onProbeAnswer = ::probeAnswer,
                onProbeAbort = ::abortProbe,
                onQuit = ::quitApp,
                theme = theme,
                onTheme = { t: AppTheme -> theme = t; ThemeStore.save(this, t) },
                onToggle = ::toggle,
                onChannelMode = AirPlayStore::applyChannelMode,
                onDisconnect = { CastService.stop(this) },
                onVolume = AirPlayStore::setVolume,
                delayMs = delayMs,
                onDelay = { v: Int -> delayMs = DelayStore.save(this, v) },
                onMute = AirPlayStore::toggleMute,
            )
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) showIdleIsland() }

    /** 沒在串流時，在 HyperIsland 顯示「未連接」。 */
    private fun showIdleIsland() {
        if (AirPlayStore.state.value.phase == Phase.Idle) AppIsland.show(this)
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
        // 沒在串流時清掉本 App 殘留的通知（舊版留下的焦點通知、已中斷通知），其他 App 的通知不受影響
        if (AirPlayStore.state.value.phase == Phase.Idle) {
            try { getSystemService(android.app.NotificationManager::class.java).cancelAll() } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else showIdleIsland()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }
}

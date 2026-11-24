// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hypercast.airplay.ui.AppTheme
import com.hypercast.airplay.ui.MainScreen
import com.hypercast.airplay.ui.ThemeStore

class MainActivity : ComponentActivity() {

    private lateinit var discovery: RaopDiscovery
    private var pendingTargets: List<Target> = emptyList()
    private var startDiagAfterPerm: Boolean = false
    private var permVersion by mutableIntStateOf(0)

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

    private val diagPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            permVersion++
            val shouldStart = startDiagAfterPerm
            startDiagAfterPerm = false
            if (shouldStart && DiagLog.state.value.allConsented) {
                startDiag()
                grants.forEach { (perm, ok) ->
                    DiagLog.d("SYS", "權限請求結果 $perm：${if (ok) "已授權" else "未授權"}")
                }
            } else if (DiagLog.enabled) {
                grants.forEach { (perm, ok) ->
                    DiagLog.d("SYS", "權限狀態更新 $perm：${if (ok) "已授權" else "未授權"}")
                }
                if (grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                    DiagLog.refreshLocation(this)
                }
            }
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

    private fun requestDiagPermission(perm: String) {
        startDiagAfterPerm = false
        diagPermLauncher.launch(arrayOf(perm))
    }

    /** 僅在所有診斷項目皆已同意（逐項同意或全部同意）後，才詢問尚未取得的系統權限並啟動搜集。 */
    private fun enableDiag() {
        if (!DiagLog.state.value.allConsented) {
            Toast.makeText(this, "請先同意所有搜集項目（或按「全部同意」）再開始", Toast.LENGTH_SHORT).show()
            return
        }
        val missing = DiagCategory.entries
            .filter { it.requiresRuntimePrompt() && !it.isPermissionGranted(this) }
            .mapNotNull { it.runtimePermission }
            .distinct()
        if (missing.isEmpty()) {
            startDiag()
        } else {
            startDiagAfterPerm = true
            diagPermLauncher.launch(missing.toTypedArray())
        }
    }

    /** 開始記錄，並在 5 分鐘後提醒使用者回來儲存 / 傳送日誌。 */
    private fun startDiag() {
        if (DiagLog.enable(this)) {
            LogReminder.schedule(this)
            Toast.makeText(this, "已開始搜集診斷資料。請重現問題，約 5 分鐘後會提醒你回來傳送日誌", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "請先完成所有項目同意後再開始搜集", Toast.LENGTH_SHORT).show()
        }
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
            permVersion++
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
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectTries = 0

    private fun handleIntent(i: android.content.Intent?) {
        val intent = i ?: return
        if (!intent.getBooleanExtra(CastService.EXTRA_RECONNECT, false)) return
        intent.removeExtra(CastService.EXTRA_RECONNECT)
        if (AirPlayStore.state.value.phase == Phase.Streaming || AirPlayStore.state.value.phase == Phase.Connecting) return
        reconnectTries = 0
        tryReconnect()
    }

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

        android.util.Log.i("HyperCast", "HyperCast v${BuildConfig.VERSION_NAME} — Developed by ${BuildConfig.DEVELOPER}")
        handleIntent(intent)
        var theme by mutableStateOf(ThemeStore.load(this))
        var delayMs by mutableStateOf(DelayStore.load(this))

        setContent {
            val state by AirPlayStore.state.collectAsState()
            val diag by DiagLog.state.collectAsState()
            MainScreen(
                state = state,
                diag = diag,
                permVersion = permVersion,
                onDiagCategoryConsent = { cat, agree -> DiagLog.setCategoryConsent(this, cat, agree) },
                onDiagAgreeAll = { agree -> DiagLog.setAllConsented(this, agree) },
                onDiagRequestPermission = ::requestDiagPermission,
                onDiagEnable = ::enableDiag,
                onDiagDisable = { DiagLog.disable(); LogReminder.cancel(this) },
                onDiagRefresh = {
                    DiagLog.refreshSnapshot(this)
                    Toast.makeText(this, "已重新採樣系統診斷快照", Toast.LENGTH_SHORT).show()
                },
                onDiagExport = ::exportDiag,
                onDiagClear = { DiagLog.clear() },
                onFeedback = ::sendFeedback,
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

    override fun onResume() {
        super.onResume()
        permVersion++
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            permVersion++
            if (ok) showIdleIsland()
        }

    private fun showIdleIsland() {
        if (AirPlayStore.state.value.phase == Phase.Idle) AppIsland.show(this)
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
        if (AirPlayStore.state.value.phase == Phase.Idle) {
            try { getSystemService(NotificationManager::class.java).cancelAll() } catch (_: Exception) {}
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

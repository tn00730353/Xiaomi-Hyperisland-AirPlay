// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.hypercast.airplay.ap2.Ap2Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/**
 * 擷取系統播放音訊（立體聲 44.1kHz）→ ALAC → AirPlay，並維護狀態列 / HyperIsland 通知。
 * 最多同時送給 2 台裝置；兩台單顆喇叭時可以分配左右聲道，立體聲組（HomePod 配對）直連組內主機。
 */
class CastService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager

    /** 一個串流目標：live = 已連線完成、可以送音訊。 */
    private class Slot(val rx: Receiver, @Volatile var role: Role, val pair: Boolean) {
        @Volatile var session: CastSession? = null
        @Volatile var live = false
        @Volatile var sentVol = -1f          // 上一次送給接收端的音量 / 靜音，沒變就不重送
        @Volatile var sentMuted = false
    }

    @Volatile private var running = false
    private var worker: Thread? = null
    private val slots = CopyOnWriteArrayList<Slot>()
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        com.hypercast.airplay.ap2.HapStore.init(this)
        VolumeStore.init(this)
        PrefsStore.init(this)
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "AirPlay 串流狀態", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    // ------------------------------------------------------------ 手機音量鍵 → 接收端音量（鏡射）

    /**
     * v1.2.6：不再建立「遠端音量」MediaSession（setPlaybackToRemote）。那種做法會讓系統把整個媒體音量
     * （實體音量鍵、音量面板的媒體滑桿）交給 HyperCast，手機自己的媒體音量就沒辦法調了，
     * 而且 HyperCast 會變成「目前的媒體工作階段」，耳機 / 藍牙的播放 / 暫停鍵也會被它吃掉。
     *
     * 改成：音量鍵照常調整手機的媒體音量；HyperCast 監聽手機媒體音量的變化，
     * 把「變動的格數」換算成比例，同步加減到接收端音量。AudioPlaybackCapture 擷取到的是
     * 混音前的聲音，不受手機媒體音量影響，所以兩邊可以各自獨立、互不干擾。
     */
    private var phoneVol = -1
    private var volReceiver: BroadcastReceiver? = null
    private var volObserver: ContentObserver? = null

    private fun startVolumeMirror() {
        stopVolumeMirror()
        val am = getSystemService(AudioManager::class.java) ?: return
        phoneVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    val type = i?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) ?: -1
                    if (type == AudioManager.STREAM_MUSIC) onPhoneVolumeChanged()
                }
            }
            ContextCompat.registerReceiver(
                this, r, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            volReceiver = r
        } catch (e: Exception) {
            DiagLog.d("CAST", "音量廣播註冊失敗：${e.message}")
        }
        try {
            // 備援：部分系統不會送 VOLUME_CHANGED_ACTION，改看系統設定資料庫的變動
            val o = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) { onPhoneVolumeChanged() }
            }
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, o)
            volObserver = o
        } catch (e: Exception) {
            DiagLog.d("CAST", "音量觀察者註冊失敗：${e.message}")
        }
    }

    private fun stopVolumeMirror() {
        volReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        volReceiver = null
        volObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        volObserver = null
        phoneVol = -1
    }

    private fun onPhoneVolumeChanged() {
        if (AirPlayStore.state.value.phase == Phase.Idle) return
        val am = audioManagerOrNull() ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val prev = phoneVol
        phoneVol = now                       // 即使關閉同步也持續記錄，之後開啟才不會跳一大段
        if (prev < 0 || now == prev) return
        val before = AirPlayStore.state.value.volume
        if (!PrefsStore.state.value.mirrorVolume) {
            DiagLog.d("VOL", "手機音量 $prev→$now（/$max）：已關閉「同步手機音量鍵」，接收端音量不變")
            return
        }
        AirPlayStore.adjust((now - prev).toFloat() / max)
        // 記錄每一次同步：若「換歌時忽大忽小」是被別的程式改了手機音量而連帶改到接收端，這裡看得出來
        DiagLog.d("VOL", "手機音量 $prev→$now（/$max）→ 接收端音量 ${(before * 100).toInt()}%→${(AirPlayStore.state.value.volume * 100).toInt()}%")
    }

    private fun audioManagerOrNull(): AudioManager? = getSystemService(AudioManager::class.java)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCast(intent)
            ACTION_ADD -> readTarget(intent, "a_")?.let { addTarget(it) }
            ACTION_REMOVE -> removeTarget(intent.getStringExtra("id") ?: "")
            ACTION_VOL_UP -> AirPlayStore.adjust(+0.08f)
            ACTION_VOL_DOWN -> AirPlayStore.adjust(-0.08f)
            ACTION_MUTE -> AirPlayStore.toggleMute()
            ACTION_STOP -> stopCast(null)
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ 開始 / 停止

    private fun startCast(i: Intent) {
        AppIsland.hide(this)
        if (running) stopWorker()

        val targets = listOfNotNull(readTarget(i, "a_"), readTarget(i, "b_"))
        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data")

        // 每次連線都套用上一次使用的音量（第一次是 25%），並且取消靜音；連線成功後不會自動開始「播放」，有聲音才算播放中
        AirPlayStore.update {
            it.copy(targets = targets, phase = Phase.Connecting, message = null,
                volume = VolumeStore.load(), muted = false, playing = false)
        }
        DiagLog.refreshLocation(this)
        DiagLog.d("CAST", "要求連線：" + targets.joinToString(" ＋ ") {
            "${it.rx.name}｜型號 ${it.rx.model}｜${it.rx.host}:${it.rx.port}｜聲道 ${it.role.label}｜立體聲組=${it.pair}｜AirPlay2=${it.rx.ap2}"
        })

        // Android 14+：必須先進入 mediaProjection 前景狀態，才能取得 MediaProjection
        nm.cancel(NOTIF_ID_IDLE)
        startVolumeMirror()
        startForeground(NOTIF_ID, buildNotification(AirPlayStore.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (targets.isEmpty()) { stopCast("找不到要連線的裝置"); return }
        if (data == null) { stopCast("沒有螢幕音訊擷取授權"); return }

        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (running) stopCast("系統已停止音訊擷取") }
        }, main)
        projection = mp

        slots.clear()
        targets.forEach { slots += Slot(it.rx, it.role, it.pair) }
        running = true
        launchNotificationLoop()
        worker = Thread({ runCast(mp) }, "cast-worker").also { it.start() }
    }

    private var notifJob: kotlinx.coroutines.Job? = null

    /** 狀態改變時更新通知（含音量、聲道），並把音量送到每一台接收端。 */
    private fun launchNotificationLoop() {
        notifJob?.cancel()
        notifJob = scope.launch {
            // 設定（HyperIsland 顯示方式）改變時也要重送通知
            combine(AirPlayStore.state, PrefsStore.state) { s, _ -> s }.collectLatest { s ->
                delay(120)   // 滑動音量時合併更新，避免通知被系統限流
                if (!running) return@collectLatest
                nm.notify(NOTIF_ID, buildNotification(s))
                // 聲道分配由 UI 改狀態，這裡同步到各目標
                s.targets.forEach { t -> slots.firstOrNull { it.rx.id == t.rx.id }?.role = t.role }
                // 只有音量 / 靜音真的變了才送 SET_PARAMETER（以前每次狀態更新，例如「開始 / 停止播放」，都會重送一次）
                slots.forEach {
                    if (it.live && (it.sentVol != s.volume || it.sentMuted != s.muted)) {
                        it.sentVol = s.volume; it.sentMuted = s.muted
                        it.session?.setVolume(s.volume, s.muted)
                    }
                }
                if (s.targets.any { it.state == TargetState.Streaming }) LastStore.save(this@CastService, s.targets)
            }
        }
    }

    private fun makeSession(rx: Receiver): CastSession = if (rx.ap2) Ap2Session(rx) else RaopSession(rx)

    /** 連線一個目標；失敗時丟出例外。完成後標記為 live 並套用目前音量。 */
    private fun connectSlot(slot: Slot) {
        val s = makeSession(slot.rx)
        slot.session = s
        val t0 = System.nanoTime()
        try {
            s.connect()
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            slot.session = null
            throw e
        }
        if (!running || slot !in slots) {     // 連線期間被使用者取消
            try { s.close() } catch (_: Exception) {}
            slot.session = null
            return
        }
        DiagLog.d("CAST", "「${slot.rx.name}」連線成功（${(System.nanoTime() - t0) / 1_000_000} ms）")
        val st = AirPlayStore.state.value
        s.setVolume(st.volume, st.muted)
        slot.sentVol = st.volume; slot.sentMuted = st.muted
        slot.live = true
        AirPlayStore.setTargetState(slot.rx.id, TargetState.Streaming)
    }

    /** 第二台：失敗時只移除它，不影響正在播放的第一台。 */
    private fun connectOptional(slot: Slot) {
        try {
            connectSlot(slot)
        } catch (e: Exception) {
            if (!running) return
            DiagLog.e("CAST", "「${slot.rx.name}」連線失敗", e)
            dropSlot(slot, "「${slot.rx.name}」連線失敗：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun addTarget(t: Target) {
        if (!running) return
        if (slots.any { it.rx.id == t.rx.id }) return
        if (slots.size >= AirPlayStore.MAX_TARGETS) {
            AirPlayStore.update { it.copy(message = "最多同時串流到 ${AirPlayStore.MAX_TARGETS} 台裝置") }
            return
        }
        val slot = Slot(t.rx, Role.Stereo, t.pair)
        slots += slot
        AirPlayStore.update { it.copy(targets = it.targets + Target(t.rx, Role.Stereo, t.pair), message = null) }
        DiagLog.d("CAST", "加入第二台：${t.rx.name}（立體聲組=${t.pair}）")
        Thread({ connectOptional(slot) }, "cast-add").start()
    }

    private fun removeTarget(id: String) {
        val slot = slots.firstOrNull { it.rx.id == id } ?: return
        if (slots.size <= 1) { stopCast(null); return }
        dropSlot(slot, null)
    }

    private fun dropSlot(slot: Slot, message: String?) {
        slots.remove(slot)
        slot.live = false
        AirPlayStore.removeTarget(slot.rx.id)
        if (message != null) AirPlayStore.update { it.copy(message = message) }
        val s = slot.session
        slot.session = null
        if (s != null) Thread { try { s.close() } catch (_: Exception) {} }.start()
    }

    /** 立體聲 PCM → 只保留指定聲道，並複製到左右兩邊（該喇叭只播自己負責的那一邊）。 */
    private fun remix(src: ByteArray, role: Role, dst: ByteArray) {
        val off = if (role == Role.Left) 0 else 2
        var o = 0
        while (o + 3 < src.size) {
            val a = src[o + off]
            val b = src[o + off + 1]
            dst[o] = a; dst[o + 1] = b; dst[o + 2] = a; dst[o + 3] = b
            o += 4
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCast(mp: MediaProjection) {
        var error: String? = null
        try {
            val first = slots.firstOrNull() ?: throw IOException("沒有可連線的裝置")
            val tConn = System.nanoTime()
            connectSlot(first)
            if (!running || !first.live) return    // 連線期間被使用者取消
            DiagLog.d("CAST", "第一台接收端連線成功（${(System.nanoTime() - tConn) / 1_000_000} ms），開始擷取系統音訊")

            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(android.os.Process.myUid())   // 與 Base 相同：不擷取自己 App 的聲音
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)   // 立體聲
                .build()
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setAudioPlaybackCaptureConfig(cfg)
                .setBufferSizeInBytes(Alac.FRAME_BYTES * 16)
                .build()
            recorder = rec
            rec.startRecording()
            DiagLog.d("CAST", "AudioRecord 已啟動（狀態 ${rec.recordingState}）")
            AirPlayStore.update { it.copy(phase = Phase.Streaming) }

            // 第二台（如果有）在串流開始後另外連線
            slots.drop(1).forEach { s -> Thread({ connectOptional(s) }, "cast-second").start() }

            val buf = ByteArray(Alac.FRAME_BYTES)
            val mix = ByteArray(Alac.FRAME_BYTES)
            val leveler = Leveler()
            var levelerWasOn = false
            var winSq = 0.0; var winN = 0; var lastWinDb = Double.NaN
            val enc = arrayOfNulls<ByteArray>(Role.values().size)
            var frames = 0L
            var lastSound = 0L
            var peak = 0
            var lastLog = System.nanoTime()
            while (running) {
                var off = 0
                while (off < buf.size && running) {
                    val n = rec.read(buf, off, buf.size - off)
                    if (n < 0) throw IOException("音訊擷取失敗 ($n)")
                    off += n
                }
                if (!running) break

                // 偵測目前擷取到的是不是有聲音（略過取樣，成本很低）
                var pk = 0
                var q = 0
                while (q + 1 < buf.size) {
                    val v = Math.abs((buf[q + 1].toInt() shl 8) or (buf[q].toInt() and 0xFF))
                    if (v > pk) pk = v
                    q += 32
                }
                val tNow = System.nanoTime()
                if (pk > 150) { lastSound = tNow; AirPlayStore.setPlaying(true) }
                else if (tNow - lastSound > 3_000_000_000L) AirPlayStore.setPlaying(false)

                // 音量平衡（設定中開啟時）：在編碼前處理，兩台接收端拿到同樣的結果
                if (PrefsStore.state.value.leveler) {
                    if (!levelerWasOn) { leveler.reset(); levelerWasOn = true; DiagLog.d("LVL", "音量平衡：開啟") }
                    leveler.process(buf)
                } else if (levelerWasOn) {
                    levelerWasOn = false; DiagLog.d("LVL", "音量平衡：關閉")
                }

                enc.fill(null)
                for (slot in slots) {
                    if (!slot.live) continue
                    val k = slot.role.ordinal
                    val frame = enc[k] ?: run {
                        val src = if (slot.role == Role.Stereo) buf else { remix(buf, slot.role, mix); mix }
                        Alac.encode(src).also { enc[k] = it }
                    }
                    try {
                        slot.session?.sendFrame(frame)
                    } catch (e: Exception) {
                        if (slots.count { it.live } <= 1) throw e      // 最後一台斷線＝整個串流結束
                        DiagLog.e("CAST", "「${slot.rx.name}」送出失敗，已移除", e)
                        dropSlot(slot, "「${slot.rx.name}」中斷：${e.message ?: e.javaClass.simpleName}")
                    }
                }
                frames++
                if (DiagLog.enabled) {
                    // 每約 1 秒統計一次響度；兩次相差 ≥ 6 dB 就記一筆（通常是換歌或音量被改）
                    var k = 0
                    while (k + 1 < buf.size) {
                        val sv = ((buf[k + 1].toInt() shl 8) or (buf[k].toInt() and 0xFF)).toShort().toInt() / 32768.0
                        winSq += sv * sv; winN++
                        k += 2
                    }
                    if (winN >= 44100 * 2) {
                        val db = 10.0 * Math.log10(winSq / winN + 1e-12)
                        if (!lastWinDb.isNaN() && db > -55 && lastWinDb > -55 && Math.abs(db - lastWinDb) >= 6.0) {
                            DiagLog.d("LVL", "響度變化 ${"%.1f".format(java.util.Locale.US, lastWinDb)}→${"%.1f".format(java.util.Locale.US, db)} dBFS" +
                                "（平衡增益 ${"%.1f".format(java.util.Locale.US, leveler.currentGainDb)} dB，接收端音量 ${(AirPlayStore.state.value.volume * 100).toInt()}%）")
                        }
                        lastWinDb = db; winSq = 0.0; winN = 0
                    }
                    var i = 0
                    while (i + 1 < buf.size) {
                        val v = Math.abs((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF))
                        if (v > peak) peak = v
                        i += 2
                    }
                    val now = System.nanoTime()
                    if (now - lastLog > 10_000_000_000L) {
                        val vs = AirPlayStore.state.value
                        DiagLog.d("CAST", "串流中：已送出 $frames 幀（${slots.count { it.live }} 台：${slots.joinToString(" ＋ ") { it.rx.name }}），接收端音量 ${(vs.volume * 100).toInt()}%、靜音=${vs.muted}，近 10 秒最大振幅 $peak/32768（0 代表被擷取到的是靜音）")
                        peak = 0
                        lastLog = now
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                error = e.message ?: e.javaClass.simpleName
                DiagLog.e("CAST", "連線或串流失敗：${e.javaClass.name}", e)
            }
        } finally {
            if (running) main.post { stopCast(error ?: "串流已結束") }
        }
    }

    private fun stopWorker() {
        running = false
        stopVolumeMirror()
        notifJob?.cancel()
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        val sessions = slots.mapNotNull { it.session }
        slots.forEach { it.live = false; it.session = null }
        slots.clear()
        if (sessions.isNotEmpty()) Thread { sessions.forEach { s -> try { s.close() } catch (_: Exception) {} } }.start()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker = null
    }

    private fun stopCast(message: String?) {
        DiagLog.d("CAST", "停止串流：${message ?: "使用者中斷"}")
        stopWorker()
        AirPlayStore.update {
            it.copy(
                playing = false,
                phase = if (message != null && message != "串流已結束") Phase.Error else Phase.Idle,
                targets = emptyList(),
                message = message,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIF_ID)
        // 使用者自己中斷：留下一則「已中斷 · 連線」通知，HyperIsland 上可以一鍵重新連線
        if (message == null) postIdleNotification() else AppIsland.show(this)
        stopSelf()
    }

    override fun onDestroy() {
        stopWorker()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知

    private fun pi(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, CastService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 連接的裝置圖示：HomePod / HomePod mini / Apple TV / 一般 AirPlay。 */
    private fun deviceIcon(rx: List<Receiver>): Int {
        val m = rx.firstOrNull()?.model ?: ""
        return when {
            m.startsWith("AudioAccessory5") -> R.drawable.ic_homepod_mini
            m.startsWith("AudioAccessory") -> R.drawable.ic_homepod
            m.startsWith("AppleTV") -> R.drawable.ic_tv
            else -> R.drawable.ic_airplay
        }
    }

    // ---- HyperIsland：短暫顯示模式只在內容改變後的幾秒內附上焦點參數 ----
    private var islandSig = ""
    private var islandSigAt = 0L

    /** 這次重送通知時要不要附上焦點（島）參數。 */
    private fun islandWanted(info: HyperIsland.Info): Boolean {
        return when (PrefsStore.state.value.island) {
            IslandMode.Off -> false
            IslandMode.Always -> true
            IslandMode.Brief -> {
                val sig = "${info.mode}|${info.name}|${info.volumePct}|${info.muted}"
                val now = SystemClock.elapsedRealtime()
                if (sig != islandSig) { islandSig = sig; islandSigAt = now; true }
                else now - islandSigAt < (HyperIsland.BRIEF_SECONDS + 1) * 1000L
            }
        }
    }

    private fun applyIsland(b: Notification.Builder, info: HyperIsland.Info) {
        if (islandWanted(info)) HyperIsland.apply(this, b, info, PrefsStore.state.value.island == IslandMode.Brief)
    }

    private fun targetsTitle(s: UiState): String = s.targets.joinToString(" ＋ ") { it.rx.name }.ifBlank { "AirPlay" }

    private fun streamingSubtitle(s: UiState): String {
        val t = s.targets
        return when {
            t.size >= 2 && t.any { it.role != Role.Stereo } -> "左右聲道 · ${t.size} 台"
            t.size >= 2 -> "${t.size} 台 · 立體聲"
            t.firstOrNull()?.pair == true -> "串流中 · 立體聲組"
            t.firstOrNull()?.rx?.ap2 == true -> "串流中 · AirPlay 2 · 立體聲"
            else -> "串流中 · 立體聲 · ALAC"
        }
    }

    private fun buildNotification(s: UiState): Notification {
        val connecting = s.phase == Phase.Connecting
        val title = when (s.phase) {
            Phase.Connecting -> "正在連線…"
            Phase.Streaming -> targetsTitle(s)
            else -> "HyperCast"
        }
        val sub = when (s.phase) {
            Phase.Connecting -> targetsTitle(s)
            Phase.Streaming -> streamingSubtitle(s)
            else -> "待機"
        }
        val pct = if (s.muted) 0 else (s.volume * 100).roundToInt()
        val volTxt = if (s.muted) "靜音" else "$pct%"

        val rv = RemoteViews(packageName, R.layout.notif_glass).apply {
            setTextViewText(R.id.n_title, title)
            setTextViewText(R.id.n_sub, "$sub · $volTxt")
            setProgressBar(R.id.n_vol, 100, pct, false)
            setOnClickPendingIntent(R.id.n_down, pi(ACTION_VOL_DOWN))
            setOnClickPendingIntent(R.id.n_up, pi(ACTION_VOL_UP))
            setOnClickPendingIntent(R.id.n_mute, pi(ACTION_MUTE))
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun act(res: Int, label: String, a: String) =
            Notification.Action.Builder(Icon.createWithResource(this, res), label, pi(a)).build()

        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle(title)
            .setContentText("$sub · $volTxt")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .setContentIntent(open)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .addAction(act(R.drawable.ic_vol_down, "音量-", ACTION_VOL_DOWN))
            .addAction(act(R.drawable.ic_vol_up, "音量+", ACTION_VOL_UP))
            .addAction(act(R.drawable.ic_stop, "中斷", ACTION_STOP))

        applyIsland(
            b,
            HyperIsland.Info(
                mode = when {
                    connecting -> HyperIsland.Mode.Connecting
                    s.playing -> HyperIsland.Mode.Playing
                    else -> HyperIsland.Mode.Connected
                },
                name = targetsTitle(s), iconRes = deviceIcon(s.targets.map { it.rx }),
                volumePct = pct, muted = s.muted,
            )
        )
        return b.build()
    }

    /** 中斷後的「已中斷 · 連線」通知（非前景、可滑掉、2 分鐘後自動消失）。 */
    private fun postIdleNotification() {
        val last = LastStore.load(this)
        if (last.isEmpty()) { AppIsland.show(this); return }
        val names = last.joinToString(" ＋ ") { it.second }
        val reconnect = PendingIntent.getActivity(
            this, 77,
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_RECONNECT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle("已中斷")
            .setContentText("點一下重新連線：$names")
            .setContentIntent(reconnect)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(120_000)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_airplay), "連線", reconnect).build())
        applyIsland(
            b,
            HyperIsland.Info(
                mode = HyperIsland.Mode.Idle, name = names,
                iconRes = deviceIcon(last.mapNotNull { (id, _, _) -> AirPlayStore.state.value.receivers.firstOrNull { it.id == id } }),
                volumePct = (VolumeStore.load() * 100).roundToInt(), muted = false,
            )
        )
        nm.notify(NOTIF_ID_IDLE, b.build())
    }

    companion object {
        private const val CH = "airplay_status"
        private const val NOTIF_ID = 4242
        private const val NOTIF_ID_IDLE = 4243
        const val EXTRA_RECONNECT = "com.hypercast.RECONNECT"
        const val ACTION_START = "com.hypercast.START"
        const val ACTION_ADD = "com.hypercast.ADD"
        const val ACTION_REMOVE = "com.hypercast.REMOVE"
        const val ACTION_STOP = "com.hypercast.STOP"
        const val ACTION_VOL_UP = "com.hypercast.VOL_UP"
        const val ACTION_VOL_DOWN = "com.hypercast.VOL_DOWN"
        const val ACTION_MUTE = "com.hypercast.MUTE"

        private fun putTarget(i: Intent, p: String, t: Target) {
            val rx = t.rx
            i.putExtra("${p}id", rx.id).putExtra("${p}name", rx.name).putExtra("${p}host", rx.host)
                .putExtra("${p}port", rx.port).putExtra("${p}et", rx.et).putExtra("${p}pw", rx.password)
                .putExtra("${p}model", rx.model).putExtra("${p}ap2", rx.ap2)
                .putExtra("${p}groupId", rx.groupId).putExtra("${p}igl", rx.groupLeader).putExtra("${p}inGroup", rx.inGroup)
                .putExtra("${p}gid", rx.gid).putExtra("${p}gcgl", rx.gcgl)
                .putExtra("${p}role", t.role.name).putExtra("${p}pair", t.pair)
        }

        private fun readTarget(i: Intent?, p: String): Target? {
            if (i == null) return null
            val host = i.getStringExtra("${p}host") ?: return null
            val rx = Receiver(
                id = i.getStringExtra("${p}id") ?: "",
                name = i.getStringExtra("${p}name") ?: "AirPlay",
                host = host,
                port = i.getIntExtra("${p}port", 7000),
                et = i.getStringExtra("${p}et") ?: "0,1",
                password = i.getBooleanExtra("${p}pw", false),
                model = i.getStringExtra("${p}model") ?: "",
                ap2 = i.getBooleanExtra("${p}ap2", false),
                groupId = i.getStringExtra("${p}groupId") ?: "",
                groupLeader = i.getBooleanExtra("${p}igl", false),
                inGroup = i.getBooleanExtra("${p}inGroup", false),
                gid = i.getStringExtra("${p}gid") ?: "",
                gcgl = i.getBooleanExtra("${p}gcgl", false),
            )
            val role = Role.values().firstOrNull { it.name == i.getStringExtra("${p}role") } ?: Role.Stereo
            return Target(rx, role, i.getBooleanExtra("${p}pair", false))
        }

        /** 開始串流到 1～2 台裝置（需要螢幕音訊擷取授權）。 */
        fun start(ctx: Context, targets: List<Target>, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CastService::class.java).setAction(ACTION_START)
                .putExtra("code", resultCode).putExtra("data", data)
            targets.getOrNull(0)?.let { putTarget(i, "a_", it) }
            targets.getOrNull(1)?.let { putTarget(i, "b_", it) }
            ContextCompat.startForegroundService(ctx, i)
        }

        /** 串流中加入第二台。 */
        fun add(ctx: Context, t: Target) {
            val i = Intent(ctx, CastService::class.java).setAction(ACTION_ADD)
            putTarget(i, "a_", t)
            ctx.startService(i)
        }

        fun remove(ctx: Context, id: String) {
            ctx.startService(Intent(ctx, CastService::class.java).setAction(ACTION_REMOVE).putExtra("id", id))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CastService::class.java).setAction(ACTION_STOP))
        }
    }
}

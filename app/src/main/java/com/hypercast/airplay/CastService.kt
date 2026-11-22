// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaMetadata
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.VolumeProvider
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.hypercast.airplay.ap2.Ap2Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
        nm = getSystemService(NotificationManager::class.java)
        setupMediaSession()
        nm.createNotificationChannel(
            NotificationChannel(CH, "AirPlay 串流狀態", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    // ------------------------------------------------------------ MediaSession（HyperIsland / 系統音量鍵控制）

    private var mediaSession: MediaSession? = null
    private var volProvider: VolumeProvider? = null

    /**
     * 與 Base 的 LocalVolumeSession 同類做法：建立一個「遠端音量」MediaSession。
     * HyperOS 的超級島 / 控制中心會把它顯示成媒體控制，實體音量鍵與系統音量滑桿也會直接調整接收端音量，
     * 不依賴焦點通知 JSON 欄位是否正確。
     */
    private fun setupMediaSession() {
        try {
            val ms = MediaSession(this, "HyperCast")
            val vp = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, 50) {
                override fun onSetVolumeTo(volume: Int) { AirPlayStore.setVolume(volume / 100f) }
                override fun onAdjustVolume(direction: Int) {
                    when {
                        direction > 0 -> AirPlayStore.adjust(+0.06f)
                        direction < 0 -> AirPlayStore.adjust(-0.06f)
                    }
                }
            }
            ms.setCallback(object : MediaSession.Callback() {
                override fun onPause() { AirPlayStore.setMuted(true) }
                override fun onPlay() { AirPlayStore.setMuted(false) }
                override fun onStop() { stopCast(null) }
            })
            ms.setPlaybackToRemote(vp)
            mediaSession = ms
            volProvider = vp
        } catch (e: Exception) {
            DiagLog.d("CAST", "MediaSession 建立失敗：${e.message}")
        }
    }

    private fun updateMediaSession(s: UiState, active: Boolean) {
        val ms = mediaSession ?: return
        try {
            val pct = if (s.muted) 0 else (s.volume * 100).roundToInt()
            volProvider?.currentVolume = pct
            ms.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, targetsTitle(s))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, streamingSubtitle(s))
                    .build()
            )
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                    .setState(
                        if (active && !s.muted) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                    )
                    .build()
            )
            ms.isActive = active
        } catch (_: Exception) {
        }
    }

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
        if (running) stopWorker()

        val targets = listOfNotNull(readTarget(i, "a_"), readTarget(i, "b_"))
        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data")

        AirPlayStore.update { it.copy(targets = targets, phase = Phase.Connecting, message = null) }
        DiagLog.refreshLocation(this)
        DiagLog.d("CAST", "要求連線：" + targets.joinToString(" ＋ ") {
            "${it.rx.name}｜型號 ${it.rx.model}｜${it.rx.host}:${it.rx.port}｜聲道 ${it.role.label}｜立體聲組=${it.pair}｜AirPlay2=${it.rx.ap2}"
        })

        // Android 14+：必須先進入 mediaProjection 前景狀態，才能取得 MediaProjection
        nm.cancel(NOTIF_ID_IDLE)
        updateMediaSession(AirPlayStore.state.value, true)
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
    private var lastNotifKey = ""

    /** 狀態改變時更新通知（含音量、聲道），並把音量送到每一台接收端。 */
    private fun launchNotificationLoop() {
        notifJob?.cancel()
        lastNotifKey = ""
        notifJob = scope.launch {
            AirPlayStore.state.collectLatest { s ->
                delay(500)   // 滑動音量時合併更新，避免通知被系統限流
                if (!running) return@collectLatest
                updateMediaSession(s, true)
                val key = "${s.phase}|${targetsTitle(s)}|${streamingSubtitle(s)}|${if (s.muted) 0 else (s.volume * 100).roundToInt()}"
                if (key != lastNotifKey) {      // 內容沒變就不重發，避免系統介面被一直刷新
                    lastNotifKey = key
                    nm.notify(NOTIF_ID, buildNotification(s))
                }
                // 聲道分配由 UI 改狀態，這裡同步到各目標
                s.targets.forEach { t -> slots.firstOrNull { it.rx.id == t.rx.id }?.role = t.role }
                slots.forEach { if (it.live) it.session?.setVolume(s.volume, s.muted) }
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
            val enc = arrayOfNulls<ByteArray>(Role.values().size)
            var frames = 0L
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
                    var i = 0
                    while (i + 1 < buf.size) {
                        val v = Math.abs((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF))
                        if (v > peak) peak = v
                        i += 2
                    }
                    val now = System.nanoTime()
                    if (now - lastLog > 10_000_000_000L) {
                        DiagLog.d("CAST", "串流中：已送出 $frames 幀（${slots.count { it.live }} 台），近 10 秒最大振幅 $peak/32768（0 代表被擷取到的是靜音）")
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
        try { mediaSession?.isActive = false } catch (_: Exception) {}
        AirPlayStore.update {
            it.copy(
                phase = if (message != null && message != "串流已結束") Phase.Error else Phase.Idle,
                targets = emptyList(),
                message = message,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIF_ID)
        // 使用者自己中斷：留下一則「已中斷 · 連線」通知，HyperIsland 上可以一鍵重新連線
        if (message == null) postIdleNotification()
        stopSelf()
    }

    override fun onDestroy() {
        stopWorker()
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 通知

    private fun pi(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, CastService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

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
            .setStyle(
                // 標準 MediaStyle：HyperOS 自己會把它顯示成媒體控制 / 超級島，不需要自訂 RemoteViews
                Notification.MediaStyle().apply {
                    mediaSession?.let { setMediaSession(it.sessionToken) }
                    setShowActionsInCompactView(0, 1, 2)
                }
            )
            .setContentIntent(open)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .addAction(act(R.drawable.ic_vol_down, "音量-", ACTION_VOL_DOWN))
            .addAction(act(R.drawable.ic_vol_up, "音量+", ACTION_VOL_UP))
            .addAction(act(R.drawable.ic_stop, "中斷", ACTION_STOP))

        HyperIsland.apply(
            this, b,
            HyperIsland.Info(
                mode = if (connecting) HyperIsland.Mode.Connecting else HyperIsland.Mode.Streaming,
                title = title, subtitle = sub, volumePct = pct, muted = s.muted,
                volDown = pi(ACTION_VOL_DOWN), volUp = pi(ACTION_VOL_UP),
                mute = pi(ACTION_MUTE), stop = pi(ACTION_STOP),
            )
        )
        return b.build()
    }

    /** 中斷後的「已中斷 · 連線」通知（非前景、可滑掉、2 分鐘後自動消失）。 */
    private fun postIdleNotification() {
        val last = LastStore.load(this)
        if (last.isEmpty()) return
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
        HyperIsland.apply(
            this, b,
            HyperIsland.Info(
                mode = HyperIsland.Mode.Idle, title = names, subtitle = "已中斷 · 點「連線」重新連到上次的裝置",
                volumePct = 0, muted = false, connect = reconnect, connectIsActivity = true,
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

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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.roundToInt

/** 擷取系統播放音訊（立體聲 44.1kHz）→ ALAC → AirPlay，並維護狀態列 / HyperIsland 通知。 */
class CastService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager

    @Volatile private var running = false
    private var worker: Thread? = null
    private var session: CastSession? = null
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "AirPlay 串流狀態", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCast(intent ?: return START_NOT_STICKY)
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

        val rx = Receiver(
            id = i.getStringExtra("id") ?: "",
            name = i.getStringExtra("name") ?: "AirPlay",
            host = i.getStringExtra("host") ?: return stopSelf(),
            port = i.getIntExtra("port", 7000),
            et = i.getStringExtra("et") ?: "0,1",
            password = i.getBooleanExtra("pw", false),
            model = i.getStringExtra("model") ?: "",
            ap2 = i.getBooleanExtra("ap2", false),
        )
        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data")

        AirPlayStore.update { it.copy(selected = rx, phase = Phase.Connecting, message = null) }
        DiagLog.refreshLocation(this)
        DiagLog.d("CAST", "要求連線：${rx.name}｜型號 ${rx.model}｜${rx.host}:${rx.port}｜et=${rx.et}｜pw=${rx.password}｜AirPlay2=${rx.ap2}")

        // Android 14+：必須先進入 mediaProjection 前景狀態，才能取得 MediaProjection
        startForeground(NOTIF_ID, buildNotification(AirPlayStore.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (data == null) {
            stopCast("沒有螢幕音訊擷取授權")
            return
        }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (running) stopCast("系統已停止音訊擷取") }
        }, main)
        projection = mp

        running = true
        launchNotificationLoop()
        worker = Thread({ runCast(rx, mp) }, "cast-worker").also { it.start() }
    }

    private var notifJob: kotlinx.coroutines.Job? = null

    /** 狀態改變時更新通知（含音量），並把音量送到接收端。 */
    private fun launchNotificationLoop() {
        notifJob?.cancel()
        notifJob = scope.launch {
            AirPlayStore.state.collectLatest { s ->
                delay(120)   // 滑動音量時合併更新，避免通知被系統限流
                nm.notify(NOTIF_ID, buildNotification(s))
                session?.setVolume(s.volume, s.muted)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runCast(rx: Receiver, mp: MediaProjection) {
        var error: String? = null
        try {
            val s: CastSession = if (!rx.ap2) RaopSession(rx) else {
                fun gk(r: Receiver) = r.groupId.uppercase().take(8)
                val all = AirPlayStore.state.value.receivers
                DiagLog.d("CAST", "組合比對：選擇 ${rx.name}（群組 ${gk(rx).ifBlank { "無" }}）；候選 " +
                    all.joinToString { "${it.name}[${it.model}|${gk(it).ifBlank { "無" }}|ap2=${it.ap2}]" })
                val members = if (gk(rx).isBlank()) emptyList() else
                    all.filter { it.ap2 && gk(it) == gk(rx) && it.id != rx.id && !it.model.startsWith("AppleTV") }
                if (members.isEmpty()) com.hypercast.airplay.ap2.Ap2Session(rx) else GroupSession(rx, members)
            }
            session = s
            val tConn = System.nanoTime()
            s.connect()
            DiagLog.d("CAST", "接收端連線成功（${(System.nanoTime() - tConn) / 1_000_000} ms），開始擷取系統音訊")
            val st = AirPlayStore.state.value
            s.setVolume(st.volume, st.muted)

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

            val buf = ByteArray(Alac.FRAME_BYTES)
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
                if (running) {
                    s.sendFrame(Alac.encode(buf))
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
                            DiagLog.d("CAST", "串流中：已送出 $frames 幀，近 10 秒最大振幅 $peak/32768（0 代表被擷取到的是靜音）")
                            peak = 0
                            lastLog = now
                        }
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
        session?.let { s -> Thread { s.close() }.start() }
        session = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker = null
    }

    private fun stopCast(message: String?) {
        DiagLog.d("CAST", "停止串流：${message ?: "使用者中斷"}")
        stopWorker()
        AirPlayStore.update {
            it.copy(
                phase = if (message != null && message != "串流已結束") Phase.Error else Phase.Idle,
                selected = null,
                message = message,
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.cancel(NOTIF_ID)
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

    private fun buildNotification(s: UiState): Notification {
        val title = when (s.phase) {
            Phase.Connecting -> "正在連線…"
            Phase.Streaming -> s.selected?.name ?: "AirPlay"
            else -> "HyperCast"
        }
        val sub = when (s.phase) {
            Phase.Connecting -> s.selected?.name ?: ""
            Phase.Streaming -> if (s.selected?.ap2 == true) "串流中 · AirPlay 2 · 立體聲" else "串流中 · 立體聲 · ALAC"
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

        HyperIsland.apply(
            this, b,
            HyperIsland.Info(
                title = title, subtitle = sub, volumePct = pct, muted = s.muted,
                volDown = pi(ACTION_VOL_DOWN), volUp = pi(ACTION_VOL_UP),
                mute = pi(ACTION_MUTE), stop = pi(ACTION_STOP),
            )
        )
        return b.build()
    }

    companion object {
        private const val CH = "airplay_status"
        private const val NOTIF_ID = 4242
        const val ACTION_START = "com.hypercast.START"
        const val ACTION_STOP = "com.hypercast.STOP"
        const val ACTION_VOL_UP = "com.hypercast.VOL_UP"
        const val ACTION_VOL_DOWN = "com.hypercast.VOL_DOWN"
        const val ACTION_MUTE = "com.hypercast.MUTE"

        fun start(ctx: Context, rx: Receiver, resultCode: Int, data: Intent) {
            val i = Intent(ctx, CastService::class.java).setAction(ACTION_START)
                .putExtra("id", rx.id).putExtra("name", rx.name).putExtra("host", rx.host)
                .putExtra("port", rx.port).putExtra("et", rx.et).putExtra("pw", rx.password)
                .putExtra("model", rx.model).putExtra("ap2", rx.ap2).putExtra("code", resultCode).putExtra("data", data)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CastService::class.java).setAction(ACTION_STOP))
        }
    }
}

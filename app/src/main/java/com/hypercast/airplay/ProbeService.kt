// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Beta4「通知被擋」診斷實驗用的最小前景服務：
 *  - 階段 A：只取得螢幕擷取授權（MediaProjection），沒有音訊擷取、沒有串流、沒有自訂通知。
 *  - 階段 B：再啟動音訊擷取（AudioRecord，設定和串流時相同），仍然不連線、不送出任何聲音。
 * 使用者在每個階段下拉通知欄檢查，就能分辨「是螢幕擷取本身讓系統隱藏通知」還是「HyperCast 的通知 / 串流造成」。
 */
class ProbeService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    @Volatile private var reading = false
    private var closed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_AUDIO -> beginAudio()
            ACTION_STOP -> finish()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(i: Intent) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "通知診斷實驗", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle("通知診斷實驗進行中")
            .setContentText("沒有串流、沒有送出任何聲音")
            .build()
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = i.getIntExtra("code", 0)
        val data = i.getParcelableExtra<Intent>("data")
        if (data == null) {
            fail("沒有螢幕擷取授權")
            return
        }
        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (ProbeStore.state.value.stage != ProbeStage.Done) ProbeStore.set(ProbeStage.Idle, "系統已停止螢幕擷取，實驗中止")
                    finish()
                }
            }, Handler(Looper.getMainLooper()))
            projection = mp
            ProbeStore.set(ProbeStage.A)
            DiagLog.d("PROBE", "階段 A：MediaProjection 已啟動（只有授權：沒有音訊擷取、沒有畫面擷取、沒有串流）")
            DiagCollector.privacyState(this, "實驗 階段A")
        } catch (e: Exception) {
            fail("啟動螢幕擷取失敗：${e.javaClass.simpleName}")
        }
    }

    private fun beginAudio() {
        val mp = projection ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ProbeStore.set(ProbeStage.Done, "沒有錄音權限，略過階段 B")
            finish()
            return
        }
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min, 4096) * 2)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            rec.startRecording()
            recorder = rec
            reading = true
            Thread({
                val buf = ByteArray(4096)
                while (reading) {
                    if (rec.read(buf, 0, buf.size) < 0) break
                }
            }, "probe-audio").start()
            ProbeStore.set(ProbeStage.B)
            DiagLog.d("PROBE", "階段 B：音訊擷取（AudioRecord）已啟動，設定和串流時相同，但沒有連線、沒有送出聲音")
            DiagCollector.privacyState(this, "實驗 階段B")
        } catch (e: Exception) {
            fail("啟動音訊擷取失敗：${e.javaClass.simpleName}")
        }
    }

    private fun fail(msg: String) {
        ProbeStore.set(ProbeStage.Idle, msg)
        DiagLog.d("PROBE", msg)
        finish()
    }

    private fun finish() {
        if (closed) return
        closed = true
        reading = false
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    companion object {
        private const val CH = "probe"
        private const val NOTIF_ID = 4243
        const val ACTION_START = "com.hypercast.airplay.PROBE_START"
        const val ACTION_AUDIO = "com.hypercast.airplay.PROBE_AUDIO"
        const val ACTION_STOP = "com.hypercast.airplay.PROBE_STOP"

        fun start(ctx: Context, code: Int, data: Intent) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ProbeService::class.java).setAction(ACTION_START).putExtra("code", code).putExtra("data", data),
            )
        }

        fun audio(ctx: Context) {
            ctx.startService(Intent(ctx, ProbeService::class.java).setAction(ACTION_AUDIO))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ProbeService::class.java).setAction(ACTION_STOP))
        }
    }
}

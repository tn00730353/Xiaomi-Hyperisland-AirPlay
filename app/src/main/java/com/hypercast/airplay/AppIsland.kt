// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * 沒有在串流時的 HyperIsland：開啟 App 就顯示「HyperCast · 未連接」，連線後交給 CastService 的通知接手。
 * 遵守設定中的 HyperIsland 顯示方式（關閉 / 短暫 / 持續）。
 */
object AppIsland {
    private const val CH = "airplay_status"
    private const val ID = 4244

    fun show(ctx: Context) {
        val app = ctx.applicationContext
        PrefsStore.init(app)
        val mode = PrefsStore.state.value.island
        if (mode == IslandMode.Off) { hide(app); return }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            VolumeStore.init(app)
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "AirPlay 串流狀態", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null); enableVibration(false); setShowBadge(false)
                }
            )
            val open = PendingIntent.getActivity(
                app, 78, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val brief = mode == IslandMode.Brief
            val b = Notification.Builder(app, CH)
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentTitle("HyperCast")
                .setContentText("未連接")
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setAutoCancel(true)
                // 「未連接」不能一直留著：持續顯示模式以前沒有逾時，會變成滑不掉的通知
                .setTimeoutAfter(if (brief) 8_000L else 30_000L)
            HyperIsland.apply(
                app, b,
                HyperIsland.Info(
                    mode = HyperIsland.Mode.Disconnected, name = "HyperCast", iconRes = R.drawable.ic_airplay,
                    volumePct = (VolumeStore.load() * 100).roundToInt(), muted = false,
                ),
                brief,
            )
            nm.notify(ID, b.build())
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ hide(app) }, if (brief) 8_500L else 30_500L)
        } catch (_: Exception) {
        }
    }

    fun hide(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(ID) } catch (_: Exception) {}
    }
}

// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 閒置時（沒有串流、App 開著）在 HyperIsland 短暫顯示「未連接」。
 *
 * Beta 1 把這個整個拿掉了（舊版會一直掛著、讓其他 App 的通知被擠掉或清不掉）。
 * Beta 2 改成「選用」，而且很克制：
 *  - 預設關閉（設定 → HyperIsland → 閒置時顯示「未連接」），只在選了「焦點通知」模式、HyperOS 3 以上才會動作。
 *  - 島只彈出約 6 秒（islandTimeout），通知本身 15 秒後自動移除，點一下或滑掉就消失。
 *  - 一開始串流、或離開 App 就立刻清掉。
 */
object AppIsland {
    private const val ID = 4244
    private const val CH = "airplay_status"
    private var lastShownAt = 0L

    fun show(ctx: Context) {
        val pr = PrefsStore.state.value
        val ph = AirPlayStore.state.value.phase
        if (!pr.idleIsland || !pr.island.isFocus || ph == Phase.Streaming || ph == Phase.Connecting ||
            HyperIsland.protocol(ctx) < 3 || !HyperIsland.canShow(ctx)
        ) {
            hide(ctx)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastShownAt < 3_000L) return          // 避免畫面反覆進出時一直彈
        lastShownAt = now
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH, "AirPlay 串流狀態", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null); enableVibration(false); setShowBadge(false)
                }
            )
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = Notification.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentTitle("HyperCast")
                .setContentText("未連接")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(15_000)
            HyperIsland.apply(
                ctx, b,
                HyperIsland.Info(HyperIsland.Mode.Disconnected, "HyperCast", R.drawable.ic_airplay, 0, false),
                brief = true, seconds = pr.focusSeconds,
            )
            nm.notify(ID, b.build())
        } catch (_: Exception) {
        }
    }

    fun hide(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(ID) } catch (_: Exception) {}
    }
}

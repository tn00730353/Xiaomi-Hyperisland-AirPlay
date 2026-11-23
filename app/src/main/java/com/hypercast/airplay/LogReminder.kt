// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 開始記錄日誌 5 分鐘後，提醒使用者回到 App 儲存或傳送日誌。 */
object LogReminder {
    private const val CH = "log_reminder"
    private const val ID = 4245
    const val DELAY_MS = 5 * 60 * 1000L

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 91, Intent(ctx, LogReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        try {
            app.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + DELAY_MS, pi(app))
        } catch (_: Exception) {
        }
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        try {
            app.getSystemService(AlarmManager::class.java).cancel(pi(app))
            app.getSystemService(NotificationManager::class.java).cancel(ID)
        } catch (_: Exception) {
        }
    }

    fun post(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "日誌提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                app, 92, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                ID,
                Notification.Builder(app, CH)
                    .setSmallIcon(R.drawable.ic_airplay)
                    .setContentTitle("日誌已記錄 5 分鐘")
                    .setContentText("回到 HyperCast，到「設定 → 意見回饋」儲存或傳送日誌")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {
        }
    }
}

class LogReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DiagLog.init(context)
        if (DiagLog.enabled) LogReminder.post(context)
    }
}

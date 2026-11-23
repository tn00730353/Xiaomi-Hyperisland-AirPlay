// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.app.NotificationManager
import android.content.Context

/**
 * 舊版會在沒串流時發一則帶焦點參數的「未連接」通知，那種通知會讓其他 App 的通知被隱藏 / 清不掉，已移除。
 * 這裡只保留 [hide]，用來清掉舊版殘留的通知；[show] 不再發任何通知。
 */
object AppIsland {
    private const val ID = 4244

    fun show(ctx: Context) = hide(ctx)

    fun hide(ctx: Context) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(ID) } catch (_: Exception) {}
    }
}

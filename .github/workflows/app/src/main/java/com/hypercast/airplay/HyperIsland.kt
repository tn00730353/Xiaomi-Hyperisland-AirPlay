// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小米 HyperOS「焦點通知 / HyperIsland（超級島）」整合。
 *
 * 做法：在一般通知的 extras 加入
 *   - miui.focus.param    : JSON（island 摘要、展開內容、進度、按鈕）
 *   - miui.focus.pics     : Bundle<Icon>，JSON 以 "miui.focus.pic_xxx" 引用
 *   - miui.focus.actions  : Bundle<Notification.Action>，JSON 以 "miui.focus.action_xxx" 引用
 * 非小米裝置或未開啟焦點通知權限時，這些 extras 會被系統忽略，通知仍以一般方式顯示。
 *
 * ⚠ 欄位名稱以 Xiaomi HyperOS 開發者文件為準；若你的機型版本有差異，只需調整 [buildParamJson]。
 */
object HyperIsland {
    private const val KEY_PARAM = "miui.focus.param"
    private const val KEY_PICS = "miui.focus.pics"
    private const val KEY_ACTIONS = "miui.focus.actions"

    /** 系統是否支援焦點通知（HyperOS 2 = 1、HyperOS 3 超級島 = 3）。 */
    fun protocol(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, "notification_focus_protocol", 0)
    } catch (_: Exception) {
        0
    }

    fun isSupported(ctx: Context) = protocol(ctx) >= 1

    /** 使用者是否在系統設定中允許本 App 顯示焦點通知。查詢失敗時視為允許。 */
    fun canShow(ctx: Context): Boolean = try {
        val extras = Bundle().apply { putString("package", ctx.packageName) }
        val r = ctx.contentResolver.call(
            Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, extras
        )
        r?.getBoolean("canShowFocus", true) ?: true
    } catch (_: Exception) {
        true
    }

    class Info(
        val title: String,
        val subtitle: String,
        val volumePct: Int,
        val muted: Boolean,
        val volDown: PendingIntent,
        val volUp: PendingIntent,
        val mute: PendingIntent,
        val stop: PendingIntent,
    )

    fun apply(ctx: Context, builder: Notification.Builder, info: Info) {
        if (!isSupported(ctx)) return
        try {
            val extras = Bundle()
            extras.putString(KEY_PARAM, buildParamJson(info))

            val pics = Bundle().apply {
                putParcelable("miui.focus.pic_ticker", Icon.createWithResource(ctx, R.drawable.ic_airplay))
                putParcelable("miui.focus.pic_icon", Icon.createWithResource(ctx, R.drawable.ic_airplay))
                putParcelable("miui.focus.pic_vol_down", Icon.createWithResource(ctx, R.drawable.ic_vol_down))
                putParcelable("miui.focus.pic_vol_up", Icon.createWithResource(ctx, R.drawable.ic_vol_up))
                putParcelable("miui.focus.pic_mute", Icon.createWithResource(ctx, R.drawable.ic_vol_off))
                putParcelable("miui.focus.pic_stop", Icon.createWithResource(ctx, R.drawable.ic_stop))
            }
            extras.putBundle(KEY_PICS, pics)

            fun act(iconRes: Int, title: String, pi: PendingIntent) =
                Notification.Action.Builder(Icon.createWithResource(ctx, iconRes), title, pi).build()

            val actions = Bundle().apply {
                putParcelable("miui.focus.action_vol_down", act(R.drawable.ic_vol_down, "音量-", info.volDown))
                putParcelable("miui.focus.action_vol_up", act(R.drawable.ic_vol_up, "音量+", info.volUp))
                putParcelable("miui.focus.action_mute", act(R.drawable.ic_vol_off, "靜音", info.mute))
                putParcelable("miui.focus.action_stop", act(R.drawable.ic_stop, "中斷", info.stop))
            }
            extras.putBundle(KEY_ACTIONS, actions)

            builder.addExtras(extras)
        } catch (_: Exception) {
            // 焦點通知失敗時，退回一般通知
        }
    }

    private fun text(title: String, content: String? = null, front: String? = null) = JSONObject().apply {
        front?.let { put("frontTitle", it) }
        put("title", title)
        content?.let { put("content", it) }
        put("useHighLight", false)
    }

    private fun action(pic: String, title: String, key: String) = JSONObject().apply {
        put("actionIcon", pic)
        put("actionTitle", title)
        put("actionIntentType", 3)       // 3 = Service（PendingIntent.getService）
        put("actionIntent", key)
    }

    private fun buildParamJson(i: Info): String {
        val volText = if (i.muted) "靜音" else "${i.volumePct}%"
        val island = JSONObject().apply {
            put("islandProperty", 1)
            put("bigIslandArea", JSONObject().apply {
                put("imageTextInfoLeft", JSONObject().apply {
                    put("type", 1)
                    put("picInfo", JSONObject().apply { put("type", 1); put("pic", "miui.focus.pic_icon") })
                    put("textInfo", text(i.title))
                })
                put("imageTextInfoRight", JSONObject().apply {
                    put("type", 2)
                    put("textInfo", text(volText))
                })
            })
            put("smallIslandArea", JSONObject().apply {
                put("picInfo", JSONObject().apply { put("type", 1); put("pic", "miui.focus.pic_icon") })
            })
        }
        val v2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "airplay_cast")
            put("enableFloat", false)    // 調音量時不要一直彈出
            put("updatable", true)
            put("ticker", i.title)
            put("tickerPic", "miui.focus.pic_ticker")
            put("param_island", island)
            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", i.title)
                put("content", "${i.subtitle} · 音量 $volText")
            })
            put("progressInfo", JSONObject().apply {
                put("progress", i.volumePct)
                put("colorProgress", "#7AA2FF")
                put("colorProgressEnd", "#B45CFF")
            })
            put("actions", JSONArray().apply {
                put(action("miui.focus.pic_vol_down", "音量-", "miui.focus.action_vol_down"))
                put(action("miui.focus.pic_mute", "靜音", "miui.focus.action_mute"))
                put(action("miui.focus.pic_vol_up", "音量+", "miui.focus.action_vol_up"))
                put(action("miui.focus.pic_stop", "中斷", "miui.focus.action_stop"))
            })
        }
        return JSONObject().put("param_v2", v2).toString()
    }
}

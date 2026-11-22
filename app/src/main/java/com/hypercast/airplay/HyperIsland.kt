// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小米 HyperOS「焦點通知 / HyperIsland（超級島）」整合，外觀走 HyperOS 4 的液態玻璃風格。
 *
 * 做法：在一般通知的 extras 加入
 *   - miui.focus.param    : JSON（island 摘要、展開內容、進度、按鈕）
 *   - miui.focus.pics     : Bundle<Icon>，JSON 以 "miui.focus.pic_xxx" 引用
 *   - miui.focus.actions  : Bundle<Notification.Action>，JSON 以 "miui.focus.action_xxx" 引用
 * 非小米裝置或未開啟焦點通知權限時，這些 extras 會被系統忽略，通知仍以一般方式顯示。
 *
 * 玻璃的模糊、折射與動態光效由 HyperOS 系統負責繪製；App 能控制的是圖示（這裡全部改成玻璃圓鈕點陣圖）、
 * 配色與按鈕配置。
 *
 * ⚠ 欄位名稱以 Xiaomi HyperOS 開發者文件為準；若你的機型版本有差異，只需調整 [buildParamJson]。
 */
object HyperIsland {
    private const val KEY_PARAM = "miui.focus.param"
    private const val KEY_PICS = "miui.focus.pics"
    private const val KEY_ACTIONS = "miui.focus.actions"

    enum class Mode { Streaming, Connecting, Idle }

    /** 系統是否支援焦點通知（HyperOS 2 = 1、HyperOS 3 / 4 超級島 = 3）。 */
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
        val mode: Mode,
        val title: String,
        val subtitle: String,
        val volumePct: Int,
        val muted: Boolean,
        val volDown: PendingIntent? = null,
        val volUp: PendingIntent? = null,
        val mute: PendingIntent? = null,
        val stop: PendingIntent? = null,
        val connect: PendingIntent? = null,
        /** connect 是開啟 Activity（true）還是 Service（false）。 */
        val connectIsActivity: Boolean = true,
    )

    // 液態玻璃配色
    private const val GLASS = 0xFFFFFFFF.toInt()
    private val ACCENT = Color.parseColor("#B79CFF")
    private val DANGER = Color.parseColor("#FF5C7A")
    private val OK = Color.parseColor("#7FE3C0")

    fun apply(ctx: Context, builder: Notification.Builder, info: Info) {
        if (!isSupported(ctx)) return
        try {
            val extras = Bundle()
            extras.putString(KEY_PARAM, buildParamJson(info))

            val pics = Bundle().apply {
                putParcelable("miui.focus.pic_ticker", GlassIcons.glass(ctx, R.drawable.ic_airplay))
                putParcelable("miui.focus.pic_icon", GlassIcons.glass(ctx, R.drawable.ic_airplay, base = ACCENT))
                // 按鈕圖示（JSON 的 actionIcon 以這些 key 引用）
                putParcelable("miui.focus.pic_vol_down", GlassIcons.glass(ctx, R.drawable.ic_vol_down))
                putParcelable("miui.focus.pic_vol_up", GlassIcons.glass(ctx, R.drawable.ic_vol_up))
                putParcelable("miui.focus.pic_mute", GlassIcons.glass(ctx, R.drawable.ic_vol_off, base = if (info.muted) ACCENT else GLASS))
                putParcelable("miui.focus.pic_stop", GlassIcons.glass(ctx, R.drawable.ic_stop, base = DANGER))
                putParcelable("miui.focus.pic_connect", GlassIcons.glass(ctx, R.drawable.ic_airplay, base = OK))
            }
            extras.putBundle(KEY_PICS, pics)

            fun act(icon: android.graphics.drawable.Icon, title: String, pi: PendingIntent) =
                Notification.Action.Builder(icon, title, pi).build()

            val actions = Bundle().apply {
                info.volDown?.let { putParcelable("miui.focus.action_vol_down", act(GlassIcons.glass(ctx, R.drawable.ic_vol_down), "音量-", it)) }
                info.volUp?.let { putParcelable("miui.focus.action_vol_up", act(GlassIcons.glass(ctx, R.drawable.ic_vol_up), "音量+", it)) }
                info.mute?.let {
                    putParcelable(
                        "miui.focus.action_mute",
                        act(GlassIcons.glass(ctx, R.drawable.ic_vol_off, base = if (info.muted) ACCENT else GLASS), if (info.muted) "取消靜音" else "靜音", it)
                    )
                }
                info.stop?.let { putParcelable("miui.focus.action_stop", act(GlassIcons.glass(ctx, R.drawable.ic_stop, base = DANGER), "中斷", it)) }
                info.connect?.let { putParcelable("miui.focus.action_connect", act(GlassIcons.glass(ctx, R.drawable.ic_airplay, base = OK), "連線", it)) }
            }
            extras.putBundle(KEY_ACTIONS, actions)

            builder.addExtras(extras)
        } catch (_: Exception) {
            // 焦點通知失敗時，退回一般通知
        }
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun text(title: String, content: String? = null) = JSONObject().apply {
        put("title", title)
        content?.let { put("content", it) }
        put("useHighLight", false)
    }

    private fun action(pic: String, title: String, key: String, activity: Boolean, bg: Int, titleColor: Int = Color.WHITE) =
        JSONObject().apply {
            put("actionIcon", pic)
            put("actionTitle", title)
            put("action", key)                                // 對應 miui.focus.actions 內的 Notification.Action
            put("actionIntentType", if (activity) 1 else 3)   // 1 = Activity、3 = Service
            put("actionIntent", key)
            put("actionBgColor", hex(bg))                     // 半透明玻璃底（系統決定最終混色）
            put("actionTitleColor", hex(titleColor))
        }

    private fun buildParamJson(i: Info): String {
        val volText = if (i.muted) "靜音" else "${i.volumePct}%"
        val idle = i.mode == Mode.Idle
        val streaming = i.mode == Mode.Streaming

        val island = JSONObject().apply {
            put("islandProperty", 1)
            put("highlightColor", hex(if (idle) OK else ACCENT))
            put("bigIslandArea", JSONObject().apply {
                put("imageTextInfoLeft", JSONObject().apply {
                    put("type", 1)
                    put("picInfo", JSONObject().apply { put("type", 1); put("pic", "miui.focus.pic_icon") })
                    put("textInfo", text(i.title))
                })
                put("imageTextInfoRight", JSONObject().apply {
                    put("type", 2)
                    put("textInfo", text(
                        when (i.mode) {
                            Mode.Streaming -> volText
                            Mode.Connecting -> "連線中"
                            Mode.Idle -> "已中斷"
                        }
                    ))
                })
            })
            put("smallIslandArea", JSONObject().apply {
                put("picInfo", JSONObject().apply { put("type", 1); put("pic", "miui.focus.pic_icon") })
            })
        }

        val v2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "airplay_cast")
            put("enableFloat", idle)           // 串流中調音量不要一直彈出；中斷時彈出一次讓你可以直接重新連線
            put("islandFirstFloat", idle)
            put("updatable", true)
            put("ticker", i.title)
            put("tickerPic", "miui.focus.pic_ticker")
            put("param_island", island)
            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", i.title)
                put("content", if (streaming) "${i.subtitle} · 音量 $volText" else i.subtitle)
                put("colorTitle", "#FFFFFF")
                put("colorContent", "#FFFFFF")
            })
            if (streaming) {
                put("progressInfo", JSONObject().apply {
                    put("progress", i.volumePct)
                    put("colorProgress", "#8EC5FF")
                    put("colorProgressEnd", "#B79CFF")
                })
            }
            put("actions", JSONArray().apply {
                val glassBg = 0x33FFFFFF
                if (streaming) {
                    i.volDown?.let { put(action("miui.focus.pic_vol_down", "音量-", "miui.focus.action_vol_down", false, glassBg)) }
                    i.mute?.let {
                        put(action("miui.focus.pic_mute", if (i.muted) "取消靜音" else "靜音", "miui.focus.action_mute", false,
                            if (i.muted) ACCENT else glassBg))
                    }
                    i.volUp?.let { put(action("miui.focus.pic_vol_up", "音量+", "miui.focus.action_vol_up", false, glassBg)) }
                    i.stop?.let { put(action("miui.focus.pic_stop", "中斷", "miui.focus.action_stop", false, DANGER)) }
                } else if (idle) {
                    i.connect?.let { put(action("miui.focus.pic_connect", "連線", "miui.focus.action_connect", i.connectIsActivity, OK)) }
                }
            })
        }
        return JSONObject().put("param_v2", v2).toString()
    }
}

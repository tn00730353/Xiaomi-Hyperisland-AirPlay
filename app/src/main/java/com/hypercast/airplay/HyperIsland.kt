// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import org.json.JSONObject

/**
 * 小米 HyperOS「焦點通知 / HyperIsland（超級島）」整合——只做「顯示」，沒有任何控制按鈕。
 *
 * 顯示內容：連線狀態（連線中 / 已連線 / 播放中）、目前音量、連接的裝置圖示與名稱；串流期間常駐在島上。
 *
 * 做法：在一般通知的 extras 加入
 *   - miui.focus.param : JSON（island 摘要、展開內容、音量進度）
 *   - miui.focus.pics  : Bundle<Icon>，JSON 以 "miui.focus.pic_xxx" 引用
 * 非小米裝置或未開啟焦點通知權限時，這些 extras 會被系統忽略，通知仍以一般方式顯示。
 *
 * 只使用官方文件列出的欄位（https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131）：
 * islandFirstFloat（首次出現是否自動展開；這裡固定關閉，只出摘要態）、updatable（持續性通知）、enableFloat。
 *
 * 注意：串流期間若其他 App 的通知被隱藏、滑不掉，不是焦點通知造成的，而是 Android 15 起的「螢幕分享保護」
 * （MediaProjection 啟動就會觸發）。解法見設定頁「通知被隱藏？」。
 */
object HyperIsland {
    private const val KEY_PARAM = "miui.focus.param"
    private const val KEY_PICS = "miui.focus.pics"

    enum class Mode { Connecting, Connected, Playing, Idle, Disconnected }

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
        val name: String,        // 連接的裝置名稱
        val iconRes: Int,        // 裝置圖示（HomePod / HomePod mini / Apple TV / AirPlay）
        val volumePct: Int,
        val muted: Boolean,
    )

    private val ACCENT = Color.parseColor("#B79CFF")
    private val OK = Color.parseColor("#7FE3C0")

    fun apply(ctx: Context, builder: Notification.Builder, info: Info) {
        if (!isSupported(ctx)) return
        try {
            val extras = Bundle()
            extras.putString(KEY_PARAM, buildParamJson(info))
            DiagLog.d("ISLAND", "附上焦點參數：狀態=${info.mode}｜常駐｜協定=${protocol(ctx)}")
            extras.putBundle(KEY_PICS, Bundle().apply {
                putParcelable("miui.focus.pic_ticker", GlassIcons.glass(ctx, info.iconRes))
                putParcelable("miui.focus.pic_icon", GlassIcons.glass(ctx, info.iconRes, base = ACCENT))
            })
            builder.addExtras(extras)
        } catch (_: Exception) {
            // 焦點通知失敗時，退回一般通知
        }
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    private fun text(title: String) = JSONObject().apply {
        put("title", title)
        put("useHighLight", false)
    }

    private fun statusText(m: Mode) = when (m) {
        Mode.Connecting -> "連線中"
        Mode.Connected -> "已連線"
        Mode.Playing -> "播放中"
        Mode.Idle -> "已中斷"
        Mode.Disconnected -> "未連接"
    }

    private fun buildParamJson(i: Info): String {
        val vol = if (i.muted) "靜音" else "${i.volumePct}%"
        val status = statusText(i.mode)
        val live = i.mode == Mode.Connected || i.mode == Mode.Playing

        val island = JSONObject().apply {
            put("islandProperty", 1)
            put("highlightColor", hex(if (i.mode == Mode.Idle || i.mode == Mode.Disconnected) OK else ACCENT))
            put("bigIslandArea", JSONObject().apply {
                put("imageTextInfoLeft", JSONObject().apply {
                    put("type", 1)
                    put("picInfo", JSONObject().apply { put("type", 1); put("pic", "miui.focus.pic_icon") })
                    put("textInfo", text(i.name))
                })
                put("imageTextInfoRight", JSONObject().apply {
                    put("type", 2)
                    put("textInfo", text(if (live) "$status · $vol" else status))
                })
            })
            put("smallIslandArea", JSONObject().apply {
                put("picInfo", JSONObject().apply { put("type", 1); put("pic", "miui.focus.pic_icon") })
            })
        }

        val v2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "airplay_cast")
            put("islandFirstFloat", false)     // 不自動展開成大卡片，只出摘要態
            put("enableFloat", false)          // 不主動彈出，避免打斷使用者
            put("updatable", true)             // 持續性通知：串流期間常駐在島上
            put("ticker", "${i.name} · $status")
            put("tickerPic", "miui.focus.pic_ticker")
            put("param_island", island)
            put("baseInfo", JSONObject().apply {
                put("type", 2)
                put("title", i.name)
                put("content", if (live) "$status · 音量 $vol" else status)
                // 不指定文字顏色：由系統依淺色 / 深色通知欄自動決定（以前寫死白色，淺色通知欄上會看不見）
            })
            if (live) {
                put("progressInfo", JSONObject().apply {
                    put("progress", if (i.muted) 0 else i.volumePct)
                    put("colorProgress", "#8EC5FF")
                    put("colorProgressEnd", "#B79CFF")
                })
            }
        }
        return JSONObject().put("param_v2", v2).toString()
    }
}

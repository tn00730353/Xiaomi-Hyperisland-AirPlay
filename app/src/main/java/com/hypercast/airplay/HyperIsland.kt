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
 * 顯示內容：連線狀態（連線中 / 已連線 / 播放中 / 已中斷）、目前音量、連接的裝置圖示與名稱。
 *
 * 做法：在一般通知的 extras 加入
 *   - miui.focus.param : JSON（island 摘要、展開內容、音量進度）
 *   - miui.focus.pics  : Bundle<Icon>，JSON 以 "miui.focus.pic_xxx" 引用
 * 非小米裝置或未開啟焦點通知權限時，這些 extras 會被系統忽略，通知仍以一般方式顯示。
 *
 * v1.2.9：不再「永遠佔著島」。
 *  - 短暫顯示（預設）：islandTimeout = 6 秒，而且只在狀態 / 音量改變時才附上焦點參數（由 CastService 決定），
 *    其餘時間只剩一般通知。小米文件：沒設定消失時間時，島預設 1 小時才消失、通知卡片 12 小時，期間其他 App
 *    的島 / 通知會被擠到後面。
 *  - 持續顯示：（舊版寫的 islandPriority 不在小米官方文件的欄位清單裡，應該沒有作用，Beta 2 已移除。）
 *
 * Beta 2：只使用官方文件列出的欄位（https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131）：
 *  islandFirstFloat（首次出現是否自動展開，官方預設 true）、islandTimeout、dismissIsland、updatable、enableFloat。
 *  每個欄位都可在「設定 → HyperIsland → 焦點通知進階」調整，並寫進診斷日誌（ISLAND 類別）。
 *  - HyperOS 2（協定 1）的焦點通知會常駐狀態列，只有選「持續顯示」才使用。
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

    /** 短暫顯示時島停留的秒數；CastService 以此決定「同樣內容還要不要繼續附上焦點參數」。 */
    const val BRIEF_SECONDS = 6

    private val ACCENT = Color.parseColor("#B79CFF")
    private val OK = Color.parseColor("#7FE3C0")

    /** @param brief true = 短暫顯示（島自動收起）；false = 持續顯示。 */
    fun apply(
        ctx: Context, builder: Notification.Builder, info: Info, brief: Boolean,
        seconds: Int = BRIEF_SECONDS, dismiss: Boolean = false,
    ) {
        if (!isSupported(ctx)) return
        if (protocol(ctx) < 3 && brief) return      // HyperOS 2 的焦點通知會常駐狀態列，只有「持續顯示」才使用
        try {
            val extras = Bundle()
            extras.putString(KEY_PARAM, buildParamJson(info, brief, seconds, dismiss))
            val pr = PrefsStore.state.value
            DiagLog.d(
                "ISLAND",
                "附上焦點參數：狀態=${info.mode}｜短暫=$brief｜停留=${seconds}s｜dismissIsland=$dismiss｜" +
                    "islandFirstFloat=${pr.focusFirstFloat}｜updatable=${pr.focusUpdatable}｜協定=${protocol(ctx)}"
            )
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

    private fun buildParamJson(i: Info, brief: Boolean, seconds: Int, dismiss: Boolean): String {
        val pr = PrefsStore.state.value
        val vol = if (i.muted) "靜音" else "${i.volumePct}%"
        val status = statusText(i.mode)
        val live = i.mode == Mode.Connected || i.mode == Mode.Playing

        val island = JSONObject().apply {
            put("islandProperty", 1)
            if (brief) put("islandTimeout", seconds)         // 幾秒後島自動收起，不會一直佔著
            if (dismiss) put("dismissIsland", true)          // 明確要求收起摘要態（官方欄位）
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
            // Beta 2.1 修正：官方文件把 islandFirstFloat / timeout 列在「通知屬性」（param_v2 這一層），
            // 之前誤放進 param_island，系統不認得 → 一直套用官方預設 true（每次都展開成大卡片）。
            put("islandFirstFloat", pr.focusFirstFloat)
            // 官方 timeout（分鐘）預設 720＝焦點通知卡片會留 12 小時；短暫模式改成最少 1 分鐘
            if (brief) put("timeout", ((seconds + 59) / 60).coerceAtLeast(1))
            put("enableFloat", false)          // 不主動彈出，避免打斷使用者
            put("updatable", pr.focusUpdatable)
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

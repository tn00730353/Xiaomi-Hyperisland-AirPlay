// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context
import android.os.Build

/** 設定頁顯示的系統資訊。 */
object SystemInfo {

    private fun prop(key: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as? String) ?: ""
    } catch (_: Exception) {
        ""
    }

    fun rows(ctx: Context): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        rows += "Android 版本" to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"

        val osName = prop("ro.mi.os.version.name")          // 例如 OS2 / OS3
        val osBuild = prop("ro.mi.os.version.incremental")  // 例如 OS3.0.3.0.WNCMIXM
        if (osName.isNotBlank() || osBuild.isNotBlank()) {
            val ver = osName.removePrefix("OS").ifBlank { "" }
            rows += "系統版本" to ("Xiaomi HyperOS" + if (ver.isNotBlank()) " $ver" else "")
            if (osBuild.isNotBlank()) rows += "系統版本號" to osBuild
        } else {
            rows += "系統版本" to Build.DISPLAY
        }

        rows += "裝置" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        if (Build.VERSION.SECURITY_PATCH.isNotBlank()) rows += "安全性更新" to Build.VERSION.SECURITY_PATCH
        rows += "App 版本" to "HyperCast ${BuildConfig.VERSION_NAME}"
        return rows
    }

    /** HyperIsland（上島）狀態說明：依目前選的顯示方式。 */
    fun hyperIslandStatus(ctx: Context): String {
        val p = HyperIsland.protocol(ctx)
        val m = PrefsStore.state.value.island
        return when {
            m == IslandMode.Normal -> "一般通知（不使用焦點通知，也不建立媒體工作階段）"
            m == IslandMode.Media ->
                if (p <= 0) "媒體通知（非 HyperOS，沒有島，通知列照常顯示控制）" else "媒體通知（HyperOS 超級島協定 $p）"
            p <= 0 -> "這支手機不支援焦點通知（非 HyperOS 或系統過舊），目前等同「一般通知」"
            !HyperIsland.canShow(ctx) -> "系統支援焦點通知（協定 $p），但尚未允許本 App 顯示焦點通知"
            m == IslandMode.FocusBrief && p < 3 -> "HyperOS 2（協定 $p）的焦點通知會常駐狀態列，「短暫」不使用；請改選「持續」或「一般通知」"
            else -> "可使用（焦點通知協定 $p，純顯示、沒有按鈕）"
        }
    }
}

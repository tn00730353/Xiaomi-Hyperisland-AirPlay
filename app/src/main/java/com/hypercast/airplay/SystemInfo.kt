// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context
import android.os.Build

/** 設定頁顯示的系統資訊。 */
object SystemInfo {

    fun prop(key: String): String = try {
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

    /** HyperIsland（超級島）狀態說明。 */
    fun hyperIslandStatus(ctx: Context): String {
        val p = HyperIsland.protocol(ctx)
        return when {
            p <= 0 -> "這支手機不支援焦點通知（非 HyperOS 或系統過舊），只會顯示一般通知"
            !HyperIsland.canShow(ctx) -> "系統支援焦點通知（協定 $p），但尚未允許本 App 顯示焦點通知：系統設定 → 通知與控制中心 → HyperCast"
            else -> "可使用（焦點通知協定 $p）：串流期間常駐在超級島，純顯示、沒有按鈕"
        }
    }
}

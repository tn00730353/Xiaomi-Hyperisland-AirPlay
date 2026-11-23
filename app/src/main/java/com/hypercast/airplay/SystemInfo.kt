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

    /** HyperIsland（上島）狀態說明：使用標準媒體通知，不使用焦點通知。 */
    fun hyperIslandStatus(ctx: Context): String {
        val p = HyperIsland.protocol(ctx)
        return when {
            p <= 0 -> "標準媒體通知（非 HyperOS，不會有島，但通知列照常顯示控制）"
            else -> "標準媒體通知（HyperOS 超級島協定 $p；不使用焦點通知）"
        }
    }
}

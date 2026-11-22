// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context

/** 記住最後一次串流的裝置與聲道分配，讓 HyperIsland / 通知上的「連線」可以一鍵重新連線。 */
object LastStore {
    private const val FILE = "hypercast_prefs"
    private const val KEY = "last_targets"

    fun save(ctx: Context, targets: List<Target>) {
        if (targets.isEmpty()) return
        val v = targets.joinToString(",") { "${it.rx.id}|${it.rx.name.replace(",", " ").replace("|", " ")}|${it.role.name}" }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()
    }

    /** 回傳 (id, 名稱, 聲道)。 */
    fun load(ctx: Context): List<Triple<String, String, Role>> {
        val v = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return v.split(',').mapNotNull {
            val p = it.split('|')
            if (p.size < 3) null else Triple(p[0], p[1], Role.values().firstOrNull { r -> r.name == p[2] } ?: Role.Stereo)
        }
    }
}

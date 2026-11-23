// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** HyperIsland 顯示方式。 */
enum class IslandMode(val label: String, val desc: String) {
    Off("關閉", "不使用 HyperIsland，只顯示一般通知（不會影響其他通知）"),
    Brief("短暫顯示", "狀態或音量改變時彈出幾秒就收起，不會一直佔著島"),
    Always("持續顯示", "島一直顯示；可能讓其他 App 的通知無法在島上顯示"),
}

data class Prefs(
    val island: IslandMode = IslandMode.Brief,
    /** 手機音量鍵 → 同步加減接收端音量。 */
    val mirrorVolume: Boolean = true,
    /** 音量平衡：把不同歌曲的整體響度拉到接近，並限制爆音。 */
    val leveler: Boolean = false,
)

/** 使用者設定（UI 與前景服務共用）。 */
object PrefsStore {
    private const val FILE = "hypercast"
    val state = MutableStateFlow(Prefs())
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val island = IslandMode.values().firstOrNull { it.name == sp.getString("island_mode", null) } ?: IslandMode.Brief
        state.value = Prefs(
            island = island,
            mirrorVolume = sp.getBoolean("mirror_volume", true),
            leveler = sp.getBoolean("leveler", false),
        )
        loaded = true
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setIsland(ctx: Context, m: IslandMode) {
        state.value = state.value.copy(island = m)
        sp(ctx).edit().putString("island_mode", m.name).apply()
    }

    fun setMirrorVolume(ctx: Context, on: Boolean) {
        state.value = state.value.copy(mirrorVolume = on)
        sp(ctx).edit().putBoolean("mirror_volume", on).apply()
    }

    fun setLeveler(ctx: Context, on: Boolean) {
        state.value = state.value.copy(leveler = on)
        sp(ctx).edit().putBoolean("leveler", on).apply()
    }
}

// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** HyperIsland 顯示方式。 */
enum class IslandMode(val label: String, val desc: String) {
    Off("關閉", "只顯示一般通知"),
    On("開啟（上島）", "用系統標準的「媒體通知」顯示在 HyperIsland 與通知列，可調音量、靜音、中斷。不使用小米焦點通知，不會影響其他 App 的通知。"),
}

data class Prefs(
    val island: IslandMode = IslandMode.On,
    /** 手機音量鍵 → 同步加減接收端音量。 */
    val mirrorVolume: Boolean = true,
    /** 音量平衡：把不同歌曲的整體響度拉到接近，並限制爆音。 */
    val leveler: Boolean = false,
    /** Alive 生命感觸覺反饋。 */
    val haptics: Boolean = true,
)

/** 使用者設定（UI 與前景服務共用）。 */
object PrefsStore {
    private const val FILE = "hypercast"
    val state = MutableStateFlow(Prefs())
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // 舊版的「短暫顯示 / 持續顯示」（焦點通知）已移除，舊設定一律視為「開啟」，改走標準媒體通知
        val island = IslandMode.values().firstOrNull { it.name == sp.getString("island_mode", null) } ?: IslandMode.On
        state.value = Prefs(
            island = island,
            mirrorVolume = sp.getBoolean("mirror_volume", true),
            leveler = sp.getBoolean("leveler", false),
            haptics = sp.getBoolean("haptics", true),
        )
        Haptics.enabled = state.value.haptics
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

fun PrefsStore.setHaptics(ctx: Context, on: Boolean) {
    state.value = state.value.copy(haptics = on)
    Haptics.enabled = on
    ctx.applicationContext.getSharedPreferences("hypercast", Context.MODE_PRIVATE).edit().putBoolean("haptics", on).apply()
}

// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 使用者設定（UI 與前景服務共用）。
 * Beta5：通知方式不再可選——固定是「一般玻璃通知 ＋ 常駐的焦點通知（超級島）」。
 * 以前為了排查「通知被擋住」而加的各種模式與焦點通知進階選項都已移除（真正原因是 Android 的螢幕分享保護）。
 */
data class Prefs(
    /** 手機音量鍵 → 同步加減接收端音量。 */
    val mirrorVolume: Boolean = true,
    /** 音量平衡：把不同歌曲的整體響度拉到接近，並限制爆音。 */
    val leveler: Boolean = false,
    /** Alive 生命感觸覺反饋。 */
    val haptics: Boolean = true,
)

object PrefsStore {
    private const val FILE = "hypercast"
    val state = MutableStateFlow(Prefs())
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        state.value = Prefs(
            mirrorVolume = sp.getBoolean("mirror_volume", true),
            leveler = sp.getBoolean("leveler", false),
            haptics = sp.getBoolean("haptics", true),
        )
        Haptics.enabled = state.value.haptics
        loaded = true
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

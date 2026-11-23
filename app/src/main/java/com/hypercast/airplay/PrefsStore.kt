// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 串流通知 / HyperIsland 的顯示方式（Beta 2：三條路線可選，哪個在你的手機上穩定就用哪個）。
 *  - Normal：一般玻璃通知。不用焦點通知、不建立媒體工作階段，最單純。
 *  - FocusBrief / FocusAlways：一般玻璃通知 ＋ 小米焦點通知（超級島）**純顯示**狀態，島上沒有任何按鈕、
 *    也沒有媒體工作階段，所以不會收到任何「暫停」事件。
 *  - Media：標準媒體通知（媒體工作階段），島與通知列有播放控制。實驗性：部分系統會自己送「暫停」。
 */
enum class IslandMode(val label: String, val desc: String) {
    Normal("一般通知", "只有一則普通的玻璃通知（音量－／＋、靜音、中斷）。不使用焦點通知、不建立媒體工作階段，最單純、最不會出狀況"),
    FocusBrief("焦點通知・純顯示（短暫）", "狀態或音量改變時，島彈出約 6 秒就收起；島上只顯示狀態與音量，沒有按鈕。不建立媒體工作階段，不會有「自動暫停」。HyperOS 3 以上有效"),
    FocusAlways("焦點通知・純顯示（持續）", "島一直顯示狀態與音量（低優先級，讓其他 App 的島優先），沒有按鈕、不建立媒體工作階段。HyperOS 2 也能用，但可能擋住其他通知"),
    Media("媒體通知（實驗）", "用系統媒體通知顯示在島與通知列，有 音量－／＋、中斷。若出現自動暫停，請改選上面其中一個"),
}

val IslandMode.isFocus: Boolean get() = this == IslandMode.FocusBrief || this == IslandMode.FocusAlways

data class Prefs(
    val island: IslandMode = IslandMode.FocusBrief,
    /** 手機音量鍵 → 同步加減接收端音量。 */
    val mirrorVolume: Boolean = true,
    /** 音量平衡：把不同歌曲的整體響度拉到接近，並限制爆音。 */
    val leveler: Boolean = false,
    /** Alive 生命感觸覺反饋。 */
    val haptics: Boolean = true,
    /** 媒體通知：島 / 通知上的「播放・暫停」是否當成「取消靜音・靜音」。預設關：系統或其他 App 自己送的「暫停」不會再讓聲音停掉。 */
    val mediaKeys: Boolean = false,
    /** 焦點通知模式：沒在串流、App 開著時，島上短暫顯示「未連接」（約 6 秒，通知 15 秒後自動消失）。 */
    val idleIsland: Boolean = false,
    // ---- 焦點通知進階（對照小米官方文件的欄位，用來找出是哪一項讓其他 App 的一般通知被隱藏）----
    /** islandFirstFloat：通知第一次出現時是否自動展開成展開態。官方預設 true（會跳出一張大卡片）；這裡預設關，只出摘要態。 */
    val focusFirstFloat: Boolean = false,
    /** 島彈出幾秒後送出 dismissIsland 收起摘要態（只用於「短暫」）。 */
    val focusSeconds: Int = 6,
    /** 把焦點通知獨立成另一則可滑掉的通知，前景服務的通知維持一般玻璃通知。 */
    val focusSeparate: Boolean = false,
    /** 焦點通知使用低重要性頻道（不彈橫幅、不響）。 */
    val focusLowChannel: Boolean = false,
    /** updatable：持續性焦點通知（官方：是否為持續性通知）。 */
    val focusUpdatable: Boolean = true,
)

/** 使用者設定（UI 與前景服務共用）。 */
object PrefsStore {
    private const val FILE = "hypercast"
    val state = MutableStateFlow(Prefs())
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        val sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // 舊設定轉換：Off → 一般通知；Brief / On（Beta 1 的媒體通知，會自動暫停）→ 焦點通知・短暫；Always → 焦點通知・持續
        val raw = sp.getString("island_mode", null)
        val island = IslandMode.values().firstOrNull { it.name == raw } ?: when (raw) {
            "Off" -> IslandMode.Normal
            "Always" -> IslandMode.FocusAlways
            else -> IslandMode.FocusBrief
        }
        state.value = Prefs(
            island = island,
            mirrorVolume = sp.getBoolean("mirror_volume", true),
            leveler = sp.getBoolean("leveler", false),
            haptics = sp.getBoolean("haptics", true),
            mediaKeys = sp.getBoolean("media_keys", false),
            idleIsland = sp.getBoolean("idle_island", false),
            focusFirstFloat = sp.getBoolean("focus_first_float", false),
            focusSeconds = sp.getInt("focus_seconds", 6).coerceIn(3, 30),
            focusSeparate = sp.getBoolean("focus_separate", false),
            focusLowChannel = sp.getBoolean("focus_low_channel", false),
            focusUpdatable = sp.getBoolean("focus_updatable", true),
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

    fun setMediaKeys(ctx: Context, on: Boolean) {
        state.value = state.value.copy(mediaKeys = on)
        sp(ctx).edit().putBoolean("media_keys", on).apply()
    }

    fun setFocusFirstFloat(ctx: Context, on: Boolean) {
        state.value = state.value.copy(focusFirstFloat = on)
        sp(ctx).edit().putBoolean("focus_first_float", on).apply()
    }

    fun setFocusSeconds(ctx: Context, v: Int) {
        state.value = state.value.copy(focusSeconds = v.coerceIn(3, 30))
        sp(ctx).edit().putInt("focus_seconds", v.coerceIn(3, 30)).apply()
    }

    fun setFocusSeparate(ctx: Context, on: Boolean) {
        state.value = state.value.copy(focusSeparate = on)
        sp(ctx).edit().putBoolean("focus_separate", on).apply()
    }

    fun setFocusLowChannel(ctx: Context, on: Boolean) {
        state.value = state.value.copy(focusLowChannel = on)
        sp(ctx).edit().putBoolean("focus_low_channel", on).apply()
    }

    fun setFocusUpdatable(ctx: Context, on: Boolean) {
        state.value = state.value.copy(focusUpdatable = on)
        sp(ctx).edit().putBoolean("focus_updatable", on).apply()
    }

    fun setIdleIsland(ctx: Context, on: Boolean) {
        state.value = state.value.copy(idleIsland = on)
        sp(ctx).edit().putBoolean("idle_island", on).apply()
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

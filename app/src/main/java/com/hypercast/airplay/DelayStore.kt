// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context

/** 音訊延遲微調（毫秒）。正值＝聲音更晚播出，負值＝更早。每秒同步封包都會套用，串流中即可調整。 */
object DelayStore {
    const val MIN = -1000
    const val MAX = 2000
    const val STEP = 50
    private const val KEY = "audio_delay_ms"

    @Volatile var ms: Int = 0
        private set

    /** 換算成 44.1kHz 的 RTP 時間戳差。 */
    val ticks: Long get() = ms * 44100L / 1000L

    fun load(ctx: Context): Int {
        ms = ctx.getSharedPreferences("hypercast", Context.MODE_PRIVATE).getInt(KEY, 0).coerceIn(MIN, MAX)
        return ms
    }

    fun save(ctx: Context, v: Int): Int {
        ms = v.coerceIn(MIN, MAX)
        ctx.getSharedPreferences("hypercast", Context.MODE_PRIVATE).edit().putInt(KEY, ms).apply()
        return ms
    }
}

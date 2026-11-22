// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context

/** 記住上一次使用的音量；第一次使用的預設音量是 25%。 */
object VolumeStore {
    const val DEFAULT = 0.25f
    private const val KEY = "last_volume"
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun sp() = app?.getSharedPreferences("hypercast", Context.MODE_PRIVATE)

    fun load(): Float = sp()?.getFloat(KEY, DEFAULT)?.coerceIn(0f, 1f) ?: DEFAULT

    fun save(v: Float) { sp()?.edit()?.putFloat(KEY, v.coerceIn(0f, 1f))?.apply() }
}

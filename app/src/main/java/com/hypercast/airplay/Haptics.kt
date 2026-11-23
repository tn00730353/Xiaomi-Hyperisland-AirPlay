// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Alive 生命感觸覺反饋：按下是輕巧的 tick，放開是飽滿的 click，開關有「起伏」，
 * 連線成功像呼吸一樣上揚、失敗是兩下低沉的 thud。支援線性馬達的裝置會用 Primitive 合成，
 * 其他裝置退回系統預設效果。使用者可在設定中關閉。
 */
object Haptics {
    @Volatile var enabled = true
    private var vib: Vibrator? = null

    fun init(ctx: Context) {
        if (vib != null) return
        val app = ctx.applicationContext
        vib = if (Build.VERSION.SDK_INT >= 31) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun play(e: VibrationEffect?) {
        if (!enabled || e == null) return
        try { vib?.takeIf { it.hasVibrator() }?.vibrate(e) } catch (_: Exception) {}
    }

    private fun predefined(id: Int) = if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(id) else null

    private fun primitives(vararg p: Triple<Int, Float, Int>): VibrationEffect? {
        if (Build.VERSION.SDK_INT < 30) return null
        val v = vib ?: return null
        if (!v.areAllPrimitivesSupported(*p.map { it.first }.toIntArray())) return null
        return try {
            val c = VibrationEffect.startComposition()
            p.forEach { c.addPrimitive(it.first, it.second, it.third) }
            c.compose()
        } catch (_: Exception) { null }
    }

    /** 手指按下：很輕的一下。 */
    fun press() = play(
        primitives(Triple(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.55f, 0))
            ?: predefined(VibrationEffect.EFFECT_TICK)
    )

    /** 點擊完成（放開）：飽滿的 click。 */
    fun click() = play(
        primitives(Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 0))
            ?: predefined(VibrationEffect.EFFECT_CLICK)
    )

    /** 滑桿經過一格。 */
    fun tick() = play(
        primitives(Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f, 0))
            ?: predefined(VibrationEffect.EFFECT_TICK)
    )

    /** 滑桿到頭 / 到底。 */
    fun edge() = play(
        primitives(Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 0))
            ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    )

    /** 開關：打開時由輕到重，關閉時由重到輕。 */
    fun toggle(on: Boolean) = play(
        (if (on) primitives(
            Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 0),
            Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, 45),
        ) else primitives(
            Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 0),
            Triple(VibrationEffect.Composition.PRIMITIVE_TICK, 0.3f, 45),
        )) ?: predefined(if (on) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
    )

    /** 連線成功：像呼吸一樣慢慢上揚。 */
    fun success() = play(
        primitives(
            Triple(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f, 0),
            Triple(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.7f, 40),
            Triple(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 20),
        ) ?: predefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
    )

    /** 失敗：兩下低沉。 */
    fun fail() = play(
        primitives(
            Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f, 0),
            Triple(VibrationEffect.Composition.PRIMITIVE_THUD, 0.6f, 90),
        ) ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    )
}

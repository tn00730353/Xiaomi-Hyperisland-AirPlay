// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/**
 * 音量平衡（穩定版）：處理 16-bit 小端交錯立體聲，逐幀（352 取樣）原地修改。
 *
 * 舊版的問題（忽大忽小）：響度估計「上升快、下降慢」，等於追著鼓點的峰值跑，增益每秒都在抖；
 * 範圍又大（−12 ～ +9 dB），再加上 tanh 軟限幅壓扁峰值，聽起來就是一直在呼吸。
 *
 * 新版：
 *  1. 響度用「對稱 3 秒」平均，不追峰值；太小聲的幀（< −50 dBFS）不計入。
 *  2. 增益有 ±2 dB 的死區：離目標不到 2 dB 就完全不動，同一首歌內部音量不會被拉來拉去。
 *  3. 超出死區才慢慢移動（上升 0.7 dB/秒、下降 2.5 dB/秒）；範圍 −9 ～ +6 dB。
 *  4. 偵測到歌曲之間的空檔（靜音 0.4 秒以上），下一首的前幾秒才允許快速調整（8 dB/秒），換歌時音量差很快被補上。
 *  5. 防削波改用「瞬間壓低、緩慢恢復」的限幅器，不再用 tanh 壓扁所有峰值。
 */
class Leveler {
    private val frameSec = Alac.FRAMES_PER_PACKET / 44100.0
    private val aLevel = 1.0 - exp(-frameSec / 3.0)
    private val aWarm = 1.0 - exp(-frameSec / 0.6)
    private val gapFrames = (0.4 / frameSec).toInt()
    private val fastFrames = (4.0 / frameSec).toInt()
    private val warmFrames = (1.5 / frameSec).toInt()
    private val upPerFrame = 0.7 * frameSec
    private val downPerFrame = 2.5 * frameSec
    private val fastPerFrame = 8.0 * frameSec
    private val relPerFrame = frameSec / 0.35      // 限幅器約 0.35 秒恢復

    private var power = 0.0
    private var gainDb = 0.0
    private var initialized = false
    private var quietFrames = 0
    private var fastLeft = 0
    private var warm = 0
    private var limGain = 1.0
    private var prevTotal = 1.0

    /** 目前增益（dB），供日誌使用。 */
    val currentGainDb: Double get() = gainDb

    fun reset() {
        power = 0.0; gainDb = 0.0; initialized = false
        quietFrames = 0; fastLeft = 0; warm = 0; limGain = 1.0; prevTotal = 1.0
    }

    fun process(buf: ByteArray) {
        val n = buf.size / 2
        if (n == 0) return
        var sum = 0.0
        var peak = 0.0
        var i = 0
        while (i < n) {
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            sum += s * s
            val a = abs(s)
            if (a > peak) peak = a
            i++
        }
        val p = sum / n
        val frameDb = 10.0 * log10(p + 1e-12)

        if (frameDb < GATE_DB) {
            // 幾乎無聲：凍結增益；空檔夠久就視為換歌，下一首重新量測並允許快速調整
            quietFrames++
            if (quietFrames == gapFrames) { initialized = false; fastLeft = fastFrames }
        } else {
            quietFrames = 0
            if (!initialized) { power = p; initialized = true; warm = warmFrames }
            else {
                val a = if (warm > 0) { warm--; aWarm } else aLevel
                power += (p - power) * a
            }
            val levelDb = 10.0 * log10(power + 1e-12)
            val want = (TARGET_DB - levelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            val diff = want - gainDb
            if (fastLeft > 0) {
                fastLeft--
                gainDb += diff.coerceIn(-fastPerFrame, fastPerFrame)
            } else if (abs(diff) > DEADBAND_DB) {
                gainDb += (diff - sign(diff) * DEADBAND_DB).coerceIn(-downPerFrame, upPerFrame)
            }
        }

        // 增益 + 限幅器（瞬間壓低、緩慢恢復）
        val g = 10.0.pow(gainDb / 20.0)
        val pk = peak * g
        val need = if (pk > CEILING) CEILING / pk else 1.0
        limGain = if (need < limGain) need else min(1.0, limGain + relPerFrame)
        val total = g * limGain
        if (abs(total - 1.0) < 0.002 && abs(prevTotal - 1.0) < 0.002) { prevTotal = total; return }

        // 壓低時整幀直接套用新增益（保護峰值）；放大或恢復時在幀內線性過渡，避免階梯聲
        val start = if (total < prevTotal) total else prevTotal
        i = 0
        while (i < n) {
            val gi = start + (total - start) * (i.toDouble() / n)
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            val y = guard(s * gi)
            val v = (y * 32767.0).toInt().coerceIn(-32768, 32767)
            buf[2 * i] = v.toByte()
            buf[2 * i + 1] = (v shr 8).toByte()
            i++
        }
        prevTotal = total
    }

    /** 最後保險：限幅器漏掉的極少數峰值才會碰到。 */
    private fun guard(x: Double): Double {
        val a = abs(x)
        if (a <= 0.97) return x
        val y = 0.97 + 0.03 * tanh((a - 0.97) / 0.03)
        return if (x < 0) -y else y
    }

    companion object {
        const val TARGET_DB = -16.0
        const val MIN_GAIN_DB = -9.0
        const val MAX_GAIN_DB = 6.0
        private const val DEADBAND_DB = 2.0
        private const val GATE_DB = -50.0
        private const val CEILING = 0.89     // 約 −1 dBFS
    }
}

// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tanh

/**
 * 音量平衡（慢速自動增益 + 軟限幅），處理 16-bit 小端交錯立體聲，逐幀（352 取樣）原地修改。
 *
 *  - 用「慢速」響度估計（上升約 0.5 秒、下降約 5 秒）把整體響度拉向目標，所以換歌時差距會被補償，
 *    但一首歌內部的動態不會被壓扁。
 *  - 增益範圍 −12 ～ +9 dB，變化速度有上限（下降 6 dB/秒、上升 2 dB/秒），不會有明顯的「抽吸」感。
 *  - 太小聲（< −50 dBFS，例如歌曲之間的靜音）時凍結增益，不會把底噪放大。
 *  - 最後用軟限幅避免加大後削波爆音。
 */
class Leveler {
    private val frameSec = Alac.FRAMES_PER_PACKET / 44100.0
    private val aUp = 1.0 - exp(-frameSec / 0.5)
    private val aDown = 1.0 - exp(-frameSec / 5.0)
    private val maxDownDb = 6.0 * frameSec     // 每幀最多降多少 dB
    private val maxUpDb = 2.0 * frameSec

    private var power = 0.0          // 平滑後的平均功率（0..1，滿幅 = 1）
    private var gainDb = 0.0
    private var initialized = false

    /** 目前增益（dB），供日誌使用。 */
    val currentGainDb: Double get() = gainDb

    fun reset() { power = 0.0; gainDb = 0.0; initialized = false }

    fun process(buf: ByteArray) {
        val n = buf.size / 2
        // 1) 這一幀的功率
        var sum = 0.0
        var i = 0
        while (i < n) {
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            sum += s * s
            i++
        }
        val p = sum / n
        if (!initialized) { power = p; initialized = true }
        power += (p - power) * (if (p > power) aUp else aDown)

        // 2) 目標增益（只在有明顯聲音時更新）
        val levelDb = 10.0 * log10(power + 1e-12)
        if (levelDb > -50.0) {
            val want = (TARGET_DB - levelDb).coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
            gainDb += (want - gainDb).coerceIn(-maxDownDb, maxUpDb)
        }

        // 3) 套用增益 + 軟限幅
        if (abs(gainDb) < 0.05) return
        val g = 10.0.pow(gainDb / 20.0)
        i = 0
        while (i < n) {
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort().toInt() / 32768.0
            val y = softClip(s * g)
            val v = (y * 32767.0).toInt().coerceIn(-32768, 32767)
            buf[2 * i] = v.toByte()
            buf[2 * i + 1] = (v shr 8).toByte()
            i++
        }
    }

    private fun softClip(x: Double): Double {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1.0 - KNEE) * tanh((a - KNEE) / (1.0 - KNEE))
        return if (x < 0) -y else y
    }

    companion object {
        const val TARGET_DB = -16.0
        const val MIN_GAIN_DB = -12.0
        const val MAX_GAIN_DB = 9.0
        private const val KNEE = 0.85
    }
}

// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

/**
 * 把 16-bit 立體聲 PCM 包成「未壓縮 ALAC 幀」（escape frame）。
 * 接收端以 AppleLossless 解碼即可無損播放，左右聲道交錯保持立體聲。
 */
object Alac {
    const val FRAMES_PER_PACKET = 352
    const val FRAME_BYTES = FRAMES_PER_PACKET * 4 // 16bit * 2ch

    /** @param pcm 小端序 16-bit 交錯立體聲，長度 frames*4 */
    fun encode(pcm: ByteArray, frames: Int = FRAMES_PER_PACKET): ByteArray {
        val bw = BitWriter(frames * 4 + 16)
        bw.write(1, 3)                 // 元素類型：CPE（立體聲）
        bw.write(0, 4)                 // instance tag
        bw.write(0, 12)                // unused
        bw.write(1, 1)                 // 帶有樣本數
        bw.write(0, 2)                 // shift
        bw.write(1, 1)                 // 未壓縮 (escape)
        bw.write(frames.toLong(), 32)  // 樣本數
        for (i in 0 until frames) {
            val o = i * 4
            val l = (pcm[o].toInt() and 0xFF) or ((pcm[o + 1].toInt() and 0xFF) shl 8)
            val r = (pcm[o + 2].toInt() and 0xFF) or ((pcm[o + 3].toInt() and 0xFF) shl 8)
            bw.write(l.toLong(), 16)   // 以大端序位元寫入
            bw.write(r.toLong(), 16)
        }
        bw.write(7, 3)                 // END 標記
        return bw.toByteArray()
    }
}

class BitWriter(capacity: Int) {
    private val buf = ByteArray(capacity)
    private var bitPos = 0

    fun write(value: Long, bits: Int) {
        for (i in bits - 1 downTo 0) {
            if (((value shr i) and 1L) == 1L) {
                val idx = bitPos ushr 3
                buf[idx] = (buf[idx].toInt() or (0x80 ushr (bitPos and 7))).toByte()
            }
            bitPos++
        }
    }

    fun toByteArray(): ByteArray = buf.copyOf((bitPos + 7) / 8)
}

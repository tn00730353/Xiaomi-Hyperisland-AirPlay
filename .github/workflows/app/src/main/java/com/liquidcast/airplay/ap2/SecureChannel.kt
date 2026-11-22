package com.liquidcast.airplay.ap2

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * AirPlay 2 加密 RTSP 通道：每個封包為
 *   uint16_le 長度 ‖ ChaCha20-Poly1305 密文 ‖ 16 位元組 tag
 * AAD = 長度欄位（2 位元組）；nonce = 4 位元組 0 ‖ 64 位元計數器（小端）；每個方向各自計數。
 * 單一封包最多 1024 位元組明文。
 */
class SecureIn(private val raw: InputStream, private val key: ByteArray) : InputStream() {
    private var ctr = 0L
    private var buf = ByteArray(0)
    private var pos = 0

    private fun readFully(n: Int): ByteArray? {
        val b = ByteArray(n)
        var o = 0
        while (o < n) {
            val r = raw.read(b, o, n - o)
            if (r < 0) return null
            o += r
        }
        return b
    }

    private fun fill(): Boolean {
        val hdr = readFully(2) ?: return false
        val len = (hdr[0].toInt() and 0xFF) or ((hdr[1].toInt() and 0xFF) shl 8)
        val data = readFully(len + 16) ?: return false
        buf = Crypto.chachaOpen(key, Crypto.counterNonce(ctr++), hdr, data)
            ?: throw IOException("加密通道解密失敗")
        pos = 0
        return true
    }

    override fun read(): Int {
        while (pos >= buf.size) if (!fill()) return -1
        return buf[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (pos >= buf.size) if (!fill()) return -1
        val n = minOf(len, buf.size - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }
}

class SecureOut(private val raw: OutputStream, private val key: ByteArray) : OutputStream() {
    private var ctr = 0L

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        val end = off + len
        while (o < end) {
            val n = minOf(1024, end - o)
            val aad = byteArrayOf(n.toByte(), (n shr 8).toByte())
            val sealed = Crypto.chachaSeal(key, Crypto.counterNonce(ctr++), aad, b.copyOfRange(o, o + n))
            raw.write(aad)
            raw.write(sealed)
            o += n
        }
    }

    override fun flush() = raw.flush()
}

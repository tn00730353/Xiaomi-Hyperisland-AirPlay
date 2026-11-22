// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ap2

import java.io.ByteArrayOutputStream

/** 最小化的二進位 plist（bplist00）編解碼：Map / List / String / Long / Double / Boolean / ByteArray。 */
object BPlist {

    // ------------------------------------------------------------ 編碼

    fun encode(root: Any): ByteArray {
        val objs = ArrayList<Any>()
        val refs = HashMap<Int, IntArray>()

        fun register(o: Any): Int {
            val idx = objs.size
            objs.add(o)
            when (o) {
                is Map<*, *> -> {
                    val keys = o.keys.map { register(it as String) }
                    val vals = o.values.map { register(it!!) }
                    refs[idx] = (keys + vals).toIntArray()
                }
                is List<*> -> refs[idx] = o.map { register(it!!) }.toIntArray()
            }
            return idx
        }
        register(root)

        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val offsets = IntArray(objs.size)

        fun writeInt(v: Long, bytes: Int) {
            for (i in bytes - 1 downTo 0) out.write((v shr (8 * i)).toInt() and 0xFF)
        }
        fun writeLen(marker: Int, n: Int) {
            if (n < 15) out.write(marker or n)
            else {
                out.write(marker or 0x0F)
                out.write(0x11); writeInt(n.toLong(), 2) // 2 位元組整數
            }
        }

        for ((i, o) in objs.withIndex()) {
            offsets[i] = out.size()
            when (o) {
                is Boolean -> out.write(if (o) 0x09 else 0x08)
                is Int -> encodeLong(o.toLong(), out)
                is Long -> encodeLong(o, out)
                is Double -> { out.write(0x23); writeInt(java.lang.Double.doubleToLongBits(o), 8) }
                is String -> {
                    if (o.all { it.code < 128 }) {
                        writeLen(0x50, o.length); out.write(o.toByteArray(Charsets.US_ASCII))
                    } else {
                        writeLen(0x60, o.length); out.write(o.toByteArray(Charsets.UTF_16BE))
                    }
                }
                is ByteArray -> { writeLen(0x40, o.size); out.write(o) }
                is Map<*, *> -> {
                    writeLen(0xD0, o.size)
                    refs[i]!!.forEach { writeInt(it.toLong(), 2) }
                }
                is List<*> -> {
                    writeLen(0xA0, o.size)
                    refs[i]!!.forEach { writeInt(it.toLong(), 2) }
                }
                else -> throw IllegalArgumentException("不支援的 plist 型別：${o.javaClass}")
            }
        }
        val tableOffset = out.size()
        offsets.forEach { writeInt(it.toLong(), 4) }
        repeat(6) { out.write(0) }          // 5 unused + sortVersion
        out.write(4)                         // offsetIntSize
        out.write(2)                         // objectRefSize
        writeInt(objs.size.toLong(), 8)
        writeInt(0, 8)                       // top object = 0
        writeInt(tableOffset.toLong(), 8)
        return out.toByteArray()
    }

    private fun encodeLong(v: Long, out: ByteArrayOutputStream) {
        val (marker, n) = when {
            v < 0 -> 0x13 to 8
            v <= 0xFF -> 0x10 to 1
            v <= 0xFFFF -> 0x11 to 2
            v <= 0xFFFFFFFFL -> 0x12 to 4
            else -> 0x13 to 8
        }
        out.write(marker)
        for (i in n - 1 downTo 0) out.write((v shr (8 * i)).toInt() and 0xFF)
    }

    // ------------------------------------------------------------ 解碼

    fun decode(b: ByteArray): Any {
        require(b.size > 40 && String(b, 0, 8) == "bplist00") { "不是 bplist" }
        val t = b.size - 32
        val offSize = b[t + 6].toInt() and 0xFF
        val refSize = b[t + 7].toInt() and 0xFF
        val count = readBE(b, t + 8, 8).toInt()
        val top = readBE(b, t + 16, 8).toInt()
        val tableOff = readBE(b, t + 24, 8).toInt()

        fun offsetOf(i: Int) = readBE(b, tableOff + i * offSize, offSize).toInt()

        fun parse(idx: Int): Any {
            require(idx in 0 until count) { "plist 索引錯誤" }
            var p = offsetOf(idx)
            val marker = b[p++].toInt() and 0xFF
            val type = marker shr 4
            var n = marker and 0x0F

            fun readCount(): Int {
                if (n != 0x0F) return n
                val m = b[p++].toInt() and 0xFF
                val size = 1 shl (m and 0x0F)
                val v = readBE(b, p, size).toInt()
                p += size
                return v
            }

            return when (type) {
                0x0 -> when (marker) { 0x08 -> false; 0x09 -> true; else -> "" }
                0x1 -> { val size = 1 shl n; readBE(b, p, size) }
                0x2 -> {
                    val size = 1 shl n
                    val bits = readBE(b, p, size)
                    if (size == 4) java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                    else java.lang.Double.longBitsToDouble(bits)
                }
                0x4 -> { val len = readCount(); b.copyOfRange(p, p + len) }
                0x5 -> { val len = readCount(); String(b, p, len, Charsets.US_ASCII) }
                0x6 -> { val len = readCount(); String(b, p, len * 2, Charsets.UTF_16BE) }
                0xA -> {
                    val len = readCount()
                    List(len) { parse(readBE(b, p + it * refSize, refSize).toInt()) }
                }
                0xD -> {
                    val len = readCount()
                    val m = LinkedHashMap<String, Any>()
                    for (i in 0 until len) {
                        val k = parse(readBE(b, p + i * refSize, refSize).toInt()) as String
                        m[k] = parse(readBE(b, p + (len + i) * refSize, refSize).toInt())
                    }
                    m
                }
                else -> throw IllegalArgumentException("不支援的 plist 標記 0x${marker.toString(16)}")
            }
        }
        return parse(top)
    }

    private fun readBE(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}

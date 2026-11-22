// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ap2

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Crypto {
    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun hmac512(key: ByteArray, data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA512")
        m.init(SecretKeySpec(key, "HmacSHA512"))
        return m.doFinal(data)
    }

    /** HKDF-SHA512，輸出 ≤ 64 位元組（單一區塊）。 */
    fun hkdf(ikm: ByteArray, salt: String, info: String, len: Int = 32): ByteArray {
        val prk = hmac512(salt.toByteArray(), ikm)
        return hmac512(prk, info.toByteArray() + byteArrayOf(1)).copyOf(len)
    }

    /** 回傳 密文‖16 位元組 tag。 */
    fun chachaSeal(key: ByteArray, nonce12: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce12, aad))
        val out = ByteArray(c.getOutputSize(plain.size))
        var n = c.processBytes(plain, 0, plain.size, out, 0)
        n += c.doFinal(out, n)
        return out.copyOf(n)
    }

    fun chachaOpen(key: ByteArray, nonce12: ByteArray, aad: ByteArray, data: ByteArray): ByteArray? = try {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce12, aad))
        val out = ByteArray(c.getOutputSize(data.size))
        var n = c.processBytes(data, 0, data.size, out, 0)
        n += c.doFinal(out, n)
        out.copyOf(n)
    } catch (_: Exception) {
        null
    }

    fun counterNonce(ctr: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (ctr shr (8 * i)).toByte()
        return n
    }
}

/** HomeKit 風格 SRP-6a（3072 位元群組、SHA-512）。 */
class Srp(private val user: String, private val pass: String) {
    companion object {
        val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"            , 16
        )
        val G: BigInteger = BigInteger.valueOf(5)
        const val LEN = 384

        fun minimal(x: BigInteger): ByteArray {
            val b = x.toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }
        fun pad(x: BigInteger): ByteArray {
            val m = minimal(x)
            return ByteArray(LEN - m.size) + m
        }
    }

    private val a: BigInteger = BigInteger(256, SecureRandom()).let { if (it.signum() == 0) BigInteger.ONE else it }
    private val bigA: BigInteger = G.modPow(a, N)
    val publicKey: ByteArray get() = pad(bigA)
    lateinit var sessionKey: ByteArray
        private set
    private lateinit var m1: ByteArray

    fun computeProof(salt: ByteArray, serverPub: ByteArray): ByteArray {
        val bigB = BigInteger(1, serverPub)
        require(bigB.mod(N).signum() != 0) { "無效的伺服器公鑰" }
        val u = BigInteger(1, Crypto.sha512(pad(bigA), pad(bigB)))
        val x = BigInteger(1, Crypto.sha512(salt, Crypto.sha512("$user:$pass".toByteArray())))
        val k = BigInteger(1, Crypto.sha512(pad(N), pad(G)))
        val v = G.modPow(x, N)
        val base = bigB.subtract(k.multiply(v)).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        sessionKey = Crypto.sha512(minimal(s))
        val hn = Crypto.sha512(minimal(N))
        val hg = Crypto.sha512(minimal(G))
        val hxor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        m1 = Crypto.sha512(hxor, Crypto.sha512(user.toByteArray()), salt, minimal(bigA), minimal(bigB), sessionKey)
        return m1
    }

    fun verifyServer(m2: ByteArray): Boolean =
        MessageDigest.isEqual(m2, Crypto.sha512(minimal(bigA), m1, sessionKey))
}

object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val FLAGS = 0x13

    fun encode(items: List<Pair<Int, ByteArray>>): ByteArray {
        val o = ByteArrayOutputStream()
        for ((t, v) in items) {
            if (v.isEmpty()) { o.write(t); o.write(0); continue }
            var off = 0
            while (off < v.size) {
                val n = minOf(255, v.size - off)
                o.write(t); o.write(n); o.write(v, off, n)
                off += n
            }
        }
        return o.toByteArray()
    }

    fun decode(b: ByteArray): Map<Int, ByteArray> {
        val m = LinkedHashMap<Int, ByteArray>()
        var i = 0
        while (i + 2 <= b.size) {
            val t = b[i].toInt() and 0xFF
            val n = b[i + 1].toInt() and 0xFF
            if (i + 2 + n > b.size) break
            m[t] = (m[t] ?: ByteArray(0)) + b.copyOfRange(i + 2, i + 2 + n)
            i += 2 + n
        }
        return m
    }
}

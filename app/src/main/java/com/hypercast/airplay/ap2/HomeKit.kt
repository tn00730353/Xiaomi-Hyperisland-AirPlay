// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ap2

import android.content.Context
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID

/** 長期配對憑證（Apple TV / 需要螢幕配對碼的裝置，一次配對後可重複使用）。 */
class PairCreds(val id: ByteArray, val ltsk: ByteArray, val ltpk: ByteArray, val accId: ByteArray, val accLtpk: ByteArray) {
    fun encode() = listOf(id, ltsk, ltpk, accId, accLtpk).joinToString(":") { it.joinToString("") { b -> "%02x".format(b) } }

    companion object {
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun decode(s: String): PairCreds? = try {
            val p = s.split(':')
            if (p.size != 5) null else PairCreds(unhex(p[0]), unhex(p[1]), unhex(p[2]), unhex(p[3]), unhex(p[4]))
        } catch (_: Exception) { null }
    }
}

/** 以 SharedPreferences 保存各裝置的配對憑證（僅存在本機 App 私有空間）。 */
object PairStore {
    private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }
    private fun sp() = ctx?.getSharedPreferences("hc_pairs", Context.MODE_PRIVATE)
    fun get(deviceId: String): PairCreds? = sp()?.getString(deviceId, null)?.let { PairCreds.decode(it) }
    fun put(deviceId: String, c: PairCreds) { sp()?.edit()?.putString(deviceId, c.encode())?.apply() }
    fun remove(deviceId: String) { sp()?.edit()?.remove(deviceId)?.apply() }
}

/** HomeKit 一般配對（pair-setup M1–M6）與 pair-verify（M1–M4），對應 X-Apple-HKP: 3。 */
object HomeKit {
    class Failure(msg: String, val auth: Boolean) : IOException(msg)

    private const val T_ID = 0x01
    private const val T_ENC = 0x05
    private const val T_SIG = 0x0A
    private val rnd = SecureRandom()

    private fun nonce(tag: String) = ByteArray(4) + tag.toByteArray()
    private fun sign(sk: ByteArray, data: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(sk, 0))
        s.update(data, 0, data.size)
        return s.generateSignature()
    }
    private fun errOf(t: Map<Int, ByteArray>) = t[Tlv8.ERROR]?.firstOrNull()?.toInt()

    /** post(path, body) → (HTTP 狀態碼, 回應本文) */
    fun setup(pin: String, post: (String, ByteArray) -> Pair<Int, ByteArray>, errText: (Int) -> String): PairCreds {
        val srp = Srp("Pair-Setup", pin)
        val (c1, b1) = post("/pair-setup", Tlv8.encode(listOf(Tlv8.METHOD to byteArrayOf(0), Tlv8.STATE to byteArrayOf(1))))
        if (c1 == 470) throw Failure("裝置回 HTTP 470（要求授權）", true)
        val t2 = Tlv8.decode(b1)
        errOf(t2)?.let { throw Failure("配對被拒絕（${errText(it)}）", it == 2) }
        val salt = t2[Tlv8.SALT]; val pk = t2[Tlv8.PUBLIC_KEY]
        if (t2[Tlv8.STATE]?.firstOrNull()?.toInt() != 2 || salt == null || pk == null)
            throw Failure("pair-setup M2 格式錯誤（HTTP $c1）", false)

        val proof = srp.computeProof(salt, pk)
        val (_, b3) = post("/pair-setup", Tlv8.encode(listOf(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to proof)))
        val t4 = Tlv8.decode(b3)
        errOf(t4)?.let { throw Failure("配對碼不正確（${errText(it)}）", true) }
        val sp = t4[Tlv8.PROOF] ?: throw Failure("pair-setup M4 沒有 proof", false)
        if (!srp.verifyServer(sp)) throw Failure("配對碼不正確（SRP 證明不符）", true)
        val k = srp.sessionKey

        val id = UUID.randomUUID().toString().uppercase().toByteArray()
        val ltsk = Ed25519PrivateKeyParameters(rnd)
        val ltskB = ltsk.encoded
        val ltpk = ltsk.generatePublicKey().encoded
        val x = Crypto.hkdf(k, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val sig = sign(ltskB, x + id + ltpk)
        val sub = Tlv8.encode(listOf(T_ID to id, Tlv8.PUBLIC_KEY to ltpk, T_SIG to sig))
        val encKey = Crypto.hkdf(k, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val enc = Crypto.chachaSeal(encKey, nonce("PS-Msg05"), ByteArray(0), sub)
        val (_, b5) = post("/pair-setup", Tlv8.encode(listOf(Tlv8.STATE to byteArrayOf(5), T_ENC to enc)))
        val t6 = Tlv8.decode(b5)
        errOf(t6)?.let { throw Failure("配對 M5 被拒絕（${errText(it)}）", false) }
        val e6 = t6[T_ENC] ?: throw Failure("pair-setup M6 沒有加密資料", false)
        val d6 = Crypto.chachaOpen(encKey, nonce("PS-Msg06"), ByteArray(0), e6)
            ?: throw Failure("pair-setup M6 解密失敗", false)
        val s6 = Tlv8.decode(d6)
        val accId = s6[T_ID] ?: throw Failure("pair-setup M6 缺少裝置識別碼", false)
        val accLtpk = s6[Tlv8.PUBLIC_KEY] ?: throw Failure("pair-setup M6 缺少裝置公鑰", false)
        return PairCreds(id, ltskB, ltpk, accId, accLtpk)
    }

    /** 回傳 X25519 共享密鑰（32 位元組），作為加密通道金鑰來源。 */
    fun verify(c: PairCreds, post: (String, ByteArray) -> Pair<Int, ByteArray>, errText: (Int) -> String): ByteArray {
        val eph = X25519PrivateKeyParameters(rnd)
        val pub = eph.generatePublicKey().encoded
        val (_, b1) = post("/pair-verify", Tlv8.encode(listOf(Tlv8.STATE to byteArrayOf(1), Tlv8.PUBLIC_KEY to pub)))
        val t2 = Tlv8.decode(b1)
        errOf(t2)?.let { throw Failure("pair-verify 被拒絕（${errText(it)}）", true) }
        val accPub = t2[Tlv8.PUBLIC_KEY]; val e2 = t2[T_ENC]
        if (accPub == null || e2 == null) throw Failure("pair-verify M2 格式錯誤", true)
        val shared = ByteArray(32)
        X25519Agreement().apply { init(eph) }.calculateAgreement(X25519PublicKeyParameters(accPub, 0), shared, 0)
        val vk = Crypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val d2 = Crypto.chachaOpen(vk, nonce("PV-Msg02"), ByteArray(0), e2) ?: throw Failure("pair-verify M2 解密失敗", true)
        val s2 = Tlv8.decode(d2)
        if (s2[T_ID]?.contentEquals(c.accId) != true) throw Failure("裝置識別碼與配對時不同", true)

        val sig = sign(c.ltsk, pub + c.id + accPub)
        val m3 = Crypto.chachaSeal(vk, nonce("PV-Msg03"), ByteArray(0), Tlv8.encode(listOf(T_ID to c.id, T_SIG to sig)))
        val (_, b3) = post("/pair-verify", Tlv8.encode(listOf(Tlv8.STATE to byteArrayOf(3), T_ENC to m3)))
        errOf(Tlv8.decode(b3))?.let { throw Failure("pair-verify M3 被拒絕（${errText(it)}）", true) }
        return shared
    }
}

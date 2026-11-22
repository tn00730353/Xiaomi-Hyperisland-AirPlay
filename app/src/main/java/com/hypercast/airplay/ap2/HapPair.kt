// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ap2

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * HomeKit（HAP）配對用的 Ed25519 / X25519。
 * 對應 Base 的原生層：Ed25519KeyPair、X25519KeyPair、Pair-Setup M1~M6、Pair-Verify M1~M4。
 */
object HapCrypto {
    private val rnd = SecureRandom()

    /** 回傳 (32 位元組 seed, 32 位元組公鑰)。 */
    fun newEd25519(): Pair<ByteArray, ByteArray> {
        val priv = Ed25519PrivateKeyParameters(rnd)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    fun sign(seed: ByteArray, msg: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(msg, 0, msg.size)
        return s.generateSignature()
    }

    fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = try {
        val s = Ed25519Signer()
        s.init(false, Ed25519PublicKeyParameters(pub, 0))
        s.update(msg, 0, msg.size)
        s.verifySignature(sig)
    } catch (_: Exception) {
        false
    }

    /** 回傳 (私鑰, 公鑰)。 */
    fun newX25519(): Pair<ByteArray, ByteArray> {
        val priv = X25519PrivateKeyParameters(rnd)
        return priv.encoded to priv.generatePublicKey().encoded
    }

    fun x25519(priv: ByteArray, peerPub: ByteArray): ByteArray {
        val ag = X25519Agreement()
        ag.init(X25519PrivateKeyParameters(priv, 0))
        val out = ByteArray(ag.agreementSize)
        ag.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), out, 0)
        return out
    }

    /** HAP 的 12 位元組 nonce：4 個 0 ＋ 8 個 ASCII 字元（例如 "PS-Msg05"）。 */
    fun nonce(tag: String): ByteArray = ByteArray(4) + tag.toByteArray(Charsets.US_ASCII)
}

/** 配對成功後保留的身分，之後只需 Pair-Verify，不必再輸入配對碼。 */
class HapCreds(
    val controllerId: String,
    val ltsk: ByteArray,        // 控制端 Ed25519 seed
    val accessoryId: ByteArray,
    val accessoryLtpk: ByteArray,
)

object HapStore {
    private const val PREF = "hypercast_hap"
    @Volatile private var app: Context? = null

    fun init(ctx: Context) { app = ctx.applicationContext }

    private fun sp() = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    fun load(id: String): HapCreds? = try {
        val p = sp()?.getString(id, null)?.split('|')
        if (p == null || p.size != 4) null else HapCreds(p[0], unb64(p[1]), unb64(p[2]), unb64(p[3]))
    } catch (_: Exception) {
        null
    }

    fun save(id: String, c: HapCreds) {
        sp()?.edit()?.putString(id, listOf(c.controllerId, b64(c.ltsk), b64(c.accessoryId), b64(c.accessoryLtpk)).joinToString("|"))?.apply()
    }

    fun clear(id: String) { sp()?.edit()?.remove(id)?.apply() }
}

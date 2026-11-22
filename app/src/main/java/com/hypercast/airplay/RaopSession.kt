// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.util.Base64
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 傳統 RAOP（AirPlay 1 音訊）發送端：
 * RTSP 握手 → UDP RTP 串流（ALAC、44.1kHz、立體聲）→ 控制/時間同步 → 音量。
 * 與 AirPort Express、shairport-sync 等接收端相容。
 */
class RaopSession(private val rx: Receiver) : CastSession {

    private class Resp(val code: Int, val headers: Map<String, String>, val body: String)

    private val rnd = SecureRandom()
    private val rtspLock = Any()
    private var sock: Socket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null
    private var cseq = 0
    private val sid = (rnd.nextInt() and 0x7fffffff).toString()
    private val clientInstance = hex(8)
    private val dacpId = hex(8)
    private var sessionHdr: String? = null
    private var uri = ""
    private var localIp = "0.0.0.0"

    private lateinit var addr: InetAddress
    private lateinit var audioSock: DatagramSocket
    private lateinit var controlSock: DatagramSocket
    private lateinit var timingSock: DatagramSocket
    private var srvAudio = 0
    private var srvControl = 0

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private val cipher: Cipher = Cipher.getInstance("AES/CBC/NoPadding")

    @Volatile private var ready = false
    @Volatile private var closed = false
    private var seq = rnd.nextInt(0x10000)
    private var ts = rnd.nextInt().toLong() and 0xFFFFFFFFL
    @Volatile private var nextTs = ts
    private val ssrc = rnd.nextInt()
    private var first = true
    private var firstSync = true
    private val ring = arrayOfNulls<ByteArray>(RING)

    private val ctl = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "raop-ctl").apply { isDaemon = true }
    }

    // ---------------------------------------------------------------- 連線

    override fun connect() {
        addr = InetAddress.getByName(rx.host)
        DiagLog.d("RAOP", "開始 AirPlay 1（RAOP）連線：${rx.name}（型號 ${rx.model}，${rx.host}:${rx.port}，et=${rx.et}，pw=${rx.password}）")
        val s = Socket()
        s.connect(InetSocketAddress(addr, rx.port), 5000)
        s.soTimeout = 8000
        s.tcpNoDelay = true
        sock = s
        inp = BufferedInputStream(s.getInputStream())
        out = s.getOutputStream()
        localIp = s.localAddress.hostAddress?.substringBefore('%') ?: "0.0.0.0"
        uri = "rtsp://$localIp/$sid"

        audioSock = DatagramSocket(0)
        controlSock = DatagramSocket(0)
        timingSock = DatagramSocket(0)

        rtsp("OPTIONS", "*", check = false)

        val useRsa = !rx.supportsPlain && rx.supportsRsa
        val sdp = StringBuilder()
            .append("v=0\r\n")
            .append("o=iTunes $sid 0 IN IP4 $localIp\r\n")
            .append("s=iTunes\r\n")
            .append("c=IN IP4 ${rx.host}\r\n")
            .append("t=0 0\r\n")
            .append("m=audio 0 RTP/AVP 96\r\n")
            .append("a=rtpmap:96 AppleLossless\r\n")
            .append("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n")
        if (useRsa) {
            val key = ByteArray(16).also { rnd.nextBytes(it) }
            val iv = ByteArray(16).also { rnd.nextBytes(it) }
            aesKey = key; aesIv = iv
            val flags = Base64.NO_WRAP or Base64.NO_PADDING
            sdp.append("a=rsaaeskey:").append(Base64.encodeToString(rsaEncrypt(key), flags)).append("\r\n")
            sdp.append("a=aesiv:").append(Base64.encodeToString(iv, flags)).append("\r\n")
        }
        val annHdr = mapOf("Content-Type" to "application/sdp")
        var ann = rtsp("ANNOUNCE", uri, annHdr, sdp.toString(), check = false)
        if (ann.code == 403) {
            // 部分新型 AirPlay 2 喇叭（Sonos、AirPort Express 等）要求先送 /auth-setup，否則 ANNOUNCE 回 403
            DiagLog.d("RAOP", "ANNOUNCE 回 403，先送 /auth-setup 再重試")
            val pub = ByteArray(32).also { rnd.nextBytes(it) }
            pub[0] = (pub[0].toInt() and 248).toByte()
            pub[31] = ((pub[31].toInt() and 127) or 64).toByte()
            rtsp("POST", "/auth-setup", mapOf("Content-Type" to "application/octet-stream"), null, check = false, raw = byteArrayOf(1) + pub)
            ann = rtsp("ANNOUNCE", uri, annHdr, sdp.toString(), check = false)
        }
        if (ann.code != 200) throw IOException(failText("ANNOUNCE", ann.code))

        val setup = rtsp(
            "SETUP", uri, mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=${controlSock.localPort};timing_port=${timingSock.localPort}"
            )
        )
        sessionHdr = setup.headers["session"]
        val transport = setup.headers["transport"] ?: throw IOException("接收端未回傳 Transport")
        srvAudio = Regex("server_port=(\\d+)").find(transport)?.groupValues?.get(1)?.toInt()
            ?: throw IOException("接收端未提供音訊連接埠")
        srvControl = Regex("control_port=(\\d+)").find(transport)?.groupValues?.get(1)?.toInt() ?: 0

        rtsp("RECORD", uri, mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=$ts"))
        ready = true

        startThreads()
    }

    // ---------------------------------------------------------------- 串流

    /** 送出一個 ALAC 幀（352 個取樣）。呼叫端以即時速率餵入。 */
    override fun sendFrame(alac: ByteArray) {
        if (closed) return
        val pkt = ByteArray(12 + alac.size)
        pkt[0] = 0x80.toByte()
        pkt[1] = (if (first) 0xE0 else 0x60).toByte()
        first = false
        put16(pkt, 2, seq)
        put32(pkt, 4, ts)
        put32(pkt, 8, ssrc.toLong() and 0xFFFFFFFFL)
        System.arraycopy(alac, 0, pkt, 12, alac.size)

        val key = aesKey
        val iv = aesIv
        if (key != null && iv != null) {
            val full = (alac.size / 16) * 16
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(pkt, 12, full, pkt, 12)
        }
        audioSock.send(DatagramPacket(pkt, pkt.size, addr, srvAudio))
        synchronized(ring) { ring[seq % RING] = pkt }
        seq = (seq + 1) and 0xFFFF
        ts = (ts + Alac.FRAMES_PER_PACKET) and 0xFFFFFFFFL
        nextTs = ts
    }

    /** 0..1 → -30..0 dB；靜音為 -144 dB。 */
    override fun setVolume(volume: Float, muted: Boolean) {
        if (!ready) return
        val db = if (muted || volume <= 0.001f) -144f else -30f + 30f * volume.coerceIn(0f, 1f)
        val body = "volume: ${"%.6f".format(java.util.Locale.US, db)}\r\n"
        DiagLog.d("VOL", "送出接收端音量 ${"%.1f".format(java.util.Locale.US, db)} dB（${rx.name}｜靜音=$muted｜滑桿 ${(volume * 100).toInt()}%）")
        ctl.execute {
            try {
                rtsp("SET_PARAMETER", uri, mapOf("Content-Type" to "text/parameters"), body)
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            sock?.soTimeout = 1000
            synchronized(rtspLock) { rtspUnlocked("TEARDOWN", uri, emptyMap(), null, false) }
        } catch (_: Exception) {
        }
        ctl.shutdownNow()
        try { sock?.close() } catch (_: Exception) {}
        try { audioSock.close() } catch (_: Exception) {}
        try { controlSock.close() } catch (_: Exception) {}
        try { timingSock.close() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- 背景執行緒

    private fun startThreads() {
        // 時間同步：回應接收端的 NTP 請求 (0x52 → 0x53)
        Thread({
            val buf = ByteArray(64)
            val p = DatagramPacket(buf, buf.size)
            while (!closed) {
                try { timingSock.receive(p) } catch (_: IOException) { break }
                if (p.length >= 32 && (buf[1].toInt() and 0x7F) == 0x52) {
                    val rep = ByteArray(32)
                    rep[0] = 0x80.toByte(); rep[1] = 0xD3.toByte(); rep[2] = 0; rep[3] = 7
                    System.arraycopy(buf, 24, rep, 8, 8)
                    put64(rep, 16, ntpNow())
                    put64(rep, 24, ntpNow())
                    try { timingSock.send(DatagramPacket(rep, rep.size, p.address, p.port)) } catch (_: IOException) {}
                }
            }
        }, "raop-timing").apply { isDaemon = true }.start()

        // 控制通道：處理重送請求 (0x55 → 0x56)
        Thread({
            val buf = ByteArray(64)
            val p = DatagramPacket(buf, buf.size)
            while (!closed) {
                try { controlSock.receive(p) } catch (_: IOException) { break }
                if (p.length >= 8 && (buf[1].toInt() and 0x7F) == 0x55) {
                    val missed = get16(buf, 4)
                    val count = get16(buf, 6)
                    for (i in 0 until count) {
                        val s = (missed + i) and 0xFFFF
                        val orig = synchronized(ring) { ring[s % RING] } ?: continue
                        if (get16(orig, 2) != s) continue
                        val rep = ByteArray(4 + orig.size)
                        rep[0] = 0x80.toByte(); rep[1] = 0xD6.toByte(); rep[2] = 0; rep[3] = 1
                        System.arraycopy(orig, 0, rep, 4, orig.size)
                        try { controlSock.send(DatagramPacket(rep, rep.size, p.address, p.port)) } catch (_: IOException) {}
                    }
                }
            }
        }, "raop-control").apply { isDaemon = true }.start()

        // 每秒同步封包
        ctl.scheduleAtFixedRate({
            if (srvControl == 0) return@scheduleAtFixedRate
            try {
                val b = ByteArray(20)
                b[0] = (if (firstSync) 0x90 else 0x80).toByte()
                firstSync = false
                b[1] = 0xD4.toByte(); b[2] = 0; b[3] = 7
                val cur = nextTs
                put32(b, 4, (cur - LATENCY - DelayStore.ticks) and 0xFFFFFFFFL)
                put64(b, 8, ntpNow())
                put32(b, 16, cur)
                controlSock.send(DatagramPacket(b, b.size, addr, srvControl))
            } catch (_: Exception) {
            }
        }, 0, 1, TimeUnit.SECONDS)

        // RTSP 保活
        ctl.scheduleAtFixedRate({
            try { rtsp("OPTIONS", "*") } catch (_: Exception) {}
        }, 15, 15, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- RTSP

    private var authPw: String? = null
    private var authRealm = ""
    private var authNonce = ""

    private fun md5(x: String) =
        java.security.MessageDigest.getInstance("MD5").digest(x.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun authHeader(method: String, target: String): Map<String, String> {
        val pw = authPw ?: return emptyMap()
        val ha1 = md5("iTunes:$authRealm:$pw")
        val resp = md5("$ha1:$authNonce:${md5("$method:$target")}")
        return mapOf(
            "Authorization" to "Digest username=\"iTunes\", realm=\"$authRealm\", nonce=\"$authNonce\", uri=\"$target\", response=\"$resp\""
        )
    }

    private fun failText(method: String, code: Int) = when (code) {
        401 -> "密碼錯誤或被拒絕"
        453, 470 -> "此裝置需要 AirPlay 2 配對"
        403 -> "$method 被拒絕（403）：裝置可能要求 AirPlay 2 或 MFi 驗證"
        else -> "$method 失敗：RTSP $code"
    }

    /** 送出請求；遇到 401（Digest 密碼）時請使用者輸入密碼並重試。check=true 時非 200 會丟出例外。 */
    private fun rtsp(
        method: String, target: String, headers: Map<String, String> = emptyMap(), body: String? = null,
        check: Boolean = true, raw: ByteArray? = null,
    ): Resp {
        var resp = synchronized(rtspLock) { rtspUnlocked(method, target, headers + authHeader(method, target), body, false, raw) }
        var tries = 0
        while (resp.code == 401 && tries < 3) {
            val ch = resp.headers["www-authenticate"] ?: break
            authRealm = Regex("realm=\"([^\"]*)\"").find(ch)?.groupValues?.get(1) ?: ""
            authNonce = Regex("nonce=\"([^\"]*)\"").find(ch)?.groupValues?.get(1) ?: ""
            DiagLog.d("RAOP", "裝置要求密碼（Digest，realm=$authRealm）")
            val pw = PinBroker.ask(rx.name, true, tries > 0) ?: throw IOException("已取消輸入密碼")
            authPw = pw
            tries++
            resp = synchronized(rtspLock) { rtspUnlocked(method, target, headers + authHeader(method, target), body, false, raw) }
        }
        if (check && resp.code != 200) throw IOException(failText(method, resp.code))
        return resp
    }

    private fun rtspUnlocked(
        method: String, target: String, headers: Map<String, String>, body: String?, check: Boolean,
        raw: ByteArray? = null,
    ): Resp {
        val o = out ?: throw IOException("尚未連線")
        val sb = StringBuilder()
        sb.append("$method $target RTSP/1.0\r\n")
        sb.append("CSeq: ${++cseq}\r\n")
        sb.append("User-Agent: iTunes/11.0.4 (Windows; N)\r\n")
        sb.append("Client-Instance: $clientInstance\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: 1234567890\r\n")
        sessionHdr?.let { sb.append("Session: $it\r\n") }
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        val bytes = raw ?: body?.toByteArray(Charsets.UTF_8)
        if (bytes != null) sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("\r\n")
        o.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (bytes != null) o.write(bytes)
        o.flush()

        val t0 = System.nanoTime()
        val resp = try {
            readResponse()
        } catch (e: IOException) {
            DiagLog.e("RAOP", "RTSP $method 讀取回應失敗（等待 ${(System.nanoTime() - t0) / 1_000_000} ms）", e)
            throw e
        }
        if (method != "OPTIONS" || resp.code != 200 || cseq <= 2) {
            DiagLog.d(
                "RAOP",
                "RTSP $method → ${resp.code}（${(System.nanoTime() - t0) / 1_000_000} ms）" +
                    (resp.headers["server"]?.let { " server=$it" } ?: "") +
                    (resp.headers["transport"]?.let { " transport=$it" } ?: "")
            )
        }
        if (check && resp.code != 200) {
            throw IOException(
                when (resp.code) {
                    401, 453, 470 -> "此裝置需要密碼或 AirPlay 2 配對（目前尚未支援）"
                    else -> "$method 失敗：RTSP ${resp.code}"
                }
            )
        }
        return resp
    }

    private fun readLine(): String? {
        val i = inp ?: return null
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readResponse(): Resp {
        val status = readLine() ?: throw IOException("接收端中斷連線")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("無效的 RTSP 回應")
        val h = HashMap<String, String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) h[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val len = h["content-length"]?.toIntOrNull() ?: 0
        val body = if (len > 0) {
            val b = ByteArray(len)
            var n = 0
            while (n < len) {
                val r = inp!!.read(b, n, len - n)
                if (r < 0) break
                n += r
            }
            String(b, 0, n, Charsets.UTF_8)
        } else ""
        return Resp(code, h, body)
    }

    // ---------------------------------------------------------------- 工具

    private fun rsaEncrypt(data: ByteArray): ByteArray {
        val mod = BigInteger(1, Base64.decode(APPLE_RSA_MOD, Base64.DEFAULT))
        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(mod, BigInteger.valueOf(65537)))
        val c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        c.init(Cipher.ENCRYPT_MODE, pub)
        return c.doFinal(data)
    }

    private fun hex(n: Int): String {
        val b = ByteArray(n); rnd.nextBytes(b)
        return b.joinToString("") { "%02X".format(it) }
    }

    private fun ntpNow(): Long {
        val ms = System.currentTimeMillis()
        val sec = ms / 1000 + 2208988800L
        val frac = ((ms % 1000) shl 32) / 1000
        return (sec shl 32) or frac
    }

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte()
    }
    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
    }
    private fun put64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = (v shr (56 - 8 * i)).toByte()
    }
    private fun get16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    companion object {
        private const val RING = 1024
        private const val LATENCY = 88200L // 2 秒 @44.1kHz

        // Apple 公開的 RAOP RSA 公鑰（僅在接收端不支援未加密 et=0 時使用）
        private const val APPLE_RSA_MOD =
            "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="
    }
}

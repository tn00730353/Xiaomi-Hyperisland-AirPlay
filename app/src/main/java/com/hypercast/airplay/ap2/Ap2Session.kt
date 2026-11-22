// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ap2

import com.hypercast.airplay.PinBroker
import com.hypercast.airplay.Alac
import com.hypercast.airplay.CastSession
import com.hypercast.airplay.DiagLog
import com.hypercast.airplay.Receiver
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AirPlay 2 realtime（type 96）發送端，用於 HomePod / Apple TV：
 *  1. HomeKit transient 配對（POST /pair-setup，X-Apple-HKP: 4，SRP-6a/SHA-512，PIN 3939）
 *  2. 之後整條 RTSP 連線改為 ChaCha20-Poly1305 加密
 *  3. 兩段 plist SETUP（session → stream），RECORD
 *  4. RTP 音訊（ALAC 立體聲 44.1kHz），每個封包以 shk 做 ChaCha20-Poly1305 加密
 *  5. NTP 時間同步、同步封包、重送，沿用 AirPlay 1 的做法
 */
class Ap2Session(private val rx: Receiver) : CastSession {

    private class Resp(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private val rnd = SecureRandom()
    private val rtspLock = Any()
    private var sock: Socket? = null
    private var rawIn: InputStream? = null
    private var rawOut: OutputStream? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null
    private var cseq = 0
    private val sid = (rnd.nextInt() and 0x7fffffff).toString()
    private val sessionUuid = UUID.randomUUID().toString().uppercase()
    private val dacpId = hex(8)
    private val mac = (0 until 6).joinToString(":") { i ->
        val b = rnd.nextInt(256)
        "%02X".format(if (i == 0) (b and 0xFC) or 0x02 else b)
    }
    private var uri = ""
    private var localIp = "0.0.0.0"
    private lateinit var addr: InetAddress

    private lateinit var audioSock: DatagramSocket
    private lateinit var controlSock: DatagramSocket
    private lateinit var timingSock: DatagramSocket
    private var srvAudio = 0
    private var srvControl = 0
    private var srvEvent = 0

    private var sessionKey = ByteArray(0)
    private val shk = ByteArray(32).also { rnd.nextBytes(it) }

    @Volatile private var closed = false
    private var seq = rnd.nextInt(0x10000)
    private var ts = rnd.nextInt().toLong() and 0xFFFFFFFFL
    @Volatile private var nextTs = ts
    private val ssrc = rnd.nextInt().toLong() and 0xFFFFFFFFL
    private var first = true
    private var firstSync = true
    private val ring = arrayOfNulls<ByteArray>(RING)
    @Volatile private var ready = false
    @Volatile private var sent = 0L
    @Volatile private var ntpReqs = 0
    @Volatile private var retrans = 0
    private var tick = 0
    private var fbErr = 0

    private val ctl = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ap2-ctl").apply { isDaemon = true }
    }

    // ---------------------------------------------------------------- 連線

    override fun connect() {
        addr = InetAddress.getByName(rx.host)
        DiagLog.d("AP2", "開始 AirPlay 2 連線：${rx.name}（型號 ${rx.model}，${rx.host}:${rx.port}，et=${rx.et}，pw=${rx.password}）")
        probeInfo()
        openConnection()

        audioSock = DatagramSocket(0)
        controlSock = DatagramSocket(0)
        timingSock = DatagramSocket(0)

        pairWithPinFallback()

        val keys = sessionKey
        inp = SecureIn(rawIn!!, Crypto.hkdf(keys, "Control-Salt", "Control-Read-Encryption-Key"))
        out = SecureOut(rawOut!!, Crypto.hkdf(keys, "Control-Salt", "Control-Write-Encryption-Key"))

        val plistHdr = mapOf(
            "Content-Type" to "application/x-apple-binary-plist",
            "X-Apple-Session-ID" to sessionUuid,
        )

        // SETUP 第一段：session
        val p1 = linkedMapOf<String, Any>(
            "deviceID" to mac,
            "macAddress" to mac,
            "sessionUUID" to sessionUuid,
            "timingPort" to timingSock.localPort.toLong(),
            "timingProtocol" to "NTP",
            "isMultiSelectAirPlay" to true,
            "groupContainsGroupLeader" to false,
            "model" to "iPhone14,3",
            "name" to "HyperCast",
            "osBuildVersion" to "21A329",
            "osName" to "iPhone OS",
            "osVersion" to "17.0",
            "senderSupportsRelay" to false,
            "sourceVersion" to "540.31",
            "statsCollectionEnabled" to false,
        )
        val r1 = rtsp("SETUP", uri, plistHdr, BPlist.encode(p1))
        val m1 = decodeMap(r1.body)
        DiagLog.d("AP2", "SETUP(session) 回應：${describe(m1)}")
        (m1["eventPort"] as? Long)?.let { srvEvent = it.toInt() }
        // 先連上事件通道再送第二段 SETUP（日誌顯示部分喇叭在此之前不會回應 streams SETUP）
        startEventChannel()

        // 參考 Base1：session SETUP 之後先送 RECORD（失敗也繼續），再送串流 SETUP；部分喇叭（如 Sound Pro）否則不回應串流 SETUP
        try {
            val rr = synchronized(rtspLock) {
                rtspUnlocked("RECORD", uri, mapOf("Range" to "npt=0-", "RTP-Info" to "seq=$seq;rtptime=$ts"), null, false)
            }
            DiagLog.d("AP2", "RECORD（串流 SETUP 之前）→ ${rr.code}")
        } catch (e: IOException) {
            DiagLog.e("AP2", "RECORD（串流 SETUP 之前）失敗", e)
            throw e
        }

        // SETUP 第二段：音訊串流（realtime，ALAC 44.1kHz 立體聲）
        val stream = linkedMapOf<String, Any>(
            "audioFormat" to 0x40000L,
            "audioMode" to "default",
            "controlPort" to controlSock.localPort.toLong(),
            "ct" to 2L,
            "isMedia" to true,
            "latencyMax" to 88200L,
            "latencyMin" to 11025L,
            "shk" to shk,
            "spf" to 352L,
            "sr" to 44100L,
            "type" to 96L,
            "supportsDynamicStreamID" to false,
            "streamConnectionID" to (rnd.nextInt() and 0x7fffffff).toLong(),
        )
        val r2 = rtsp("SETUP", uri, plistHdr, BPlist.encode(mapOf("streams" to listOf(stream))))
        DiagLog.d("AP2", "SETUP(streams) 回應：${describe(decodeMap(r2.body))}")
        val st = (decodeMap(r2.body)["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw IOException("HomePod 沒有回傳串流資訊")
        srvAudio = (st["dataPort"] as? Long)?.toInt() ?: throw IOException("HomePod 沒有提供音訊連接埠")
        srvControl = (st["controlPort"] as? Long)?.toInt() ?: 0
        DiagLog.d("AP2", "接收端連接埠：audio=$srvAudio control=$srvControl event=$srvEvent；本機 audio=${audioSock.localPort} control=${controlSock.localPort} timing=${timingSock.localPort}")

        ready = true
        DiagLog.d("AP2", "串流 SETUP 完成，開始送音訊")
        startThreads()
    }

    /**
     * 以 transient HomeKit 配對取得 SRP session key。
     * 注意：不可先送 /pair-pin-start —— 那會讓 Apple TV 在電視上顯示配對碼，
     * 而 transient 配對使用固定 PIN 3939，不需要螢幕配對碼。
     */
    private class AuthRejected(msg: String) : IOException(msg)

    /**
     * 先用固定碼 3939 做 transient 配對；裝置要求配對碼（pw=true、驗證失敗或 HTTP 470）時，
     * 送 /pair-pin-start 讓裝置顯示配對碼，再請使用者輸入並以該碼重新配對（最多 3 次）。
     */
    private fun pairWithPinFallback() {
        val hk3 = mapOf("X-Apple-HKP" to "3", "Content-Type" to "application/octet-stream")
        val post: (String, ByteArray) -> Pair<Int, ByteArray> = { path, body ->
            val r = http("POST", path, hk3, body)
            r.code to r.body
        }
        PairStore.get(rx.id)?.let { saved ->
            try {
                sessionKey = HomeKit.verify(saved, post, ::tlvErr)
                DiagLog.d("AP2", "使用已儲存的配對憑證完成 pair-verify")
                return
            } catch (e: HomeKit.Failure) {
                DiagLog.d("AP2", "已儲存的憑證失效：${e.message}，重新配對")
                PairStore.remove(rx.id)
                openConnection()
            }
        }
        var needPin = rx.password
        if (!needPin) {
            try {
                pairTransient("3939")
                return
            } catch (e: AuthRejected) {
                DiagLog.d("AP2", "固定碼配對被拒絕，改用使用者輸入的配對碼：${e.message}")
                needPin = true
            }
        }
        var retry = false
        for (attempt in 1..3) {
            openConnection()
            try {
                http("POST", "/pair-pin-start", mapOf("Content-Type" to "application/octet-stream"), ByteArray(0))
            } catch (e: IOException) {
                DiagLog.d("AP2", "pair-pin-start：${e.message}（裝置通常會在回應後關閉連線）")
            }
            openConnection()
            val pin = PinBroker.ask(rx.name, false, retry)
                ?: throw IOException("已取消輸入配對碼")
            try {
                val creds = HomeKit.setup(pin, post, ::tlvErr)
                DiagLog.d("AP2", "HomeKit 配對（pair-setup M1–M6）成功，開始 pair-verify")
                PairStore.put(rx.id, creds)
                sessionKey = HomeKit.verify(creds, post, ::tlvErr)
                DiagLog.d("AP2", "pair-verify 成功")
                return
            } catch (e: HomeKit.Failure) {
                DiagLog.d("AP2", "配對第 $attempt 次失敗：${e.message}")
                if (!e.auth) throw e
                retry = true
                if (attempt == 3) throw IOException("配對碼錯誤：${e.message}")
            }
        }
    }

    private fun pairTransient(pin: String) {
        val hkp = mapOf("X-Apple-HKP" to "4")
        val hdr = hkp + ("Content-Type" to "application/octet-stream")

        val srp = Srp("Pair-Setup", pin)
        var m2: Map<Int, ByteArray>? = null
        var lastErr: IOException? = null
        // transient 旗標有的實作用 1 位元組、有的用 4 位元組，依序嘗試（每次都用全新連線）
        val variants = listOf("1 位元組" to byteArrayOf(0x10), "4 位元組" to byteArrayOf(0x10, 0, 0, 0))
        for ((idx, v) in variants.withIndex()) {
            val (label, flags) = v
            val m1 = Tlv8.encode(
                listOf(Tlv8.METHOD to byteArrayOf(0), Tlv8.STATE to byteArrayOf(1), Tlv8.FLAGS to flags)
            )
            val t0 = System.nanoTime()
            try {
                val r = http("POST", "/pair-setup", hdr, m1)
                val t = Tlv8.decode(r.body)
                DiagLog.d("AP2", "pair-setup M1（flags $label）→ HTTP ${r.code}，${ms(t0)} ms，${tlvSummary(t)}")
                val err = t[Tlv8.ERROR]?.firstOrNull()?.toInt()
                if (r.code == 470) {
                    lastErr = AuthRejected(
                        "裝置要求授權（HTTP 470），不接受免配對碼的連線。Apple TV：設定 → AirPlay 與 HomeKit → 允許存取，" +
                            "選「同一網路上的任何人」並關閉密碼 / 裝置驗證；若是 HomePod 與 Apple TV 的組合，請改連組合內的 HomePod"
                    )
                    break
                }
                if (t[Tlv8.STATE]?.firstOrNull()?.toInt() == 2 && t.containsKey(Tlv8.PUBLIC_KEY) && t.containsKey(Tlv8.SALT)) {
                    m2 = t
                    break
                }
                lastErr = if (err != null) AuthRejected("配對被拒絕（${tlvErr(err)}）")
                else IOException("$PAIR_HINT（HTTP ${r.code}）")
                if (lastErr is AuthRejected) break
            } catch (e: IOException) {
                DiagLog.e("AP2", "pair-setup M1（flags $label）失敗（等待 ${ms(t0)} ms）", e)
                lastErr = IOException(
                    "配對沒有回應（${e.message}）。若 Apple TV 螢幕出現配對碼，代表它要求非 transient 配對", e
                )
            }
            if (idx < variants.lastIndex) {
                DiagLog.d("AP2", "重新建立連線後以另一種旗標格式重試")
                openConnection()
            }
        }
        val msg2 = m2 ?: throw (lastErr ?: IOException(PAIR_HINT))

        val proof = srp.computeProof(msg2[Tlv8.SALT]!!, msg2[Tlv8.PUBLIC_KEY]!!)
        val m3 = Tlv8.encode(
            listOf(
                Tlv8.STATE to byteArrayOf(3),
                Tlv8.PUBLIC_KEY to srp.publicKey,
                Tlv8.PROOF to proof,
            )
        )
        val t3 = System.nanoTime()
        val r4 = http("POST", "/pair-setup", hdr, m3)
        val t4 = Tlv8.decode(r4.body)
        DiagLog.d("AP2", "pair-setup M3 → HTTP ${r4.code}，${ms(t3)} ms，${tlvSummary(t4)}")
        val serverProof = t4[Tlv8.PROOF]
        val err4 = t4[Tlv8.ERROR]?.firstOrNull()?.toInt()
        if (err4 != null) throw AuthRejected("配對被拒絕（${tlvErr(err4)}）")
        if (serverProof == null) throw IOException("$PAIR_HINT（M4 沒有 proof）")
        if (!srp.verifyServer(serverProof)) throw AuthRejected("配對驗證失敗（SRP 證明不符，多半是配對碼錯誤）")
        sessionKey = srp.sessionKey
        DiagLog.d("AP2", "transient 配對成功，已建立加密通道金鑰")
    }

    private fun tlvErr(code: Int) = when (code) {
        1 -> "未知錯誤"
        2 -> "驗證失敗：配對碼不符"
        3 -> "裝置要求稍後再試"
        4 -> "已達配對裝置上限"
        5 -> "嘗試次數過多"
        6 -> "裝置不接受此配對方式"
        7 -> "裝置忙碌中"
        else -> "錯誤碼 $code"
    }

    private fun tlvSummary(t: Map<Int, ByteArray>): String {
        val st = t[Tlv8.STATE]?.firstOrNull()?.toInt()
        val er = t[Tlv8.ERROR]?.firstOrNull()?.toInt()
        val keys = t.entries.joinToString(",") { "0x%02X(%dB)".format(it.key, it.value.size) }
        return "state=${st ?: "-"} error=${er ?: "-"} TLV=[$keys]"
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** 建立（或重建）到接收端的 TCP 連線，並重設為未加密。 */
    private fun openConnection() {
        try { sock?.close() } catch (_: Exception) {}
        val t0 = System.nanoTime()
        val s = Socket()
        s.connect(InetSocketAddress(addr, rx.port), 5000)
        s.soTimeout = 8000
        s.tcpNoDelay = true
        sock = s
        rawIn = BufferedInputStream(s.getInputStream())
        rawOut = s.getOutputStream()
        inp = rawIn
        out = rawOut
        localIp = s.localAddress.hostAddress?.substringBefore('%') ?: "0.0.0.0"
        uri = "rtsp://$localIp/$sid"
        DiagLog.d("AP2", "TCP 已連線 ${rx.host}:${rx.port}，本機 $localIp（${ms(t0)} ms）")
    }

    /** 僅在日誌開啟時：用獨立連線讀取 GET /info（功能旗標、配對需求等），純診斷用途，失敗不影響連線。 */
    private fun probeInfo() {
        if (!DiagLog.enabled) return
        val t0 = System.nanoTime()
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(addr, rx.port), 3000)
                s.soTimeout = 3000
                s.getOutputStream().apply {
                    write("GET /info RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: AirPlay/540.31\r\n\r\n".toByteArray())
                    flush()
                }
                val i = BufferedInputStream(s.getInputStream())
                fun line(): String? {
                    val sb = StringBuilder()
                    while (true) {
                        val c = i.read()
                        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                        if (c == '\n'.code) break
                        if (c != '\r'.code) sb.append(c.toChar())
                    }
                    return sb.toString()
                }
                val status = line() ?: throw IOException("沒有回應")
                var len = 0
                while (true) {
                    val l = line() ?: break
                    if (l.isEmpty()) break
                    if (l.lowercase().startsWith("content-length:")) len = l.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val body = ByteArray(len)
                var n = 0
                while (n < len) {
                    val r = i.read(body, n, len - n)
                    if (r < 0) break
                    n += r
                }
                DiagLog.d("AP2", "GET /info → $status（${ms(t0)} ms，$n 位元組）")
                if (n > 0) {
                    val m = decodeMap(body.copyOf(n))
                    DiagLog.d("AP2", "/info 內容：${describe(m, maxDepth = 1).take(2500)}")
                }
            }
        } catch (e: Exception) {
            DiagLog.d("AP2", "GET /info 失敗（${ms(t0)} ms）：${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** 把 plist 內容轉成可讀字串（位元組陣列只記長度，不記內容）。 */
    private fun describe(v: Any?, maxDepth: Int = 3, depth: Int = 0): String = when (v) {
        null -> "null"
        is ByteArray -> "<${v.size}B>"
        is Map<*, *> ->
            if (depth >= maxDepth) "<dict ${v.size}>"
            else v.entries.joinToString(", ", "{", "}") { "${it.key}=${describe(it.value, maxDepth, depth + 1)}" }
        is List<*> ->
            if (depth >= maxDepth) "<list ${v.size}>"
            else v.joinToString(", ", "[", "]") { describe(it, maxDepth, depth + 1) }
        is String -> if (v.length > 60) v.take(60) + "…" else v
        else -> v.toString()
    }

    // ---------------------------------------------------------------- 串流

    override fun sendFrame(alac: ByteArray) {
        if (closed) return
        val hdr = ByteArray(12)
        hdr[0] = 0x80.toByte()
        hdr[1] = (if (first) 0xE0 else 0x60).toByte()
        first = false
        put16(hdr, 2, seq)
        put32(hdr, 4, ts)
        put32(hdr, 8, ssrc)

        val nonce = ByteArray(12)
        nonce[4] = seq.toByte()
        nonce[5] = (seq shr 8).toByte()
        val aad = hdr.copyOfRange(4, 12)
        val sealed = Crypto.chachaSeal(shk, nonce, aad, alac)   // 密文 ‖ tag(16)

        val pkt = ByteArray(12 + sealed.size + 8)
        System.arraycopy(hdr, 0, pkt, 0, 12)
        System.arraycopy(sealed, 0, pkt, 12, sealed.size)
        System.arraycopy(nonce, 4, pkt, 12 + sealed.size, 8)

        audioSock.send(DatagramPacket(pkt, pkt.size, addr, srvAudio))
        synchronized(ring) { ring[seq % RING] = pkt }
        seq = (seq + 1) and 0xFFFF
        ts = (ts + Alac.FRAMES_PER_PACKET) and 0xFFFFFFFFL
        nextTs = ts
        sent++
    }

    override fun setVolume(volume: Float, muted: Boolean) {
        if (!ready) return   // 連線完成前不可送 SET_PARAMETER（會插進配對流程）
        val db = if (muted || volume <= 0.001f) -144f else -30f + 30f * volume.coerceIn(0f, 1f)
        val body = "volume: ${"%.6f".format(java.util.Locale.US, db)}\r\n".toByteArray()
        ctl.execute {
            try { rtsp("SET_PARAMETER", uri, mapOf("Content-Type" to "text/parameters"), body) } catch (_: Exception) {}
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        DiagLog.d("AP2", "關閉連線（共送出 $sent 個封包）")
        try {
            sock?.soTimeout = 1000
            val body = BPlist.encode(mapOf("streams" to listOf(mapOf("type" to 96L))))
            synchronized(rtspLock) {
                rtspUnlocked("TEARDOWN", uri, mapOf("Content-Type" to "application/x-apple-binary-plist"), body, false)
            }
        } catch (_: Exception) {
        }
        ctl.shutdownNow()
        try { sock?.close() } catch (_: Exception) {}
        try { audioSock.close() } catch (_: Exception) {}
        try { controlSock.close() } catch (_: Exception) {}
        try { timingSock.close() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- 背景工作

    private fun startThreads() {
        // NTP 時間同步 (0x52 → 0x53)
        Thread({
            val buf = ByteArray(64)
            val p = DatagramPacket(buf, buf.size)
            while (!closed) {
                try { timingSock.receive(p) } catch (_: IOException) { break }
                if (p.length >= 32 && (buf[1].toInt() and 0x7F) == 0x52) {
                    if (ntpReqs++ == 0) DiagLog.d("AP2", "收到第一個 NTP 時間請求（來自 ${p.address.hostAddress}:${p.port}）")
                    val rep = ByteArray(32)
                    rep[0] = 0x80.toByte(); rep[1] = 0xD3.toByte(); rep[2] = 0; rep[3] = 7
                    System.arraycopy(buf, 24, rep, 8, 8)
                    put64(rep, 16, ntpNow())
                    put64(rep, 24, ntpNow())
                    try { timingSock.send(DatagramPacket(rep, rep.size, p.address, p.port)) } catch (_: IOException) {}
                }
            }
        }, "ap2-timing").apply { isDaemon = true }.start()

        // 控制通道：重送請求 (0x55 → 0x56)
        Thread({
            val buf = ByteArray(64)
            val p = DatagramPacket(buf, buf.size)
            while (!closed) {
                try { controlSock.receive(p) } catch (_: IOException) { break }
                if (p.length >= 8 && (buf[1].toInt() and 0x7F) == 0x55) {
                    val missed = get16(buf, 4)
                    val count = get16(buf, 6)
                    if (retrans++ == 0) DiagLog.d("AP2", "收到第一個重送請求（seq=$missed，數量 $count）")
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
        }, "ap2-control").apply { isDaemon = true }.start()

        // 每秒同步封包
        ctl.scheduleAtFixedRate({
            if (srvControl == 0) return@scheduleAtFixedRate
            try {
                val b = ByteArray(20)
                b[0] = (if (firstSync) 0x90 else 0x80).toByte()
                firstSync = false
                b[1] = 0xD4.toByte(); b[2] = 0; b[3] = 7
                val cur = nextTs
                put32(b, 4, (cur - LATENCY) and 0xFFFFFFFFL)
                put64(b, 8, ntpNow())
                put32(b, 16, cur)
                controlSock.send(DatagramPacket(b, b.size, addr, srvControl))
                if (DiagLog.enabled && ++tick % 10 == 0) {
                    DiagLog.d("AP2", "串流統計：已送 $sent 封包，NTP 請求 $ntpReqs 次，重送請求 $retrans 次")
                }
            } catch (_: Exception) {
            }
        }, 0, 1, TimeUnit.SECONDS)

        // AirPlay 2 以 POST /feedback 保活
        ctl.scheduleAtFixedRate({
            try {
                rtsp("POST", "/feedback")
            } catch (e: Exception) {
                if (fbErr++ < 3) DiagLog.e("AP2", "feedback 保活失敗", e)
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    /** 事件通道：盡力連線並以 200 OK 回應接收端的請求；任何失敗都忽略。 */
    private fun startEventChannel() {
        if (srvEvent == 0) return
        Thread({
            try {
                val es = Socket()
                es.connect(InetSocketAddress(addr, srvEvent), 3000)
                DiagLog.d("AP2", "事件通道已連線（port $srvEvent）")
                // 事件通道的方向與控制通道相反：把它視為由接收端發起
                val ein = SecureIn(BufferedInputStream(es.getInputStream()),
                    Crypto.hkdf(sessionKey, "Events-Salt", "Events-Write-Encryption-Key"))
                val eout = SecureOut(es.getOutputStream(),
                    Crypto.hkdf(sessionKey, "Events-Salt", "Events-Read-Encryption-Key"))
                es.soTimeout = 0
                val reply = "RTSP/1.0 200 OK\r\nContent-Length: 0\r\nServer: AirTunes/366.0\r\nCSeq: 0\r\n\r\n".toByteArray()
                val buf = ByteArray(4096)
                while (!closed) {
                    val n = ein.read(buf)
                    if (n < 0) break
                    val text = String(buf, 0, n, Charsets.ISO_8859_1)
                    val c = Regex("CSeq: (\\d+)").find(text)?.groupValues?.get(1) ?: "0"
                    eout.write(String(reply).replace("CSeq: 0", "CSeq: $c").toByteArray())
                    eout.flush()
                }
            } catch (e: Exception) {
                if (!closed) DiagLog.d("AP2", "事件通道結束：${e.javaClass.simpleName} ${e.message}")
            }
        }, "ap2-event").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- RTSP / HTTP

    private fun http(method: String, path: String, headers: Map<String, String>, body: ByteArray): Resp =
        synchronized(rtspLock) {
            val o = out ?: throw IOException("尚未連線")
            val sb = StringBuilder("$method $path HTTP/1.1\r\n")
            sb.append("User-Agent: AirPlay/540.31\r\n")
            headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("Content-Length: ${body.size}\r\n\r\n")
            o.write(sb.toString().toByteArray())
            if (body.isNotEmpty()) o.write(body)
            o.flush()
            val t0 = System.nanoTime()
            val r = readResponse()
            DiagLog.d("AP2", "HTTP $method $path → ${r.code}（${ms(t0)} ms，回應 ${r.body.size} 位元組）")
            r
        }

    private fun rtsp(method: String, target: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Resp =
        synchronized(rtspLock) { rtspUnlocked(method, target, headers, body, true) }

    private fun rtspUnlocked(
        method: String, target: String, headers: Map<String, String>, body: ByteArray?, check: Boolean
    ): Resp {
        val o = out ?: throw IOException("尚未連線")
        val sb = StringBuilder("$method $target RTSP/1.0\r\n")
        sb.append("CSeq: ${++cseq}\r\n")
        sb.append("User-Agent: AirPlay/540.31\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: 1234567890\r\n")
        sb.append("Client-Instance: $dacpId\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (body != null) sb.append("Content-Length: ${body.size}\r\n")
        sb.append("\r\n")
        val head = sb.toString().toByteArray()
        o.write(if (body != null) head + body else head)
        o.flush()

        val t0 = System.nanoTime()
        val resp = try {
            readResponse()
        } catch (e: IOException) {
            DiagLog.e("AP2", "RTSP $method 讀取回應失敗（等待 ${ms(t0)} ms）", e)
            throw e
        }
        if (!(target == "/feedback" && resp.code == 200)) {
            DiagLog.d("AP2", "RTSP $method → ${resp.code}（${ms(t0)} ms，回應 ${resp.body.size} 位元組）")
        }
        if (check && resp.code != 200) {
            throw IOException(
                when (resp.code) {
                    401, 403, 453, 470 -> PAIR_HINT
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
        val status = readLine() ?: throw IOException("HomePod 中斷連線")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("無效的回應：$status")
        val h = HashMap<String, String>()
        while (true) {
            val line = readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) h[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val len = h["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var n = 0
        while (n < len) {
            val r = inp!!.read(body, n, len - n)
            if (r < 0) break
            n += r
        }
        return Resp(code, h, body.copyOf(n))
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeMap(b: ByteArray): Map<String, Any> =
        if (b.isEmpty()) emptyMap() else (BPlist.decode(b) as? Map<String, Any>) ?: emptyMap()

    // ---------------------------------------------------------------- 工具

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

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte(); b[o + 1] = (v shr 16).toByte(); b[o + 2] = (v shr 8).toByte(); b[o + 3] = v.toByte()
    }
    private fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v shr (56 - 8 * i)).toByte() }
    private fun get16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    companion object {
        private const val RING = 1024
        private const val LATENCY = 88200L
        private const val PAIR_HINT =
            "HomePod 拒絕配對。請在「家庭」App → HomePod 設定 → 允許喇叭與電視存取，改選「所有人」或「同一網路上的任何人」，並關閉密碼"
    }
}

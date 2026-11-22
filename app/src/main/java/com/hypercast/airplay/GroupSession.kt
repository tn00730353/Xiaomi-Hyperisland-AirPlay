// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import com.hypercast.airplay.ap2.Ap2Session
import java.io.IOException

/**
 * 組合裝置（例如「HomePod 組合和投影機」）：
 * 先嘗試直接連組合主機（Apple TV）；它回 470 要求授權時，改成直接連組合內的其他 AirPlay 2 成員
 * （HomePod），同一份音訊同時送給每個成員，各成員依 NTP 時間同步播放。
 * 成員連線不跳配對碼輸入框；任一成員中途失敗只會被移除，全部失敗才中斷。
 */
class GroupSession(private val primary: Receiver, private val members: List<Receiver>) : CastSession {
    @Volatile private var active: List<CastSession> = emptyList()

    override fun connect() {
        try {
            val p = Ap2Session(primary, allowPin = false)
            try {
                p.connect()
                active = listOf(p)
                return
            } catch (e: Exception) {
                try { p.close() } catch (_: Exception) {}
                if (e !is Ap2Session.AuthRejected) throw e
                DiagLog.d("GROUP", "${primary.name} 要求授權，改連組合成員：${members.joinToString { it.name }}")
            }
        } catch (e: Ap2Session.AuthRejected) {
            throw e
        }
        val ok = ArrayList<CastSession>()
        val errs = ArrayList<String>()
        for (m in members) {
            val s = Ap2Session(m, allowPin = false)
            try {
                s.connect()
                ok += s
                DiagLog.d("GROUP", "成員 ${m.name} 連線成功")
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                errs += "${m.name}：${e.message}"
                DiagLog.e("GROUP", "成員 ${m.name} 連線失敗", e)
            }
        }
        if (ok.isEmpty()) throw IOException("組合成員都無法連線（${errs.joinToString("；")}）")
        active = ok
        DiagLog.d("GROUP", "串流成員數：${ok.size}/${members.size}")
    }

    override fun sendFrame(alac: ByteArray) {
        val cur = active
        var failed: ArrayList<CastSession>? = null
        for (s in cur) {
            try { s.sendFrame(alac) } catch (e: Exception) {
                if (cur.size == 1) throw e
                (failed ?: ArrayList<CastSession>().also { failed = it }) += s
                DiagLog.e("GROUP", "成員送出失敗，已移除", e)
            }
        }
        failed?.let { f -> active = cur - f.toSet(); f.forEach { try { it.close() } catch (_: Exception) {} } }
    }

    override fun setVolume(volume: Float, muted: Boolean) {
        active.forEach { try { it.setVolume(volume, muted) } catch (_: Exception) {} }
    }

    override fun close() {
        val cur = active; active = emptyList()
        cur.forEach { try { it.close() } catch (_: Exception) {} }
    }
}

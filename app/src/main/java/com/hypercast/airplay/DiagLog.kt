// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 診斷日誌（Beta3：每一項資料都要使用者同意，才會開始記錄）。
 *  - 同意畫面列出每一項資料與所需權限；全部同意（逐項或「全部同意」）之後才會啟用。
 *  - 每筆日誌帶有標籤，寫入前檢查該標籤所屬項目（見 [DiagItem]）是否已同意，沒同意的一筆都不寫。
 *  - 只寫入本機 App 私有目錄，不會自動上傳；使用者按「匯出」才會產生檔案。
 *  - 寫入前一律遮蔽：IPv4 末段、MAC、UUID。
 *  - 絕不記錄：配對碼、加密金鑰、音訊內容、Wi-Fi 名稱 / 密碼。
 *  - 大小上限約 1 MB，超過時捨棄最舊的紀錄。
 */
object DiagLog {
    const val CONSENT_VERSION = 2   // Beta3：改為逐項同意；舊版（1）的同意不算數，需要重新同意
    private const val PREFS = "hypercast_diag"
    private const val MAX_BYTES = 1_000_000

    data class UiState(
        val enabled: Boolean = false,
        val entries: Int = 0,
        val sizeKb: Int = 0,
        val place: String = "",
    )

    val state = MutableStateFlow(UiState())

    @Volatile
    var enabled = false
        private set

    @Volatile private var consent: Set<DiagItem> = emptySet()
    @Volatile private var place = ""
    @Volatile private var lastEventMs = 0L
    @Volatile private var entries = 0
    private var app: Context? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "diag-io").apply { isDaemon = true } }
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)   // 只在 io 執行緒使用

    // ------------------------------------------------------------ 生命週期

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = sp.getStringSet("consent_items", emptySet()) ?: emptySet()
        consent = DiagItem.values().filter { it.key in keys }.toSet()
        val wasOn = sp.getBoolean("enabled", false)
        val valid = sp.getInt("consent_version", 0) >= CONSENT_VERSION && consent.size == DiagItem.values().size
        enabled = wasOn && valid
        if (wasOn && !valid) sp.edit().putBoolean("enabled", false).apply()   // 舊版同意書：需要重新逐項同意
        if (enabled) DiagCollector.installCrashHandler()
        place = sp.getString("place", "") ?: ""
        io.execute {
            entries = try {
                file()?.takeIf { it.exists() }?.useLines { s -> s.count() } ?: 0
            } catch (_: Exception) {
                0
            }
            publish()
        }
    }

    /**
     * 使用者在同意頁逐項同意（或按「全部同意」）之後呼叫。**每一項都要同意**，少一項就不會啟用。
     * @return 是否已啟用
     */
    fun enable(ctx: Context, items: Set<DiagItem>): Boolean {
        init(ctx)
        val c = app ?: return false
        if (!items.containsAll(DiagItem.all)) return false
        consent = items
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true)
            .putInt("consent_version", CONSENT_VERSION)
            .putLong("consent_at", System.currentTimeMillis())
            .putStringSet("consent_items", items.map { it.key }.toSet())
            .apply()
        enabled = true
        DiagCollector.installCrashHandler()
        writeSessionHeader(c)
        refreshLocation(c)
        io.execute { publish() }
        return true
    }

    fun disable() {
        d("LOG", "日誌記錄已由使用者關閉")
        enabled = false
        consent = emptySet()   // 關閉＝撤回同意；之後要再開，必須重新逐項同意
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("enabled", false)?.remove("consent_items")?.apply()
        io.execute { publish() }
    }

    fun clear() {
        lastEventMs = 0L
        io.execute {
            try { file()?.delete() } catch (_: Exception) {}
            entries = 0
            publish()
        }
    }

    // ------------------------------------------------------------ 寫入

    fun d(tag: String, msg: String) = add(tag, "D", msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t == null) msg else msg + "\n" + android.util.Log.getStackTraceString(t).trimEnd()
        add(tag, "E", full)
    }

    /** 標籤所屬項目已同意才寫入；「LOG」是日誌自己的啟停紀錄，永遠允許；沒登記的標籤一律不寫。 */
    private fun allowed(tag: String): Boolean {
        if (tag == "LOG") return true
        val item = DiagItem.byTag[tag] ?: return false
        return item in consent
    }

    private fun add(tag: String, lvl: String, msg: String) {
        if (!enabled || !allowed(tag)) return
        val now = System.currentTimeMillis()
        val th = Thread.currentThread().name
        lastEventMs = now
        io.execute {
            try {
                val f = file() ?: return@execute
                val line = "${tsFmt.format(Date(now))} $lvl/$tag [$th] ${maskText(msg)}\n"
                f.appendText(line, Charsets.UTF_8)
                entries++
                if (f.length() > MAX_BYTES) trim(f)
                publish()
            } catch (_: Exception) {
            }
        }
    }

    private fun trim(f: File) {
        val bytes = f.readBytes()
        var s = bytes.size - MAX_BYTES * 6 / 10
        if (s <= 0) return
        while (s < bytes.size && bytes[s] != '\n'.code.toByte()) s++
        s++
        if (s >= bytes.size) return
        val marker = "…（較舊的紀錄已因容量上限捨棄）\n".toByteArray(Charsets.UTF_8)
        f.writeBytes(marker + bytes.copyOfRange(s, bytes.size))
    }

    private fun file(): File? {
        val c = app ?: return null
        val dir = File(c.filesDir, "diag")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "hypercast.log")
    }

    private fun publish() {
        val size = try { (file()?.length() ?: 0L) / 1024 } catch (_: Exception) { 0L }
        state.value = UiState(enabled, entries, size.toInt(), place)
    }

    // ------------------------------------------------------------ 遮蔽

    private val macRe = Regex("""\b([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})(:[0-9A-Fa-f]{2}){3}\b""")
    private val bareMacRe = Regex("""\b([0-9A-Fa-f]{6})[0-9A-Fa-f]{6}(?=@)""")
    private val uuidRe = Regex("""\b([0-9A-Fa-f]{8})-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b""")
    private val ipv4Re = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.\d{1,3}\b""")
    private val ipv6Re = Regex("""\b(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}\b""")

    fun maskText(s: String): String = s
        .replace(macRe) { "${it.groupValues[1]}:${it.groupValues[2]}:${it.groupValues[3]}:**:**:**" }
        .replace(bareMacRe) { it.groupValues[1] + "******" }
        .replace(uuidRe) { it.groupValues[1] + "-****" }
        .replace(ipv4Re) { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}.x" }
        .replace(ipv6Re) { "[IPv6 已遮蔽]" }

    // ------------------------------------------------------------ 工作階段標頭

    private fun writeSessionHeader(c: Context) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        d("LOG", "===== 日誌記錄開始（同意書版本 $CONSENT_VERSION，$stamp）=====")
        d("LOG", "已逐項同意 ${consent.size}/${DiagItem.values().size} 項：" + DiagItem.values().filter { it in consent }.joinToString("、") { it.title })
        DiagCollector.snapshotAll(c, "開始記錄")
    }

    // ------------------------------------------------------------ 概略位置（選用）

    /** 只在記錄啟用、且使用者授權「概略位置」時讀取一次；座標四捨五入到約 1 公里，不持續追蹤。 */
    @SuppressLint("MissingPermission")
    fun refreshLocation(ctx: Context) {
        if (!enabled || DiagItem.LOCATION !in consent) return
        val c = ctx.applicationContext
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            d("LOC", "未授權概略位置，地點記為「未知地點」")
            return
        }
        Thread({
            try {
                val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var loc: Location? = null
                for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                    val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
                    if (l != null && (loc == null || l.time > loc.time)) loc = l
                }
                if (loc == null && Build.VERSION.SDK_INT >= 30) loc = currentLocation(c, lm)
                if (loc == null) {
                    d("LOC", "取不到位置（定位服務可能未開啟），地點記為「未知地點」")
                    return@Thread
                }
                val lat = (loc.latitude * 100).roundToInt() / 100.0
                val lng = (loc.longitude * 100).roundToInt() / 100.0
                val name = geocode(c, lat, lng) ?: "${lat}_${lng}"
                place = name
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("place", name).apply()
                d("LOC", "概略位置：$name（約 $lat, $lng）")
                io.execute { publish() }
            } catch (e: Exception) {
                d("LOC", "讀取位置失敗：${e.javaClass.simpleName} ${e.message}")
            }
        }, "diag-loc").apply { isDaemon = true }.start()
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(30)
    private fun currentLocation(c: Context, lm: LocationManager): Location? {
        val latch = CountDownLatch(1)
        var result: Location? = null
        try {
            lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ContextCompat.getMainExecutor(c)) { l ->
                result = l
                latch.countDown()
            }
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun geocode(c: Context, lat: Double, lng: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else {
            val a = Geocoder(c, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()
            if (a == null) null
            else listOfNotNull(
                (a.locality ?: a.subAdminArea ?: a.adminArea)?.takeIf { it.isNotBlank() },
                a.subLocality?.takeIf { it.isNotBlank() },
            ).distinct().joinToString("").ifBlank { null }
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------ 匯出

    /** 檔名：HyperCast_日期_時間_地點.log（時間 = 最後一筆事件發生時；地點 = 最近一次記錄的概略地點）。 */
    fun exportFileName(): String {
        val t = if (lastEventMs > 0) lastEventMs else System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(t))
        val p = place.ifBlank { "未知地點" }.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(40)
        return "HyperCast_${stamp}_$p.log"
    }

    /** 日誌最後一筆事件的時間（信件主旨用）。 */
    fun exportStamp(): String {
        val t = if (lastEventMs > 0) lastEventMs else System.currentTimeMillis()
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
    }

    fun hasData(): Boolean = entries > 0

    /** 等待佇列中的寫入完成（匯出前呼叫，須在背景執行緒）。 */
    fun flush() {
        try { io.submit(Runnable { }).get(3, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    fun snapshot(): String {
        val body = try { file()?.takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "" } catch (_: Exception) { "" }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val head = buildString {
            append("# HyperCast 診斷日誌\n")
            append("# 匯出時間: $now\n")
            append("# 最近記錄地點: ${place.ifBlank { "未知地點" }}\n")
            append("# App 版本: ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）\n")
            append("# 已同意的項目: " + (if (consent.isEmpty()) "（記錄已關閉；以下是關閉前的紀錄）"
                else DiagItem.values().filter { it in consent }.joinToString("、") { it.title }) + "\n")
            append("# 已遮蔽：IP 末段、MAC、UUID。不含：配對碼、加密金鑰、音訊內容、通知內容、Wi-Fi 名稱 / 密碼 / BSSID。\n\n")
        }
        return head + body
    }
}

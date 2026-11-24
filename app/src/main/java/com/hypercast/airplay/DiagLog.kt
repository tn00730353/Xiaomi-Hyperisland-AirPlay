// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 診斷日誌搜集項目：每一項都清楚列出搜集內容與所需權限，
 * 必須經使用者逐項同意（或按「全部同意」）後，才會開始搜集資料。
 */
enum class DiagCategory(
    val title: String,
    val summary: String,
    val permissionLabel: String,
    val runtimePermission: String? = null,
    val minRuntimeSdk: Int = 29,
) {
    DEVICE_OS(
        title = "裝置與系統環境",
        summary = "品牌型號、SoC、ABI、Android / HyperOS 版本與區域碼、安全性更新、App 版本、RAM / Heap 與開機時長",
        permissionLabel = "無需額外系統權限（僅讀取公開系統屬性）",
    ),
    NETWORK_WIFI(
        title = "網路與 Wi-Fi 狀態",
        summary = "傳輸介面（Wi-Fi / 行動 / 乙太 / VPN 警示）、MTU、Wi-Fi 頻段、訊號強度、上下行速率與 Wi-Fi 標準（不含 SSID 與密碼）",
        permissionLabel = "網路狀態、Wi-Fi 狀態（安裝時自動授予）",
    ),
    MDNS_AIRPLAY(
        title = "AirPlay 與區網探索",
        summary = "探索到的 AirPlay / RAOP 裝置清單、型號、HomePod 立體聲組群組旗標、TXT 紀錄與解析錯誤（IP 末段與 MAC 自動遮蔽）",
        permissionLabel = "網際網路、Wi-Fi 群播狀態（安裝時自動授予）",
    ),
    STREAM_PROTOCOL(
        title = "串流協定與連線紀錄",
        summary = "AirPlay 2 / RAOP 交握耗時與回應碼、配對狀態（不含 PIN 與金鑰）、連接埠、RTP / NTP / 重送統計與例外堆疊",
        permissionLabel = "無需額外系統權限（僅記錄 App 內部連線流程）",
    ),
    AUDIO_CAPTURE(
        title = "音訊擷取與音量管線",
        summary = "音訊模式、輸出裝置類型、串流音量、AudioRecord / MediaProjection 狀態、延遲、音量平衡與近 10 秒最大振幅（不含原始音訊）",
        permissionLabel = "錄音權限（RECORD_AUDIO）、修改音訊設定",
        runtimePermission = Manifest.permission.RECORD_AUDIO,
    ),
    ISLAND_NOTIF(
        title = "通知與 HyperIsland 狀態",
        summary = "通知總開關、各頻道重要性、勿擾模式、HyperOS 超級島協定與焦點通知權限、param_v2 參數與媒體回呼事件",
        permissionLabel = "通知權限（POST_NOTIFICATIONS，Android 13+ 需授權）",
        runtimePermission = Manifest.permission.POST_NOTIFICATIONS,
        minRuntimeSdk = 33,
    ),
    POWER_BACKGROUND(
        title = "電源、背景與系統限制",
        summary = "電量與電池溫度、省電模式、電池最佳化白名單、背景限制、溫控等級、偏好設定快照與本 App 自身錯誤 logcat",
        permissionLabel = "無需額外系統權限（僅讀取電池與本 App 自身程序狀態）",
    ),
    APPROX_LOCATION(
        title = "概略地點（日誌檔名標記）",
        summary = "開啟日誌或連線時讀取一次概略位置（四捨五入至約 1 公里），轉成縣市／行政區名稱作為日誌檔名，不持續追蹤",
        permissionLabel = "概略位置（ACCESS_COARSE_LOCATION，需授權）",
        runtimePermission = Manifest.permission.ACCESS_COARSE_LOCATION,
    );

    fun requiresRuntimePrompt(): Boolean =
        runtimePermission != null && Build.VERSION.SDK_INT >= minRuntimeSdk

    fun isPermissionGranted(ctx: Context): Boolean {
        val perm = runtimePermission ?: return true
        if (Build.VERSION.SDK_INT < minRuntimeSdk) return true
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 診斷日誌（需使用者逐項同意或全部同意所有項目後才會啟動搜集）。
 *  - 只寫入本機 App 私有目錄，不會自動上傳；只有使用者匯出或寄信才會離開手機。
 *  - 寫入前一律遮蔽：IPv4 末段、IPv6、MAC、UUID。
 *  - 絕不記錄：配對碼、加密金鑰、音訊原始內容、Wi-Fi 名稱 / 密碼。
 *  - 大小上限約 1.5 MB，超過時捨棄最舊的紀錄。
 */
object DiagLog {
    const val CONSENT_VERSION = 2
    private const val PREFS = "hypercast_diag"
    private const val MAX_BYTES = 1_500_000

    data class UiState(
        val enabled: Boolean = false,
        val entries: Int = 0,
        val sizeKb: Int = 0,
        val place: String = "",
        val consented: Set<DiagCategory> = emptySet(),
    ) {
        val allConsented: Boolean get() = consented.size == DiagCategory.entries.size
    }

    val state = MutableStateFlow(UiState())

    @Volatile
    var enabled = false
        private set

    @Volatile private var consented: Set<DiagCategory> = emptySet()
    @Volatile private var place = ""
    @Volatile private var lastEventMs = 0L
    @Volatile private var entries = 0
    private var app: Context? = null

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "diag-io").apply { isDaemon = true } }
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // ------------------------------------------------------------ 生命週期與同意管理

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        val sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedVer = sp.getInt("consent_version", 0)
        val savedSet = sp.getStringSet("consented_categories", emptySet()).orEmpty()
            .mapNotNull { runCatching { DiagCategory.valueOf(it) }.getOrNull() }
            .toSet()
        consented = if (savedVer == CONSENT_VERSION) savedSet else emptySet()
        enabled = savedVer == CONSENT_VERSION &&
            consented.size == DiagCategory.entries.size &&
            sp.getBoolean("enabled", false)
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

    fun setCategoryConsent(ctx: Context, cat: DiagCategory, agree: Boolean) {
        init(ctx)
        val next = if (agree) consented + cat else consented - cat
        consented = next
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putInt("consent_version", CONSENT_VERSION)
            ?.putStringSet("consented_categories", next.map { it.name }.toSet())
            ?.apply()
        if (!agree && enabled && next.size < DiagCategory.entries.size) {
            d("SYS", "使用者取消同意「${cat.title}」，停止搜集日誌")
            enabled = false
            app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("enabled", false)?.apply()
        }
        publish()
    }

    fun setAllConsented(ctx: Context, agree: Boolean) {
        init(ctx)
        val next = if (agree) DiagCategory.entries.toSet() else emptySet()
        consented = next
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putInt("consent_version", CONSENT_VERSION)
            ?.putStringSet("consented_categories", next.map { it.name }.toSet())
            ?.apply()
        if (!agree && enabled) {
            d("SYS", "使用者取消全部同意，停止搜集日誌")
            enabled = false
            app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("enabled", false)?.apply()
        }
        publish()
    }

    fun isCategoryAllowed(cat: DiagCategory): Boolean = enabled && (cat in consented)

    /** 必須所有項目皆已同意（逐項同意或全部同意）後才會開始搜集資料。 */
    fun enable(ctx: Context): Boolean {
        init(ctx)
        val c = app ?: return false
        if (consented.size < DiagCategory.entries.size) return false
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true)
            .putInt("consent_version", CONSENT_VERSION)
            .putStringSet("consented_categories", consented.map { it.name }.toSet())
            .putLong("consent_at", System.currentTimeMillis())
            .apply()
        enabled = true
        collectFullSnapshot(c)
        io.execute { publish() }
        return true
    }

    fun disable() {
        if (enabled) d("SYS", "日誌記錄已由使用者關閉")
        enabled = false
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("enabled", false)?.apply()
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

    fun refreshSnapshot(ctx: Context) {
        if (!enabled) return
        collectFullSnapshot(ctx.applicationContext)
    }

    // ------------------------------------------------------------ 寫入

    private fun categoryForTag(tag: String): DiagCategory = when (tag.uppercase(Locale.US)) {
        "SYS", "DEV" -> DiagCategory.DEVICE_OS
        "NET", "WIFI" -> DiagCategory.NETWORK_WIFI
        "MDNS" -> DiagCategory.MDNS_AIRPLAY
        "AP2", "RAOP", "CAST" -> DiagCategory.STREAM_PROTOCOL
        "VOL", "LVL", "AUDIO" -> DiagCategory.AUDIO_CAPTURE
        "ISLAND", "MEDIA", "NOTIF" -> DiagCategory.ISLAND_NOTIF
        "PWR", "MEM", "PREFS", "LOGCAT" -> DiagCategory.POWER_BACKGROUND
        "LOC" -> DiagCategory.APPROX_LOCATION
        else -> DiagCategory.STREAM_PROTOCOL
    }

    fun d(tag: String, msg: String) = add(tag, "D", msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t == null) msg else msg + "\n" + android.util.Log.getStackTraceString(t).trimEnd()
        add(tag, "E", full)
    }

    private fun add(tag: String, lvl: String, msg: String) {
        if (!enabled) return
        if (categoryForTag(tag) !in consented) return
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
        state.value = UiState(enabled, entries, size.toInt(), place, consented)
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

    // ------------------------------------------------------------ 全方位快照採樣

    private fun sysProp(key: String): String = try {
        val cls = Class.forName("android.os.SystemProperties")
        (cls.getMethod("get", String::class.java).invoke(null, key) as? String) ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun collectFullSnapshot(c: Context) {
        io.execute {
            collectDeviceAndOs(c)
            collectNetworkAndWifi(c)
            collectMdnsAndCast(c)
            collectAudioPipeline(c)
            collectIslandAndNotif(c)
            collectPowerAndBackground(c)
        }
        refreshLocation(c)
    }

    private fun collectDeviceAndOs(c: Context) {
        if (!isCategoryAllowed(DiagCategory.DEVICE_OS)) return
        d("SYS", "===== 日誌記錄開始（同意書版本 $CONSENT_VERSION，已同意 ${consented.size}/${DiagCategory.entries.size} 項）=====")
        d("SYS", "App: HyperCast v${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）")
        runCatching { SystemInfo.rows(c).forEach { (k, v) -> d("SYS", "$k: $v") } }
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val region = sysProp("ro.miui.region").ifBlank { sysProp("ro.product.locale.region") }
        val modDev = sysProp("ro.product.mod_device")
        d("SYS", "品牌/代號/硬體: ${Build.BRAND} / ${Build.DEVICE} / $soc｜mod_device=$modDev｜區域=$region")
        d("SYS", "ABI: ${Build.SUPPORTED_ABIS.joinToString()}｜targetSdk=${c.applicationInfo.targetSdkVersion}｜語言=${Locale.getDefault()}｜時區=${TimeZone.getDefault().id}")
        runCatching {
            val dm = c.resources.displayMetrics
            d("SYS", "螢幕: ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi（density=${dm.density}）｜開機時長 ${SystemClock.elapsedRealtime() / 60000} 分鐘")
        }
        runCatching {
            val am = c.getSystemService(ActivityManager::class.java) ?: return@runCatching
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val rt = Runtime.getRuntime()
            val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val maxHeapMb = rt.maxMemory() / (1024 * 1024)
            d("SYS", "記憶體: 可用 ${mi.availMem / (1024 * 1024)} MB / 總計 ${mi.totalMem / (1024 * 1024)} MB（lowMemory=${mi.lowMemory}）｜App Heap ${usedHeapMb}/${maxHeapMb} MB")
        }
        val permSummary = DiagCategory.entries.filter { it.runtimePermission != null }
            .joinToString("｜") { "${it.title}=${if (it.isPermissionGranted(c)) "已授權" else "未授權"}" }
        d("SYS", "動態權限狀態：$permSummary")
    }

    private fun collectNetworkAndWifi(c: Context) {
        if (!isCategoryAllowed(DiagCategory.NETWORK_WIFI)) return
        runCatching {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            val tr = ArrayList<String>()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) tr += "Wi-Fi"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) tr += "行動網路"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) tr += "乙太網路"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) tr += "VPN（可能阻擋區網 mDNS/AirPlay）"
            val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val notMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            val airplane = Settings.Global.getInt(c.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            d("NET", "目前網路: ${if (tr.isEmpty()) "無連線" else tr.joinToString(" + ")}｜validated=$validated｜notMetered=$notMetered｜飛航=$airplane")
            val lp = cm?.getLinkProperties(net)
            if (lp != null) {
                val addrs = lp.linkAddresses.joinToString { it.address.hostAddress ?: "" }
                d("NET", "介面=${lp.interfaceName}｜MTU=${lp.mtu}｜DNS數=${lp.dnsServers.size}｜本機位址=$addrs")
            }
            @Suppress("DEPRECATION")
            val wi = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
            if (wi != null && wi.frequency > 0) {
                val band = when {
                    wi.frequency >= 5925 -> "6 GHz"
                    wi.frequency >= 4900 -> "5 GHz"
                    else -> "2.4 GHz"
                }
                val std = if (Build.VERSION.SDK_INT >= 30) {
                    val code = wi.wifiStandard
                    when {
                        Build.VERSION.SDK_INT >= 33 && code == ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7 (802.11be)"
                        code == ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6/6E (802.11ax)"
                        code == ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5 (802.11ac)"
                        code == ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4 (802.11n)"
                        else -> "standard=$code"
                    }
                } else "API<30"
                d("NET", "Wi-Fi: $band（${wi.frequency} MHz）｜標準=$std｜訊號=${wi.rssi} dBm｜Tx=${wi.txLinkSpeedMbps} / Rx=${wi.rxLinkSpeedMbps} Mbps（link=${wi.linkSpeed}）")
            }
        }.onFailure {
            d("NET", "讀取網路資訊失敗：${it.javaClass.simpleName}")
        }
    }

    private fun collectMdnsAndCast(c: Context) {
        if (isCategoryAllowed(DiagCategory.MDNS_AIRPLAY)) {
            val rx = AirPlayStore.state.value.receivers
            d("MDNS", "目前已發現 ${rx.size} 台裝置")
            rx.forEach {
                d("MDNS", "  ${it.name}｜型號 ${it.model}｜${it.host}:${it.port}｜et=${it.et}｜pw=${it.password}｜AirPlay2=${it.ap2}｜群組=${it.groupId}｜gid=${it.gid}｜pgid=${it.pgid}｜tsid=${it.tsid}｜igl=${it.igl}｜gcgl=${it.gcgl}")
            }
        }
        if (isCategoryAllowed(DiagCategory.STREAM_PROTOCOL)) {
            val st = AirPlayStore.state.value
            d("CAST", "目前狀態：${st.phase}｜playing=${st.playing}｜音量 ${(st.volume * 100).toInt()}%｜靜音=${st.muted}｜延遲=${DelayStore.ms} ms｜目標：" +
                st.targets.joinToString(" ＋ ") { "${it.rx.name}（型號 ${it.rx.model}｜${it.rx.host}｜AirPlay2=${it.rx.ap2}｜pair=${it.pair}｜聲道 ${it.role.label}）" }.ifBlank { "無" })
        }
    }

    private fun collectAudioPipeline(c: Context) {
        if (!isCategoryAllowed(DiagCategory.AUDIO_CAPTURE)) return
        runCatching {
            val am = c.getSystemService(AudioManager::class.java) ?: return@runCatching
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { "type=${it.type}(ch=${it.channelCounts.joinToString("/")})" }
                .distinct()
                .joinToString(", ")
            d("AUDIO", "AudioManager: mode=${am.mode}｜ringer=${am.ringerMode}｜媒體音量=$cur/$max｜isMusicActive=${am.isMusicActive}｜輸出裝置=[$outs]")
        }.onFailure {
            d("AUDIO", "讀取音訊管線失敗：${it.javaClass.simpleName}")
        }
    }

    private fun collectIslandAndNotif(c: Context) {
        if (!isCategoryAllowed(DiagCategory.ISLAND_NOTIF)) return
        runCatching {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return@runCatching
            val proto = HyperIsland.protocol(c)
            val canFocus = HyperIsland.canShow(c)
            val pr = PrefsStore.state.value
            val channels = nm.notificationChannels.joinToString("｜") { ch ->
                "${ch.id}(imp=${ch.importance},dnd=${ch.canBypassDnd()})"
            }
            d("NOTIF", "通知總開關=${nm.areNotificationsEnabled()}｜勿擾模式=${nm.currentInterruptionFilter}｜HyperOS焦點協定=$proto｜canShowFocus=$canFocus")
            d("NOTIF", "島設定: mode=${pr.island}｜idleIsland=${pr.idleIsland}｜firstFloat=${pr.focusFirstFloat}｜separate=${pr.focusSeparate}｜lowCh=${pr.focusLowChannel}｜updatable=${pr.focusUpdatable}｜sec=${pr.focusSeconds}｜mediaKeys=${pr.mediaKeys}")
            d("NOTIF", "通知頻道：$channels")
        }.onFailure {
            d("NOTIF", "讀取通知與超級島狀態失敗：${it.javaClass.simpleName}")
        }
    }

    private fun collectPowerAndBackground(c: Context) {
        if (!isCategoryAllowed(DiagCategory.POWER_BACKGROUND)) return
        runCatching {
            val pm = c.getSystemService(PowerManager::class.java) ?: return@runCatching
            val am = c.getSystemService(ActivityManager::class.java) ?: return@runCatching
            val bat = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (scale > 0 && level >= 0) level * 100 / scale else -1
            val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val tempC = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val ignoringOpt = pm.isIgnoringBatteryOptimizations(c.packageName)
            d("PWR", "電量=$pct%｜充電狀態=$status｜電池溫度=${tempC}°C｜省電模式=${pm.isPowerSaveMode}｜溫控等級=${pm.currentThermalStatus}｜忽略電池最佳化=$ignoringOpt｜背景受限=${am.isBackgroundRestricted}")
            val pr = PrefsStore.state.value
            d("PREFS", "設定快照: mirrorVolume=${pr.mirrorVolume}｜leveler=${pr.leveler}｜haptics=${pr.haptics}｜delayMs=${DelayStore.ms}")
        }.onFailure {
            d("PWR", "讀取電源狀態失敗：${it.javaClass.simpleName}")
        }
        runCatching {
            val pid = android.os.Process.myPid()
            val proc = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "50", "--pid=$pid", "*:W").start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            proc.waitFor(2, TimeUnit.SECONDS)
            if (out.isNotBlank()) {
                d("LOGCAT", "本程式最近警告/錯誤（PID $pid）：\n$out")
            }
        }
    }

    // ------------------------------------------------------------ 概略位置

    @SuppressLint("MissingPermission")
    fun refreshLocation(ctx: Context) {
        if (!isCategoryAllowed(DiagCategory.APPROX_LOCATION)) return
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

    fun exportFileName(): String {
        val t = if (lastEventMs > 0) lastEventMs else System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(t))
        val p = place.ifBlank { "未知地點" }.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(40)
        return "HyperCast_v${BuildConfig.VERSION_NAME.replace(" ", "_")}_${stamp}_$p.log"
    }

    fun exportStamp(): String {
        val t = if (lastEventMs > 0) lastEventMs else System.currentTimeMillis()
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
    }

    fun hasData(): Boolean = entries > 0

    fun flush() {
        try { io.submit(Runnable { }).get(3, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    fun snapshot(): String {
        val body = try { file()?.takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: "" } catch (_: Exception) { "" }
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val head = buildString {
            append("# HyperCast v${BuildConfig.VERSION_NAME} 診斷日誌（同意書 v$CONSENT_VERSION）\n")
            append("# 匯出時間: $now\n")
            append("# 最近記錄地點: ${place.ifBlank { "未知地點" }}\n")
            append("# 已同意項目: ${consented.joinToString("、") { it.title }}\n")
            append("# 已遮蔽：IP 末段、IPv6、MAC、UUID。不含：配對碼、加密金鑰、音訊原始內容、Wi-Fi 名稱與密碼。\n\n")
        }
        return head + body
    }
}

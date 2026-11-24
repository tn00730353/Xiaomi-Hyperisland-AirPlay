// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.core.content.ContextCompat
import java.net.Inet6Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Beta3：診斷日誌各項資料的實際收集。每一筆資料都帶標籤，由 [DiagLog] 依「該標籤所屬項目是否已同意」決定要不要寫入
 * （標籤與項目的對應見 [DiagItem]）。所有函式在記錄沒開啟時直接返回。
 *
 * 一律不收集：配對碼、加密金鑰、音訊內容、通知標題與內容、Wi-Fi 名稱 / 密碼 / BSSID、其他 App 的資料。
 */
object DiagCollector {

    private fun on() = DiagLog.enabled
    private fun yn(b: Boolean) = if (b) "是" else "否"
    private fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------ 組合快照

    /** 開始記錄、以及每次 App 啟動（記錄已開啟）時：寫一份完整快照。 */
    fun snapshotAll(c: Context, why: String) {
        if (!on()) return
        device(c)
        config(c)
        network(c, why)
        discovery()
        streamState(why)
        audio(c)
        notif(c)
        privacySettings(c)
        privacyState(c, why)
        power(c, why)
        exitReasons(c)
    }

    /** 串流開始：這些是「通知被擋住」時最需要對照的資料。 */
    fun onCastStart(c: Context) {
        if (!on()) return
        DiagLog.d("PRIV", "MediaProjection 已啟動（只擷取聲音，沒有建立畫面擷取）。Android 15 以上，螢幕擷取 / 分享期間系統可能隱藏通知的私人內容；以下記錄用來和串流前後對照")
        network(c, "串流開始")
        audio(c)
        notif(c)
        privacySettings(c)
        privacyState(c, "串流開始")
        power(c, "串流開始")
        streamState("串流開始")
    }

    /** 串流停止：和串流開始對照，看通知與鎖屏狀態是否恢復。 */
    fun onCastStop(c: Context, why: String) {
        if (!on()) return
        notif(c)
        privacyState(c, "串流停止（$why）")
        power(c, "串流停止")
    }

    /** 串流中每分鐘取樣一次。 */
    fun sample(c: Context) {
        if (!on()) return
        power(c, "取樣")
        privacyState(c, "取樣")
        streamState("取樣")
    }

    // ------------------------------------------------------------ 手機與 App 資訊（DEVICE：APP / DEV）

    fun device(c: Context) {
        if (!on()) return
        DiagLog.d("APP", "HyperCast ${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）｜" +
            "${if (BuildConfig.DEBUG) "debug" else "release"}｜targetSdk ${c.applicationInfo.targetSdkVersion}")
        try {
            val pi = c.packageManager.getPackageInfo(c.packageName, 0)
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            DiagLog.d("APP", "安裝來源 ${installer(c) ?: "未知（側載）"}｜首次安裝 ${f.format(Date(pi.firstInstallTime))}｜最後更新 ${f.format(Date(pi.lastUpdateTime))}")
        } catch (e: Exception) {
            DiagLog.d("APP", "讀取安裝資訊失敗：${e.javaClass.simpleName}")
        }
        try {
            SystemInfo.rows(c).forEach { (k, v) -> DiagLog.d("DEV", "$k: $v") }
        } catch (_: Exception) {}
        DiagLog.d("DEV", "品牌/型號/代號: ${Build.BRAND} / ${Build.MODEL} / ${Build.DEVICE}｜產品 ${Build.PRODUCT}｜硬體 ${Build.HARDWARE}｜ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        DiagLog.d("DEV", "韌體: ${Build.DISPLAY}｜${Build.FINGERPRINT}")
        val props = listOf(
            "ro.mi.os.version.name", "ro.mi.os.version.incremental", "ro.miui.ui.version.name",
            "ro.miui.region", "ro.product.mod_device", "ro.build.version.incremental",
        ).map { it to SystemInfo.prop(it) }.filter { it.second.isNotBlank() }
        if (props.isNotEmpty()) DiagLog.d("DEV", "小米系統屬性: " + props.joinToString("｜") { "${it.first}=${it.second}" })
        try {
            val dm = c.resources.displayMetrics
            val disp = c.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            val cfg = c.resources.configuration
            val night = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val hz = disp?.refreshRate?.let { String.format(Locale.US, "%.0f", it) } ?: "?"
            DiagLog.d("DEV", "螢幕: ${dm.widthPixels}x${dm.heightPixels}｜${dm.densityDpi} dpi｜更新率 $hz Hz｜深色模式 ${yn(night)}｜字體縮放 ${cfg.fontScale}")
        } catch (_: Exception) {}
        try {
            val am = c.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            DiagLog.d("DEV", "記憶體: 總計 ${mi.totalMem shr 20} MB｜App 記憶體等級 ${am.memoryClass} MB｜低記憶體機型=${yn(am.isLowRamDevice)}")
        } catch (_: Exception) {}
        DiagLog.d("DEV", "語言: ${Locale.getDefault()}｜時區: ${TimeZone.getDefault().id}")
    }

    @Suppress("DEPRECATION")
    private fun installer(c: Context): String? = try {
        if (Build.VERSION.SDK_INT >= 30) c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName
        else c.packageManager.getInstallerPackageName(c.packageName)
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------ App 設定（CONFIG：CFG）

    fun config(c: Context) {
        if (!on()) return
        DiagLog.d("CFG", "設定: ${PrefsStore.state.value}")
        try {
            DiagLog.d("CFG", "主題 ${com.hypercast.airplay.ui.ThemeStore.load(c)}｜延遲補償 ${DelayStore.load(c)} ms")
        } catch (_: Exception) {}
        val st = AirPlayStore.state.value
        DiagLog.d("CFG", "音量 ${(st.volume * 100).toInt()}%｜靜音=${yn(st.muted)}")
    }

    // ------------------------------------------------------------ 網路環境（NETWORK：NET）

    @Suppress("DEPRECATION")
    fun network(c: Context, why: String) {
        if (!on()) return
        try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            val tr = ArrayList<String>()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) tr += "Wi-Fi"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) tr += "行動網路"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) tr += "乙太網路"
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) tr += "VPN（可能阻擋區網連線）"
            val saver = when (cm?.restrictBackgroundStatus) {
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "開啟"
                ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "開啟（本 App 在白名單）"
                else -> "關閉"
            }
            DiagLog.d("NET", "[$why] 目前網路: ${if (tr.isEmpty()) "未知" else tr.joinToString(" + ")}｜" +
                "計費網路=${yn(cm?.isActiveNetworkMetered == true)}｜省流量模式=$saver")
            val lp = cm?.getLinkProperties(net)
            if (lp != null) {
                val v6 = lp.linkAddresses.any { it.address is Inet6Address && !it.address.isLinkLocalAddress }
                DiagLog.d("NET", "介面 ${lp.interfaceName}｜IPv6=${yn(v6)}｜私人 DNS=${yn(lp.isPrivateDnsActive)}")
            }
            val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wi = wm?.connectionInfo
            if (wi != null && wi.frequency > 0) {
                val band = when {
                    wi.frequency < 3000 -> "2.4 GHz"
                    wi.frequency < 5900 -> "5 GHz"
                    else -> "6 GHz"
                }
                val std = if (Build.VERSION.SDK_INT >= 30) when (wi.wifiStandard) {
                    4 -> "Wi-Fi 4"
                    5 -> "Wi-Fi 5"
                    6 -> "Wi-Fi 6"
                    8 -> "Wi-Fi 7"
                    else -> "代碼 ${wi.wifiStandard}"
                } else "?"
                DiagLog.d("NET", "Wi-Fi $band（${wi.frequency} MHz）｜訊號 ${wi.rssi} dBm｜速率 上 ${wi.txLinkSpeedMbps} / 下 ${wi.rxLinkSpeedMbps} Mbps｜$std")
            }
        } catch (e: Exception) {
            DiagLog.d("NET", "讀取網路資訊失敗：${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 區網裝置探索（DISCOVERY：MDNS）

    fun discovery() {
        if (!on()) return
        val rx = AirPlayStore.state.value.receivers
        DiagLog.d("MDNS", "目前已發現 ${rx.size} 台裝置")
        rx.forEach {
            DiagLog.d("MDNS", "  ${it.name}｜型號 ${it.model}｜${it.host}:${it.port}｜et=${it.et}｜pw=${it.password}｜AirPlay2=${it.ap2}｜群組=${it.groupId}｜gid=${it.gid}｜pgid=${it.pgid}｜tsid=${it.tsid}｜igl=${it.igl}｜gcgl=${it.gcgl}")
        }
    }

    // ------------------------------------------------------------ 連線與串流（STREAM：CAST）

    fun streamState(why: String) {
        if (!on()) return
        val st = AirPlayStore.state.value
        DiagLog.d("CAST", "[$why] 狀態：${st.phase}｜音量 ${(st.volume * 100).toInt()}%｜靜音=${yn(st.muted)}｜目標：" +
            st.targets.joinToString(" ＋ ") { "${it.rx.name}（型號 ${it.rx.model}｜${it.rx.host}｜AirPlay2=${it.rx.ap2}｜聲道 ${it.role.label}）" }.ifBlank { "無" })
    }

    // ------------------------------------------------------------ 音訊擷取與音量（AUDIO：MEDIA）

    fun audio(c: Context) {
        if (!on()) return
        try {
            val am = c.getSystemService(AudioManager::class.java)
            val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString("、") { outName(it.type) }
            DiagLog.d("MEDIA", "錄音權限=${yn(granted(c, Manifest.permission.RECORD_AUDIO))}｜" +
                "手機音量 ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}｜" +
                "音樂播放中=${yn(am.isMusicActive)}｜音訊模式 ${am.mode}｜輸出裝置：${devs.ifBlank { "無" }}｜" +
                "輸出取樣率 ${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}｜每緩衝幀數 ${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")
        } catch (e: Exception) {
            DiagLog.d("MEDIA", "讀取音訊資訊失敗：${e.javaClass.simpleName}")
        }
    }

    private fun outName(t: Int) = when (t) {
        1 -> "聽筒"
        2 -> "內建喇叭"
        3 -> "有線耳機麥克風"
        4 -> "有線耳機"
        7 -> "藍牙通話"
        8 -> "藍牙音樂"
        9 -> "HDMI"
        11 -> "USB 裝置"
        22 -> "USB 耳機"
        26 -> "低功耗藍牙耳機"
        27 -> "低功耗藍牙喇叭"
        else -> "類型$t"
    }

    // ------------------------------------------------------------ 通知（NOTIFICATION：NOTIF）

    fun notif(c: Context) {
        if (!on()) return
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            val perm = if (Build.VERSION.SDK_INT >= 33) granted(c, Manifest.permission.POST_NOTIFICATIONS) else true
            val filter = when (nm.currentInterruptionFilter) {
                1 -> "全部允許"
                2 -> "僅限優先"
                3 -> "完全靜音"
                4 -> "僅限鬧鐘"
                else -> "未知"
            }
            DiagLog.d("NOTIF", "通知權限=${yn(perm)}｜App 通知總開關=${yn(nm.areNotificationsEnabled())}｜勿擾=$filter｜通知模式=${PrefsStore.state.value.island}")
            nm.notificationChannels.forEach { ch ->
                DiagLog.d("NOTIF", "  頻道 ${ch.id}｜重要性 ${ch.importance}｜鎖屏可見性 ${ch.lockscreenVisibility}｜可繞過勿擾=${yn(ch.canBypassDnd())}")
            }
            val act = nm.activeNotifications
            DiagLog.d("NOTIF", "本 App 顯示中的通知 ${act.size} 則" +
                if (act.isEmpty()) "" else "：" + act.joinToString("；") {
                    "id=${it.id}｜頻道=${it.notification.channelId}｜旗標=0x${Integer.toHexString(it.notification.flags)}｜持續性=${yn(it.isOngoing)}｜可清除=${yn(it.isClearable)}"
                })
            val p = HyperIsland.protocol(c)
            DiagLog.d("NOTIF", "焦點通知協定=$p｜允許顯示=${yn(p > 0 && HyperIsland.canShow(c))}")
        } catch (e: Exception) {
            DiagLog.d("NOTIF", "讀取通知資訊失敗：${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 系統隱私保護與鎖屏（PRIVACY：PRIV）

    private fun gset(c: Context, k: String): String =
        (try { Settings.Global.getString(c.contentResolver, k) } catch (_: Exception) { null }) ?: "未設定"

    private fun sset(c: Context, k: String): String =
        (try { Settings.Secure.getString(c.contentResolver, k) } catch (_: Exception) { null }) ?: "未設定"

    /** 系統設定值（「未設定」＝系統沒有這個值或讀不到，不代表關閉）。 */
    fun privacySettings(c: Context) {
        if (!on()) return
        DiagLog.d("PRIV", "螢幕分享保護（開發者選項，Android 15+）停用=" +
            "${gset(c, "disable_screen_share_protections_for_apps_and_notifications")}｜開發者選項已開啟=${gset(c, "development_settings_enabled")}")
        DiagLog.d("PRIV", "鎖屏通知設定：顯示=${sset(c, "lock_screen_show_notifications")}｜允許私人內容=${sset(c, "lock_screen_allow_private_notifications")}（未設定＝讀不到，不代表關閉）")
    }

    /** 螢幕與鎖屏即時狀態。 */
    fun privacyState(c: Context, why: String) {
        if (!on()) return
        try {
            val km = c.getSystemService(KeyguardManager::class.java)
            val pm = c.getSystemService(PowerManager::class.java)
            DiagLog.d("PRIV", "[$why] 螢幕亮著=${yn(pm.isInteractive)}｜鎖屏顯示中=${yn(km.isKeyguardLocked)}｜" +
                "裝置已鎖定=${yn(km.isDeviceLocked)}｜有設定鎖屏密碼=${yn(km.isDeviceSecure)}")
        } catch (e: Exception) {
            DiagLog.d("PRIV", "讀取鎖屏狀態失敗：${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------ 電池、背景限制與效能（POWER：PWR）

    fun power(c: Context, why: String) {
        if (!on()) return
        try {
            val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val temp = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val pm = c.getSystemService(PowerManager::class.java)
            val am = c.getSystemService(ActivityManager::class.java)
            val thermal = when (pm.currentThermalStatus) {
                0 -> "正常"
                1 -> "輕微"
                2 -> "中等"
                3 -> "嚴重"
                4 -> "危急"
                5 -> "緊急"
                6 -> "即將關機"
                else -> "未知"
            }
            val t = if (temp >= 0) String.format(Locale.US, "%.1f", temp / 10.0) else "?"
            DiagLog.d("PWR", "[$why] 電量 $pct%｜${if (charging) "充電中" else "未充電"}｜電池 $t°C｜省電模式=${yn(pm.isPowerSaveMode)}｜" +
                "Doze=${yn(pm.isDeviceIdleMode)}｜電池最佳化白名單=${yn(pm.isIgnoringBatteryOptimizations(c.packageName))}｜" +
                "背景受限=${yn(am.isBackgroundRestricted)}｜待機分組 ${bucket(c)}｜發熱 $thermal")
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            DiagLog.d("PWR", "記憶體：系統可用 ${mi.availMem shr 20} MB（門檻 ${mi.threshold shr 20} MB，吃緊=${yn(mi.lowMemory)}）｜" +
                "Java 堆 ${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20} MB｜原生 ${Debug.getNativeHeapAllocatedSize() shr 20} MB｜程序重要性 ${me.importance}")
        } catch (e: Exception) {
            DiagLog.d("PWR", "讀取電源 / 效能資訊失敗：${e.javaClass.simpleName}")
        }
    }

    private fun bucket(c: Context): String = try {
        when (c.getSystemService(UsageStatsManager::class.java).appStandbyBucket) {
            10 -> "使用中"
            20 -> "常用"
            30 -> "經常"
            40 -> "很少"
            45 -> "受限"
            50 -> "從未使用"
            else -> "未知"
        }
    } catch (_: Exception) {
        "未知"
    }

    // ------------------------------------------------------------ 當機與異常結束（CRASH：CRASH / EXIT）

    private var crashInstalled = false

    /** 記錄未捕捉的例外（同步等候寫入後，再交還給系統原本的處理）。只在記錄開啟時才會寫入。 */
    @Synchronized
    fun installCrashHandler() {
        if (crashInstalled) return
        crashInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                DiagLog.e("CRASH", "未捕捉的例外（執行緒 ${t.name}）：${e.javaClass.name}: ${e.message}", e)
                DiagLog.flush()
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** 系統記錄的「本 App 上次為什麼結束」（Android 11 以上）。 */
    fun exitReasons(c: Context) {
        if (!on()) return
        if (Build.VERSION.SDK_INT < 30) {
            DiagLog.d("EXIT", "Android 11 以下沒有程序結束紀錄")
            return
        }
        try {
            val am = c.getSystemService(ActivityManager::class.java)
            val list = am.getHistoricalProcessExitReasons(c.packageName, 0, 5)
            if (list.isEmpty()) {
                DiagLog.d("EXIT", "沒有歷史結束紀錄")
                return
            }
            val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            list.forEach { i ->
                DiagLog.d("EXIT", "${f.format(Date(i.timestamp))}｜${reasonName(i.reason)}｜重要性 ${i.importance}｜狀態 ${i.status}｜${i.description ?: ""}")
            }
        } catch (e: Exception) {
            DiagLog.d("EXIT", "讀取結束紀錄失敗：${e.javaClass.simpleName}")
        }
    }

    private fun reasonName(r: Int) = when (r) {
        1 -> "自行結束"
        2 -> "被訊號終止"
        3 -> "記憶體不足被回收"
        4 -> "Java 當機"
        5 -> "原生當機"
        6 -> "沒有回應（ANR）"
        7 -> "初始化失敗"
        8 -> "權限變更"
        9 -> "資源使用過度"
        10 -> "使用者要求結束"
        11 -> "使用者強制停止"
        12 -> "相依程序結束"
        13 -> "其他"
        14 -> "被凍結"
        else -> "未知($r)"
    }
}

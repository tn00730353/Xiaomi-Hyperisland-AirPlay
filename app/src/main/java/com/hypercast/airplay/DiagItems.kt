// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest

/** 權限類型（同意頁依此顯示說明與狀態）。 */
enum class PermKind(val label: String) {
    None("不需要權限"),
    Normal("一般權限・安裝時自動授予"),
    Runtime("執行時權限・需要你按「允許」"),
    Special("特殊授權"),
}

/**
 * 某一項診斷資料需要的權限。
 * @param manifest 對應的 Android 權限名稱；沒有（例如螢幕音訊擷取授權、焦點通知）時為 null。
 */
data class DiagPerm(
    val name: String,
    val manifest: String?,
    val kind: PermKind,
    val note: String = "",
)

/**
 * 診斷日誌可收集的每一項資料。
 * Beta3：**每一項都要使用者同意（或按「全部同意」）之後，才會開始記錄**；
 * 日誌的每一筆都帶有標籤（[tags]），寫入前會檢查它所屬的項目是否已同意，沒同意的項目一筆都不會寫。
 */
enum class DiagItem(
    val key: String,
    val title: String,
    val summary: String,
    val fields: List<String>,
    val perms: List<DiagPerm>,
    val tags: List<String>,
    val icon: Int,
    val top: Long,       // 圖示磚漸層：上緣色
    val bottom: Long,    // 圖示磚漸層：下緣色
) {
    DEVICE(
        "device", "手機與 App 資訊",
        "判斷問題是否只發生在特定機型或系統版本",
        listOf(
            "品牌、型號、代號、處理器架構、韌體版本",
            "Android 版本、安全性更新、小米 HyperOS 版本與版本號",
            "螢幕解析度、更新率、深色模式、字體縮放",
            "記憶體大小、是否為低記憶體機型、語言與時區",
            "HyperCast 版本、安裝來源、首次安裝與更新時間",
        ),
        listOf(DiagPerm("不需要任何權限", null, PermKind.None)),
        listOf("APP", "DEV", "SYS"),
        R.drawable.ic_diag_device, 0xFF7B9BFF, 0xFF3F5FE8,
    ),
    CONFIG(
        "config", "App 設定",
        "確認問題是否和你的設定有關",
        listOf(
            "通知顯示方式與焦點通知的進階選項",
            "同步手機音量鍵、音量平衡、觸覺回饋、延遲補償、外觀主題",
            "目前的音量與靜音狀態（只有數值）",
        ),
        listOf(DiagPerm("不需要任何權限", null, PermKind.None)),
        listOf("CFG"),
        R.drawable.ic_diag_config, 0xFFB08CFF, 0xFF7A4FE0,
    ),
    NETWORK(
        "network", "網路環境",
        "找出 Wi-Fi、VPN 或省流量模式造成的連線問題",
        listOf(
            "連線類型（Wi-Fi／行動網路／VPN／乙太網路）、是否為計費網路、省流量模式",
            "Wi-Fi 頻段（2.4／5／6 GHz）、訊號強度、連線速率、Wi-Fi 世代",
            "是否有 IPv6、私人 DNS 是否啟用、網路介面名稱",
            "不含：Wi-Fi 名稱、密碼、BSSID；IP 位址最後一段已遮蔽",
        ),
        listOf(
            DiagPerm("查看網路狀態", Manifest.permission.ACCESS_NETWORK_STATE, PermKind.Normal),
            DiagPerm("查看 Wi-Fi 狀態", Manifest.permission.ACCESS_WIFI_STATE, PermKind.Normal),
        ),
        listOf("NET"),
        R.drawable.ic_diag_net, 0xFF5CC8FF, 0xFF1E8CE8,
    ),
    DISCOVERY(
        "discovery", "區網裝置探索（mDNS）",
        "確認有沒有找到你的 HomePod、Apple TV 與其分組資訊",
        listOf(
            "發現到的 AirPlay 裝置：名稱、型號、功能旗標、連接埠、是否需要密碼",
            "HomePod 立體聲組的分組欄位",
            "IP 位址最後一段與 MAC 位址已遮蔽",
        ),
        listOf(
            DiagPerm("連接網際網路 / 區域網路", Manifest.permission.INTERNET, PermKind.Normal),
            DiagPerm("接收區網多點傳送（mDNS）", Manifest.permission.CHANGE_WIFI_MULTICAST_STATE, PermKind.Normal),
        ),
        listOf("MDNS"),
        R.drawable.ic_diag_disc, 0xFF5BE0C3, 0xFF14A88C,
    ),
    STREAM(
        "stream", "AirPlay 連線與串流",
        "分析連線失敗、中斷與同步問題的主要依據",
        listOf(
            "配對與各階段的回應碼、耗時、錯誤訊息與例外堆疊",
            "串流目標、聲道設定、連線狀態變化、重新連線",
            "封包與同步統計（數量、遺失、延遲）",
            "不含：配對碼、加密金鑰",
        ),
        listOf(DiagPerm("連接網際網路 / 區域網路", Manifest.permission.INTERNET, PermKind.Normal)),
        listOf("AP2", "RAOP", "CAST"),
        R.drawable.ic_airplay, 0xFFFF7BA8, 0xFFE03A78,
    ),
    AUDIO(
        "audio", "音訊擷取與音量",
        "判斷是否擷取到聲音，以及音量為什麼忽大忽小",
        listOf(
            "錄音權限與螢幕音訊擷取是否已授權、擷取開始與停止時間",
            "取樣率、緩衝大小、近 10 秒最大振幅（只是一個數字，用來判斷是不是靜音）",
            "音量平衡的增益變化、手機音量與按鍵同步事件、目前輸出裝置類型",
            "不含：音訊內容",
        ),
        listOf(
            DiagPerm("錄音（擷取系統聲音用）", Manifest.permission.RECORD_AUDIO, PermKind.Runtime),
            DiagPerm("螢幕音訊擷取授權（MediaProjection）", null, PermKind.Special,
                "每次開始串流時由系統詢問；HyperCast 只擷取聲音，不擷取畫面"),
            DiagPerm("調整音訊設定", Manifest.permission.MODIFY_AUDIO_SETTINGS, PermKind.Normal),
        ),
        listOf("MEDIA", "LVL", "VOL"),
        R.drawable.ic_diag_audio, 0xFFFFA45C, 0xFFE8661A,
    ),
    NOTIFICATION(
        "notification", "通知與焦點通知（超級島）",
        "查出通知被擋住、清不掉的原因",
        listOf(
            "通知權限、通知頻道的重要性、勿擾模式狀態",
            "本 App 目前顯示中的通知數量、編號與旗標（前景服務、持續性、可否清除）",
            "焦點通知協定版本、是否允許焦點通知、每次附上島參數的紀錄",
            "不含：任何通知的標題與內容，也不會讀取其他 App 的通知",
        ),
        listOf(
            DiagPerm("顯示通知（Android 13 以上）", Manifest.permission.POST_NOTIFICATIONS, PermKind.Runtime),
            DiagPerm("焦點通知（HyperOS）", null, PermKind.Special, "在系統設定中允許本 App 顯示焦點通知"),
            DiagPerm("前景服務", Manifest.permission.FOREGROUND_SERVICE, PermKind.Normal),
            DiagPerm("前景服務（螢幕擷取類型）", Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION, PermKind.Normal),
        ),
        listOf("ISLAND", "NOTIF"),
        R.drawable.ic_diag_notif, 0xFFFF6B6B, 0xFFD92E3A,
    ),
    PRIVACY(
        "privacy", "系統隱私保護與鎖屏狀態",
        "找出「所有通知被鎖屏擋住、滑不掉」是不是系統保護造成的",
        listOf(
            "螢幕是否亮著、鎖屏是否顯示中、裝置是否已鎖定（串流開始、結束與每分鐘取樣）",
            "鎖屏通知的顯示設定（是否顯示、是否隱藏私人內容）",
            "Android 15 以上「螢幕分享保護」開發者選項的狀態",
            "串流前後的差異，用來判斷是否由螢幕擷取授權觸發",
        ),
        listOf(DiagPerm("不需要任何權限", null, PermKind.None, "只讀取系統設定的開關值")),
        listOf("PRIV"),
        R.drawable.ic_diag_privacy, 0xFF6FD58B, 0xFF2BA655,
    ),
    POWER(
        "power", "電池、背景限制與效能",
        "找出背景串流被系統中斷、發熱降頻或記憶體不足的原因",
        listOf(
            "電量、充電狀態、電池溫度",
            "省電模式、Doze、背景執行限制、電池最佳化白名單、App 待機分組",
            "機身發熱等級、系統可用記憶體與 App 記憶體用量、程序重要性",
        ),
        listOf(DiagPerm("不需要任何權限", null, PermKind.None)),
        listOf("PWR"),
        R.drawable.ic_diag_power, 0xFFB6D94C, 0xFF7FA81A,
    ),
    CRASH(
        "crash", "當機與異常結束",
        "知道 App 是自己當機、被系統回收，還是被你劃掉",
        listOf(
            "未捕捉的例外：類型、訊息與堆疊（內含的 IP、MAC 會遮蔽）",
            "上次 App 被結束的原因（系統的程序結束紀錄，只含本 App）：被系統回收、記憶體不足、當機、使用者結束等",
        ),
        listOf(DiagPerm("不需要任何權限", null, PermKind.None)),
        listOf("CRASH", "EXIT"),
        R.drawable.ic_diag_crash, 0xFFFFC24D, 0xFFE59210,
    ),
    LOCATION(
        "location", "概略位置",
        "只用來替匯出的日誌檔命名，不會追蹤你",
        listOf(
            "每次開始記錄時讀取一次概略位置，四捨五入到約 1 公里",
            "只用來替日誌檔取地名；不會持續追蹤",
            "轉換成地名時，系統的地理編碼服務可能會處理這組座標",
        ),
        listOf(
            DiagPerm("概略位置", Manifest.permission.ACCESS_COARSE_LOCATION, PermKind.Runtime,
                "即使你在系統對話框按「不允許」，仍可開始記錄，檔名地點會是「未知地點」"),
        ),
        listOf("LOC"),
        R.drawable.ic_diag_loc, 0xFF8E9AAF, 0xFF566277,
    ),
    ;

    companion object {
        /** 日誌標籤 → 所屬項目。標籤「LOG」是日誌本身的啟停紀錄，不屬於任何項目，永遠允許。 */
        val byTag: Map<String, DiagItem> by lazy {
            values().flatMap { item -> item.tags.map { it to item } }.toMap()
        }
        val all: Set<DiagItem> get() = values().toSet()
    }
}

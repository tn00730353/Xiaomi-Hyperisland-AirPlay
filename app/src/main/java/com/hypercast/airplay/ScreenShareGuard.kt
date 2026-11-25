// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Android 15 起，螢幕分享 / 錄製（MediaProjection）期間，系統會隱藏通知的私人內容（開發者選項「停用螢幕分享保護」可關閉）。
 * HyperCast 為了擷取系統聲音必須啟動 MediaProjection，所以會觸發這個保護；它只擷取聲音，不擷取畫面。
 *
 * 這個開關是系統的全域設定，App 預設沒有權限改。使用者用電腦執行一次 [ADB_CMD] 授權後，
 * HyperCast 才能代為切換；沒授權時這個類別什麼都不會做。設定會一直保留，直到使用者按「還原」。
 * 設定鍵名來自 Android 的開發者選項（Settings.Global）；讀不到或寫入後讀回不一致，代表這支手機不吃這個鍵。
 */
object ScreenShareGuard {
    const val KEY = "disable_screen_share_protections_for_apps_and_notifications"
    const val ADB_CMD = "adb shell pm grant com.hypercast.airplay android.permission.WRITE_SECURE_SETTINGS"

    fun canWrite(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun read(c: Context): String? = try {
        Settings.Global.getString(c.contentResolver, KEY)
    } catch (_: Exception) {
        null
    }

    /** @param disabled true＝停用系統的螢幕分享保護；false＝還原（啟用）。 */
    fun set(c: Context, disabled: Boolean): Boolean = try {
        Settings.Global.putInt(c.contentResolver, KEY, if (disabled) 1 else 0)
        DiagLog.d("PRIV", "螢幕分享保護：使用者在 HyperCast 內設為「${if (disabled) "停用" else "還原（啟用）"}」，讀回 ${read(c) ?: "未設定"}")
        true
    } catch (e: Exception) {
        DiagLog.d("PRIV", "螢幕分享保護設定失敗：${e.javaClass.simpleName}")
        false
    }
}

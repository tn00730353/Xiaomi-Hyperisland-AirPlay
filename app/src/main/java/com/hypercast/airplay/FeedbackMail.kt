// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** 開啟郵件 App，收件人、主旨（日誌時間）與日誌附件都已填好。 */
object FeedbackMail {
    const val EMAIL = "dylanliao.hyper@gmail.com"

    fun send(a: Activity): Boolean {
        return try {
            DiagLog.flush()
            val dir = File(a.cacheDir, "feedback").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, DiagLog.exportFileName())
            f.writeText(DiagLog.snapshot(), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(a, "${a.packageName}.fileprovider", f)
            val subject = "[HyperCast v${BuildConfig.VERSION_NAME}] ${DiagLog.exportStamp()}"
            val body = "版本：HyperCast v${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）\n" +
                "請在這裡描述遇到的問題（例如：連到哪一台裝置、發生了什麼事）：\n\n\n\n（日誌檔已附在這封信裡）"

            fun base() = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                // 只列出郵件 App
                a.startActivity(base().apply { selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) })
            } catch (_: ActivityNotFoundException) {
                a.startActivity(Intent.createChooser(base().apply { type = "text/plain" }, "傳送日誌給開發者"))
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

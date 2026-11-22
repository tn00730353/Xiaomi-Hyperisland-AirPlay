// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

/** 開發者浮水印：顯示於畫面底部、記錄於 Log，並寫入 BuildConfig 與 Manifest meta-data。 */
object Watermark {
    const val OWNER = "DylanLiao"
    val NOTICE: String get() = "© DylanLiao — HyperCast v${BuildConfig.VERSION_NAME}"
}

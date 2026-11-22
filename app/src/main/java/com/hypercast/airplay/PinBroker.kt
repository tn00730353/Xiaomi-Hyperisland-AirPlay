// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 連線過程中需要使用者輸入配對碼 / 密碼時的請求。 */
data class PinRequest(
    val deviceName: String,
    val password: Boolean,   // true = 裝置密碼（RAOP），false = 裝置螢幕上顯示的配對碼
    val retry: Boolean,      // 上一次輸入錯誤
)

/** 讓背景連線執行緒暫停並等待 UI 輸入配對碼。 */
object PinBroker {
    val request = MutableStateFlow<PinRequest?>(null)
    private val answers = LinkedBlockingQueue<String>()

    /** 阻塞直到使用者送出或取消（回傳 null）；最長等待 2 分鐘。 */
    fun ask(name: String, password: Boolean, retry: Boolean): String? {
        answers.clear()
        request.value = PinRequest(name, password, retry)
        return try {
            answers.poll(120, TimeUnit.SECONDS)?.takeIf { it.isNotEmpty() }
        } finally {
            request.value = null
        }
    }

    fun submit(pin: String) { answers.offer(pin.trim()) }
    fun cancel() { answers.offer("") }
}

// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Receiver(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val et: String,          // 加密類型，如 "0,1"
    val password: Boolean,
    val model: String,
    val ap2: Boolean = false,   // HomePod / Apple TV 等 AirPlay 2 裝置
    val groupId: String = "",      // 群組 ID（立體聲組）
    val groupLeader: Boolean = false,
    val inGroup: Boolean = false,
) {
    private val etList get() = et.split(',').map { it.trim() }
    val supportsPlain get() = "0" in etList
    val supportsRsa get() = "1" in etList
}

enum class Phase { Idle, Connecting, Streaming, Error }

data class UiState(
    val receivers: List<Receiver> = emptyList(),
    val selected: Receiver? = null,
    val phase: Phase = Phase.Idle,
    val volume: Float = 0.5f,
    val muted: Boolean = false,
    val message: String? = null,
)

/** UI、前景服務、通知共用的單一狀態來源。 */
object AirPlayStore {
    val state = MutableStateFlow(UiState())

    fun update(f: (UiState) -> UiState) = state.update(f)

    fun setVolume(v: Float) = update { it.copy(volume = v.coerceIn(0f, 1f), muted = false) }
    fun adjust(delta: Float) = setVolume(state.value.volume + delta)
    fun toggleMute() = update { it.copy(muted = !it.muted) }
}

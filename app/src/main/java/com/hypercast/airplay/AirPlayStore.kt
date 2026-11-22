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
    val groupId: String = "",      // 群組 ID（pgid 優先，其次 gid），用來把立體聲組的兩顆合併
    val groupLeader: Boolean = false,   // igl：這顆是群組主機
    val inGroup: Boolean = false,       // gcgl / pgcgl：屬於含主機的群組
    val gid: String = "",          // 群組 UUID（SETUP 的 groupUUID）
    val gcgl: Boolean = false,     // groupContainsGroupLeader（SETUP 同名欄位）
    val pgid: String = "",         // 原始 pgid（上層群組，例如家庭劇院）
    val tsid: String = "",         // 原始 tsid
    val igl: String = "",          // 原始 igl，僅供診斷
) {
    private val etList get() = et.split(',').map { it.trim() }
    val supportsPlain get() = "0" in etList
    val supportsRsa get() = "1" in etList
}

/** 聲道分配：立體聲＝完整左右聲道；左 / 右＝只播放該聲道（兩個聲道都送同一邊的內容）。 */
enum class Role(val label: String) { Stereo("立體聲"), Left("左聲道"), Right("右聲道") }

/** 兩台單顆喇叭時的聲道模式。 */
enum class ChannelMode { Both, LeftRight, RightLeft }

enum class TargetState { Connecting, Streaming }

/** 一個串流目標（最多同時 2 個）。pair = 這台本身已是 HomePod 立體聲組。 */
data class Target(
    val rx: Receiver,
    val role: Role = Role.Stereo,
    val pair: Boolean = false,
    val state: TargetState = TargetState.Connecting,
)

enum class Phase { Idle, Connecting, Streaming, Error }

data class UiState(
    val receivers: List<Receiver> = emptyList(),
    val targets: List<Target> = emptyList(),
    val phase: Phase = Phase.Idle,
    val volume: Float = VolumeStore.DEFAULT,
    val muted: Boolean = false,
    val playing: Boolean = false,   // 擷取到的音訊正在有聲音（連線成功但還沒有聲音時是 false）
    val message: String? = null,
) {
    /** 第一個目標（舊程式相容）。 */
    val selected: Receiver? get() = targets.firstOrNull()?.rx
}

/** UI、前景服務、通知共用的單一狀態來源。 */
object AirPlayStore {
    const val MAX_TARGETS = 2

    val state = MutableStateFlow(UiState())

    fun update(f: (UiState) -> UiState) = state.update(f)

    fun setVolume(v: Float) {
        val x = v.coerceIn(0f, 1f)
        update { it.copy(volume = x, muted = false) }
        VolumeStore.save(x)
    }
    fun setPlaying(p: Boolean) { if (state.value.playing != p) update { it.copy(playing = p) } }
    fun adjust(delta: Float) = setVolume(state.value.volume + delta)
    fun toggleMute() = update { it.copy(muted = !it.muted) }
    fun setMuted(m: Boolean) = update { it.copy(muted = m) }

    /** 兩台都是單顆喇叭時，指定左右聲道；其中任一台是立體聲組則不動作。 */
    fun applyChannelMode(mode: ChannelMode) = update { s ->
        val t = s.targets
        if (t.size != 2 || t.any { it.pair }) s
        else s.copy(
            targets = when (mode) {
                ChannelMode.Both -> t.map { it.copy(role = Role.Stereo) }
                ChannelMode.LeftRight -> listOf(t[0].copy(role = Role.Left), t[1].copy(role = Role.Right))
                ChannelMode.RightLeft -> listOf(t[0].copy(role = Role.Right), t[1].copy(role = Role.Left))
            }
        )
    }

    fun channelMode(s: UiState): ChannelMode {
        val t = s.targets
        return when {
            t.size == 2 && t[0].role == Role.Left && t[1].role == Role.Right -> ChannelMode.LeftRight
            t.size == 2 && t[0].role == Role.Right && t[1].role == Role.Left -> ChannelMode.RightLeft
            else -> ChannelMode.Both
        }
    }

    fun setTargetState(id: String, st: TargetState) = update { s ->
        s.copy(targets = s.targets.map { if (it.rx.id == id) it.copy(state = st) else it })
    }

    fun removeTarget(id: String) = update { s ->
        val left = s.targets.filter { it.rx.id != id }
        // 只剩一台時，不需要左右聲道，恢復成完整立體聲
        s.copy(targets = if (left.size == 1) left.map { it.copy(role = Role.Stereo) } else left, message = null)
    }
}

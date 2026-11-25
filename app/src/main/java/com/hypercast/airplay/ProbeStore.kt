// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import kotlinx.coroutines.flow.MutableStateFlow

/** 「通知被擋」診斷實驗的階段。A＝只有螢幕擷取授權　B＝再加上音訊擷取（和串流時相同，但不連線）。 */
enum class ProbeStage { Idle, A, B, Done }

data class ProbeState(
    val stage: ProbeStage = ProbeStage.Idle,
    /** 階段 A 的結果：true＝通知被擋住、false＝正常、null＝還沒回答。 */
    val answerA: Boolean? = null,
    val answerB: Boolean? = null,
    val note: String = "",
)

/** 實驗畫面與 [ProbeService] 共用的狀態。 */
object ProbeStore {
    val state = MutableStateFlow(ProbeState())

    fun reset() {
        state.value = ProbeState()
    }

    fun set(stage: ProbeStage, note: String = "") {
        state.value = state.value.copy(stage = stage, note = note)
    }
}

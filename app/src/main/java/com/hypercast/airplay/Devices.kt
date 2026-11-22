// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

enum class PodKind { Large, Mini, Generic }

/** HomePod（一代/二代）→ 大顆；HomePod mini → 小顆；其他喇叭 → 一般樣式。 */
val Receiver.podKind: PodKind
    get() = when {
        model.startsWith("AudioAccessory5") -> PodKind.Mini     // HomePod mini
        model.startsWith("AudioAccessory") -> PodKind.Large     // HomePod 1 / 2 代
        else -> PodKind.Generic
    }

/** 這台接收端是否本身就是 HomePod 立體聲組（兩顆共用群組 ID，或帶有 gcgl 旗標的 HomePod）。 */
fun isStereoPair(rx: Receiver, all: List<Receiver>): Boolean {
    if (rx.groupId.isNotBlank() && all.count { it.groupId == rx.groupId } >= 2) return true
    return rx.inGroup && rx.podKind != PodKind.Generic
}

/** 選單中的一個項目；pair = true 時顯示兩顆（立體聲組）。 */
data class DeviceItem(val primary: Receiver, val pair: Boolean)

/**
 * 把搜尋到的接收端整理成選單項目。
 * 立體聲組的判斷（盡力而為）：
 *  - 多個接收端共用同一個群組 ID（pgid / gid）→ 合併成一個「兩顆」項目，優先連到群組主機（igl）
 *  - 單一接收端帶有 gcgl 旗標且是 HomePod → 視為立體聲組
 */
fun buildItems(list: List<Receiver>): List<DeviceItem> {
    val byGroup = list.filter { it.groupId.isNotBlank() }.groupBy { it.groupId }
    val merged = HashSet<String>()
    val items = ArrayList<DeviceItem>()
    for (rx in list) {
        if (rx.id in merged) continue
        val g = if (rx.groupId.isNotBlank()) byGroup[rx.groupId] else null
        if (g != null && g.size >= 2) {
            g.forEach { merged += it.id }
            items += DeviceItem(g.firstOrNull { it.groupLeader } ?: g.first(), true)
        } else {
            items += DeviceItem(rx, rx.inGroup && rx.podKind != PodKind.Generic)
        }
    }
    return items
}

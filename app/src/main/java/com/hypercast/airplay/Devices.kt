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

val Receiver.isHomePod: Boolean get() = model.startsWith("AudioAccessory")

/** 這台接收端是否屬於 HomePod 組合（同一分組鍵的 HomePod ≥ 2 顆，或帶有 gcgl 旗標的 HomePod）。 */
fun isStereoPair(rx: Receiver, all: List<Receiver>): Boolean {
    if (!rx.isHomePod) return false
    if (rx.groupId.isNotBlank() && all.count { it.isHomePod && it.groupId == rx.groupId } >= 2) return true
    return rx.inGroup
}

/**
 * 選單中的一個項目。
 * members = 實際要連線的接收端：單台裝置是 1 個；HomePod 組合是組內每一顆 HomePod（各自直連）。
 */
data class DeviceItem(
    val primary: Receiver,
    val pair: Boolean,
    val members: List<Receiver> = listOf(primary),
    val name: String = primary.name,
)

/**
 * 把搜尋到的接收端整理成選單項目，做法對照 Base（AirPlayDevice.merge / grouping）：
 *  - 只有 HomePod（AudioAccessory）會被分組；Apple TV、其他喇叭、Mac 永遠單獨一台。
 *    （v1.2.0 把 Apple TV 也併進同一群組，並且選它當「主機」，所以一直卡在 Apple TV 的配對碼。）
 *  - 同一分組鍵（tsid → gid → pgid）的 HomePod ≥ 2 顆 → 額外多一個「組合」項目，連線時每一顆 HomePod 各連一條。
 *  - 每一台裝置仍保有自己的單獨項目，方便個別選擇。
 */
fun buildItems(list: List<Receiver>): List<DeviceItem> {
    val items = ArrayList<DeviceItem>()
    val pods = list.filter { it.isHomePod && it.groupId.isNotBlank() }.groupBy { it.groupId }
    for ((_, g) in pods) {
        if (g.size < 2) continue
        val members = g.sortedWith(compareByDescending<Receiver> { it.groupLeader }.thenBy { it.name.lowercase() })
        items += DeviceItem(
            primary = members.first(),
            pair = true,
            members = members,
            name = members.joinToString(" ＋ ") { it.name },
        )
    }
    for (rx in list) items += DeviceItem(rx, false)
    return items
}

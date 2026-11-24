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

/** 這台接收端是否屬於 HomePod 立體聲組（同一分組鍵的 HomePod ≥ 2 顆，或帶有 gcgl 旗標的 HomePod）。 */
fun isStereoPair(rx: Receiver, all: List<Receiver>): Boolean {
    if (!rx.isHomePod) return false
    if (rx.groupId.isNotBlank() && all.count { it.isHomePod && it.groupId == rx.groupId } >= 2) return true
    return rx.inGroup
}

/**
 * 依裝置清單回傳蘋果官方 SF Symbols 風格圖示：
 *  - 兩顆 HomePod → ic_homepod_2（對應 homepod.2.fill）
 *  - 兩顆 HomePod mini → ic_homepod_mini_2（對應 homepodmini.2.fill）
 *  - HomePod ＋ HomePod mini 混搭 → ic_homepod_and_mini（對應 homepod.and.homepodmini.fill）
 *  - 單顆 HomePod / HomePod mini / Apple TV / AirPlay → 各自對應官方圖示
 */
fun appleDeviceIcon(members: List<Receiver>, isPair: Boolean = false): Int {
    val pods = members.filter { it.isHomePod }
    val multiPod = pods.size >= 2 || (isPair && pods.isNotEmpty())
    if (multiPod) {
        val allMini = pods.all { it.podKind == PodKind.Mini }
        val allLarge = pods.all { it.podKind == PodKind.Large }
        return when {
            allMini -> R.drawable.ic_homepod_mini_2
            allLarge -> R.drawable.ic_homepod_2
            else -> R.drawable.ic_homepod_and_mini
        }
    }
    val first = members.firstOrNull() ?: return R.drawable.ic_airplay
    return when {
        first.podKind == PodKind.Mini -> R.drawable.ic_homepod_mini
        first.podKind == PodKind.Large -> R.drawable.ic_homepod
        first.model.startsWith("AppleTV") -> R.drawable.ic_tv
        else -> R.drawable.ic_airplay
    }
}

/** 蘋果台灣官方立體聲組名稱標示。 */
fun applePairLabel(members: List<Receiver>): String {
    val pods = members.filter { it.isHomePod }
    return when {
        pods.isNotEmpty() && pods.all { it.podKind == PodKind.Mini } -> "HomePod mini 立體聲組"
        else -> "HomePod 立體聲組"
    }
}

/**
 * 選單中的一個項目。
 * members = 實際要連線的接收端：單台裝置是 1 個；HomePod 立體聲組是組內每一顆 HomePod（各自直連）。
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
 *  - 同一分組鍵（tsid → gid → pgid）的 HomePod ≥ 2 顆 → 額外多一個「HomePod 立體聲組」項目，連線時每一顆 HomePod 各連一條。
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

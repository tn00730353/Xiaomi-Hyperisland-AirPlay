// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

/** AirPlay 1 (RAOP) 與 AirPlay 2 兩種發送端的共同介面。 */
interface CastSession {
    fun connect()
    fun sendFrame(alac: ByteArray)
    fun setVolume(volume: Float, muted: Boolean)
    fun close()
}

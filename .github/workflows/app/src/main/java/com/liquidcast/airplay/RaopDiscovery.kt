package com.liquidcast.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress

/** 以 mDNS 搜尋 _raop._tcp（AirPlay 音訊接收端）。 */
class RaopDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null

    private val found = LinkedHashMap<String, Receiver>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var started = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            AirPlayStore.update { it.copy(message = "搜尋裝置失敗 ($errorCode)") }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(this@RaopDiscovery) { pending.addLast(info) }
            pump()
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(this@RaopDiscovery) {
                found.remove(info.serviceName)
                publish()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        lock = wifi.createMulticastLock("liquidcast-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        nsd.discoverServices("_raop._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        if (!started) return
        started = false
        try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    private fun pump() {
        val next = synchronized(this) {
            if (resolving || pending.isEmpty()) return
            resolving = true
            pending.removeFirst()
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(this@RaopDiscovery) { resolving = false }
                pump()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                try { handle(info) } catch (_: Exception) {}
                synchronized(this@RaopDiscovery) { resolving = false }
                pump()
            }
        })
    }

    private fun pickAddress(info: NsdServiceInfo): InetAddress? {
        @Suppress("DEPRECATION")
        val all: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses
        else listOfNotNull(info.host)
        return all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
    }

    private fun handle(info: NsdServiceInfo) {
        val addr = pickAddress(info) ?: return
        val host = addr.hostAddress?.substringBefore('%') ?: return
        val attrs = info.attributes.mapValues { String(it.value ?: ByteArray(0)) }
        val rx = Receiver(
            id = info.serviceName.substringBefore('@'),
            name = info.serviceName.substringAfter('@'),
            host = host,
            port = info.port,
            et = attrs["et"] ?: "0,1",
            password = attrs["pw"] == "true" || attrs["pw"] == "1",
            model = attrs["am"] ?: "",
            ap2 = isAirPlay2(attrs),
        )
        synchronized(this) {
            found[info.serviceName] = rx
            publish()
        }
    }

    /** 型號為 AudioAccessory / AppleTV，或 features 的第 48 位元（SupportsTransientPairing）為 1。 */
    private fun isAirPlay2(attrs: Map<String, String>): Boolean {
        val am = attrs["am"] ?: ""
        if (am.startsWith("AudioAccessory") || am.startsWith("AppleTV")) return true
        val high = attrs["ft"]?.split(',')?.getOrNull(1)?.trim()?.removePrefix("0x")?.toLongOrNull(16) ?: return false
        return (high shr 16) and 1L == 1L
    }

    private fun publish() {
        val list = found.values.sortedBy { it.name.lowercase() }
        AirPlayStore.update { it.copy(receivers = list) }
    }
}

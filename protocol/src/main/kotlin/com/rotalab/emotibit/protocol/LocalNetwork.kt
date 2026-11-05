package com.rotalab.emotibit.protocol

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** An IPv4 network this phone is on (Wi-Fi client or its own hotspot). */
data class LocalNetwork(
    val interfaceName: String,
    val address: Inet4Address,
    val broadcast: Inet4Address?,
    val prefixLength: Int,
) {
    /** "192.168.43" for 192.168.43.17: the /24 the EmotiBit Oscilloscope sweeps for unicast discovery. */
    val prefix24: String get() = address.hostAddress.substringBeforeLast('.')

    override fun toString(): String = "$interfaceName ${address.hostAddress}/$prefixLength"

    companion object {
        /** Lists up, non-loopback IPv4 networks. Cellular interfaces have no broadcast and are skipped. */
        fun discover(): List<LocalNetwork> {
            val result = mutableListOf<LocalNetwork>()
            val interfaces = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            } catch (e: Exception) {
                emptyList()
            }
            for (ni in interfaces) {
                try {
                    if (!ni.isUp || ni.isLoopback) continue
                } catch (e: Exception) {
                    continue
                }
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val bcast = ia.broadcast as? Inet4Address
                    if (bcast == null && !looksLikeLan(addr)) continue
                    result += LocalNetwork(ni.name, addr, bcast ?: computeBroadcast(addr, ia.networkPrefixLength.toInt()), ia.networkPrefixLength.toInt())
                }
            }
            return result.sortedBy { rank(it.interfaceName) }
        }

        private fun looksLikeLan(addr: Inet4Address): Boolean = addr.isSiteLocalAddress

        /** Prefer hotspot and Wi-Fi interfaces over everything else. */
        private fun rank(name: String): Int = when {
            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 0
            name.startsWith("wlan") -> 1
            else -> 2
        }

        fun computeBroadcast(addr: Inet4Address, prefixLength: Int): Inet4Address? {
            if (prefixLength !in 1..30) return null
            val b = addr.address
            val ip = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            val mask = -1 shl (32 - prefixLength)
            val bc = ip or mask.inv()
            val bytes = byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte())
            return InetAddress.getByAddress(bytes) as Inet4Address
        }
    }
}

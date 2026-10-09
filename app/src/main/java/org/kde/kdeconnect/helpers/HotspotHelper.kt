package org.kde.kdeconnect.helpers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.DeviceHost
import org.kde.kdeconnect.datastore.ConnectionsSettingsDataStore
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

object HotspotHelper {

    fun getArpConnectedIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val file = File("/proc/net/arp")
            if (file.exists() && file.canRead()) {
                file.forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val ip = parts[0]
                        val flags = parts[2]
                        val mac = parts[3]
                        if (ip != "IP" && flags != "0x0" && mac != "00:00:00:00:00:00") {
                            if (DeviceHost.isValidDeviceHost(ip) && !ips.contains(ip)) {
                                ips.add(ip)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LoggerTagged.w(e) { "Error reading /proc/net/arp" }
        }
        return ips
    }

    data class SubnetInfo(
        val basePrefix: String,
        val hostIp: String,
        val broadcastIp: String?
    )

    fun getHotspotSubnets(): List<SubnetInfo> {
        val subnets = mutableListOf<SubnetInfo>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.interfaceAddresses) {
                    val inet = addr.address
                    if (inet is Inet4Address && !inet.isLoopbackAddress) {
                        val hostAddress = inet.hostAddress ?: continue
                        val parts = hostAddress.split(".")
                        if (parts.size == 4) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                            val broadcast = addr.broadcast?.hostAddress
                            // Standard Android hotspot subnets often start with 192.168.43, 192.168.44, 192.168.49, etc.,
                            // or interface name includes ap, softap, wlan, rndis, tether
                            val isLikelyHotspot = hostAddress.endsWith(".1") ||
                                    intf.name.contains("ap", ignoreCase = true) ||
                                    intf.name.contains("wlan", ignoreCase = true) ||
                                    intf.name.contains("tether", ignoreCase = true) ||
                                    hostAddress.startsWith("192.168.43.")

                            if (isLikelyHotspot) {
                                subnets.add(SubnetInfo(
                                    basePrefix = prefix,
                                    hostIp = hostAddress,
                                    broadcastIp = broadcast ?: "$prefix.255"
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LoggerTagged.w(e) { "Error checking network interfaces" }
        }
        return subnets
    }

    fun getSubnetBroadcastAddresses(): List<String> {
        val list = mutableListOf<String>()
        for (subnet in getHotspotSubnets()) {
            val b = subnet.broadcastIp
            if (b != null && !list.contains(b)) {
                list.add(b)
            }
        }
        if (list.isEmpty()) {
            // Default Android tethering broadcast fallback
            list.add("192.168.43.255")
        }
        return list
    }

    suspend fun probeSubnetForKdeConnect(basePrefix: String, myHostIp: String): List<String> = withContext(Dispatchers.IO) {
        val foundIps = Collections.synchronizedList(mutableListOf<String>())
        // Scan common DHCP IP range for hotspot clients (typically .2 to .150)
        val candidateIps = (2..150).map { "$basePrefix.$it" }.filter { it != myHostIp }

        // Probe in parallel with timeout 150ms
        candidateIps.chunked(32).forEach { batch ->
            val jobs = batch.map { ip ->
                async {
                    try {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(ip, 1716), 150)
                        socket.close()
                        foundIps.add(ip)
                    } catch (_: Exception) {
                        // Port not open or timed out
                    }
                }
            }
            jobs.awaitAll()
        }

        return@withContext foundIps
    }

    suspend fun autoDetectAndAddHotspotIps(
        context: Context,
        connectionsDataStore: ConnectionsSettingsDataStore,
        customDevicesHelper: CustomDevicesHelper
    ): List<String> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<String>()

        // 1. Check ARP entries
        val arpIps = getArpConnectedIps()
        discovered.addAll(arpIps)

        // 2. Check hotspot subnets
        val subnets = getHotspotSubnets()
        for (subnet in subnets) {
            val probed = probeSubnetForKdeConnect(subnet.basePrefix, subnet.hostIp)
            for (p in probed) {
                if (!discovered.contains(p)) {
                    discovered.add(p)
                }
            }
            // Add subnet broadcast
            val broadcast = subnet.broadcastIp ?: "${subnet.basePrefix}.255"
            if (!discovered.contains(broadcast)) {
                discovered.add(broadcast)
            }
        }

        // If no subnets detected via interfaces (e.g. strict permissions), probe standard 192.168.43.x
        if (discovered.isEmpty()) {
            val probedDefault = probeSubnetForKdeConnect("192.168.43", "192.168.43.1")
            discovered.addAll(probedDefault)
            if (!discovered.contains("192.168.43.255")) {
                discovered.add("192.168.43.255")
            }
        }

        // Save new devices to custom device list
        if (discovered.isNotEmpty()) {
            val existing = customDevicesHelper.getCustomDeviceList()
            val existingStrings = existing.map { it.toString() }.toSet()
            val newlyAdded = discovered.filter { it !in existingStrings }

            if (newlyAdded.isNotEmpty()) {
                val updatedList = (existing.map { it.toString() } + newlyAdded).distinct()
                val serialized = updatedList.joinToString(",")
                connectionsDataStore.setCustomDeviceList(serialized)
            }

            BackgroundService.forceRefreshConnections(context)
        }

        return@withContext discovered
    }
}

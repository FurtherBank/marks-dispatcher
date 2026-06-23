package com.marksdispatcher.app.discovery

import android.content.Context
import com.marksdispatcher.app.api.CollectorDiscoveryClient
import com.marksdispatcher.app.model.CollectorDefaults
import com.marksdispatcher.app.model.DiscoveredDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class LanDeviceScanner(
    private val discoveryClient: CollectorDiscoveryClient = CollectorDiscoveryClient()
) {

    data class ScanResult(
        val devices: List<DiscoveredDevice>,
        val scannedHosts: Int,
        val subnet: String?
    )

    suspend fun scan(port: Int = CollectorDefaults.PORT): ScanResult = withContext(Dispatchers.IO) {
        val localIp = getLocalIpv4Address()
        if (localIp == null) {
            return@withContext ScanResult(emptyList(), 0, null)
        }

        val prefix = localIp.substringBeforeLast('.')
        val subnet = "$prefix.0/24"
        val jobs = (1..254).map { host ->
            async {
                discoveryClient.probe("$prefix.$host", port)
            }
        }
        val devices = jobs.awaitAll().filterNotNull().distinctBy { it.deviceId }
        ScanResult(devices, 254, subnet)
    }

    fun getLocalIpv4Address(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
        return null
    }
}

package com.marksdispatcher.app.discovery

import android.content.Context
import com.marksdispatcher.app.api.CollectorDiscoveryClient
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.model.CollectorDefaults
import com.marksdispatcher.app.model.DiscoveredDevice
import com.marksdispatcher.app.model.PairedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceResolver(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val scanner: LanDeviceScanner = LanDeviceScanner(),
    private val discoveryClient: CollectorDiscoveryClient = CollectorDiscoveryClient()
) {

    data class ResolvedEndpoint(
        val url: String,
        val token: String,
        val device: PairedDevice?,
        val source: String
    )

    suspend fun resolve(): ResolvedEndpoint? = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSettings()

        if (settings.usePairedDevice) {
            val paired = settings.pairedDevice ?: return@withContext null
            resolvePairedDevice(paired, settings.apiToken)
        } else {
            val endpoint = settings.apiEndpoint.trim()
            if (endpoint.isBlank()) return@withContext null
            ResolvedEndpoint(
                url = endpoint,
                token = settings.apiToken,
                device = null,
                source = "manual"
            )
        }
    }

    private suspend fun resolvePairedDevice(
        paired: PairedDevice,
        fallbackToken: String
    ): ResolvedEndpoint? {
        val token = paired.apiToken.ifBlank { fallbackToken }

        if (paired.lastKnownIp.isNotBlank()) {
            val probe = discoveryClient.probe(paired.lastKnownIp, paired.lastKnownPort)
            if (probe != null && probe.deviceId == paired.deviceId) {
                updatePairedDevice(paired, probe)
                return ResolvedEndpoint(
                    url = probe.dispatchUrl,
                    token = token,
                    device = paired.copy(
                        lastKnownIp = probe.ip,
                        lastKnownPort = probe.port,
                        lastSeenAt = System.currentTimeMillis()
                    ),
                    source = "last_known_ip"
                )
            }
        }

        val scan = scanner.scan(paired.lastKnownPort)
        val matched = scan.devices.firstOrNull { it.deviceId == paired.deviceId }
        if (matched != null) {
            updatePairedDevice(paired, matched)
            return ResolvedEndpoint(
                url = matched.dispatchUrl,
                token = token,
                device = paired.copy(
                    deviceName = matched.deviceName,
                    lastKnownIp = matched.ip,
                    lastKnownPort = matched.port,
                    lastSeenAt = System.currentTimeMillis()
                ),
                source = "lan_scan"
            )
        }

        return null
    }

    private fun updatePairedDevice(paired: PairedDevice, discovered: DiscoveredDevice) {
        val updated = paired.copy(
            deviceName = discovered.deviceName,
            lastKnownIp = discovered.ip,
            lastKnownPort = discovered.port,
            lastSeenAt = System.currentTimeMillis()
        )
        settingsRepository.savePairedDevice(updated)
    }

    suspend fun isPairedDeviceOnline(): Boolean {
        return resolve() != null
    }
}

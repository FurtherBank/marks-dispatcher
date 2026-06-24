package com.marksdispatcher.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.databinding.ActivityMainBinding
import com.marksdispatcher.app.discovery.DeviceResolver
import com.marksdispatcher.app.discovery.LanDeviceScanner
import com.marksdispatcher.app.dispatch.DispatchManager
import com.marksdispatcher.app.model.DiscoveredDevice
import com.marksdispatcher.app.model.DispatchRecord
import com.marksdispatcher.app.model.PairedDevice
import com.marksdispatcher.app.service.ClipboardMonitorService
import com.marksdispatcher.app.worker.DispatchRetryWorker
import com.marksdispatcher.app.overlay.OverlayPermissionHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var historyAdapter: HistoryAdapter
    private val lanScanner = LanDeviceScanner()
    private lateinit var deviceResolver: DeviceResolver
    private lateinit var dispatchManager: DispatchManager
    private var suppressMonitorListener = false

    private val dispatchUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshHistory()
            refreshDeviceStatus()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "通知权限未授予，前台服务可能无法正常显示", Toast.LENGTH_LONG).show()
        }
        toggleMonitor(true)
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (OverlayPermissionHelper.canDrawOverlays(this)) {
            requestNotificationAndStart(skipOverlayCheck = true)
        } else {
            suppressMonitorListener = true
            binding.switchMonitor.isChecked = false
            suppressMonitorListener = false
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = SettingsRepository(this)
        deviceResolver = DeviceResolver(this, settingsRepository)
        dispatchManager = DispatchManager(this)

        setupHistoryList()
        loadSettings()
        setupActions()
        refreshDeviceStatus()

        ContextCompat.registerReceiver(
            this,
            dispatchUpdateReceiver,
            IntentFilter(ClipboardMonitorService.ACTION_DISPATCH_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        refreshMonitorUi()
        refreshHistory()
        refreshDeviceStatus()
    }

    override fun onDestroy() {
        unregisterReceiver(dispatchUpdateReceiver)
        super.onDestroy()
    }

    private fun setupHistoryList() {
        historyAdapter = HistoryAdapter()
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = historyAdapter
    }

    private fun loadSettings() {
        val settings = settingsRepository.getSettings()
        binding.inputApiEndpoint.setText(settings.apiEndpoint)
        binding.inputApiToken.setText(settings.apiToken)
        binding.switchAutoStart.isChecked = settings.autoStartOnBoot
        suppressMonitorListener = true
        binding.switchMonitor.isChecked = settings.monitorEnabled
        suppressMonitorListener = false
        binding.switchUsePairedDevice.isChecked = settings.usePairedDevice
    }

    private fun setupActions() {
        binding.btnSave.setOnClickListener { saveSettings() }

        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (suppressMonitorListener) return@setOnCheckedChangeListener
            if (isChecked) {
                requestNotificationAndStart()
            } else {
                stopMonitor()
            }
        }

        binding.switchUsePairedDevice.setOnCheckedChangeListener { _, isChecked ->
            val current = settingsRepository.getSettings()
            settingsRepository.saveSettings(current.copy(usePairedDevice = isChecked))
            refreshDeviceStatus()
        }

        binding.btnScanDevices.setOnClickListener { scanLanDevices() }
        binding.btnUnpairDevice.setOnClickListener { unpairDevice() }
        binding.btnRetryPending.setOnClickListener { retryPendingNow() }
        binding.btnGrantOverlay.setOnClickListener { openOverlaySettings() }

        binding.btnDispatchClipboard.setOnClickListener { dispatchClipboardManually() }
        binding.btnClearHistory.setOnClickListener {
            settingsRepository.clearHistory()
            refreshHistory()
        }
    }

    private fun saveSettings() {
        val current = settingsRepository.getSettings()
        val endpointInput = binding.inputApiEndpoint.text?.toString().orEmpty().trim()
        val endpoint = if (current.usePairedDevice) {
            current.apiEndpoint
        } else {
            endpointInput
        }
        val updated = current.copy(
            apiEndpoint = endpoint,
            apiToken = binding.inputApiToken.text?.toString().orEmpty().trim(),
            autoStartOnBoot = binding.switchAutoStart.isChecked,
            usePairedDevice = binding.switchUsePairedDevice.isChecked
        )
        settingsRepository.saveSettings(updated)
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        refreshDeviceStatus()
    }

    private fun scanLanDevices() {
        binding.btnScanDevices.isEnabled = false
        binding.deviceStatusText.text = getString(R.string.device_scanning)
        lifecycleScope.launch {
            val result = lanScanner.scan()
            binding.btnScanDevices.isEnabled = true
            if (result.devices.isEmpty()) {
                binding.deviceStatusText.text = getString(
                    R.string.device_scan_empty,
                    result.subnet ?: "unknown"
                )
                Toast.makeText(this@MainActivity, R.string.toast_scan_empty, Toast.LENGTH_LONG).show()
                return@launch
            }
            showDevicePicker(result.devices)
        }
    }

    private fun showDevicePicker(devices: List<DiscoveredDevice>) {
        val labels = devices.map { "${it.deviceName} (${it.ip}:${it.port})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_pick_device_title)
            .setItems(labels) { _, which ->
                pairDevice(devices[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pairDevice(device: DiscoveredDevice) {
        val token = binding.inputApiToken.text?.toString().orEmpty().trim()
        val paired = PairedDevice(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            apiToken = token,
            lastKnownIp = device.ip,
            lastKnownPort = device.port,
            lastSeenAt = System.currentTimeMillis()
        )
        settingsRepository.savePairedDevice(paired)
        val current = settingsRepository.getSettings()
        settingsRepository.saveSettings(
            current.copy(
                usePairedDevice = true,
                pairedDevice = paired,
                apiToken = token
            )
        )
        binding.switchUsePairedDevice.isChecked = true
        Toast.makeText(this, getString(R.string.toast_device_paired, device.deviceName), Toast.LENGTH_SHORT).show()
        refreshDeviceStatus()
        lifecycleScope.launch {
            dispatchManager.retryPendingQueue()
        }
    }

    private fun unpairDevice() {
        settingsRepository.clearPairedDevice()
        Toast.makeText(this, R.string.toast_device_unpaired, Toast.LENGTH_SHORT).show()
        refreshDeviceStatus()
    }

    private fun retryPendingNow() {
        ClipboardMonitorService.retryPending(this)
        Toast.makeText(this, R.string.toast_retry_started, Toast.LENGTH_SHORT).show()
    }

    private fun refreshDeviceStatus() {
        val settings = settingsRepository.getSettings()
        val paired = settings.pairedDevice
        val pendingCount = settingsRepository.pendingCount()

        binding.btnUnpairDevice.isEnabled = paired != null
        binding.layoutApiEndpoint.visibility = if (settings.usePairedDevice) View.GONE else View.VISIBLE
        binding.pendingStatusText.visibility = if (pendingCount > 0) View.VISIBLE else View.GONE
        binding.pendingStatusText.text = getString(R.string.pending_count, pendingCount)

        if (paired == null) {
            binding.deviceStatusText.text = getString(R.string.device_not_paired)
            return
        }

        binding.deviceStatusText.text = getString(
            R.string.device_paired_offline,
            paired.deviceName,
            paired.lastKnownIp.ifBlank { "?" }
        )

        lifecycleScope.launch {
            val online = deviceResolver.isPairedDeviceOnline()
            val resolved = if (online) deviceResolver.resolve() else null
            if (online && resolved != null) {
                binding.deviceStatusText.text = getString(
                    R.string.device_paired_online,
                    paired.deviceName,
                    resolved.url
                )
            } else {
                binding.deviceStatusText.text = getString(
                    R.string.device_paired_offline,
                    paired.deviceName,
                    paired.lastKnownIp.ifBlank { "?" }
                )
            }
        }
    }

    private fun requestNotificationAndStart(skipOverlayCheck: Boolean = false) {
        if (!skipOverlayCheck && !OverlayPermissionHelper.canDrawOverlays(this)) {
            suppressMonitorListener = true
            binding.switchMonitor.isChecked = false
            suppressMonitorListener = false
            showOverlayRequiredDialog()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> toggleMonitor(true)

                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            toggleMonitor(true)
        }
    }

    private fun hasValidTarget(settings: com.marksdispatcher.app.model.AppSettings): Boolean {
        return if (settings.usePairedDevice) {
            settings.pairedDevice != null
        } else {
            settings.apiEndpoint.isNotBlank()
        }
    }

    private fun openOverlaySettings() {
        overlayPermissionLauncher.launch(OverlayPermissionHelper.createSettingsIntent(this))
    }

    private fun showOverlayRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_overlay_title)
            .setMessage(R.string.dialog_overlay_message)
            .setPositiveButton(R.string.btn_grant_overlay) { _, _ ->
                openOverlaySettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleMonitor(enable: Boolean) {
        saveSettings()
        val settings = settingsRepository.getSettings().copy(monitorEnabled = enable)
        settingsRepository.saveSettings(settings)

        if (enable) {
            if (!hasValidTarget(settings)) {
                Toast.makeText(this, R.string.toast_need_pair_or_endpoint, Toast.LENGTH_LONG).show()
                suppressMonitorListener = true
                binding.switchMonitor.isChecked = false
                suppressMonitorListener = false
                settingsRepository.setMonitorEnabled(false)
                return
            }
            DispatchRetryWorker.schedule(this)
            ClipboardMonitorService.start(this)
            Toast.makeText(this, R.string.toast_monitor_started, Toast.LENGTH_SHORT).show()
        } else {
            stopMonitor()
        }
        refreshMonitorUi()
    }

    private fun stopMonitor() {
        ClipboardMonitorService.stop(this)
        settingsRepository.setMonitorEnabled(false)
        suppressMonitorListener = true
        binding.switchMonitor.isChecked = false
        suppressMonitorListener = false
        refreshMonitorUi()
    }

    private fun dispatchClipboardManually() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = com.marksdispatcher.app.util.ClipboardReader.readText(this, clipboard).orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "剪贴板为空或无可用链接", Toast.LENGTH_SHORT).show()
            return
        }
        saveSettings()
        ClipboardMonitorService.dispatchNow(this, text, force = true)
        Toast.makeText(this, "正在手动派发剪贴板内容…", Toast.LENGTH_SHORT).show()
    }

    private fun refreshMonitorUi() {
        val enabled = settingsRepository.getSettings().monitorEnabled
        val overlayGranted = OverlayPermissionHelper.canDrawOverlays(this)
        suppressMonitorListener = true
        binding.switchMonitor.isChecked = enabled
        suppressMonitorListener = false
        binding.overlayStatusText.text = if (overlayGranted) {
            getString(R.string.overlay_status_enabled)
        } else {
            getString(R.string.overlay_status_disabled)
        }
        binding.statusText.text = when {
            enabled && overlayGranted -> getString(R.string.status_monitoring)
            enabled && !overlayGranted -> getString(R.string.overlay_status_disabled)
            else -> getString(R.string.status_idle)
        }
    }

    private fun refreshHistory() {
        historyAdapter.submitList(settingsRepository.getHistory())
        binding.emptyHistory.visibility =
            if (historyAdapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private val items = mutableListOf<DispatchRecord>()
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        fun submitList(records: List<DispatchRecord>) {
            items.clear()
            items.addAll(records)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], timeFormat)
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.historyTitle)
            private val subtitle: TextView = itemView.findViewById(R.id.historySubtitle)
            private val status: TextView = itemView.findViewById(R.id.historyStatus)

            fun bind(record: DispatchRecord, timeFormat: SimpleDateFormat) {
                title.text = "${record.payload.sourceLabel} · ${record.payload.url}"
                subtitle.text = timeFormat.format(Date(record.timestamp))
                status.text = record.message
                status.setTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        if (record.success) R.color.status_success else R.color.status_error
                    )
                )
            }
        }
    }
}

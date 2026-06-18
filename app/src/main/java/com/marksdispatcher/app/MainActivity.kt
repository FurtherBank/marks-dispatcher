package com.marksdispatcher.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.marksdispatcher.app.data.SettingsRepository
import com.marksdispatcher.app.databinding.ActivityMainBinding
import com.marksdispatcher.app.model.DispatchRecord
import com.marksdispatcher.app.service.ClipboardMonitorService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var historyAdapter: HistoryAdapter

    private val dispatchUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshHistory()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = SettingsRepository(this)
        setupHistoryList()
        loadSettings()
        setupActions()

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
        binding.switchMonitor.isChecked = settings.monitorEnabled
    }

    private fun setupActions() {
        binding.btnSave.setOnClickListener { saveSettings() }

        binding.switchMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestNotificationAndStart()
            } else {
                stopMonitor()
            }
        }

        binding.btnDispatchClipboard.setOnClickListener { dispatchClipboardManually() }
        binding.btnClearHistory.setOnClickListener {
            settingsRepository.clearHistory()
            refreshHistory()
        }
    }

    private fun saveSettings() {
        val current = settingsRepository.getSettings()
        val updated = current.copy(
            apiEndpoint = binding.inputApiEndpoint.text?.toString().orEmpty().trim(),
            apiToken = binding.inputApiToken.text?.toString().orEmpty().trim(),
            autoStartOnBoot = binding.switchAutoStart.isChecked
        )
        settingsRepository.saveSettings(updated)
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationAndStart() {
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

    private fun toggleMonitor(enable: Boolean) {
        saveSettings()
        val settings = settingsRepository.getSettings().copy(monitorEnabled = enable)
        settingsRepository.saveSettings(settings)

        if (enable) {
            if (settings.apiEndpoint.isBlank()) {
                Toast.makeText(this, "请先配置 API 地址", Toast.LENGTH_LONG).show()
                binding.switchMonitor.isChecked = false
                settingsRepository.setMonitorEnabled(false)
                return
            }
            ClipboardMonitorService.start(this)
            Toast.makeText(this, "已开始监听剪贴板", Toast.LENGTH_SHORT).show()
        } else {
            stopMonitor()
        }
        refreshMonitorUi()
    }

    private fun stopMonitor() {
        ClipboardMonitorService.stop(this)
        settingsRepository.setMonitorEnabled(false)
        binding.switchMonitor.isChecked = false
        refreshMonitorUi()
    }

    private fun dispatchClipboardManually() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "剪贴板无文本内容", Toast.LENGTH_SHORT).show()
            return
        }
        saveSettings()
        ClipboardMonitorService.dispatchNow(this, text)
        Toast.makeText(this, "正在手动派发剪贴板内容…", Toast.LENGTH_SHORT).show()
    }

    private fun refreshMonitorUi() {
        val enabled = settingsRepository.getSettings().monitorEnabled
        binding.switchMonitor.isChecked = enabled
        binding.statusText.text = if (enabled) {
            getString(R.string.status_monitoring)
        } else {
            getString(R.string.status_idle)
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

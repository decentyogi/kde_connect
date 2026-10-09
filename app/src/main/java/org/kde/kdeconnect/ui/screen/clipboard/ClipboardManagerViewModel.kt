package org.kde.kdeconnect.ui.screen.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.kde.kdeconnect.DeviceManager
import org.kde.kdeconnect.DeviceState
import org.kde.kdeconnect.PairState
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.plugins.clipboard.history.ClipboardEntry
import org.kde.kdeconnect.plugins.clipboard.history.ClipboardRepository

class ClipboardManagerViewModel(
    private val repository: ClipboardRepository,
    private val deviceManager: DeviceManager
) : ViewModel() {

    val searchQuery = MutableStateFlow("")

    val totalCount: StateFlow<Int> = repository.countFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val connectedDevices: StateFlow<List<DeviceState>> = deviceManager.allDeviceStatesMap
        .map { it.values.filter { dev -> dev.isReachable && dev.pairState == PairState.Paired } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val entries: StateFlow<List<ClipboardEntry>> = combine(
        repository.entriesFlow,
        searchQuery
    ) { allEntries, query ->
        if (query.isBlank()) {
            allEntries
        } else {
            allEntries.filter { it.text.contains(query, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun syncSystemClipboard(context: Context) {
        try {
            val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrBlank()) {
                    repository.saveCopiedText(text)
                }
            }
        } catch (_: Exception) { }
    }

    fun copyToClipboard(context: Context, text: String) {
        try {
            val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
            val clip = ClipData(
                ClipDescription("KDE Connect", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)),
                ClipData.Item(text)
            )
            cm?.setPrimaryClip(clip)
        } catch (_: Exception) { }
    }

    fun sendToDevice(text: String, deviceId: String?, onSent: (String) -> Unit) {
        viewModelScope.launch {
            val targetId = deviceId ?: connectedDevices.value.firstOrNull()?.deviceInfo?.id
            if (targetId != null) {
                val plugin = deviceManager.getDevicePlugin(targetId, ClipboardPlugin::class.java)
                val deviceName = deviceManager.getDevice(targetId)?.name ?: "Device"
                if (plugin != null) {
                    plugin.propagateClipboard(text)
                    onSent(deviceName)
                }
            }
        }
    }

    fun deleteItem(id: Long) {
        viewModelScope.launch {
            repository.deleteEntry(id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }
}

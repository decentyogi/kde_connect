package org.kde.kdeconnect.ui.screen.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kde.kdeconnect.BackgroundService.Companion.forceRefreshConnections
import org.kde.kdeconnect.BackgroundServiceData
import org.kde.kdeconnect.DeviceManager
import org.kde.kdeconnect.DeviceState
import org.kde.kdeconnect.PairState
import org.kde.kdeconnect.datastore.ConnectionsSettingsDataStore
import org.kde.kdeconnect.helpers.CustomDevicesHelper
import org.kde.kdeconnect.helpers.HotspotHelper
import org.kde.kdeconnect.helpers.TrustedNetworkHelper

class HomeViewModel(
    deviceManager: DeviceManager,
    backgroundServiceData: BackgroundServiceData,
    private val trustedNetworkHelper: TrustedNetworkHelper,
    private val connectionsDataStore: ConnectionsSettingsDataStore,
    private val customDevicesHelper: CustomDevicesHelper,
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    val uiState = combine(
        backgroundServiceData.isConnectedToNonCellularNetwork,
        deviceManager.allDeviceStatesMap.map { it.values },
        trustedNetworkHelper.isTrustedNetwork,
        refreshing
    ) { isConnectedToNonCellularNetwork, devices, trustedNetwork,  refreshing ->
        val localNetworkAvailable = isConnectedToNonCellularNetwork || HotspotHelper.getHotspotSubnets().isNotEmpty()
        HomeUiState(
            wifiAvailable = localNetworkAvailable,
            trustedNetwork = trustedNetwork,
            connected = devices.filter { it.isReachable && it.pairState == PairState.Paired },
            availableCount = devices.count { it.isReachable && it.pairState != PairState.Paired },
            refreshing = refreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun onRefresh(context: Context) {
        refreshing.update { true }

        viewModelScope.launch {
            try {
                HotspotHelper.autoDetectAndAddHotspotIps(context, connectionsDataStore, customDevicesHelper)
            } catch (_: Exception) { }
            forceRefreshConnections(context)
            delay(timeMillis = 1500)
            refreshing.update { false }
        }
    }
}

data class HomeUiState(
    val wifiAvailable: Boolean = true,
    val trustedNetwork: Boolean = true,
    val connected: List<DeviceState> = emptyList(),
    val availableCount: Int = 0,
    val refreshing: Boolean = false
)

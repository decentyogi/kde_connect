/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kde.kdeconnect.backends.BaseLinkProvider
import org.kde.kdeconnect.backends.BaseLinkProvider.ConnectionReceiver
import org.kde.kdeconnect.backends.bluetooth.BluetoothLinkProvider
import org.kde.kdeconnect.backends.lan.LanLinkProvider
import org.kde.kdeconnect.helpers.LoggerTagged
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.helpers.PermissionHelper
import org.kde.kdeconnect.helpers.HotspotHelper
import org.kde.kdeconnect.helpers.CustomDevicesHelper
import org.kde.kdeconnect.datastore.ConnectionsSettingsDataStore
import org.kde.kdeconnect.plugins.clipboard.ClipboardFloatingActivity
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.plugins.mousepad.SendKeystrokeReceiver
import org.kde.kdeconnect.plugins.share.SendFileActivity
import org.kde.kdeconnect.ui.MainActivity
import androidx.core.app.RemoteInput
import com.leekleak.knot.R
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * This class (still) does 3 things:
 * - Keeps the app running by creating a foreground notification.
 * - Holds references to the active LinkProviders, but doesn't handle the DeviceLink those create (the KdeConnect class does that).
 * - Listens for network connectivity changes and tells the LinkProviders to re-check for devices.
 * It can be started by the KdeConnectBroadcastReceiver on some events or when the MainActivity is launched.
 */
@OptIn(ExperimentalAtomicApi::class)
class BackgroundService : Service() {
    private val data: BackgroundServiceData by inject()
    private val deviceManager: DeviceManager by inject()
    private val connectionsDataStore: ConnectionsSettingsDataStore by inject()
    private val customDevicesHelper: CustomDevicesHelper by inject()
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val linkProviders = mutableListOf<BaseLinkProvider>()
    private var lastKnownStates: Map<String, DeviceState> = emptyMap()

    private fun getEffectiveStates(states: Map<String, DeviceState>): Map<String, DeviceState> {
        if (states.isNotEmpty()) return states
        if (lastKnownStates.isNotEmpty()) return lastKnownStates
        return deviceManager.devices.value.mapValues { it.value.state.value }
    }

    fun updateForegroundNotification(states: Map<String, DeviceState>) {
        lastKnownStates = states
        val notificationManager = getSystemService<NotificationManager>()
        notificationManager?.notify(FOREGROUND_NOTIFICATION_ID, createForegroundNotification(states))
    }

    private fun registerLinkProviders() {
        linkProviders.add(get<LanLinkProvider>())
        //linkProviders.add(get<LoopbackLinkProvider>())
        linkProviders.add(get<BluetoothLinkProvider>())
    }

    suspend fun onNetworkChange(network: Network?) {
        if (!initialized.load()) {
            LoggerTagged.d { "ignoring onNetworkChange called before the service is initialized" }
            return
        }
        LoggerTagged.d { "onNetworkChange" }
        for (linkProvider in linkProviders) {
            linkProvider.onNetworkChange(network)
        }
    }

    fun addConnectionListener(connectionReceiver: ConnectionReceiver) {
        for (linkProvider in linkProviders) {
            linkProvider.addConnectionReceiver(connectionReceiver)
        }
    }

    /** This will called only once, even if we launch the service intent several times */
    @MainThread
    override fun onCreate() {
        super.onCreate()
        LoggerTagged.d { "onCreate" }
        instance = this

        serviceScope.launch {
            deviceManager.allDeviceStatesMap.collect { states ->
                updateForegroundNotification(states)
            }
        }

        // Auto-detect hotspot clients on startup
        serviceScope.launch {
            try {
                HotspotHelper.autoDetectAndAddHotspotIps(this@BackgroundService, connectionsDataStore, customDevicesHelper)
            } catch (_: Exception) { }
        }

        // Register screen on listener
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        // See: https://developer.android.com/reference/android/net/ConnectivityManager.html#CONNECTIVITY_ACTION
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        registerReceiver(KdeConnectBroadcastReceiver(), filter)

        // Watch for changes on all network connections except cellular networks
        val networkRequestBuilder = createNonCellularNetworkRequestBuilder()
        val connectivityManager = this.getSystemService<ConnectivityManager>()
        connectivityManager?.registerNetworkCallback(networkRequestBuilder.build(), object : NetworkCallback() {

            // All callbacks runs on a dedicated thread that isn't the main thread
            override fun onAvailable(network: Network) {
                LoggerTagged.i { "Valid network available" }
                data.setConnected(true)
                runBlocking { onNetworkChange(network) }
            }

            override fun onLost(network: Network) {
                LoggerTagged.i { "Valid network lost" }
                data.setConnected(false)
            }
        })

        serviceScope.launch {
            registerLinkProviders()
            addConnectionListener(deviceManager.connectionListener) // Link Providers need to be already registered
            for (linkProvider in linkProviders) {
                linkProvider.onStart()
            }
            initialized.store(true)
        }
    }

    private fun createForegroundNotification(states: Map<String, DeviceState>): Notification {
        // Why is this needed: https://developer.android.com/guide/components/services#Foreground

        val effectiveStates = getEffectiveStates(states)
        val connectedDevices = effectiveStates.values.filter { it.isReachable && it.isPaired }

        val intent = Intent(this, MainActivity::class.java)
        if (connectedDevices.size == 1) {
            // Force open screen of the only connected device
            intent.putExtra(MainActivity.EXTRA_DEVICE_ID, connectedDevices[0].deviceInfo.id)
        }

        val pi = PendingIntent.getActivity(this, 0, intent, UPDATE_IMMUTABLE_FLAGS)
        val notification = NotificationCompat.Builder(this, NotificationHelper.Channels.PERSISTENT).apply {
            setSmallIcon(R.drawable.ic_notification)
            setOngoing(true)
            setContentIntent(pi)
            setPriority(NotificationCompat.PRIORITY_MIN) //MIN so it's not shown in the status bar before Oreo, on Oreo it will be bumped to LOW
            setShowWhen(false)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            setAutoCancel(false)
            setGroup("BackgroundService")
            if (connectedDevices.isEmpty()) {
                setContentText(getString(R.string.foreground_notification_no_devices))
            }
            else {
                setContentText(
                    getString(
                        R.string.foreground_notification_devices,
                        connectedDevices.joinToString(", ") { it.deviceInfo.name }
                    )
                )

                // Action 1: Send clipboard directly from notification bar
                val sendClipboard = ClipboardFloatingActivity.getIntent(this@BackgroundService, true)
                val sendPendingClipboard = PendingIntent.getActivity(this@BackgroundService, 3, sendClipboard, UPDATE_IMMUTABLE_FLAGS)
                addAction(R.drawable.assignment, getString(R.string.foreground_notification_send_clipboard), sendPendingClipboard)

                // Action 2: Send file directly from notification bar
                val sendFileIntent = Intent(this@BackgroundService, SendFileActivity::class.java).apply {
                    if (connectedDevices.size == 1) {
                        putExtra(MainActivity.EXTRA_DEVICE_ID, connectedDevices[0].deviceInfo.id)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val sendFilePendingIntent = PendingIntent.getActivity(this@BackgroundService, 4, sendFileIntent, UPDATE_IMMUTABLE_FLAGS)
                addAction(R.drawable.description, getString(R.string.send_files), sendFilePendingIntent)

                // Action 3: Send keystroke directly from notification bar with inline reply / input
                val keystrokeIntent = Intent(this@BackgroundService, SendKeystrokeReceiver::class.java).apply {
                    action = SendKeystrokeReceiver.ACTION_SEND_KEYSTROKE
                    if (connectedDevices.size == 1) {
                        putExtra(SendKeystrokeReceiver.EXTRA_DEVICE_ID, connectedDevices[0].deviceInfo.id)
                    }
                }
                val keystrokePendingIntent = PendingIntent.getBroadcast(
                    this@BackgroundService,
                    5,
                    keystrokeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                val remoteInput = RemoteInput.Builder(SendKeystrokeReceiver.EXTRA_KEYSTROKE_TEXT)
                    .setLabel(getString(R.string.send_keystroke_hint))
                    .build()
                val keystrokeAction = NotificationCompat.Action.Builder(
                    R.drawable.keyboard,
                    getString(R.string.foreground_notification_send_keystroke),
                    keystrokePendingIntent
                ).addRemoteInput(remoteInput).build()
                addAction(keystrokeAction)
            }
        }.build()
        
        return notification
    }

    override fun onDestroy() {
        LoggerTagged.d { "onDestroy" }
        initialized.store(false)
        for (linkProvider in linkProviders) {
            linkProvider.onStop()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LoggerTagged.d { "onStartCommand" }

        val effectiveStates = getEffectiveStates(emptyMap())
        val notification = createForegroundNotification(effectiveStates)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } catch (e: Exception) {
                LoggerTagged.w(e) { "Couldn't startForeground" }
            }
        }
        else {
            try {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                LoggerTagged.w(e) { "Couldn't startForeground" }
            }
        }

        if (!PermissionHelper.hasRequiredPermissions(this)) {
            LoggerTagged.w { "BackgroundService started without required permissions, stopping" }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent != null && intent.getBooleanExtra("refresh", false)) {
            serviceScope.launch {
                try {
                    HotspotHelper.autoDetectAndAddHotspotIps(this@BackgroundService, connectionsDataStore, customDevicesHelper)
                } catch (_: Exception) { }
                onNetworkChange(null)
            }
        }
        return START_STICKY
    }

    companion object {
        const val UPDATE_IMMUTABLE_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val FOREGROUND_NOTIFICATION_ID = 1

        @JvmStatic
        var instance: BackgroundService? = null
            private set

        private var initialized = AtomicBoolean(false)

        private fun createNonCellularNetworkRequestBuilder(): NetworkRequest.Builder {
            return NetworkRequest.Builder().apply {
                addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH)
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                }
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S) {
                    addTransportType(NetworkCapabilities.TRANSPORT_USB)
                    addTransportType(NetworkCapabilities.TRANSPORT_LOWPAN)
                }
            }
        }

        fun start(context: Context) {
            LoggerTagged.d { "Start" }
            if (!PermissionHelper.hasRequiredPermissions(context)) {
                LoggerTagged.w { "Skipping start because required permissions are not granted" }
                return
            }
            val intent = Intent(context, BackgroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        @JvmStatic
        fun forceRefreshConnections(context: Context) {
            LoggerTagged.d { "ForceRefreshConnections" }
            if (!PermissionHelper.hasRequiredPermissions(context)) {
                LoggerTagged.w { "Skipping forceRefreshConnections because required permissions are not granted" }
                return
            }
            val intent = Intent(context, BackgroundService::class.java)
            intent.putExtra("refresh", true)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

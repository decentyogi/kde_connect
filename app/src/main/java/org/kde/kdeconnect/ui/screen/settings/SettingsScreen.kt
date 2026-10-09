package org.kde.kdeconnect.ui.screen.settings

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.BLUETOOTH_SCAN
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.leekleak.knot.R
import kotlinx.serialization.json.Json
import org.kde.kdeconnect.helpers.CreateFileParams
import org.kde.kdeconnect.helpers.CreateFileResultContract
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.plugins.sftp.SimpleSftpServer
import org.kde.kdeconnect.ui.AppTheme
import org.kde.kdeconnect.ui.PermissionExplanationActivity
import org.kde.kdeconnect.ui.PermissionRequest
import org.kde.kdeconnect.ui.components.BackAction
import org.kde.kdeconnect.ui.components.CategoryTitleTextSmall
import org.kde.kdeconnect.ui.components.DialogItemSelectPreference
import org.kde.kdeconnect.ui.components.DialogTextPreference
import org.kde.kdeconnect.ui.components.HazeScaffold
import org.kde.kdeconnect.ui.components.IconPreference
import org.kde.kdeconnect.ui.components.NavigatePreference
import org.kde.kdeconnect.ui.components.Preference
import org.kde.kdeconnect.ui.components.SwitchPreference
import org.kde.kdeconnect.ui.navigation.AboutKey
import org.kde.kdeconnect.ui.navigation.ConnectionsSettingsKey
import org.kde.kdeconnect.ui.navigation.Navigator
import org.kde.kdeconnect.ui.navigation.NotificationSettingsKey
import org.kde.kdeconnect.ui.navigation.SavedDevicesKey
import org.kde.kdeconnect.ui.navigation.SftpPluginSettingsKey
import org.kde.kdeconnect.ui.navigation.TelephonyPluginSettingsKey

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    exportLogs: (Context, Uri) -> Unit,
    setBluetoothEnabled: (Boolean) -> Unit,
    setDeviceName: (String) -> Unit,
    setTheme: (AppTheme) -> Unit,
    setBlur: (Boolean) -> Unit,
    saveStorageLocation: (Context, Uri) -> Unit,
    resetStorageLocation: () -> Unit,
    getDisplayPath: (Context, Uri) -> String,
    navigator: Navigator,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = CreateFileResultContract()
    ) { uri ->
        uri?.let { exportLogs(context, it) }
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        setBluetoothEnabled(result.resultCode == RESULT_OK)
    }

    val destinationSelector = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { saveStorageLocation(context, it) }
    }

    val themeEntries = stringArrayResource(R.array.theme_list)
    val themeOptions = remember(themeEntries) { AppTheme.entries.zip(themeEntries).toList() }

    val formattedDisplayPath by remember(uiState.fileDestination) {
        derivedStateOf {
            uiState.fileDestination?.let { getDisplayPath(context, it) }
        }
    }

    HazeScaffold(
        title = stringResource(R.string.settings),
        scrollState = null,
        backAction = BackAction.Normal(navigator),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "cat_app") {
                CategoryTitleTextSmall(stringResource(R.string.app))
            }

            item(key = "pref_rename") {
                DialogTextPreference(
                    title = stringResource(R.string.settings_rename),
                    icon = painterResource(R.drawable.id_card),
                    value = uiState.deviceName,
                    filterInput = {
                        DeviceHelper.filterInvalidCharactersFromDeviceName(it)
                            .take(DeviceHelper.MAX_DEVICE_NAME_LENGTH)
                    },
                    onValueChanged = {
                        setDeviceName(it)
                    }
                )
            }

            item(key = "pref_theme") {
                DialogItemSelectPreference(
                    title = stringResource(R.string.theme_dialog_title),
                    icon = painterResource(R.drawable.colors),
                    value = uiState.theme,
                    values = themeOptions,
                    onValueChanged = { setTheme(it) }
                )
            }

            item(key = "pref_blur") {
                SwitchPreference(
                    title = stringResource(R.string.blur_effects),
                    icon = painterResource(R.drawable.blur_on),
                    value = uiState.blur,
                    onValueChanged = { setBlur(it) },
                )
            }

            item(key = "pref_saved_devices") {
                NavigatePreference(
                    title = stringResource(R.string.saved_devices),
                    icon = painterResource(R.drawable.devices),
                    onClick = { navigator.goTo(SavedDevicesKey) }
                )
            }

            item(key = "cat_advanced") {
                CategoryTitleTextSmall(stringResource(R.string.advanced))
            }

            item(key = "pref_connections") {
                NavigatePreference(
                    title = stringResource(R.string.connections),
                    icon = painterResource(R.drawable.responsive_layout),
                    onClick = { navigator.goTo(ConnectionsSettingsKey) }
                )
            }

            item(key = "pref_calls_messages") {
                NavigatePreference(
                    title = stringResource(R.string.calls_messages),
                    icon = painterResource(R.drawable.perm_phone_msg),
                    onClick = { navigator.goTo(TelephonyPluginSettingsKey) }
                )
            }

            item(key = "pref_notifications_media") {
                NavigatePreference(
                    title = stringResource(R.string.notifications_media),
                    icon = painterResource(R.drawable.notifications),
                    onClick = { navigator.goTo(NotificationSettingsKey) }
                )
            }

            if (!SimpleSftpServer.SUPPORTS_NATIVEFS) {
                item(key = "pref_filesystem") {
                    NavigatePreference(
                        title = stringResource(R.string.filesystem),
                        icon = painterResource(R.drawable.folder_managed),
                        onClick = { navigator.goTo(SftpPluginSettingsKey) }
                    )
                }
            }

            item(key = "pref_bluetooth") {
                SwitchPreference(
                    title = stringResource(R.string.enable_bluetooth),
                    icon = painterResource(R.drawable.bluetooth),
                    value = uiState.bluetoothEnabled,
                    onValueChanged = { newValue ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && newValue) {
                            val missingPermissionRequests =
                                arrayOf(BLUETOOTH_CONNECT, BLUETOOTH_SCAN).filter {
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        it
                                    ) != PackageManager.PERMISSION_GRANTED
                                }.map { permission ->
                                    PermissionRequest(
                                        title = R.string.enable_bluetooth,
                                        description = R.string.bluetooth_permission_request,
                                        intentAction = permission,
                                        positiveButton = R.string.grant
                                    )
                                }

                            if (missingPermissionRequests.isNotEmpty()) {
                                bluetoothPermissionLauncher.launch(
                                    Intent(
                                        context,
                                        PermissionExplanationActivity::class.java
                                    ).apply {
                                        putExtra(
                                            "permissionRequests",
                                            Json.encodeToString(missingPermissionRequests.take(1))
                                        )
                                    })
                                return@SwitchPreference
                            }
                        }
                        setBluetoothEnabled(newValue)
                    }
                )
            }

            item(key = "pref_storage_location") {
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Preference(
                        modifier = Modifier.weight(1f),
                        title = stringResource(R.string.share_destination_folder_preference),
                        summary = formattedDisplayPath,
                        icon = painterResource(R.drawable.file_export),
                        onClick = {
                            destinationSelector.launch(null)
                        }
                    )
                    IconPreference(
                        title = stringResource(R.string.reset),
                        painter = painterResource(R.drawable.replay),
                        enabled = !uiState.fileDestinationIsDefault
                    ) {
                        resetStorageLocation()
                    }
                }
            }

            item(key = "cat_other") {
                CategoryTitleTextSmall(stringResource(R.string.other))
            }

            item(key = "pref_export_logs") {
                Preference(
                    title = stringResource(R.string.settings_export_logs),
                    summary = stringResource(R.string.settings_export_logs_text),
                    icon = painterResource(R.drawable.export_notes),
                    onClick = {
                        exportLogsLauncher.launch(CreateFileParams("text/plain", "kdeconnect-log.txt"))
                    }
                )
            }

            item(key = "pref_about") {
                NavigatePreference(
                    title = stringResource(R.string.about),
                    icon = painterResource(R.drawable.info),
                    onClick = { navigator.goTo(AboutKey) }
                )
            }
        }
    }
}

@org.kde.kdeconnect.ui.components.KdeThemePreviews
@Composable
fun SettingsPreview() {
    SettingsScreen(
        uiState = SettingsUiState(),
        exportLogs = { _, _ -> },
        setBluetoothEnabled = {},
        setDeviceName = {},
        setTheme = {},
        setBlur = {},
        saveStorageLocation = { _, _ -> },
        resetStorageLocation = {},
        getDisplayPath = { _, _ -> "" },
        navigator = Navigator()
    )
}

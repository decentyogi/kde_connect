package org.kde.kdeconnect.plugins.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.leekleak.knot.R
import kotlinx.coroutines.launch
import org.kde.kdeconnect.DeviceManager
import org.kde.kdeconnect.ui.MainActivity
import org.koin.android.ext.android.inject

class SendFileActivity : AppCompatActivity() {
    private val deviceManager: DeviceManager by inject()
    private var targetDeviceId: String? = null

    private val filePickerLauncher = registerForActivityResult(GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val deviceId = targetDeviceId ?: getFirstConnectedDeviceId()
            if (deviceId != null) {
                lifecycleScope.launch {
                    val plugin = deviceManager.getDevicePlugin(deviceId, SharePlugin::class.java)
                    val device = deviceManager.getDevice(deviceId)
                    if (plugin != null) {
                        plugin.sendUriList(uris)
                        val deviceName = device?.name ?: "PC"
                        Toast.makeText(this@SendFileActivity, getString(R.string.send_file_to, deviceName), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@SendFileActivity, R.string.could_not_find_device, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
                return@registerForActivityResult
            }
        }
        finish()
    }

    private fun getFirstConnectedDeviceId(): String? {
        val reachablePaired = deviceManager.devices.value.values.firstOrNull { it.isReachable && it.isPaired }
        return reachablePaired?.deviceId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetDeviceId = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ID)
        filePickerLauncher.launch("*/*")
    }
}

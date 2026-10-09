package org.kde.kdeconnect.plugins.mousepad

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.leekleak.knot.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kde.kdeconnect.DeviceManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SendKeystrokeReceiver : BroadcastReceiver(), KoinComponent {
    private val deviceManager: DeviceManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND_KEYSTROKE) return

        val bundle = RemoteInput.getResultsFromIntent(intent)
        val text = bundle?.getCharSequence(EXTRA_KEYSTROKE_TEXT)?.toString()
            ?: intent.getStringExtra(EXTRA_KEYSTROKE_TEXT)

        if (text.isNullOrEmpty()) return

        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)

        CoroutineScope(Dispatchers.IO).launch {
            val targetDevices = if (!deviceId.isNullOrEmpty()) {
                listOfNotNull(deviceManager.getDevice(deviceId))
            } else {
                deviceManager.devices.value.values.filter { it.isReachable && it.isPaired }
            }

            var sent = false
            for (device in targetDevices) {
                val mousePadPlugin = device.getPlugin(MousePadPlugin::class.java)
                if (mousePadPlugin != null) {
                    mousePadPlugin.sendText(text)
                    sent = true
                }
            }

            if (sent) {
                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(
                        context,
                        context.getString(R.string.keystroke_sent, text),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    companion object {
        const val ACTION_SEND_KEYSTROKE = "org.kde.kdeconnect.action.SEND_KEYSTROKE"
        const val EXTRA_KEYSTROKE_TEXT = "keystroke_text"
        const val EXTRA_DEVICE_ID = "deviceId"
    }
}

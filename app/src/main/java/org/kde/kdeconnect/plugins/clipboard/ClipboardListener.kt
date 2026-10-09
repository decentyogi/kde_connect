/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipDescription.MIMETYPE_TEXT_PLAIN
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.leekleak.knot.BuildConfig
import org.kde.kdeconnect.plugins.clipboard.history.ClipboardRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClipboardListener(private val context: Context) {
    interface ClipboardObserver {
        fun clipboardChanged(content: String)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()
    private val repository = ClipboardRepository.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentContent: String? = null
        private set
    var updateTimestamp: Long = 0
        private set

    private var cm: ClipboardManager? = null

    init {
        // Initialize ClipboardManager and clip changed listener on Main Thread
        mainHandler.post {
            try {
                cm = ContextCompat.getSystemService(context, ClipboardManager::class.java)
                cm?.addPrimaryClipChangedListener { this@ClipboardListener.onClipboardChanged() }
                
                // Read initial clipboard if available
                cm?.primaryClip?.let { clip ->
                    if (clip.itemCount > 0) {
                        val text = clip.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrBlank()) {
                            currentContent = text
                            updateTimestamp = System.currentTimeMillis()
                            repository.saveCopiedText(text)
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        // Background logcat watcher for Android Q+ when READ_LOGS is available
        CoroutineScope(Dispatchers.IO).launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ClipboardPlugin.canSyncAutomatically(context)) {
                try {
                    val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                    val process = Runtime.getRuntime().exec(arrayOf<String>("logcat", "-T", timeStamp, logcatFilter, "*:S"))
                    val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                    bufferedReader.forEachLine { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID)) {
                            context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    fun onClipboardChanged() {
        mainHandler.post {
            try {
                val clip = cm?.primaryClip ?: return@post
                if (clip.itemCount == 0) return@post
                val item = clip.getItemAt(0)
                val content = item.coerceToText(context).toString()

                if (content.isEmpty() || content == currentContent) {
                    return@post
                }
                updateTimestamp = System.currentTimeMillis()
                currentContent = content
                repository.saveCopiedText(content)

                for (observer in observers) {
                    observer.clipboardChanged(content)
                }
            } catch (_: Exception) {
                // Probably clipboard was not text
            }
        }
    }

    fun setText(text: String?) {
        if (text.isNullOrEmpty()) return
        mainHandler.post {
            try {
                updateTimestamp = System.currentTimeMillis()
                currentContent = text
                repository.saveCopiedText(text)
                cm?.setPrimaryClip(
                    ClipData(
                        ClipDescription("KDE Connect Clipboard Sync", arrayOf(MIMETYPE_TEXT_PLAIN)),
                        ClipData.Item(text)
                    )
                )
            } catch (_: Exception) { }
        }
    }

    companion object {
        private var instanceRef: WeakReference<ClipboardListener>? = null
        val instance: ClipboardListener?
            get() = instanceRef?.get()

        fun instance(context: Context): ClipboardListener {
            return instance ?: ClipboardListener(context.applicationContext).also { instanceRef = WeakReference(it) }
        }
    }
}

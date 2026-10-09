package org.kde.kdeconnect.plugins.clipboard.history

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class ClipboardRepository(context: Context) {
    private val database = ClipboardDatabase(context)
    private val dao = database.dao
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val entriesFlow: Flow<List<ClipboardEntry>> = dao.getAllFlow()
    val countFlow: Flow<Int> = dao.getCountFlow()

    fun saveCopiedText(text: String?) {
        if (text.isNullOrBlank()) return
        val trimmed = text.trim()
        scope.launch {
            val latest = dao.getLatest()
            if (latest?.text == trimmed) {
                return@launch
            }
            dao.upsert(ClipboardEntry(text = trimmed, timestamp = System.currentTimeMillis()))
            dao.trimToLimit()
        }
    }

    suspend fun deleteEntry(id: Long) {
        dao.deleteById(id)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    companion object {
        @Volatile
        private var INSTANCE: ClipboardRepository? = null

        fun getInstance(context: Context): ClipboardRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ClipboardRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

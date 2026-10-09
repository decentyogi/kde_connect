package org.kde.kdeconnect.plugins.clipboard.history

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT 1000")
    fun getAllFlow(): Flow<List<ClipboardEntry>>

    @Query("SELECT * FROM clipboard_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): ClipboardEntry?

    @Query("SELECT COUNT(*) FROM clipboard_history")
    fun getCountFlow(): Flow<Int>

    @Upsert
    suspend fun upsert(entry: ClipboardEntry)

    @Query("DELETE FROM clipboard_history WHERE id NOT IN (SELECT id FROM clipboard_history ORDER BY timestamp DESC LIMIT 1000)")
    suspend fun trimToLimit()

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM clipboard_history")
    suspend fun clearAll()
}

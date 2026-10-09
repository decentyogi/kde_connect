package org.kde.kdeconnect.plugins.clipboard.history

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "clipboard_history")
data class ClipboardEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

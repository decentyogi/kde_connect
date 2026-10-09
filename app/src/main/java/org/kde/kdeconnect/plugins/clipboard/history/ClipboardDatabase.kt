package org.kde.kdeconnect.plugins.clipboard.history

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase

@Database(entities = [ClipboardEntry::class], version = 1, exportSchema = false)
abstract class ClipboardRoomDatabase : RoomDatabase() {
    abstract fun dao(): ClipboardDao
}

class ClipboardDatabase(context: Context) {
    val db = Room.databaseBuilder(
        context.applicationContext,
        ClipboardRoomDatabase::class.java,
        "ClipboardHistory"
    ).build()

    val dao: ClipboardDao get() = db.dao()
}

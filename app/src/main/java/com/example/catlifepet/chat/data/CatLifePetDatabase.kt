package com.example.catlifepet.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, PendingMessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class CatLifePetDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: CatLifePetDatabase? = null

        fun getInstance(context: Context): CatLifePetDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CatLifePetDatabase::class.java,
                    "catlifepet-chat.db"
                ).build().also { instance = it }
            }
        }
    }
}

package com.example.catlifepet.chat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.catlifepet.chat.data.CatLifePetDatabase
import com.example.catlifepet.chat.data.ConversationEntity
import com.example.catlifepet.chat.data.PendingMessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRoomCacheInstrumentedTest {
    @Test
    fun pendingUserTextSurvivesDatabaseReopen() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val name = "chat-cache-test.db"
            context.deleteDatabase(name)
            val first = Room.databaseBuilder(context, CatLifePetDatabase::class.java, name).build()
            try {
                first.chatDao().upsertConversation(
                    ConversationEntity("conversation", null, "2026-07-22T00:00:00Z", "2026-07-22T00:00:00Z")
                )
                first.chatDao().upsertPending(
                    PendingMessageEntity("client", "conversation", "不能丢失", 1L, "failed", "网络中断")
                )
            } finally {
                first.close()
            }

            val reopened = Room.databaseBuilder(context, CatLifePetDatabase::class.java, name).build()
            try {
                val pending = reopened.chatDao().listPending("conversation")
                assertEquals(1, pending.size)
                assertEquals("不能丢失", pending.single().content)
                assertEquals("failed", pending.single().state)
            } finally {
                reopened.close()
            }
            context.deleteDatabase(name)
        }
    }
}

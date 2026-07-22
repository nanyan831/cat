package com.example.catlifepet.server.chat

import com.example.catlifepet.server.data.MemoryRecord
import com.example.catlifepet.server.data.MessageRecord
import com.example.catlifepet.server.data.MessageRole
import com.example.catlifepet.server.data.MessageStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PromptContextBuilderTest {
    @Test
    fun `context ordering and bounds are deterministic`() {
        val now = Instant.parse("2026-07-22T00:00:00Z")
        val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val memories = listOf(
            memory("00000000-0000-0000-0000-000000000003", userId, "routine", "z".repeat(900), now),
            memory("00000000-0000-0000-0000-000000000002", userId, "nickname", "请叫我小雨", now)
        )
        val conversationId = UUID.randomUUID()
        val messages = (1L..30L).map { sequence ->
            message(conversationId, sequence, "m$sequence-" + "x".repeat(700), now)
        }.reversed()

        val first = PromptContextBuilder.build("旧摘要 " + "s".repeat(2_000), memories, messages)
        val second = PromptContextBuilder.build("旧摘要 " + "s".repeat(2_000), memories.reversed(), messages.reversed())

        assertEquals(first, second)
        assertTrue(first.instructions.length <= PromptContextBuilder.MAX_INSTRUCTION_CHARS)
        assertTrue(first.messages.sumOf { it.content.length } <= PromptContextBuilder.MAX_MESSAGE_CONTEXT_CHARS)
        assertTrue(first.messages.size in 1..PromptContextBuilder.MAX_RECENT_MESSAGES)
        assertTrue(first.instructions.indexOf("nickname") < first.instructions.indexOf("routine"))
        assertEquals("m30-", first.messages.last().content.take(4))
    }

    @Test
    fun `deleted memory is excluded and summary stays bounded`() {
        val now = Instant.parse("2026-07-22T00:00:00Z")
        val userId = UUID.randomUUID()
        val deleted = memory(UUID.randomUUID().toString(), userId, "preference", "不要保留我", now)
            .copy(deletedAt = now)
        val context = PromptContextBuilder.build(null, listOf(deleted), emptyList())
        assertFalse(context.instructions.contains("不要保留我"))

        val conversationId = UUID.randomUUID()
        val records = (1L..60L).map { message(conversationId, it, "第 $it 条消息", now) }
        val summary = assertNotNull(ConversationSummaryBuilder.build(records))
        assertTrue(summary.length <= ConversationSummaryBuilder.MAX_SUMMARY_CHARS)
        assertTrue(summary.contains("第 40 条消息"))
        assertFalse(summary.contains("第 60 条消息"))
    }

    private fun memory(id: String, userId: UUID, kind: String, content: String, now: Instant) = MemoryRecord(
        UUID.fromString(id), userId, kind, content, null, 1.0, now, now
    )

    private fun message(conversationId: UUID, sequence: Long, content: String, now: Instant) = MessageRecord(
        UUID.randomUUID(), conversationId, sequence,
        if (sequence % 2L == 1L) MessageRole.USER else MessageRole.ASSISTANT,
        content, MessageStatus.COMPLETED, null, null, null, 0, 0, now, now
    )
}

package com.example.catlifepet.memory

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryActivityInstrumentedTest {
    private lateinit var fake: FakeMemorySource

    @Before
    fun setUp() {
        fake = FakeMemorySource()
        MemoryGraph.testDataSource = fake
    }

    @After
    fun tearDown() = MemoryGraph.clearTestDataSource()

    @Test
    fun explicitMemoryCanBeAddedAndRendered() {
        ActivityScenario.launch(MemoryActivity::class.java).use { scenario ->
            scenario.waitForMemoryText("还没有保存陪伴记忆。")
            scenario.onActivity { activity ->
                activity.window.decorView.memoryEditText("例如：请叫我小雨").setText("请叫我小雨")
                activity.window.decorView.memoryButton("保存这条记忆").performClick()
            }
            scenario.waitForMemoryText("已保存 1 条记忆")
            assertTrue(fake.memories.single().content == "请叫我小雨")
        }
    }
}

private class FakeMemorySource : MemoryDataSource {
    val memories = mutableListOf<CompanionMemory>()
    override suspend fun list() = MemoryOutcome.Success(memories.toList())
    override suspend fun create(kind: String, content: String): MemoryOutcome<CompanionMemory> {
        delay(10)
        return MemoryOutcome.Success(CompanionMemory("one", kind, content, "now", "now").also(memories::add))
    }
    override suspend fun delete(memoryId: String): MemoryOutcome<Unit> {
        memories.removeAll { it.id == memoryId }
        return MemoryOutcome.Success(Unit)
    }
    override suspend fun deleteAll(): MemoryOutcome<Unit> {
        memories.clear()
        return MemoryOutcome.Success(Unit)
    }
    override suspend fun clearConversations() = MemoryOutcome.Success(Unit)
}

private fun ActivityScenario<MemoryActivity>.waitForMemoryText(expected: String) {
    val deadline = System.currentTimeMillis() + 10_000L
    while (System.currentTimeMillis() < deadline) {
        var found = false
        onActivity { found = it.window.decorView.memoryContainsText(expected) }
        if (found) return
        Thread.sleep(100)
    }
    assertTrue("Timed out waiting for: $expected", false)
}

private fun View.memoryEditText(hint: String): EditText =
    memoryFind { it is EditText && it.hint?.toString() == hint } as? EditText
        ?: error("EditText not found: $hint")

private fun View.memoryButton(value: String): Button =
    memoryFind { it is Button && it.text.toString() == value } as? Button
        ?: error("Button not found: $value")

private fun View.memoryContainsText(value: String): Boolean =
    memoryFind { it is TextView && it.text.toString().contains(value) } != null

private fun View.memoryFind(predicate: (View) -> Boolean): View? {
    if (predicate(this)) return this
    if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).memoryFind(predicate)?.let { return it }
    return null
}

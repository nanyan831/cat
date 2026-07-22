package com.example.catlifepet.reminder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.catlifepet.data.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReminderSchedulingInstrumentedTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = WorkManager.getInstance(context)
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        settings = SettingsRepository(context)
        ReminderType.entries.forEach { settings.setReminderEnabled(it.settingKey(), true) }
        settings.setDebugReminderEnabled(true)
    }

    @After
    fun tearDown() {
        workManager.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        settings.setDebugReminderEnabled(false)
    }

    @Test
    fun repeatedSyncKeepsOneActiveWorkPerTypeAndDisablingCancelsIt() {
        val manager = ReminderManager(context)
        manager.syncAll()
        manager.syncAll()

        ReminderType.entries.forEach { type -> assertEquals(1, activeCount(type)) }

        settings.setReminderEnabled(SettingsRepository.ReminderSettingKey.WATER, false)
        manager.syncAll()
        assertEquals(0, activeCount(ReminderType.WATER))
        ReminderType.entries.filterNot { it == ReminderType.WATER }.forEach { type ->
            assertEquals(1, activeCount(type))
        }
    }

    private fun activeCount(type: ReminderType): Int = workManager
        .getWorkInfosForUniqueWork(ReminderManager.workName(type))
        .get(10, TimeUnit.SECONDS)
        .count { it.state != WorkInfo.State.CANCELLED && !it.state.isFinished }

    private fun ReminderType.settingKey() = when (this) {
        ReminderType.WATER -> SettingsRepository.ReminderSettingKey.WATER
        ReminderType.FOOD -> SettingsRepository.ReminderSettingKey.FOOD
        ReminderType.REST -> SettingsRepository.ReminderSettingKey.REST
        ReminderType.SLEEP -> SettingsRepository.ReminderSettingKey.SLEEP
    }
}

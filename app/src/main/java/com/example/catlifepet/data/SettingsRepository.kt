package com.example.catlifepet.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getSettings(): PetSettings {
        return PetSettings(
            petSizeDp = prefs.getInt(KEY_PET_SIZE_DP, 120),
            lastX = prefs.getInt(KEY_LAST_X, -1),
            lastY = prefs.getInt(KEY_LAST_Y, -1),
            waterReminderEnabled = prefs.getBoolean(KEY_WATER_ENABLED, true),
            foodReminderEnabled = prefs.getBoolean(KEY_FOOD_ENABLED, true),
            restReminderEnabled = prefs.getBoolean(KEY_REST_ENABLED, true),
            sleepReminderEnabled = prefs.getBoolean(KEY_SLEEP_ENABLED, true),
            doNotDisturbEnabled = prefs.getBoolean(KEY_DND_ENABLED, false),
            doNotDisturbStart = prefs.getString(KEY_DND_START, "23:30") ?: "23:30",
            doNotDisturbEnd = prefs.getString(KEY_DND_END, "07:30") ?: "07:30",
            debugReminderEnabled = prefs.getBoolean(KEY_DEBUG_REMINDER_ENABLED, false),
            muteTodayEnabled = prefs.getBoolean(KEY_MUTE_TODAY_ENABLED, false),
            muteTodayDate = prefs.getString(KEY_MUTE_TODAY_DATE, "") ?: ""
        )
    }

    fun savePetSize(sizeDp: Int) {
        prefs.edit { putInt(KEY_PET_SIZE_DP, sizeDp.coerceIn(80, 180)) }
    }

    fun savePetPosition(x: Int, y: Int) {
        prefs.edit {
            putInt(KEY_LAST_X, x)
            putInt(KEY_LAST_Y, y)
        }
    }

    fun setReminderEnabled(key: ReminderSettingKey, enabled: Boolean) {
        prefs.edit { putBoolean(key.prefKey, enabled) }
    }

    fun setDoNotDisturbEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DND_ENABLED, enabled) }
    }

    fun saveDoNotDisturbTime(start: String, end: String) {
        prefs.edit {
            putString(KEY_DND_START, start)
            putString(KEY_DND_END, end)
        }
    }

    fun setDebugReminderEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DEBUG_REMINDER_ENABLED, enabled) }
    }

    fun setMuteToday(enabled: Boolean, date: String) {
        prefs.edit {
            putBoolean(KEY_MUTE_TODAY_ENABLED, enabled)
            putString(KEY_MUTE_TODAY_DATE, if (enabled) date else "")
        }
    }

    fun isOnboardingCompleted(): Boolean = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit { putBoolean(KEY_ONBOARDING_COMPLETED, completed) }
    }

    fun isFirstPetSummonCompleted(): Boolean = prefs.getBoolean(KEY_FIRST_PET_SUMMON_COMPLETED, false)

    fun setFirstPetSummonCompleted(completed: Boolean) {
        prefs.edit { putBoolean(KEY_FIRST_PET_SUMMON_COMPLETED, completed) }
    }

    fun saveTemporaryHideUntil(epochMillis: Long) {
        prefs.edit { putLong(KEY_TEMPORARY_HIDE_UNTIL, epochMillis) }
    }

    fun getTemporaryHideUntil(): Long = prefs.getLong(KEY_TEMPORARY_HIDE_UNTIL, 0L)

    fun clearTemporaryHide() {
        prefs.edit { remove(KEY_TEMPORARY_HIDE_UNTIL) }
    }

    fun hasExistingUserData(): Boolean {
        return prefs.contains(KEY_PET_ENERGY) || prefs.contains(KEY_PET_MOOD) ||
            prefs.contains(KEY_PET_AFFECTION) || prefs.contains(KEY_HIGHEST_UNLOCKED_AFFECTION_LEVEL) ||
            prefs.contains(KEY_LAST_X) || prefs.contains(KEY_LAST_Y)
    }

    fun getStoredEnergy(): Int? {
        return if (prefs.contains(KEY_PET_ENERGY)) prefs.getInt(KEY_PET_ENERGY, 70) else null
    }

    fun getLastEnergyUpdateTime(): Long = prefs.getLong(KEY_LAST_ENERGY_UPDATE_TIME, 0L)

    fun getLastClickEnergyChangeTime(): Long = prefs.getLong(KEY_LAST_CLICK_ENERGY_CHANGE_TIME, 0L)

    fun saveEnergyState(energy: Int, lastUpdateTime: Long) {
        prefs.edit {
            putInt(KEY_PET_ENERGY, energy)
            putLong(KEY_LAST_ENERGY_UPDATE_TIME, lastUpdateTime)
        }
    }

    fun saveLastClickEnergyChangeTime(timeMillis: Long) {
        prefs.edit { putLong(KEY_LAST_CLICK_ENERGY_CHANGE_TIME, timeMillis) }
    }

    fun getStoredMood(): Int? {
        return if (prefs.contains(KEY_PET_MOOD)) prefs.getInt(KEY_PET_MOOD, 60) else null
    }

    fun getLastMoodUpdateTime(): Long = prefs.getLong(KEY_LAST_MOOD_UPDATE_TIME, 0L)

    fun getLastClickMoodChangeTime(): Long = prefs.getLong(KEY_LAST_CLICK_MOOD_CHANGE_TIME, 0L)

    fun saveMoodState(mood: Int, lastUpdateTime: Long) {
        prefs.edit {
            putInt(KEY_PET_MOOD, mood)
            putLong(KEY_LAST_MOOD_UPDATE_TIME, lastUpdateTime)
        }
    }

    fun saveLastClickMoodChangeTime(timeMillis: Long) {
        prefs.edit { putLong(KEY_LAST_CLICK_MOOD_CHANGE_TIME, timeMillis) }
    }

    fun getStoredAffection(): Int? {
        return if (prefs.contains(KEY_PET_AFFECTION)) prefs.getInt(KEY_PET_AFFECTION, 10) else null
    }

    fun getAffectionDailyState(): AffectionDailyState {
        return AffectionDailyState(
            lastInteractionDate = prefs.getString(KEY_LAST_AFFECTION_INTERACTION_DATE, "").orEmpty(),
            reminderGainDate = prefs.getString(KEY_AFFECTION_REMINDER_GAIN_DATE, "").orEmpty(),
            reminderGainCount = prefs.getInt(KEY_AFFECTION_REMINDER_GAIN_COUNT, 0),
            lastSleepRewardDate = prefs.getString(KEY_LAST_AFFECTION_SLEEP_REWARD_DATE, "").orEmpty(),
            companionDate = prefs.getString(KEY_AFFECTION_COMPANION_DATE, "").orEmpty(),
            companionAccumulatedMs = prefs.getLong(KEY_AFFECTION_COMPANION_ACCUMULATED_MS, 0L),
            companionRewardCount = prefs.getInt(KEY_AFFECTION_COMPANION_REWARD_COUNT, 0),
            dailyGainDate = prefs.getString(KEY_AFFECTION_DAILY_GAIN_DATE, "").orEmpty(),
            dailyGainAmount = prefs.getInt(KEY_AFFECTION_DAILY_GAIN_AMOUNT, 0)
        )
    }

    fun saveAffectionState(affection: Int, state: AffectionDailyState) {
        prefs.edit {
            putInt(KEY_PET_AFFECTION, affection)
            putString(KEY_LAST_AFFECTION_INTERACTION_DATE, state.lastInteractionDate)
            putString(KEY_AFFECTION_REMINDER_GAIN_DATE, state.reminderGainDate)
            putInt(KEY_AFFECTION_REMINDER_GAIN_COUNT, state.reminderGainCount)
            putString(KEY_LAST_AFFECTION_SLEEP_REWARD_DATE, state.lastSleepRewardDate)
            putString(KEY_AFFECTION_COMPANION_DATE, state.companionDate)
            putLong(KEY_AFFECTION_COMPANION_ACCUMULATED_MS, state.companionAccumulatedMs)
            putInt(KEY_AFFECTION_COMPANION_REWARD_COUNT, state.companionRewardCount)
            putString(KEY_AFFECTION_DAILY_GAIN_DATE, state.dailyGainDate)
            putInt(KEY_AFFECTION_DAILY_GAIN_AMOUNT, state.dailyGainAmount)
        }
    }

    fun getHighestUnlockedAffectionLevel(): Int? {
        return if (prefs.contains(KEY_HIGHEST_UNLOCKED_AFFECTION_LEVEL)) {
            prefs.getInt(KEY_HIGHEST_UNLOCKED_AFFECTION_LEVEL, 0)
        } else {
            null
        }
    }

    fun saveHighestUnlockedAffectionLevel(levelOrdinal: Int) {
        prefs.edit { putInt(KEY_HIGHEST_UNLOCKED_AFFECTION_LEVEL, levelOrdinal) }
    }

    fun getPendingRelationshipLevelUp(): Int? {
        return if (prefs.contains(KEY_PENDING_RELATIONSHIP_LEVEL_UP)) {
            prefs.getInt(KEY_PENDING_RELATIONSHIP_LEVEL_UP, -1).takeIf { it >= 0 }
        } else {
            null
        }
    }

    fun savePendingRelationshipLevelUp(levelOrdinal: Int?) {
        prefs.edit {
            if (levelOrdinal == null) {
                remove(KEY_PENDING_RELATIONSHIP_LEVEL_UP)
            } else {
                putInt(KEY_PENDING_RELATIONSHIP_LEVEL_UP, levelOrdinal)
            }
        }
    }

    fun getPendingRelationshipPreviousLevel(): Int? {
        return if (prefs.contains(KEY_PENDING_RELATIONSHIP_PREVIOUS_LEVEL)) {
            prefs.getInt(KEY_PENDING_RELATIONSHIP_PREVIOUS_LEVEL, -1).takeIf { it >= 0 }
        } else {
            null
        }
    }

    fun savePendingRelationshipPreviousLevel(levelOrdinal: Int?) {
        prefs.edit {
            if (levelOrdinal == null) {
                remove(KEY_PENDING_RELATIONSHIP_PREVIOUS_LEVEL)
            } else {
                putInt(KEY_PENDING_RELATIONSHIP_PREVIOUS_LEVEL, levelOrdinal)
            }
        }
    }

    fun getLastRelationshipWelcomeDate(): String {
        return prefs.getString(KEY_LAST_RELATIONSHIP_WELCOME_DATE, "").orEmpty()
    }

    fun saveLastRelationshipWelcomeDate(date: String) {
        prefs.edit { putString(KEY_LAST_RELATIONSHIP_WELCOME_DATE, date) }
    }

    data class AffectionDailyState(
        val lastInteractionDate: String,
        val reminderGainDate: String,
        val reminderGainCount: Int,
        val lastSleepRewardDate: String,
        val companionDate: String,
        val companionAccumulatedMs: Long,
        val companionRewardCount: Int,
        val dailyGainDate: String,
        val dailyGainAmount: Int
    )

    enum class ReminderSettingKey(val prefKey: String) {
        WATER("water_reminder_enabled"),
        FOOD("food_reminder_enabled"),
        REST("rest_reminder_enabled"),
        SLEEP("sleep_reminder_enabled")
    }

    companion object {
        private const val PREFS_NAME = "cat_life_pet_settings"
        private const val KEY_PET_SIZE_DP = "pet_size_dp"
        private const val KEY_LAST_X = "last_x"
        private const val KEY_LAST_Y = "last_y"
        private const val KEY_WATER_ENABLED = "water_reminder_enabled"
        private const val KEY_FOOD_ENABLED = "food_reminder_enabled"
        private const val KEY_REST_ENABLED = "rest_reminder_enabled"
        private const val KEY_SLEEP_ENABLED = "sleep_reminder_enabled"
        private const val KEY_DND_ENABLED = "do_not_disturb_enabled"
        private const val KEY_DND_START = "do_not_disturb_start"
        private const val KEY_DND_END = "do_not_disturb_end"
        private const val KEY_DEBUG_REMINDER_ENABLED = "debug_reminder_enabled"
        private const val KEY_MUTE_TODAY_ENABLED = "mute_today_enabled"
        private const val KEY_MUTE_TODAY_DATE = "mute_today_date"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_FIRST_PET_SUMMON_COMPLETED = "first_pet_summon_completed"
        private const val KEY_TEMPORARY_HIDE_UNTIL = "temporary_hide_until"
        private const val KEY_PET_ENERGY = "pet_energy"
        private const val KEY_LAST_ENERGY_UPDATE_TIME = "last_energy_update_time"
        private const val KEY_LAST_CLICK_ENERGY_CHANGE_TIME = "last_click_energy_change_time"
        private const val KEY_PET_MOOD = "pet_mood"
        private const val KEY_LAST_MOOD_UPDATE_TIME = "last_mood_update_time"
        private const val KEY_LAST_CLICK_MOOD_CHANGE_TIME = "last_click_mood_change_time"
        private const val KEY_PET_AFFECTION = "pet_affection"
        private const val KEY_LAST_AFFECTION_INTERACTION_DATE = "last_affection_interaction_date"
        private const val KEY_AFFECTION_REMINDER_GAIN_DATE = "affection_reminder_gain_date"
        private const val KEY_AFFECTION_REMINDER_GAIN_COUNT = "affection_reminder_gain_count"
        private const val KEY_LAST_AFFECTION_SLEEP_REWARD_DATE = "last_affection_sleep_reward_date"
        private const val KEY_AFFECTION_COMPANION_DATE = "affection_companion_date"
        private const val KEY_AFFECTION_COMPANION_ACCUMULATED_MS = "affection_companion_accumulated_ms"
        private const val KEY_AFFECTION_COMPANION_REWARD_COUNT = "affection_companion_reward_count"
        private const val KEY_AFFECTION_DAILY_GAIN_DATE = "affection_daily_gain_date"
        private const val KEY_AFFECTION_DAILY_GAIN_AMOUNT = "affection_daily_gain_amount"
        private const val KEY_HIGHEST_UNLOCKED_AFFECTION_LEVEL = "highest_unlocked_affection_level"
        private const val KEY_PENDING_RELATIONSHIP_LEVEL_UP = "pending_relationship_level_up"
        private const val KEY_PENDING_RELATIONSHIP_PREVIOUS_LEVEL = "pending_relationship_previous_level"
        private const val KEY_LAST_RELATIONSHIP_WELCOME_DATE = "last_relationship_welcome_date"
    }
}

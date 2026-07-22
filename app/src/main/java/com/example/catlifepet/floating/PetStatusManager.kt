package com.example.catlifepet.floating

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import com.example.catlifepet.data.SettingsRepository
import java.time.LocalDate
import java.time.ZoneId

/** Owns persisted short-term pet attributes without controlling behavior or visuals. */
class PetStatusManager private constructor(context: Context) {
    private val settingsRepository = SettingsRepository(context.applicationContext)
    private val isDebugBuild = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private var energy: Int
    private var lastUpdateTime: Long
    private var mood: Int
    private var lastMoodUpdateTime: Long
    private var affection: Int
    private var affectionDailyState: SettingsRepository.AffectionDailyState
    private var visibleSessionLastElapsedRealtime: Long? = null
    private var visibleSessionLastWallTime: Long? = null

    init {
        val now = System.currentTimeMillis()
        energy = (settingsRepository.getStoredEnergy() ?: DEFAULT_ENERGY).coerceIn(MIN_ENERGY, MAX_ENERGY)
        val storedTime = settingsRepository.getLastEnergyUpdateTime()
        lastUpdateTime = if (storedTime <= 0L || storedTime > now) now else storedTime
        settingsRepository.saveEnergyState(energy, lastUpdateTime)
        refreshEnergyFromElapsedTime(now)

        mood = (settingsRepository.getStoredMood() ?: DEFAULT_MOOD).coerceIn(MIN_MOOD, MAX_MOOD)
        val storedMoodTime = settingsRepository.getLastMoodUpdateTime()
        lastMoodUpdateTime = if (storedMoodTime <= 0L || storedMoodTime > now) now else storedMoodTime
        settingsRepository.saveMoodState(mood, lastMoodUpdateTime)
        refreshMoodFromElapsedTime(now)

        affection = (settingsRepository.getStoredAffection() ?: DEFAULT_AFFECTION)
            .coerceIn(MIN_AFFECTION, MAX_AFFECTION)
        affectionDailyState = settingsRepository.getAffectionDailyState()
        ensureCurrentAffectionDate()
        persistAffection()
    }

    @Synchronized
    fun getEnergy(): Int = energy

    @Synchronized
    fun getEnergyLevel(): EnergyLevel = levelFor(energy)

    @Synchronized
    fun getLastUpdateTime(): Long = lastUpdateTime

    @Synchronized
    fun getMood(): Int = mood

    @Synchronized
    fun getMoodLevel(): MoodLevel = moodLevelFor(mood)

    @Synchronized
    fun getLastMoodUpdateTime(): Long = lastMoodUpdateTime

    @Synchronized
    fun getAffection(): Int = affection

    @Synchronized
    fun getAffectionLevel(): AffectionLevel = affectionLevelFor(affection)

    @Synchronized
    fun getDailyAffectionGain(): Int {
        ensureCurrentAffectionDate()
        return affectionDailyState.dailyGainAmount
    }

    @Synchronized
    fun getReminderAffectionGainCount(): Int {
        ensureCurrentAffectionDate()
        return affectionDailyState.reminderGainCount
    }

    @Synchronized
    fun getCompanionshipAccumulatedMs(): Long {
        ensureCurrentAffectionDate()
        return affectionDailyState.companionAccumulatedMs
    }

    @Synchronized
    fun getCompanionshipRewardCount(): Int {
        ensureCurrentAffectionDate()
        return affectionDailyState.companionRewardCount
    }

    @Synchronized
    fun setEnergy(value: Int, reason: EnergyChangeReason = EnergyChangeReason.DEBUG_SET): Int {
        refreshEnergyFromElapsedTime()
        return updateEnergy(value.coerceIn(MIN_ENERGY, MAX_ENERGY), reason)
    }

    @Synchronized
    fun changeEnergy(delta: Int, reason: EnergyChangeReason): Int {
        refreshEnergyFromElapsedTime()
        return updateEnergy((energy + delta).coerceIn(MIN_ENERGY, MAX_ENERGY), reason)
    }

    @Synchronized
    fun applyClickEnergyCost(now: Long = System.currentTimeMillis()): Int {
        refreshEnergyFromElapsedTime(now)
        val lastClickTime = settingsRepository.getLastClickEnergyChangeTime()
        val cooldownComplete = lastClickTime <= 0L || now < lastClickTime ||
            now - lastClickTime >= CLICK_ENERGY_COOLDOWN_MS
        if (!cooldownComplete) {
            debugLog("click energy change skipped: cooldown active")
            return energy
        }
        settingsRepository.saveLastClickEnergyChangeTime(now)
        return updateEnergy((energy - 1).coerceAtLeast(MIN_ENERGY), EnergyChangeReason.USER_INTERACTION)
    }

    @Synchronized
    fun setMood(value: Int, reason: MoodChangeReason = MoodChangeReason.DEBUG_SET): Int {
        refreshMoodFromElapsedTime()
        return updateMood(value.coerceIn(MIN_MOOD, MAX_MOOD), reason)
    }

    @Synchronized
    fun changeMood(delta: Int, reason: MoodChangeReason): Int {
        refreshMoodFromElapsedTime()
        return updateMood((mood + delta).coerceIn(MIN_MOOD, MAX_MOOD), reason)
    }

    @Synchronized
    fun applyClickMoodReward(now: Long = System.currentTimeMillis()): Int {
        refreshMoodFromElapsedTime(now)
        val lastClickTime = settingsRepository.getLastClickMoodChangeTime()
        val cooldownComplete = lastClickTime <= 0L || now < lastClickTime ||
            now - lastClickTime >= MOOD_CLICK_COOLDOWN_MS
        if (!cooldownComplete) {
            debugLog("click mood change skipped: cooldown active")
            return mood
        }
        settingsRepository.saveLastClickMoodChangeTime(now)
        return updateMood((mood + 1).coerceAtMost(MAX_MOOD), MoodChangeReason.USER_INTERACTION)
    }

    @Synchronized
    fun refreshEnergyFromElapsedTime(now: Long = System.currentTimeMillis()): Int {
        if (lastUpdateTime <= 0L || lastUpdateTime > now) {
            lastUpdateTime = now
            settingsRepository.saveEnergyState(energy, lastUpdateTime)
            return energy
        }
        val fullIntervals = (now - lastUpdateTime) / ENERGY_DECAY_INTERVAL_MS
        if (fullIntervals <= 0L) return energy

        val oldEnergy = energy
        val oldLevel = levelFor(oldEnergy)
        energy = (energy - fullIntervals.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .coerceIn(MIN_ENERGY, MAX_ENERGY)
        lastUpdateTime += fullIntervals * ENERGY_DECAY_INTERVAL_MS
        settingsRepository.saveEnergyState(energy, lastUpdateTime)
        logChange(oldEnergy, energy, oldLevel, EnergyChangeReason.TIME_DECAY)
        return energy
    }

    @Synchronized
    fun refreshMoodFromElapsedTime(now: Long = System.currentTimeMillis()): Int {
        if (lastMoodUpdateTime <= 0L || lastMoodUpdateTime > now) {
            lastMoodUpdateTime = now
            settingsRepository.saveMoodState(mood, lastMoodUpdateTime)
            return mood
        }
        val fullIntervals = (now - lastMoodUpdateTime) / MOOD_RETURN_INTERVAL_MS
        if (fullIntervals <= 0L) return mood

        val oldMood = mood
        val oldLevel = moodLevelFor(oldMood)
        val steps = fullIntervals.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        mood = when {
            mood > NEUTRAL_MOOD -> (mood - steps).coerceAtLeast(NEUTRAL_MOOD)
            mood < NEUTRAL_MOOD -> (mood + steps).coerceAtMost(NEUTRAL_MOOD)
            else -> mood
        }.coerceIn(MIN_MOOD, MAX_MOOD)
        lastMoodUpdateTime += fullIntervals * MOOD_RETURN_INTERVAL_MS
        settingsRepository.saveMoodState(mood, lastMoodUpdateTime)
        logMoodChange(oldMood, mood, oldLevel, MoodChangeReason.TIME_RETURN)
        return mood
    }

    @Synchronized
    fun tryGainAffection(amount: Int, source: AffectionGainSource): Int {
        ensureCurrentAffectionDate()
        return tryGainAffectionForDate(amount, source, currentDateKey())
    }

    private fun tryGainAffectionForDate(
        amount: Int,
        source: AffectionGainSource,
        dateKey: String
    ): Int {
        if (amount <= 0) return 0
        val sourceAvailable = when (source) {
            AffectionGainSource.DAILY_FIRST_INTERACTION ->
                affectionDailyState.lastInteractionDate != dateKey
            AffectionGainSource.REMINDER_COMPLETED ->
                affectionDailyState.reminderGainCount < MAX_DAILY_REMINDER_GAINS
            AffectionGainSource.COMPANIONSHIP_TIME ->
                affectionDailyState.companionRewardCount < MAX_DAILY_COMPANION_REWARDS
            AffectionGainSource.OFFICIAL_SLEEP ->
                affectionDailyState.lastSleepRewardDate != dateKey
        }
        val granted = if (sourceAvailable) {
            minOf(
                amount,
                MAX_DAILY_AFFECTION_GAIN - affectionDailyState.dailyGainAmount,
                MAX_AFFECTION - affection
            ).coerceAtLeast(0)
        } else {
            0
        }
        if (granted <= 0) {
            debugLog(
                "Affection gain skipped: source=$source affection=$affection " +
                    "daily=${affectionDailyState.dailyGainAmount}/$MAX_DAILY_AFFECTION_GAIN"
            )
            return 0
        }

        val oldAffection = affection
        affection += granted
        affectionDailyState = affectionDailyState.copy(
            lastInteractionDate = if (source == AffectionGainSource.DAILY_FIRST_INTERACTION) dateKey
            else affectionDailyState.lastInteractionDate,
            reminderGainCount = if (source == AffectionGainSource.REMINDER_COMPLETED) {
                affectionDailyState.reminderGainCount + 1
            } else affectionDailyState.reminderGainCount,
            lastSleepRewardDate = if (source == AffectionGainSource.OFFICIAL_SLEEP) dateKey
            else affectionDailyState.lastSleepRewardDate,
            companionRewardCount = if (source == AffectionGainSource.COMPANIONSHIP_TIME) {
                affectionDailyState.companionRewardCount + 1
            } else affectionDailyState.companionRewardCount,
            dailyGainAmount = affectionDailyState.dailyGainAmount + granted
        )
        persistAffection()
        debugLog(
            "Affection $oldAffection -> $affection source=$source " +
                "daily=${affectionDailyState.dailyGainAmount}/$MAX_DAILY_AFFECTION_GAIN"
        )
        return granted
    }

    @Synchronized
    fun tryGainDailyInteractionAffection(): Int =
        tryGainAffection(1, AffectionGainSource.DAILY_FIRST_INTERACTION)

    @Synchronized
    fun tryGainReminderAffection(): Int =
        tryGainAffection(1, AffectionGainSource.REMINDER_COMPLETED)

    @Synchronized
    fun tryGainOfficialSleepAffection(): Int =
        tryGainAffection(1, AffectionGainSource.OFFICIAL_SLEEP)

    @Synchronized
    fun onPetVisible(
        elapsedRealtime: Long = SystemClock.elapsedRealtime(),
        wallTime: Long = System.currentTimeMillis()
    ) {
        ensureCurrentAffectionDate()
        if (visibleSessionLastElapsedRealtime == null) {
            visibleSessionLastElapsedRealtime = elapsedRealtime
            visibleSessionLastWallTime = wallTime
            debugLog("Affection companionship session started")
        }
    }

    @Synchronized
    fun onPetHidden(
        elapsedRealtime: Long = SystemClock.elapsedRealtime(),
        wallTime: Long = System.currentTimeMillis()
    ) {
        refreshCompanionshipTime(elapsedRealtime, wallTime)
        if (visibleSessionLastElapsedRealtime != null) {
            visibleSessionLastElapsedRealtime = null
            visibleSessionLastWallTime = null
            persistAffection()
            debugLog("Affection companionship session stopped")
        }
    }

    @Synchronized
    fun refreshCompanionshipTime(
        elapsedRealtime: Long = SystemClock.elapsedRealtime(),
        wallTime: Long = System.currentTimeMillis()
    ): Long {
        val previousElapsed = visibleSessionLastElapsedRealtime
        val previousWallTime = visibleSessionLastWallTime
        if (previousElapsed == null || previousWallTime == null) {
            ensureCurrentAffectionDate()
            return affectionDailyState.companionAccumulatedMs
        }
        if (elapsedRealtime <= previousElapsed) {
            visibleSessionLastElapsedRealtime = elapsedRealtime
            visibleSessionLastWallTime = wallTime
            debugLog("Companionship clock moved backwards; session baseline reset")
            return affectionDailyState.companionAccumulatedMs
        }

        val elapsed = (elapsedRealtime - previousElapsed).coerceAtLeast(0L)
        settleCompanionshipAcrossDates(previousWallTime, wallTime, elapsed)
        visibleSessionLastElapsedRealtime = elapsedRealtime
        visibleSessionLastWallTime = wallTime
        return affectionDailyState.companionAccumulatedMs
    }

    private fun settleCompanionshipAcrossDates(
        fromWallTime: Long,
        toWallTime: Long,
        elapsedRealtime: Long
    ) {
        val zone = ZoneId.systemDefault()
        val fromDate = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(fromWallTime), zone)
        val toDate = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(toWallTime), zone)
        if (fromDate == toDate) {
            addCompanionshipForDate(fromDate.toString(), elapsedRealtime)
            return
        }

        var remainingElapsed = elapsedRealtime
        var cursorWallTime = fromWallTime
        var cursorDate = fromDate
        while (cursorDate.isBefore(toDate) && remainingElapsed > 0L) {
            val nextMidnight = cursorDate.plusDays(1).atStartOfDay(zone)
                .toInstant().toEpochMilli()
            val wallSegment = (nextMidnight - cursorWallTime).coerceAtLeast(0L)
            val elapsedSegment = minOf(remainingElapsed, wallSegment)
            addCompanionshipForDate(cursorDate.toString(), elapsedSegment)
            remainingElapsed -= elapsedSegment
            cursorWallTime = nextMidnight
            cursorDate = cursorDate.plusDays(1)
            if (wallSegment == 0L && elapsedSegment == 0L) break
        }
        rolloverAffectionDate(toDate.toString())
        if (remainingElapsed > 0L) {
            addCompanionshipForDate(toDate.toString(), remainingElapsed)
        }
    }

    private fun addCompanionshipForDate(dateKey: String, durationMs: Long) {
        rolloverAffectionDate(dateKey)
        if (durationMs <= 0L) return
        affectionDailyState = affectionDailyState.copy(
            companionAccumulatedMs = affectionDailyState.companionAccumulatedMs + durationMs
        )
        grantCompanionshipThresholdRewards(dateKey)
        persistAffection()
    }

    @Synchronized
    fun setAffectionForDebug(value: Int): Int {
        val oldAffection = affection
        affection = value.coerceIn(MIN_AFFECTION, MAX_AFFECTION)
        persistAffection()
        debugLog("Affection $oldAffection -> $affection source=DEBUG_SET")
        return affection
    }

    @Synchronized
    fun changeAffectionForDebug(delta: Int): Int = setAffectionForDebug(affection + delta)

    @Synchronized
    fun simulateCompanionshipForDebug(durationMs: Long): Long {
        refreshCompanionshipTime()
        ensureCurrentAffectionDate()
        affectionDailyState = affectionDailyState.copy(
            companionAccumulatedMs = affectionDailyState.companionAccumulatedMs + durationMs.coerceAtLeast(0L)
        )
        grantCompanionshipThresholdRewards(currentDateKey())
        persistAffection()
        debugLog("Simulated companionship: +${durationMs}ms")
        return affectionDailyState.companionAccumulatedMs
    }

    @Synchronized
    fun resetDailyAffectionCountersForDebug() {
        refreshCompanionshipTime()
        val today = currentDateKey()
        affectionDailyState = emptyAffectionDailyState(today)
        if (visibleSessionLastElapsedRealtime != null) {
            visibleSessionLastElapsedRealtime = SystemClock.elapsedRealtime()
            visibleSessionLastWallTime = System.currentTimeMillis()
        }
        persistAffection()
        debugLog("Daily affection counters reset")
    }

    private fun grantCompanionshipThresholdRewards(dateKey: String) {
        val earnedThresholds = minOf(
            MAX_DAILY_COMPANION_REWARDS,
            (affectionDailyState.companionAccumulatedMs / COMPANIONSHIP_REWARD_INTERVAL_MS).toInt()
        )
        while (affectionDailyState.companionRewardCount < earnedThresholds) {
            if (tryGainAffectionForDate(1, AffectionGainSource.COMPANIONSHIP_TIME, dateKey) <= 0) {
                affectionDailyState = affectionDailyState.copy(
                    companionRewardCount = affectionDailyState.companionRewardCount + 1
                )
                persistAffection()
                debugLog(
                    "Companionship threshold consumed without Affection: " +
                        "daily=${affectionDailyState.dailyGainAmount}/$MAX_DAILY_AFFECTION_GAIN"
                )
            }
        }
    }

    private fun ensureCurrentAffectionDate() {
        rolloverAffectionDate(currentDateKey())
    }

    private fun rolloverAffectionDate(dateKey: String) {
        if (affectionDailyState.dailyGainDate == dateKey &&
            affectionDailyState.reminderGainDate == dateKey &&
            affectionDailyState.companionDate == dateKey
        ) return
        affectionDailyState = emptyAffectionDailyState(dateKey)
        persistAffection()
        debugLog("Affection daily counters initialized for $dateKey")
    }

    private fun emptyAffectionDailyState(today: String) = SettingsRepository.AffectionDailyState(
        lastInteractionDate = "",
        reminderGainDate = today,
        reminderGainCount = 0,
        lastSleepRewardDate = "",
        companionDate = today,
        companionAccumulatedMs = 0L,
        companionRewardCount = 0,
        dailyGainDate = today,
        dailyGainAmount = 0
    )

    private fun persistAffection() {
        settingsRepository.saveAffectionState(affection, affectionDailyState)
    }

    private fun currentDateKey(): String = LocalDate.now().toString()

    private fun updateEnergy(newEnergy: Int, reason: EnergyChangeReason): Int {
        val oldEnergy = energy
        val oldLevel = levelFor(oldEnergy)
        energy = newEnergy.coerceIn(MIN_ENERGY, MAX_ENERGY)
        settingsRepository.saveEnergyState(energy, lastUpdateTime)
        logChange(oldEnergy, energy, oldLevel, reason)
        return energy
    }

    private fun updateMood(newMood: Int, reason: MoodChangeReason): Int {
        val oldMood = mood
        val oldLevel = moodLevelFor(oldMood)
        mood = newMood.coerceIn(MIN_MOOD, MAX_MOOD)
        settingsRepository.saveMoodState(mood, lastMoodUpdateTime)
        logMoodChange(oldMood, mood, oldLevel, reason)
        return mood
    }

    private fun logChange(
        oldEnergy: Int,
        newEnergy: Int,
        oldLevel: EnergyLevel,
        reason: EnergyChangeReason
    ) {
        if (!isDebugBuild || oldEnergy == newEnergy) return
        Log.d(TAG, "Energy $oldEnergy -> $newEnergy reason=$reason")
        val newLevel = levelFor(newEnergy)
        if (oldLevel != newLevel) Log.d(TAG, "EnergyLevel $oldLevel -> $newLevel")
    }

    private fun logMoodChange(
        oldMood: Int,
        newMood: Int,
        oldLevel: MoodLevel,
        reason: MoodChangeReason
    ) {
        if (!isDebugBuild || oldMood == newMood) return
        Log.d(TAG, "Mood $oldMood -> $newMood reason=$reason")
        val newLevel = moodLevelFor(newMood)
        if (oldLevel != newLevel) Log.d(TAG, "MoodLevel $oldLevel -> $newLevel")
    }

    private fun debugLog(message: String) {
        if (isDebugBuild) Log.d(TAG, message)
    }

    enum class EnergyLevel {
        HIGH,
        NORMAL,
        LOW,
        EXHAUSTED
    }

    enum class EnergyChangeReason {
        TIME_DECAY,
        USER_INTERACTION,
        SHORT_SLEEP,
        OFFICIAL_SLEEP,
        DRINK_COMPLETED,
        EAT_COMPLETED,
        REST_COMPLETED,
        DEBUG_SET
    }

    enum class MoodLevel {
        HAPPY,
        NORMAL,
        LOW,
        SAD
    }

    enum class MoodChangeReason {
        TIME_RETURN,
        USER_INTERACTION,
        DRINK_COMPLETED,
        EAT_COMPLETED,
        REST_COMPLETED,
        AUTONOMOUS_HAPPY,
        SHORT_SLEEP,
        OFFICIAL_SLEEP,
        DEBUG_SET
    }

    enum class AffectionLevel {
        NEW,
        FAMILIAR,
        CLOSE,
        BONDED
    }

    enum class AffectionGainSource {
        DAILY_FIRST_INTERACTION,
        REMINDER_COMPLETED,
        COMPANIONSHIP_TIME,
        OFFICIAL_SLEEP
    }

    companion object {
        const val MIN_ENERGY = 0
        const val MAX_ENERGY = 100
        const val DEFAULT_ENERGY = 70
        const val ENERGY_DECAY_INTERVAL_MS = 30L * 60L * 1000L
        const val CLICK_ENERGY_COOLDOWN_MS = 30_000L
        const val MIN_MOOD = 0
        const val MAX_MOOD = 100
        const val DEFAULT_MOOD = 60
        const val NEUTRAL_MOOD = 50
        const val MOOD_RETURN_INTERVAL_MS = 60L * 60L * 1000L
        const val MOOD_CLICK_COOLDOWN_MS = 30_000L
        const val MIN_AFFECTION = 0
        const val MAX_AFFECTION = 100
        const val DEFAULT_AFFECTION = 10
        const val MAX_DAILY_AFFECTION_GAIN = 5
        const val MAX_DAILY_REMINDER_GAINS = 2
        const val MAX_DAILY_COMPANION_REWARDS = 2
        const val COMPANIONSHIP_REWARD_INTERVAL_MS = 60L * 60L * 1000L

        @Volatile
        private var instance: PetStatusManager? = null

        fun getInstance(context: Context): PetStatusManager {
            return instance ?: synchronized(this) {
                instance ?: PetStatusManager(context.applicationContext).also { instance = it }
            }
        }

        fun levelFor(value: Int): EnergyLevel {
            return when (value.coerceIn(MIN_ENERGY, MAX_ENERGY)) {
                in 75..100 -> EnergyLevel.HIGH
                in 45..74 -> EnergyLevel.NORMAL
                in 20..44 -> EnergyLevel.LOW
                else -> EnergyLevel.EXHAUSTED
            }
        }

        fun moodLevelFor(value: Int): MoodLevel {
            return when (value.coerceIn(MIN_MOOD, MAX_MOOD)) {
                in 75..100 -> MoodLevel.HAPPY
                in 45..74 -> MoodLevel.NORMAL
                in 20..44 -> MoodLevel.LOW
                else -> MoodLevel.SAD
            }
        }

        fun affectionLevelFor(value: Int): AffectionLevel {
            return when (value.coerceIn(MIN_AFFECTION, MAX_AFFECTION)) {
                in 80..100 -> AffectionLevel.BONDED
                in 50..79 -> AffectionLevel.CLOSE
                in 20..49 -> AffectionLevel.FAMILIAR
                else -> AffectionLevel.NEW
            }
        }

        private const val TAG = "CatLifePet"
    }
}

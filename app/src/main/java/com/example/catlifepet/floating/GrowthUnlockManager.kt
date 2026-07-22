package com.example.catlifepet.floating

import android.content.Context
import android.util.Log
import com.example.catlifepet.data.SettingsRepository
import java.time.LocalDate

/** Owns permanent relationship progress without changing Affection itself. */
class GrowthUnlockManager(context: Context) {
    private val settingsRepository = SettingsRepository(context.applicationContext)

    @Synchronized
    fun syncWithAffection(level: PetStatusManager.AffectionLevel) {
        val storedOrdinal = settingsRepository.getHighestUnlockedAffectionLevel()
        if (storedOrdinal == null) {
            // Existing users migrate at their current level without a historical pop-up.
            settingsRepository.saveHighestUnlockedAffectionLevel(level.ordinal)
            settingsRepository.savePendingRelationshipLevelUp(null)
            settingsRepository.savePendingRelationshipPreviousLevel(null)
            debugLog("growth migrated at current level=$level")
            return
        }

        val highest = safeLevel(storedOrdinal)
        if (level.ordinal > highest.ordinal) {
            settingsRepository.saveHighestUnlockedAffectionLevel(level.ordinal)
            settingsRepository.savePendingRelationshipPreviousLevel(highest.ordinal)
            settingsRepository.savePendingRelationshipLevelUp(level.ordinal)
            debugLog("relationship level up: $highest -> $level")
        } else if (storedOrdinal != highest.ordinal) {
            settingsRepository.saveHighestUnlockedAffectionLevel(highest.ordinal)
        }
    }

    @Synchronized
    fun currentLevel(): PetStatusManager.AffectionLevel {
        return safeLevel(settingsRepository.getHighestUnlockedAffectionLevel() ?: 0)
    }

    @Synchronized
    fun getPendingLevelUp(): RelationshipLevelUpEvent? {
        val pending = settingsRepository.getPendingRelationshipLevelUp() ?: return null
        val newLevel = safeLevel(pending)
        val oldLevel = safeLevel(
            settingsRepository.getPendingRelationshipPreviousLevel() ?: (newLevel.ordinal - 1)
        )
        return RelationshipLevelUpEvent(oldLevel, newLevel)
    }

    @Synchronized
    fun markPendingLevelUpShown() {
        settingsRepository.savePendingRelationshipLevelUp(null)
        settingsRepository.savePendingRelationshipPreviousLevel(null)
        debugLog("pending relationship level up shown")
    }

    @Synchronized
    fun isUnlocked(feature: UnlockFeature): Boolean {
        return currentLevel().ordinal >= feature.requiredLevel.ordinal
    }

    @Synchronized
    fun unlockedFeatures(): List<UnlockFeature> {
        return UnlockFeature.values().filter(::isUnlocked)
    }

    @Synchronized
    fun canShowDailyWelcome(
        isDoNotDisturb: Boolean,
        date: String = LocalDate.now().toString()
    ): Boolean {
        return isUnlocked(UnlockFeature.FAMILIAR_WELCOME) &&
            !isDoNotDisturb &&
            settingsRepository.getLastRelationshipWelcomeDate() != date
    }

    @Synchronized
    fun markDailyWelcomeShown(date: String = LocalDate.now().toString()) {
        settingsRepository.saveLastRelationshipWelcomeDate(date)
        debugLog("relationship welcome marked for date=$date")
    }

    @Synchronized
    fun resetForDebug(currentLevel: PetStatusManager.AffectionLevel) {
        settingsRepository.saveHighestUnlockedAffectionLevel(currentLevel.ordinal)
        settingsRepository.savePendingRelationshipLevelUp(null)
        settingsRepository.savePendingRelationshipPreviousLevel(null)
        settingsRepository.saveLastRelationshipWelcomeDate("")
        debugLog("growth unlock state reset at level=$currentLevel")
    }

    private fun safeLevel(ordinal: Int): PetStatusManager.AffectionLevel {
        return PetStatusManager.AffectionLevel.values().getOrElse(ordinal) {
            PetStatusManager.AffectionLevel.NEW
        }
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
    }

    data class RelationshipLevelUpEvent(
        val oldLevel: PetStatusManager.AffectionLevel,
        val newLevel: PetStatusManager.AffectionLevel
    )

    enum class UnlockFeature(val requiredLevel: PetStatusManager.AffectionLevel) {
        FAMILIAR_WELCOME(PetStatusManager.AffectionLevel.FAMILIAR),
        CLOSE_INTERACTION(PetStatusManager.AffectionLevel.CLOSE),
        BONDED_SPECIAL_DIALOGUE(PetStatusManager.AffectionLevel.BONDED),
        CURIOUS_BEHAVIOR(PetStatusManager.AffectionLevel.FAMILIAR),
        PEEK_BEHAVIOR(PetStatusManager.AffectionLevel.CLOSE),
        CUDDLE_BEHAVIOR(PetStatusManager.AffectionLevel.BONDED)
    }

    companion object {
        private const val TAG = "CatLifePet"
    }
}

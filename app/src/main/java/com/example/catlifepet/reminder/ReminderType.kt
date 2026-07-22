package com.example.catlifepet.reminder

enum class ReminderType {
    WATER,
    FOOD,
    REST,
    SLEEP;

    companion object {
        fun valueOfOrNull(value: String): ReminderType? {
            return entries.firstOrNull { it.name == value }
        }
    }
}

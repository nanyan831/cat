package com.example.catlifepet.data

data class PetSettings(
    val petSizeDp: Int = 120,
    val lastX: Int = -1,
    val lastY: Int = -1,
    val waterReminderEnabled: Boolean = true,
    val foodReminderEnabled: Boolean = true,
    val restReminderEnabled: Boolean = true,
    val sleepReminderEnabled: Boolean = true,
    val doNotDisturbEnabled: Boolean = false,
    val doNotDisturbStart: String = "23:30",
    val doNotDisturbEnd: String = "07:30",
    val debugReminderEnabled: Boolean = false,
    val muteTodayEnabled: Boolean = false,
    val muteTodayDate: String = ""
)

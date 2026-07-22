package com.example.catlifepet.floating

import android.util.Log
import kotlin.random.Random

object DialogueManager {
    private const val TAG = "CatLifePet"

    private val dialoguePools = mapOf(
        CatState.HAPPY to listOf(
            "我在呢。",
            "今天也要好好照顾自己。",
            "喝点水会舒服一点哦。",
            "坐久了可以伸个懒腰。",
            "别太累啦，我陪你。",
            "慢慢来，也没关系。",
            "记得吃饭哦。"
        ),
        CatState.DRINKING to listOf(
            "喝口水吧，我陪你。",
            "水杯在旁边吗？",
            "补一点水分会舒服些。",
            "小猫也想喝水啦。",
            "别等口渴了才喝哦。"
        ),
        CatState.EATING to listOf(
            "到饭点啦，好好吃饭。",
            "先吃饭，事情一会儿再做。",
            "小猫的饭碗已经准备好了。",
            "不要空着肚子忙太久。",
            "吃点东西再继续吧。"
        ),
        CatState.STRETCHING to listOf(
            "坐太久啦，起来活动一下。",
            "眼睛也需要休息哦。",
            "伸个懒腰吧。",
            "休息 5 分钟也没关系。",
            "小猫陪你放松一下。"
        ),
        CatState.SLEEPING to listOf(
            "已经很晚啦，该休息了。",
            "今天已经很努力了。",
            "我们明天再继续吧。",
            "早点睡，明天会更有精神。",
            "小猫要困啦，你也休息吧。"
        )
    )

    private val confirmMessages = mapOf(
        CatState.DRINKING to "好耶，喝水完成。",
        CatState.EATING to "好好吃饭，小猫放心了。",
        CatState.STRETCHING to "休息一下也很重要。",
        CatState.SLEEPING to "晚安，小猫陪你。"
    )

    private val companionDialoguePools = mapOf(
        PetStatusManager.MoodLevel.HAPPY to listOf(
            "今天心情超级好～",
            "看到你我就开心啦！",
            "嘿嘿，陪我玩一会儿嘛～",
            "今天也要一起加油呀！",
            "喵～你来找我啦！"
        ),
        PetStatusManager.MoodLevel.NORMAL to listOf(
            "我在呢。",
            "今天也要好好照顾自己。",
            "喝点水会舒服一点哦。",
            "坐久了可以伸个懒腰。",
            "别太累啦，我陪你。",
            "慢慢来，也没关系。",
            "记得吃饭哦。",
            "今天过得怎么样？",
            "摸摸我嘛～"
        ),
        PetStatusManager.MoodLevel.LOW to listOf(
            "今天想安静地陪你一会儿。",
            "我就在这里，不着急。",
            "陪我待一会儿就好啦。",
            "今天慢一点也没关系。"
        ),
        PetStatusManager.MoodLevel.SAD to listOf(
            "今天想静静待一会儿。",
            "有你在旁边就很好啦。",
            "我们一起慢慢来吧。",
            "今天也不用太勉强自己。"
        )
    )

    private val affectionDialoguePools = mapOf(
        PetStatusManager.AffectionLevel.FAMILIAR to listOf(
            "又见到你啦～",
            "今天也一起待一会儿吧。",
            "感觉越来越熟悉你了呢。"
        ),
        PetStatusManager.AffectionLevel.CLOSE to listOf(
            "看到你回来，我就安心啦。",
            "今天也想陪在你旁边。",
            "和你待在一起已经变成习惯啦～"
        ),
        PetStatusManager.AffectionLevel.BONDED to listOf(
            "又是和你一起的一天～",
            "只要一起慢慢生活就很好啦。",
            "今天也继续陪着你。",
            "嘿嘿，我们已经是很好的伙伴啦。"
        )
    )

    private val calmAffectionDialoguePools = mapOf(
        PetStatusManager.AffectionLevel.FAMILIAR to listOf(
            "今天也一起安静地待一会儿吧。",
            "慢慢熟悉彼此就很好啦。"
        ),
        PetStatusManager.AffectionLevel.CLOSE to listOf(
            "今天想安静一点，不过有你在就很好啦。",
            "看到你回来，我就安心啦。"
        ),
        PetStatusManager.AffectionLevel.BONDED to listOf(
            "不用说什么，陪在彼此旁边就很好啦。",
            "今天也安静地陪着你。",
            "有你在，我就很安心。"
        )
    )

    private val closeInteractionMessages = listOf(
        "嘿嘿，一看到你就觉得很安心。",
        "又来摸我啦～",
        "今天也陪你一会儿。"
    )

    private val closeCalmInteractionMessages = listOf(
        "今天有点安静，不过你来找我还是很好。",
        "有你在旁边，安静待着也很好。"
    )

    private val curiousMessages = mapOf(
        PetStatusManager.AffectionLevel.FAMILIAR to listOf(
            "你在忙什么呀？",
            "偷偷看看你在做什么～",
            "今天也在这里陪着你。"
        ),
        PetStatusManager.AffectionLevel.CLOSE to listOf(
            "又在认真做自己的事情啦。",
            "我就在旁边看看，不打扰你～"
        ),
        PetStatusManager.AffectionLevel.BONDED to listOf(
            "喵呜，我只是想看看你。",
            "看你一眼，然后继续陪着你。"
        )
    )

    private val curiousCalmMessages = listOf(
        "我就在这里安静看看你。",
        "今天安静一点也没关系。"
    )

    private val cuddleMessages = listOf(
        "喵呜，再靠近一点点好吗～",
        "今天也很喜欢和你待在一起。",
        "被你发现我在等你啦～",
        "再陪我一小会儿吧。",
        "这样待在一起就很好。"
    )

    private val cuddleCalmMessages = listOf(
        "今天想安静地靠近一点。",
        "什么都不说，也一起待一会儿吧。"
    )

    private val bondedSpecialMessages = listOf(
        "又是一起生活的一天～",
        "我们已经认识很久啦。",
        "以后也慢慢陪着彼此吧。",
        "嘿嘿，你已经是我最熟悉的人之一啦。"
    )

    private val bondedCalmSpecialMessages = listOf(
        "今天也安静地陪着彼此吧。",
        "认识这么久，有你在就很安心。",
        "不用说什么，我也会陪在这里。"
    )

    fun randomMessage(state: CatState): String {
        val pool = dialoguePools[state].orEmpty()
        val message = if (pool.isEmpty()) state.defaultBubble else pool[Random.nextInt(pool.size)]
        Log.d(TAG, "随机文案选择: state=$state, message=$message")
        return message
    }

    fun randomCompanionMessage(
        moodLevel: PetStatusManager.MoodLevel,
        affectionLevel: PetStatusManager.AffectionLevel
    ): String {
        val specialChance = when (affectionLevel) {
            PetStatusManager.AffectionLevel.NEW -> 0
            PetStatusManager.AffectionLevel.FAMILIAR -> 10
            PetStatusManager.AffectionLevel.CLOSE -> 20
            PetStatusManager.AffectionLevel.BONDED -> 30
        }
        val useAffectionMessage = specialChance > 0 && Random.nextInt(100) < specialChance
        val pool = if (useAffectionMessage) {
            if (moodLevel == PetStatusManager.MoodLevel.LOW || moodLevel == PetStatusManager.MoodLevel.SAD) {
                calmAffectionDialoguePools.getValue(affectionLevel)
            } else {
                affectionDialoguePools.getValue(affectionLevel)
            }
        } else {
            companionDialoguePools.getValue(moodLevel)
        }
        val message = pool[Random.nextInt(pool.size)]
        Log.d(
            TAG,
            "陪伴文案选择: moodLevel=$moodLevel, affectionLevel=$affectionLevel, " +
                "affectionSpecial=$useAffectionMessage, message=$message"
        )
        return message
    }

    fun randomClickMessage(
        moodLevel: PetStatusManager.MoodLevel,
        affectionLevel: PetStatusManager.AffectionLevel,
        unlockedFeatures: Set<GrowthUnlockManager.UnlockFeature>,
        allowCuddle: Boolean = false
    ): String {
        val roll = Random.nextInt(100)
        if (allowCuddle && GrowthUnlockManager.UnlockFeature.CUDDLE_BEHAVIOR in unlockedFeatures && roll < 10) {
            Log.d(TAG, "CUDDLE triggered from unified click decision")
            return randomCuddleMessage(moodLevel)
        }
        val isCalm = moodLevel == PetStatusManager.MoodLevel.LOW ||
            moodLevel == PetStatusManager.MoodLevel.SAD
        val growthRoll = if (allowCuddle && GrowthUnlockManager.UnlockFeature.CUDDLE_BEHAVIOR in unlockedFeatures) {
            roll - 10
        } else {
            roll
        }
        val growthPool = when {
            GrowthUnlockManager.UnlockFeature.BONDED_SPECIAL_DIALOGUE in unlockedFeatures && growthRoll in 0..9 -> {
                if (isCalm) bondedCalmSpecialMessages else bondedSpecialMessages
            }
            GrowthUnlockManager.UnlockFeature.CLOSE_INTERACTION in unlockedFeatures &&
                (GrowthUnlockManager.UnlockFeature.BONDED_SPECIAL_DIALOGUE !in unlockedFeatures && growthRoll in 0..14 ||
                    GrowthUnlockManager.UnlockFeature.BONDED_SPECIAL_DIALOGUE in unlockedFeatures && growthRoll in 10..24) -> {
                if (isCalm) closeCalmInteractionMessages else closeInteractionMessages
            }
            else -> null
        }
        if (growthPool == null) {
            return randomCompanionMessage(moodLevel, affectionLevel)
        }
        val message = growthPool[Random.nextInt(growthPool.size)]
        Log.d(
            TAG,
            "成长互动文案: mood=$moodLevel affection=$affectionLevel roll=$roll message=$message"
        )
        return message
    }

    fun randomCuriousMessage(
        moodLevel: PetStatusManager.MoodLevel,
        highestLevel: PetStatusManager.AffectionLevel
    ): String {
        val calm = moodLevel == PetStatusManager.MoodLevel.LOW ||
            moodLevel == PetStatusManager.MoodLevel.SAD
        val pool = if (calm) curiousCalmMessages else curiousMessages.getValue(highestLevel)
        val message = pool[Random.nextInt(pool.size)]
        Log.d(TAG, "CURIOUS dialogue selected: mood=$moodLevel, level=$highestLevel, message=$message")
        return message
    }

    fun randomCuddleMessage(moodLevel: PetStatusManager.MoodLevel): String {
        val calm = moodLevel == PetStatusManager.MoodLevel.LOW ||
            moodLevel == PetStatusManager.MoodLevel.SAD
        val pool = if (calm) cuddleCalmMessages else cuddleMessages
        val message = pool[Random.nextInt(pool.size)]
        Log.d(TAG, "CUDDLE dialogue selected: mood=$moodLevel, message=$message")
        return message
    }

    fun relationshipWelcomeMessage(
        moodLevel: PetStatusManager.MoodLevel,
        highestLevel: PetStatusManager.AffectionLevel
    ): String {
        val calm = moodLevel == PetStatusManager.MoodLevel.LOW ||
            moodLevel == PetStatusManager.MoodLevel.SAD
        val pool = when (highestLevel) {
            PetStatusManager.AffectionLevel.BONDED -> if (calm) {
                listOf(
                    "你回来啦。今天想安静一点，不过看到你还是很好。",
                    "今天也安静地陪着彼此吧。"
                )
            } else {
                listOf("又是和你一起的一天～", "你来啦～今天也一起待一会儿吧。")
            }
            PetStatusManager.AffectionLevel.CLOSE -> if (calm) {
                listOf("你回来啦，有你在旁边就很好。", "今天也安静地陪你一会儿。")
            } else {
                listOf("看到你回来，我就安心啦。", "今天也想陪在你旁边。")
            }
            PetStatusManager.AffectionLevel.FAMILIAR -> if (calm) {
                listOf("又见到你啦，今天慢慢来就好。", "今天也一起安静地待一会儿吧。")
            } else {
                listOf("又见到你啦～", "今天也一起待一会儿吧。", "我已经开始熟悉你的脚步了呢。")
            }
            PetStatusManager.AffectionLevel.NEW -> listOf("我在呢。")
        }
        return pool[Random.nextInt(pool.size)]
    }

    fun relationshipLevelUpMessage(level: PetStatusManager.AffectionLevel): String {
        return when (level) {
            PetStatusManager.AffectionLevel.FAMILIAR -> "我们好像越来越熟悉啦～"
            PetStatusManager.AffectionLevel.CLOSE -> "和你待在一起，已经变成很安心的事情啦。"
            PetStatusManager.AffectionLevel.BONDED -> "我们已经一起走了很久啦。以后也继续陪着你。"
            PetStatusManager.AffectionLevel.NEW -> "我们才刚刚认识。"
        }
    }

    fun confirmMessage(state: CatState): String? {
        val message = confirmMessages[state]
        if (message != null) {
            Log.d(TAG, "提醒确认反馈: state=$state, message=$message")
        }
        return message
    }
}

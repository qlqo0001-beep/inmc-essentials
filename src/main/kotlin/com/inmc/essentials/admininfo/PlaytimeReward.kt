package com.inmc.essentials.admininfo

import kr.inmc.core.reward.RewardBundle
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 플레이 시간 보상 하나(CMI `Settings/PlayTimeRewards.yml` 의 한 항목).
 *
 * - [repeat] = CMI `PayEvery` — [seconds] 마다 반복. 잰 시간은 **이 보상을 만든 뒤부터**(옛 플레이 시간으로 몇 백 번 몰아 주지 않는다).
 *   설정 `exclude-afk` 면 잠수 시간은 세지 않는다.
 * - 아니면 CMI `PayFor` — 전체 플레이 시간(바닐라 통계)이 [seconds] 를 넘으면 한 번.
 * - [autoClaim] = 저절로 받기, 아니면 `/플레이보상` 화면에서 누른다. [stack] = 반복 보상을 안 받고 두면 쌓인다(아니면 하나까지만).
 * - [permission] 이면 `inmcessentials.admininfo.prewards.reward.<id>` 가 있는 사람만(설정 `require-permission` 이면 전부).
 */
class PlaytimeReward(
    val id: String,
    var name: String = id,
    var description: MutableList<String> = mutableListOf(),
    var repeat: Boolean = false,
    var seconds: Long = 3600,
    var autoClaim: Boolean = false,
    var stack: Boolean = true,
    var permission: Boolean = false,
    var enabled: Boolean = true,
    var bundle: RewardBundle = RewardBundle(),
) {

    val periodTicks: Long get() = seconds.coerceAtLeast(1) * PlaytimeMath.TICKS_PER_SECOND

    val permissionNode: String get() = PERMISSION_PREFIX + id

    fun toYaml(): String {
        val y = YamlConfiguration()
        y.set("name", name)
        y.set("description", description)
        y.set("repeat", repeat)
        y.set("seconds", seconds)
        y.set("auto-claim", autoClaim)
        y.set("stack", stack)
        y.set("permission", permission)
        y.set("enabled", enabled)
        bundle.save(y.createSection("rewards"))
        return y.saveToString()
    }

    companion object {

        /** 보상마다의 권한 접두(노드는 문자열로 짓는다 — 선언하지 않으니 op 기본, docs 에 적음). */
        const val PERMISSION_PREFIX = AdminInfoFeature.PREWARDS + ".reward."

        /** id 규칙 — 영문 소문자·숫자·`_-`, 1~32자(YAML 키·권한 노드에 그대로 들어간다). */
        fun validId(id: String): Boolean = Regex("^[a-z0-9_-]{1,32}$").matches(id)

        fun fromYaml(id: String, text: String): PlaytimeReward {
            val y = YamlConfiguration().apply { loadFromString(text) }
            return PlaytimeReward(
                id = id,
                name = y.getString("name") ?: id,
                description = y.getStringList("description").toMutableList(),
                repeat = y.getBoolean("repeat", false),
                seconds = y.getLong("seconds", 3600).coerceAtLeast(1),
                autoClaim = y.getBoolean("auto-claim", false),
                stack = y.getBoolean("stack", true),
                permission = y.getBoolean("permission", false),
                enabled = y.getBoolean("enabled", true),
                bundle = RewardBundle.load(y.getConfigurationSection("rewards")),
            )
        }

        /**
         * 반복 보상의 진행. [progress] 에 [delta] 틱을 더하고, 한 주기([period])를 넘을 때마다 받을 것([pending])을 하나씩 늘린다.
         * [stack] 이 아니면 받을 것은 1 까지만 — 넘친 주기는 버린다(CMI StackRewards false). 결과 (진행, 받을 것).
         */
        fun advance(progress: Long, pending: Int, delta: Long, period: Long, stack: Boolean): Pair<Long, Int> {
            if (period <= 0 || delta <= 0) return progress to pending
            var p = progress + delta
            var n = pending
            val laps = p / period
            if (laps > 0) {
                p -= laps * period
                n = if (stack) (n.toLong() + laps).coerceAtMost(MAX_PENDING.toLong()).toInt() else maxOf(n, 1)
            }
            return p to n
        }

        /** 한 사람이 쌓아 둘 수 있는 반복 보상 수(오래 안 받아도 터무니없이 쌓이지 않게). */
        const val MAX_PENDING = 1000
    }
}

/** 한 사람의 보상 하나 상태. 반복: [progress](틱)·[pending](받을 것). 한 번: [claimed] 가 1 이면 받았다. */
class RewardState(var progress: Long = 0, var pending: Int = 0, var claimed: Int = 0) {
    fun copy() = RewardState(progress, pending, claimed)
}

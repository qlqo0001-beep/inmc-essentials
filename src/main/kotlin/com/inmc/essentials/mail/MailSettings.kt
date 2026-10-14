package com.inmc.essentials.mail

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration

/** 우편 화면 아래 줄의 "다른 곳에서 받을 것" 바로가기 하나 — 개수는 PAPI 로 읽고, 누르면 그 사람이 [command] 를 친다. */
data class MailShortcut(val name: String, val icon: Material, val plugin: String, val count: String, val command: String)

/**
 * mail 기능 설정(`settings/mail.yml`) — 불변 스냅샷. CMI `config.yml Command.Mail`(ExpiresIn 30 · MaxMails 50 · mailAllDays 7 · ConsoleMailSender)에 해당.
 *
 * 사용자 결정(2026-10-10): 편지는 30일·50통. **보상 우편은 만료 없음·스스로 지우지 않음** — 한도를 넘으면 넣기를 거절하고 콘솔에 경고한다
 * (넣은 쪽이 바닥에 떨구거나 제 우편함에 둔다 — core `Mail` 계약).
 */
data class MailSettings(
    /** 편지 보관 일수. 0 = 만료 없음. */
    val letterDays: Int = 30,
    /** 한 사람이 쥘 수 있는 편지 수. 0 = 제한 없음. */
    val letterLimit: Int = 50,
    /** 편지가 꽉 찼을 때 — false: 새 편지를 거절(보낸 사람에게 알림) · true: 가장 오래된 편지를 지우고 넣기(CMI). */
    val letterDropOldest: Boolean = false,
    /** 편지 한 통의 최대 글자 수. */
    val letterMaxLength: Int = 500,
    /** 플레이어가 편지를 보낸 뒤 다음 편지까지(초). 콘솔·관리자(첨부·우편전체 권한)는 없음. 0 = 없음. */
    val sendCooldownSeconds: Int = 10,
    /** 한 사람이 한 받는 사람에게 쌓아 둘 수 있는 안 읽은 편지 수(도배 막기). 콘솔·관리자는 없음. 0 = 제한 없음. */
    val maxUnreadPerSender: Int = 5,
    /** 뮤트된 사람은 편지를 못 보낸다(그림자 뮤트는 이 값과 상관없이 보낸 것처럼 보이고 넣지 않는다). */
    val denyWhenMuted: Boolean = true,
    /** 보상 우편 한도(한 사람). 넘으면 거절 + 콘솔 경고. 0 = 제한 없음. */
    val rewardLimit: Int = 500,
    /** /우편전체 — 최근 이 일수 안에 들어온 사람에게. */
    val mailAllDays: Int = 7,
    /** 콘솔이 보낸 편지의 보낸 사람 이름. */
    val consoleSender: String = "서버",
    /** 접속 알림 지연(틱). */
    val joinDelayTicks: Long = 40L,
    /** 편지가 오면 소리(키:크기:높낮이). */
    val sound: String = "entity.experience_orb.pickup:0.7:1.2",
    /** 보상이 우편으로 들어오면 액션바로 알린다. */
    val rewardActionbar: Boolean = true,
    val shortcuts: List<MailShortcut> = DEFAULT_SHORTCUTS,
) {
    companion object {
        /** 경매장의 팔린 돈·만료 물건 — 경매는 여러 서버 공용 DB 의 상태 기계라 우편으로 옮기지 않고 바로가기만(연구 D). */
        val DEFAULT_SHORTCUTS = listOf(
            MailShortcut("경매장 받을 돈", Material.GOLD_INGOT, "inmc-shop", "%inmcshop_auction_unclaimed_listings%", "경매장 미수령"),
            MailShortcut("경매장 만료 물건", Material.HOPPER, "inmc-shop", "%inmcshop_auction_expired_listings%", "경매장 만료"),
        )

        fun from(yaml: YamlConfiguration): MailSettings {
            val d = MailSettings()
            val shortcuts = if (!yaml.contains("shortcuts")) d.shortcuts else yaml.getMapList("shortcuts").mapNotNull { map ->
                val name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val command = map["command"]?.toString()?.trim()?.removePrefix("/")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val icon = map["icon"]?.toString()?.let { Material.matchMaterial(it) }?.takeIf { m -> runCatching { m.isItem }.getOrDefault(true) } ?: Material.PAPER
                MailShortcut(name, icon, map["plugin"]?.toString().orEmpty(), map["count"]?.toString().orEmpty(), command)
            }.take(2)
            return MailSettings(
                letterDays = yaml.getInt("letters.expire-days", d.letterDays).coerceAtLeast(0),
                letterLimit = yaml.getInt("letters.max-per-player", d.letterLimit).coerceAtLeast(0),
                letterDropOldest = yaml.getString("letters.when-full", "refuse")?.trim()?.lowercase() == "oldest",
                letterMaxLength = yaml.getInt("letters.max-length", d.letterMaxLength).coerceIn(16, 4000),
                sendCooldownSeconds = yaml.getInt("letters.send-cooldown-seconds", d.sendCooldownSeconds).coerceIn(0, 86400),
                maxUnreadPerSender = yaml.getInt("letters.max-unread-per-sender", d.maxUnreadPerSender).coerceAtLeast(0),
                denyWhenMuted = yaml.getBoolean("letters.deny-when-muted", d.denyWhenMuted),
                rewardLimit = yaml.getInt("rewards.max-per-player", d.rewardLimit).coerceAtLeast(0),
                mailAllDays = yaml.getInt("mail-all.days", d.mailAllDays).coerceAtLeast(1),
                consoleSender = yaml.getString("console-sender")?.takeIf { it.isNotBlank() } ?: d.consoleSender,
                joinDelayTicks = yaml.getLong("notify.join-delay-ticks", d.joinDelayTicks).coerceIn(0L, 1200L),
                sound = yaml.getString("notify.sound") ?: d.sound,
                rewardActionbar = yaml.getBoolean("notify.reward-actionbar", d.rewardActionbar),
                shortcuts = shortcuts,
            )
        }
    }
}

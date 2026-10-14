package com.inmc.essentials.chat

import org.bukkit.configuration.file.YamlConfiguration

/** 소리 하나 — `키:크기:높낮이`(마크 키 `entity.experience_orb.pickup`). */
data class ChatSound(val key: String, val volume: Float = 1f, val pitch: Float = 1f) {

    companion object {
        /** 비었거나 이상하면 null(소리 없음). 끝의 숫자 둘만 떼고 나머지는 키 — `minecraft:entity.x:1:1` 처럼 이름공간이 붙어도 된다. */
        fun parse(raw: String?): ChatSound? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split(':')
            val numbers = parts.reversed().takeWhile { it.toFloatOrNull() != null }.reversed().take(2)
            val key = parts.dropLast(numbers.size).joinToString(":").ifEmpty { return null }
            val volume = numbers.getOrNull(0)?.toFloatOrNull() ?: 1f
            val pitch = numbers.getOrNull(1)?.toFloatOrNull() ?: 1f
            return ChatSound(key.lowercase(), volume.coerceIn(0f, 10f), pitch.coerceIn(0.5f, 2f))
        }
    }
}

/**
 * chat 기능 설정(`settings/chat.yml`) — 불변 스냅샷. 값이 이상하면 기본값(던지지 않는다).
 * CMI 의 `Settings/Chat.yml`·`Settings/ChatFilter.yml`·`config.yml`(Command.Spy · Mute · Optimizations.helpop)에 해당한다.
 */
data class ChatSettings(
    // --- 귓속말 ---
    /** CMI ReplyToLastMessenger — true 면 /답장은 늘 마지막으로 내게 보낸 사람. */
    val replyToLastMessenger: Boolean = false,
    /** CMI LastMessengerTimeOut(초) — 이 안에 대화한 상대가 있으면 다른 사람이 보내도 그 상대에게 답한다. */
    val replyTimeoutSeconds: Int = 120,
    /** CMI Mute.DenyPrivateMessages — 뮤트된 사람은 귓속말·도움요청을 못 보낸다. */
    val denyWhenMuted: Boolean = true,
    /** CMI PrivateMessagesGroups — 받는 사람의 그룹마다 `inmcessentials.chat.msg.group.<그룹>` 이 있어야 보낸다. */
    val groupsEnabled: Boolean = false,
    /** 그룹 이름(앞이 높다). 받는 사람의 그룹 = 이 목록에서 처음으로 `group.<이름>` 권한이 있는 것(LuckPerms 가 주는 권한). */
    val groups: List<String> = emptyList(),
    val pmSound: ChatSound? = ChatSound("entity.experience_orb.pickup", 0.6f, 1.6f),
    val logPrivate: Boolean = true,
    val consoleName: String = "서버",
    val maxLength: Int = 256,

    // --- 도움요청 ---
    /** CMI Optimizations.helpop.feedbackMessage — 보낸 사람에게 보낸 글 대신 "전달됐다" 안내만. */
    val helpopFeedback: Boolean = true,

    // --- 스파이 ---
    /** CMI Command.Spy.BlackListed — 명령 스파이에 안 보이는 명령(루트 이름). */
    val spyHidden: Set<String> = setOf("register", "login", "l"),
    /** CMI Command.Spy.CommandList — 비우면 전부. 채우면 `…spy.all` 권한이 없는 스파이는 이 명령만 본다. */
    val spyOnly: Set<String> = emptySet(),

    // --- 무시 ---
    /** CMI Chat.IgnorePublicMessage — 무시한 사람의 공개 채팅도 내 화면에서 뺀다. false 면 귓속말·편지만 막는다. */
    val ignorePublic: Boolean = true,

    // --- 도배 ---
    /** CMI ChatFilter.DuplicatedMessagePrevention.Use — CMI 처럼 기본 꺼짐. */
    val spamEnabled: Boolean = false,
    val spamPercentage: Int = 80,
    val spamIntervalSeconds: Int = 5,
    val spamMinAmount: Int = 2,
    val spamPrivate: Boolean = true,

    // --- 거리 채팅 ---
    /** CMI Ranged.General — 일반 채팅이 닿는 거리. -1(또는 0) = 제한 없음(모든 월드). */
    val rangeGeneral: Int = -1,
    /** CMI Ranged.Shout — `!` 외침의 거리. 0 = 모든 월드, -1 = 외침 끔. 거리 채팅이 켜져 있을 때만 뜻이 있다. */
    val rangeShout: Int = 200,
    /** CMI Ranged.ShoutColor — 외침 글의 기본 색(MiniMessage 색 이름 또는 #rrggbb). 비우면 그대로. */
    val shoutColor: String = "",
    /** CMI Ranged.ShoutCost — 외침 한 번의 비용(기본 화폐). 0 = 무료. */
    val shoutCost: Double = 0.0,
    val dynamicRange: Boolean = false,
    /** CMI Ranged.Dynamic.Limits — (접속 인원, 거리). 인원이 그 이상이면 그 거리. 거리 0 = 제한 없음. */
    val dynamicLimits: List<Pair<Int, Int>> = listOf(1 to 0, 10 to 1000, 20 to 500, 30 to 400, 40 to 300, 50 to 200),
    /** 거리 안에 아무도 없으면 보낸 사람에게 알린다. */
    val notifyNobody: Boolean = true,

    // --- 링크 ---
    /** CMI TranslateLink. */
    val linkEnabled: Boolean = true,
    /** CMI LinkRegex. */
    val linkRegex: Regex = Regex(DEFAULT_LINK_REGEX),

    // --- 타이틀(틱) ---
    val titleFadeIn: Int = 10,
    val titleStay: Int = 70,
    val titleFadeOut: Int = 20,
) {

    companion object {
        /** CMI 기본 LinkRegex 그대로(앞의 `(^| )` 는 빈칸을 남기고 바꾼다). */
        const val DEFAULT_LINK_REGEX = """(^| )((http|https|ftp|ftps)\:\/\/)?[a-zA-Z0-9\-]+\.[a-zA-Z]{2,3}(\/\S*)?([^\s|^\)]+)"""

        private val GROUP_NAME = Regex("[a-z0-9_.-]+")

        fun from(yaml: YamlConfiguration): ChatSettings {
            val d = ChatSettings()
            val limits = yaml.getStringList("ranged.dynamic.limits").mapNotNull { line ->
                val parts = line.split('-').map { it.trim().toIntOrNull() }
                val count = parts.getOrNull(0)
                val range = parts.getOrNull(1)
                if (count == null || range == null || count < 0 || range < 0) null else count to range
            }.sortedBy { it.first }
            val regex = yaml.getString("link.regex")?.takeIf { it.isNotBlank() }?.let { runCatching { Regex(it) }.getOrNull() } ?: d.linkRegex
            return ChatSettings(
                replyToLastMessenger = yaml.getBoolean("private.reply-to-last-messenger", d.replyToLastMessenger),
                replyTimeoutSeconds = yaml.getInt("private.reply-timeout-seconds", d.replyTimeoutSeconds).coerceAtLeast(0),
                denyWhenMuted = yaml.getBoolean("private.deny-when-muted", d.denyWhenMuted),
                groupsEnabled = yaml.getBoolean("private.groups.enabled", d.groupsEnabled),
                groups = yaml.getStringList("private.groups.order").map { it.trim().lowercase() }.filter { it.matches(GROUP_NAME) },
                pmSound = if (yaml.contains("private.sound")) ChatSound.parse(yaml.getString("private.sound")) else d.pmSound,
                logPrivate = yaml.getBoolean("private.log-to-console", d.logPrivate),
                consoleName = yaml.getString("private.console-name")?.takeIf { it.isNotBlank() } ?: d.consoleName,
                maxLength = yaml.getInt("private.max-length", d.maxLength).coerceIn(16, 4096),
                helpopFeedback = yaml.getBoolean("helpop.feedback-only", d.helpopFeedback),
                spyHidden = if (yaml.contains("spy.hidden-commands")) roots(yaml.getStringList("spy.hidden-commands")) else d.spyHidden,
                spyOnly = roots(yaml.getStringList("spy.only-commands")),
                ignorePublic = yaml.getBoolean("ignore.public-chat", d.ignorePublic),
                spamEnabled = yaml.getBoolean("spam.enabled", d.spamEnabled),
                spamPercentage = yaml.getInt("spam.percentage", d.spamPercentage).coerceIn(1, 100),
                spamIntervalSeconds = yaml.getInt("spam.interval-seconds", d.spamIntervalSeconds).coerceIn(1, 3600),
                spamMinAmount = yaml.getInt("spam.min-amount", d.spamMinAmount).coerceIn(1, 100),
                spamPrivate = yaml.getBoolean("spam.include-private", d.spamPrivate),
                rangeGeneral = yaml.getInt("ranged.general", d.rangeGeneral).coerceAtLeast(-1),
                rangeShout = yaml.getInt("ranged.shout", d.rangeShout).coerceAtLeast(-1),
                shoutColor = yaml.getString("ranged.shout-color")?.trim().orEmpty(),
                shoutCost = yaml.getDouble("ranged.shout-cost", d.shoutCost).coerceAtLeast(0.0),
                dynamicRange = yaml.getBoolean("ranged.dynamic.enabled", d.dynamicRange),
                dynamicLimits = if (yaml.contains("ranged.dynamic.limits")) limits else d.dynamicLimits,
                notifyNobody = yaml.getBoolean("ranged.notify-nobody", d.notifyNobody),
                linkEnabled = yaml.getBoolean("link.enabled", d.linkEnabled),
                linkRegex = regex,
                titleFadeIn = yaml.getInt("title.fade-in", d.titleFadeIn).coerceIn(0, 1200),
                titleStay = yaml.getInt("title.stay", d.titleStay).coerceIn(0, 12000),
                titleFadeOut = yaml.getInt("title.fade-out", d.titleFadeOut).coerceIn(0, 1200),
            )
        }

        /** 명령 이름 목록 → 소문자 루트(앞의 `/`·이름공간은 뗀다). */
        fun roots(list: List<String>): Set<String> =
            list.map { ChatRules.commandRoot(it) }.filter { it.isNotEmpty() }.toSet()
    }
}

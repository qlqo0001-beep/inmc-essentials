package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import kr.inmc.core.util.Durations
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 명령들이 같이 쓰는 작은 것 — 시간·날짜 글, 대상 고르기, 깃발(`-s`). */
internal object AdminText {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun date(ms: Long?): String = if (ms == null || ms <= 0) "-" else DATE.format(Instant.ofEpochMilli(ms))

    /** 밀리초 → "1일 2시간 3분"(초는 1분 미만일 때만). */
    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s < 60) Durations.formatShort(s) else Durations.formatShort(s - s % 60)
    }

    /** 틱 → "12시간 3분". */
    fun ticks(t: Long): String = ago(t * 50)

    fun escape(text: String): String = MiniMessage.miniMessage().escapeTags(text)

    fun perm(node: String): (CommandSourceStack) -> Boolean = { it.sender.hasPermission(node) }

    /** 명령을 친 사람(실행자가 바뀌었으면 그 사람). */
    fun self(ctx: CommandContext<CommandSourceStack>): Player? = ctx.source.executor as? Player ?: ctx.source.sender as? Player

    /**
     * 온 적 있는 사람 하나(이름·닉네임·uuid). 없으면 알리고 null.
     *
     * **정확한 이름이 먼저**: 보이는 접속자의 정확한 이름(대소문자 무시) → 기록의 정확한 이름 → uuid → 그다음에야 `ess.args.offline`(부분 이름·닉네임).
     * `ess.args.offline` 은 접속자의 앞부분 일치를 기록의 정확한 이름보다 먼저 보므로, 접속 안 한 `Bob` 을 친 `/투표편집 Bob 초기화` 가 접속 중인
     * `Bobby` 를 고치는 일이 생긴다(편집 명령이 이것을 쓴다).
     */
    fun offline(ess: Ess, sender: CommandSender, input: String): Pair<UUID, String>? {
        val text = input.trim()
        kr.inmc.core.integration.Vanish.visibleOnline(sender).firstOrNull { it.name.equals(text, true) }?.let { return it.uniqueId to it.name }
        ess.users.byName(text)?.let { return it.id to it.name }
        runCatching { UUID.fromString(text) }.getOrNull()?.let { id ->
            ess.users.get(id)?.let { return id to it.name }
        }
        ess.args.offline(sender, text)?.let { return it.id to it.name }
        ess.messages.send(sender, "user-not-found", Ph.of().value(escape(input)))
        return null
    }

    /** 빈칸으로 나눈 꼬리에서 `-s` 를 떼어 낸다. (남은 말, 조용히?) */
    fun flags(tail: String?): Pair<List<String>, Boolean> {
        val words = Args.split(tail.orEmpty())
        val silent = words.any { it.equals("-s", true) }
        return words.filterNot { it.equals("-s", true) } to silent
    }

    /** 쪽 번호(1부터) → 0부터. 이상하면 0. */
    fun page(raw: String?): Int = (raw?.trim()?.removePrefix("-p:")?.toIntOrNull() ?: 1).coerceAtLeast(1) - 1
}

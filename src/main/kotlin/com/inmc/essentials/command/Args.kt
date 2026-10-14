package com.inmc.essentials.command

import com.inmc.essentials.Ess
import com.inmc.essentials.store.UserRecord
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.UUID

/**
 * 플레이어 인자 — 모든 기능이 같은 규칙으로 사람을 찾는다(`ess.args`).
 *
 * - 제안은 [Vanish.visibleNames] — **투명인 사람은 볼 수 있는 사람에게만**(사용자 결정: 투명이 새는 곳 전부 막기).
 * - 찾기 순서([online]): 정확한 이름 → 대소문자 무시 → 앞부분이 하나만 맞음 → 들어 있는 것이 하나만 → TitleForge 닉네임. 후보는 늘
 *   **부른 사람에게 보이는 접속자**뿐이다 — 볼 수 없는 투명인 사람은 이름을 정확히 쳐도 "없음".
 * - 오프라인([offline])은 **정확한 이름을 먼저** — 보이는 접속자의 정확한 이름(대소문자 무시) → 온 적 있는 사람의 정확한 이름 → 그다음에야
 *   접속자의 부분 이름(앞부분·들어 있음) → 닉네임. 전에는 접속자 부분 이름이 먼저라 `/밴 Luuk` 이 접속 중인 `Luuk666` 을 골랐다(2026-10-14).
 *   기록의 부분 이름은 안 받는다(온 사람 수천 명 중 앞부분 맞추기는 엉뚱한 사람을 고른다).
 * - 인자([TOKEN])는 **빈칸까지 한 낱말**을 그대로 읽는다 — 한글 닉네임·`*`·`전원` 도 된다(Brigadier `word()` 는 영문·숫자·`_-.+` 밖에서
 *   멈춰 한글 닉네임이 [online] 까지 오지 못한다). 뒤에 다른 인자를 이어 붙여도 된다. 손님(클라이언트)에게는 `word()` 로 보인다 — 영문 이름은
 *   입력창 색·뒤 인자 제안이 그대로이고, 한글을 치면 입력창에서만 빨갛게 보일 뿐 엔터를 치면 서버가 받는다.
 */
class Args(private val ess: Ess) {

    /** 플레이어 하나 — 빈칸까지 한 낱말([TOKEN], 한글 닉네임 포함) + 보이는 접속자 이름 제안. 읽기는 `StringArgumentType.getString(ctx, 이름)`. */
    fun player(name: String = "플레이어"): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument(name, TOKEN).suggests(NAMES)

    /** 플레이어 하나 또는 전원(`all`·`*`·`전원` — [isAll]) — 제안에 `all` 도. */
    fun players(name: String = "대상"): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument(name, TOKEN).suggests(NAMES_AND_ALL)

    /** [sender] 에게 보이는 접속자 중 [input] 에 맞는 사람(위 순서). 없으면 null. */
    fun online(sender: CommandSender?, input: String): Player? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val visible = Vanish.visibleOnline(sender)
        pick(text, visible) { it.name }?.let { return it }
        val id = nicknameOwner(text) ?: return null
        return visible.firstOrNull { it.uniqueId == id }
    }

    /** `all`·`*` 면 [sender] 에게 보이는 접속자 전부, 아니면 [online] 하나. 못 찾으면 null(빈 서버의 전원은 빈 목록). */
    fun targets(sender: CommandSender?, input: String): List<Player>? =
        if (isAll(input)) Vanish.visibleOnline(sender) else online(sender, input)?.let { listOf(it) }

    /**
     * 온 적 있는 사람. 보이는 접속자의 **정확한** 이름(대소문자 무시) → 기록의 정확한 이름 → 보이는 접속자의 부분 이름([online]) → 닉네임.
     * 투명이라 안 보이는 접속자도 **기록으로는** 찾힌다(밴·정보처럼 오프라인 대상을 받는 명령) — 접속 중이라고 알릴지는 부른 쪽이 [Vanish.canSee] 로 정한다.
     */
    fun offline(sender: CommandSender?, input: String): UserRecord? {
        val text = input.trim()
        if (text.isEmpty()) return null
        Vanish.visibleOnline(sender).firstOrNull { it.name.equals(text, ignoreCase = true) }?.let { return ess.users.get(it.uniqueId) }
        ess.users.byName(text)?.let { return it }
        online(sender, text)?.let { return ess.users.get(it.uniqueId) }
        return nicknameOwner(text)?.let { ess.users.get(it) }
    }

    /** 사람 이름 제안(보이는 접속자). 기능이 자기 인자에 붙일 때. */
    val names: SuggestionProvider<CommandSourceStack> get() = NAMES

    // --- TitleForge 닉네임(리플렉션 — ARCHITECTURE "리플렉션 진입점" `uuidOfNickname`) ------------------------

    @Volatile
    private var cachedPlugin: Any? = null

    @Volatile
    private var cachedMethod: Method? = null

    /** 닉네임 → uuid(오프라인 포함, 둘 이상이면 null). 맵만 보므로 메인에서 불러도 된다. 없거나 실패하면 null. */
    fun nicknameOwner(nickname: String): UUID? {
        val plugin = Bukkit.getPluginManager().getPlugin("InMc-TitleForge")?.takeIf { it.isEnabled } ?: return null
        return try {
            var method = cachedMethod
            if (method == null || cachedPlugin !== plugin) {
                method = plugin.javaClass.getMethod("uuidOfNickname", String::class.java)
                cachedPlugin = plugin
                cachedMethod = method
            }
            method!!.invoke(plugin, nickname) as? UUID
        } catch (_: Throwable) {
            cachedPlugin = null
            cachedMethod = null
            null
        }
    }

    companion object {

        /** "전원" 으로 읽는 말([players] 인자로 셋 다 들어온다). */
        val ALL: List<String> = listOf("all", "*", "전원")

        /** 빈칸(또는 끝)까지 읽는다 — 한글·기호 포함. 빈칸 자체는 남긴다(다음 인자 구분). 아무것도 없으면 빈 문자열(`word()` 와 같다). */
        fun readToken(reader: StringReader): String {
            val start = reader.cursor
            while (reader.canRead() && reader.peek() != ' ') reader.skip()
            return reader.string.substring(start, reader.cursor)
        }

        /** 사람 인자의 형식 — 서버는 [readToken], 손님에게는 `word()`(Paper 가 손님에게 보낼 때 바꿔 보낸다). */
        val TOKEN: ArgumentType<String> = object : CustomArgumentType<String, String> {
            override fun parse(reader: StringReader): String = readToken(reader)

            override fun getNativeType(): ArgumentType<String> = StringArgumentType.word()

            override fun getExamples(): Collection<String> = listOf("Steve", "홍길동", "all")
        }

        fun isAll(input: String): Boolean = input.trim().lowercase() in ALL

        /**
         * 이름 고르기(순수) — 정확히 → 대소문자 무시 → 앞부분이 하나만 → 들어 있는 것이 하나만. 둘 이상 맞으면 그 단계에서 null
         * (다음 단계로 넘어가지 않는다 — 앞부분이 둘 맞는데 "들어 있음" 으로 하나를 고르면 엉뚱한 사람이 된다).
         */
        fun <T> pick(input: String, candidates: Collection<T>, name: (T) -> String): T? {
            candidates.firstOrNull { name(it) == input }?.let { return it }
            val lower = input.lowercase()
            candidates.firstOrNull { name(it).lowercase() == lower }?.let { return it }
            val prefixed = candidates.filter { name(it).lowercase().startsWith(lower) }
            if (prefixed.size == 1) return prefixed[0]
            if (prefixed.size > 1) return null
            val containing = candidates.filter { name(it).lowercase().contains(lower) }
            return containing.singleOrNull()
        }

        /** greedy 꼬리를 빈칸으로 쪼갠다(빈 조각은 버림). 한글 인자가 둘 이상일 때. */
        fun split(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        private val NAMES = SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val typed = builder.remainingLowerCase
            for (name in Vanish.visibleNames(ctx.source.sender)) if (name.lowercase().startsWith(typed)) builder.suggest(name)
            builder.buildFuture()
        }

        private val NAMES_AND_ALL = SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val typed = builder.remainingLowerCase
            if ("all".startsWith(typed)) builder.suggest("all")
            for (name in Vanish.visibleNames(ctx.source.sender)) if (name.lowercase().startsWith(typed)) builder.suggest(name)
            builder.buildFuture()
        }
    }
}

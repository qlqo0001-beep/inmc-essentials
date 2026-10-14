package com.inmc.essentials.mail

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.util.Durations
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/우편`(mail) · `/우편전체`(mailall) — 계획 4절. 하위 명령은 한글·영어 둘 다(CMI `mail send|read|clear|sendtemp`):
 * 받기(claim) · 읽기(read) · 보내기(send) · 임시(sendtemp) · 첨부(attach) · 지우기(clear) · 보기(view) · 비우기(purge) · 도움(help).
 */
class MailCommands(private val ess: Ess, private val feature: MailFeature) {

    private val service: MailService get() = feature.service
    private val store: MailStore get() = feature.store
    private val lastSent get() = feature.lastSent

    fun register() {
        val root = Commands.literal("우편").requires { it.sender.hasPermission(MailFeature.USE) }
            .executes { ctx -> player(ctx)?.let { MailMenu(ess, it, feature, it.uniqueId, it.name).show() }; 1 }
        for (name in listOf("받기", "claim")) root.then(Commands.literal(name).executes { ctx -> player(ctx)?.let(::claim); 1 })
        for (name in listOf("읽기", "read")) root.then(
            Commands.literal(name).executes { ctx -> player(ctx)?.let { list(it) }; 1 }
                .then(Commands.argument("번호", StringArgumentType.greedyString()).suggests(letterHandles)
                    .executes { ctx -> player(ctx)?.let { read(it, StringArgumentType.getString(ctx, "번호")) }; 1 }),
        )
        for (name in listOf("보내기", "send")) root.then(sendTree(name, temp = false))
        for (name in listOf("임시", "sendtemp")) root.then(sendTree(name, temp = true))
        for (name in listOf("첨부", "attach")) root.then(
            Commands.literal(name).requires { it.sender.hasPermission(MailFeature.ATTACH) }
                .then(ess.args.player("받는사람")
                    .executes { ctx -> attach(ctx, ""); 1 }
                    .then(Commands.argument("내용", StringArgumentType.greedyString())
                        .executes { ctx -> attach(ctx, StringArgumentType.getString(ctx, "내용")); 1 })),
        )
        for (name in listOf("지우기", "clear")) root.then(
            Commands.literal(name).executes { ctx -> player(ctx)?.let { clear(it, "읽은것") }; 1 }
                .then(Commands.argument("대상", StringArgumentType.greedyString()).suggests(clearHints)
                    .executes { ctx -> player(ctx)?.let { clear(it, StringArgumentType.getString(ctx, "대상")) }; 1 }),
        )
        for (name in listOf("보기", "view")) root.then(
            Commands.literal(name).requires { it.sender.hasPermission(MailFeature.OTHERS) }
                .then(ess.args.player().executes { ctx -> view(ctx); 1 }),
        )
        for (name in listOf("비우기", "purge")) root.then(
            Commands.literal(name).requires { it.sender.hasPermission(MailFeature.OTHERS) }
                .then(ess.args.player()
                    .then(Commands.argument("범위", StringArgumentType.greedyString()).suggests(purgeHints)
                        .executes { ctx -> purge(ctx); 1 })),
        )
        for (name in listOf("도움", "help")) root.then(Commands.literal(name).executes { ctx -> usage(ctx.source.sender); 1 })
        ess.commands.add(root.build(), "우편함 — 보상·편지 받기, 편지 보내기·읽기", listOf("mail"))

        ess.commands.add(
            Commands.literal("우편전체").requires { it.sender.hasPermission(MailFeature.ALL) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "mail-all-usage"); 1 }
                .then(Commands.argument("내용", StringArgumentType.greedyString())
                    .executes { ctx -> mailAll(ctx.source.sender, StringArgumentType.getString(ctx, "내용")); 1 })
                .build(),
            "최근 접속한 모두에게 편지 · 회수", listOf("mailall"),
        )
    }

    private fun sendTree(name: String, temp: Boolean): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).requires { it.sender.hasPermission(MailFeature.SEND) }
            .executes { ctx -> ess.messages.send(ctx.source.sender, "mail-usage"); 1 }
            .then(ess.args.player("받는사람")
                .executes { ctx -> ess.messages.send(ctx.source.sender, "mail-usage"); 1 }
                .then(Commands.argument("내용", StringArgumentType.greedyString())
                    .executes { ctx -> send(ctx, temp); 1 }))

    // --- 받기 · 읽기 · 지우기 -------------------------------------------------------------------------

    private fun claim(player: Player) {
        if (!service.ensureSynced(player)) return
        val result = service.claim(player, null)
        when {
            result.claimed == 0 && result.leftBehind == 0 -> ess.messages.send(player, "mail-claim-nothing")
            result.partial -> ess.messages.send(player, "mail-claimed-partial", Ph.of().count(result.claimed).amount(result.leftBehind.toString()))
            else -> ess.messages.send(player, "mail-claimed", Ph.of().count(result.claimed))
        }
    }

    /** 편지(새것부터) — 번호는 이 순서. */
    private fun letters(owner: UUID): List<MailEntry> = store.of(owner).filter { it.isLetter }.sortedByDescending { it.parcel.at }

    private fun list(player: Player) {
        val letters = letters(player.uniqueId)
        if (letters.isEmpty()) return ess.messages.send(player, "mail-read-empty")
        ess.messages.send(player, "mail-read-header", Ph.of().count(letters.size))
        for ((index, entry) in letters.withIndex()) {
            val p = entry.parcel
            val preview = p.text.orEmpty().replace('\n', ' ').let { if (it.length > 30) it.take(30) + "…" else it }
            ess.messages.send(
                player, if (entry.readAt == null) "mail-read-entry-unread" else "mail-read-entry",
                Ph.of().rank("${index + 1}").player(MM.escapeTags(p.senderName ?: "?")).time(service.date(p.at))
                    .value(safe(preview)).raw("{아이디}", entry.handle).raw("{실명}", p.senderName ?: "")
                    .raw("{첨부}", if (entry.hasContent) ess.messages.raw("mail-read-attachment-mark") else ""),
            )
        }
    }

    private fun read(player: Player, input: String) {
        val entry = pick(player.uniqueId, input.trim()) ?: return ess.messages.send(player, "mail-not-found", Ph.of().value(input))
        service.openLetter(player, entry)
    }

    /** 번호(1 = 가장 새 편지) 또는 `#id 앞부분`([MailRules.pick]) — 편지만 골라진다(보상 우편의 id 는 못 찾은 것으로). */
    private fun pick(owner: UUID, input: String): MailEntry? = MailRules.pick(letters(owner), store.of(owner), input)

    private fun clear(player: Player, input: String) {
        val word = input.trim().lowercase()
        val owner = player.uniqueId
        val ids = when (word) {
            in READ_WORDS -> letters(owner).filter { it.readAt != null && !it.hasContent }.map { it.id }
            in ALL_WORDS -> letters(owner).filter { !it.hasContent }.map { it.id }
            else -> {
                val entry = pick(owner, word) ?: return ess.messages.send(player, "mail-not-found", Ph.of().value(input))
                if (!entry.isLetter) return ess.messages.send(player, "mail-delete-reward")
                if (entry.hasContent) return ess.messages.send(player, "mail-delete-has-content")
                listOf(entry.id)
            }
        }
        val removed = store.remove(owner, ids).size
        ess.messages.send(player, "mail-deleted", Ph.of().count(removed))
        if (word in ALL_WORDS && letters(owner).any { it.hasContent }) ess.messages.send(player, "mail-delete-kept-content")
    }

    // --- 보내기 ----------------------------------------------------------------------------------

    private fun send(ctx: CommandContext<CommandSourceStack>, temp: Boolean) {
        val sender = ctx.source.sender
        val input = StringArgumentType.getString(ctx, "받는사람")
        val target = ess.args.offline(sender, input) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(input))
        var text = StringArgumentType.getString(ctx, "내용").trim()
        var expires: Long? = null
        if (temp) {
            val first = text.substringBefore(' ')
            val seconds = Durations.parse(first, -1L)
            if (seconds <= 0L) return ess.messages.send(sender, "mail-duration-invalid", Ph.of().value(first))
            expires = System.currentTimeMillis() + seconds * 1000L
            text = text.substringAfter(' ', "").trim()
        }
        deliver(sender, target, text, emptyList(), expires)
    }

    private fun attach(ctx: CommandContext<CommandSourceStack>, text: String) {
        val player = player(ctx) ?: return
        val input = StringArgumentType.getString(ctx, "받는사람")
        val target = ess.args.offline(player, input) ?: return ess.messages.send(player, "user-not-found", Ph.of().value(input))
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir || hand.amount <= 0) return ess.messages.send(player, "mail-hand-empty")
        val copy = hand.clone()
        // 실제로 넣었을 때만 손에서 뺀다 — 거절·그림자 뮤트(보낸 척)·저장 실패면 손에 그대로.
        if (deliver(player, target, text.trim().ifEmpty { ess.messages.raw("mail-attach-default-text") }, listOf(copy), null) == MailService.Sent.OK) {
            player.inventory.setItemInMainHand(null)
        }
    }

    /** 콘솔·관리자(첨부·우편전체 권한) — 쿨타임·사람마다 한도·편지함 한도를 받지 않는다. */
    private fun exempt(sender: CommandSender): Boolean =
        sender !is Player || sender.hasPermission(MailFeature.ATTACH) || sender.hasPermission(MailFeature.ALL)

    /** 공통 확인 후 보낸다. 결과(확인에서 막혔으면 null). */
    private fun deliver(sender: CommandSender, target: UserRecord, text: String, attach: List<org.bukkit.inventory.ItemStack>, expires: Long?, key: String? = null): MailService.Sent? {
        val s = feature.settings
        if (sender is Player && sender.uniqueId == target.id) {
            ess.messages.send(sender, "mail-self")
            return null
        }
        if (text.isEmpty()) {
            ess.messages.send(sender, "mail-empty-text")
            return null
        }
        if (text.length > s.letterMaxLength) {
            ess.messages.send(sender, "mail-too-long", Ph.of().count(s.letterMaxLength))
            return null
        }
        if (sender is Player && s.denyWhenMuted && ess.moderation.isMuted(sender.uniqueId) && !ess.moderation.isShadowMuted(sender.uniqueId)) {
            ess.messages.send(sender, "mail-muted")
            return null
        }
        val ph = Ph.of().target(target.name)
        val exempt = exempt(sender)
        if (sender is Player && !exempt) {
            val wait = MailRules.cooldownLeft(lastSent[sender.uniqueId], System.currentTimeMillis(), s.sendCooldownSeconds)
            if (wait > 0) {
                ess.messages.send(sender, "mail-cooldown", Ph.of().count(wait.toInt()))
                return null
            }
            if (s.maxUnreadPerSender > 0 && MailRules.unreadFrom(store.of(target.id), sender.uniqueId) >= s.maxUnreadPerSender) {
                ess.messages.send(sender, "mail-target-too-many", ph.count(s.maxUnreadPerSender))
                return null
            }
        }
        val result = service.sendLetter(sender, target.id, text, attach, expires, key, rich = sender.hasPermission(MailFeature.COLOR), force = exempt)
        when (result) {
            MailService.Sent.OK, MailService.Sent.SHADOW -> {
                if (sender is Player) lastSent[sender.uniqueId] = System.currentTimeMillis()
                ess.messages.send(sender, if (attach.isNotEmpty()) "mail-sent-attached" else if (expires != null) "mail-sent-temp" else "mail-sent", ph.time(expires?.let(service::date) ?: ""))
            }
            MailService.Sent.FULL -> ess.messages.send(sender, "mail-target-full", ph)
            MailService.Sent.IGNORED -> ess.messages.send(sender, "mail-target-ignores", ph)
            MailService.Sent.FAILED -> ess.messages.send(sender, "mail-failed", ph)
        }
        return result
    }

    /** 사용법 — 관리 명령은 권한이 있을 때만 덧붙인다. */
    private fun usage(sender: CommandSender) {
        ess.messages.send(sender, "mail-usage")
        if (sender.hasPermission(MailFeature.ATTACH) || sender.hasPermission(MailFeature.OTHERS) || sender.hasPermission(MailFeature.ALL)) {
            ess.messages.send(sender, "mail-usage-admin")
        }
    }

    // --- 관리 ------------------------------------------------------------------------------------

    private fun view(ctx: CommandContext<CommandSourceStack>) {
        val player = player(ctx) ?: return
        val input = StringArgumentType.getString(ctx, "플레이어")
        val target = ess.args.offline(player, input) ?: return ess.messages.send(player, "user-not-found", Ph.of().value(input))
        MailMenu(ess, player, feature, target.id, target.name).show()
    }

    /**
     * `/우편 비우기 <플레이어> 편지` — 받을 것이 없는 편지만 · `… 편지 확인` — 첨부가 남은 편지까지 · `… 전체 확인` — 보상까지(되돌릴 수 없어 확인 낱말을
     * 받는다). 받을 것이 남은 우편을 지우면 하나하나 로그에 남긴다(우편함 화면의 관리자 지우기와 같은 규칙).
     */
    private fun purge(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender
        val input = StringArgumentType.getString(ctx, "플레이어")
        val target = ess.args.offline(sender, input) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(input))
        val words = Args.split(StringArgumentType.getString(ctx, "범위")).map { it.lowercase() }
        val ph = Ph.of().player(target.name)
        val confirmed = words.getOrNull(1) in CONFIRM_WORDS
        val all = store.of(target.id)
        val chosen = when (words.firstOrNull()) {
            in LETTER_WORDS -> all.filter { it.isLetter && (confirmed || !it.hasContent) }
            in ALL_WORDS -> if (confirmed) all else return ess.messages.send(sender, "mail-purge-confirm", ph)
            else -> return ess.messages.send(sender, "mail-purge-usage", ph)
        }
        val removed = store.remove(target.id, chosen.map { it.id })
        for (entry in removed.filter { it.hasContent }) {
            ess.logger.info("${sender.name} 이(가) ${target.name} 의 받을 것이 남은 우편을 지웠습니다: ${service.describe(entry)}")
        }
        ess.logger.info("${sender.name} 이(가) ${target.name} 의 우편 ${removed.size}통을 비웠습니다(${words.joinToString(" ")})")
        ess.messages.send(sender, "mail-purged", ph.count(removed.size))
        val kept = store.of(target.id).count { it.isLetter && it.hasContent }
        if (!confirmed && kept > 0) ess.messages.send(sender, "mail-purge-kept-content", Ph.of().player(target.name).count(kept))
    }

    // --- 우편전체 --------------------------------------------------------------------------------

    private fun mailAll(sender: CommandSender, text: String) {
        val trimmed = text.trim()
        if (trimmed.lowercase() in RECALL_WORDS) return recall(sender)
        if (trimmed.length > feature.settings.letterMaxLength) return ess.messages.send(sender, "mail-too-long", Ph.of().count(feature.settings.letterMaxLength))
        val now = System.currentTimeMillis()
        val cutoff = now - feature.settings.mailAllDays * MailRules.DAY
        val self = (sender as? Player)?.uniqueId
        val receivers = (ess.users.all().filter { it.lastJoin >= cutoff }.map { it.id } + Bukkit.getOnlinePlayers().map { it.uniqueId })
            .toSet() - setOfNotNull(self)
        val key = BATCH_PREFIX + now
        feature.lastBatch = key
        val rich = sender.hasPermission(MailFeature.COLOR)
        var sent = 0
        var full = 0
        var ignored = 0
        var failed = 0
        for (id in receivers) {
            when (service.sendLetter(sender, id, trimmed, emptyList(), null, key, rich, force = true)) {
                MailService.Sent.OK, MailService.Sent.SHADOW -> sent++
                MailService.Sent.FULL -> full++
                MailService.Sent.IGNORED -> ignored++
                MailService.Sent.FAILED -> failed++
            }
        }
        ess.messages.send(
            sender, "mail-all-sent",
            Ph.of().count(sent).amount(full.toString()).value(feature.settings.mailAllDays.toString())
                .raw("{무시}", ignored.toString()).raw("{실패}", failed.toString()),
        )
    }

    /**
     * **마지막으로 보낸** /우편전체 묶음을 모두에게서 거둔다. 마지막 묶음은 남은 편지만이 아니라 묘비(받는 사람이 지웠거나 만료된 것 — `ess_mail_keys`)와
     * 이번 실행에 보낸 것까지 보고 정한다 — 그 묶음이 이미 하나도 남지 않았으면 그 전 묶음을 거두지 않고 "거둘 것 없음".
     */
    private fun recall(sender: CommandSender) {
        val origin = MailFeature.LETTER_ORIGIN
        val keys = store.all().filter { it.parcel.origin == origin }.mapNotNull { it.parcel.key } + store.spentKeysOf(origin) + listOfNotNull(feature.lastBatch)
        val latest = MailRules.latestBatch(keys, BATCH_PREFIX) ?: return ess.messages.send(sender, "mail-all-none")
        val time = MailRules.batchTime(latest, BATCH_PREFIX)?.let(service::date) ?: "?"
        val batch = store.all().filter { it.parcel.origin == origin && it.parcel.key == latest }.toList()
        if (batch.isEmpty()) return ess.messages.send(sender, "mail-all-recall-empty", Ph.of().time(time))
        val preview = batch.first().parcel.text.orEmpty().replace('\n', ' ').let { if (it.length > 30) it.take(30) + "…" else it }
        var removed = 0
        for ((owner, entries) in batch.groupBy { it.owner }) removed += store.remove(owner, entries.map { it.id }).size
        ess.logger.info("${sender.name} 이(가) 우편전체($time · $latest) ${removed}통을 거뒀습니다")
        ess.messages.send(sender, "mail-all-recalled", Ph.of().count(removed).time(time).value(safe(preview)))
    }

    // --- 제안 · 공용 --------------------------------------------------------------------------------

    private val letterHandles = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val player = ctx.source.sender as? Player
        if (player != null) {
            val letters = letters(player.uniqueId)
            for (i in letters.indices.take(20)) builder.suggest("${i + 1}")
        }
        builder.buildFuture()
    }

    private val clearHints = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remainingLowerCase
        for (word in listOf("읽은것", "전체")) if (word.startsWith(typed)) builder.suggest(word)
        builder.buildFuture()
    }

    private val purgeHints = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remainingLowerCase
        for (word in listOf("편지", "편지 확인", "전체 확인")) if (word.startsWith(typed)) builder.suggest(word)
        builder.buildFuture()
    }

    /** 편지 미리보기는 사람이 친 글 — 틀의 MiniMessage·PAPI 에 섞이지 않게 태그를 풀고 `%` 를 전각으로. */
    private fun safe(text: String): String = MM.escapeTags(text).replace('%', '％')

    private fun player(ctx: CommandContext<CommandSourceStack>): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    companion object {
        const val BATCH_PREFIX = "mailall:"
        private val MM = MiniMessage.miniMessage()
        val READ_WORDS = setOf("읽은것", "읽은", "read")
        val ALL_WORDS = setOf("전체", "all", "모두")
        val LETTER_WORDS = setOf("편지", "letters", "letter")
        val CONFIRM_WORDS = setOf("확인", "confirm")
        val RECALL_WORDS = setOf("회수", "recall", "remove")
    }
}

package com.inmc.essentials.mail

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Mail
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.reward.Mailbox
import kr.inmc.core.util.Text
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.function.BiPredicate

/**
 * **core `Mail` 의 공급처**(사용자 결정 — 우편함은 이 서버에 하나). 넣기·세기·목록·받기·버리기와 편지 보내기·읽기를 맡는다.
 *
 * 공급처의 의무(core `Mail` KDoc · 계획 3.1): 넣기는 빠르고 돌아오면 잃지 않는다(메모리 + DB 쓰기 줄) · 같은 사람·origin·key 는 한 통 ·
 * 명령의 `{player}` 는 받는 사람 이름, `%…%` 는 받는 사람 기준 PAPI(`ess.rewards.runCommand`) · 돈을 못 줬다는 안내는 여기서.
 */
class MailService(private val ess: Ess, private val feature: MailFeature) : Mail.Provider {

    private val settings: MailSettings get() = feature.settings
    private val store: MailStore get() = feature.store

    /** 보상 한도 경고 — (사람, origin, 열쇠 또는 출처)마다 하루에 한 번. */
    private val limitWarnings = DailyWarnings()

    /** 다시 읽는 중인 사람(공용 DB — [ensureSynced]). */
    private val resyncing: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 검증기 — "받는 사람이 보낸 사람을 무시하나" 를 메모리에서만 바꿔 끼운다(null 이면 chat 기능에 묻는다). */
    @Volatile
    internal var ignoreOverride: ((UUID, UUID) -> Boolean)? = null

    // --- core Mail.Provider ---------------------------------------------------------------------

    override fun deposit(playerId: UUID, parcel: Mail.Parcel): Boolean = depositEntry(playerId, parcel, rich = false).first.let {
        it is MailRules.Admit.Accept || it is MailRules.Admit.Duplicate
    }

    /** 넣고 알린다. 보상 한도로 거절하면 콘솔에 경고(사용자 결정 — 조용히 지우지 않는다). [force] = 편지 한도를 넘어서도, [persist] = false 면 메모리만(검증기). */
    fun depositEntry(
        playerId: UUID, parcel: Mail.Parcel, rich: Boolean, force: Boolean = false, persist: Boolean = true, realm: String? = null,
    ): Pair<MailRules.Admit, MailEntry?> {
        val result = store.deposit(playerId, parcel, settings, rich, persist, force, realm)
        when (val admit = result.first) {
            MailRules.Admit.RewardLimit -> if (limitWarnings.shouldWarn("$playerId/${parcel.origin}/${parcel.key ?: parcel.source}", System.currentTimeMillis())) {
                ess.logger.warning(
                    "우편을 받지 않았습니다 — ${nameOf(playerId)} 의 보상 우편이 한도(${settings.rewardLimit})에 찼습니다: ${parcel.origin} · ${parcel.source} " +
                        "(넣은 쪽이 제 길로 처리합니다 — 바닥 또는 제 우편함. 한도는 settings/mail.yml rewards.max-per-player · 같은 것은 하루에 한 번만 알립니다)",
                )
            }
            MailRules.Admit.LetterLimit, MailRules.Admit.Duplicate -> Unit
            is MailRules.Admit.Accept -> result.second?.let { notifyArrival(it) }
        }
        return result
    }

    override fun count(playerId: UUID, origin: String?): Int = store.of(playerId).count { origin == null || it.parcel.origin == origin }

    override fun entries(playerId: UUID, origin: String?): List<Pair<String, Mail.Parcel>> =
        store.of(playerId).filter { origin == null || it.parcel.origin == origin }.map { it.id to it.parcel }

    /** 받을 것이 있는 우편을 오래된 것부터 받는다. 메인 스레드에서만(가방을 만진다). 공용 DB 와 맞추지 못한 사람은 거절([ensureSynced]). */
    override fun claim(player: Player, origin: String?): Mailbox.ClaimResult = claimAll(player, origin, tell = true)

    /** [claim] — [tell] = false 면 "맞추지 못함" 알림·다시 읽기를 하지 않는다(검증기). */
    internal fun claimAll(player: Player, origin: String?, tell: Boolean): Mailbox.ClaimResult {
        if (!Bukkit.isPrimaryThread()) {
            ess.logger.warning("우편 받기가 메인이 아닌 스레드에서 불렸습니다(${player.name}) — 받지 않습니다")
            return Mailbox.ClaimResult(0, count(player.uniqueId, origin), false)
        }
        if (!(if (tell) ensureSynced(player) else store.isSynced(player.uniqueId))) {
            return Mailbox.ClaimResult(0, store.of(player.uniqueId).count { (origin == null || it.parcel.origin == origin) && it.hasContent }, false)
        }
        var claimed = 0
        var left = 0
        var partial = false
        var held = false
        for (entry in store.of(player.uniqueId)) {
            if (origin != null && entry.parcel.origin != origin) continue
            if (!entry.hasContent) continue
            val outcome = claimOne(player, entry, tellHeld = false)
            if (outcome.done) claimed++ else left++
            partial = partial || outcome.partial
            held = held || outcome.moneyHeld
        }
        if (held) ess.messages.send(player, "mailbox-money-held")
        return Mailbox.ClaimResult(claimed, left, partial)
    }

    /**
     * 공용 DB 와 맞췄나 — 아니면 알리고, 비동기로 다시 읽기를 걸고 false(받기 거절 — 낡은 사본으로 주면 다른 서버에서 받은 것을 또 준다).
     * 로컬 DB 에서는 늘 true.
     */
    fun ensureSynced(player: Player): Boolean {
        val id = player.uniqueId
        if (store.isSynced(id)) return true
        ess.messages.send(player, "mail-not-synced")
        if (resyncing.add(id)) {
            Bukkit.getAsyncScheduler().runNow(ess.plugin) { _ ->
                try {
                    if (store.reload(id)) ess.logger.info("공용 DB 에서 ${player.name} 의 우편을 다시 읽었습니다 — 받기를 엽니다")
                } catch (t: Throwable) {
                    store.markUnsynced(id)
                    ess.logger.warning("공용 DB 에서 ${player.name} 의 우편을 다시 읽지 못했습니다: ${t.message}")
                } finally {
                    resyncing -= id
                }
            }
        }
        return false
    }

    override fun discard(playerId: UUID, origin: String?): Int =
        store.remove(playerId, store.of(playerId).filter { origin == null || it.parcel.origin == origin }.map { it.id }).size

    // --- 받기 ---------------------------------------------------------------------------------

    /**
     * 한 통 받기([MailRules.claim]). 다 받은 보상은 지우고, 편지는 글만 남긴다. 남은 것이 있으면 그것만 남긴다. 명령은 다 받았을 때만
     * (`{player}`·PAPI — `ess.rewards.runCommand`). 되살리지 못한 아이템은 로그를 남기고 버린다(core `Mailbox` 와 같은 규칙 — 남기면 영영 못 받는다).
     */
    fun claimOne(player: Player, entry: MailEntry, tellHeld: Boolean = true): MailRules.Claim {
        val outcome = MailRules.claim(
            entry.parcel,
            give = { bytes ->
                val stack = runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull()
                if (stack == null) null
                else player.inventory.addItem(stack).values.mapNotNull { runCatching { it.serializeAsBytes() }.getOrNull() }
            },
            pay = { amount, currency ->
                ess.economy.isEnabled && (if (currency == null) ess.economy.deposit(player, amount) else ess.economy.deposit(player, amount, currency))
            },
        )
        if (outcome.broken > 0) {
            ess.logger.warning("우편 아이템 ${outcome.broken}개를 되살리지 못해 버렸습니다: ${player.name} (${entry.parcel.origin} · ${entry.parcel.source})")
            ess.messages.send(player, "mail-item-broken", Ph.of().count(outcome.broken))
        }
        if (outcome.moneyHeld) ess.logger.warning("우편 금액을 주지 못해 보관합니다: ${player.name} (${entry.parcel.source})")
        if (outcome.done && !entry.isLetter) store.remove(entry.owner, listOf(entry.id))
        else if (outcome.done) {
            // 첨부를 다 받은 편지 — 만료 시계가 지금부터 다시 돈다(받을 것이 남은 동안은 만료되지 않았다).
            val rest = outcome.rest.copy(expiresAt = MailRules.expiryAfterClaim(entry.parcel.expiresAt, System.currentTimeMillis(), settings))
            store.update(entry.copy(parcel = rest))
        } else if (outcome.rest != entry.parcel) store.update(entry.copy(parcel = outcome.rest))
        for (command in outcome.commands) ess.rewards.runCommand(command, player)
        if (tellHeld && outcome.moneyHeld) ess.messages.send(player, "mailbox-money-held")
        return outcome
    }

    // --- 편지 ---------------------------------------------------------------------------------

    /** [SHADOW] = 그림자 뮤트라 보낸 척만 했다(넣지 않음 — 첨부는 손에 그대로 둬야 한다). */
    enum class Sent { OK, SHADOW, FULL, IGNORED, FAILED }

    /**
     * 편지 한 통. [sender] 가 플레이어면 무시·그림자 뮤트를 본다(그림자 뮤트는 [Sent.SHADOW] — 넣지 않는다). [attach] 는 붙일 아이템(관리자) —
     * 하나라도 저장 모양으로 바꾸지 못하면 넣지 않고 [Sent.FAILED](아이템이 손에서 사라지지 않게). [expiresAt] 은 임시 편지의 만료 시각.
     * [key] 는 /우편전체 의 묶음 표시. [force] = 편지함이 꽉 차도 넣는다(콘솔·관리자). [persist] = false 면 메모리만(검증기).
     */
    fun sendLetter(
        sender: CommandSender, to: UUID, text: String, attach: List<ItemStack> = emptyList(), expiresAt: Long? = null, key: String? = null,
        rich: Boolean = false, force: Boolean = false, persist: Boolean = true,
    ): Sent {
        val player = sender as? Player
        if (player != null) {
            if (ignores(to, player.uniqueId)) return Sent.IGNORED
            if (ess.moderation.isShadowMuted(player.uniqueId)) return Sent.SHADOW
        }
        val stacks = attach.filter { !it.type.isAir && it.amount > 0 }
        val items = stacks.mapNotNull { stack ->
            runCatching { stack.serializeAsBytes() }.onFailure { ess.logger.warning("편지에 붙일 아이템을 저장 모양으로 바꾸지 못했습니다(${stack.type}): ${it.message}") }.getOrNull()
        }
        if (items.size != stacks.size) return Sent.FAILED
        val name = player?.name ?: settings.consoleSender
        val parcel = Mail.Parcel(
            kind = Mail.Kind.LETTER, origin = MailFeature.LETTER_ORIGIN,
            source = ess.messages.raw("mail-source-letter").replace("{플레이어}", name).replace("{player}", name),
            senderId = player?.uniqueId, senderName = name, text = text, items = items,
            at = System.currentTimeMillis(), expiresAt = expiresAt, key = key,
        )
        // 아이템을 붙인 편지는 **보낸 사람의 모드** 우편함으로 — 받는 사람이 다른 모드에 있어도 물건이 모드를 넘지 않게(core `Realms`, 2026-10-15).
        // 글만 있는 편지는 받는 사람의 지금 모드로(넘을 물건이 없다).
        val realm = if (items.isNotEmpty()) player?.let { kr.inmc.core.integration.Realms.of(it) } else null
        val (admit, entry) = depositEntry(to, parcel, rich, force, persist, realm)
        return when (admit) {
            is MailRules.Admit.Accept -> if (entry != null && entry.parcel.items.size == items.size) Sent.OK else Sent.FAILED
            MailRules.Admit.Duplicate -> if (items.isEmpty()) Sent.OK else Sent.FAILED
            MailRules.Admit.LetterLimit -> Sent.FULL
            MailRules.Admit.RewardLimit -> Sent.FAILED
        }
    }

    /** 받는 사람이 보낸 사람을 무시하나 — chat 기능이 답한다(꺼져 있으면 아무도 무시하지 않는 것). */
    private fun ignores(receiver: UUID, sender: UUID): Boolean {
        ignoreOverride?.let { return it(receiver, sender) }
        @Suppress("UNCHECKED_CAST")
        val chat = ess.feature("chat") as? BiPredicate<UUID, UUID> ?: return false
        return runCatching { chat.test(receiver, sender) }.getOrDefault(false)
    }

    /** 편지를 책으로 연다. 주인이 열면 읽음 표시. */
    fun openLetter(viewer: Player, entry: MailEntry) {
        val p = entry.parcel
        // 보낸 사람 이름은 CMI 에서 가져온 글일 수도 있다 — 틀에 넣기 전에 태그를 풀고 % 를 전각으로.
        val sender = MM.escapeTags(p.senderName ?: "?").replace('%', '％')
        val header = ess.messages.raw("mail-book-header")
            .replace("{보낸사람}", sender).replace("{날짜}", date(p.at))
        val pages = ArrayList<Component>()
        val bodyPages = MailRules.pages(p.text.orEmpty())
        for ((index, raw) in bodyPages.withIndex()) {
            // 색이 있는 편지도 색·꾸밈만(클릭·호버는 글자 그대로 — MailText) · PAPI 는 풀지 않는다.
            val body: Component = MailText.body(raw, entry.rich)
            pages += if (index == 0) Text.render(header).append(Component.newline()).append(body) else body
        }
        if (entry.hasContent) pages += Text.render(ess.messages.raw("mail-book-attachment"))
        viewer.openBook(Book.book(Component.text("편지"), Component.text(p.senderName ?: "?"), pages))
        if (viewer.uniqueId == entry.owner && entry.readAt == null) store.update(entry.copy(readAt = System.currentTimeMillis()))
    }

    // --- 알림 ---------------------------------------------------------------------------------

    private fun notifyArrival(entry: MailEntry) {
        val player = Bukkit.getPlayer(entry.owner) ?: return
        val run = Runnable {
            if (!player.isOnline) return@Runnable
            if (entry.isLetter) {
                ess.messages.send(player, "mail-letter-arrived", Ph.of().player(entry.parcel.senderName ?: "?"))
                playSound(player)
            } else if (settings.rewardActionbar) {
                player.sendActionBar(ess.messages.component("mail-reward-arrived", Ph.of().subject(entry.parcel.source), player))
            }
        }
        if (Bukkit.isPrimaryThread()) run.run() else player.scheduler.run(ess.plugin, { _ -> run.run() }, null)
    }

    /** 접속 알림 — 안 읽은 편지·받을 것이 있는 우편 수. */
    fun notifyJoin(player: Player) {
        val (unread, claimable) = MailRules.joinCounts(store.of(player.uniqueId))
        if (unread == 0 && claimable == 0) return
        ess.messages.send(player, "mail-join", Ph.of().count(unread).raw("{받을것}", claimable.toString()))
        playSound(player)
    }

    fun playSound(player: Player) {
        val text = settings.sound.trim()
        if (text.isEmpty()) return
        val parts = text.split(':')
        val numbers = parts.reversed().takeWhile { it.toFloatOrNull() != null }.reversed().take(2)
        val key = parts.dropLast(numbers.size).joinToString(":").ifEmpty { return }
        runCatching { player.playSound(player.location, key.lowercase(), numbers.getOrNull(0)?.toFloat() ?: 1f, numbers.getOrNull(1)?.toFloat() ?: 1f) }
    }

    // --- 공용 ---------------------------------------------------------------------------------

    fun nameOf(id: UUID): String = Bukkit.getPlayer(id)?.name ?: ess.users.get(id)?.name ?: id.toString()

    fun displayName(id: UUID): String = TitleForgeNames.displayName(id, nameOf(id))

    fun date(at: Long): String = DATE.format(Instant.ofEpochMilli(at))

    /** 로그에 남길 우편 한 통의 모양 — 지울 때 무엇이 사라졌는지 되살릴 근거(보낸 사람·때·아이템·돈·명령). */
    fun describe(entry: MailEntry): String {
        val p = entry.parcel
        val items = p.items.joinToString(", ") { bytes ->
            runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull()?.let { "${it.type} x${it.amount}" } ?: "되살릴 수 없는 아이템"
        }
        val money = (listOfNotNull(p.money.takeIf { it > 0.0 }?.let { "$it" }) + p.moneyBy.filterValues { it > 0.0 }.map { (c, a) -> "$c=$a" }).joinToString(", ")
        return "${p.senderName ?: p.source} · ${date(p.at)} · 아이템 ${p.items.size}개[$items] · 돈 [${money}] · 명령 ${p.commands.size}개${p.commands}"
    }

    private companion object {
        val MM: net.kyori.adventure.text.minimessage.MiniMessage = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}

package com.inmc.essentials.kit

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.integration.Vanish
import kr.inmc.core.reward.RewardService
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.random.Random

/**
 * 킷 주기(연구 give-kits 【2】). 순서: 검사(권한·조건·쿨타임·횟수·비용) → 비용 받기 → **사용 기록을 DB 에 쓰고**(쓰기 스레드) → 쓴 뒤 메인에서 준다.
 * 쓰기에 실패하면 비용을 돌려주고 주지 않는다 — 크래시 사이에 기록 없이 받는 일(1회용을 다시 받기)을 막는다(연구 위험 "먼저 적고 준다").
 * 가방이 차면 core `RewardService.give` 가 우편으로(규칙 9). 접속하지 않은 사람(관리자 지급·첫 접속 뒤 나감)은 우편으로.
 */
class KitService(private val ess: Ess, private val feature: KitsFeature) {

    enum class Denied { PERMISSION, SUPERSEDED, CONDITION, COOLDOWN, USED_UP, MONEY, EXP, EMPTY, BUSY }

    data class Verdict(val denied: Denied?, val left: Long = 0L, val missing: String = "")

    private val store: KitStore get() = feature.store

    /** 받는 중(쓰기 스레드를 기다리는) — 같은 킷을 두 번 누르지 못하게. */
    private val inFlight = HashSet<String>()

    fun canAccess(player: Player, kit: Kit): Boolean =
        player.hasPermission(KitsFeature.USE) && (!kit.requirePermission || player.hasPermission(kit.permission) || player.hasPermission(KitsFeature.ALL_KITS))

    private val randomGroups: Boolean get() = feature.settings.groupMode == KitsSettings.GroupMode.RANDOM

    /** CMI 무게 규칙(priority 모드) — 같은 그룹에서 더 높은 무게의 킷을 받을 수 있어 이 킷은 못 받는다. */
    fun superseded(player: Player, kit: Kit): Boolean =
        !randomGroups && KitRules.superseded(kit, store.all()) { canAccess(player, it) }

    /** 목록 화면·채팅 목록에 보이나 — 밀린 킷은 show-despite-weight 일 때만. */
    fun listed(player: Player, kit: Kit): Boolean = !superseded(player, kit) || kit.showDespiteWeight

    fun canPreview(player: Player, kit: Kit): Boolean =
        kit.preview && listed(player, kit) &&
            (canAccess(player, kit) || player.hasPermission(kit.previewPermission) || player.hasPermission(KitsFeature.PREVIEW_ALL))

    /** [id] 의 킷을 주는 중인가(DB 쓰기를 기다리는) — 나가도 그동안은 기록을 내리지 않는다. */
    fun busy(id: UUID): Boolean = inFlight.any { it.startsWith("$id:") }

    /** 받는 길이 끝난 뒤 — 그새 나갔으면 기록을 내린다. */
    private fun settled(id: UUID) {
        if (Bukkit.getPlayer(id) == null && !busy(id)) store.unload(id)
    }

    fun verdict(player: Player, kit: Kit, now: Long = System.currentTimeMillis()): Verdict {
        if (!canAccess(player, kit)) return Verdict(Denied.PERMISSION)
        // 기록을 아직 못 읽었다(접속 때 DB 오류) — 1회용·쿨타임을 모르니 주지 않는다(곧 다시 읽는다).
        if (!store.isLoaded(player.uniqueId)) return Verdict(Denied.BUSY)
        if (superseded(player, kit)) return Verdict(Denied.SUPERSEDED)
        if (!player.hasPermission(KitsFeature.BYPASS_CONDITIONS)) kit.conditions.firstOrNull { !player.hasPermission(it) }?.let { return Verdict(Denied.CONDITION, missing = it) }
        val usage = store.usage(player.uniqueId, kit.id)
        if (!player.hasPermission(KitsFeature.BYPASS_USES) && KitRules.usedUp(kit, usage)) return Verdict(Denied.USED_UP)
        if (!player.hasPermission(KitsFeature.BYPASS_COOLDOWN)) {
            val left = KitRules.cooldownLeft(kit, usage, now)
            if (left > 0) return Verdict(Denied.COOLDOWN, left)
        }
        if (kit.money > 0.0 && !player.hasPermission(KitsFeature.BYPASS_MONEY) && ess.economy.isEnabled &&
            !ess.economy.has(player, kit.money, kit.currency.ifEmpty { null })) return Verdict(Denied.MONEY)
        if (kit.exp > 0 && !player.hasPermission(KitsFeature.BYPASS_EXP) && player.calculateTotalExperiencePoints() < kit.exp) return Verdict(Denied.EXP)
        if (kit.isEmpty()) return Verdict(Denied.EMPTY)
        if ("${player.uniqueId}:${kit.id}" in inFlight) return Verdict(Denied.BUSY)
        return Verdict(null)
    }

    /** 까닭을 알린다. */
    fun tellDenied(to: CommandSender, kit: Kit, v: Verdict) {
        val ph = Ph.of().subject(plainName(kit)).value(v.missing).time(Durations.formatShort(v.left))
            .amount(ess.economy.format(kit.money, kit.currency.ifEmpty { null })).count(kit.exp)
        val key = DENIED_PREFIX + v.denied!!.name.lowercase().replace('_', '-') // kits-denied-permission · -condition · -cooldown · -used-up · -money · -exp · -empty · -busy
        ess.messages.send(to, key, ph)
    }

    /**
     * [player] 가 [kit] 을 받는다. [free] = 검사·비용 없이(관리자 지급), [record] = 사용 기록을 남긴다(쿨타임·횟수가 걸린다).
     * true 면 주는 길에 들어섰다(실제로 주는 것은 DB 쓰기 뒤). [persist] 는 검증기용 — 기록 쓰기를 갈아 끼우고 끝나면 그 콜백을 부른다.
     */
    fun claim(
        player: Player, kit: Kit, by: CommandSender = player, free: Boolean = false, record: Boolean = true,
        persist: ((List<Pair<String, KitUsage>>, (Boolean) -> Unit) -> Unit)? = null,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!free) {
            val v = verdict(player, kit, now)
            if (v.denied != null) { tellDenied(by, kit, v); return false }
        } else if (kit.isEmpty()) {
            tellDenied(by, kit, Verdict(Denied.EMPTY)); return false
        }
        val flight = "${player.uniqueId}:${kit.id}"
        if (!inFlight.add(flight)) { tellDenied(by, kit, Verdict(Denied.BUSY)); return false }
        // 비용
        val currency = kit.currency.ifEmpty { null }
        val paidMoney = !free && kit.money > 0.0 && !player.hasPermission(KitsFeature.BYPASS_MONEY) && ess.economy.isEnabled
        if (paidMoney && !ess.economy.withdraw(player, kit.money, currency)) {
            inFlight.remove(flight)
            tellDenied(by, kit, Verdict(Denied.MONEY)); return false
        }
        val paidExp = if (!free && kit.exp > 0 && !player.hasPermission(KitsFeature.BYPASS_EXP)) kit.exp else 0
        if (paidExp > 0) player.giveExp(-paidExp)
        val id = player.uniqueId
        if (!record) {
            inFlight.remove(flight)
            deliver(id, kit, by)
            return true
        }
        // random 모드의 그룹 킷은 그룹 전체에 같은 기록 — 하나를 받은 뒤 /킷 <그룹> 을 되풀이해 나머지까지 받지 못하게.
        val records = KitRules.recordKits(kit, store.all(), randomGroups).map { k ->
            val previous = store.usage(id, k.id)
            Triple(k.id, previous, KitUsage(now, (previous?.uses ?: 0) + 1))
        }
        records.forEach { (kitId, _, next) -> store.markMemory(id, kitId, next) }
        val finish: (Boolean) -> Unit = finish@{ ok ->
            inFlight.remove(flight)
            settled(id)
            if (ok) return@finish deliver(id, kit, by)
            // 되돌리기 — 기록·비용. 나가 버린 사람의 돈은 오프라인 계좌로, 경험치는 돌려줄 길이 없어 로그로.
            records.forEach { (kitId, previous, _) -> store.restoreMemory(id, kitId, previous) }
            if (paidMoney) ess.economy.deposit(Bukkit.getPlayer(id) ?: Bukkit.getOfflinePlayer(id), kit.money, currency)
            if (paidExp > 0) {
                val online = Bukkit.getPlayer(id)
                if (online != null) online.giveExp(paidExp)
                else ess.logger.severe("킷 ${kit.id}: $id 가 나가서 경험치 비용 ${paidExp}점을 돌려주지 못했습니다 — 직접 주세요")
            }
            ess.messages.send(by, "kits-failed", Ph.of().subject(plainName(kit)))
        }
        // 검증기는 쓰기를 갈아 끼워 순서(쓰기 → 주기)만 본다 — DB 에 쓰지 않는다(규칙 13).
        val write = persist
        if (write != null) return true.also { write(records.map { it.first to it.third }, finish) }
        ess.db.run("킷 사용 기록") {
            val ok = runCatching { records.forEach { (kitId, _, next) -> store.writeUsage(id, kitId, next) } }
                .onFailure { ess.logger.severe("킷 사용 기록을 쓰지 못해 주지 않습니다(${kit.id} · $id): $it") }.isSuccess
            Bukkit.getScheduler().runTask(ess.plugin, Runnable { finish(ok) })
        }
        return true
    }

    /**
     * `/킷 <그룹>`(CMI CommandName·Group). priority(기본 — CMI): 받을 수 있는 것 중 무게가 가장 높은 하나 — 쿨타임이면 그 까닭을 알린다
     * (낮은 무게로 넘어가지 않는다). random: 지금 받을 수 있는 것 중 무게로 하나를 뽑고 그룹 전체에 기록을 남긴다.
     */
    fun claimGroup(player: Player, group: String): Boolean {
        val members = store.all().filter { it.group.equals(group, ignoreCase = true) }
        if (members.isEmpty()) return false
        val chosen = pickGroup(player, members)
        if (chosen == null) {
            val first = members.firstOrNull { canAccess(player, it) } ?: members.first()
            tellDenied(player, first, verdict(player, first))
            return true
        }
        val v = verdict(player, chosen)
        if (v.denied != null) { tellDenied(player, chosen, v); return true }
        claim(player, chosen)
        return true
    }

    /** 그룹에서 줄 킷(검증기도 부른다). priority 는 받을 수 있는 것 중 최고 무게(쿨타임이어도 그것), random 은 지금 받을 수 있는 것 중 뽑기. */
    fun pickGroup(player: Player, members: List<Kit>, roll: Double = Random.nextDouble()): Kit? =
        if (randomGroups) KitRules.weighted(members.filter { verdict(player, it).denied == null }, { it.weight }, roll)
        else KitRules.topOfGroup(members) { canAccess(player, it) }

    /** 굴려서 준다. 접속 중이 아니면 우편(core 우편함 → Mail). */
    private fun deliver(id: UUID, kit: Kit, by: CommandSender) {
        val player = Bukkit.getPlayer(id)
        val payout = roll(kit, player)
        val source = "킷 " + plainName(kit)
        if (player == null) {
            ess.rewards.mail(id, payout, source)
            ess.messages.send(by, "kits-mailed", Ph.of().subject(plainName(kit)))
            return
        }
        val result = ess.rewards.give(player, payout, plainName(kit), source)
        ess.messages.send(player, "kits-received", Ph.of().subject(plainName(kit)).item(result.labels.joinToString(", ").ifEmpty { "-" }))
        if (by != player) ess.messages.send(by, "kits-given", Ph.of().subject(plainName(kit)).player(player.name))
        if (result.unresolved > 0) ess.messages.send(by, "kits-unresolved", Ph.of().count(result.unresolved))
    }

    /** 묶음마다 굴려 합친다. 이름·설명·명령의 변수({USERNAME} {DISPLAYNAME} {WORLDNAME} {KITNAME} · 한글 {플레이어} {월드} {킷})를 채운다. */
    fun roll(kit: Kit, player: Player?): RewardService.Payout {
        val total = RewardService.Payout()
        for (pool in kit.pools) {
            val p = ess.rewards.resolve(pool)
            total.stacks += p.stacks
            total.money += p.money
            for ((k, v) in p.moneyBy) total.moneyBy.merge(k, v, Double::plus)
            total.commands += p.commands
            total.announced += p.announced
            total.labels += p.labels
            total.unresolved += p.unresolved
        }
        if (player != null) {
            val vars = variables(kit, player)
            total.stacks.forEach { fill(it, vars) }
            val commands = total.commands.map { c -> vars.entries.fold(c) { acc, (k, v) -> acc.replace(k, v) } }
            total.commands.clear()
            total.commands += commands
        }
        return total
    }

    private fun variables(kit: Kit, player: Player): Map<String, String> {
        val display = TitleForgeNames.displayName(player.uniqueId, player.name)
        return linkedMapOf(
            "{USERNAME}" to player.name, "{DISPLAYNAME}" to display, "{WORLDNAME}" to player.world.name, "{KITNAME}" to plainName(kit),
            // 투명인 사람은 뽑지 않는다(규칙 8 — 명령이 방송이면 이름이 샌다).
            "{RANDOMPLAYER}" to (Bukkit.getOnlinePlayers().filter { !Vanish.isVanished(it) }.randomOrNull()?.name ?: player.name),
            "{월드}" to player.world.name, "{킷}" to plainName(kit),
        )
    }

    private fun fill(stack: ItemStack, vars: Map<String, String>) {
        if (!stack.hasItemMeta()) return
        stack.editMeta { meta ->
            fun replace(c: Component): Component = vars.entries.fold(c) { acc, (k, v) ->
                acc.replaceText(TextReplacementConfig.builder().matchLiteral(k).replacement(v).build())
            }
            meta.displayName()?.let { meta.displayName(replace(it)) }
            meta.lore()?.let { lore -> meta.lore(lore.map { replace(it) }) }
        }
    }

    // --- 첫 접속 ------------------------------------------------------------------------------------

    /** 첫 접속 킷 — 사람마다 한 번(표 `*first-join`). 표와 킷 기록을 먼저 쓰고 준다. */
    fun giveFirstJoin(player: Player) {
        val id = player.uniqueId
        if (!store.isLoaded(id)) {
            // 기록을 못 읽었다 — 이미 받았는지 모르니 주지 않는다(두 번 주기보다 낫다). 로그로 남긴다.
            return ess.logger.warning("첫 접속 킷: ${player.name} 의 킷 기록을 아직 읽지 못해 주지 않았습니다 — 필요하면 /킷 <이름> ${player.name} 로 주세요")
        }
        if (store.usage(id, KitStore.FIRST_JOIN_MARK) != null) return
        val kits = store.all().filter { it.firstJoin && !it.isEmpty() }
        if (kits.isEmpty()) return
        val now = System.currentTimeMillis()
        val mark = KitUsage(now, 1)
        store.markMemory(id, KitStore.FIRST_JOIN_MARK, mark)
        // (킷, 이전 기록, 새 기록) — 실패하면 이전 기록으로 되돌린다(주지 않은 1회용 킷이 '이미 받음' 으로 남지 않게).
        val records = kits.map { kit -> store.usage(id, kit.id).let { previous -> Triple(kit, previous, KitUsage(now, (previous?.uses ?: 0) + 1)) } }
        records.forEach { (kit, _, u) -> store.markMemory(id, kit.id, u) }
        ess.db.run("첫 접속 킷") {
            val ok = runCatching {
                store.writeUsage(id, KitStore.FIRST_JOIN_MARK, mark)
                records.forEach { (kit, _, u) -> store.writeUsage(id, kit.id, u) }
            }.onFailure { ess.logger.severe("첫 접속 킷 기록을 쓰지 못해 주지 않습니다($id): $it") }.isSuccess
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                settled(id)
                if (!ok) {
                    store.restoreMemory(id, KitStore.FIRST_JOIN_MARK, null)
                    records.forEach { (kit, previous, _) -> store.restoreMemory(id, kit.id, previous) }
                    return@Runnable
                }
                val console = Bukkit.getConsoleSender()
                for ((kit, _, _) in records) deliver(id, kit, Bukkit.getPlayer(id) ?: console)
            })
        }
    }

    companion object {
        const val DENIED_PREFIX = "kits-denied-"

        fun plainName(kit: Kit): String = Text.plain(kit.name).ifEmpty { kit.id }
    }
}

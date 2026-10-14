package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.integration.Vanish
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 투명의 주인(규칙 6 — core `Vanish` 의 상태를 거는 곳은 여기 하나).
 *
 * - 상태 표식은 core [Vanish.set](메타데이터 `vanished`) — 다른 플러그인(타이틀포지·디스코드·목록 …)은 그것만 본다.
 * - 실제로 안 보이게 하기는 여기서: 볼 권한([Vanish.SEE_PERMISSION])이 없는 접속자마다 `hidePlayer`. 나중에 들어온 사람에게도(접속 때),
 *   권한이 바뀐 사람에게도(주기 [resync]).
 * - 투명으로 들어오는 사람은 접속 사건 **LOWEST** 에서 표식을 건다 — 디스코드가 MONITOR 에서 보고 알리지 않게, 타이틀포지가 HIGH 에서 접속 메시지를
 *   빼게. 나갈 때는 **한 틱 뒤에** 표식을 지운다 — 타이틀포지·디스코드가 퇴장 MONITOR 까지 읽는다.
 * - 사람마다 옵션([VanishOption])은 바꾼 것만 저장한다(`ess_admininfo_vanish`).
 * - **못 보는 사람에게 보일 "마지막으로 본 때"**([seen]): 투명을 켠 순간의 (마지막 접속, 마지막 나감). 접속하며 투명이 되면 지난 접속의 값
 *   (이번 접속을 드러내지 않게), 접속 중에 켜면 그 순간. 투명인 채 나가도·재시작해도 남는다(DB). 보이게 되면 지운다.
 */
internal class VanishService(private val ess: Ess, private val feature: AdminInfoFeature) {

    private class Pref(
        @Volatile var vanished: Boolean,
        val options: ConcurrentHashMap<VanishOption, Boolean> = ConcurrentHashMap(),
        /** 못 보는 사람에게 보일 마지막 나감·마지막 접속. 0 = 그런 적 없음. 둘 다 null 이면 가리지 않는다. */
        @Volatile var seen: Long? = null,
        @Volatile var seenJoin: Long? = null,
    )

    private val prefs = ConcurrentHashMap<UUID, Pref>()

    /** 지금 이 기능이 투명으로 둔 접속자 → 켠 시각. */
    private val active = ConcurrentHashMap<UUID, Long>()

    /** 투명인 채로 나간 사람이 투명을 켠 시각 — `/투명 목록` 의 "몇 분째"(재시작하면 잊는다). */
    private val vanishedAt = ConcurrentHashMap<UUID, Long>()

    /** 접속 직전(비동기 사건)에 읽어 둔 지난 접속 시각 — 접속 LOWEST 에서 사용자 기록이 이번 접속으로 바뀌기 전 값. */
    private val previousJoin = ConcurrentHashMap<UUID, Long>()

    private val bars = HashMap<UUID, BossBar>()
    private val nightVision = HashSet<UUID>()
    private val sleepIgnored = HashSet<UUID>()

    /** 검증기 — 저장·알림·가리기·효과 없이 상태(표식)만. */
    @Volatile
    var dryRun = false

    private val settings get() = feature.settings

    // --- 읽기 -----------------------------------------------------------------------------------

    /** 켤 때(기다린다). */
    fun load() {
        val rows = ess.db.call { AdminStore.readVanish(ess.db.sql) }
        prefs.clear()
        for (r in rows) prefs[r.id] = Pref(r.vanished, ConcurrentHashMap(r.options), r.seen, r.seenJoin)
    }

    /** 공용 DB — 접속 직전(비동기 사건)에 그 사람 줄만 다시. */
    fun preload(id: UUID) {
        val row = ess.db.call { AdminStore.readVanish(ess.db.sql, id) }.firstOrNull() ?: return
        prefs[id] = Pref(row.vanished, ConcurrentHashMap(row.options), row.seen, row.seenJoin)
    }

    /** 접속 직전(비동기 사건) — 지난 접속 시각을 잡아 둔다(메모리만 읽는다). */
    fun rememberJoin(id: UUID) {
        ess.users.get(id)?.lastJoin?.let { previousJoin[id] = it } ?: previousJoin.remove(id)
    }

    fun isActive(player: Player): Boolean = active.containsKey(player.uniqueId)

    fun activeIds(): Set<UUID> = active.keys.toSet()

    /** 저장된 "투명" (접속하지 않은 사람 포함). */
    fun storedVanished(): List<UUID> = prefs.filterValues { it.vanished }.keys.toList()

    /** `/투명 목록` 의 켠 시각. */
    fun since(id: UUID): Long? = active[id] ?: vanishedAt[id]

    /**
     * 못 보는 사람에게 보일 (마지막 접속, 마지막 나감) — 투명을 켠 뒤로 이 사람을 가리고 있으면. 0 은 null 로(그런 적 없음).
     * 가리지 않으면(보이는 사람 · 투명인 적 없음) null — 부르는 쪽은 사용자 기록을 그대로 쓴다.
     */
    fun seen(id: UUID): Pair<Long?, Long?>? {
        val p = prefs[id] ?: return null
        if (p.seen == null && p.seenJoin == null) return null
        return p.seenJoin?.takeIf { it > 0 } to p.seen?.takeIf { it > 0 }
    }

    fun option(id: UUID, option: VanishOption): Boolean = prefs[id]?.options?.get(option) ?: settings.vanishDefault(option)

    fun option(player: Player, option: VanishOption): Boolean = option(player.uniqueId, option)

    fun overridden(id: UUID, option: VanishOption): Boolean = prefs[id]?.options?.containsKey(option) == true

    /** 플레이 시간을 멈출 사람(투명 + stop-playtime). */
    fun stopsPlaytime(player: Player): Boolean = isActive(player) && option(player, VanishOption.STOP_PLAYTIME)

    // --- 켜고 끄기 ---------------------------------------------------------------------------------

    /** 켜거나 끈다. 바뀌었으면 true. [silent] 면 가짜 입퇴장·스태프 알림 없이. */
    fun set(player: Player, on: Boolean, silent: Boolean): Boolean {
        if (on == isActive(player)) return false
        apply(player, on, announce = !silent)
        val pref = prefs.computeIfAbsent(player.uniqueId) { Pref(false) }
        pref.vanished = on
        persist(player.uniqueId)
        return true
    }

    /**
     * 상태만(저장 없이 — [set]·[joinEarly] 가 저장한다). [atJoin] 이면 접속하며 투명이 되는 것(지난 접속의 "마지막으로 본 때" 를 쓴다).
     * [dryRun] 이면 이 기능의 표식만 바꾼다 — 가리기·효과·알림·core 알림(타이틀포지·디스코드 감시자) 없이.
     */
    fun apply(player: Player, on: Boolean, announce: Boolean, atJoin: Boolean = false) {
        val id = player.uniqueId
        if (dryRun) {
            dryMark(player, on)
            return
        }
        if (on) {
            active[id] = System.currentTimeMillis()
            vanishedAt.remove(id)
            mask(id, atJoin)
            Vanish.set(ess.plugin, player, true)
            if (announce && option(id, VanishOption.FAKE_JOIN_LEAVE)) fake(player, "admininfo-vanish-fake-quit")
            hideFromOthers(player)
            applyEffects(player)
            if (announce) staffNotice(player, "admininfo-vanish-staff-on")
        } else {
            // 못 보던 사람에게 "들어왔다" — 보이게 하기 전에 고른다.
            if (announce && option(id, VanishOption.FAKE_JOIN_LEAVE)) fake(player, "admininfo-vanish-fake-join")
            active.remove(id)
            unmask(id)
            Vanish.set(ess.plugin, player, false)
            for (other in Bukkit.getOnlinePlayers()) if (other != player) other.showPlayer(ess.plugin, player)
            clearEffects(player)
            if (announce) staffNotice(player, "admininfo-vanish-staff-off")
        }
    }

    /** 검증기 — 메모리 표식만(core 메타데이터를 직접 — [Vanish.set] 은 감시자에게 알린다). */
    @Suppress("DEPRECATION")
    private fun dryMark(player: Player, on: Boolean) {
        if (on) {
            active[player.uniqueId] = System.currentTimeMillis()
            player.setMetadata(Vanish.META, FixedMetadataValue(ess.plugin, true))
        } else {
            active.remove(player.uniqueId)
            player.removeMetadata(Vanish.META, ess.plugin)
        }
        Vanish.isVanished(player) // 다른 스레드용 거울을 지금 값으로
    }

    /** "마지막으로 본 때" 를 잡는다. 이미 있으면(투명인 채 나갔다 들어옴 · 리로드) 그대로 — 그때 이미 숨었다. */
    private fun mask(id: UUID, atJoin: Boolean) {
        val pref = prefs.computeIfAbsent(id) { Pref(false) }
        val user = ess.users.get(id)
        if (atJoin) {
            val prevJoin = previousJoin.remove(id)
            if (pref.seen != null || pref.seenJoin != null) return
            // 사용자 기록의 마지막 나감은 아직 지난 접속의 것(이번 퇴장 때 바뀐다). 마지막 접속은 이미 이번 것일 수 있어 접속 직전에 잡은 값을.
            pref.seen = user?.lastQuit ?: 0L
            pref.seenJoin = prevJoin ?: user?.lastJoin?.takeIf { System.currentTimeMillis() - it > JOIN_SLACK_MS } ?: 0L
        } else {
            pref.seen = System.currentTimeMillis()
            pref.seenJoin = user?.lastJoin ?: 0L
        }
    }

    private fun unmask(id: UUID) {
        val pref = prefs[id] ?: return
        pref.seen = null
        pref.seenJoin = null
    }

    private fun hideFromOthers(player: Player) {
        for (other in Bukkit.getOnlinePlayers()) {
            if (other == player) continue
            if (other.hasPermission(Vanish.SEE_PERMISSION)) other.showPlayer(ess.plugin, player) else other.hidePlayer(ess.plugin, player)
        }
    }

    /** 투명을 켠 쪽/끈 쪽 — 볼 권한이 없는 접속자(지금 못 보는 사람)에게만. */
    private fun fake(player: Player, key: String) {
        // 접두 없이 — 진짜 접속·퇴장 메시지처럼 보여야 한다.
        val message = ess.messages.component(key, Ph.of().player(displayName(player)))
        for (other in Bukkit.getOnlinePlayers()) {
            if (other == player || other.hasPermission(Vanish.SEE_PERMISSION)) continue
            other.sendMessage(message)
        }
    }

    private fun staffNotice(player: Player, key: String) {
        if (!settings.vanishStaffNotice) return
        val ph = Ph.of().player(displayName(player))
        for (other in Bukkit.getOnlinePlayers()) if (other != player && other.hasPermission(Vanish.SEE_PERMISSION)) ess.messages.send(other, key, ph)
    }

    // --- 효과(야간 투시·보스바·잠자기) ------------------------------------------------------------------

    fun applyEffects(player: Player) {
        val id = player.uniqueId
        if (option(id, VanishOption.NIGHT_VISION)) {
            val current = player.getPotionEffect(PotionEffectType.NIGHT_VISION)
            if (current == null || id in nightVision) {
                player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, true, false, false))
                nightVision += id
            }
        }
        if (option(id, VanishOption.BOSSBAR) && id !in bars) {
            val bar = BossBar.bossBar(ess.messages.component("admininfo-vanish-bossbar"), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS)
            bars[id] = bar
            player.showBossBar(bar)
        }
        if (option(id, VanishOption.SLEEP_IGNORE) && !player.isSleepingIgnored) {
            player.isSleepingIgnored = true
            sleepIgnored += id
        }
    }

    /** 이 기능이 건 것만 되돌린다(남이 준 야간 투시·잠자기 무시는 그대로). */
    fun clearEffects(player: Player) {
        val id = player.uniqueId
        if (nightVision.remove(id)) {
            val current = player.getPotionEffect(PotionEffectType.NIGHT_VISION)
            if (current != null && current.isInfinite) player.removePotionEffect(PotionEffectType.NIGHT_VISION)
        }
        bars.remove(id)?.let { player.hideBossBar(it) }
        if (sleepIgnored.remove(id)) player.isSleepingIgnored = false
    }

    /** 옵션이 바뀐 뒤 — 투명이면 효과를 다시. */
    fun refreshEffects(player: Player) {
        if (!isActive(player)) return
        clearEffects(player)
        applyEffects(player)
    }

    // --- 옵션 ---------------------------------------------------------------------------------------

    fun setOption(id: UUID, option: VanishOption, value: Boolean) {
        prefs.computeIfAbsent(id) { Pref(false) }.options[option] = value
        persist(id)
        Bukkit.getPlayer(id)?.let { refreshEffects(it) }
    }

    fun resetOptions(id: UUID) {
        prefs[id]?.options?.clear() ?: return
        persist(id)
        Bukkit.getPlayer(id)?.let { refreshEffects(it) }
    }

    /**
     * CMI 가져오기 — 이 사람의 줄이 아직 없을 때만(이미 이 플러그인에서 고른 것이 이긴다). [options] 중 지금 기본값과 같은 것은 저장하지 않는다
     * (CMI 는 모든 값을 적어 두므로 바꾼 것만 남긴다). 넣었으면 true.
     */
    fun importCmi(id: UUID, vanished: Boolean, options: Map<VanishOption, Boolean>): Boolean {
        if (prefs.containsKey(id)) return false
        val changed = options.filter { (opt, v) -> v != settings.vanishDefault(opt) }
        prefs[id] = Pref(vanished, ConcurrentHashMap(changed))
        persist(id)
        return true
    }

    private fun persist(id: UUID) {
        if (dryRun) return
        val pref = prefs[id] ?: return
        val row = AdminStore.VanishRow(id, pref.vanished, HashMap(pref.options), pref.seen, pref.seenJoin)
        ess.db.run("투명 저장") { AdminStore.writeVanish(ess.db.sql, row) }
    }

    // --- 접속·퇴장 -----------------------------------------------------------------------------------

    /** 접속 LOWEST — 투명으로 들어올 사람에게 표식, 들어온 사람에게서 투명인 사람 가리기. */
    fun joinEarly(player: Player) {
        val id = player.uniqueId
        val pref = prefs[id]
        val allowed = player.hasPermission(AdminInfoFeature.VANISH)
        val want = allowed && ((pref?.vanished == true && !option(id, VanishOption.RELOG_DISABLE)) || option(id, VanishOption.JOIN_VANISHED))
        if (want) {
            apply(player, true, announce = false, atJoin = true)
            prefs.computeIfAbsent(id) { Pref(false) }.vanished = true
            persist(id)
        } else {
            previousJoin.remove(id)
            // 지난 접속의 표식이 남아 있을 수 있다(나간 틱 안에 다시 들어옴 · 끄다 만 서버) — 플레이어 메타데이터는 다시 들어와도 남는다.
            active.remove(id)
            Vanish.set(ess.plugin, player, false)
            if (pref != null && (pref.vanished || pref.seen != null || pref.seenJoin != null)) {
                pref.vanished = false
                unmask(id)
                persist(id)
            }
            // 지난 접속에서 이 사람을 가렸던 것이 다른 접속자에게 남아 있을 수 있다(가림은 보는 사람 쪽에 남는다).
            for (other in Bukkit.getOnlinePlayers()) if (other != player) other.showPlayer(ess.plugin, player)
        }
        for (vid in active.keys) {
            val vanished = Bukkit.getPlayer(vid) ?: continue
            if (vanished == player) continue
            if (player.hasPermission(Vanish.SEE_PERMISSION)) player.showPlayer(ess.plugin, vanished) else player.hidePlayer(ess.plugin, vanished)
        }
    }

    /** 접속 MONITOR — 본인 안내. */
    fun joined(player: Player) {
        if (isActive(player)) ess.messages.send(player, "admininfo-vanish-joined")
    }

    /** 퇴장 MONITOR — 효과를 떼고, 표식은 **한 틱 뒤에** 지운다(같은 퇴장 사건을 읽는 다른 플러그인이 아직 있을 수 있다). */
    fun quit(player: Player) {
        val id = player.uniqueId
        previousJoin.remove(id)
        if (!isActive(player)) return
        clearEffects(player)
        vanishedAt[id] = active[id] ?: System.currentTimeMillis()
        Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ ->
            if (Bukkit.getPlayer(id) != null) return@run // 그 사이 다시 들어왔다 — 접속 처리가 정했다
            active.remove(id)
            Vanish.set(ess.plugin, player, false)
        }
    }

    /** 주기 — 권한이 바뀐 사람에게 맞춘다. */
    fun resync() {
        for (vid in active.keys) {
            val vanished = Bukkit.getPlayer(vid) ?: continue
            for (other in Bukkit.getOnlinePlayers()) {
                if (other == vanished) continue
                if (other.hasPermission(Vanish.SEE_PERMISSION)) other.showPlayer(ess.plugin, vanished) else other.hidePlayer(ess.plugin, vanished)
            }
        }
    }

    /** 준비(첫 틱) — 이미 접속한 사람(`/reload`)을 저장된 대로. */
    fun ready() {
        for (player in Bukkit.getOnlinePlayers()) {
            val pref = prefs[player.uniqueId] ?: continue
            if (pref.vanished && player.hasPermission(AdminInfoFeature.VANISH) && !isActive(player)) apply(player, true, announce = false, atJoin = true)
        }
    }

    /**
     * 끌 때. 기능만 끄는 것(리로드·기능 끄기)이면 다 보이게 하고 표식을 푼다(저장된 "투명" 은 그대로 — 다시 켜지면 돌아온다).
     * **서버가 꺼지는 중이면** 효과만 뗀다 — Paper 는 플러그인을 먼저 끄고 접속자를 나중에 내보낸다. 여기서 보이게 하고 표식을 풀면 남은 사람에게
     * 잠깐 보이고, 내보낼 때 퇴장 메시지가 나가고, core 감시자(디스코드 현황판)가 "들어옴" 으로 센다.
     */
    fun shutdown() {
        val stopping = runCatching { Bukkit.isStopping() }.getOrDefault(false)
        for (vid in active.keys.toList()) {
            val player = Bukkit.getPlayer(vid) ?: continue
            clearEffects(player)
            if (stopping) continue
            for (other in Bukkit.getOnlinePlayers()) if (other != player) other.showPlayer(ess.plugin, player)
            Vanish.set(ess.plugin, player, false)
        }
        if (!stopping) active.clear()
    }

    // --- 검증기 ----------------------------------------------------------------------------------------

    /** 메모리만 — 옵션 하나를 잠깐 바꿨다 되돌릴 때. 이전 값(null = 안 바꿨음). */
    fun putOptionMemory(id: UUID, option: VanishOption, value: Boolean?): Boolean? {
        val pref = prefs.computeIfAbsent(id) { Pref(false) }
        return if (value == null) pref.options.remove(option) else pref.options.put(option, value)
    }

    /** 메모리만 — "마지막으로 본 때" 를 잠깐 바꿨다 되돌릴 때. 이전 값. */
    fun putSeenMemory(id: UUID, value: Pair<Long?, Long?>?): Pair<Long?, Long?>? {
        val pref = prefs.computeIfAbsent(id) { Pref(false) }
        val before = if (pref.seen == null && pref.seenJoin == null) null else pref.seenJoin to pref.seen
        pref.seenJoin = value?.first
        pref.seen = value?.second
        return before
    }

    companion object {
        /** 접속 직전 값을 못 잡았을 때 — 사용자 기록의 마지막 접속이 이만큼 안쪽이면 "이번 접속" 으로 보고 쓰지 않는다. */
        private const val JOIN_SLACK_MS = 10_000L

        /** 표시 이름(타이틀포지 닉네임) — 메시지에 넣을 수 있게 MiniMessage 태그를 무력화. */
        fun displayName(player: Player): String =
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(TitleForgeNames.displayName(player.uniqueId, player.name))
    }
}

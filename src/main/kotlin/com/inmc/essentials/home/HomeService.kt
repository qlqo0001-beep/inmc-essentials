package com.inmc.essentials.home

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.data.type.Bed
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 홈의 동작 — 만들기(한도·월드 한도·보호구역·덮어쓰기 확인) · 이동(남의 홈·비공개·한도 초과 막기·사라진 침대) · 침대 홈.
 * 명령·화면·리스너가 모두 이것을 부른다. 메인 스레드.
 */
class HomeService(private val ess: Ess, private val feature: HomesFeature, val store: HomeStore, val protection: Protection) {

    val settings: HomeSettings get() = feature.settings

    // --- 한도 ----------------------------------------------------------------------------------

    /**
     * 그 사람의 한도(null = 무제한). 그룹·숫자 권한은 **가진 권한 목록**에서 찾는다(LuckPerms 로 준 노드 — 선언할 수 없는 노드라 op 기본이 없다).
     * CMI 의 `cmi.command.sethome.<그룹|숫자|unlimited>` 도 넘어가는 동안 함께 인정한다([HomeRules.LEGACY_PREFIX]).
     */
    fun limitOf(player: Player): Int? = HomeRules.limit(
        player.effectivePermissions.asSequence().filter { it.value }.map { it.permission.lowercase() }.toList(),
        player.hasPermission(HomesFeature.UNLIMITED), settings,
    )

    /** 월드 한도 무시(우리 것 또는 CMI `…sethome.bypasslimit`). */
    fun bypassWorldLimit(player: Player): Boolean =
        player.hasPermission(HomesFeature.BYPASS_LIMIT) || player.hasPermission(HomeRules.LEGACY_BYPASS_LIMIT)

    /** 보호구역 무시(우리 것 또는 CMI `…sethome.bypass`). */
    fun bypassProtection(player: Player): Boolean =
        player.hasPermission(HomesFeature.BYPASS_PROTECTION) || player.hasPermission(HomeRules.LEGACY_BYPASS_PROTECTION)

    /** [location] 이 [player] 에게 홈 자리로 막혔나 — 월드 한도·보호구역. 막히면 (메시지 키, 토큰), 되면 null. [replacing] = 바꿔 끼울 홈 이름. */
    private fun placeProblem(player: Player, owner: UUID, location: Location, replacing: String, countBed: Boolean): Pair<String, Ph>? {
        val s = settings
        if (s.worldLimitsUse && !bypassWorldLimit(player)) {
            HomeRules.worldGroup(location.world.name, s)?.let { group ->
                val inGroup = store.of(owner).count { h ->
                    h.key != Home.keyOf(replacing) && (!h.bed || countBed) && group.worlds.any { it.equals(h.world, ignoreCase = true) }
                }
                if (group.amount == 0 || inGroup >= group.amount) return "homes-world-limit" to Ph.of().count(group.amount).value(inGroup.toString())
            }
        }
        if (s.protectionCheck && !bypassProtection(player)) {
            protection.blockedBy(player, location)?.let { who -> return "homes-protected" to Ph.of().value(who) }
        }
        return null
    }

    fun counted(owner: UUID): Int = HomeRules.counted(store.of(owner), settings)

    /** 한도보다 많이 가졌나(강등 등 — CMI Limits.BlockExtraHomes). */
    fun overLimit(player: Player): Boolean {
        val limit = limitOf(player) ?: return false
        return counted(player.uniqueId) > limit
    }

    // --- 찾기 ----------------------------------------------------------------------------------

    fun bedHome(owner: UUID): Home? = store.of(owner).firstOrNull { it.bed }

    fun defaultHome(owner: UUID): Home? = HomeRules.pickDefault(store.of(owner), settings.defaultName)

    /** 화면 아이콘 재질 — 정한 것, 없으면 설정의 기본(침대 홈은 침대 아이콘). */
    fun iconOf(home: Home): Material {
        val raw = home.icon ?: if (home.bed) settings.bedIcon else settings.defaultIcon
        return Material.matchMaterial(raw)?.takeIf { it.isItem && !it.isAir } ?: if (home.bed) Material.RED_BED else Material.WHITE_BED
    }

    /** [viewer] 가 [home] 으로 갈 수 있나 — 안 되면 메시지 키. */
    fun accessProblem(viewer: CommandSender, home: Home): String? = when {
        viewer is Player && viewer.uniqueId == home.owner -> null
        viewer.hasPermission(HomesFeature.OTHERS) -> null
        home.private -> "homes-private"
        viewer.hasPermission(HomesFeature.VISIT) -> null
        else -> "no-permission"
    }

    // --- 이동 ----------------------------------------------------------------------------------

    /**
     * [who] 를 [home] 으로. [viewer] 는 명령을 친 사람(알림을 받는다). [label] 이 있으면 이동이 성공한 순간 그 명령의 쿨타임을 건다
     * (본인이 자기를 옮길 때만 — "성공해야 쿨타임").
     */
    fun go(viewer: CommandSender, home: Home, label: String?, who: Player) {
        val ph = Ph.of().raw("{홈}", home.name)
        val location = home.location() ?: return ess.messages.send(viewer, "homes-world-missing", ph.world(home.world))
        if (home.bed && !bedStillThere(home)) {
            store.remove(home.owner, home.name)
            return ess.messages.send(viewer, "homes-bed-missing", ph)
        }
        if (who.uniqueId == home.owner && !home.bed && settings.blockExtraHomes && overLimit(who)) {
            return ess.messages.send(viewer, "homes-over-limit", ph.count(counted(who.uniqueId)).value((limitOf(who) ?: 0).toString()))
        }
        ess.teleporter.teleport(who, location, TeleportKind.HOME).whenComplete { ok, error ->
            sync(who) {
                if (error != null || ok != true) return@sync ess.messages.send(viewer, "homes-teleport-failed", ph)
                if (label != null && viewer == who) ess.gate?.commit(who, label)
                ess.messages.send(who, "homes-teleported", ph)
                if (viewer != who) ess.messages.send(viewer, "homes-sent", ph.target(who.name))
            }
        }
    }

    private fun sync(player: Player, block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block() else player.scheduler.run(ess.plugin, { block() }, null)
    }

    // --- 만들기 --------------------------------------------------------------------------------

    /**
     * 홈을 만든다/덮는다. [actor] = 친 사람, [owner] 와 [ownerName] = 주인(남의 홈이면 `.others` 를 이미 확인했다), [retry] = 덮어쓰기 확인을
     * 누르면 다시 칠 명령(앞의 `/` 없이). 메시지는 [actor] 에게.
     */
    fun create(actor: Player, owner: UUID, ownerName: String, request: SetRequest, location: Location, retry: String) {
        val s = settings
        val name = request.name ?: s.defaultName
        val ph = Ph.of().raw("{홈}", name).player(ownerName)
        HomeRules.nameProblem(name, s)?.let { key -> return ess.messages.send(actor, key, ph.value(s.nameMaxLength.toString())) }
        val self = owner == actor.uniqueId
        val existing = store.get(owner, name)

        if (existing != null && !request.overwrite && s.confirmOverwrite) {
            return ess.messages.send(actor, "homes-exists", ph.raw("{명령}", "/$retry 덮어쓰기"))
        }
        if (existing == null && self) {
            val limit = limitOf(actor)
            if (limit != null && counted(owner) >= limit) return ess.messages.send(actor, "homes-limit-reached", ph.count(limit))
        }
        placeProblem(actor, owner, location, name, countBed = false)?.let { (key, extra) ->
            return ess.messages.send(actor, key, extra.raw("{홈}", name).player(ownerName))
        }
        val slot = request.slot
        if (slot != null) {
            if (slot !in 1..s.maxSlot) return ess.messages.send(actor, "homes-slot-invalid", ph.value(s.maxSlot.toString()))
            store.of(owner).firstOrNull { it.slot == slot && it.key != Home.keyOf(name) }?.let { taken ->
                return ess.messages.send(actor, "homes-slot-taken", ph.raw("{다른홈}", taken.name).value(slot.toString()))
            }
        }
        val icon = request.icon?.let { raw ->
            resolveIcon(actor, raw) ?: return ess.messages.send(actor, "homes-icon-invalid", ph.value(raw))
        }
        val now = System.currentTimeMillis()
        val home = Home.at(owner, name, location, now).copy(
            icon = icon ?: existing?.icon,
            private = request.private || (existing?.private ?: false),
            slot = slot ?: existing?.slot,
            created = existing?.created ?: now,
        )
        store.put(home)
        val key = when {
            !self -> "homes-set-other"
            home.private -> "homes-set-private"
            else -> "homes-set"
        }
        ess.messages.send(actor, key, ph.count(counted(owner)).value(limitText(if (self) limitOf(actor) else null)))
    }

    /** 아이콘 낱말 — `손`·`hand` 면 손에 든 것의 재질, 아니면 재질 이름. 못 찾으면 null. */
    fun resolveIcon(player: Player, raw: String): String? {
        val material = if (raw.equals("손", true) || raw.equals("hand", true)) player.inventory.itemInMainHand.type
        else Material.matchMaterial(raw)
        return material?.takeIf { it.isItem && !it.isAir }?.name
    }

    fun limitText(limit: Int?): String = limit?.toString() ?: "∞"

    // --- 침대 홈 --------------------------------------------------------------------------------

    /** 침대의 머리 블록(발 블록을 눌러도 같은 침대로). */
    fun bedHead(block: Block): Block {
        val data = block.blockData as? Bed ?: return block
        return if (data.part == Bed.Part.FOOT) block.getRelative(data.facing) else block
    }

    /**
     * 침대를 눌렀다 — 침대 홈을 그 사람이 선 자리로(같은 침대를 다시 누르면 조용히 넘어간다). 침대가 터지는 월드(네더·엔드)에서는 아무것도
     * 안 한다. 만들기와 같은 월드 한도·보호구역 검사를 거친다. 같은 이름의 **보통 홈**이 있으면 덮지 않는다.
     */
    fun setBed(player: Player, bedBlock: Block) {
        val s = settings
        if (!HomeRules.bedWorks(bedBlock.world.environment)) return
        val head = bedHead(bedBlock)
        val blockKey = Home.blockKey(head.world.name, head.x, head.y, head.z)
        val old = bedHome(player.uniqueId)
        if (old != null && old.bedBlock == blockKey) return
        val ph = Ph.of().raw("{홈}", s.bedName)
        val existing = store.get(player.uniqueId, s.bedName)
        if (existing != null && !existing.bed) return ess.messages.send(player, "homes-bed-name-taken", ph)
        val location = player.location
        placeProblem(player, player.uniqueId, location, old?.name ?: s.bedName, countBed = s.bedCountsTowardLimit)?.let { (key, extra) ->
            return ess.messages.send(player, key, extra.raw("{홈}", s.bedName))
        }
        if (s.bedCountsTowardLimit && old == null) {
            val limit = limitOf(player)
            if (limit != null && counted(player.uniqueId) >= limit) return ess.messages.send(player, "homes-limit-reached", ph.count(limit))
        }
        if (old != null && old.key != Home.keyOf(s.bedName)) store.remove(old.owner, old.name)
        val now = System.currentTimeMillis()
        store.put(Home.at(player.uniqueId, s.bedName, location, now).copy(
            bed = true, bedBlock = blockKey, icon = old?.icon, private = old?.private ?: false, slot = old?.slot, created = old?.created ?: now,
        ))
        ess.messages.send(player, "homes-bed-set", ph)
    }

    /** 침대가 부서졌다 — 그 침대로 만든 홈을 지운다(CMI RemoveBedLocationOnBedBreak). */
    fun bedBroken(block: Block) {
        for (home in bedHomesAt(block)) {
            store.remove(home.owner, home.name)
            Bukkit.getPlayer(home.owner)?.let { ess.messages.send(it, "homes-bed-removed", Ph.of().raw("{홈}", home.name)) }
        }
    }

    /** [block](침대의 머리·발 어느 쪽이든)으로 만든 침대 홈들 — 메모리만 본다(검증기도 부른다). */
    fun bedHomesAt(block: Block): List<Home> {
        val head = bedHead(block)
        val blockKey = Home.blockKey(head.world.name, head.x, head.y, head.z)
        return store.all().filter { it.bed && it.bedBlock == blockKey }.toList()
    }

    /** 침대 홈의 침대가 아직 있나 — 청크가 안 올라와 있으면 있다고 본다(청크를 읽지 않는다). */
    fun bedStillThere(home: Home): Boolean {
        val pos = HomeRules.parseBlockKey(home.bedBlock) ?: return true
        val world = Bukkit.getWorld(pos.world) ?: return true
        if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) return true
        return Tag.BEDS.isTagged(world.getBlockAt(pos.x, pos.y, pos.z).type)
    }
}

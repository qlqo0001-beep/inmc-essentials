package com.inmc.essentials.warp

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.Icon
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/** 워프의 동작 — 권한·보이기·만들기·이동·아이콘. 명령·화면이 부른다. 메인 스레드. */
class WarpService(private val ess: Ess, private val feature: WarpsFeature, val store: WarpStore) {

    val settings: WarpSettings get() = feature.settings

    // --- 권한 ----------------------------------------------------------------------------------

    fun isAdmin(sender: CommandSender): Boolean = sender.hasPermission(WarpsFeature.ADMIN)

    /** 쓸 수 있나 — 권한이 필요 없는 워프, 또는 `.all`·관리·그 워프의 노드. */
    fun canUse(sender: CommandSender, warp: Warp): Boolean =
        !warp.permission || sender.hasPermission(WarpsFeature.ALL) || isAdmin(sender) || sender.hasPermission(WarpRules.WARP_PREFIX + warp.key)

    /** 목록·화면·제안에 보이나 — 숨긴 것은 관리자만, 못 쓰는 것은 [Warp.showLocked] 일 때만(잠긴 채). */
    fun canSee(sender: CommandSender, warp: Warp): Boolean =
        (!warp.hidden || isAdmin(sender)) && (warp.showLocked || canUse(sender, warp))

    /** 고칠 수 있나 — 관리자, 또는 만든 사람(만들기 권한이 남아 있을 때 — CMI notYours). */
    fun canEdit(sender: CommandSender, warp: Warp): Boolean =
        isAdmin(sender) || (sender is Player && warp.creator == sender.uniqueId && sender.hasPermission(WarpsFeature.SET))

    fun limitOf(player: Player): Int? =
        if (isAdmin(player)) null
        else WarpRules.limit(player.effectivePermissions.filter { it.value }.map { it.permission.lowercase() }, settings)

    fun ownedBy(id: UUID): Int = store.all().count { it.creator == id }

    fun visible(sender: CommandSender, group: String? = null): List<Warp> =
        store.all().filter { canSee(sender, it) && WarpRules.inGroup(it, group) && sameMode(sender, it) }.sortedBy { it.key }

    /** 다른 모드의 워프는 목록에서 뺀다(관리자는 넘을 수 있어 다 보인다 — core `Realms`, 2026-10-15). 월드가 없으면 남긴다(원래 그 메시지가 나온다). */
    private fun sameMode(sender: CommandSender, warp: Warp): Boolean {
        val player = sender as? Player ?: return true
        val at = warp.location() ?: return true
        return kr.inmc.core.integration.Realms.canTravel(player, at)
    }

    // --- 아이콘 --------------------------------------------------------------------------------

    /** 화면 아이콘(이름·설명까지). [locked] 면 잠긴 재질로. */
    fun icon(warp: Warp, locked: Boolean, extraLore: List<String> = emptyList()): ItemStack {
        val title = (if (locked) "<gray>🔒 " else "<white>") + (warp.displayName ?: warp.name)
        val lore = lore(warp) + (if (locked) listOf("", "<red>쓸 권한이 없습니다</red>") else emptyList()) + extraLore
        if (locked) return Icon.of(material(settings.lockedIcon, Material.GRAY_DYE), title, lore)
        val custom = warp.iconItem?.let { decode(it) }
        if (custom != null) return Icon.relabel(custom, title, lore)
        return Icon.of(material(warp.icon ?: settings.defaultIcon, Material.ENDER_PEARL), title, lore)
    }

    fun lore(warp: Warp): List<String> = buildList {
        addAll(warp.description)
        if (warp.autoLore) {
            if (warp.description.isNotEmpty()) add("")
            add("<gray>${warp.world}</gray>")
            add("<gray>${warp.x.toInt()}:${warp.y.toInt()}:${warp.z.toInt()}</gray>")
            warp.group?.let { add("<dark_gray>그룹: $it</dark_gray>") }
        }
        if (settings.showCreator) warp.creatorName?.let { add("<dark_gray>만든 이: $it</dark_gray>") }
    }

    private fun material(raw: String, fallback: Material): Material =
        Material.matchMaterial(raw)?.takeIf { it.isItem && !it.isAir } ?: fallback

    /** 아이콘으로 적는다 — 보이는 것만 남긴 새 아이템([iconCopy])을. */
    fun encode(stack: ItemStack): String = Base64.getEncoder().encodeToString(iconCopy(stack).serializeAsBytes())

    /** 읽을 때도 보이는 것만(옛 판이 통째로 적어 둔 상자·책 내용이 화면 패킷에 실리지 않게). */
    fun decode(raw: String): ItemStack? = runCatching { iconCopy(ItemStack.deserializeBytes(Base64.getDecoder().decode(raw))) }.getOrNull()

    /**
     * 손에 든 아이템에서 **보이는 것만** 옮긴 새 아이템 — 재질 · 이름 · 설명(최대 [MAX_ICON_LORE]줄) · 모델(커스텀 모델 데이터·아이템 모델) ·
     * 반짝임 · 머리 주인. 셜커 안의 물건·꾸러미·책 내용 같은 것은 버린다(워프 화면을 여는 모두에게 커다란 패킷이 가지 않게 — 책 밴).
     */
    fun iconCopy(src: ItemStack): ItemStack {
        val out = ItemStack(src.type)
        val from = src.itemMeta ?: return out
        out.editMeta { to ->
            if (from.hasDisplayName()) to.displayName(from.displayName())
            if (from.hasLore()) to.lore(from.lore()?.take(MAX_ICON_LORE))
            if (from.hasCustomModelDataComponent()) to.setCustomModelDataComponent(from.customModelDataComponent)
            if (from.hasItemModel()) to.itemModel = from.itemModel
            if (from.hasEnchants() || from.enchantmentGlintOverride == true) to.setEnchantmentGlintOverride(true)
            if (from is SkullMeta && to is SkullMeta) from.playerProfile?.let { to.playerProfile = it }
        }
        return out
    }

    /** `머리`(빈 이름 = 자기) · `머리:이름`(이 서버에 온 적 있는 사람만 — 이름으로 웹을 찾지 않는다). */
    fun head(player: Player, name: String): ItemStack? {
        val owner = if (name.isEmpty()) player else (Bukkit.getOfflinePlayerIfCached(name) ?: return null)
        return ItemStack(Material.PLAYER_HEAD).apply { editMeta(SkullMeta::class.java) { it.owningPlayer = owner } }
    }

    // --- 이동 ----------------------------------------------------------------------------------

    /**
     * [who] 를 [warp] 로(기본·추가 위치 중 무작위, 방향 무작위 선택 가능). [viewer] 가 친 사람. 본인이 자기를 옮기면 성공한 순간
     * [label] 명령의 쿨타임을 건다.
     */
    fun go(viewer: CommandSender, warp: Warp, label: String?, who: Player, silent: Boolean = false) {
        val ph = Ph.of().raw("{워프}", warp.displayName ?: warp.name)
        val spots = warp.locations()
        if (spots.isEmpty()) return ess.messages.send(viewer, "warps-world-missing", ph.world(warp.world))
        val random = ThreadLocalRandom.current()
        val to: Location = spots[random.nextInt(spots.size)].clone()
        if (!kr.inmc.core.integration.Realms.canTravel(who, to)) return ess.messages.send(viewer, "warps-other-mode", ph)
        if (warp.randomYaw) to.yaw = random.nextFloat() * 360f - 180f
        ess.teleporter.teleport(who, to, TeleportKind.WARP).whenComplete { ok, error ->
            sync(who) {
                if (error != null || ok != true) return@sync ess.messages.send(viewer, "warps-teleport-failed", ph)
                if (label != null && viewer == who) ess.gate?.commit(who, label)
                if (viewer == who || !silent) ess.messages.send(who, "warps-teleported", ph)
                if (viewer != who) ess.messages.send(viewer, "warps-sent", ph.target(who.name))
            }
        }
    }

    private fun sync(player: Player, block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block() else player.scheduler.run(ess.plugin, { block() }, null)
    }

    // --- 만들기 --------------------------------------------------------------------------------

    /** `/워프설정` — 만들었거나 고친 워프(실패면 null, 메시지는 보냈다). [retry] = 덮어쓰기 확인 때 다시 칠 명령(앞 `/` 없이). */
    fun create(player: Player, request: WarpRequest, retry: String): Warp? {
        val s = settings
        val name = request.name ?: return null.also { ess.messages.send(player, "warps-set-usage") }
        val ph = Ph.of().raw("{워프}", name)
        WarpRules.nameProblem(name, s)?.let { key -> return null.also { ess.messages.send(player, key, ph.count(s.minLength).value(s.maxLength.toString())) } }
        val existing = store.get(name)
        if (existing != null) {
            if (!canEdit(player, existing)) return null.also { ess.messages.send(player, "warps-not-yours", ph) }
            if (!request.overwrite) return null.also { ess.messages.send(player, "warps-exists", ph.raw("{명령}", "/$retry 덮어쓰기")) }
        } else {
            val limit = limitOf(player)
            if (limit != null && ownedBy(player.uniqueId) >= limit) return null.also { ess.messages.send(player, "warps-limit", ph.count(limit)) }
        }
        val location = request.location?.let { raw ->
            // 좌표 지정은 관리용 — 만들기 권한만 가진 사람(상점 워프 등)이 가 본 적 없는 곳(남의 영지)에 워프를 두지 못하게(홈의 customloc 과 같다).
            if (!isAdmin(player) && !player.hasPermission(WarpsFeature.CUSTOM_LOCATION)) return null.also { ess.messages.send(player, "no-permission") }
            WarpCodec.parseSpot(raw) ?: return null.also { ess.messages.send(player, "warps-location-invalid", ph.value(raw)) }
        } ?: player.location
        val slot = request.slot
        if (slot != null) {
            if (slot !in 1..MAX_SLOT) return null.also { ess.messages.send(player, "warps-slot-invalid", ph.value(MAX_SLOT.toString())) }
            store.all().firstOrNull { it.slot == slot && it.key != Warp.keyOf(name) }?.let { taken ->
                return null.also { ess.messages.send(player, "warps-slot-taken", ph.raw("{다른워프}", taken.name).value(slot.toString())) }
            }
        }
        var icon = existing?.icon
        var iconItem = existing?.iconItem
        when {
            request.iconFromHand -> {
                val hand = player.inventory.itemInMainHand
                if (hand.type.isAir) return null.also { ess.messages.send(player, "warps-icon-hand-empty", ph) }
                icon = hand.type.name
                iconItem = encode(hand)
            }
            request.iconHead != null -> {
                val head = head(player, request.iconHead) ?: return null.also { ess.messages.send(player, "warps-icon-invalid", ph.value("머리:" + request.iconHead)) }
                icon = Material.PLAYER_HEAD.name
                iconItem = encode(head)
            }
            request.icon != null -> {
                val material = Material.matchMaterial(request.icon)?.takeIf { it.isItem && !it.isAir }
                    ?: return null.also { ess.messages.send(player, "warps-icon-invalid", ph.value(request.icon)) }
                icon = material.name
                iconItem = null
            }
        }
        val now = System.currentTimeMillis()
        val base = existing ?: Warp(name, location.world.name, location.x, location.y, location.z, creator = player.uniqueId, creatorName = player.name, created = now)
        val warp = base.moved(location).copy(
            name = name,
            group = request.group ?: existing?.group,
            permission = request.permission ?: existing?.permission ?: s.requirePermissionDefault,
            autoLore = request.autoLore ?: existing?.autoLore ?: true,
            slot = slot ?: existing?.slot,
            icon = icon,
            iconItem = iconItem,
        )
        store.put(warp, existing?.name ?: name)
        ess.messages.send(player, if (existing == null) "warps-set" else "warps-updated", ph)
        if (warp.permission) ess.messages.send(player, "warps-permission-hint", ph.value(WarpRules.WARP_PREFIX + warp.key))
        return warp
    }

    /** 지운다 — 됐으면 true. 부활 워프였으면 스폰 기능이 [sender] 에게 알린다. */
    fun remove(sender: CommandSender, name: String): Boolean {
        if (!store.remove(name)) return false
        feature.removed(name, sender)
        return true
    }

    /** 이름 바꾸기(CMI `/editwarp 이름 새이름`) — 됐으면 true. 메시지는 [sender] 에게. */
    fun rename(sender: CommandSender, warp: Warp, next: String): Boolean {
        val s = settings
        val ph = Ph.of().raw("{워프}", next).value(warp.name)
        WarpRules.nameProblem(next, s)?.let { key -> return false.also { ess.messages.send(sender, key, ph.count(s.minLength).value(s.maxLength.toString())) } }
        val other = store.get(next)
        if (other != null && other.key != warp.key) return false.also { ess.messages.send(sender, "warps-rename-exists", ph) }
        store.put(warp.copy(name = next), old = warp.name)
        ess.messages.send(sender, "warps-renamed", ph)
        feature.renamed(warp.name, next)
        return true
    }

    companion object {
        /** 아이콘 설명 줄 수의 끝. */
        const val MAX_ICON_LORE = 16

        /** 칸 번호의 끝(쪽당 45칸 × 22쪽). */
        const val MAX_SLOT = 990
    }
}

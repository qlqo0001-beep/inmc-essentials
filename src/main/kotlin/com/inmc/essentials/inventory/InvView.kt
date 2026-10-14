package com.inmc.essentials.inventory

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kr.inmc.core.gui.Icon
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * `/인벤` 의 접속자 창 — **54칸 사본**: 0~26 가방 윗칸(9~35) · 27~35 핫바(0~8) · 36 머리 · 37 몸 · 38 다리 · 39 발 · 40 왼손 · 나머지 칸막이.
 *
 * 남의 `PlayerInventory` 를 그대로 열면 이 서버에서는 4줄 상자(36칸)로 보여 갑옷·왼손이 안 보인다(Leaf 26.2 `CraftContainer` 가
 * `InventoryType.PLAYER` 를 `GENERIC_9X4` 로 — javap 로 확인). 그래서 사본 창을 쓰고 [SilentChest] 와 같은 기준(base) 방식으로 맞춘다:
 * - 클릭·드래그 때 대상의 진짜 가방이 기준과 다르면 그 클릭을 취소하고 다시 맞춘다. 같으면 **다음 틱에 바뀐 칸만** 대상에 쓴다.
 * - 쓰기는 **전부 아니면 하나도 아니다**: 그 틱 사이 대상 쪽도 바뀐 칸(줍기·대상의 클릭), 맞지 않는 갑옷 칸(머리에 흙), 셜커 배낭이 열린
 *   상자 칸이 하나라도 있으면 아무것도 쓰지 않고 그 틱의 변화 전부를 **되돌린다** — 꺼낸 것과 넣은 것을 맞비긴 뒤 꺼낸 것은 커서·가방에서
 *   다시 가져오고 넣은 것은 돌려준다([Reclaim]). 편집 창에서는 버리기·창 밖 클릭을 막아(꺼낸 것이 커서·가방을 떠나지 않게) 되찾지 못하는 일이
 *   없다. 쓰기를 기다리는 그 한 틱 동안은 대상의 클릭·버리기·놓기·먹기·줍기·손 바꾸기도 막는다.
 * - 대상 쪽 변화는 2틱마다 사본에 옮긴다. 대상이 나가면 쓰고 닫고(저장 전에), 죽으면 그 틱의 변화를 되돌리고 닫는다(떨어질 목록이 이미 만들어졌다).
 */
class InvView(private val ess: Ess) : Listener {

    class Session(val viewer: UUID, val target: UUID, val targetName: String, val editable: Boolean) : InventoryHolder {
        lateinit var copy: Inventory
        var base: Array<ItemStack?> = emptyArray()
        var pending = false
        var closed = false
        var task: ScheduledTask? = null
        override fun getInventory(): Inventory = copy
    }

    private val sessions = HashMap<UUID, Session>()

    /** [viewer] 에게 [target] 의 가방을 연다. 다른 플러그인이 창 열기를 막으면 null. */
    fun open(viewer: Player, target: Player, editable: Boolean): Session? {
        sessions.remove(viewer.uniqueId)?.let { close(it, apply = true) }
        val session = Session(viewer.uniqueId, target.uniqueId, target.name, editable)
        session.copy = Bukkit.createInventory(session, SIZE, Component.text("${target.name} 의 가방", NamedTextColor.DARK_GRAY))
        session.base = live(target)
        fill(session)
        sessions[viewer.uniqueId] = session
        viewer.openInventory(session.copy)
        if (viewer.openInventory.topInventory.holder !== session) {
            session.closed = true
            sessions.remove(viewer.uniqueId)
            return null
        }
        session.task = viewer.scheduler.runAtFixedRate(ess.plugin, { _ -> refresh(viewer, session) }, null, 2L, 2L)
        return session
    }

    private fun live(target: Player): Array<ItemStack?> =
        Array(TARGET_SIZE) { target.inventory.getItem(it)?.takeIf { s -> !s.type.isAir }?.clone() }

    /** 사본 창의 내용을 대상 칸 순서로. */
    private fun copyAsTarget(session: Session): Array<ItemStack?> =
        Array(TARGET_SIZE) { session.copy.getItem(toView(it))?.takeIf { s -> !s.type.isAir }?.clone() }

    /** 기준(base)으로 사본을 다시 그린다. */
    private fun fill(session: Session) {
        val contents = arrayOfNulls<ItemStack>(SIZE)
        for (v in 0 until SIZE) {
            val t = toTarget(v)
            contents[v] = if (t >= 0) session.base[t]?.clone() else LABELS[v]?.let { Icon.of(Material.BLACK_STAINED_GLASS_PANE, it) } ?: Icon.FILLER
        }
        session.copy.contents = contents
    }

    private fun refresh(viewer: Player, session: Session) {
        if (session.closed) return
        val target = Bukkit.getPlayer(session.target)
        if (target == null) return close(session, apply = false)
        if (session.pending) return
        val now = live(target)
        if (SilentChest.same(now, session.base)) return
        if (SilentChest.same(copyAsTarget(session), session.base)) {
            session.base = now
            fill(session)
        }
    }

    // --- 고치기 ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(event: InventoryClickEvent) {
        val session = event.view.topInventory.holder as? Session ?: return
        val player = event.whoClicked as? Player ?: return
        if (!session.editable) { event.isCancelled = true; return }
        // 버리기·창 밖 클릭은 막는다 — 꺼낸 것이 커서·가방을 떠나면 되돌릴 때 되찾지 못한다(복제).
        if (Reclaim.leaves(event)) { event.isCancelled = true; return }
        // 두 번 클릭 모으기는 칸막이 유리를 끌어오지 않게 유리를 쥐었을 때 막는다(칸막이 칸은 대상에 쓰이지 않으니 공짜 유리가 된다).
        if (event.action == InventoryAction.COLLECT_TO_CURSOR && event.cursor.type.name.endsWith("STAINED_GLASS_PANE")) { event.isCancelled = true; return }
        val top = event.view.topInventory
        if (event.clickedInventory === top) {
            if (toTarget(event.slot) < 0) { event.isCancelled = true; return }
            if (backpack(event.currentItem)) { event.isCancelled = true; return }
        }
        guard(player, session, event)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrag(event: InventoryDragEvent) {
        val session = event.view.topInventory.holder as? Session ?: return
        val player = event.whoClicked as? Player ?: return
        if (!session.editable) { event.isCancelled = true; return }
        val topSlots = event.rawSlots.filter { it < SIZE }
        if (topSlots.isEmpty()) return
        if (topSlots.any { toTarget(it) < 0 }) { event.isCancelled = true; return }
        guard(player, session, event)
    }

    private fun guard(player: Player, session: Session, event: org.bukkit.event.Cancellable) {
        val target = Bukkit.getPlayer(session.target)
        if (target == null) { event.isCancelled = true; return }
        if (!SilentChest.same(live(target), session.base)) {
            // 대상이 그새 바뀌었다 — 이 클릭은 낡은 화면 위의 것.
            event.isCancelled = true
            session.base = live(target)
            fill(session)
            ess.messages.send(player, "inventory-inv-resync")
            return
        }
        if (!session.pending) {
            session.pending = true
            Bukkit.getScheduler().runTask(ess.plugin, Runnable { apply(player, session, revert = false) })
        }
    }

    /**
     * 사본에서 바뀐 칸을 대상에 — **전부 쓰거나 하나도 쓰지 않는다**. 한 칸이라도 못 쓰면(대상 쪽도 바뀐 칸 · 안 맞는 갑옷 · 열린 배낭 ·
     * 대상 없음 · [revert]) 아무것도 쓰지 않고 바뀐 칸 전부를 보는 사람 쪽에서 되돌린다([Reclaim.undoAll] — 사본 안에서 옮긴 것은 맞비긴다).
     * 칸마다 따로 쓰면 사본의 다른 칸으로 옮긴 아이템이 그쪽 칸에만 써져 복제된다.
     */
    private fun apply(viewer: Player, session: Session, revert: Boolean) {
        if (!session.pending) return
        session.pending = false
        val copy = copyAsTarget(session)
        val changed = (0 until TARGET_SIZE).filter { !SilentChest.same(copy[it], session.base[it]) }
        if (changed.isEmpty()) return
        val target = Bukkit.getPlayer(session.target)?.takeIf { it.isOnline && !it.isDead && !revert }
        val live = target?.let { live(it) }
        val ok = target != null && live != null && writable(changed, live, session.base, copy)
        if (ok) {
            for (i in changed) target!!.inventory.setItem(i, copy[i]?.clone())
        } else {
            val missing = Reclaim.undoAll(viewer, changed.map { session.base[it] to copy[it] })
            if (missing > 0) ess.logger.warning("/인벤: ${viewer.name} 가 ${session.targetName} 의 가방에서 꺼낸 것 중 ${missing}개를 되찾지 못했습니다")
            ess.messages.send(viewer, "inventory-inv-undone", Ph.of().count(changed.size))
        }
        if (target != null) session.base = live(target)
        if (!session.closed) fill(session)
    }

    /** 검증기용 — 기다리던 쓰기를 지금 한다(다음 틱을 기다리지 않고). */
    internal fun flush(viewer: Player, session: Session) {
        session.pending = true
        apply(viewer, session, revert = false)
    }

    // --- 쓰기를 기다리는 그 틱 동안 대상 쪽 변화 막기(되돌리기가 덜 일어나게) -----------------------------------

    private fun pendingTarget(id: UUID): Boolean = sessions.values.any { it.pending && it.target == id }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTargetClick(event: InventoryClickEvent) {
        if (sessions.isEmpty() || event.view.topInventory.holder is Session) return
        if (pendingTarget(event.whoClicked.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTargetDrag(event: InventoryDragEvent) {
        if (sessions.isEmpty() || event.view.topInventory.holder is Session) return
        if (pendingTarget(event.whoClicked.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTargetDrop(event: PlayerDropItemEvent) {
        if (sessions.isNotEmpty() && pendingTarget(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTargetPlace(event: BlockPlaceEvent) {
        if (sessions.isNotEmpty() && pendingTarget(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTargetConsume(event: PlayerItemConsumeEvent) {
        if (sessions.isNotEmpty() && pendingTarget(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTargetPickup(event: EntityPickupItemEvent) {
        if (sessions.isNotEmpty() && pendingTarget(event.entity.uniqueId)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTargetSwap(event: PlayerSwapHandItemsEvent) {
        if (sessions.isNotEmpty() && pendingTarget(event.player.uniqueId)) event.isCancelled = true
    }

    // --- 닫기 ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val session = event.inventory.holder as? Session ?: return
        if (session.closed) return
        if (session.pending) (event.player as? Player)?.let { apply(it, session, revert = false) }
        session.closed = true
        session.task?.cancel()
        sessions.remove(session.viewer)
    }

    private fun close(session: Session, apply: Boolean) {
        if (session.closed) return
        val viewer = Bukkit.getPlayer(session.viewer)
        if (session.pending && viewer != null) apply(viewer, session, revert = !apply)
        session.closed = true
        session.task?.cancel()
        sessions.remove(session.viewer)
        if (viewer != null && viewer.openInventory.topInventory.holder === session) viewer.closeInventory()
    }

    /** 대상이 나가면 그 틱의 변화를 **저장 전에** 쓰고 닫는다. 보던 사람이 나가면 쓰고 닫는다. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        sessions[id]?.let { close(it, apply = true) }
        for (session in sessions.values.toList()) {
            if (session.target != id) continue
            close(session, apply = true)
            Bukkit.getPlayer(session.viewer)?.let { ess.messages.send(it, "inventory-target-left", Ph.of().player(event.player.name)) }
        }
    }

    /** 대상이 죽으면 떨어질 목록이 이미 만들어졌다 — 그 틱의 변화는 쓰지 않고 되돌린 뒤 닫는다. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onDeath(event: PlayerDeathEvent) {
        val id = event.entity.uniqueId
        for (session in sessions.values.toList()) {
            if (session.target != id) continue
            close(session, apply = false)
            Bukkit.getPlayer(session.viewer)?.let { ess.messages.send(it, "inventory-inv-target-died", Ph.of().player(event.entity.name)) }
        }
    }

    fun closeAll() {
        for (session in sessions.values.toList()) close(session, apply = true)
    }

    companion object {
        const val SIZE = 54
        const val TARGET_SIZE = 41

        /** 칸막이 위의 안내(갑옷 줄 오른쪽). */
        private val LABELS: Map<Int, String> = mapOf(41 to "<gray>← 머리 · 몸 · 다리 · 발 · 왼손</gray>")

        /** 창 칸 → 대상 가방 칸(-1 = 칸막이). 대상 칸: 0~8 핫바 · 9~35 가방 · 36 발 · 37 다리 · 38 몸 · 39 머리 · 40 왼손. */
        fun toTarget(view: Int): Int = when (view) {
            in 0..26 -> view + 9
            in 27..35 -> view - 27
            36 -> 39
            37 -> 38
            38 -> 37
            39 -> 36
            40 -> 40
            else -> -1
        }

        /** 대상 가방 칸 → 창 칸. */
        fun toView(target: Int): Int = when (target) {
            in 0..8 -> target + 27
            in 9..35 -> target - 9
            36 -> 39
            37 -> 38
            38 -> 37
            39 -> 36
            40 -> 40
            else -> -1
        }

        /** 바뀐 칸 [changed] 를 **모두** 쓸 수 있나 — 하나라도 대상 쪽도 바뀌었거나(기준과 다름) 갑옷이 안 맞거나 열린 배낭이면 false(아무것도 안 쓴다). */
        fun writable(changed: List<Int>, live: Array<ItemStack?>, base: Array<ItemStack?>, copy: Array<ItemStack?>): Boolean =
            changed.all { i -> SilentChest.same(live[i], base[i]) && fits(i, copy[i]) && !backpack(live[i]) }

        private val BACKPACK = NamespacedKey(Ess.NAMESPACE, "backpack")

        /** 셜커 배낭으로 열려 있는 상자(items 기능의 표) — 건드리지 않는다(밑에서 옮기면 배낭이 복제한다). */
        fun backpack(item: ItemStack?): Boolean =
            item != null && !item.type.isAir && item.hasItemMeta() && item.itemMeta.persistentDataContainer.has(BACKPACK, PersistentDataType.STRING)

        /** 대상 칸 [slot] 에 [item] 이 들어갈 수 있나 — 갑옷 칸은 바닐라 장착 규칙(재질의 장착 칸 · equippable 구성요소). */
        @Suppress("UnstableApiUsage")
        fun fits(slot: Int, item: ItemStack?): Boolean {
            if (item == null || item.type.isAir) return true
            val wanted = when (slot) {
                36 -> EquipmentSlot.FEET
                37 -> EquipmentSlot.LEGS
                38 -> EquipmentSlot.CHEST
                39 -> EquipmentSlot.HEAD
                else -> return true
            }
            if (item.type.equipmentSlot == wanted) return true
            return runCatching { item.getData(DataComponentTypes.EQUIPPABLE)?.slot() }.getOrNull() == wanted
        }
    }
}

/** 사본 창에서 원본에 쓰지 못한 변화를 보는 사람 쪽에서 되돌린다(조용히 열기 · /인벤 이 같이 쓴다). */
internal object Reclaim {

    private val LEAVING = setOf(
        InventoryAction.DROP_ALL_CURSOR, InventoryAction.DROP_ONE_CURSOR, InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT,
    )

    /** 이 클릭이 아이템을 커서·가방 밖(바닥)으로 보내나 — 편집 창에서는 막는다. */
    fun leaves(event: InventoryClickEvent): Boolean =
        event.clickedInventory == null || event.action in LEAVING || event.click == ClickType.DROP || event.click == ClickType.CONTROL_DROP

    /**
     * 여러 칸의 변화([기준, 사본] 쌍)를 한꺼번에 되돌린다. 꺼낸 것과 넣은 것을 먼저 맞비긴다 — 사본 안에서 X 칸 → Y 칸으로 옮긴 것은
     * 보는 사람 쪽에서 아무것도 오가지 않는다. 남은 꺼낸 것은 커서 → 가방에서 다시 가져오고, 남은 넣은 것은 돌려준다. 되찾지 못한 개수.
     */
    fun undoAll(player: Player, changes: List<Pair<ItemStack?, ItemStack?>>): Int {
        val taken = ArrayList<ItemStack>()
        val added = ArrayList<ItemStack>()
        for ((base, copy) in changes) {
            val (t, a) = delta(base, copy)
            t?.let { taken += it }
            a?.let { added += it }
        }
        val (netTaken, netAdded) = InvDelta.net(taken, added, { a, b -> a.isSimilar(b) }, { it.amount }, { s, n -> s.clone().also { it.amount = n } })
        var missing = 0
        for (t in netTaken) missing += takeBack(player, t)
        for (a in netAdded) giveBack(player, a)
        return missing
    }

    /** (보는 사람이 꺼낸 것, 넣은 것). */
    fun delta(base: ItemStack?, copy: ItemStack?): Pair<ItemStack?, ItemStack?> {
        val b = base?.takeIf { !it.type.isAir }
        val c = copy?.takeIf { !it.type.isAir }
        if (b == null && c == null) return null to null
        if (b != null && c != null && b.isSimilar(c)) {
            val d = c.amount - b.amount
            return when {
                d > 0 -> null to c.clone().also { it.amount = d }
                d < 0 -> b.clone().also { it.amount = -d } to null
                else -> null to null
            }
        }
        return b?.clone() to c?.clone()
    }

    /** 커서 → 가방 순서로 [item] 만큼 다시 가져온다. 못 가져온 개수. */
    private fun takeBack(player: Player, item: ItemStack): Int {
        var left = item.amount
        val cursor = player.itemOnCursor
        if (!cursor.type.isAir && cursor.isSimilar(item)) {
            val n = minOf(left, cursor.amount)
            cursor.amount -= n
            player.setItemOnCursor(if (cursor.amount <= 0) null else cursor)
            left -= n
        }
        if (left > 0) left = player.inventory.removeItem(item.clone().also { it.amount = left }).values.sumOf { it.amount }
        return left
    }

    private fun giveBack(player: Player, item: ItemStack) {
        for (rest in player.inventory.addItem(item).values) player.world.dropItemNaturally(player.location, rest)
    }
}

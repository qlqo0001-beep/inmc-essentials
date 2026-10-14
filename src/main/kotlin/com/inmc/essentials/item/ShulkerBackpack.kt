package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.VanillaNames
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.Tag
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * 셜커 상자 배낭(카탈로그 cfg4:cfg-shulker-backpack) — 가방 안의 셜커 상자를 놓지 않고 연다(우클릭, 설정이면 Shift+우클릭만 · 손에 들고 허공 우클릭).
 *
 * **복제 막기**가 전부다:
 * - 연 상자 아이템에 이번 열기의 표(PDC `inmcess:backpack` = 무작위 토큰)를 단다. 되써 넣을 때 그 칸에 **같은 토큰**이 있어야만 쓴다 —
 *   같은 사람 가방의 다른 칸으로 옮겨졌으면(F 패킷·`/모자` …) 그 칸을 따라가고, 가방 밖으로 나갔으면 쓰지 않고 닫는다(로그를 남김).
 * - **클릭·드래그가 적용되기 전에** 상자가 제자리에 있는지 본다 — 없으면 그 클릭을 취소하고 닫는다. 그래야 상자가 옮겨진 뒤 꺼낸 것이
 *   창(=상자)과 손 양쪽에 남지 않는다. 연 동안 손 바꾸기(F)는 상자를 쥔 손이면 막는다.
 * - 연 동안 그 칸은 못 만진다(클릭·숫자키·F·드래그 취소). 셜커 안에 셜커는 `InventoryType.SHULKER_BOX` 창의 바닐라 칸 규칙이 막는다.
 * - 다른 플러그인이 창 열기를 막으면 세션을 지우고 표를 떼고 받은 비용을 돌려준다.
 * - **바뀔 때마다**(클릭·드래그 다음 틱) 상자 아이템에 되써 넣는다. 닫기·나가기·죽기(떨어진 아이템에 써 넣음)·버리기·움직이기에 닫는다.
 */
class ShulkerBackpack(private val ess: Ess, private val feature: ItemsFeature) : Listener {

    /** 열린 배낭 하나. 창의 주인(holder) — 이것으로 우리 창을 알아본다. */
    class Session(val player: UUID, var slot: Int, val token: String, val editable: Boolean, val origin: Location) : InventoryHolder {
        lateinit var inv: Inventory
        var closed = false
        override fun getInventory(): Inventory = inv
    }

    private val sessions = HashMap<UUID, Session>()

    private val s: ShulkerSettings get() = feature.settings.shulker

    fun openCount(): Int = sessions.size

    /**
     * 배낭으로 열 셜커인가 — 재질이 셜커이고, 커스텀 아이템(inmc·MMOItems·ItemsAdder … 무엇이든 CustomItemHook/MMOItems 가 알아보는 것)이
     * 아닐 때(설정 shulker-backpack.allow-custom-items 면 커스텀도). 커스텀 셜커는 그 플러그인의 우클릭 능력·배낭과 다투지 않게 건드리지 않는다.
     */
    fun opens(item: ItemStack?): Boolean {
        if (!isShulker(item)) return false
        if (s.allowCustomItems) return true
        return ess.mmoItems.identify(item!!) == null && ess.customItems.identify(item) == null
    }

    /** [player] 의 열린 배낭을 지금 되써 넣고 닫는다(다른 기능이 그 사람의 가방을 고치기 전에 — 검색 지우기). */
    fun closeFor(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        finish(player, session, write = true)
    }

    /**
     * 열린 세션이 없는 열기 표를 뗀다 — 서버가 연 채로 꺼지면(크래시) 표가 아이템에 남는다. 남은 표는 `/인벤` 이 '열린 배낭' 으로 보고
     * 그 칸을 못 옮기게 하므로 들어올 때와 새로 열 때 떼어 낸다. 뗀 수.
     */
    fun stripStale(player: Player): Int {
        val active = sessions[player.uniqueId]?.token
        var n = 0
        for (inv in listOf(player.inventory, player.enderChest)) {
            for (i in 0 until inv.size) {
                val item = inv.getItem(i) ?: continue
                val token = token(item) ?: continue
                if (token == active) continue
                item.editMeta { it.persistentDataContainer.remove(KEY) }
                inv.setItem(i, item)
                n++
            }
        }
        return n
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val n = stripStale(event.player)
        if (n > 0) ess.logger.info("셜커 배낭: ${event.player.name} 의 아이템 ${n}개에 남은 열기 표를 뗐습니다(연 채로 서버가 꺼졌던 것)")
    }

    // --- 열기 ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val open = sessions[player.uniqueId]
        if (open != null && event.view.topInventory.holder === open) return onSessionClick(event, player, open)
        val settings = s
        if (!settings.enabled || event.isCancelled) return
        if (event.view.topInventory.type != InventoryType.CRAFTING || event.clickedInventory !is PlayerInventory) return
        if (player.gameMode == GameMode.CREATIVE) return // 창조 가방은 손님이 다룬다 — 서버 클릭과 어긋난다
        val wanted = if (settings.shiftOnly) event.click == ClickType.SHIFT_RIGHT else event.click == ClickType.RIGHT || event.click == ClickType.SHIFT_RIGHT
        if (!wanted) return
        val item = event.currentItem ?: return
        if (!opens(item) || !event.cursor.type.isAir) return
        if (!player.hasPermission(ItemsFeature.SHULKER)) return
        event.isCancelled = true
        val slot = event.slot
        // 클릭 처리 중에 창을 바꾸지 않는다 — 다음 틱에.
        Bukkit.getScheduler().runTask(ess.plugin, Runnable { if (player.isOnline) open(player, slot) })
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        val settings = s
        if (!settings.enabled || !settings.fromHand) return
        if (event.action != Action.RIGHT_CLICK_AIR || event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!opens(player.inventory.itemInMainHand) || !player.hasPermission(ItemsFeature.SHULKER)) return
        event.isCancelled = true
        open(player, player.inventory.heldItemSlot)
    }

    /** [slot] 의 셜커를 연다. 막히면 까닭을 알리고 false. */
    fun open(player: Player, slot: Int): Boolean {
        val settings = s
        if (sessions.containsKey(player.uniqueId)) return false
        stripStale(player) // 세션이 없으니 남은 표는 모두 낡은 것
        val item = player.inventory.getItem(slot) ?: return false
        if (!opens(item) || item.amount != 1) return false
        if (settings.preventInCombat && feature.combat.inCombat(player.uniqueId, feature.settings.combatSeconds)) {
            ess.messages.send(player, "items-shulker-combat")
            return false
        }
        if (settings.preventWhileMoving && (player.isSprinting || player.isGliding || player.isInsideVehicle)) {
            ess.messages.send(player, "items-shulker-moving")
            return false
        }
        val meta = item.itemMeta as? BlockStateMeta ?: return false
        val box = meta.blockState as? ShulkerBox ?: return false
        val currency = settings.currency.ifEmpty { null }
        var charged = 0.0
        if (settings.cost > 0.0 && !player.hasPermission(ItemsFeature.SHULKER_FREE) && ess.economy.isEnabled) {
            if (!ess.economy.withdraw(player, settings.cost, currency)) {
                ess.messages.send(player, "items-shulker-no-money", Ph.of().amount(ess.economy.format(settings.cost, currency)))
                return false
            }
            charged = settings.cost
        }
        val token = UUID.randomUUID().toString()
        item.editMeta { it.persistentDataContainer.set(KEY, PersistentDataType.STRING, token) }
        player.inventory.setItem(slot, item)
        val editable = !settings.requireEditPermission || player.hasPermission(ItemsFeature.SHULKER_EDIT)
        val session = Session(player.uniqueId, slot, token, editable, player.location)
        val title: Component = meta.displayName() ?: Component.text(VanillaNames.of(item.type) ?: "셜커 상자")
        session.inv = Bukkit.createInventory(session, InventoryType.SHULKER_BOX, title)
        session.inv.contents = box.inventory.contents
        sessions[player.uniqueId] = session
        player.openInventory(session.inv)
        if (player.openInventory.topInventory.holder !== session) {
            // 다른 플러그인(전투·구역)이 InventoryOpenEvent 를 막았다 — 없던 일로.
            session.closed = true
            sessions.remove(player.uniqueId)
            stripToken(player, session)
            if (charged > 0.0) ess.economy.deposit(player, charged, currency)
            return false
        }
        player.playSound(player.location, Sound.BLOCK_SHULKER_BOX_OPEN, SoundCategory.PLAYERS, 0.6f, 1.0f)
        if (!editable) ess.messages.send(player, "items-shulker-readonly")
        return true
    }

    // --- 연 동안 ---------------------------------------------------------------------------------------

    private fun onSessionClick(event: InventoryClickEvent, player: Player, session: Session) {
        if (!session.editable) { event.isCancelled = true; return }
        if (!locate(player, session)) { event.isCancelled = true; return lost(player, session) }
        val touchesBox = (event.clickedInventory is PlayerInventory && event.slot == session.slot) ||
            (event.click == ClickType.NUMBER_KEY && event.hotbarButton == session.slot) ||
            (event.click == ClickType.SWAP_OFFHAND && session.slot == OFFHAND_SLOT) ||
            (event.currentItem?.let { token(it) == session.token } == true) || token(event.cursor) == session.token
        if (touchesBox) { event.isCancelled = true; return }
        later(player, session)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.view.topInventory.holder !== session) return
        if (!locate(player, session)) { event.isCancelled = true; return lost(player, session) }
        val top = event.view.topInventory.size
        val touchesTop = event.rawSlots.any { it < top }
        val touchesBox = event.rawSlots.any { it >= top && event.view.convertSlot(it) == session.slot }
        if ((touchesTop && !session.editable) || touchesBox) { event.isCancelled = true; return }
        if (touchesTop) later(player, session)
    }

    /** 다음 틱에 되써 넣는다(클릭이 끝난 뒤의 칸을 읽는다). */
    private fun later(player: Player, session: Session) {
        Bukkit.getScheduler().runTask(ess.plugin, Runnable { if (!session.closed && !write(player, session)) finish(player, session, write = false) })
    }

    /**
     * 상자가 [Session.slot] 에 있는가. 같은 사람 가방의 다른 칸으로 옮겨졌으면 그 칸으로 고쳐 잡고 true.
     * 창의 내용은 바뀔 때마다 상자에 써 왔으니 옮겨진 상자도 창과 같다 — 따라가도 복제가 없다.
     */
    private fun locate(player: Player, session: Session): Boolean {
        val inv = player.inventory
        if (token(inv.getItem(session.slot)) == session.token) return true
        for (i in 0 until inv.size) {
            if (token(inv.getItem(i)) == session.token) { session.slot = i; return true }
        }
        return false
    }

    /** 상자가 가방 밖으로 나갔다 — 쓰지 않고 닫는다(이 클릭은 이미 취소됐다). */
    private fun lost(player: Player, session: Session) {
        ess.logger.warning("셜커 배낭: ${player.name} 의 상자가 연 동안 가방 밖으로 옮겨져 되써 넣지 않고 닫습니다(복제 막기) — 창 안의 " +
            session.inv.contents.count { it != null && !it.type.isAir } + "칸")
        // 클릭 처리 중에 창을 닫지 않는다 — 다음 틱에.
        Bukkit.getScheduler().runTask(ess.plugin, Runnable { finish(player, session, write = false) })
    }

    /** 창의 내용을 상자 아이템에. 상자를 못 찾으면 쓰지 않고 false(부른 쪽이 닫는다). */
    private fun write(player: Player, session: Session): Boolean {
        if (!locate(player, session)) {
            ess.logger.warning("셜커 배낭: ${player.name} 의 상자가 연 동안 가방 밖으로 옮겨져 되써 넣지 않고 닫습니다(복제 막기) — 창 안의 " +
                session.inv.contents.count { it != null && !it.type.isAir } + "칸")
            return false
        }
        val item = player.inventory.getItem(session.slot) ?: return false
        item.editMeta(BlockStateMeta::class.java) { meta ->
            val box = meta.blockState as ShulkerBox
            box.inventory.contents = session.inv.contents
            meta.blockState = box
        }
        player.inventory.setItem(session.slot, item)
        return true
    }

    /** 닫는다 — [write] 면 마지막으로 되써 넣고, 토큰을 뗀다. */
    private fun finish(player: Player, session: Session, write: Boolean, closeWindow: Boolean = true) {
        if (session.closed) return
        if (write && session.editable) write(player, session)
        session.closed = true
        sessions.remove(player.uniqueId)
        stripToken(player, session)
        if (closeWindow && player.openInventory.topInventory.holder === session) player.closeInventory()
    }

    private fun stripToken(player: Player, session: Session) {
        if (!locate(player, session)) return
        player.inventory.getItem(session.slot)?.takeIf { token(it) == session.token }?.let { item ->
            item.editMeta { it.persistentDataContainer.remove(KEY) }
            player.inventory.setItem(session.slot, item)
        }
    }

    /** 연 동안 상자를 쥔 손의 바꾸기(F)는 막는다 — 따라가기로 복제는 없지만 창 아래에서 상자가 움직이지 않게. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        val inv = event.player.inventory
        if (token(inv.itemInMainHand) == session.token || token(inv.itemInOffHand) == session.token) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = sessions[player.uniqueId] ?: return
        if (event.inventory.holder !== session || session.closed) return
        finish(player, session, write = true, closeWindow = false) // 닫히는 중 — 다시 닫지 않는다
        player.playSound(player.location, Sound.BLOCK_SHULKER_BOX_CLOSE, SoundCategory.PLAYERS, 0.6f, 1.0f)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        finish(event.player, session, write = true)
    }

    /** 죽으면 떨어질(또는 남을) 상자에 지금 내용을 써 넣고 닫는다. 떨어진 것 목록은 이미 만들어진 사본이라 거기에 직접 쓴다. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val session = sessions[player.uniqueId] ?: return
        if (session.editable) {
            for (drop in event.drops) {
                if (token(drop) != session.token) continue
                drop.editMeta(BlockStateMeta::class.java) { meta ->
                    val box = meta.blockState as ShulkerBox
                    box.inventory.contents = session.inv.contents
                    meta.blockState = box
                    meta.persistentDataContainer.remove(KEY)
                }
            }
        }
        finish(player, session, write = true)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrop(event: PlayerDropItemEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        if (token(event.itemDrop.itemStack) == session.token) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (sessions.isEmpty()) return
        val session = sessions[event.player.uniqueId] ?: return
        if (!s.preventWhileMoving) return
        val to = event.to
        if (to.world != session.origin.world || to.distanceSquared(session.origin) > s.moveTolerance * s.moveTolerance) {
            finish(event.player, session, write = true)
            ess.messages.send(event.player, "items-shulker-moved")
        }
    }

    /** 끌 때 — 열린 것 전부 닫는다(되써 넣고). */
    fun closeAll() {
        for (session in sessions.values.toList()) Bukkit.getPlayer(session.player)?.let { finish(it, session, write = true) }
        sessions.clear()
    }

    companion object {
        val KEY = NamespacedKey(Ess.NAMESPACE, "backpack")
        private const val OFFHAND_SLOT = 40

        fun isShulker(item: ItemStack?): Boolean =
            item != null && Tag.SHULKER_BOXES.isTagged(item.type) && item.itemMeta is BlockStateMeta

        fun token(item: ItemStack?): String? =
            item?.takeIf { !it.type.isAir && it.hasItemMeta() }?.itemMeta?.persistentDataContainer?.get(KEY, PersistentDataType.STRING)
    }
}

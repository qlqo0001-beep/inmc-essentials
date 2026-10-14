package com.inmc.essentials.inventory

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.VanillaNames
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.Container
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.loot.Lootable
import java.lang.reflect.Method
import java.util.UUID

/**
 * 조용히 상자 열기 `/몰래열기`(silentchest) — 카탈로그 cmd7:cmd-silentchest, 연구 silentchest-vanish 의 추천 (가).
 *
 * - 켜져 있거나(토글) **투명 중이면 저절로**(설정 silent-chest.auto-when-vanished) 상자·덫 상자·통·셜커를 우클릭하면 그 사건을 취소하고
 *   **복사본 창**을 연다 — 서버 안의 startOpen 이 불리지 않아 소리·뚜껑·통 상태·sculk 진동·덫 상자 신호·셜커 밀기·통계·피글린 분노가 없다.
 *   엔더 상자 블록은 자기 엔더를 연다. 팔 휘두르기도 막는다.
 * - 고치기는 `…silentchest.edit` 가 있을 때만(없으면 보기만). **통째로 덮어쓰지 않는다**: 기준 사본(base)을 두고 클릭 때 원본이 기준과 다르면
 *   그 클릭을 취소하고 다시 맞춘다. 맞으면 다음 틱에 **바뀐 칸만** 원본에 쓴다 — 그 한 틱 동안 그 상자의 깔때기 이동·다른 사람 클릭은 막는다.
 *   원본 쪽 변화(깔때기·다른 사람)는 2틱마다 사본에 옮긴다.
 * - 전리품이 아직 안 생긴 상자는 열지 않는다(관리자가 '약탈한 사람'이 된다). 셜커는 `SHULKER_BOX` 창(셜커 안에 셜커 금지 칸 규칙).
 * - CoreProtect 가 있으면 원본에 쓰기 직전 `logContainerTransaction(이름, 위치)` (리플렉션) — 감사 기록에 구멍이 나지 않게.
 * - 그 한 틱 사이를 끊는 것들: 같은 상자의 다른 조용히 열기 세션도 막고(먼저 쓴 쪽이 이긴다), 부수기·폭발·불타기는 **쓰고 나서** 닫는다
 *   (BlockBreakEvent·폭발 사건은 떨굼 전에 온다). 그래도 원본이 그새 바뀐 칸이 하나라도 있으면 **그 틱의 변화를 하나도 쓰지 않고** 보는
 *   사람 쪽에서 되돌린다([Reclaim] — 꺼낸 것·넣은 것을 맞비긴 뒤 꺼낸 것을 다시 가져오고 넣은 것을 돌려준다). 편집 창에서는 버리기·창 밖
 *   클릭을 막는다. 같은 그릇인지는 블록 자리로 본다(큰 상자). 쓴 뒤에는 사본을 원본에 다시 맞춘다(다른 칸이 낡은 채 남아 다음 쓰기에 덮지 않게).
 * - 앞선 리스너가 막은 클릭(영지 보호·무덤·urb 상자)은 건드리지 않는다. 다른 플러그인이 창 열기를 막으면 세션을 남기지 않는다.
 */
class SilentChest(private val ess: Ess, private val feature: InventoryFeature) : Listener {

    class Session(val viewer: UUID, val location: Location, val live: Inventory, val editable: Boolean) : InventoryHolder {
        lateinit var copy: Inventory
        var base: Array<ItemStack?> = emptyArray()
        var pending = false
        var closed = false
        var task: ScheduledTask? = null
        override fun getInventory(): Inventory = copy
    }

    private val toggled = HashSet<UUID>()
    private val sessions = HashMap<UUID, Session>()
    private val swing = HashMap<UUID, Int>()

    fun register() {
        val root = Commands.literal("몰래열기").requires { it.sender.hasPermission(InventoryFeature.SILENT) }
            .executes { ctx -> (ctx.source.sender as? Player)?.let { toggle(it, null) } ?: ess.messages.send(ctx.source.sender, "player-only"); 1 }
        for ((word, value) in listOf("켜기" to true, "on" to true, "끄기" to false, "off" to false)) {
            root.then(Commands.literal(word).executes { ctx -> (ctx.source.sender as? Player)?.let { toggle(it, value) } ?: ess.messages.send(ctx.source.sender, "player-only"); 1 })
        }
        ess.commands.add(root.build(), "상자를 소리·뚜껑 없이 열기(투명 중이면 저절로)", listOf("silentchest"))
    }

    fun toggle(player: Player, to: Boolean?) {
        val on = to ?: (player.uniqueId !in toggled)
        if (on) toggled += player.uniqueId else toggled -= player.uniqueId
        ess.messages.send(player, if (on) "inventory-silent-on" else "inventory-silent-off")
        if (!on && active(player)) ess.messages.send(player, "inventory-silent-still-vanished")
    }

    fun isToggled(id: UUID): Boolean = id in toggled

    /** 지금 조용히 열기인가(권한 + 토글 또는 투명). */
    fun active(player: Player): Boolean =
        player.hasPermission(InventoryFeature.SILENT) &&
            (player.uniqueId in toggled || (feature.settings.silentWhenVanished && Vanish.isVanished(player)))

    // --- 열기 ----------------------------------------------------------------------------------------

    /**
     * 앞선 리스너가 이미 막았거나 자기 창을 연 클릭(영지 보호 · invkeeper 무덤 · urb 상자 — 모두 사건을 취소한다)은 건드리지 않는다.
     * 그래서 보호 구역의 상자는 조용히 열기로도 열리지 않는다(우회 권한을 따로 두지 않았다 — 문서).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        if (event.useInteractedBlock() == Event.Result.DENY) return
        val player = event.player
        if (player.isSneaking && !player.inventory.itemInMainHand.type.isAir) return // 웅크리고 블록 놓기
        if (!active(player)) return
        val block = event.clickedBlock ?: return
        if (block.type == Material.ENDER_CHEST) {
            stop(event)
            swing[player.uniqueId] = Bukkit.getCurrentTick()
            player.openInventory(player.enderChest)
            return
        }
        val state = block.getState(false) as? Container ?: return
        if (state !is Chest && state !is Barrel && state !is ShulkerBox) return // 소리·뚜껑이 없는 그릇은 바닐라대로
        stop(event)
        swing[player.uniqueId] = Bukkit.getCurrentTick()
        if ((state as? Lootable)?.hasLootTable() == true) return ess.messages.send(player, "inventory-silent-loot")
        open(player, block, state)
    }

    private fun stop(event: PlayerInteractEvent) {
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        event.isCancelled = true
    }

    fun open(player: Player, block: Block, state: Container) {
        sessions.remove(player.uniqueId)?.let { close(it, apply = true) }
        val live = state.inventory
        val session = Session(player.uniqueId, block.location, live, player.hasPermission(InventoryFeature.SILENT_EDIT))
        val name = (VanillaNames.of(block.type) ?: block.type.name)
        val title = Component.text("조용히 · $name", NamedTextColor.DARK_GRAY)
        session.copy = if (state is ShulkerBox) Bukkit.createInventory(session, InventoryType.SHULKER_BOX, title)
        else Bukkit.createInventory(session, ((live.size + 8) / 9 * 9).coerceIn(9, 54), title)
        session.base = snapshot(live)
        session.copy.contents = clones(session.base, session.copy.size)
        sessions[player.uniqueId] = session
        player.openInventory(session.copy)
        if (player.openInventory.topInventory.holder !== session) {
            // 다른 플러그인(전투·구역)이 InventoryOpenEvent 를 막았다 — 세션·반복 작업을 남기지 않는다.
            session.closed = true
            sessions.remove(player.uniqueId)
            return
        }
        session.task = player.scheduler.runAtFixedRate(ess.plugin, { _ -> refresh(player, session) }, null, 2L, 2L)
        if (!session.editable) ess.messages.send(player, "inventory-silent-readonly")
    }

    /** 원본 쪽 변화를 사본에. 상자가 사라졌으면 닫는다. */
    private fun refresh(player: Player, session: Session) {
        if (session.closed) return
        if (!isContainer(session.location)) {
            ess.messages.send(player, "inventory-silent-gone")
            return close(session, apply = true) // 상자가 없으니 기다리던 변화는 되돌린다(apply 가 알아서)
        }
        if (session.pending) return
        val now = snapshot(session.live)
        if (same(now, session.base)) return
        if (same(snapshot(session.copy), session.base)) {
            session.base = now
            session.copy.contents = clones(now, session.copy.size)
        }
    }

    // --- 고치기 ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(event: InventoryClickEvent) {
        val session = event.view.topInventory.holder as? Session
        if (session != null) return guard(event.whoClicked as? Player, session, event)
        // 다른 사람이 같은 상자를 고치려는 그 한 틱 — 막는다.
        val top = event.view.topInventory
        if (sessions.values.any { it.pending && overlaps(it.live, top) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrag(event: InventoryDragEvent) {
        val session = event.view.topInventory.holder as? Session
        if (session != null) {
            if (event.rawSlots.none { it < event.view.topInventory.size }) return
            return guard(event.whoClicked as? Player, session, event)
        }
        val top = event.view.topInventory
        if (sessions.values.any { it.pending && overlaps(it.live, top) }) event.isCancelled = true
    }

    private fun guard(player: Player?, session: Session, event: org.bukkit.event.Cancellable) {
        if (player == null) return
        if (!session.editable) { event.isCancelled = true; return }
        // 버리기·창 밖 클릭은 막는다 — 꺼낸 것이 커서·가방을 떠나면 되돌릴 때 되찾지 못한다(복제).
        if (event is InventoryClickEvent && Reclaim.leaves(event)) { event.isCancelled = true; return }
        // 같은 상자의 다른 조용히 열기가 이 틱에 쓰려고 기다린다 — 그쪽이 쓴 뒤에(기준이 달라져 다시 맞춰진다).
        if (sessions.values.any { it !== session && it.pending && overlaps(it.live, session.live) }) { event.isCancelled = true; return }
        // 원본이 기준과 다르면(깔때기·다른 사람) 이 클릭은 낡은 화면 위의 것 — 취소하고 다시 맞춘다.
        if (!same(snapshot(session.live), session.base)) {
            event.isCancelled = true
            session.base = snapshot(session.live)
            session.copy.contents = clones(session.base, session.copy.size)
            ess.messages.send(player, "inventory-silent-resync")
            return
        }
        if (!session.pending) {
            session.pending = true
            Bukkit.getScheduler().runTask(ess.plugin, Runnable { apply(player, session) })
        }
    }

    /**
     * 사본에서 바뀐 칸을 원본에 — **전부 쓰거나 하나도 쓰지 않는다**. 원본이 그새 바뀐 칸이 하나라도 있거나 상자가 사라졌으면 아무것도
     * 쓰지 않고 그 틱의 변화 전부를 보는 사람 쪽에서 되돌린다(꺼낸 것·넣은 것을 맞비긴 뒤 — [Reclaim.undoAll]). 끝나면 사본을 원본에 맞춘다.
     */
    private fun apply(player: Player, session: Session) {
        if (!session.pending) return
        session.pending = false
        val copy = snapshot(session.copy)
        val changed = session.base.indices.filter { !same(copy.getOrNull(it), session.base[it]) }
        if (changed.isEmpty()) return
        val present = isContainer(session.location)
        val live = snapshot(session.live)
        val ok = present && changed.all { i -> i < session.live.size && same(live[i], session.base[i]) }
        if (ok) {
            CoreProtectLog.log(player.name, session.location)
            for (i in changed) session.live.setItem(i, copy[i]?.clone())
        } else {
            val missing = Reclaim.undoAll(player, changed.map { session.base[it] to copy.getOrNull(it) })
            ess.logger.warning("조용히 열기: ${player.name} 의 편집 중 원본과 어긋난 칸이 있어 ${changed.size}칸을 쓰지 않고 되돌렸습니다(${session.location})" +
                if (missing > 0) " — ${missing}개는 되찾지 못함" else "")
            ess.messages.send(player, "inventory-silent-resync")
        }
        session.base = snapshot(session.live)
        if (!session.closed) session.copy.contents = clones(session.base, session.copy.size)
    }

    /** 그 틱 동안 이 상자의 깔때기 이동을 막는다. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMove(event: InventoryMoveItemEvent) {
        if (sessions.isEmpty()) return
        if (sessions.values.any { it.pending && (overlaps(it.live, event.source) || overlaps(it.live, event.destination)) }) event.isCancelled = true
    }

    // --- 닫기 ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val session = event.inventory.holder as? Session ?: return
        if (session.closed) return
        if (session.pending) (event.player as? Player)?.let { apply(it, session) }
        session.closed = true
        session.task?.cancel()
        sessions.remove(session.viewer)
    }

    private fun close(session: Session, apply: Boolean) {
        val player = Bukkit.getPlayer(session.viewer)
        if (apply && session.pending && player != null) apply(player, session)
        session.closed = true
        session.task?.cancel()
        sessions.remove(session.viewer)
        if (player != null && player.openInventory.topInventory.holder === session) player.closeInventory()
    }

    /** 부서지기 전에(이 사건은 떨굼보다 먼저) 기다리던 쓰기를 마치고 닫는다 — 꺼낸 것이 떨굼에 또 섞이지 않게. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (sessions.isEmpty()) return
        closeAt(listOf(event.block))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (sessions.isEmpty()) return
        closeAt(event.blockList())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (sessions.isEmpty()) return
        closeAt(event.blockList() + event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (sessions.isEmpty()) return
        closeAt(listOf(event.block))
    }

    private fun closeAt(blocks: List<Block>) {
        for (session in sessions.values.toList()) {
            if (blocks.any { near(session.location, it.location) }) close(session, apply = true)
        }
    }

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        if (sessions.isEmpty()) return
        for (session in sessions.values.toList()) {
            val l = session.location
            if (l.world == event.world && l.blockX shr 4 == event.chunk.x && l.blockZ shr 4 == event.chunk.z) close(session, apply = true)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sessions[event.player.uniqueId]?.let { close(it, apply = true) }
        toggled.remove(event.player.uniqueId)
        swing.remove(event.player.uniqueId)
    }

    @EventHandler(ignoreCancelled = true)
    fun onSwing(event: PlayerAnimationEvent) {
        val at = swing[event.player.uniqueId] ?: return
        if (Bukkit.getCurrentTick() - at <= 1) event.isCancelled = true
    }

    fun closeAll() {
        for (session in sessions.values.toList()) close(session, apply = true)
    }

    private fun isContainer(location: Location): Boolean =
        location.isChunkLoaded && location.block.getState(false) is Container

    companion object {
        fun snapshot(inv: Inventory): Array<ItemStack?> = Array(inv.size) { inv.getItem(it)?.takeIf { s -> !s.type.isAir }?.clone() }

        fun clones(items: Array<ItemStack?>, size: Int): Array<ItemStack?> = Array(size) { items.getOrNull(it)?.clone() }

        fun same(a: ItemStack?, b: ItemStack?): Boolean {
            val x = a?.takeIf { !it.type.isAir }
            val y = b?.takeIf { !it.type.isAir }
            return if (x == null || y == null) x == null && y == null else x == y
        }

        fun same(a: Array<ItemStack?>, b: Array<ItemStack?>): Boolean =
            a.size == b.size && a.indices.all { same(a[it], b[it]) }

        /**
         * 같은 그릇인가 — **블록 자리로** 견준다. 큰 상자의 `Chest.getInventory()` 는 부를 때마다 새 CompoundContainer 를 감싸
         * `==` 가 같은 상자끼리도 거짓이다. 블록 그릇(상자·통·셜커 · 큰 상자 반쪽)끼리만 견준다 — 플레이어 가방·수레 상자는 자리가 겹쳐도 다른 것.
         */
        fun overlaps(a: Inventory, b: Inventory): Boolean {
            if (a === b) return true
            val x = blocks(a)
            if (x.isEmpty()) return false
            val y = blocks(b)
            return y.any { it in x }
        }

        private val BLOCK_TYPES = setOf(InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX)

        /** 블록 그릇의 자리들(`월드:x:y:z`). 블록 그릇이 아니면 빈 것. */
        fun blocks(inv: Inventory): Set<String> {
            if (inv.type !in BLOCK_TYPES) return emptySet()
            if (inv is DoubleChestInventory) return setOfNotNull(inv.leftSide.location?.let(::key), inv.rightSide.location?.let(::key))
            val holder = runCatching { inv.getHolder(false) }.getOrNull()
            if (holder !is Container && holder !is org.bukkit.block.DoubleChest) return emptySet()
            return setOfNotNull(inv.location?.let(::key))
        }

        private fun key(l: Location): String = "${l.world?.name}:${l.blockX}:${l.blockY}:${l.blockZ}"

        /** 같은 블록이거나 바로 옆(큰 상자의 다른 반쪽). */
        fun near(a: Location, b: Location): Boolean =
            a.world == b.world && kotlin.math.abs(a.blockX - b.blockX) + kotlin.math.abs(a.blockY - b.blockY) + kotlin.math.abs(a.blockZ - b.blockZ) <= 1
    }
}

/**
 * CoreProtect 감사 기록 — 리플렉션으로만(규칙 14). `CoreProtect.getAPI().logContainerTransaction(String, Location)`(23.x API, 연구 facts 로 확인).
 * 없거나 실패하면 조용히 false(편집 자체는 막지 않는다 — 막으면 CoreProtect 가 없는 서버에서 기능이 죽는다).
 */
object CoreProtectLog {

    @Volatile
    private var cached: Pair<Any, Method>? = null

    fun log(user: String, location: Location): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin("CoreProtect")?.takeIf { it.isEnabled } ?: return false
        return try {
            val (api, method) = cached ?: run {
                val api = plugin.javaClass.getMethod("getAPI").invoke(plugin) ?: return false
                val method = api.javaClass.getMethod("logContainerTransaction", String::class.java, Location::class.java)
                (api to method).also { cached = it }
            }
            method.invoke(api, user, location) as? Boolean ?: false
        } catch (_: Throwable) {
            cached = null
            false
        }
    }

    /** 검증기용 — CoreProtect 가 있으면 진입점을 찾을 수 있나. 없으면 null. */
    fun probe(): String? {
        val plugin = Bukkit.getPluginManager().getPlugin("CoreProtect")?.takeIf { it.isEnabled } ?: return null
        return try {
            val api = plugin.javaClass.getMethod("getAPI").invoke(plugin) ?: return "getAPI() 가 null(API 꺼짐 — CoreProtect config 의 api-enabled)"
            api.javaClass.getMethod("logContainerTransaction", String::class.java, Location::class.java)
            null
        } catch (t: Throwable) {
            "logContainerTransaction 을 찾지 못했습니다: $t"
        }
    }
}

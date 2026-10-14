package com.inmc.essentials.inventory

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * `/인벤`(inv, invsee) · `/엔더`(ender, ec, enderchest) · `/엔더비우기`(clearender) — 카탈로그 cmd3:cmd-inv · cmd2:cmd-ender · cmd1:inv-clearender.
 *
 * - **가방**(접속자): 54칸 사본 창([InvView]) — 핫바 · 가방 · 갑옷 넷 · 왼손이 다 보인다. 남의 PlayerInventory 를 그대로 열면 이 서버에서는
 *   4줄 상자(36칸)라 갑옷·왼손이 안 보이기 때문(지뢰 — javap 로 확인). 맞추기·되돌리기는 [InvView].
 * - **엔더**(접속자): 그 사람의 진짜 엔더 그릇(27칸 — 상자 창에 다 보인다)을 그대로 연다. 두 쪽이 같은 그릇을 고치므로 맞출 것이 없다.
 *   편집 권한이 없으면 클릭·드래그를 막는다(보기만). 대상이 나가면 **나가기 사건에서** 닫는다(저장된 뒤에 버려진 그릇을 고쳐 복제하지 않게).
 *
 * 접속하지 않은 사람은 **나갈 때 남긴 모습**(DB) — 편집·오프라인 권한이 있으면 고칠 수 있고 다음 접속 때 덮어쓴다([OfflineEdits]).
 * 남의 `.dat` 를 고쳐 쓰지는 않는다. `/엔더비우기` 도 접속하지 않은 사람에게는 같은 대기 편집이 된다.
 * `/엔더` 는 엔더 쪽부터 열고, 다른 쪽(가방↔엔더)으로 넘기는 단추는 그 쪽 권한(`inv` · `ender.others`)이 있을 때만.
 */
class InvCommands(private val ess: Ess, private val feature: InventoryFeature) : Listener {

    private enum class Kind { INVENTORY, ENDER }

    private class Session(val target: UUID, val inventory: Inventory, val kind: Kind, val editable: Boolean)

    private val sessions = HashMap<UUID, Session>()

    fun register() {
        ess.commands.add(
            Commands.literal("인벤").requires { it.sender.hasPermission(InventoryFeature.INV) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "inventory-inv-usage"); 1 }
                .then(ess.args.player("대상")
                    .executes { ctx -> openFor(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), null, Kind.INVENTORY); 1 }
                    .then(ess.args.player("보는사람").requires { it.sender.hasPermission(InventoryFeature.INV_OTHERS) }
                        .executes { ctx -> openFor(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), StringArgumentType.getString(ctx, "보는사람"), Kind.INVENTORY); 1 }))
                .build(),
            "남의 가방 보기·고치기(오프라인은 나갈 때 모습)", listOf("inv", "invsee"),
        )
        ess.commands.add(
            Commands.literal("엔더").requires { it.sender.hasPermission(InventoryFeature.ENDER) }
                .executes { ctx ->
                    val p = ctx.source.sender as? Player
                    if (p == null) ess.messages.send(ctx.source.sender, "player-only") else p.openInventory(p.enderChest)
                    1
                }
                .then(ess.args.player("대상").requires { it.sender.hasPermission(InventoryFeature.ENDER_OTHERS) }
                    .executes { ctx -> openFor(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), null, Kind.ENDER); 1 }
                    .then(ess.args.player("보는사람").requires { it.sender.hasPermission(InventoryFeature.INV_OTHERS) }
                        .executes { ctx -> openFor(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), StringArgumentType.getString(ctx, "보는사람"), Kind.ENDER); 1 }))
                .build(),
            "엔더 상자 열기(남의 것 포함)", listOf("ender", "ec", "enderchest"),
        )
        ess.commands.add(
            Commands.literal("엔더비우기").requires { it.sender.hasPermission(InventoryFeature.CLEAR_ENDER) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "inventory-clearender-usage"); 1 }
                .then(ess.args.player("대상")
                    .executes { ctx -> clearEnder(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), false); 1 }
                    .then(Commands.literal("-s").executes { ctx -> clearEnder(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), true); 1 }))
                .build(),
            "남의 엔더 상자 비우기", listOf("clearender"),
        )
    }

    private fun openFor(sender: CommandSender, targetName: String, viewerName: String?, kind: Kind) {
        val viewer = if (viewerName == null) sender as? Player ?: return ess.messages.send(sender, "player-only")
        else ess.args.online(sender, viewerName) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(viewerName))
        val s = feature.settings
        val online = ess.args.online(sender, targetName)
        if (online != null) {
            if (online == viewer) return ess.messages.send(sender, if (kind == Kind.ENDER) "inventory-ender-self" else "inventory-inv-self")
            if (online.name.lowercase() in s.blacklist && !sender.isOp) return ess.messages.send(sender, "inventory-blacklisted", Ph.of().player(online.name))
            val editPerm = if (kind == Kind.ENDER) InventoryFeature.ENDER_EDIT else InventoryFeature.INV_EDIT
            if (kind == Kind.ENDER) open(viewer, online.uniqueId, online.enderChest, kind, sender.hasPermission(editPerm))
            else if (feature.view.open(viewer, online, sender.hasPermission(editPerm)) == null) return
            ess.messages.send(sender, if (kind == Kind.ENDER) "inventory-ender-opened" else "inventory-inv-opened", Ph.of().player(online.name)
                .raw("{편집}", ess.messages.raw(if (sender.hasPermission(editPerm)) "inventory-mode-edit" else "inventory-mode-view")))
            if (sender.hasPermission(InventoryFeature.INV_LOCATION)) {
                val l = online.location
                ess.messages.send(sender, "inventory-location", Ph.of().player(online.name).world(l.world.name).value("${l.blockX}, ${l.blockY}, ${l.blockZ}"))
            }
            return
        }
        if (s.disableOffline) return ess.messages.send(sender, "player-not-found", Ph.of().value(targetName))
        if (!sender.hasPermission(InventoryFeature.INV_OFFLINE)) return ess.messages.send(sender, "player-not-found", Ph.of().value(targetName))
        val record = ess.args.offline(sender, targetName) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(targetName))
        // 접속 중이지만 보는 사람이 못 보는 투명인 사람 — 있다고 알리지 않는다.
        Bukkit.getPlayer(record.id)?.let { if (!Vanish.canSee(sender, it)) return ess.messages.send(sender, "player-not-found", Ph.of().value(targetName)) }
        if (record.name.lowercase() in s.blacklist && !sender.isOp) return ess.messages.send(sender, "inventory-blacklisted", Ph.of().player(record.name))
        // 편집 권한이 있으면 고칠 수 있는 창(다음 접속 때 덮어씀 — [OfflineEdits]), 없으면 보기만. 보기만 창에서 다른 쪽(가방↔엔더)으로 넘기는
        // 단추는 그 쪽 권한이 있을 때만 — /엔더 권한으로 가방을, /인벤 권한으로 엔더를 보지 않게.
        val editPerm = if (kind == Kind.ENDER) InventoryFeature.ENDER_EDIT else InventoryFeature.INV_EDIT
        val other = if (kind == Kind.ENDER) sender.hasPermission(InventoryFeature.INV) else sender.hasPermission(InventoryFeature.ENDER_OTHERS)
        val pendingKind = if (kind == Kind.ENDER) PendingKind.ENDER else PendingKind.INVENTORY
        feature.offline.open(sender, viewer, record, pendingKind, editable = sender.hasPermission(editPerm), both = other)
    }

    private fun open(viewer: Player, target: UUID, inventory: Inventory, kind: Kind, editable: Boolean) {
        viewer.openInventory(inventory)
        sessions[viewer.uniqueId] = Session(target, inventory, kind, editable)
    }

    private fun clearEnder(sender: CommandSender, name: String, silent: Boolean) {
        val target = ess.args.online(sender, name) ?: return clearEnderOffline(sender, name)
        val count = target.enderChest.contents.count { it != null && !it.type.isAir }
        target.enderChest.clear()
        ess.messages.send(sender, "inventory-clearender-done", Ph.of().player(target.name).count(count))
        if (!silent && target != sender) ess.messages.send(target, "inventory-clearender-target", Ph.of().player(sender.name))
    }

    /** 접속하지 않은 사람 — 빈 엔더를 대기 편집으로(다음 접속 때 덮어씀). 오프라인 권한이 있어야. */
    private fun clearEnderOffline(sender: CommandSender, name: String) {
        if (!sender.hasPermission(InventoryFeature.INV_OFFLINE) || feature.settings.disableOffline) {
            return ess.messages.send(sender, "inventory-clearender-offline", Ph.of().value(name))
        }
        val record = ess.args.offline(sender, name) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(name))
        // 접속 중이지만 보내는 사람이 못 보는 투명인 — 그 자리에서 비우되 알림은 접속하지 않은 사람과 같은 말로(접속 중임이 새지 않게).
        Bukkit.getPlayer(record.id)?.let { hidden ->
            val count = hidden.enderChest.contents.count { it != null && !it.type.isAir }
            hidden.enderChest.clear()
            ess.messages.send(sender, "inventory-offline-staged", Ph.of().player(record.name).raw("{종류}", feature.offline.kindName(PendingKind.ENDER)))
            return ess.messages.send(sender, "inventory-clearender-done", Ph.of().player(record.name).count(count))
        }
        feature.offline.stage(sender, record, mapOf(PendingKind.ENDER to arrayOfNulls(InvData.ENDER_SIZE))) { before, _ ->
            if (before == null) return@stage
            val count = before[PendingKind.ENDER]?.count { it != null } ?: 0
            ess.messages.send(sender, "inventory-offline-staged", Ph.of().player(record.name).raw("{종류}", feature.offline.kindName(PendingKind.ENDER)))
            ess.messages.send(sender, "inventory-clearender-done", Ph.of().player(record.name).count(count))
        }
    }

    // --- 보기만 · 나갈 때 닫기 -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val session = sessions[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory != session.inventory) return
        if (!session.editable) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val session = sessions[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory != session.inventory) return
        if (!session.editable) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        if (event.inventory == session.inventory) sessions.remove(event.player.uniqueId)
    }

    /** 대상이 나가면 그 그릇을 보던 창을 닫는다 — 저장된 뒤 버려진 그릇을 고치면 복제다. 보던 사람이 나가면 기록만 지운다. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        sessions.remove(id)
        for ((viewerId, session) in sessions.entries.toList()) {
            if (session.target != id) continue
            sessions.remove(viewerId)
            Bukkit.getPlayer(viewerId)?.let { viewer ->
                if (viewer.openInventory.topInventory == session.inventory) viewer.closeInventory()
                ess.messages.send(viewer, "inventory-target-left", Ph.of().player(event.player.name))
            }
        }
    }

    /** 검증기용 — 보기만 하는 세션을 걸고 그 창의 클릭이 막히는지. */
    internal fun probeReadOnly(viewer: Player): String? {
        val inventory = viewer.enderChest
        viewer.openInventory(inventory)
        sessions[viewer.uniqueId] = Session(viewer.uniqueId, inventory, Kind.ENDER, editable = false)
        try {
            val view = viewer.openInventory
            if (view.topInventory != inventory) return "엔더 창이 열리지 않았습니다"
            val event = InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 0,
                org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL)
            Bukkit.getPluginManager().callEvent(event)
            return if (!event.isCancelled) "보기만 하는 창의 클릭이 막히지 않았습니다" else null
        } finally {
            sessions.remove(viewer.uniqueId)
            viewer.closeInventory()
        }
    }

    companion object {
        fun date(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(at))
    }
}

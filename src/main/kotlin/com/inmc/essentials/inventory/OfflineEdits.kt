package com.inmc.essentials.inventory

import com.inmc.essentials.Ess
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.Icon
import kr.inmc.core.integration.Mail
import kr.inmc.core.integration.Vanish
import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.AtomicFiles
import kr.inmc.core.util.VanillaNames
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 접속하지 않은 사람의 가방·엔더 고치기(사용자 요구 2026-10-10 "복사본을 저장해 두고 그 사람이 들어올 때 덮어쓴다").
 *
 * 1. **모습**: 나갈 때(PlayerQuitEvent MONITOR)와 서버를 끌 때(disable — 끌 때는 나가기 사건이 오지 않는다) 가방 41칸·엔더 27칸·각 지문·
 *    월드·MV-Inventories 그룹을 남긴다([SavedInventories.snapshot]).
 * 2. **고치기**: 편집·오프라인 권한이 있으면 `/인벤`·`/엔더` 가 고칠 수 있는 창을 연다 — 내용은 **대기 편집이 있으면 그것, 없으면 나갈 때 모습**.
 *    바뀔 때마다(다음 틱) 대기 편집(DB `ess_inventory_pending` — 고친 내용 · 바탕 모습과 그 지문 · 고친 사람 · 시각)을 바로 쓴다.
 *    한 사람의 가방(또는 엔더)은 한 번에 한 관리자만 고친다(다른 사람은 보기만). 창에서 꺼낸 것은 관리자에게 간다 — 그래서 아래 적용이 믿을 만해야 한다.
 * 3. **적용**: 들어오면(PlayerJoinEvent MONITOR) 먼저 열린 편집 창을 저장하고 닫는다. MV-Inv 가 자기 프로필을 다 얹은 뒤(MV-Inventories 5.3.3 의
 *    `ShareHandleListener` 를 javap 로 확인: 접속 처리 `playerJoin` 은 우선순위 표시가 없어 Multiverse-Core 동적 리스너의 기본값 **NORMAL**, 세계 이동
 *    `playerChangedWorld` 는 `@DefaultEventPriority(LOW)` — 둘 다 Multiverse-Core 의 event-priority 설정(`EventPriorityMapper`)으로 바꿀 수 있다.
 *    프로필은 그 처리 안에서 바로 얹는다. 우리는 MONITOR 에서 DB 에 '적용하려 함' 표를 쓴 뒤 다음 틱 + [InventorySettings.applyDelayTicks])
 *    그 사람의 지금 그룹이 모습의 그룹과 같으면(또는 MV-Inv 가 없으면) 지금 가방을 대기 편집의 바탕과 견준다:
 *    - 같다(지문 또는 칸별 비교) → 고친 내용으로 **통째로 덮는다**.
 *    - 이미 고친 내용과 같다 → 이미 적용됨(지우기만).
 *    - 다르다(그새 무엇이 바꿨다) → **칸별 변화로** 얹는다: 관리자가 뺀 것은 아직 있을 때만(같은 종류·개수) 빼고, 넣은 것은 넣고(넘치면 우편),
 *      못 한 것은 `inventory/conflicts-<시각>-<이름>-<종류>.txt` 에 적고 권한 있는 관리자에게 알린다.
 *    적용한 그 걸음에 대기 편집을 DB 에서 지우고 플레이어 파일을 저장한다. '적용하려 함' 표가 남은 채 서버가 꺼졌으면 다음 접속 때 지금 가방이 바탕과
 *    같을 때만 덮고, 고친 내용과 같으면 지우기만, 그 밖은 적용하지 않고 충돌 파일로(두 번 적용하지 않는다).
 *    다른 MV 그룹에 있으면 기다렸다가 그 그룹 월드로 들어올 때(PlayerChangedWorldEvent) 적용한다.
 * 4. 가방과 엔더는 따로 기록하고 따로 적용한다. 대기 편집 위에 다시 고치면 그 내용에서 이어 고친다(바탕은 처음 모습 그대로).
 *    저장 인벤 불러오기·엔더 비우기를 접속하지 않은 사람에게 하면 같은 대기 편집이 된다([stage]). 접속 중이면 지금처럼 바로 고친다.
 */
class OfflineEdits(private val ess: Ess, private val feature: InventoryFeature) : Listener {

    enum class Mode { OVERWRITE, DELTA, ALREADY, AMBIGUOUS, DEFERRED }

    class Outcome(val mode: Mode, val lines: List<String>)

    /** 편집 창 하나 — 그 사람·종류의 잠금이기도 하다. */
    inner class Editor(
        val viewer: UUID, val viewerName: String, val owner: UUID, val ownerName: String, val kind: PendingKind,
        val base: ByteArray, val baseHash: String, val world: String?, val group: String?,
    ) : InventoryHolder {
        lateinit var copy: Inventory
        var saved: Array<ItemStack?> = emptyArray()
        var scheduled = false
        var closed = false
        override fun getInventory(): Inventory = copy
    }

    /** 대기 편집 — 열쇠 `uuid:종류`. 공용 DB 면 들어오기 직전(비동기)에 그 사람 것을 다시 읽어 넣으므로 동시 접근에 안전한 표. */
    private val pending = ConcurrentHashMap<String, PendingEdit>()
    private val editors = HashMap<String, Editor>()
    private val claiming = HashSet<String>()

    private val store: InventoryStore get() = feature.store

    private fun key(owner: UUID, kind: PendingKind) = "$owner:${kind.key}"

    /** 켤 때 — 전부 읽는다(기다린다). */
    fun load() {
        val rows = ess.db.call { store.readPending(null) }
        pending.clear()
        for (row in rows) pending[key(row.owner, row.kind)] = row
        if (rows.isNotEmpty()) ess.logger.info("오프라인 편집 대기 ${rows.size}건(다음 접속 때 적용)")
    }

    fun pendingFor(owner: UUID, kind: PendingKind): PendingEdit? = pending[key(owner, kind)]

    fun count(): Int = pending.size

    // --- 열기 ----------------------------------------------------------------------------------------

    /** [kind] 의 지금 모습 — 대기 편집이 있으면 그 내용, 없으면 나갈 때 모습. 둘 다 없으면 null. */
    fun latest(owner: UUID, kind: PendingKind, snap: Snapshot?): Array<ItemStack?>? {
        pendingFor(owner, kind)?.let { return InvData.decode(it.edited, kind.size) }
        val bytes = snap?.bytes(kind) ?: return null
        return InvData.decode(bytes, kind.size)
    }

    /**
     * 접속하지 않은 [record] 의 [kind] 를 [viewer] 에게 연다. [editable] 이고 잠겨 있지 않으면 고칠 수 있는 창, 아니면 보기만([SnapshotMenu]).
     * [both] 면 보기만 창에 다른 쪽(가방↔엔더)으로 넘기는 단추.
     */
    fun open(sender: CommandSender, viewer: Player, record: UserRecord, kind: PendingKind, editable: Boolean, both: Boolean) {
        ess.db.run("오프라인 가방 읽기") {
            val snap = store.readSnapshot(record.id)
            Bukkit.getScheduler().runTask(ess.plugin, Runnable { openRead(sender, viewer, record, kind, editable, both, snap) })
        }
    }

    private fun openRead(sender: CommandSender, viewer: Player, record: UserRecord, kind: PendingKind, editable: Boolean, both: Boolean, snap: Snapshot?) {
        if (!viewer.isOnline) return
        if (Bukkit.getPlayer(record.id) != null) return ess.messages.send(sender, "inventory-offline-now-online", Ph.of().player(record.name))
        val mine = latest(record.id, kind, snap) ?: return ess.messages.send(sender, "inventory-no-snapshot", Ph.of().player(record.name))
        val p = pendingFor(record.id, kind)
        val lock = editors[key(record.id, kind)]?.takeIf { !it.closed }
        if (editable && (lock == null || lock.viewer == viewer.uniqueId)) {
            val base = p?.base ?: snap?.bytes(kind) ?: return ess.messages.send(sender, "inventory-no-snapshot", Ph.of().player(record.name))
            val baseHash = p?.baseHash ?: snap?.hash(kind) ?: InvHash.of(InvData.decode(base, kind.size))
            val world = p?.world ?: snap?.world
            val group = if (p != null) p.group else snap?.group
            openEditor(sender, viewer, record, kind, mine, base, baseHash, world, group, snap)
            return
        }
        if (editable && lock != null) {
            ess.messages.send(sender, "inventory-offline-locked", Ph.of().player(record.name).value(lock.viewerName).raw("{종류}", kindName(kind)))
        }
        val items = if (kind == PendingKind.INVENTORY || both) latest(record.id, PendingKind.INVENTORY, snap) else null
        val ender = if (kind == PendingKind.ENDER || both) latest(record.id, PendingKind.ENDER, snap) else null
        val title = (if (kind == PendingKind.ENDER) "<dark_purple>" else "<dark_gray>") + record.name + " (오프라인)"
        SnapshotMenu(ess, viewer, title, items, ender, info(record.id, snap), startWithEnder = kind == PendingKind.ENDER).show()
        ess.messages.send(sender, "inventory-offline-opened", Ph.of().player(record.name).time(snap?.let { InvCommands.date(it.at) } ?: "-"))
    }

    private fun info(owner: UUID, snap: Snapshot?): List<String> = buildList {
        add("<gray>나갈 때: ${snap?.let { InvCommands.date(it.at) } ?: "-"}${snap?.world?.let { " · $it" } ?: ""}</gray>")
        for (kind in PendingKind.entries) pendingFor(owner, kind)?.let {
            add("<yellow>${kindName(kind)}: ${it.editorName} 의 편집이 다음 접속을 기다림(${InvCommands.date(it.at)})</yellow>")
            if (it.group != null && snap?.group != null && it.group != snap.group) add("<gray>  (MV 그룹 ${it.group} 에 들어오면 적용)</gray>")
        }
    }

    private fun openEditor(
        sender: CommandSender, viewer: Player, record: UserRecord, kind: PendingKind, contents: Array<ItemStack?>,
        base: ByteArray, baseHash: String, world: String?, group: String?, snap: Snapshot?,
    ) {
        val editor = Editor(viewer.uniqueId, viewer.name, record.id, record.name, kind, base, baseHash, world, group)
        val title = Component.text("${record.name} 의 ${kindName(kind)} · 오프라인 편집", NamedTextColor.DARK_RED)
        editor.copy = if (kind == PendingKind.INVENTORY) Bukkit.createInventory(editor, InvView.SIZE, title) else Bukkit.createInventory(editor, InvData.ENDER_SIZE, title)
        draw(editor, contents, snap)
        editor.saved = contents.map { it?.clone() }.toTypedArray()
        val k = key(record.id, kind)
        editors[k] = editor
        viewer.openInventory(editor.copy)
        if (viewer.openInventory.topInventory.holder !== editor) {
            editor.closed = true
            if (editors[k] === editor) editors.remove(k)
            return
        }
        ess.messages.send(sender, "inventory-offline-editing", Ph.of().player(record.name).raw("{종류}", kindName(kind))
            .time(snap?.let { InvCommands.date(it.at) } ?: "-"))
    }

    private fun draw(editor: Editor, contents: Array<ItemStack?>, snap: Snapshot?) {
        if (editor.kind == PendingKind.ENDER) {
            editor.copy.contents = Array(InvData.ENDER_SIZE) { contents.getOrNull(it)?.clone() }
            return
        }
        val out = arrayOfNulls<ItemStack>(InvView.SIZE)
        for (v in 0 until InvView.SIZE) {
            val t = InvView.toTarget(v)
            out[v] = when {
                t >= 0 -> contents.getOrNull(t)?.clone()
                v == 41 -> Icon.of(Material.BLACK_STAINED_GLASS_PANE, "<gray>← 머리 · 몸 · 다리 · 발 · 왼손</gray>")
                v == SLOT_INFO -> Icon.of(Material.BOOK, "<gold>오프라인 편집</gold>", info(editor.owner, snap) +
                    listOf("", "<gray>고친 것은 바로 저장되고</gray>", "<gray>${editor.ownerName} 이(가) 다음에 들어올 때 덮어씁니다.</gray>"))
                else -> Icon.FILLER
            }
        }
        editor.copy.contents = out
    }

    /** 창 → 대상 칸 순서. */
    private fun read(editor: Editor): Array<ItemStack?> = Array(editor.kind.size) { t ->
        val view = if (editor.kind == PendingKind.INVENTORY) InvView.toView(t) else t
        editor.copy.getItem(view)?.takeIf { !it.type.isAir }?.clone()
    }

    // --- 편집 창 ---------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(event: InventoryClickEvent) {
        val editor = event.view.topInventory.holder as? Editor ?: return
        val player = event.whoClicked as? Player ?: return
        if (editor.closed) { event.isCancelled = true; return }
        // 칸막이·정보 칸은 못 만지고, 모으기(두 번 클릭)는 칸막이 유리를 끌어오지 않게 막는다.
        if (event.action == InventoryAction.COLLECT_TO_CURSOR) { event.isCancelled = true; return }
        if (event.clickedInventory === event.view.topInventory && editor.kind == PendingKind.INVENTORY && InvView.toTarget(event.slot) < 0) {
            event.isCancelled = true
            return
        }
        later(editor, player)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrag(event: InventoryDragEvent) {
        val editor = event.view.topInventory.holder as? Editor ?: return
        val player = event.whoClicked as? Player ?: return
        if (editor.closed) { event.isCancelled = true; return }
        val top = event.rawSlots.filter { it < event.view.topInventory.size }
        if (top.isEmpty()) return
        if (editor.kind == PendingKind.INVENTORY && top.any { InvView.toTarget(it) < 0 }) { event.isCancelled = true; return }
        later(editor, player)
    }

    private fun later(editor: Editor, player: Player) {
        if (editor.scheduled) return
        editor.scheduled = true
        Bukkit.getScheduler().runTask(ess.plugin, Runnable {
            editor.scheduled = false
            if (!editor.closed) save(editor, player)
        })
    }

    /** 창의 지금 내용을 대기 편집으로(바뀌었으면). 갑옷 칸에 맞지 않는 것은 관리자에게 돌려준다. */
    private fun save(editor: Editor, viewer: Player) {
        val now = read(editor)
        if (editor.kind == PendingKind.INVENTORY) {
            for (t in 36..39) {
                val item = now[t] ?: continue
                if (InvView.fits(t, item)) continue
                now[t] = null
                editor.copy.setItem(InvView.toView(t), null)
                for (rest in viewer.inventory.addItem(item).values) viewer.world.dropItemNaturally(viewer.location, rest)
            }
        }
        if (SilentChest.same(now, editor.saved)) return
        editor.saved = now.map { it?.clone() }.toTypedArray()
        commit(editor.owner, editor.ownerName, editor.kind, editor.base, editor.baseHash, editor.world, editor.group, now, viewer.uniqueId, viewer.name)
    }

    /**
     * 대기 편집을 [edited] 로 — 메모리에 넣고 DB 에 바로 쓴다(규칙 5 — 아이템 기록은 묶지 않는다). 바탕과 같아졌으면(되돌림) 지운다.
     * 이미 있으면 그 바탕·지문·그룹은 그대로(처음 모습 기준).
     */
    private fun commit(
        owner: UUID, name: String, kind: PendingKind, base: ByteArray, baseHash: String, world: String?, group: String?,
        edited: Array<ItemStack?>, editorId: UUID?, editorName: String,
    ) {
        val k = key(owner, kind)
        val existing = pending[k]
        val baseBytes = existing?.base ?: base
        if (SilentChest.same(edited, InvData.decode(baseBytes, kind.size))) {
            if (pending.remove(k) != null) ess.db.run("오프라인 편집 지우기") { store.deletePending(owner, kind) }
            return
        }
        val bytes = InvData.encode(edited)
        val now = System.currentTimeMillis()
        val rec = existing?.also { it.edited = bytes; it.editor = editorId; it.editorName = editorName; it.at = now }
            ?: PendingEdit(owner, name, kind, base, baseHash, bytes, world, group, editorId, editorName, now, false).also { pending[k] = it }
        val row = PendingEdit(rec.owner, rec.name, rec.kind, rec.base, rec.baseHash, rec.edited, rec.world, rec.group, rec.editor, rec.editorName, rec.at, rec.claimed)
        ess.db.run("오프라인 편집 저장") { store.upsertPending(row) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val editor = event.inventory.holder as? Editor ?: return
        if (editor.closed) return
        (event.player as? Player)?.let { save(editor, it) }
        release(editor)
    }

    private fun release(editor: Editor) {
        editor.closed = true
        val k = key(editor.owner, editor.kind)
        if (editors[k] === editor) editors.remove(k)
    }

    /** 저장하고 닫는다(대상이 들어옴 · 끌 때). */
    private fun closeEditor(editor: Editor, reason: String?) {
        if (editor.closed) return
        val viewer = Bukkit.getPlayer(editor.viewer)
        if (viewer != null) save(editor, viewer)
        release(editor)
        if (viewer != null && viewer.openInventory.topInventory.holder === editor) viewer.closeInventory()
        if (viewer != null && reason != null) ess.messages.send(viewer, reason, Ph.of().player(editor.ownerName).raw("{종류}", kindName(editor.kind)))
    }

    fun closeAll() {
        for (editor in editors.values.toList()) closeEditor(editor, null)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        for (editor in editors.values.toList()) if (editor.viewer == event.player.uniqueId) closeEditor(editor, null)
    }

    // --- 다른 명령에서(불러오기 · 엔더 비우기) -----------------------------------------------------------------

    /**
     * 접속하지 않은 [owner] 의 [contents] 종류들을 대기 편집으로(다음 접속 때 덮어씀). 누가 그 창을 고치는 중이면 거절.
     * 기준은 지금의 대기 편집(있으면) 또는 나갈 때 모습 — 둘 다 없으면 거절. 끝나면 메인에서 [done](바꾸기 전의 모습 — 실패면 null).
     */
    fun stage(
        sender: CommandSender, owner: UserRecord, contents: Map<PendingKind, Array<ItemStack?>>,
        done: (Map<PendingKind, Array<ItemStack?>>?, Snapshot?) -> Unit,
    ) {
        for (kind in contents.keys) {
            val lock = editors[key(owner.id, kind)]?.takeIf { !it.closed } ?: continue
            ess.messages.send(sender, "inventory-offline-busy", Ph.of().player(owner.name).value(lock.viewerName).raw("{종류}", kindName(kind)))
            return done(null, null)
        }
        ess.db.run("오프라인 편집 준비") {
            val snap = store.readSnapshot(owner.id)
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                if (Bukkit.getPlayer(owner.id) != null) {
                    ess.messages.send(sender, "inventory-offline-now-online", Ph.of().player(owner.name))
                    return@Runnable done(null, snap)
                }
                val before = HashMap<PendingKind, Array<ItemStack?>>()
                for (kind in contents.keys) {
                    before[kind] = latest(owner.id, kind, snap) ?: run {
                        ess.messages.send(sender, "inventory-no-snapshot", Ph.of().player(owner.name))
                        return@Runnable done(null, snap)
                    }
                    if (editors[key(owner.id, kind)]?.takeIf { !it.closed } != null) {
                        ess.messages.send(sender, "inventory-offline-busy", Ph.of().player(owner.name).value("?").raw("{종류}", kindName(kind)))
                        return@Runnable done(null, snap)
                    }
                }
                val by = (sender as? Player)?.uniqueId
                for ((kind, items) in contents) {
                    val p = pendingFor(owner.id, kind)
                    val base = p?.base ?: snap?.bytes(kind) ?: continue
                    val hash = p?.baseHash ?: snap?.hash(kind) ?: InvHash.of(InvData.decode(base, kind.size))
                    commit(owner.id, owner.name, kind, base, hash, p?.world ?: snap?.world, if (p != null) p.group else snap?.group, items, by, sender.name)
                }
                done(before, snap)
            })
        }
    }

    // --- 접속 때 적용 ------------------------------------------------------------------------------------

    /** 공용 DB — 다른 서버에서 고친 대기 편집을 들어오기 직전에 다시 읽는다(비동기 사건이라 기다려도 된다). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (!ess.config.storage.mysql || event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
        val rows = runCatching { ess.db.call { store.readPending(event.uniqueId) } }.getOrElse {
            ess.logger.warning("오프라인 편집을 다시 읽지 못했습니다(${event.name}): $it")
            return
        }
        for (kind in PendingKind.entries) {
            val row = rows.firstOrNull { it.kind == kind }
            if (row == null) pending.remove(key(event.uniqueId, kind)) else pending[key(event.uniqueId, kind)] = row
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        for (kind in PendingKind.entries) editors[key(player.uniqueId, kind)]?.let { closeEditor(it, "inventory-offline-target-joined") }
        schedule(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorld(event: PlayerChangedWorldEvent) {
        // 들어온 월드의 MV 그룹 열쇠를 기억해 둔다 — 서버를 끌 때 MV-Inv 가 먼저 꺼져도 disable() 의 모습에 그룹이 남게([MvInvGroups]).
        MvInvGroups.of(event.player.world.name)
        if (pending.isEmpty()) return
        schedule(event.player)
    }

    /**
     * 적용할 것이 있으면: DB 에 '적용하려 함' 표를 쓰고(쓰기 스레드) → 끝나면 메인에서 [InventorySettings.applyDelayTicks] 틱 뒤에(MV-Inv 가
     * 자기 프로필을 다 얹은 뒤) 적용한다. 그사이 나가거나 그룹이 바뀌면 표를 되돌린다.
     */
    private fun schedule(player: Player) {
        val id = player.uniqueId
        val group = MvInvGroups.of(player.world.name)
        val due = PendingKind.entries.mapNotNull { pendingFor(id, it) }
            .filter { key(id, it.kind) !in claiming && MvInvGroups.matches(it.group, group) }
        if (due.isEmpty()) return
        val kinds = due.map { it.kind }
        kinds.forEach { claiming += key(id, it) }
        val delay = feature.settings.applyDelayTicks.toLong()
        ess.db.run("오프라인 편집 적용 표시") {
            val ok = runCatching { kinds.forEach { store.markPendingClaimed(id, it, true) } }
                .onFailure { ess.logger.warning("오프라인 편집 적용 표시를 쓰지 못해 다음 접속으로 미룹니다($id): $it") }.isSuccess
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                if (!ok) { kinds.forEach { claiming -= key(id, it) }; return@Runnable }
                val now = Bukkit.getPlayer(id) ?: return@Runnable release(id, kinds)
                now.scheduler.runDelayed(ess.plugin, { _ ->
                    val target = Bukkit.getPlayer(id)
                    for (kind in kinds) {
                        val out = target?.let { applyNow(it, kind, MvInvGroups.of(it.world.name), persist = true) }
                        if (out == null || out.mode == Mode.DEFERRED) release(id, listOf(kind)) else claiming -= key(id, kind)
                    }
                }, { release(id, kinds) }, delay)
            })
        }
    }

    /** 적용하지 못하고 미룬 것 — 잡아 둔 것을 풀고 DB 표를 되돌린다(전에 이미 표가 있던 것은 그대로). */
    private fun release(id: UUID, kinds: List<PendingKind>) {
        for (kind in kinds) {
            claiming -= key(id, kind)
            val rec = pendingFor(id, kind) ?: continue
            if (!rec.claimed) ess.db.run("오프라인 편집 적용 표시 되돌리기") { store.markPendingClaimed(id, kind, false) }
        }
    }

    /**
     * [player] 의 [kind] 대기 편집을 지금 적용한다. 없으면 null. [currentGroup] 이 기록의 그룹과 다르면 DEFERRED(기록은 남는다).
     * [persist] = 실제 적용(DB 지우기 · 플레이어 파일 저장 · 충돌 파일 · 알림) — 검증기는 false(메모리만).
     */
    internal fun applyNow(player: Player, kind: PendingKind, currentGroup: String?, persist: Boolean): Outcome? {
        val k = key(player.uniqueId, kind)
        val rec = pending[k] ?: return null
        if (!MvInvGroups.matches(rec.group, currentGroup)) return Outcome(Mode.DEFERRED, emptyList())
        val current = read(player, kind)
        val base = InvData.decode(rec.base, kind.size)
        val edited = InvData.decode(rec.edited, kind.size)
        val mode = decide(
            hashEqual = InvHash.of(current) == rec.baseHash,
            baseEqual = SilentChest.same(current, base),
            editedEqual = SilentChest.same(current, edited),
            wasClaimed = rec.claimed,
        )
        val lines = ArrayList<String>()
        var mailed = false
        when (mode) {
            Mode.OVERWRITE -> for (i in 0 until kind.size) write(player, kind, i, edited[i]?.clone())
            Mode.DELTA -> {
                val ops = InvDelta.diff(base.toList(), edited.toList(), { a, b -> SilentChest.same(a, b) }, { a, b -> a.isSimilar(b) }, { it.amount }, ::withAmount)
                val applied = InvDelta.apply(
                    current.toList(), ops, { a, b -> a.isSimilar(b) }, { it.amount }, ::withAmount, { it.maxStackSize },
                    fits = { slot, item -> kind != PendingKind.INVENTORY || InvView.fits(slot, item) },
                    storage = { slot -> kind == PendingKind.ENDER || slot in 0..35 },
                )
                for (i in 0 until kind.size) if (!SilentChest.same(applied.slots[i], current[i])) write(player, kind, i, applied.slots[i]?.clone())
                for ((slot, item) in applied.notRemoved) lines += "빼지 못함(${slot}칸 — 이미 없거나 모자람): ${label(item)} x${item.amount}"
                if (applied.leftovers.isNotEmpty()) {
                    mailed = persist && Mail.available && Mail.send(player.uniqueId, ess.plugin.name, "관리자 오프라인 편집 (${rec.editorName})", applied.leftovers)
                    if (persist && !mailed) for (left in applied.leftovers) player.world.dropItemNaturally(player.location, left)
                    for (left in applied.leftovers) lines += "자리가 없어 ${if (mailed) "우편으로" else "발밑에"}: ${label(left)} x${left.amount}"
                }
            }
            Mode.AMBIGUOUS -> {
                lines += "적용하려던 중 서버가 꺼진 기록입니다 — 지금 가방이 바탕과도 고친 내용과도 달라 적용하지 않았습니다(두 번 적용 막기). 아래 변화를 손으로 맞추세요."
                val ops = InvDelta.diff(base.toList(), edited.toList(), { a, b -> SilentChest.same(a, b) }, { a, b -> a.isSimilar(b) }, { it.amount }, ::withAmount)
                for ((slot, item) in ops.removed) lines += "관리자가 뺀 것(${slot}칸): ${label(item)} x${item.amount}"
                for ((slot, item) in ops.added) lines += "관리자가 넣은 것(${slot}칸): ${label(item)} x${item.amount}"
            }
            Mode.ALREADY, Mode.DEFERRED -> {}
        }
        // 두 번 적용하지 않게 — 적용한 그 걸음에 지운다(메모리 + DB). 플레이어 파일도 바로 저장해 둔다.
        pending.remove(k)
        if (persist) {
            if (mode == Mode.OVERWRITE || mode == Mode.DELTA) runCatching { player.saveData() }
            ess.db.run("오프라인 편집 적용 끝") { store.deletePending(player.uniqueId, kind) }
            report(player, rec, mode, lines)
        }
        return Outcome(mode, lines)
    }

    private fun report(player: Player, rec: PendingEdit, mode: Mode, lines: List<String>) {
        val kindText = kindName(rec.kind)
        if (mode == Mode.OVERWRITE || mode == Mode.DELTA) ess.messages.send(player, "inventory-offline-applied-target", Ph.of().raw("{종류}", kindText))
        rec.editor?.let { Bukkit.getPlayer(it) }?.takeIf { it != player && Vanish.canSee(it, player) }?.let {
            ess.messages.send(it, "inventory-offline-applied", Ph.of().player(player.name).raw("{종류}", kindText).value(modeText(mode)))
        }
        ess.logger.info("오프라인 편집 적용: ${player.name} 의 $kindText — ${modeText(mode)} (편집 ${rec.editorName})")
        if (lines.isEmpty()) return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = ess.io.file("inventory", "conflicts-$stamp-${player.name}-${rec.kind.key}.txt")
        val body = buildString {
            appendLine("# 오프라인 편집 충돌 — ${player.name} (${player.uniqueId}) · $kindText · 편집 ${rec.editorName} (${InvCommands.date(rec.at)})")
            appendLine("# 방식: ${modeText(mode)} · 적용 시각 ${InvCommands.date(System.currentTimeMillis())} · 월드 ${player.world.name}")
            lines.forEach { appendLine(it) }
        }
        ess.io.asyncRun { AtomicFiles.write(file, body) }
        ess.logger.warning("오프라인 편집 충돌 ${lines.size}줄 — ${file.path}")
        for (admin in Bukkit.getOnlinePlayers()) {
            if (!admin.hasPermission(InventoryFeature.INV) || !Vanish.canSee(admin, player)) continue
            ess.messages.send(admin, "inventory-offline-conflict", Ph.of().player(player.name).raw("{종류}", kindText).value(file.name))
        }
    }

    // --- 검증기용(메모리만) ---------------------------------------------------------------------------------

    /** 메모리에만 대기 편집을 넣는다(DB 에 쓰지 않음). */
    internal fun putMemory(rec: PendingEdit) { pending[key(rec.owner, rec.kind)] = rec }

    internal fun removeMemory(owner: UUID, kind: PendingKind) { pending.remove(key(owner, kind)) }

    // --- 도움 -------------------------------------------------------------------------------------------

    private fun read(player: Player, kind: PendingKind): Array<ItemStack?> = Array(kind.size) { i ->
        val item = if (kind == PendingKind.INVENTORY) player.inventory.getItem(i) else player.enderChest.getItem(i)
        item?.takeIf { !it.type.isAir && !it.isEmpty }?.clone()
    }

    private fun write(player: Player, kind: PendingKind, slot: Int, item: ItemStack?) {
        if (kind == PendingKind.INVENTORY) player.inventory.setItem(slot, item) else player.enderChest.setItem(slot, item)
    }

    fun kindName(kind: PendingKind): String = ess.messages.raw(if (kind == PendingKind.INVENTORY) "inventory-kind-inv" else "inventory-kind-ender")

    /** 여러 종류를 한 글로("가방 · 엔더 상자") — 오프라인 알림과 못 보는 투명인에게의 알림이 **같은 글**이어야 한다(규칙 8). */
    fun kindsName(kinds: Collection<PendingKind>): String = PendingKind.entries.filter { it in kinds }.joinToString(" · ") { kindName(it) }

    private fun modeText(mode: Mode): String = when (mode) {
        Mode.OVERWRITE -> "그대로 덮어씀"
        Mode.DELTA -> "그새 바뀌어 칸별로 얹음"
        Mode.ALREADY -> "이미 적용돼 있음"
        Mode.AMBIGUOUS -> "적용 여부를 알 수 없어 적용하지 않음"
        Mode.DEFERRED -> "다른 MV 그룹이라 미룸"
    }

    companion object {
        private const val SLOT_INFO = 49

        /** 적용 방식(순수). 지문이나 칸별 비교로 바탕과 같으면 덮기 · 고친 내용과 같으면 이미 적용 · 전에 '적용하려 함' 표가 있었으면 손대지 않음 · 그 밖은 칸별. */
        fun decide(hashEqual: Boolean, baseEqual: Boolean, editedEqual: Boolean, wasClaimed: Boolean): Mode = when {
            hashEqual || baseEqual -> Mode.OVERWRITE
            editedEqual -> Mode.ALREADY
            wasClaimed -> Mode.AMBIGUOUS
            else -> Mode.DELTA
        }

        private fun withAmount(stack: ItemStack, n: Int): ItemStack = stack.clone().also { it.amount = n }

        fun label(stack: ItemStack): String = StoredItem.plainName(stack) ?: VanillaNames.of(stack.type) ?: stack.type.key.value()
    }
}

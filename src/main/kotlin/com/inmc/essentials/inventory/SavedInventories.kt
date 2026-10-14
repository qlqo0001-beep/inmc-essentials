package com.inmc.essentials.inventory

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.gui.Icon
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 저장 인벤 — `/인벤저장`(invsave) · `/인벤불러오기`(invload) · `/인벤목록`(invlist, 번호를 주면 미리보기 = CMI invcheck) · `/인벤삭제`(invremove — all = invremoveall)
 * 와 죽을 때 자동 저장(CMI inv.SaveOnDeath). 카탈로그 mod:sys-savedinv. 저장은 **그 순간 DB 로**(규칙 5 — 아이템 기록은 묶지 않는다).
 *
 * 번호는 그 사람의 저장 목록에서 **새것부터 1, 2, …**(`last` = 1)를 칠 수 있고, 목록의 [보기]·[불러오기]·[지우기] 와 미리보기 단추는
 * **DB id**(`#123`)를 쓴다 — 목록을 본 뒤 죽음 저장·백업이 끼어들어 번호가 밀려도 본 그것을 고른다(남의 id 는 거절).
 * 불러오기는 받는 사람의 지금 가방을 **같은 틱에** 그대로(금지 아이템 거르지 않고) 잡아 두고 덮은 뒤 백업으로 쓴다(설정 saved.backup-before-load)
 * — CMI 는 그냥 덮는다. 백업 쓰기가 실패하면 그 바이트를 `inventory-backups/` 파일로 남긴다. 백업은 keep-per-player 를 따로 센다
 * (백업이 직접 저장·죽음 저장을 밀어내지 않는다) · 불러온 그 저장은 그때 지우지 않는다.
 */
class SavedInventories(private val ess: Ess, private val feature: InventoryFeature) : Listener {

    private val store: InventoryStore get() = feature.store
    private val s: SavedSettings get() = feature.settings.saved

    fun register() {
        ess.commands.add(
            Commands.literal("인벤저장").requires { it.sender.hasPermission(InventoryFeature.SAVE) }
                .executes { ctx -> (ctx.source.sender as? Player)?.let { saveOne(ctx.source.sender, it) } ?: ess.messages.send(ctx.source.sender, "player-only"); 1 }
                .then(ess.args.players("대상").suggests(TARGETS)
                    .executes { ctx -> save(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), null); 1 }
                    .then(Commands.argument("일", IntegerArgumentType.integer(1, 3650))
                        .executes { ctx -> save(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), IntegerArgumentType.getInteger(ctx, "일")); 1 }))
                .build(),
            "가방 저장(* 접속자 전원 · ** 최근 접속자 — 오프라인은 나갈 때 모습)", listOf("invsave"),
        )
        ess.commands.add(
            Commands.literal("인벤목록").requires { it.sender.hasPermission(InventoryFeature.LIST) }
                .executes { ctx -> (ctx.source.sender as? Player)?.let { list(ctx.source.sender, it.name) } ?: ess.messages.send(ctx.source.sender, "inventory-list-usage"); 1 }
                .then(ess.args.player("주인")
                    .executes { ctx -> list(ctx.source.sender, StringArgumentType.getString(ctx, "주인")); 1 }
                    .then(Commands.argument("번호", Args.TOKEN).suggests(NUMBERS)
                        .executes { ctx -> preview(ctx.source.sender, StringArgumentType.getString(ctx, "주인"), StringArgumentType.getString(ctx, "번호")); 1 }))
                .build(),
            "저장 인벤 목록 · 번호를 주면 미리보기", listOf("invlist"),
        )
        ess.commands.add(
            Commands.literal("인벤불러오기").requires { it.sender.hasPermission(InventoryFeature.LOAD) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "inventory-load-usage"); 1 }
                .then(ess.args.player("주인")
                    .executes { ctx -> load(ctx.source.sender, StringArgumentType.getString(ctx, "주인"), "last", null); 1 }
                    .then(Commands.argument("번호", Args.TOKEN).suggests(NUMBERS)
                        .executes { ctx -> load(ctx.source.sender, StringArgumentType.getString(ctx, "주인"), StringArgumentType.getString(ctx, "번호"), null); 1 }
                        .then(ess.args.player("받을사람")
                            .executes { ctx ->
                                load(ctx.source.sender, StringArgumentType.getString(ctx, "주인"), StringArgumentType.getString(ctx, "번호"), StringArgumentType.getString(ctx, "받을사람"))
                                1
                            })))
                .build(),
            "저장 인벤 되돌리기(주인 또는 다른 사람에게)", listOf("invload"),
        )
        ess.commands.add(
            Commands.literal("인벤삭제").requires { it.sender.hasPermission(InventoryFeature.REMOVE) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "inventory-remove-usage"); 1 }
                .then(ess.args.player("주인")
                    .then(Commands.argument("번호", Args.TOKEN).suggests(NUMBERS_ALL)
                        .executes { ctx -> remove(ctx.source.sender, StringArgumentType.getString(ctx, "주인"), StringArgumentType.getString(ctx, "번호")); 1 }))
                .build(),
            "저장 인벤 지우기(번호 · last · all)", listOf("invremove"),
        )
    }

    // --- 저장 ----------------------------------------------------------------------------------------

    private fun save(sender: CommandSender, target: String, days: Int?) {
        val many = target.trim().lowercase() in setOf("*", "all", "전원", "**")
        if (many && !sender.hasPermission(InventoryFeature.SAVE_OTHERS)) return ess.messages.send(sender, "no-permission")
        when (target.trim().lowercase()) {
            "*", "all", "전원" -> {
                val players = Vanish.visibleOnline(sender)
                players.forEach { saveOne(sender, it, quiet = true) }
                ess.messages.send(sender, "inventory-saved-many", Ph.of().count(players.size).amount("0"))
            }
            "**" -> {
                val players = Vanish.visibleOnline(sender)
                players.forEach { saveOne(sender, it, quiet = true) }
                val cutoff = System.currentTimeMillis() - (days ?: 30) * 86_400_000L
                val online = Bukkit.getOnlinePlayers().map { it.uniqueId }.toSet()
                val by = sender.name
                val keep = s.keep
                ess.db.run("인벤 저장(오프라인)") {
                    var offline = 0
                    for ((id, name) in store.snapshotsSince(cutoff)) {
                        if (id in online) continue
                        if (store.saveFromSnapshot(id, name, by, keep)) offline++
                    }
                    Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                        ess.messages.send(sender, "inventory-saved-many", Ph.of().count(players.size).amount(offline.toString()))
                    })
                }
            }
            else -> {
                val p = ess.args.online(sender, target) ?: return ess.messages.send(sender, "inventory-save-offline", Ph.of().value(target))
                if (p != sender && !sender.hasPermission(InventoryFeature.SAVE_OTHERS)) return ess.messages.send(sender, "no-permission")
                saveOne(sender, p)
            }
        }
    }

    fun saveOne(sender: CommandSender, player: Player, quiet: Boolean = false, reason: String = InventoryStore.REASON_MANUAL) {
        write(player, sender.name, reason) { ok ->
            if (!quiet) ess.messages.send(sender, if (ok) "inventory-saved" else "inventory-save-failed", Ph.of().player(player.name))
        }
    }

    /** 지금 가방을 DB 에 넣는다(바이트는 메인에서, 쓰기는 DB 스레드). 끝나면 메인에서 [done]. */
    private fun write(player: Player, by: String, reason: String, done: ((Boolean) -> Unit)? = null) =
        write(player.uniqueId, player.name, by, reason, InvData.capture(player, s.blacklist), -1L, done)

    /** [data] 를 DB 에. [protect] 의 저장은 이번 정리(keep-per-player)에서 지우지 않는다. */
    private fun write(id: java.util.UUID, name: String, by: String, reason: String, data: InvData, protect: Long, done: ((Boolean) -> Unit)?) {
        val items = InvData.encode(data.items)
        val ender = InvData.encode(data.ender)
        val effects = InvData.encodeEffects(data.effects)
        val count = data.items.count { it != null } + data.ender.count { it != null }
        val keep = s.keep
        ess.db.run("인벤 저장") {
            val ok = runCatching {
                store.insertSaved(id, name, System.currentTimeMillis(), by, reason, count, items, ender, data.health, data.level, data.exp, data.food, data.saturation, effects, keep, protect)
            }.onFailure { ess.logger.warning("인벤 저장 실패($name): $it") }.isSuccess
            if (done != null) Bukkit.getScheduler().runTask(ess.plugin, Runnable { done(ok) })
        }
    }

    /** DB 에 못 쓴 백업을 파일로(되살릴 길을 남긴다). 파일 이름. */
    private fun dumpBackup(name: String, data: InvData): String {
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = ess.io.file("inventory-backups", "$name-$stamp.txt")
        val b64 = java.util.Base64.getEncoder()
        val body = "# $name 의 불러오기 전 가방 — Paper ItemStack.serializeItemsAsBytes 의 Base64(items 41칸 · ender 27칸)\n" +
            "items: " + b64.encodeToString(InvData.encode(data.items)) + "\n" +
            "ender: " + b64.encodeToString(InvData.encode(data.ender)) + "\n"
        ess.io.asyncRun { kr.inmc.core.util.AtomicFiles.write(file, body) }
        ess.logger.warning("불러오기 전 백업을 DB 에 쓰지 못해 파일로 남겼습니다: ${file.path}")
        return file.name
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onDeath(event: PlayerDeathEvent) {
        val settings = s
        if (!settings.saveOnDeath) return
        val player = event.entity
        if (settings.requiresPermission && !player.hasPermission(InventoryFeature.SAVE_ON_DEATH)) return
        if (settings.ignoreEmpty && InvData.capture(player).isEmpty()) return
        write(player, "죽음", InventoryStore.REASON_DEATH)
    }

    /** 나갈 때의 모습(접속하지 않은 사람 보기의 근거). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        if (feature.settings.snapshotOnQuit) snapshot(event.player)
    }

    /** 가방 41칸·엔더 27칸 바이트와 지문 · 월드 · MV-Inventories 그룹 · 시각(오프라인 보기·고치기의 바탕 — [OfflineEdits]). */
    fun snapshot(player: Player) {
        val data = InvData.capture(player)
        val world = player.world.name
        val snap = Snapshot(
            System.currentTimeMillis(), InvData.encode(data.items), InvData.encode(data.ender),
            InvHash.of(data.items), InvHash.of(data.ender), world, MvInvGroups.of(world),
        )
        val id = player.uniqueId
        val name = player.name
        ess.db.run("나갈 때 가방") { store.writeSnapshot(snap, id, name, data.level, data.exp) }
    }

    // --- 목록 · 미리보기 ------------------------------------------------------------------------------

    private fun owner(sender: CommandSender, name: String): UserRecord? =
        ess.args.offline(sender, name) ?: null.also { ess.messages.send(sender, "user-not-found", Ph.of().value(name)) }

    /** DB 스레드에서 목록을 읽어 메인에서 [then]. */
    private fun heads(owner: UserRecord, then: (List<SavedHead>) -> Unit) {
        ess.db.run("저장 인벤 목록") {
            val heads = store.listSaved(owner.id)
            Bukkit.getScheduler().runTask(ess.plugin, Runnable { then(heads) })
        }
    }

    private fun list(sender: CommandSender, name: String) {
        val owner = owner(sender, name) ?: return
        heads(owner) { heads ->
            if (heads.isEmpty()) return@heads ess.messages.send(sender, "inventory-list-empty", Ph.of().player(owner.name))
            ess.messages.send(sender, "inventory-list-header", Ph.of().player(owner.name).count(heads.size))
            heads.forEachIndexed { i, h ->
                ess.messages.send(sender, "inventory-list-entry", Ph.of().rank((i + 1).toString()).player(owner.name).time(InvCommands.date(h.created))
                    .reason(reasonText(h.reason)).value(h.savedBy).count(h.itemCount.coerceAtLeast(0)).raw("{아이디}", h.id.toString()))
            }
        }
    }

    private fun preview(sender: CommandSender, name: String, number: String) {
        val viewer = sender as? Player ?: return ess.messages.send(sender, "player-only")
        val owner = owner(sender, name) ?: return
        val ref = ref(number) ?: return ess.messages.send(sender, "inventory-bad-number", Ph.of().value(number))
        ess.db.run("저장 인벤 미리보기") {
            val row = resolve(owner, ref)
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                if (row == null) return@Runnable ess.messages.send(sender, "inventory-not-found", Ph.of().player(owner.name).value(number))
                val items = InvData.decode(row.items, InvData.INVENTORY_SIZE)
                val ender = InvData.decode(row.ender, InvData.ENDER_SIZE)
                val n = "#" + row.head.id // 단추는 id 로 — 그새 끼어든 저장에 번호가 밀려도 이것을 고른다
                val extra = buildList {
                    if (viewer.hasPermission(InventoryFeature.LOAD)) add(Triple(SnapshotMenu.SLOT_EXTRA_1, Icon.of(Material.LIME_DYE, "<green>이 인벤 불러오기</green>", "<gray>${owner.name} 에게 되돌립니다</gray>")) {
                        viewer.closeInventory()
                        load(viewer, owner.name, n, null)
                    })
                    if (viewer.hasPermission(InventoryFeature.REMOVE)) add(Triple(SnapshotMenu.SLOT_EXTRA_2, Icon.of(Material.LAVA_BUCKET, "<red>이 저장 지우기</red>")) {
                        viewer.closeInventory()
                        remove(viewer, owner.name, n)
                    })
                }
                SnapshotMenu(ess, viewer, "<dark_gray>${owner.name} · 저장 ${if (ref is Ref.Index) (ref.index + 1).toString() else n}</dark_gray>", items, ender, listOf(
                    "<gray>저장: ${InvCommands.date(row.head.created)} · ${reasonText(row.head.reason)}</gray>",
                    "<gray>저장한 사람: ${row.head.savedBy}</gray>",
                    "<gray>체력 ${"%.1f".format(row.hp)} · 레벨 ${row.level} · 배고픔 ${row.food}</gray>",
                ), extra).show()
            })
        }
    }

    // --- 불러오기 · 지우기 -------------------------------------------------------------------------------

    fun load(sender: CommandSender, ownerName: String, number: String, receiverName: String?) {
        val owner = owner(sender, ownerName) ?: return
        val ref = ref(number) ?: return ess.messages.send(sender, "inventory-bad-number", Ph.of().value(number))
        val online = if (receiverName != null) ess.args.online(sender, receiverName) else Bukkit.getPlayer(owner.id)?.takeIf { Vanish.canSee(sender, it) }
        val receiver = online ?: run {
            // 접속하지 않은 받는 사람 — 다음 접속 때 덮어쓸 대기 편집으로(아이템만). 보내는 사람이 못 보는 투명인은 같은 말로 그 자리에서 되돌린다.
            val record = if (receiverName != null) ess.args.offline(sender, receiverName) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(receiverName))
            else owner
            if (!sender.hasPermission(InventoryFeature.INV_OFFLINE) || !s.restoreItems) return ess.messages.send(sender, "inventory-load-offline", Ph.of().player(record.name))
            val hidden = Bukkit.getPlayer(record.id)
            if (hidden == null) return loadOffline(sender, owner, ref, number, record)
            hidden
        }
        val staged = online == null // 못 보는 투명인 — 알림은 오프라인과 같은 말로(접속 중임이 새지 않게)
        ess.db.run("저장 인벤 불러오기") {
            val row = resolve(owner, ref)
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                if (row == null) return@Runnable ess.messages.send(sender, "inventory-not-found", Ph.of().player(owner.name).value(number))
                if (!receiver.isOnline) return@Runnable ess.messages.send(sender, "player-not-found", Ph.of().value(receiver.name))
                // 같은 틱에: 지금 가방을 그대로(금지 아이템도) 잡고 → 덮고 → 잡은 것을 백업으로 쓴다. 그 사이에 들어온 것이 어디에도 없게 되는 틈이 없다.
                val backup = if (s.backupBeforeLoad) InvData.capture(receiver) else null
                val data = InvData(
                    InvData.decode(row.items, InvData.INVENTORY_SIZE), InvData.decode(row.ender, InvData.ENDER_SIZE),
                    row.hp, row.level, row.exp, row.food, row.saturation, InvData.decodeEffects(row.effects),
                )
                InvData.restore(receiver, data, s)
                // {종류} 는 오프라인 길(loadOffline — 가방·엔더 둘)과 글자까지 같아야 접속 중임이 새지 않는다(규칙 8).
                if (staged) ess.messages.send(sender, "inventory-offline-staged", Ph.of().player(receiver.name).raw("{종류}", feature.offline.kindsName(PendingKind.entries)))
                else ess.messages.send(sender, "inventory-loaded", Ph.of().player(receiver.name).value(owner.name).time(InvCommands.date(row.head.created)))
                if (receiver != sender) ess.messages.send(receiver, "inventory-loaded-target", Ph.of().player(sender.name))
                if (backup != null && !backup.isEmpty()) {
                    val name = receiver.name
                    write(receiver.uniqueId, name, sender.name, InventoryStore.REASON_BACKUP, backup, row.head.id) { ok ->
                        if (!ok) ess.messages.send(sender, "inventory-backup-failed", Ph.of().player(name).value(dumpBackup(name, backup)))
                    }
                }
            })
        }
    }

    /**
     * 접속하지 않은 [receiver] 에게 — 가방·엔더를 대기 편집으로([OfflineEdits.stage], 다음 접속 때 덮어씀). 아이템만(체력·경험치·효과는 접속해 있어야).
     * 불러오기 전 백업은 그 사람의 지금 모습(대기 편집 또는 나갈 때 모습)을 저장 인벤으로.
     */
    private fun loadOffline(sender: CommandSender, owner: UserRecord, ref: Ref, number: String, receiver: UserRecord) {
        ess.db.run("저장 인벤 불러오기(오프라인)") {
            val row = resolve(owner, ref)
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                if (row == null) return@Runnable ess.messages.send(sender, "inventory-not-found", Ph.of().player(owner.name).value(number))
                val contents = mapOf(
                    PendingKind.INVENTORY to InvData.decode(row.items, InvData.INVENTORY_SIZE),
                    PendingKind.ENDER to InvData.decode(row.ender, InvData.ENDER_SIZE),
                )
                feature.offline.stage(sender, receiver, contents) { before, _ ->
                    if (before == null) return@stage
                    ess.messages.send(sender, "inventory-offline-staged", Ph.of().player(receiver.name).raw("{종류}", feature.offline.kindsName(contents.keys)))
                    if (!s.backupBeforeLoad) return@stage
                    val backup = InvData(
                        before[PendingKind.INVENTORY] ?: arrayOfNulls(InvData.INVENTORY_SIZE), before[PendingKind.ENDER] ?: arrayOfNulls(InvData.ENDER_SIZE),
                        20.0, 0, 0f, 20, 5f, emptyList(),
                    )
                    if (backup.isEmpty()) return@stage
                    write(receiver.id, receiver.name, sender.name, InventoryStore.REASON_BACKUP, backup, row.head.id) { ok ->
                        if (!ok) ess.messages.send(sender, "inventory-backup-failed", Ph.of().player(receiver.name).value(dumpBackup(receiver.name, backup)))
                    }
                }
            })
        }
    }

    /** DB 스레드 — 번호(그때의 목록) 또는 id(주인이 맞을 때만)로 한 줄. */
    private fun resolve(owner: UserRecord, ref: Ref): InventoryStore.SavedRow? = when (ref) {
        is Ref.Index -> store.listSaved(owner.id).getOrNull(ref.index)?.let { store.loadSaved(it.id) }
        is Ref.Id -> store.loadSaved(ref.id)?.takeIf { it.head.owner == owner.id }
    }

    private fun remove(sender: CommandSender, ownerName: String, number: String) {
        val owner = owner(sender, ownerName) ?: return
        val all = number.lowercase() in setOf("all", "전부", "*")
        val ref = if (all) null else ref(number) ?: return ess.messages.send(sender, "inventory-bad-number", Ph.of().value(number))
        ess.db.run("저장 인벤 지우기") {
            val removed = if (ref == null) store.removeAllSaved(owner.id) else resolve(owner, ref)?.let { if (store.removeSaved(it.head.id)) 1 else 0 } ?: 0
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                if (removed == 0) ess.messages.send(sender, "inventory-not-found", Ph.of().player(owner.name).value(number))
                else ess.messages.send(sender, "inventory-removed", Ph.of().player(owner.name).count(removed))
            })
        }
    }

    private fun reasonText(reason: String): String = when (reason) {
        InventoryStore.REASON_DEATH -> "죽음"
        InventoryStore.REASON_BACKUP -> "불러오기 전 백업"
        InventoryStore.REASON_SNAPSHOT -> "나갈 때 모습"
        else -> "직접 저장"
    }

    /** 저장 하나를 가리키는 말 — 목록의 번호(0부터) 또는 DB id. */
    sealed interface Ref {
        data class Index(val index: Int) : Ref
        data class Id(val id: Long) : Ref
    }

    companion object {
        /** `last`·`1` → 0, `2` → 1 … 못 읽으면 null. */
        fun index(text: String): Int? {
            val t = text.trim().lowercase()
            if (t == "last" || t == "마지막") return 0
            return t.toIntOrNull()?.takeIf { it >= 1 }?.minus(1)
        }

        /** `#123` → id, 그 밖은 [index]. 못 읽으면 null. */
        fun ref(text: String): Ref? {
            val t = text.trim()
            if (t.startsWith("#")) return t.substring(1).toLongOrNull()?.takeIf { it >= 1 }?.let { Ref.Id(it) }
            return index(t)?.let { Ref.Index(it) }
        }

        private val TARGETS = SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val typed = builder.remainingLowerCase
            for (special in listOf("*", "**")) if (special.startsWith(typed)) builder.suggest(special)
            for (name in Vanish.visibleNames(ctx.source.sender)) if (name.lowercase().startsWith(typed)) builder.suggest(name)
            builder.buildFuture()
        }

        private val NUMBERS = SuggestionProvider<CommandSourceStack> { _, builder ->
            for (w in listOf("last", "1", "2", "3")) if (w.startsWith(builder.remainingLowerCase)) builder.suggest(w)
            builder.buildFuture()
        }

        private val NUMBERS_ALL = SuggestionProvider<CommandSourceStack> { _, builder ->
            for (w in listOf("last", "all", "1", "2", "3")) if (w.startsWith(builder.remainingLowerCase)) builder.suggest(w)
            builder.buildFuture()
        }
    }
}

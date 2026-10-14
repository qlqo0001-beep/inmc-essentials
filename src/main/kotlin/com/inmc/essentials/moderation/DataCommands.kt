package com.inmc.essentials.moderation

import com.inmc.essentials.moderation.DataRules.Column
import com.inmc.essentials.moderation.DataRules.Kind
import com.inmc.essentials.moderation.DataRules.Op
import com.inmc.essentials.moderation.ModCmds.greedy
import com.inmc.essentials.moderation.ModCmds.literal
import com.inmc.essentials.moderation.ModCmds.sender
import com.inmc.essentials.moderation.ModCmds.tokens
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * `/데이터교환`(switchplayerdata) · `/유저삭제`(removeuser) · `/열초기화`(resetdbfields) · `/미접속정리`(purge) · `/유저가져오기`(importoldusers).
 *
 * 앞의 넷은 **미리 보기 → `확인`(60초 안) → 다음 시작 때 적용**(예약). 미리 보기는 바뀔 표·열과 줄 수를 DB 에서 세어 보여 준다(쓰기 스레드).
 * 예약은 `… 예약목록` · `… 예약취소 <번호>` 로 보고 뺀다. 바닐라 파일(playerdata·stats·advancements)은 `/미접속정리 … -파일` 로
 * 따로 고르고 그것도 확인한 때만.
 */
class DataCommands(private val mod: Moderation) {

    private val ess get() = mod.ess

    private class Preview(val op: Op, val at: Long)

    /** 보낸 사람 + 친 글(확인 뺀) → 미리 본 것. */
    private val previews = ConcurrentHashMap<String, Preview>()

    fun register() {
        add("데이터교환", "switchplayerdata", ModerationFeature.SWITCHPLAYERDATA, "두 사람의 데이터를 서로 바꾸기") { ctx, args -> switch(ctx, args) }
        add("유저삭제", "removeuser", ModerationFeature.REMOVEUSER, "유저와 그 데이터 지우기 · 중복 계정 정리") { ctx, args -> remove(ctx, args) }
        add("열초기화", "resetdbfields", ModerationFeature.RESETDBFIELDS, "DB 열을 기본값으로") { ctx, args -> reset(ctx, args) }
        add("미접속정리", "purge", ModerationFeature.PURGE, "오래 안 온 사람의 데이터 정리") { ctx, args -> purge(ctx, args) }
        ess.commands.add(
            literal("유저가져오기", ModerationFeature.IMPORTOLDUSERS).executes { ctx -> importOld(sender(ctx)); 1 }.build(),
            "바닐라 playerdata 의 사람을 유저 기록으로", listOf("importoldusers"),
        )
    }

    private fun add(name: String, alias: String, permission: String, description: String, run: (CommandContext<CommandSourceStack>, List<String>) -> Unit) {
        ess.commands.add(
            literal(name, permission).executes { ctx -> mod.usage(sender(ctx), USAGE.getValue(name)); 1 }
                .then(greedy().suggests(hints(name)).executes { ctx ->
                    val args = tokens(ctx)
                    when (args.firstOrNull()?.lowercase()) {
                        in LIST -> pendingList(sender(ctx), ModCmds.label(ctx))
                        in CANCEL -> cancel(sender(ctx), args.getOrNull(1)?.toLongOrNull())
                        else -> run(ctx, args)
                    }
                    1
                })
                .build(),
            description, listOf(alias),
        )
    }

    // --- 교환 ------------------------------------------------------------------------------------

    private fun switch(ctx: CommandContext<CommandSourceStack>, args: List<String>) {
        val sender = sender(ctx)
        val (body, confirm) = split(args)
        if (body.size < 2) return mod.usage(sender, USAGE.getValue("데이터교환"))
        val a = mod.user(sender, body[0]) ?: return
        val b = mod.user(sender, body[1]) ?: return
        if (a.id == b.id) return ess.messages.send(sender, "moderation-data-same")
        if (confirm) return confirm(ctx, sender, body)
        val s = mod.settings
        mod.query(sender, "데이터 교환 미리 보기", {
            val sql = ess.db.sql
            val columns = DataRules.playerColumns(DataTools.tables(sql), s.dataExtraColumns, s.dataExcludeTables.toSet(), identity = true)
            columns to columns.associateWith { DataTools.count(sql, it, listOf(a.id.toString(), b.id.toString())) }
        }) { (columns, counts) ->
            val op = Op(Kind.SWITCH, columns = columns, a = a.id.toString(), b = b.id.toString())
            show(ctx, sender, body, op, "${a.name} ↔ ${b.name}", counts, users = emptyList(), files = null)
        }
    }

    // --- 삭제 ------------------------------------------------------------------------------------

    private fun remove(ctx: CommandContext<CommandSourceStack>, args: List<String>) {
        val sender = sender(ctx)
        val (body, confirm) = split(args)
        val input = body.firstOrNull() ?: return mod.usage(sender, USAGE.getValue("유저삭제"))
        val duplicates = input.lowercase() in DUPLICATES
        // 프록시(Velocity·BungeeCord) 뒤에서는 server.properties 가 오프라인이어도 진짜 계정은 온라인(v4) uuid 다 — 프록시의 온라인 모드도 본다.
        val onlineMode = Bukkit.getOnlineMode() || runCatching { Bukkit.getServer().serverConfig.isProxyOnlineMode }.getOrDefault(false)
        val users: List<UserRecord> = if (duplicates) {
            val ids = ModRules.duplicates(ess.users.all().map { it.id to it.name }, onlineMode).toSet()
            ess.users.all().filter { it.id in ids && Bukkit.getPlayer(it.id) == null }
        } else listOf(mod.user(sender, input) ?: return)
        if (users.isEmpty()) return ess.messages.send(sender, "moderation-data-nothing")
        if (confirm) return confirm(ctx, sender, body)
        val s = mod.settings
        val ids = users.map { it.id.toString() }
        mod.query(sender, "유저 삭제 미리 보기", {
            val sql = ess.db.sql
            val columns = DataRules.playerColumns(DataTools.tables(sql), s.dataExtraColumns, s.dataExcludeTables.toSet(), identity = false)
            columns to columns.associateWith { DataTools.count(sql, it, ids) }
        }) { (columns, counts) ->
            val mode = if (duplicates) " " + ess.messages.raw(if (onlineMode) "moderation-data-dup-online" else "moderation-data-dup-offline") else ""
            show(ctx, sender, body, Op(Kind.REMOVE, columns = columns, ids = ids), "${Kind.REMOVE.label} ${users.size}명$mode", counts, users.map { it.name }, files = null)
        }
    }

    // --- 열 초기화 ---------------------------------------------------------------------------------

    private fun reset(ctx: CommandContext<CommandSourceStack>, args: List<String>) {
        val sender = sender(ctx)
        val (body, confirm) = split(args)
        val flags = ModRules.flags(body)
        val target = flags.rest.firstOrNull()?.let { Column.parse(it) } ?: return ess.messages.send(sender, "moderation-data-no-column", Ph.of().value(flags.rest.firstOrNull() ?: ""))
        val who = (flags.options["p"] ?: flags.options["플레이어"])?.let { mod.user(sender, it) ?: return }
        val world = flags.options["w"] ?: flags.options["월드"]
        if (confirm) return confirm(ctx, sender, body)
        mod.query(sender, "열 초기화 미리 보기", {
            val sql = ess.db.sql
            val tables = DataTools.tables(sql)
            val columns = tables[target.table]
            if (columns == null || columns.none { it.equals(target.column, true) } || target.table in DataRules.NEVER) return@query ResetPlan.Missing
            val info = DataTools.columnInfo(sql, target) ?: return@query ResetPlan.Missing
            if (info.key) return@query ResetPlan.Key
            val value = when (val d = DataRules.parseDefault(info.default, info.nullable)) {
                DataRules.Default.Null -> null
                is DataRules.Default.Value -> d.value
                DataRules.Default.Unknown -> return@query ResetPlan.NoDefault
            }
            val where = who?.let { columns.firstOrNull { c -> DataRules.isPlayerColumn(c) }?.let { c -> Column(target.table, c.lowercase()) } ?: return@query ResetPlan.Missing }
            if (world != null && !DataTools.hasWorld(tables, target.table)) return@query ResetPlan.Missing
            val op = Op(Kind.RESET, reset = target, value = value, where = where, whereId = who?.id?.toString(), world = world)
            ResetPlan.Ready(op, DataTools.countWhere(sql, target.table, where, who?.id?.toString(), world))
        }) { plan ->
            when (plan) {
                ResetPlan.Missing -> ess.messages.send(sender, "moderation-data-no-column", Ph.of().value(body.joinToString(" ")))
                ResetPlan.Key -> ess.messages.send(sender, "moderation-data-key-column", Ph.of().value(target.toString()))
                ResetPlan.NoDefault -> ess.messages.send(sender, "moderation-data-no-default", Ph.of().value(target.toString()))
                is ResetPlan.Ready -> show(ctx, sender, body, plan.op, plan.op.describe() + " → ${plan.op.value ?: "NULL"}", mapOf(target to plan.rows), emptyList(), files = null)
            }
        }
    }

    private sealed interface ResetPlan {
        data object Missing : ResetPlan
        data object Key : ResetPlan
        data object NoDefault : ResetPlan
        class Ready(val op: Op, val rows: Int) : ResetPlan
    }

    // --- 미접속 정리 --------------------------------------------------------------------------------

    private fun purge(ctx: CommandContext<CommandSourceStack>, args: List<String>) {
        val sender = sender(ctx)
        val (body, confirm) = split(args)
        val s = mod.settings
        val withFiles = body.any { it.lowercase() in FILES }
        val days = body.firstNotNullOfOrNull { it.toIntOrNull() }?.coerceAtLeast(1) ?: s.purgeDays
        val now = System.currentTimeMillis()
        val cutoff = now - days * 86_400_000L
        val users = ess.users.all().filter { it.lastJoin < cutoff && Bukkit.getPlayer(it.id) == null }
        if (users.isEmpty()) return ess.messages.send(sender, "moderation-data-nothing")
        if (confirm) return confirm(ctx, sender, body)
        val folders = if (withFiles) mainWorld()?.let { world -> s.purgeFolders.map { File(world, it).absolutePath } }.orEmpty() else emptyList()
        val ids = users.map { it.id.toString() }
        mod.query(sender, "미접속 정리 미리 보기", {
            val sql = ess.db.sql
            val columns = DataRules.playerColumns(DataTools.tables(sql), s.dataExtraColumns, s.dataExcludeTables.toSet(), identity = false)
            Triple(columns, columns.associateWith { DataTools.count(sql, it, ids) }, DataTools.vanillaFiles(folders, ids).size)
        }) { (columns, counts, files) ->
            val op = Op(Kind.PURGE, columns = columns, ids = ids, cutoff = cutoff, folders = folders, deleteFiles = s.purgeDeleteFiles)
            show(ctx, sender, body, op, "${Kind.PURGE.label} — ${days}일 넘게 안 온 ${users.size}명", counts, users.map { it.name },
                files = if (withFiles) files to (if (s.purgeDeleteFiles) "지움" else "<폴더>_backup 으로 옮김") else null)
        }
    }

    private fun mainWorld(): File? = Bukkit.getWorlds().firstOrNull()?.worldFolder

    // --- 미리 보기·확인 ------------------------------------------------------------------------------

    private fun show(ctx: CommandContext<CommandSourceStack>, sender: CommandSender, body: List<String>, op: Op, title: String,
                     counts: Map<Column, Int>, users: List<String>, files: Pair<Int, String>?) {
        val total = counts.values.sum() + (files?.first ?: 0)
        ess.messages.send(sender, "moderation-data-preview", Ph.of().value(title))
        for ((col, n) in counts) if (n > 0) ess.messages.send(sender, "moderation-data-preview-row", Ph.of().count(n).raw("{표}", col.table).raw("{열}", col.column))
        if (users.isNotEmpty()) ess.messages.send(sender, "moderation-data-preview-users",
            Ph.of().count(users.size).raw("{목록}", users.take(10).joinToString(", ") + if (users.size > 10) " …" else ""))
        files?.let { (n, how) -> ess.messages.send(sender, "moderation-data-preview-files", Ph.of().count(n).value(how)) }
        if (total == 0 && op.kind != Kind.REMOVE && op.kind != Kind.PURGE) return ess.messages.send(sender, "moderation-data-nothing")
        val now = System.currentTimeMillis()
        // 확인하지 않고 버린 미리 보기(정리의 uuid 목록을 품는다)는 여기서 치운다.
        previews.values.removeIf { now - it.at > CONFIRM_MS }
        previews[key(sender, body)] = Preview(op, now)
        ess.messages.send(sender, "moderation-data-confirm", Ph.of().raw("{명령}", "/${ModCmds.label(ctx)} ${body.joinToString(" ")} 확인"))
    }

    private fun confirm(ctx: CommandContext<CommandSourceStack>, sender: CommandSender, body: List<String>) {
        val preview = previews.remove(key(sender, body))
        if (preview == null || System.currentTimeMillis() - preview.at > CONFIRM_MS) return ess.messages.send(sender, "moderation-data-no-preview")
        val serial = mod.state.serial.incrementAndGet()
        mod.store.insertPending(serial, preview.op.kind.name, DataRules.encode(preview.op), mod.who(sender))
        ess.logger.warning("데이터 도구 예약 $serial — ${preview.op.describe()} (${mod.who(sender)}) · 다음 시작 때 적용")
        ess.messages.send(sender, "moderation-data-queued", Ph.of().raw("{번호}", serial.toString()).raw("{명령}", ModCmds.label(ctx)))
    }

    /** 보낸 사람이 다룰 수 있는 예약 종류 — 그 종류의 명령 권한이 있을 때만(열초기화 권한만으로 미접속 정리 예약을 보거나 지우지 못하게). 메인에서 센다. */
    private fun allowedKinds(sender: CommandSender): Set<String> =
        KIND_PERMISSION.filter { (_, perm) -> sender.hasPermission(perm) }.keys.toSet()

    private fun pendingList(sender: CommandSender, label: String) {
        val allowed = allowedKinds(sender)
        mod.query(sender, "데이터 도구 예약 목록", {
            val out = ArrayList<Triple<Long, Op?, Pair<String, Long>>>()
            ess.db.sql.connection().createStatement().use { st ->
                st.executeQuery("SELECT serial, kind, payload, by_name, created FROM ${ModStore.PENDING} ORDER BY serial").use { rs ->
                    while (rs.next()) {
                        if (rs.getString(2) !in allowed) continue
                        out += Triple(rs.getLong(1), DataRules.decode(rs.getString(3)), rs.getString(4).orEmpty() to rs.getLong(5))
                    }
                }
            }
            out
        }) { rows ->
            if (rows.isEmpty()) return@query ess.messages.send(sender, "moderation-data-pending-none")
            ess.messages.send(sender, "moderation-data-pending-header", Ph.of().count(rows.size))
            for ((serial, op, meta) in rows) {
                ess.messages.send(sender, "moderation-data-pending-entry", Ph.of().raw("{번호}", serial.toString()).value(op?.describe() ?: "?")
                    .raw("{관리자}", meta.first).raw("{날짜}", mod.dateText(meta.second)).raw("{명령}", "/$label 예약취소 $serial"))
            }
        }
    }

    private fun cancel(sender: CommandSender, serial: Long?) {
        serial ?: return mod.usage(sender, "… 예약취소 [번호]")
        val allowed = allowedKinds(sender)
        mod.query(sender, "데이터 도구 예약 취소", {
            val c = ess.db.sql.connection()
            val kind = c.prepareStatement("SELECT kind FROM ${ModStore.PENDING} WHERE serial = ?").use { ps ->
                ps.setLong(1, serial)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            } ?: return@query "moderation-data-pending-not-found"
            if (kind !in allowed) return@query "no-permission"
            c.prepareStatement("DELETE FROM ${ModStore.PENDING} WHERE serial = ?").use { ps ->
                ps.setLong(1, serial)
                ps.executeUpdate()
            }
            "moderation-data-pending-cancelled"
        }) { key -> ess.messages.send(sender, key, Ph.of().raw("{번호}", serial.toString())) }
    }

    private fun key(sender: CommandSender, body: List<String>): String =
        ((sender as? Player)?.uniqueId?.toString() ?: sender.name) + "|" + body.joinToString(" ").lowercase()

    /** 꼬리에서 마지막 `확인` 을 뗀다. */
    private fun split(args: List<String>): Pair<List<String>, Boolean> {
        val confirm = args.lastOrNull()?.lowercase() in CONFIRM
        return (if (confirm) args.dropLast(1) else args) to confirm
    }

    // --- 옛 유저 가져오기 ----------------------------------------------------------------------------

    private fun importOld(sender: CommandSender) {
        val world = mainWorld() ?: return
        val playerdata = File(world, "playerdata")
        val usercache = File(Bukkit.getWorldContainer(), "usercache.json").takeIf { it.isFile } ?: File("usercache.json")
        ess.messages.send(sender, "moderation-import-start")
        ess.io.async({ DataTools.readOldUsers(playerdata, usercache) }) { old ->
            var added = 0
            var existing = 0
            var nameless = 0
            for ((id, seen) in old.seen) {
                if (ess.users.get(id) != null) {
                    existing++
                    continue
                }
                val name = old.names[id] ?: Bukkit.getOfflinePlayer(id).name
                if (name.isNullOrBlank()) {
                    nameless++
                    continue
                }
                // 처음 온 때는 바닐라 파일로 알 수 없다 — 파일을 마지막으로 쓴 때를 둘 다에.
                ess.users.save(UserRecord(id, name, firstJoin = seen, lastJoin = seen))
                added++
            }
            for (p in old.problems) ess.logger.warning("유저 가져오기: $p")
            ess.messages.send(sender, "moderation-import-done", Ph.of().count(added).value(existing.toString()).raw("{번호}", nameless.toString()))
        }
    }

    private fun hints(name: String) = ModCmds.hints { sender, before ->
        when {
            before.isEmpty() -> when (name) {
                "유저삭제" -> listOf("중복", "예약목록", "예약취소") + kr.inmc.core.integration.Vanish.visibleNames(sender)
                "열초기화" -> listOf("예약목록", "예약취소")
                "미접속정리" -> listOf(mod.settings.purgeDays.toString(), "-파일", "예약목록", "예약취소")
                else -> listOf("예약목록", "예약취소") + kr.inmc.core.integration.Vanish.visibleNames(sender)
            }
            name == "열초기화" -> listOf("-p:", "-w:", "확인")
            name == "미접속정리" -> listOf("-파일", "확인")
            name == "데이터교환" && before.size == 1 -> kr.inmc.core.integration.Vanish.visibleNames(sender)
            else -> listOf("확인")
        }
    }

    companion object {
        const val CONFIRM_MS = 60_000L
        val CONFIRM = setOf("확인", "confirm")
        val LIST = setOf("예약목록", "pending")
        val CANCEL = setOf("예약취소", "cancel")
        val DUPLICATES = setOf("중복", "duplicates")
        val FILES = setOf("-파일", "-files", "-playerdata")

        /** 예약 종류(`ess_moderation_pending.kind` = [Kind.name]) → 그 종류를 보고 취소할 수 있는 명령 권한. */
        val KIND_PERMISSION: Map<String, String> = mapOf(
            Kind.SWITCH.name to ModerationFeature.SWITCHPLAYERDATA,
            Kind.REMOVE.name to ModerationFeature.REMOVEUSER,
            Kind.RESET.name to ModerationFeature.RESETDBFIELDS,
            Kind.PURGE.name to ModerationFeature.PURGE,
        )
        val USAGE = mapOf(
            "데이터교환" to "/데이터교환 [원본 이름/uuid] [대상 이름/uuid] (확인)",
            "유저삭제" to "/유저삭제 [이름/uuid | 중복] (확인)",
            "열초기화" to "/열초기화 [표.열] (-p:플레이어) (-w:월드) (확인)",
            "미접속정리" to "/미접속정리 (일수) (-파일) (확인)",
        )
    }
}

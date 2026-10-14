package com.inmc.essentials.moderation

import com.inmc.essentials.moderation.ModCmds.greedy
import com.inmc.essentials.moderation.ModCmds.literal
import com.inmc.essentials.moderation.ModCmds.sender
import com.inmc.essentials.moderation.ModCmds.tokens
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * `/수갑`(cuff) · `/경보`(alert) · `/메모`(note) · `/부계정`(checkaccount) · `/같은아이피`(sameip) · `/아이피잠금`(lockip) · `/순찰`(patrol) ·
 * `/대신실행`(sudo) · `/유저메타`(usermeta). 하위 낱말은 한글·영어 둘 다 받는다.
 */
class RecordCommands(private val mod: Moderation, private val records: Records) {

    private val ess get() = mod.ess
    private val state get() = mod.state

    fun register() {
        ess.commands.add(
            literal("수갑", ModerationFeature.CUFF).executes { ctx -> mod.usage(sender(ctx), "/수갑 [플레이어] (true/false) (-s)"); 1 }
                .then(ess.args.player("플레이어").executes { ctx -> cuff(ctx); 1 }
                    .then(greedy().suggests(ModCmds.hints { _, _ -> listOf("true", "false", "-s") }).executes { ctx -> cuff(ctx); 1 }))
                .build(),
            "수갑 채우기·풀기", listOf("cuff"),
        )
        ess.commands.add(
            literal("경보", ModerationFeature.ALERT).executes { ctx -> alerts(sender(ctx)); 1 }
                .then(greedy().suggests(actionHints(ALERT_ACTIONS)).executes { ctx -> alert(ctx); 1 })
                .build(),
            "접속 경보", listOf("alert"),
        )
        ess.commands.add(
            literal("메모", ModerationFeature.NOTE).executes { ctx -> mod.usage(sender(ctx), "/메모 (플레이어) [추가|삭제|비우기|목록] (번호/내용)"); 1 }
                .then(greedy().suggests(playerThenActions(NOTE_ACTIONS)).executes { ctx -> note(ctx); 1 })
                .build(),
            "플레이어 메모", listOf("note"),
        )
        ess.commands.add(
            literal("부계정", ModerationFeature.CHECKACCOUNT).executes { ctx ->
                (sender(ctx) as? Player)?.let { checkAccount(it, it.name) } ?: mod.usage(sender(ctx), "/부계정 [이름/IP]"); 1
            }
                .then(ess.args.player("대상").executes { ctx -> checkAccount(sender(ctx), ModCmds.arg(ctx, "대상")); 1 })
                .build(),
            "같은 IP 를 쓴 계정", listOf("checkaccount"),
        )
        ess.commands.add(
            literal("같은아이피", ModerationFeature.SAMEIP).executes { ctx -> sameIp(sender(ctx)); 1 }.build(),
            "같은 IP 로 접속 중인 사람", listOf("sameip"),
        )
        ess.commands.add(
            literal("아이피잠금", ModerationFeature.LOCKIP).executes { ctx -> mod.usage(sender(ctx), "/아이피잠금 (플레이어) [추가|삭제|목록|비우기] (IP)"); 1 }
                .then(greedy().suggests(playerThenActions(LOCK_ACTIONS)).executes { ctx -> lockIp(ctx); 1 })
                .build(),
            "계정의 허용 IP", listOf("lockip"),
        )
        ess.commands.add(
            literal("순찰", ModerationFeature.PATROL).executes { ctx ->
                (sender(ctx) as? Player)?.let { records.patrol(it, ModCmds.label(ctx)) } ?: ess.messages.send(sender(ctx), "player-only"); 1
            }.build(),
            "접속자를 차례로 찾아가기", listOf("patrol"),
        )
        ess.commands.add(
            literal("대신실행", ModerationFeature.SUDO).executes { ctx -> mod.usage(sender(ctx), "/대신실행 [플레이어] [명령 | c:채팅]"); 1 }
                .then(ess.args.player("플레이어").then(greedy().executes { ctx -> sudo(ctx); 1 }))
                .build(),
            "남이 명령·채팅하게", listOf("sudo"),
        )
        ess.commands.add(
            literal("유저메타", ModerationFeature.USERMETA).executes { ctx -> mod.usage(sender(ctx), "/유저메타 [플레이어] [추가|삭제|비우기|목록|증가] (키) (값) (-s)"); 1 }
                .then(ess.args.player("플레이어").executes { ctx -> meta(ctx); 1 }
                    .then(greedy().suggests(metaHints).executes { ctx -> meta(ctx); 1 }))
                .build(),
            "플레이어마다 키·값", listOf("usermeta"),
        )
    }

    // --- 수갑 ------------------------------------------------------------------------------------

    private fun cuff(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val user = mod.user(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        val flags = ModRules.flags(tokens(ctx))
        records.cuff(sender, user, flags.rest.firstOrNull()?.let { ModRules.bool(it) }, flags.silent)
    }

    // --- 경보 ------------------------------------------------------------------------------------

    private fun alert(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val args = tokens(ctx)
        val action = args.firstOrNull()?.lowercase()?.let { ALERT_ACTIONS[it] } ?: return mod.usage(sender, "/경보 [추가|삭제|목록|하루추가] [플레이어] (사유)")
        if (action == "list") return alerts(sender)
        val user = args.getOrNull(1)?.let { mod.user(sender, it) } ?: return mod.usage(sender, "/경보 [추가|삭제|목록|하루추가] [플레이어] (사유)")
        when (action) {
            "add" -> records.addAlert(sender, user, ModRules.flags(args.drop(2)).rest.joinToString(" "))
            "remove" -> records.removeAlert(sender, user)
            "extend" -> records.extendAlert(sender, user)
        }
    }

    private fun alerts(sender: CommandSender) {
        val now = System.currentTimeMillis()
        val list = state.alerts.values.filter { it.until > now }.sortedBy { it.until }
        if (list.isEmpty()) return ess.messages.send(sender, "moderation-alert-list-empty")
        list.forEachIndexed { i, a ->
            val name = ess.users.get(a.id)?.name ?: a.id.toString()
            ess.messages.send(sender, "moderation-alert-list-entry", Ph.of().raw("{번호}", (i + 1).toString()).player(name)
                .time(mod.timeText((a.until - now) / 1000)).reason(a.reason ?: "-").raw("{명령}", "/경보 하루추가 $name"))
        }
    }

    // --- 메모 ------------------------------------------------------------------------------------

    private fun note(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val (user, action, rest) = targetAction(sender, tokens(ctx), NOTE_ACTIONS, default = "list") ?: return
        val ph = Ph.of().player(user.name)
        val notes = state.activeNotes(user.id)
        when (action) {
            "add" -> {
                val text = rest.joinToString(" ").trim()
                if (text.isEmpty()) return mod.usage(sender, "/메모 [플레이어] 추가 [내용]")
                records.addNote(sender, user, text)
            }
            "remove" -> {
                val n = rest.firstOrNull()?.toIntOrNull()
                val note = n?.let { notes.getOrNull(it - 1) } ?: return ess.messages.send(sender, "moderation-note-not-found", Ph.of().raw("{번호}", rest.firstOrNull() ?: "?"))
                records.removeNote(user, note)
                ess.messages.send(sender, "moderation-note-removed", ph.raw("{번호}", n.toString()))
            }
            "clear" -> {
                records.clearNotes(user.id)
                ess.messages.send(sender, "moderation-note-cleared", ph)
            }
            else -> {
                if (notes.isEmpty()) return ess.messages.send(sender, "moderation-note-none", ph)
                ess.messages.send(sender, "moderation-note-header", ph.count(notes.size))
                notes.forEachIndexed { i, n ->
                    ess.messages.send(sender, "moderation-note-entry", Ph.of().raw("{번호}", (i + 1).toString()).value(n.text).raw("{관리자}", n.author)
                        .raw("{날짜}", mod.dateText(n.created)))
                }
            }
        }
    }

    // --- 계정 조회 --------------------------------------------------------------------------------

    private fun checkAccount(sender: CommandSender, input: String) {
        val excluded = mod.settings.accountExcluded.map { it.lowercase() }.toSet()
        fun shown(id: UUID): Boolean {
            val name = ess.users.get(id)?.name ?: return true
            return name.lowercase() !in excluded
        }
        fun nameEntry(id: UUID, times: Int): String {
            val name = ess.users.get(id)?.name ?: id.toString()
            return ess.messages.raw("moderation-account-name").replace("{명령}", "/부계정 $name").replace("{플레이어}", name).replace("{개수}", times.toString())
        }
        val sep = ess.messages.raw("moderation-account-separator")
        if (state.ips.isEmpty() && !mod.settings.ipRecord) return ess.messages.send(sender, "moderation-account-disabled")
        val showIp = sender.hasPermission(ModerationFeature.CHECKACCOUNT_SHOWIP)
        if (ModRules.isIp(input)) {
            if (!showIp || input in excluded) return ess.messages.send(sender, "no-permission")
            val ids = state.usersOf(input).filter { shown(it) }
            if (ids.isEmpty()) return ess.messages.send(sender, "moderation-account-none", Ph.of().player(input))
            ess.messages.send(sender, "moderation-account-header", Ph.of().player(input))
            ess.messages.send(sender, "moderation-account-ip", Ph.of().raw("{아이피}", input).raw("{목록}",
                ids.joinToString(sep) { id -> nameEntry(id, state.ips[id]?.firstOrNull { it.ip == input }?.times ?: 0) }))
            return
        }
        val user = mod.user(sender, input) ?: return
        val mine = state.ips[user.id].orEmpty().filter { it.ip !in excluded }
        if (mine.isEmpty()) return ess.messages.send(sender, "moderation-account-none", Ph.of().player(user.name))
        ess.messages.send(sender, "moderation-account-header", Ph.of().player(user.name))
        var any = false
        if (showIp) {
            for (seen in mine.sortedByDescending { it.lastSeen }) {
                val others = state.usersOf(seen.ip).filter { it != user.id && shown(it) }
                if (others.isEmpty()) continue
                any = true
                ess.messages.send(sender, "moderation-account-ip", Ph.of().raw("{아이피}", seen.ip).raw("{목록}",
                    others.joinToString(sep) { id -> nameEntry(id, state.ips[id]?.firstOrNull { it.ip == seen.ip }?.times ?: 0) }))
            }
        } else {
            val others = mine.flatMap { state.usersOf(it.ip) }.toSet().filter { it != user.id && shown(it) }
            if (others.isNotEmpty()) {
                any = true
                ess.messages.send(sender, "moderation-account-plain", Ph.of().raw("{목록}", others.joinToString(sep) { nameEntry(it, state.ips[it]?.sumOf { s -> s.times } ?: 0) }))
            }
        }
        if (!any) ess.messages.send(sender, "moderation-account-alone")
    }

    private fun sameIp(sender: CommandSender) {
        val groups = Vanish.visibleOnline(sender).groupBy { it.address?.address?.hostAddress }.filter { (ip, list) -> ip != null && list.size > 1 }
        if (groups.isEmpty()) return ess.messages.send(sender, "moderation-sameip-none")
        ess.messages.send(sender, "moderation-sameip-header")
        val sep = ess.messages.raw("moderation-account-separator")
        groups.values.forEachIndexed { i, list ->
            ess.messages.send(sender, "moderation-sameip-group", Ph.of().raw("{번호}", (i + 1).toString()).raw("{목록}", list.joinToString(sep) { it.name }))
        }
    }

    // --- IP 잠금 ---------------------------------------------------------------------------------

    private fun lockIp(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val (user, action, rest) = targetAction(sender, tokens(ctx), LOCK_ACTIONS, default = "list") ?: return
        // 남의 계정을 잠그거나 풀기는 보호 대상(lockip.bypass · 오프라인이면 op)에게 못 한다 — 자기 계정은 된다.
        val self = sender is Player && sender.uniqueId == user.id
        if (action != "list" && !self && mod.isProtected(user.id, ModerationFeature.LOCKIP_BYPASS)) {
            return ess.messages.send(sender, "moderation-protected", Ph.of().player(user.name))
        }
        when (action) {
            "add" -> {
                val ip = rest.firstOrNull() ?: Bukkit.getPlayer(user.id)?.address?.address?.hostAddress ?: user.lastIp
                    ?: return ess.messages.send(sender, "moderation-ip-unknown", Ph.of().player(user.name))
                if (!ModRules.isIp(ip)) return ess.messages.send(sender, "moderation-lockip-invalid", Ph.of().value(ip))
                records.addLockIp(sender, user, ip)
            }
            "remove" -> {
                val ip = rest.firstOrNull() ?: return mod.usage(sender, "/아이피잠금 [플레이어] 삭제 [IP]")
                records.removeLockIp(sender, user, ip)
            }
            "clear" -> records.clearLockIp(sender, user)
            else -> {
                val list = state.lockIps[user.id].orEmpty()
                if (list.isEmpty()) return ess.messages.send(sender, "moderation-lockip-empty", Ph.of().player(user.name))
                ess.messages.send(sender, "moderation-lockip-header", Ph.of().player(user.name).count(list.size))
                list.forEachIndexed { i, ip -> ess.messages.send(sender, "moderation-lockip-entry", Ph.of().raw("{번호}", (i + 1).toString()).raw("{아이피}", ip)) }
            }
        }
    }

    // --- 대신 실행 -------------------------------------------------------------------------------

    private fun sudo(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val target = mod.online(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        if (target != sender && target.hasPermission(ModerationFeature.SUDO_BYPASS)) {
            return ess.messages.send(sender, "moderation-protected", Ph.of().player(target.name))
        }
        val text = ModCmds.tail(ctx).trim()
        if (text.isEmpty()) return
        if (text.startsWith("c:", true)) {
            val chat = text.substring(2).trim()
            if (chat.isEmpty()) return
            target.chat(chat)
            ess.messages.send(sender, "moderation-sudo-chat", Ph.of().player(target.name).value(chat))
        } else {
            val command = text.removePrefix("/")
            target.performCommand(command)
            ess.messages.send(sender, "moderation-sudo-done", Ph.of().player(target.name).raw("{명령}", command))
        }
    }

    // --- 유저 메타 --------------------------------------------------------------------------------

    private fun meta(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val user = mod.user(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        val flags = ModRules.flags(tokens(ctx))
        val args = flags.rest
        val action = args.firstOrNull()?.lowercase()?.let { META_ACTIONS[it] } ?: "list"
        // 키는 소문자로 저장·조회한다(%inmcmod_meta_<키>% 와 가져온 CMI 키가 대소문자로 어긋나지 않게).
        val key = args.getOrNull(1)?.lowercase()
        if (key != null && !ModRules.validName(key)) return ess.messages.send(sender, "moderation-usermeta-invalid-key")
        when (action) {
            "add" -> {
                if (key == null || args.size < 3) return mod.usage(sender, "/유저메타 [플레이어] 추가 [키] [값]")
                records.setMeta(sender, user, key, args.drop(2).joinToString(" "), flags.silent)
            }
            "increment" -> {
                if (key == null) return mod.usage(sender, "/유저메타 [플레이어] 증가 [키] (수)")
                val delta = args.getOrNull(2)?.let { it.toDoubleOrNull() ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(it)) } ?: 1.0
                records.incrementMeta(sender, user, key, delta, flags.silent)
            }
            "remove" -> {
                if (key == null) return mod.usage(sender, "/유저메타 [플레이어] 삭제 [키]")
                records.removeMeta(sender, user, key, flags.silent)
            }
            "clear" -> records.clearMeta(sender, user, flags.silent)
            else -> {
                val map = state.meta[user.id].orEmpty()
                if (map.isEmpty()) return ess.messages.send(sender, "moderation-usermeta-none", Ph.of().player(user.name))
                ess.messages.send(sender, "moderation-usermeta-header", Ph.of().player(user.name).count(map.size))
                for ((k, v) in map) ess.messages.send(sender, "moderation-usermeta-entry", Ph.of().value(v).raw("{키}", k))
            }
        }
    }

    // --- 도우미 ----------------------------------------------------------------------------------

    /**
     * `(플레이어) [동작] …` — 첫 낱말이 동작이면 대상은 자기 자신(플레이어일 때), 아니면 첫 낱말이 사람이고 둘째가 동작.
     * 동작이 없으면 [default].
     */
    private fun targetAction(sender: CommandSender, args: List<String>, actions: Map<String, String>, default: String): Triple<UserRecord, String, List<String>>? {
        val first = args.firstOrNull()
        if (first != null && actions.containsKey(first.lowercase())) {
            val self = sender as? Player ?: return null.also { ess.messages.send(sender, "player-only") }
            return Triple(ess.users.get(self.uniqueId) ?: UserRecord(self.uniqueId, self.name, 0L, 0L), actions.getValue(first.lowercase()), args.drop(1))
        }
        if (first == null) {
            val self = sender as? Player ?: return null.also { ess.messages.send(sender, "player-only") }
            return Triple(ess.users.get(self.uniqueId) ?: UserRecord(self.uniqueId, self.name, 0L, 0L), default, emptyList())
        }
        val user = mod.user(sender, first) ?: return null
        val action = args.getOrNull(1)?.lowercase()?.let { actions[it] } ?: return Triple(user, default, args.drop(1))
        return Triple(user, action, args.drop(2))
    }

    private fun actionHints(actions: Map<String, String>) = ModCmds.hints { sender, before ->
        when (before.size) {
            0 -> actions.keys.filter { it.first().code > 127 }
            1 -> if (actions[before[0].lowercase()] == "list") emptyList() else Vanish.visibleNames(sender)
            else -> emptyList()
        }
    }

    private fun playerThenActions(actions: Map<String, String>) = ModCmds.hints { sender, before ->
        when (before.size) {
            0 -> actions.keys.filter { it.first().code > 127 } + Vanish.visibleNames(sender)
            1 -> if (actions.containsKey(before[0].lowercase())) emptyList() else actions.keys.filter { it.first().code > 127 }
            else -> emptyList()
        }
    }

    private val metaHints = ModCmds.hints { _, before ->
        when (before.size) {
            0 -> META_ACTIONS.keys.filter { it.first().code > 127 }
            else -> listOf("-s")
        }
    }

    companion object {
        val ALERT_ACTIONS = linkedMapOf("추가" to "add", "삭제" to "remove", "목록" to "list", "하루추가" to "extend",
            "add" to "add", "remove" to "remove", "list" to "list", "extend" to "extend")
        val NOTE_ACTIONS = linkedMapOf("추가" to "add", "삭제" to "remove", "비우기" to "clear", "목록" to "list",
            "add" to "add", "remove" to "remove", "clear" to "clear", "list" to "list")
        val LOCK_ACTIONS = NOTE_ACTIONS
        val META_ACTIONS = linkedMapOf("추가" to "add", "삭제" to "remove", "비우기" to "clear", "목록" to "list", "증가" to "increment",
            "add" to "add", "set" to "add", "remove" to "remove", "clear" to "clear", "list" to "list", "increment" to "increment")
    }
}

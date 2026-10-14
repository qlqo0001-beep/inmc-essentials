package com.inmc.essentials.moderation

import com.inmc.essentials.moderation.ModCmds.greedy
import com.inmc.essentials.moderation.ModCmds.literal
import com.inmc.essentials.moderation.ModCmds.sender
import com.inmc.essentials.moderation.ModCmds.tokens
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.store.LogoutSpot
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import kr.inmc.core.integration.Selections
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/감옥`(jail) · `/석방`(unjail) · `/감옥목록`(jaillist) · `/감옥편집`(jailedit). */
class JailCommands(private val mod: Moderation, private val jails: JailService) {

    private val ess get() = mod.ess

    fun register() {
        ess.commands.add(
            literal("감옥", ModerationFeature.JAIL).executes { ctx -> mod.usage(sender(ctx), "/감옥 [플레이어] (시간) (감옥) (감방) (-s) (r:사유)"); 1 }
                .then(ess.args.player("플레이어").executes { ctx -> jail(ctx); 1 }.then(greedy().suggests(jailHints).executes { ctx -> jail(ctx); 1 }))
                .build(),
            "감옥에 가두기", listOf("jail"),
        )
        ess.commands.add(
            literal("석방", ModerationFeature.UNJAIL).executes { ctx -> mod.usage(sender(ctx), "/석방 [플레이어] (-s)"); 1 }
                .then(ess.args.player("플레이어").suggests(jailedNames).executes { ctx -> unjail(ctx); 1 }.then(greedy().executes { ctx -> unjail(ctx); 1 }))
                .build(),
            "감옥에서 풀기", listOf("unjail"),
        )
        ess.commands.add(
            literal("감옥목록", ModerationFeature.JAILLIST).executes { ctx -> list(ctx); 1 }
                .then(greedy().suggests(ModCmds.hints { _, before -> if (before.isEmpty()) jails.jails.map { it.name } else emptyList() }).executes { ctx -> list(ctx); 1 })
                .build(),
            "감옥·갇힌 사람 목록", listOf("jaillist"),
        )
        ess.commands.add(
            literal("감옥편집", ModerationFeature.JAILEDIT).executes { ctx ->
                (sender(ctx) as? Player)?.let { JailEditMenu(ess, it, mod, jails, this).show() } ?: ess.messages.send(sender(ctx), "player-only"); 1
            }
                .then(greedy().suggests(editHints).executes { ctx -> edit(ctx); 1 })
                .build(),
            "감옥 만들기·고치기", listOf("jailedit"),
        )
    }

    // --- /감옥 -------------------------------------------------------------------------------------

    private fun jail(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val user = mod.user(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        val flags = ModRules.flags(tokens(ctx), reasonTag = true)
        val rest = flags.rest.toMutableList()
        var change: ModRules.Change? = null
        var jailName: String? = null
        var cell: Int? = null
        rest.firstOrNull()?.let { t -> ModRules.parseChange(t)?.let { change = it; rest.removeAt(0) } }
        rest.firstOrNull()?.let { t -> jails.jail(t)?.let { jailName = it.name; rest.removeAt(0) } }
        if (jailName != null) rest.firstOrNull()?.toIntOrNull()?.let { cell = it; rest.removeAt(0) }
        val reason = flags.reason ?: rest.joinToString(" ").ifBlank { null }
        jails.imprison(sender, user, change, jailName, cell, reason, flags.silent)
    }

    private fun unjail(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val user = mod.user(sender, ModCmds.arg(ctx, "플레이어")) ?: return
        if (!jails.release(sender, user.id, ModRules.flags(tokens(ctx)).silent, auto = false)) {
            ess.messages.send(sender, "moderation-unjail-not", Ph.of().player(user.name))
        }
    }

    // --- /감옥목록 ----------------------------------------------------------------------------------

    private fun list(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val args = tokens(ctx)
        val jail = args.firstOrNull()?.let { name -> jails.jail(name) ?: return ess.messages.send(sender, "moderation-jail-unknown", Ph.of().value(name)) }
        val cell = args.getOrNull(1)?.toIntOrNull()
        if (sender is Player) {
            return if (jail == null) JailListMenu(ess, sender, mod, jails).show() else JailedMenu(ess, sender, mod, jails, jail.name, cell, null).show()
        }
        chatList(sender, jail?.name, cell)
    }

    private fun chatList(sender: CommandSender, jail: String?, cell: Int?) {
        if (jail == null) {
            if (jails.jails.isEmpty()) return ess.messages.send(sender, "moderation-jaillist-empty")
            jails.jails.forEachIndexed { i, j ->
                ess.messages.send(sender, "moderation-jaillist-jail", Ph.of().raw("{번호}", (i + 1).toString()).raw("{감옥}", j.name)
                    .value(j.cells.size.toString()).count(jails.occupants(j.name).size))
            }
            return
        }
        val inside = jails.occupants(jail, cell)
        if (inside.isEmpty()) return ess.messages.send(sender, "moderation-jaillist-none")
        inside.forEachIndexed { i, j ->
            val name = ess.users.get(j.id)?.name ?: j.id.toString()
            ess.messages.send(sender, "moderation-jaillist-jailed", Ph.of().raw("{번호}", (i + 1).toString()).player(name).raw("{감옥}", j.jail)
                .raw("{감방}", j.cell?.toString() ?: "-").time(JailService.remainingText(mod, j)).raw("{관리자}", j.source).raw("{명령}", "/석방 $name"))
        }
    }

    // --- /감옥편집 ----------------------------------------------------------------------------------

    private fun edit(ctx: CommandContext<CommandSourceStack>) {
        val sender = sender(ctx)
        val args = tokens(ctx)
        val action = args.firstOrNull()?.lowercase() ?: return
        val name = args.getOrNull(1) ?: return mod.usage(sender, "/감옥편집 [만들기|삭제|영역|석방위치|감방추가|감방삭제|보기|이동] [이름] (번호)")
        val player = sender as? Player
        when (EDIT[action]) {
            "create" -> player?.let { create(it, name) } ?: ess.messages.send(sender, "player-only")
            "remove" -> {
                val jail = jails.jail(name) ?: return ess.messages.send(sender, "moderation-jail-unknown", Ph.of().value(name))
                val freed = jails.remove(jail.name)
                ess.messages.send(sender, "moderation-jailedit-removed", Ph.of().raw("{감옥}", jail.name).count(freed))
            }
            "area" -> player?.let { area(it, name) } ?: ess.messages.send(sender, "player-only")
            "outside" -> {
                player ?: return ess.messages.send(sender, "player-only")
                val jail = jails.jail(name) ?: return ess.messages.send(sender, "moderation-jail-unknown", Ph.of().value(name))
                jails.replace(jail.copy(outside = LogoutSpot.of(player.location)))
                ess.messages.send(sender, "moderation-jailedit-outside-set", Ph.of().raw("{감옥}", jail.name))
            }
            "addcell" -> player?.let { addCell(it, name) } ?: ess.messages.send(sender, "player-only")
            "removecell" -> {
                val jail = jails.jail(name) ?: return ess.messages.send(sender, "moderation-jail-unknown", Ph.of().value(name))
                val id = args.getOrNull(2)?.toIntOrNull()
                val cell = id?.let { jail.cell(it) } ?: return ess.messages.send(sender, "moderation-jail-cell-unknown",
                    Ph.of().value(args.getOrNull(2) ?: "?").raw("{감옥}", jail.name))
                jails.replace(jail.copy(cells = jail.cells - cell))
                for (j in jails.occupants(jail.name, cell.id)) {
                    j.cell = null
                    mod.store.putJailed(j)
                }
                ess.messages.send(sender, "moderation-jailedit-cell-removed", Ph.of().raw("{감옥}", jail.name).raw("{감방}", cell.id.toString()))
            }
            "show" -> {
                player ?: return ess.messages.send(sender, "player-only")
                val jail = jails.jail(name) ?: return ess.messages.send(sender, "moderation-jail-unknown", Ph.of().value(name))
                jails.show(player, jail)
                ess.messages.send(sender, "moderation-jailedit-show", Ph.of().raw("{감옥}", jail.name))
            }
            "tp" -> {
                player ?: return ess.messages.send(sender, "player-only")
                val jail = jails.jail(name) ?: return ess.messages.send(sender, "moderation-jail-unknown", Ph.of().value(name))
                val to = (jail.cells.firstOrNull()?.spot ?: jail.area.center()).toLocation() ?: return
                val label = ModCmds.label(ctx)
                ess.teleporter.teleport(player, to, TeleportKind.ADMIN).thenAccept { ok -> if (ok == true) Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ -> ess.gate?.commit(player, label) } }
            }
            else -> mod.usage(sender, "/감옥편집 [만들기|삭제|영역|석방위치|감방추가|감방삭제|보기|이동] [이름] (번호)")
        }
    }

    /** 새 감옥 — WorldEdit 선택이 있으면 그 상자, 없으면 선 자리 둘레(설정 `jail.stand-radius`). */
    fun create(player: Player, name: String) {
        if (!ModRules.validName(name)) return ess.messages.send(player, "moderation-jailedit-invalid-name")
        if (jails.jail(name) != null) return ess.messages.send(player, "moderation-jailedit-exists", Ph.of().raw("{감옥}", name))
        val (area, source) = pickArea(player)
        jails.jails.firstOrNull { it.area.overlaps(area) }?.let { return ess.messages.send(player, "moderation-jailedit-collides", Ph.of().raw("{감옥}", it.name)) }
        val first = if (area.contains(player.location)) listOf(JailCell(1, LogoutSpot.of(player.location)!!)) else emptyList()
        jails.replace(Jail(name, area, null, first))
        ess.messages.send(player, "moderation-jailedit-created", Ph.of().raw("{감옥}", name).value("$source · ${area.describe()}"))
    }

    fun area(player: Player, name: String) {
        val jail = jails.jail(name) ?: return ess.messages.send(player, "moderation-jail-unknown", Ph.of().value(name))
        val (area, source) = pickArea(player)
        jails.jails.firstOrNull { it.name != jail.name && it.area.overlaps(area) }?.let {
            return ess.messages.send(player, "moderation-jailedit-collides", Ph.of().raw("{감옥}", it.name))
        }
        val dropped = jails.replaceArea(jail, area)
        ess.messages.send(player, "moderation-jailedit-area-set", Ph.of().raw("{감옥}", jail.name).value("$source · ${area.describe()}"))
        if (dropped.isNotEmpty()) ess.messages.send(player, "moderation-jailedit-cells-dropped",
            Ph.of().raw("{감옥}", jail.name).count(dropped.size).raw("{목록}", dropped.joinToString(", ") { "${it.id}번" }))
    }

    fun addCell(player: Player, name: String) {
        val jail = jails.jail(name) ?: return ess.messages.send(player, "moderation-jail-unknown", Ph.of().value(name))
        if (!jail.area.contains(player.location)) return ess.messages.send(player, "moderation-jailedit-not-in-area", Ph.of().raw("{감옥}", jail.name))
        val cell = JailCell(jail.nextCellId(), LogoutSpot.of(player.location)!!)
        jails.replace(jail.copy(cells = jail.cells + cell))
        ess.messages.send(player, "moderation-jailedit-cell-added", Ph.of().raw("{감옥}", jail.name).raw("{감방}", cell.id.toString()))
    }

    private fun pickArea(player: Player): Pair<JailArea, String> {
        val sel = Selections.of(player)
        if (sel != null) return JailArea(sel.world.name, sel.minX, sel.minY, sel.minZ, sel.maxX, sel.maxY, sel.maxZ) to
            ess.messages.raw("moderation-jailedit-source-selection")
        val l = player.location
        val r = mod.settings.jailRadius
        return JailArea.around(l.world.name, l.blockX, l.blockY, l.blockZ, r) to ess.messages.raw("moderation-jailedit-source-radius").replace("{값}", r.toString())
    }

    // --- 제안 ------------------------------------------------------------------------------------

    private val jailHints = ModCmds.hints { _, before ->
        when (before.size) {
            0 -> listOf("5m", "1h", "1d") + jails.jails.map { it.name } + listOf("r:", "-s")
            else -> {
                val jail = before.lastOrNull()?.let { jails.jail(it) }
                jail?.cells?.map { it.id.toString() }.orEmpty() + jails.jails.map { it.name } + listOf("r:", "-s")
            }
        }
    }

    private val editHints = ModCmds.hints { _, before ->
        when (before.size) {
            0 -> EDIT.keys.filter { it.first().code > 127 }.toList()
            1 -> if (EDIT[before[0].lowercase()] == "create") emptyList() else jails.jails.map { it.name }
            2 -> jails.jail(before[1])?.cells?.map { it.id.toString() }.orEmpty()
            else -> emptyList()
        }
    }

    private val jailedNames = ModCmds.hints { sender, before ->
        if (before.isNotEmpty()) emptyList()
        else mod.state.jailed.keys.filter { id -> Bukkit.getPlayer(id)?.let { Vanish.canSee(sender, it) } ?: true }.mapNotNull { ess.users.get(it)?.name }
    }

    companion object {
        /** 하위 명령 — 한글·영어 → 뜻. */
        val EDIT: Map<String, String> = linkedMapOf(
            "만들기" to "create", "삭제" to "remove", "영역" to "area", "석방위치" to "outside", "감방추가" to "addcell", "감방삭제" to "removecell",
            "보기" to "show", "이동" to "tp",
            "create" to "create", "remove" to "remove", "area" to "area", "outside" to "outside", "tp" to "tp", "addcell" to "addcell",
            "removecell" to "removecell", "show" to "show",
        )
    }
}

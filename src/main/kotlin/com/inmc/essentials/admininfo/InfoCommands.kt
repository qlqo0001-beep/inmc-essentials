package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.integration.Vanish
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** `/정보` · `/마지막접속` · `/최근접속` · `/실명` · `/목록` · `/오피목록` · `/권한확인`. 투명인 사람은 볼 수 있는 사람에게만 "접속 중"(규칙 8). */
internal class InfoCommands(private val ess: Ess, private val feature: AdminInfoFeature) {

    private val abilities = PlayerAbilities(ess)

    fun register() {
        ess.commands.add(infoNode(), "플레이어 종합 정보", listOf("info", "whois"))
        ess.commands.add(seenNode(), "마지막 접속", listOf("seen"))
        ess.commands.add(lastOnlineNode(), "최근에 나간 사람", listOf("lastonline"))
        ess.commands.add(realNameNode(), "닉네임 → 실제 이름", listOf("realname"))
        ess.commands.add(listNode(), "접속자 목록(바닐라 /list 를 덮는다)", listOf("list"))
        ess.commands.add(opListNode(), "OP 목록", listOf("oplist"))
        ess.commands.add(hasPermissionNode(), "권한 확인", listOf("haspermission"))
    }

    /** [sender] 에게 접속 중으로 보이는 [id] 의 플레이어(투명이라 못 보면 null). */
    private fun visible(sender: CommandSender, id: UUID): Player? = Bukkit.getPlayer(id)?.takeIf { Vanish.canSee(sender, it) }

    /** 마지막으로 본 때. 투명이라 가리는 사람(접속 중이든 투명인 채 나갔든)은 투명을 켠 때의 값 — 접속하며 투명이 됐으면 지난 접속의 나감. */
    internal fun lastSeen(id: UUID): Long? {
        val user = ess.users.get(id)
        feature.vanish.seen(id)?.let { (_, quit) -> return quit }
        // 접속 중인데 못 보는 사람(다른 플러그인의 투명)도 마지막 나감은 아직 지난 접속의 것 — 이번 접속 시각은 쓰지 않는다.
        return user?.lastQuit ?: user?.lastJoin
    }

    /** 보이는 마지막 접속 — 가리는 사람은 투명을 켜기 전 값. */
    private fun lastJoinShown(id: UUID): Long? {
        feature.vanish.seen(id)?.let { (join, _) -> return join }
        return ess.users.get(id)?.lastJoin
    }

    // --- /정보 ---------------------------------------------------------------------------------------

    private fun infoNode() = Commands.literal("정보").requires(AdminText.perm(AdminInfoFeature.INFO))
        .executes { ctx -> info(ctx, null); 1 }
        .then(ess.args.player().executes { ctx -> info(ctx, StringArgumentType.getString(ctx, "플레이어")); 1 })
        .build()

    private fun info(ctx: CommandContext<CommandSourceStack>, input: String?) {
        val sender = ctx.source.sender
        val (id, name) = if (input == null) {
            val me = AdminText.self(ctx) ?: return ess.messages.send(sender, "player-only")
            me.uniqueId to me.name
        } else AdminText.offline(ess, sender, input) ?: return
        val online = visible(sender, id)
        if (online != null || !LuckPermsBridge.available) {
            val group = online?.let { LuckPermsBridge.primaryGroup(id) }
            val prefix = online?.let { LuckPermsBridge.prefix(id) }
            return render(sender, id, name, group, prefix)
        }
        // 접속 안 한(또는 이 사람에게는 안 보이는) 사람의 그룹·접두는 LuckPerms 가 비동기로 읽는다 — 다 읽은 뒤 메인에서 한 번에.
        LuckPermsBridge.offlineInfo(id).completeOnTimeout(null, 5, TimeUnit.SECONDS).thenAccept { found ->
            Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ -> render(sender, id, name, found?.first, found?.second) }
        }
    }

    private fun render(sender: CommandSender, id: UUID, name: String, group: String?, prefix: String?) {
        val user = ess.users.get(id)
        val online = visible(sender, id)
        val now = System.currentTimeMillis()
        fun send(key: String, ph: Ph) = ess.messages.send(sender, key, ph)

        val nick = TitleForgeNames.displayName(id, name)
        val ph = Ph.of().target(AdminText.escape(name))
            .raw("{닉네임}", AdminText.escape(nick))
            .raw("{그룹}", AdminText.escape(group?.let { LuckPermsBridge.group(it).first } ?: "-"))
            .raw("{접두}", prefix?.takeIf { it.isNotBlank() }?.let { CmiConvert.legacyToMini(it) } ?: "<dark_gray>-</dark_gray>")
            .raw("{uuid}", id.toString())
        send("admininfo-info-header", ph)
        send("admininfo-info-names", ph)
        send("admininfo-info-uuid", ph)
        if (sender.hasPermission(AdminInfoFeature.INFO_IP)) {
            val ip = online?.address?.address?.hostAddress ?: user?.lastIp ?: "-"
            send("admininfo-info-ip", Ph.of().value(ip).raw("{핑}", online?.ping?.toString() ?: "-"))
        }
        val joinShown = if (online != null) user?.lastJoin else lastJoinShown(id)
        send("admininfo-info-joins", Ph.of().value(AdminText.date(user?.firstJoin)).time(AdminText.date(joinShown)))
        if (online != null) {
            send("admininfo-info-online", Ph.of().time(AdminText.ago(now - (user?.lastJoin ?: now))))
        } else {
            val seen = lastSeen(id)
            send("admininfo-info-offline", Ph.of().time(seen?.let { AdminText.ago(now - it) } ?: "-"))
        }
        send("admininfo-info-playtime", Ph.of().time(feature.playtime.ticks(id)?.let { AdminText.ticks(it) } ?: "-").count(feature.votes.votes(id)))
        if (ess.economy.isEnabled) {
            val offlinePlayer = online ?: Bukkit.getOfflinePlayer(id)
            send("admininfo-info-money", Ph.of().amount(ess.economy.format(ess.economy.balance(offlinePlayer))))
        }
        val m = ess.moderation
        val flags = Ph.of()
            .raw("{잠수}", if (online != null && ess.afk.isAfk(online)) "<yellow>잠수</yellow>" else "<dark_gray>잠수</dark_gray>")
            .raw("{투명}", if (online != null && Vanish.isVanished(online)) "<light_purple>투명</light_purple>" else "<dark_gray>투명</dark_gray>")
            .raw("{뮤트}", when {
                m.isShadowMuted(id) -> "<dark_red>그림자 뮤트</dark_red>"
                m.isMuted(id) -> "<red>뮤트</red>"
                else -> "<dark_gray>뮤트</dark_gray>"
            })
            .raw("{감옥}", if (m.isJailed(id)) "<red>감옥</red>" else "<dark_gray>감옥</dark_gray>")
            .raw("{OP}", if (Bukkit.getOfflinePlayer(id).isOp) "<gold>OP</gold>" else "<dark_gray>OP</dark_gray>")
        send("admininfo-info-flags", flags)
        if (online == null) {
            user?.logout?.let { spot -> send("admininfo-info-logout", locationPh(spot.world, spot.x, spot.y, spot.z, name)) }
            return
        }
        val p = online
        val maxHp = p.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        // 무적·시간제 비행/무적은 player 기능의 것 — 접점 ess.abilities 로 읽는다(그 기능이 꺼져 있으면 바닐라 무적 표시).
        val god = abilities.god(p)
        val tgod = abilities.godLeft(p)
        val tfly = abilities.flightLeft(p)
        send(
            "admininfo-info-state",
            Ph.of().value(gameMode(p)).raw("{비행}", if (p.isFlying) "비행 중" else if (p.allowFlight) "비행 가능" else "걷기")
                .raw("{시간제비행}", tfly?.let { " (시간제 ${AdminText.escape(it)} 남음)" } ?: "")
                .raw("{비행속도}", "%.2f".format(Locale.ROOT, p.flySpeed)).raw("{걷기속도}", "%.2f".format(Locale.ROOT, p.walkSpeed))
                .raw("{무적}", if (god) "<gold>무적</gold>" else "<dark_gray>무적</dark_gray>")
                .raw("{시간제무적}", tgod?.let { " <gray>(시간제 ${AdminText.escape(it)} 남음)</gray>" } ?: ""),
        )
        send(
            "admininfo-info-health",
            Ph.of().raw("{체력}", "%.1f".format(Locale.ROOT, p.health)).raw("{최대}", "%.1f".format(Locale.ROOT, maxHp))
                .raw("{공기}", "${p.remainingAir}/${p.maximumAir}").raw("{배고픔}", p.foodLevel.toString())
                .raw("{포만}", "%.1f".format(Locale.ROOT, p.saturation)),
        )
        send(
            "admininfo-info-exp",
            Ph.of().raw("{레벨}", p.level.toString()).raw("{경험치}", p.totalExperience.toString()).raw("{진행}", "${(p.exp * 100).toInt()}%"),
        )
        val loc = p.location
        send("admininfo-info-location", locationPh(loc.world?.name ?: "?", loc.x, loc.y, loc.z, name))
        p.respawnLocation?.let { send("admininfo-info-bed", locationPh(it)) }
        p.lastDeathLocation?.let { send("admininfo-info-death", locationPh(it)) }
        // 전환 단추 — player 기능의 /비행 · /무적 을 그대로 친다(그 기능의 권한·저장·알림을 거친다). 명령이 없으면 단추도 없다.
        if (sender is Player && sender.hasPermission(AdminInfoFeature.INFO_TOGGLE)) {
            val names = ess.commands.names()
            val buttons = listOfNotNull(
                ess.messages.component("admininfo-info-toggle-fly").clickEvent(ClickEvent.runCommand("/비행 ${p.name}")).takeIf { "비행" in names },
                ess.messages.component("admininfo-info-toggle-god").clickEvent(ClickEvent.runCommand("/무적 ${p.name}")).takeIf { "무적" in names },
            )
            if (buttons.isNotEmpty()) sender.sendMessage(Component.join(JoinConfiguration.separator(Component.space()), buttons))
        }
    }

    private fun gameMode(p: Player): String = when (p.gameMode) {
        org.bukkit.GameMode.SURVIVAL -> "서바이벌"
        org.bukkit.GameMode.CREATIVE -> "크리에이티브"
        org.bukkit.GameMode.ADVENTURE -> "모험"
        org.bukkit.GameMode.SPECTATOR -> "관전"
    }

    private fun locationPh(l: Location): Ph = locationPh(l.world?.name ?: "?", l.x, l.y, l.z, null)

    private fun locationPh(world: String, x: Double, y: Double, z: Double, name: String?): Ph =
        Ph.of().world(AdminText.escape(world)).raw("{x}", x.toInt().toString()).raw("{y}", y.toInt().toString()).raw("{z}", z.toInt().toString())
            .target(AdminText.escape(name ?: ""))

    // --- /마지막접속 ------------------------------------------------------------------------------------

    private fun seenNode() = Commands.literal("마지막접속").requires(AdminText.perm(AdminInfoFeature.SEEN))
        .then(ess.args.player().executes { ctx -> seen(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어")); 1 })
        .build()

    private fun seen(sender: CommandSender, input: String) {
        val (id, name) = AdminText.offline(ess, sender, input) ?: return
        val now = System.currentTimeMillis()
        val online = visible(sender, id)
        val user = ess.users.get(id)
        val ph = Ph.of().target(AdminText.escape(name))
        if (online != null) {
            ess.messages.send(sender, "admininfo-seen-online", ph.time(AdminText.ago(now - (user?.lastJoin ?: now))))
        } else {
            val at = lastSeen(id) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(AdminText.escape(input)))
            ess.messages.send(sender, "admininfo-seen-offline", ph.time(AdminText.ago(now - at)).value(AdminText.date(at)))
        }
    }

    // --- /최근접속 ---------------------------------------------------------------------------------------

    private fun lastOnlineNode() = Commands.literal("최근접속").requires(AdminText.perm(AdminInfoFeature.LASTONLINE))
        .executes { ctx -> lastOnline(ctx.source.sender, 0); 1 }
        .then(Commands.argument("쪽", IntegerArgumentType.integer(1)).executes { ctx -> lastOnline(ctx.source.sender, IntegerArgumentType.getInteger(ctx, "쪽") - 1); 1 })
        .build()

    private fun lastOnline(sender: CommandSender, page: Int) {
        val now = System.currentTimeMillis()
        // 보이는 접속자는 "지금", 나머지(투명으로 숨는 접속자 포함)는 마지막으로 본 때.
        val seen = ess.users.all().mapNotNull { u ->
            val at = if (visible(sender, u.id) != null) now else lastSeen(u.id)
            at?.let { Triple(u.id, u.name, it) }
        }
        val parts = feature.settings.lastOnlineTimers.joinToString(" · ") { seconds ->
            val n = seen.count { now - it.third <= seconds * 1000 }
            Ph.of().time(AdminText.ago(seconds * 1000)).count(n).apply(ess.messages.raw("admininfo-lastonline-window"))
        }
        ess.messages.send(sender, "admininfo-lastonline-summary", Ph.of().value(parts))
        val offline = seen.filter { visible(sender, it.first) == null }.sortedByDescending { it.third }
        val size = feature.settings.pageSize
        val pages = maxOf(1, (offline.size + size - 1) / size)
        val p = page.coerceIn(0, pages - 1)
        ess.messages.send(sender, "admininfo-lastonline-header", Ph.of().raw("{쪽}", (p + 1).toString()).raw("{전체쪽}", pages.toString()).count(offline.size))
        for ((i, row) in offline.withIndex().drop(p * size).take(size)) {
            ess.messages.send(
                sender, "admininfo-lastonline-line",
                Ph.of().raw("{순번}", (i + 1).toString()).player(AdminText.escape(row.second)).time(AdminText.ago(now - row.third)),
            )
        }
    }

    // --- /실명 ------------------------------------------------------------------------------------------

    private fun realNameNode() = Commands.literal("실명").requires(AdminText.perm(AdminInfoFeature.REALNAME))
        .then(Commands.argument("닉네임", StringArgumentType.greedyString()).suggests(ess.args.names).executes { ctx -> realName(ctx.source.sender, StringArgumentType.getString(ctx, "닉네임")); 1 })
        .build()

    private fun realName(sender: CommandSender, input: String) {
        val text = input.trim()
        val owner = ess.args.nicknameOwner(text)
        if (owner != null) {
            val name = ess.users.get(owner)?.name ?: Bukkit.getOfflinePlayer(owner).name ?: owner.toString()
            return ess.messages.send(sender, "admininfo-realname", Ph.of().value(AdminText.escape(text)).target(AdminText.escape(name)))
        }
        val online = ess.args.online(sender, text)
        if (online != null) {
            val nick = TitleForgeNames.displayName(online.uniqueId, online.name)
            return ess.messages.send(sender, "admininfo-realname-nick", Ph.of().target(AdminText.escape(online.name)).value(AdminText.escape(nick)))
        }
        ess.messages.send(sender, "admininfo-realname-none", Ph.of().value(AdminText.escape(text)))
    }

    // --- /목록 -------------------------------------------------------------------------------------------

    private fun listNode() = Commands.literal("목록").requires(AdminText.perm(AdminInfoFeature.LIST))
        .executes { ctx -> list(ctx.source.sender); 1 }
        .build()

    fun list(sender: CommandSender) {
        val all = Bukkit.getOnlinePlayers()
        val shown = all.filter { Vanish.canSee(sender, it) }
        val hidden = shown.count { Vanish.isVanished(it) }
        val ph = Ph.of().count(shown.size - hidden).amount(Bukkit.getMaxPlayers().toString())
            .raw("{투명수}", if (hidden > 0) ess.messages.raw("admininfo-list-hidden-count").replace("{개수}", hidden.toString()) else "")
        ess.messages.send(sender, "admininfo-list-header", ph)
        if (shown.isEmpty()) return
        fun entry(p: Player): String {
            val afk = if (ess.afk.isAfk(p)) ess.messages.raw("admininfo-list-afk") else ""
            val vanished = if (Vanish.isVanished(p)) ess.messages.raw("admininfo-list-hidden") else ""
            return afk + vanished + VanishService.displayName(p)
        }
        val groups = if (feature.settings.listGroups && LuckPermsBridge.available) ListGrouping.players(shown, { LuckPermsBridge.primaryGroup(it.uniqueId) ?: "default" }, { LuckPermsBridge.group(it) }, feature.settings.listHighestFirst)
        else listOf(ListGrouping.Group("", "", 0, shown.sortedBy { it.name.lowercase() }))
        for (g in groups) {
            val names = g.players.joinToString(ess.messages.raw("admininfo-list-separator")) { entry(it) }
            if (g.key.isEmpty()) ess.messages.send(sender, "admininfo-list-all", Ph.of().value(names))
            else ess.messages.send(sender, "admininfo-list-group", Ph.of().subject(AdminText.escape(g.display)).value(names).count(g.players.size))
        }
    }

    // --- /오피목록 ----------------------------------------------------------------------------------------

    private fun opListNode() = Commands.literal("오피목록").requires(AdminText.perm(AdminInfoFeature.OPLIST))
        .executes { ctx ->
            val sender = ctx.source.sender
            val ops = Bukkit.getOperators().sortedBy { (it.name ?: it.uniqueId.toString()).lowercase() }
            ess.messages.send(sender, "admininfo-oplist-header", Ph.of().count(ops.size))
            for ((i, op) in ops.withIndex()) {
                val on = visible(sender, op.uniqueId) != null
                ess.messages.send(
                    sender, if (on) "admininfo-oplist-online" else "admininfo-oplist-offline",
                    Ph.of().raw("{순번}", (i + 1).toString()).player(AdminText.escape(op.name ?: op.uniqueId.toString())),
                )
            }
            1
        }.build()

    // --- /권한확인 ---------------------------------------------------------------------------------------

    private val nodeSuggest = SuggestionProvider<CommandSourceStack> { _, b ->
        val typed = b.remainingLowerCase
        var n = 0
        for (perm in Bukkit.getPluginManager().permissions) {
            if (perm.name.startsWith(typed)) { b.suggest(perm.name); if (++n >= 50) break }
        }
        b.buildFuture()
    }

    private fun hasPermissionNode() = Commands.literal("권한확인").requires(AdminText.perm(AdminInfoFeature.HASPERMISSION))
        .then(
            ess.args.player().then(
                Commands.argument("권한", StringArgumentType.greedyString()).suggests(nodeSuggest).executes { ctx ->
                    hasPermission(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), StringArgumentType.getString(ctx, "권한").trim()); 1
                },
            ),
        ).build()

    private fun hasPermission(sender: CommandSender, input: String, node: String) {
        if (node.isEmpty() || node.contains(' ')) return ess.messages.send(sender, "admininfo-haspermission-usage")
        val online = ess.args.online(sender, input)
        if (online == null) {
            val (id, name) = AdminText.offline(ess, sender, input) ?: return
            ess.messages.send(sender, "admininfo-haspermission-title", Ph.of().target(AdminText.escape(name)).value(AdminText.escape(node)))
            if (!LuckPermsBridge.available) return ess.messages.send(sender, "admininfo-haspermission-offline-unknown")
            LuckPermsBridge.checkOffline(id, node).thenAccept { state ->
                Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ ->
                    if (state == null) ess.messages.send(sender, "admininfo-haspermission-offline-unknown")
                    else ess.messages.send(sender, "admininfo-haspermission-offline", Ph.of().value(state))
                }
            }
            return
        }
        val ph = Ph.of().target(AdminText.escape(online.name)).value(AdminText.escape(node))
        ess.messages.send(sender, "admininfo-haspermission-title", ph)
        val result = online.hasPermission(node)
        val state = Ph.of().value(if (result) "<green>참</green>" else "<red>거짓</red>")
        if (online.isPermissionSet(node)) {
            val by = online.effectivePermissions.firstOrNull { it.permission.equals(node, true) }?.attachment?.plugin?.name ?: "?"
            ess.messages.send(sender, "admininfo-haspermission-hard", state.raw("{플러그인}", AdminText.escape(by)))
        } else if (online.isOp) {
            ess.messages.send(sender, "admininfo-haspermission-op", state)
        } else {
            val declared = Bukkit.getPluginManager().getPermission(node)
            if (declared != null) ess.messages.send(sender, "admininfo-haspermission-soft", state.raw("{기본}", declared.default.name))
            else ess.messages.send(sender, "admininfo-haspermission-none", state)
        }
        // 숫자 권한(`…limit.5` 처럼 끝이 숫자) — 이 노드로 시작하는 것들의 최소·최대.
        val prefix = if (node.endsWith(".")) node else "$node."
        val numbers = online.effectivePermissions.filter { it.value && it.permission.startsWith(prefix, true) }
            .mapNotNull { it.permission.substring(prefix.length).toDoubleOrNull() }
        if (numbers.isNotEmpty()) ess.messages.send(sender, "admininfo-haspermission-minmax", Ph.of().raw("{최소}", fmt(numbers.min())).raw("{최대}", fmt(numbers.max())))
    }

    private fun fmt(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}

/** `/목록` 의 그룹 묶기(서버 없이 시험할 수 있게 떼어 둠). */
internal object ListGrouping {

    data class Group<P>(val key: String, val display: String, val weight: Int, val players: List<P>)

    /** [groupOf] 로 묶고 [info](그룹 이름 → 보이는 이름·무게) 무게로 줄 세운다. 같은 무게는 이름 순. 그룹 안은 이름 순(문자열 비교). */
    fun <P> group(players: Collection<P>, groupOf: (P) -> String, info: (String) -> Pair<String, Int>, highestFirst: Boolean, nameOf: (P) -> String = { it.toString() }): List<Group<P>> {
        val byGroup = players.groupBy { groupOf(it).lowercase() }
        val groups = byGroup.map { (key, list) ->
            val (display, weight) = info(key)
            Group(key, display, weight, list.sortedBy { nameOf(it).lowercase() })
        }
        val cmp = compareBy<Group<P>> { if (highestFirst) -it.weight else it.weight }.thenBy { it.key }
        return groups.sortedWith(cmp)
    }

    /** Player 판 — 이름으로 줄 세운다. */
    fun players(players: Collection<Player>, groupOf: (Player) -> String, info: (String) -> Pair<String, Int>, highestFirst: Boolean): List<Group<Player>> =
        group(players, groupOf, info, highestFirst) { it.name }
}

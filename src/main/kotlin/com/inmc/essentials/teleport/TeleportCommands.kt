package com.inmc.essentials.teleport

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

private typealias Ctx = CommandContext<CommandSourceStack>

/**
 * 이동 기능의 명령(계획 4절 표 그대로) — 이동 · 소환 · 강제이동 · 강제소환 · 전원소환 · 월드전원소환 · 좌표이동 · 강제좌표이동 ·
 * 이동요청 · 소환요청 · 전원소환요청 · 수락 · 거절 · 요청차단 · 뒤로 · 사망지점 · 뒤로초기화.
 *
 * 바닐라 `/tp` 는 그대로 바닐라다(그 이동도 사건 리스너가 뒤로에 적는다). 사람 인자는 `ess.args`(투명 거르기·부분 이름·닉네임).
 * 이동이 성공한 순간 친 사람의 명령 이름으로 `ess.gate?.commit` — "성공해야 쿨타임".
 */
class TeleportCommands(
    private val ess: Ess,
    private val feature: TeleportFeature,
    private val service: TeleportService,
    private val tpa: TpaFlow,
) {

    fun register() {
        ess.commands.add(tp("이동", force = false), "플레이어에게 이동 · 남을 남에게 보내기", listOf("tpto"))
        ess.commands.add(here("소환", force = false), "플레이어를 내 자리로", listOf("tphere", "s"))
        ess.commands.add(tp("강제이동", force = true), "요청 차단을 무시하고 이동", listOf("tpo"))
        ess.commands.add(here("강제소환", force = true), "요청 차단을 무시하고 소환", listOf("tpohere"))
        ess.commands.add(tpAll(), "접속자 전원을 내(또는 그 사람) 자리로", listOf("tpall"))
        ess.commands.add(tpAllWorld(), "한 월드에 있는(나간 사람 포함) 전원을 옮기기", listOf("tpallworld"))
        ess.commands.add(tpPos("좌표이동", force = false), "좌표로 이동", listOf("tppos"))
        ess.commands.add(tpPos("강제좌표이동", force = true), "좌표로 그대로 이동(안전 확인 없음)", listOf("tpopos"))
        ess.commands.add(tpaRequest("이동요청", here = false, TeleportFeature.TPA), "플레이어에게 가도 되는지 요청", listOf("tpa"))
        ess.commands.add(tpaRequest("소환요청", here = true, TeleportFeature.TPAHERE), "플레이어에게 내게 오라고 요청", listOf("tpahere"))
        ess.commands.add(tpaAll(), "접속자 전원에게 내게 오라고 요청", listOf("tpaall"))
        ess.commands.add(answer("수락", accept = true), "받은 이동 요청 수락", listOf("tpaccept", "tpyes"))
        ess.commands.add(answer("거절", accept = false), "받은 이동 요청 거절", listOf("tpdeny", "tpno"))
        ess.commands.add(toggle(), "이동 요청 받기 켜고 끄기 · 한 사람의 요청 막기", listOf("tptoggle"))
        ess.commands.add(back("뒤로", death = false), "마지막 이동 전 자리로", listOf("back"))
        ess.commands.add(back("사망지점", death = true), "마지막으로 죽은 자리로", listOf("dback"))
        ess.commands.add(resetBack(), "뒤로·사망 자리 지우기", listOf("resetback"))
    }

    // --- 공통 -------------------------------------------------------------------------------------

    private fun perm(node: String): (CommandSourceStack) -> Boolean = { it.sender.hasPermission(node) }

    private fun arg(ctx: Ctx, name: String): String = StringArgumentType.getString(ctx, name)

    /** 친 명령 이름(앞의 `/`·`플러그인:` 없이, 별칭이면 별칭) — 쿨타임 기록용. */
    private fun label(ctx: Ctx): String = ctx.input.trim().removePrefix("/").substringBefore(' ').substringAfterLast(':')

    private fun self(ctx: Ctx): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun commit(sender: CommandSender, ctx: Ctx) {
        (sender as? Player)?.let { ess.gate?.commit(it, label(ctx)) }
    }

    /** [viewer] 에게 [actor] 의 이름 — 투명이라 [viewer] 가 못 보면 "관리자"(CLAUDE.md 8). */
    private fun nameFor(viewer: Player, actor: CommandSender): String =
        if (actor is Player && !Vanish.canSee(viewer, actor)) ess.messages.raw("teleport-someone") else actor.name

    /** 위치를 사람이 읽을 글로. */
    private fun where(location: Location): String =
        "${location.world?.name} ${location.blockX} ${location.blockY} ${location.blockZ}"

    private fun online(ctx: Ctx, input: String): Player? =
        ess.args.online(ctx.source.sender, input) ?: null.also { ess.messages.send(ctx.source.sender, "player-not-found", Ph.of().value(input)) }

    /** 요청 받기를 끈 사람에게는 못 간다(강제·우회 권한이면 된다). 막혔으면 말하고 true. */
    private fun refusedByToggle(sender: CommandSender, target: Player, force: Boolean): Boolean {
        if (target == sender) return false
        if (!service.refuses(target.uniqueId, null, bypass = force || sender.hasPermission(TeleportFeature.TOGGLE_BYPASS))) return false
        ess.messages.send(sender, "teleport-target-not-accepting", Ph.of().target(target.name))
        return true
    }

    /** 여럿을 옮긴 결과 — 모두 끝나면 **옮겨진 수**를 [done] 으로(메인). 실패·예외는 0 으로 센다. */
    private fun whenAllDone(futures: List<java.util.concurrent.CompletableFuture<Boolean>>, done: (Int) -> Unit) {
        val safe = futures.map { f -> f.exceptionally { false } }
        java.util.concurrent.CompletableFuture.allOf(*safe.toTypedArray()).whenComplete { _, _ -> done(safe.count { it.getNow(false) == true }) }
    }

    /** 이름을 남길 때 — 투명이면 null(받는 사람에게 "관리자" 로 보인다, CLAUDE.md 8). */
    private fun recordedName(sender: CommandSender): String? = if (sender is Player && Vanish.isVanished(sender)) null else sender.name

    private fun silentTail(builder: com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, *>, run: (Ctx, Boolean) -> Unit) {
        builder.executes { ctx -> run(ctx, false); 1 }
        builder.then(Commands.literal("-s").executes { ctx -> run(ctx, true); 1 })
    }

    // --- 이동 · 강제이동 ----------------------------------------------------------------------------

    private fun tp(name: String, force: Boolean): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> {
        val base = if (force) TeleportFeature.TPO else TeleportFeature.TP
        val first = ess.args.player("대상")
        silentTail(first) { ctx, _ -> tpTo(ctx, arg(ctx, "대상"), force) }
        val second = ess.args.player("목적지").requires(perm(if (force) TeleportFeature.TPO else TeleportFeature.TP_OTHERS))
        silentTail(second) { ctx, silent ->
            val (who, whereTo) = if (feature.settings.switchPlaces) arg(ctx, "대상") to arg(ctx, "목적지") else arg(ctx, "목적지") to arg(ctx, "대상")
            sendTo(ctx, who, whereTo, force, silent, TeleportSettings.FX_TP)
        }
        first.then(second)
        return Commands.literal(name).requires(perm(base)).then(first).build()
    }

    /** 나를 [input] 에게. 나가 있는 사람이면 나간 자리로(권한). */
    private fun tpTo(ctx: Ctx, input: String, force: Boolean) {
        val sender = ctx.source.sender
        val me = self(ctx) ?: return
        val target = ess.args.online(sender, input)
        if (target == null) {
            tpToOffline(ctx, me, input)
            return
        }
        if (target == me) {
            ess.messages.send(sender, "teleport-self")
            return
        }
        if (refusedByToggle(sender, target, force)) return
        // 강제가 아니면 사람이 고른 자기 이동(OTHER + TP) — 금지 아이템의 TP 칸, 안전한 자리가 없으면 막힌다.
        feature.move(me, target.location, if (force) TeleportKind.ADMIN else TeleportKind.OTHER, command = TeleportSettings.FX_TP).thenAccept { ok ->
            if (ok != true) return@thenAccept
            ess.messages.send(me, "teleport-tp-done", Ph.of().target(target.name))
            commit(sender, ctx)
        }
    }

    private fun tpToOffline(ctx: Ctx, me: Player, input: String) {
        val sender = ctx.source.sender
        val record = if (sender.hasPermission(TeleportFeature.OFFLINE)) ess.args.offline(sender, input) else null
        // 기록은 있지만 실제로는 접속 중(투명이라 안 보임)이면 "없음" — 투명을 새지 않는다.
        if (record == null || Bukkit.getPlayer(record.id) != null) {
            ess.messages.send(sender, "player-not-found", Ph.of().value(input))
            return
        }
        val spot = record.logout?.toLocation()
        if (spot == null) {
            ess.messages.send(sender, "teleport-offline-no-spot", Ph.of().target(record.name))
            return
        }
        feature.move(me, spot, TeleportKind.ADMIN, command = TeleportSettings.FX_TP).thenAccept { ok ->
            if (ok != true) return@thenAccept
            ess.messages.send(me, "teleport-tp-offline-done", Ph.of().target(record.name).value(where(spot)))
            commit(sender, ctx)
        }
    }

    /** [whoInput] 를 [whereInput] 에게. */
    private fun sendTo(ctx: Ctx, whoInput: String, whereInput: String, force: Boolean, silent: Boolean, effect: String) {
        val sender = ctx.source.sender
        val who = online(ctx, whoInput) ?: return
        val target = online(ctx, whereInput) ?: return
        if (who == target) {
            ess.messages.send(sender, "teleport-same-player")
            return
        }
        if (refusedByToggle(sender, who, force) || refusedByToggle(sender, target, force)) return
        feature.move(who, target.location, TeleportKind.ADMIN, command = effect).thenAccept { ok ->
            if (ok != true) {
                ess.messages.send(sender, "teleport-failed", Ph.of().player(who.name))
                return@thenAccept
            }
            ess.messages.send(sender, "teleport-tp-other-done", Ph.of().player(who.name).target(target.name))
            if (!silent && who != sender) ess.messages.send(who, "teleport-moved-by", Ph.of().player(nameFor(who, sender)).target(target.name))
            commit(sender, ctx)
        }
    }

    // --- 소환 · 강제소환 ----------------------------------------------------------------------------

    private fun here(name: String, force: Boolean): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> {
        val base = if (force) TeleportFeature.TPOHERE else TeleportFeature.HERE
        val first = ess.args.player("대상")
        silentTail(first) { ctx, silent -> summon(ctx, arg(ctx, "대상"), force, silent) }
        val second = ess.args.player("목적지").requires(perm(if (force) TeleportFeature.TPOHERE else TeleportFeature.TP_OTHERS))
        silentTail(second) { ctx, silent -> sendTo(ctx, arg(ctx, "대상"), arg(ctx, "목적지"), force, silent, TeleportSettings.FX_TPHERE) }
        first.then(second)
        // `/소환 취소 <이름>` — 나가 있는 사람에게 걸어 둔 다음 접속 이동을 거둔다.
        val cancel = Commands.literal("취소").requires(perm(TeleportFeature.HERE_OFFLINE))
            .then(ess.args.player("이름").executes { ctx -> cancelPending(ctx, arg(ctx, "이름")); 1 })
        return Commands.literal(name).requires(perm(base)).then(cancel).then(first).build()
    }

    private fun cancelPending(ctx: Ctx, input: String) {
        val sender = ctx.source.sender
        val record = ess.args.offline(sender, input)
        if (record == null) {
            ess.messages.send(sender, "user-not-found", Ph.of().value(input))
            return
        }
        service.cancelPending(record.id)
        ess.messages.send(sender, "teleport-pending-cancelled", Ph.of().target(record.name))
    }

    private fun summon(ctx: Ctx, input: String, force: Boolean, silent: Boolean) {
        val sender = ctx.source.sender
        val me = self(ctx) ?: return
        val target = ess.args.online(sender, input)
        if (target == null) {
            summonOffline(ctx, me, input)
            return
        }
        if (target == me) {
            ess.messages.send(sender, "teleport-self")
            return
        }
        if (refusedByToggle(sender, target, force)) return
        feature.move(target, me.location, TeleportKind.ADMIN, command = TeleportSettings.FX_TPHERE).thenAccept { ok ->
            if (ok != true) {
                ess.messages.send(sender, "teleport-failed", Ph.of().player(target.name))
                return@thenAccept
            }
            ess.messages.send(me, "teleport-here-done", Ph.of().target(target.name))
            if (!silent) ess.messages.send(target, "teleport-here-moved", Ph.of().player(nameFor(target, me)))
            commit(sender, ctx)
        }
    }

    /** 나가 있는 사람 — 다음 접속 때 지금 내 자리로 옮겨지게 적어 둔다(권한). */
    private fun summonOffline(ctx: Ctx, me: Player, input: String) {
        val sender = ctx.source.sender
        val record = if (sender.hasPermission(TeleportFeature.HERE_OFFLINE)) ess.args.offline(sender, input) else null
        if (record == null || Bukkit.getPlayer(record.id) != null) {
            ess.messages.send(sender, "player-not-found", Ph.of().value(input))
            return
        }
        val spot = Spot.of(me.location, System.currentTimeMillis()) ?: return
        service.addPending(record.id, spot, recordedName(me))
        ess.messages.send(sender, "teleport-here-offline", Ph.of().target(record.name))
    }

    // --- 전원소환 · 월드전원소환 ---------------------------------------------------------------------

    private fun tpAll(): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> {
        val root = Commands.literal("전원소환").requires(perm(TeleportFeature.TPALL))
        silentTail(root) { ctx, silent -> self(ctx)?.let { pullAll(ctx, it, silent) } }
        val target = ess.args.player("대상")
        silentTail(target) { ctx, silent -> online(ctx, arg(ctx, "대상"))?.let { pullAll(ctx, it, silent) } }
        return root.then(target).build()
    }

    private fun pullAll(ctx: Ctx, destination: Player, silent: Boolean) {
        val sender = ctx.source.sender
        val movers = Vanish.visibleOnline(sender).filter { it != destination && it != sender }
        val location = destination.location
        val futures = movers.map { mover ->
            feature.move(mover, location, TeleportKind.ADMIN, command = TeleportSettings.FX_TPALL).thenApply { ok ->
                if (ok == true && !silent) ess.messages.send(mover, "teleport-here-moved", Ph.of().player(nameFor(mover, destination)))
                ok == true
            }
        }
        // 옮겨진 사람만 센다(막힌 사람은 빼고) — 다 끝난 뒤에 알린다.
        whenAllDone(futures) { moved ->
            ess.messages.send(sender, "teleport-tpall-done", Ph.of().count(moved).target(destination.name))
            if (moved > 0) commit(sender, ctx)
        }
    }

    private val worldNames = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remainingLowerCase
        runCatching {
            if ("-a".startsWith(typed)) builder.suggest("-a")
            for (world in Bukkit.getWorlds()) if (world.name.lowercase().startsWith(typed)) builder.suggest(world.name)
        }
        builder.buildFuture()
    }

    private fun tpAllWorld(): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> {
        val world = Commands.argument("월드", Args.TOKEN).suggests(worldNames)
        world.executes { ctx -> pullWorld(ctx, arg(ctx, "월드"), null); 1 }
        world.then(Commands.argument("위치", StringArgumentType.greedyString()).executes { ctx -> pullWorld(ctx, arg(ctx, "월드"), arg(ctx, "위치")); 1 })
        // `/월드전원소환 취소` — 걸어 둔 다음 접속 이동(이 명령·`/소환 <나간 사람>` 모두)을 전부 거둔다.
        val cancel = Commands.literal("취소").executes { ctx ->
            val sender = ctx.source.sender
            service.cancelAllPending { count -> ess.messages.send(sender, "teleport-pending-all-cancelled", Ph.of().count(count)) }
            1
        }
        return Commands.literal("월드전원소환").requires(perm(TeleportFeature.TPALLWORLD)).then(cancel).then(world).build()
    }

    /**
     * [worldInput] 에 있는 사람 전부를 [locationText](없으면 내 자리)로. 접속자는 지금, 나가 있는 사람(마지막으로 나간 자리가 그 월드)은
     * 다음 접속 때. `-a` = 지금 없는 월드에서 나간 사람 전부(월드를 지운 뒤 되살리기).
     */
    private fun pullWorld(ctx: Ctx, worldInput: String, locationText: String?) {
        val sender = ctx.source.sender
        val me = ctx.source.sender as? Player
        val destination = if (locationText.isNullOrBlank()) me?.location else resolve(ctx, locationText, me)?.first
        if (destination == null) {
            if (locationText.isNullOrBlank()) ess.messages.send(sender, "teleport-need-location")
            return
        }
        val missing = worldInput == "-a"
        if (!missing && Bukkit.getWorlds().none { it.name.equals(worldInput, ignoreCase = true) } &&
            ess.users.all().none { it.logout?.world.equals(worldInput, ignoreCase = true) }
        ) {
            ess.messages.send(sender, "teleport-world-not-found", Ph.of().world(worldInput))
            return
        }
        // 접속자는 보는 사람이 볼 수 있는 사람만(투명인 관리자를 끌어오지도, 인원수로 새지도 않게 — CLAUDE.md 8).
        val futures = if (missing) emptyList() else Vanish.visibleOnline(sender)
            .filter { it != sender && it.world.name.equals(worldInput, ignoreCase = true) }
            .map { feature.move(it, destination, TeleportKind.ADMIN, command = TeleportSettings.FX_TPALL) }
        val nowMillis = System.currentTimeMillis()
        val spot = Spot.of(destination, nowMillis) ?: return
        val days = feature.settings.tpAllWorldOfflineDays
        val oldest = if (days > 0) nowMillis - days * 86_400_000L else Long.MIN_VALUE
        val by = recordedName(sender)
        var later = 0
        for (record in ess.users.all()) {
            val logout = record.logout ?: continue
            if (Bukkit.getPlayer(record.id) != null) continue
            // 오래전에 떠난 사람까지 적지 않는다(`tp.tpallworld-offline-days`) — 몇 달 뒤 돌아와 옛 행사 자리로 끌려가지 않게.
            if ((record.lastQuit ?: record.lastJoin) < oldest) continue
            val hit = if (missing) Bukkit.getWorld(logout.world) == null else logout.world.equals(worldInput, ignoreCase = true)
            if (!hit) continue
            service.addPending(record.id, spot, by)
            later++
        }
        whenAllDone(futures) { moved ->
            ess.messages.send(
                sender, "teleport-tpallworld-done",
                Ph.of().count(moved).raw("{나간사람}", later.toString()).world(if (missing) "-a" else worldInput),
            )
            if (moved > 0 || later > 0) commit(sender, ctx)
        }
    }

    // --- 좌표이동 · 강제좌표이동 ---------------------------------------------------------------------

    private fun tpPos(name: String, force: Boolean): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name).requires(perm(if (force) TeleportFeature.TPOPOS else TeleportFeature.TPPOS))
            .then(Commands.argument("좌표", StringArgumentType.greedyString()).executes { ctx -> toPosition(ctx, arg(ctx, "좌표"), force); 1 })
            .build()

    /** 좌표 글을 위치로(상대 좌표는 [base] 기준). 틀리면 말하고 null. 두 번째 값 = 읽은 꼬리표. */
    private fun resolve(ctx: Ctx, text: String, base: Player?): Pair<Location, CoordParser.Parsed>? {
        val sender = ctx.source.sender
        val result = CoordParser.parse(text)
        val parsed = result.parsed
        if (parsed == null) {
            ess.messages.send(sender, "teleport-bad-coords", Ph.of().value(result.error ?: text))
            return null
        }
        val named = parsed.world
        val world = if (named != null) {
            Bukkit.getWorlds().firstOrNull { it.name.equals(named, ignoreCase = true) } ?: run {
                ess.messages.send(sender, "teleport-world-not-found", Ph.of().world(named))
                return null
            }
        } else {
            base?.world
        }
        if (world == null) {
            ess.messages.send(sender, "teleport-need-location")
            return null
        }
        val relative = parsed.x.relative || parsed.y.relative || parsed.z.relative
        if (relative && base == null) {
            ess.messages.send(sender, "teleport-need-location")
            return null
        }
        val from = base?.location
        var x = parsed.x.resolve(from?.x ?: 0.0)
        var z = parsed.z.resolve(from?.z ?: 0.0)
        val y = parsed.y.resolve(from?.y ?: 0.0)
        parsed.range?.let { range ->
            val random = ThreadLocalRandom.current()
            x += random.nextInt(-range, range + 1)
            z += random.nextInt(-range, range + 1)
        }
        val location = Location(world, x, y, z, parsed.yaw ?: from?.yaw ?: 0f, parsed.pitch ?: from?.pitch ?: 0f)
        return location to parsed
    }

    private fun toPosition(ctx: Ctx, text: String, force: Boolean) {
        val sender = ctx.source.sender
        val peek = CoordParser.parse(text).parsed
        val mover: Player = if (peek?.target != null) {
            if (!sender.hasPermission(if (force) TeleportFeature.TPOPOS else TeleportFeature.TPPOS_OTHERS)) {
                ess.messages.send(sender, "no-permission")
                return
            }
            online(ctx, peek.target) ?: return
        } else {
            self(ctx) ?: return
        }
        val (location, parsed) = resolve(ctx, text, mover) ?: return
        val kind = if (force || mover != sender) TeleportKind.ADMIN else TeleportKind.OTHER
        // 무작위 범위면 그 높이가 땅속일 수 있다 — 강제여도 안전한 자리를 찾는다.
        val safe = !force || parsed.range != null
        feature.move(mover, location, kind, safe, if (force) TeleportSettings.FX_TPOPOS else TeleportSettings.FX_TPPOS).thenAccept { ok ->
            if (ok != true) {
                if (mover != sender) ess.messages.send(sender, "teleport-failed", Ph.of().player(mover.name))
                return@thenAccept
            }
            val ph = Ph.of().player(mover.name).value(where(mover.location))
            if (mover == sender) {
                ess.messages.send(mover, "teleport-tppos-done", ph)
            } else {
                ess.messages.send(sender, "teleport-tppos-other-done", ph)
                if (!parsed.silent) ess.messages.send(mover, "teleport-tppos-moved", ph.player(nameFor(mover, sender)))
            }
            commit(sender, ctx)
        }
    }

    // --- 이동 요청 ---------------------------------------------------------------------------------

    private fun outgoingNames(): SuggestionProvider<CommandSourceStack> = SuggestionProvider { ctx, builder ->
        val player = ctx.source.sender as? Player
        val typed = builder.remainingLowerCase
        if (player != null) runCatching {
            for (request in service.book.outgoing(player.uniqueId)) if (request.toName.lowercase().startsWith(typed)) builder.suggest(request.toName)
        }
        builder.buildFuture()
    }

    private fun incomingNames(): SuggestionProvider<CommandSourceStack> = SuggestionProvider { ctx, builder ->
        val player = ctx.source.sender as? Player
        val typed = builder.remainingLowerCase
        if (player != null) runCatching {
            for (request in service.book.incoming(player.uniqueId)) if (request.fromName.lowercase().startsWith(typed)) builder.suggest(request.fromName)
        }
        builder.buildFuture()
    }

    private fun tpaRequest(name: String, here: Boolean, node: String): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> =
        Commands.literal(name).requires(perm(node))
            .then(
                Commands.literal("취소").executes { ctx -> self(ctx)?.let { tpa.cancel(it, null) }; 1 }
                    .then(Commands.argument("대상", Args.TOKEN).suggests(outgoingNames()).executes { ctx -> self(ctx)?.let { tpa.cancel(it, arg(ctx, "대상")) }; 1 }),
            )
            .then(
                ess.args.player("대상").executes { ctx ->
                    val me = self(ctx) ?: return@executes 1
                    val target = online(ctx, arg(ctx, "대상")) ?: return@executes 1
                    tpa.request(me, target, here, label(ctx))
                    1
                },
            )
            .build()

    private fun tpaAll(): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> =
        Commands.literal("전원소환요청").requires(perm(TeleportFeature.TPAALL))
            .executes { ctx -> self(ctx)?.let { tpa.requestAll(it, label(ctx)) }; 1 }
            .build()

    private fun answer(name: String, accept: Boolean): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> {
        fun run(ctx: Ctx, who: String?) {
            val me = self(ctx) ?: return
            if (accept) tpa.accept(me, who) else tpa.deny(me, who)
        }
        return Commands.literal(name).requires(perm(if (accept) TeleportFeature.TPACCEPT else TeleportFeature.TPDENY))
            .executes { ctx -> run(ctx, null); 1 }
            .then(Commands.argument("대상", Args.TOKEN).suggests(incomingNames()).executes { ctx -> run(ctx, arg(ctx, "대상")); 1 })
            .build()
    }

    private fun toggle(): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> =
        Commands.literal("요청차단").requires(perm(TeleportFeature.TPTOGGLE))
            .executes { ctx -> self(ctx)?.let { tpa.toggle(it) }; 1 }
            .then(
                ess.args.player("대상").executes { ctx ->
                    val me = self(ctx) ?: return@executes 1
                    val input = arg(ctx, "대상")
                    val record = ess.args.offline(me, input)
                    if (record == null) ess.messages.send(me, "user-not-found", Ph.of().value(input)) else tpa.toggleBlock(me, record.id, record.name)
                    1
                },
            )
            .build()

    // --- 뒤로 · 사망지점 · 뒤로초기화 ---------------------------------------------------------------

    private fun back(name: String, death: Boolean): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> {
        val root = Commands.literal(name).requires(perm(if (death) TeleportFeature.DBACK else TeleportFeature.BACK))
        root.executes { ctx -> self(ctx)?.let { goBack(ctx, it, death, silent = false) }; 1 }
        val target = ess.args.player("대상").requires(perm(if (death) TeleportFeature.DBACK_OTHERS else TeleportFeature.BACK_OTHERS))
        silentTail(target) { ctx, silent -> online(ctx, arg(ctx, "대상"))?.let { goBack(ctx, it, death, silent) } }
        return root.then(target).build()
    }

    private fun goBack(ctx: Ctx, player: Player, death: Boolean, silent: Boolean) {
        val sender = ctx.source.sender
        val s = feature.settings
        val other = player != sender
        val spot = if (death) service.death(player.uniqueId) else service.back(player.uniqueId)
        if (spot == null) {
            ess.messages.send(sender, if (death) "teleport-dback-none" else "teleport-back-none", Ph.of().player(player.name))
            return
        }
        if (spot.expired(if (death) s.deathExpireSeconds else s.backExpireSeconds, System.currentTimeMillis())) {
            service.clear(player.uniqueId, if (death) TeleportData.DEATH else TeleportData.BACK)
            ess.messages.send(sender, if (death) "teleport-dback-none" else "teleport-back-none", Ph.of().player(player.name))
            return
        }
        if (if (death) s.deathExcluded(spot.world) else s.backExcluded(spot.world)) {
            ess.messages.send(sender, "teleport-back-excluded", Ph.of().world(spot.world))
            return
        }
        val location = spot.toLocation()
        if (location == null) {
            ess.messages.send(sender, "teleport-world-gone", Ph.of().world(spot.world))
            return
        }
        ess.teleporter.teleport(player, location, if (death) TeleportKind.DEATH_BACK else TeleportKind.BACK).thenAccept { ok ->
            if (ok != true) {
                if (other) ess.messages.send(sender, "teleport-failed", Ph.of().player(player.name))
                return@thenAccept
            }
            val ph = Ph.of().player(player.name).value(where(location))
            if (other) {
                ess.messages.send(sender, if (death) "teleport-dback-other-done" else "teleport-back-other-done", ph)
                if (!silent) ess.messages.send(player, if (death) "teleport-dback-done" else "teleport-back-done", ph)
            } else {
                ess.messages.send(player, if (death) "teleport-dback-done" else "teleport-back-done", ph)
            }
            commit(sender, ctx)
        }
    }

    private fun resetBack(): com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> {
        val root = Commands.literal("뒤로초기화").requires(perm(TeleportFeature.RESETBACK))
        root.executes { ctx -> self(ctx)?.let { reset(ctx, it.uniqueId, it.name, "") }; 1 }
        val target = ess.args.player("대상")
        target.executes { ctx -> resetOther(ctx, arg(ctx, "대상"), ""); 1 }
        target.then(Commands.argument("옵션", StringArgumentType.greedyString()).executes { ctx -> resetOther(ctx, arg(ctx, "대상"), arg(ctx, "옵션")); 1 })
        return root.then(target).build()
    }

    private fun resetOther(ctx: Ctx, input: String, tail: String) {
        val sender = ctx.source.sender
        // `/뒤로초기화 -death` · `/뒤로초기화 사망 -s` — 대상 없이 꼬리표부터 적으면 나 자신(CMI 와 같은 꼴).
        if (sender is Player && (input.startsWith("-") || input == "사망")) {
            reset(ctx, sender.uniqueId, sender.name, "$input $tail")
            return
        }
        val record = ess.args.offline(sender, input)
        if (record == null) {
            ess.messages.send(sender, "user-not-found", Ph.of().value(input))
            return
        }
        if (record.id != (sender as? Player)?.uniqueId && !sender.hasPermission(TeleportFeature.RESETBACK_OTHERS)) {
            ess.messages.send(sender, "no-permission")
            return
        }
        reset(ctx, record.id, record.name, tail)
    }

    /** 꼬리: `-death`/`사망` = 사망 자리도 · `-s` = 조용히 · 나머지 = 사유(대상에게 보인다). */
    private fun reset(ctx: Ctx, id: UUID, name: String, tail: String) {
        val sender = ctx.source.sender
        val words = Args.split(tail)
        val withDeath = words.any { it.equals("-death", true) || it == "사망" }
        val silent = words.any { it.equals("-s", true) }
        val reason = words.filterNot { it.equals("-death", true) || it == "사망" || it.equals("-s", true) }.joinToString(" ")
        service.clear(id, TeleportData.BACK)
        if (withDeath) service.clear(id, TeleportData.DEATH)
        ess.messages.send(sender, if (withDeath) "teleport-resetback-all-done" else "teleport-resetback-done", Ph.of().player(name))
        val target = Bukkit.getPlayer(id)
        if (target != null && target != sender && !silent) {
            ess.messages.send(target, "teleport-resetback-notice", Ph.of().player(nameFor(target, sender)).reason(reason.ifEmpty { ess.messages.raw("teleport-no-reason") }))
        }
    }
}

package com.inmc.essentials.player

import com.inmc.essentials.Ess
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/** 놀이 명령 — 타기 · 내리기 · 모자 · 앉기. */
internal class FunCommands(private val ess: Ess, private val feature: PlayerFeature, private val cmd: Cmds) {

    private val config get() = feature.config

    fun register() {
        ess.commands.add(mount(), "바라보는 개체·플레이어에 타기 · 목록 · 남이 나에게 타기 허용", listOf("mount"))
        ess.commands.add(shake(), "나(또는 그 사람)에게 탄 것을 모두 내리기", listOf("shakeitoff"))
        ess.commands.add(hat(), "손에 든 아이템을 머리에 쓰기", listOf("hat"))
        ess.commands.add(sit(), "그 자리에 앉기", listOf("sit"))
    }

    // --- 타기 ----------------------------------------------------------------------------------

    private fun typeKey(entity: Entity): String = entity.type.key.key

    /** 이 종류에 탈 수 있나 — `inmcessentials.player.ride.<종류>`(예: `.horse`, `.player`) 또는 `.all`. 막은 종류는 아무도. */
    internal fun canRide(player: Player, type: String, c: PlayerConfig = config): Boolean =
        RideRule.allowed(type, c.rideBlacklist, player.hasPermission(PlayerFeature.RIDE_ALL), player.hasPermission(PlayerFeature.RIDE_PREFIX + type))

    /** 남이 [target] 에게 탈 수 있나(개인 설정 `essentials.ride-me`). 사람이 아니면 늘 된다. */
    internal fun rideMeAllows(target: Entity): Boolean = target !is Player || PlayerSettings.enabled(target, PlayerFeature.SETTING_RIDE_ME, true)

    private fun mount(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("타기").requires(cmd.perm(PlayerFeature.RIDE_TOGGLE))
            .executes { ctx -> cmd.self(ctx)?.let { me -> if (me.hasPermission(PlayerFeature.RIDE)) rideLooked(me) else ess.messages.send(me, "no-permission") }; 1 }
            .then(Commands.literal("목록").requires(cmd.perm(PlayerFeature.RIDE)).executes { ctx -> cmd.self(ctx)?.let { list(it) }; 1 })
            .then(Commands.literal("허용").executes { ctx -> cmd.self(ctx)?.let { toggleRideMe(it) }; 1 })
            .then(
                ess.args.player("대상").requires(cmd.perm(PlayerFeature.RIDE)).executes { ctx ->
                    val me = cmd.self(ctx) ?: return@executes 0
                    val input = cmd.str(ctx, "대상")
                    val target = ess.args.online(me, input) ?: return@executes 0.also { ess.messages.send(me, "player-not-found", Ph.of().value(input)) }
                    if (target.world != me.world || target.location.distance(me.location) > config.rideMaxDistance) {
                        ess.messages.send(me, "player-ride-too-far", Ph.of().target(target.name).value(MaxHpMath.format(config.rideMaxDistance)))
                    } else {
                        ride(me, target)
                    }
                    1
                },
            ).build()

    private fun rideLooked(me: Player) {
        val target = me.getTargetEntity(config.rideMaxDistance.toInt().coerceAtLeast(1))
        if (target == null || (target is Player && !Vanish.canSee(me, target))) return ess.messages.send(me, "player-ride-nothing")
        ride(me, target)
    }

    /** 실제로 태운다. 성공하면 true. */
    fun ride(me: Player, target: Entity): Boolean {
        val type = typeKey(target)
        val name = displayName(target)
        if (target == me || target == me.vehicle || feature.seats.isSeat(target)) return false.also { ess.messages.send(me, "player-ride-cant", Ph.of().subject(name)) }
        if (!canRide(me, type)) return false.also { ess.messages.send(me, "player-ride-cant", Ph.of().subject(name)) }
        if (target is Player && !rideMeAllows(target)) {
            return false.also { ess.messages.send(me, "player-ride-refused", Ph.of().target(target.name)) }
        }
        if (target.passengers.isNotEmpty()) return false.also { ess.messages.send(me, "player-ride-occupied") }
        if (feature.seats.isSeated(me)) feature.seats.stand(me)
        if (me.isInsideVehicle) me.leaveVehicle()
        if (!target.addPassenger(me)) return false.also { ess.messages.send(me, "player-ride-cant", Ph.of().subject(name)) }
        ess.messages.send(me, "player-ride-done", Ph.of().subject(name))
        if (target is Player) ess.messages.send(target, "player-ride-on-you", Ph.of().player(cmd.nameFor(target, me)))
        return true
    }

    private fun displayName(entity: Entity): String = when {
        entity is Player -> entity.name
        entity.customName() != null -> net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(entity.customName()!!)
        else -> "<lang:${entity.type.translationKey()}>"
    }

    private fun list(me: Player) {
        val types = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE)
            .filter { it != EntityType.UNKNOWN && (it.isAlive || it == EntityType.MINECART || it.name.endsWith("BOAT") || it.name.endsWith("RAFT")) }
            .map { it.key.key }
            .filter { canRide(me, it) }
            .sorted()
        if (types.isEmpty()) return ess.messages.send(me, "player-ride-list-none")
        ess.messages.send(me, "player-ride-list", Ph.of().value(types.joinToString(", ")).count(types.size))
    }

    private fun toggleRideMe(me: Player) {
        val now = !PlayerSettings.enabled(me, PlayerFeature.SETTING_RIDE_ME, true)
        PlayerSettings.set(me, PlayerFeature.SETTING_RIDE_ME, now)
        ess.messages.send(me, if (now) "player-ride-me-on" else "player-ride-me-off")
    }

    private fun shake(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex("내리기", PlayerFeature.SHAKE, PlayerFeature.SHAKE_OTHERS, listOf("-s"), listOf("-s")) { ctx, targets, tail, _ ->
            val sender = ctx.source.sender
            val target = targets.first()
            val riders = target.passengers.filterNot { feature.seats.isSeat(it) }
            if (riders.isEmpty()) return@flex ess.messages.send(sender, "player-shake-nothing")
            val names = riders.joinToString(", ") { displayName(it) }
            for (r in riders) target.removePassenger(r)
            ess.messages.send(sender, "player-shake-done", Ph.of().target(target.name).value(names))
            if (!tail.silent) for (r in riders) if (r is Player) ess.messages.send(r, "player-shake-thrown", Ph.of().target(target.name))
        }

    // --- 모자 ----------------------------------------------------------------------------------

    private fun hat(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("모자").requires(cmd.perm(PlayerFeature.HAT))
            .executes { ctx -> cmd.self(ctx)?.let { putHat(it, it, silent = false) }; 1 }
            .then(
                ess.args.player("대상").requires(cmd.perm(PlayerFeature.HAT_OTHERS)).executes { ctx -> hatOther(ctx, null); 1 }
                    .then(Commands.argument("값", StringArgumentType.greedyString()).suggests(cmd.tailSuggest(listOf("-s"))).executes { ctx -> hatOther(ctx, cmd.str(ctx, "값")); 1 }),
            ).build()

    /** 남의 머리에 — CMI 처럼 **그 사람이 손에 든 것**을 씌운다(관리자가 대신 눌러 주기). */
    private fun hatOther(ctx: Ctx, rest: String?) {
        val sender = ctx.source.sender
        val input = cmd.str(ctx, "대상")
        val target = ess.args.online(sender, input) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(input))
        putHat(sender, target, Tail.parse(rest).silent)
    }

    /** [target] 의 주 손 아이템 하나를 머리에. 쓰던 것은 가방으로(가방이 차면 안 바꾼다). 성공하면 true. */
    fun putHat(sender: org.bukkit.command.CommandSender, target: Player, silent: Boolean): Boolean {
        val hand = target.inventory.itemInMainHand
        if (hand.type.isAir) return false.also { ess.messages.send(sender, "player-hat-empty-hand") }
        when (feature.hatVerdict(hand)) {
            HatRule.Verdict.OK -> Unit
            else -> return false.also { ess.messages.send(sender, "player-hat-cant") }
        }
        val old = target.inventory.helmet
        if (old != null && !old.type.isAir && !HatRule.canReplace(isBound(old), target.gameMode == org.bukkit.GameMode.CREATIVE)) {
            return false.also { ess.messages.send(sender, "player-hat-incorrect") }
        }
        val single = hand.clone().also { it.amount = 1 }
        if (old != null && !old.type.isAir) {
            // 쓰던 것을 돌려줄 자리 — 손의 것이 하나뿐이면 그 칸이 빈다.
            val freeAfter = target.inventory.storageContents.count { it == null || it.type.isAir } + (if (hand.amount == 1) 1 else 0)
            if (freeAfter <= 0) return false.also { ess.messages.send(sender, "player-hat-full", Ph.of().target(target.name)) }
        }
        if (hand.amount <= 1) target.inventory.setItemInMainHand(null) else hand.amount = hand.amount - 1
        target.inventory.setHelmet(single)
        if (old != null && !old.type.isAir) {
            val left = target.inventory.addItem(old)
            for (stack in left.values) target.world.dropItemNaturally(target.location, stack) // 위에서 자리를 셌다 — 남을 일은 없다
        }
        ess.messages.send(target, "player-hat-done")
        if (sender != target && !silent) ess.messages.send(target, "player-hat-changed-by", Ph.of().player(cmd.nameFor(target, sender)))
        if (sender != target) ess.messages.send(sender, "player-hat-other-done", Ph.of().target(target.name))
        return true
    }

    // --- 앉기 ----------------------------------------------------------------------------------

    private fun sit(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex("앉기", PlayerFeature.SIT, PlayerFeature.SIT_OTHERS, listOf("-persistent", "-s", "좌표"), listOf("-persistent", "-s", "좌표", "~", "~", "~"),
            looksLikeValue = { Tail.isFlag(it) || it == "좌표" || it.lowercase() in setOf("on", "off", "켜기", "끄기") }) { ctx, targets, tail, other ->
            val sender = ctx.source.sender
            val target = targets.first()
            val keep = tail.has("-persistent")
            if (keep && !sender.hasPermission(PlayerFeature.SIT_PERSISTENT)) return@flex ess.messages.send(sender, "no-permission")
            val toggle = Toggle.parse(tail.word(0))
            if (feature.seats.isSeated(target) && toggle != true && tail.word(0) != "좌표") {
                feature.seats.stand(target)
                ess.messages.send(target, "player-sit-off")
                return@flex
            }
            if (toggle == false) return@flex ess.messages.send(target, "player-sit-off")
            if (tail.word(0) == "좌표") {
                if (!sender.hasPermission(PlayerFeature.SIT_LOCATION)) return@flex ess.messages.send(sender, "no-permission")
                val at = parseLocation(sender, target, tail.words.drop(1)) ?: return@flex ess.messages.send(sender, "player-sit-bad-location")
                ess.teleporter.teleport(target, at, TeleportKind.OTHER, safe = false).thenAccept { ok ->
                    if (ok != true) return@thenAccept
                    if (feature.seats.sit(target, at, keep = keep)) {
                        ess.messages.send(target, "player-sit-on")
                        (sender as? Player)?.let { ess.gate?.commit(it, cmd.label(ctx)) }
                    }
                }
                return@flex
            }
            if (!other && !target.isOnGround && !target.isInWater) return@flex ess.messages.send(sender, "player-sit-not-ground")
            val at = target.location.clone()
            val below = at.block.getRelative(org.bukkit.block.BlockFace.DOWN)
            val chair = feature.seats.chairSpot(at.block)?.let { at.block } ?: feature.seats.chairSpot(below)?.let { below }
            if (chair != null && feature.seats.occupant(chair)?.let { it != target.uniqueId } == true) return@flex ess.messages.send(sender, "player-sit-occupied")
            val spot = chair?.let { feature.seats.chairSpot(it) } ?: at
            if (feature.seats.sit(target, spot, chair, keep)) {
                ess.messages.send(target, "player-sit-on")
                if (other && !tail.silent) ess.messages.send(target, "player-sit-by", Ph.of().player(cmd.nameFor(target, sender)))
            }
        }

    /** `x y z [월드]` — `~` 는 대상의 지금 자리. */
    private fun parseLocation(sender: org.bukkit.command.CommandSender, target: Player, words: List<String>): Location? {
        if (words.size < 3) return null
        val base = target.location
        fun coord(text: String, origin: Double): Double? =
            if (text.startsWith("~")) origin + (text.substring(1).ifEmpty { "0" }.toDoubleOrNull() ?: return null) else text.toDoubleOrNull()
        val x = coord(words[0], base.x) ?: return null
        val y = coord(words[1], base.y) ?: return null
        val z = coord(words[2], base.z) ?: return null
        val world = words.getOrNull(3)?.let { Bukkit.getWorld(it) ?: return null } ?: (sender as? Player)?.world ?: base.world
        return Location(world, x, y, z, base.yaw, 0f)
    }

    companion object {
        /** 몹 머리(CMI Allow.MobHeads). */
        val MOB_HEADS = setOf(
            Material.SKELETON_SKULL, Material.WITHER_SKELETON_SKULL, Material.ZOMBIE_HEAD, Material.CREEPER_HEAD,
            Material.DRAGON_HEAD, Material.PIGLIN_HEAD, Material.PLAYER_HEAD,
        )

        fun isHeadwear(stack: ItemStack): Boolean = stack.type.equipmentSlot == EquipmentSlot.HEAD

        /** 귀속 저주(바닐라는 창작 모드가 아니면 벗기지 못하게 한다). */
        fun isBound(stack: ItemStack): Boolean = stack.containsEnchantment(org.bukkit.enchantments.Enchantment.BINDING_CURSE)
    }
}

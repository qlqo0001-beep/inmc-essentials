package com.inmc.essentials.player

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.util.Durations
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * 능력 명령 — 무적 · 시간제무적 · 비행 · 시간제비행 · 속도 · 걷기속도 · 비행속도 · 충돌 · 토템.
 * 무적·비행·충돌은 DB 에 남아 다시 접속해도 그대로다. 시간제는 남은 초가 남는다(접속해 있는 동안만 준다). `0` = 다시 접속할 때까지.
 */
internal class AbilityCommands(private val ess: Ess, private val feature: PlayerFeature, private val cmd: Cmds) {

    private val states get() = feature.states

    fun register() {
        ess.commands.add(god(), "무적 켜고 끄기", listOf("god"))
        ess.commands.add(timed("시간제무적", god = true), "정한 시간 동안 무적", listOf("tgod"))
        ess.commands.add(fly(), "비행 켜고 끄기", listOf("fly"))
        ess.commands.add(timed("시간제비행", god = false), "정한 시간 동안 비행", listOf("tfly"))
        ess.commands.add(speed("속도", null), "속도(나는 중이면 비행, 아니면 걷기)", listOf("speed"))
        ess.commands.add(speed("걷기속도", false), "걷기 속도(0~5)", listOf("walkspeed"))
        ess.commands.add(speed("비행속도", true), "비행 속도(0~10)", listOf("flyspeed"))
        ess.commands.add(collision(), "플레이어끼리 밀기 켜고 끄기", listOf("playercollision"))
        ess.commands.add(totem(), "가방 속 토템 자동 사용·토템 보스바 켜고 끄기", listOf("totemtoggle"))
    }

    private fun bool(value: Boolean): String = ess.messages.raw(if (value) "player-on" else "player-off")

    // --- 무적 ----------------------------------------------------------------------------------

    private fun god(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex("무적", PlayerFeature.GOD, PlayerFeature.GOD_OTHERS, Toggle.WORDS + "-s", Toggle.WORDS + "-s",
            looksLikeValue = { Tail.isFlag(it) || Toggle.parse(it) != null }) { ctx, targets, tail, _ ->
            val sender = ctx.source.sender
            val target = targets.first()
            // 시간제 무적 중이면 "무적" 이다 — 그냥 /무적 은 끈다(시간제까지).
            val wanted = Toggle.parse(tail.word(0)) ?: !states.isGod(target.uniqueId)
            feature.setGod(target, wanted)
            ess.messages.send(sender, "player-god-done", Ph.of().target(target.name).value(bool(wanted)))
            cmd.notify(sender, target, tail.silent, "player-god-changed-by", Ph.of().value(bool(wanted)))
        }

    // --- 비행 ----------------------------------------------------------------------------------

    private fun fly(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex("비행", PlayerFeature.FLY, PlayerFeature.FLY_OTHERS, Toggle.WORDS + "-s", Toggle.WORDS + "-s",
            looksLikeValue = { Tail.isFlag(it) || Toggle.parse(it) != null }) { ctx, targets, tail, _ ->
            val sender = ctx.source.sender
            val target = targets.first()
            val wanted = Toggle.parse(tail.word(0)) ?: !target.allowFlight
            feature.setFlight(target, wanted)
            states.update(target.uniqueId) { it.copy(fly = wanted, tflyLeft = if (wanted) it.tflyLeft else null) }
            ess.messages.send(sender, "player-fly-done", Ph.of().target(target.name).value(bool(wanted)))
            cmd.notify(sender, target, tail.silent, "player-fly-changed-by", Ph.of().value(bool(wanted)))
        }

    // --- 시간제 무적 · 비행 ----------------------------------------------------------------------

    private fun timed(name: String, god: Boolean): LiteralCommandNode<CommandSourceStack> {
        val base = if (god) PlayerFeature.TGOD else PlayerFeature.TFLY
        val others = if (god) PlayerFeature.TGOD_OTHERS else PlayerFeature.TFLY_OTHERS
        val kind = if (god) "god" else "fly"
        // 첫 자리에 `끄기`·`0`·`+10m` 을 쓰면 나에게 쓴 것이다 — 기본 판정(첫 자리 제안 · 시간)은 끝내기 낱말을 몰라 `끄기` 를 사람 이름으로 찾았다(2026-10-10 리뷰).
        val values = listOf("30", "5m", "1h", "+10m", "-5m", "0", "끄기")
        return cmd.flex(name, base, others, values + "-s", values + "-s", looksLikeValue = TimedMath::looksLikeValue) { ctx, targets, tail, _ ->
            val sender = ctx.source.sender
            val target = targets.first()
            val record = states.get(target.uniqueId)
            val left = if (god) record.tgodLeft else record.tflyLeft
            val text = tail.word(0)
            if (text == null) {
                // 남은 시간 보기
                when {
                    left == null -> ess.messages.send(sender, "player-t$kind-not-set", Ph.of().target(target.name))
                    left == PlayerRecord.UNTIL_RELOG -> ess.messages.send(sender, "player-t$kind-until-relog", Ph.of().target(target.name))
                    else -> ess.messages.send(sender, "player-t$kind-left", Ph.of().target(target.name).time(Durations.formatShort(left)))
                }
                return@flex
            }
            // `끄기`/`off` = 지금 끝내기(`0` 은 CMI 처럼 "다시 접속할 때까지").
            val end = text.lowercase() in TimedMath.END_WORDS
            val time = if (end) TimeArg.Parsed(0L, 0) else TimeArg.parse(text) ?: return@flex ess.messages.send(sender, "invalid-number", Ph.of().value(text))
            val next: Long? = when (val n = TimedMath.next(left, time, end)) {
                is TimedMath.Next.To -> n.left
                TimedMath.Next.NotSet -> return@flex ess.messages.send(sender, "player-t$kind-not-set", Ph.of().target(target.name))
                TimedMath.Next.UntilRelog -> return@flex ess.messages.send(sender, "player-t$kind-relog-no-math", Ph.of().target(target.name))
            }
            feature.setTimed(target, god, next)
            val timeText = when (next) {
                null -> "0"
                PlayerRecord.UNTIL_RELOG -> ess.messages.raw("player-until-relog")
                else -> Durations.formatShort(next)
            }
            val ph = Ph.of().target(target.name).time(timeText).raw("{더한시간}", Durations.formatShort(time.seconds))
            val (senderKey, targetKey) = when {
                next == null -> "player-t$kind-ended-by-command" to "player-t$kind-ended"
                next == PlayerRecord.UNTIL_RELOG -> "player-t$kind-set-relog" to "player-t$kind-got-relog"
                time.sign > 0 -> "player-t$kind-added" to "player-t$kind-got-added"
                time.sign < 0 -> "player-t$kind-taken" to "player-t$kind-got-taken"
                else -> "player-t$kind-set" to "player-t$kind-got"
            }
            ess.messages.sendRaw(sender, ess.messages.raw(senderKey), ph)
            cmd.notify(sender, target, tail.silent, targetKey, Ph.of().time(timeText).raw("{더한시간}", Durations.formatShort(time.seconds)))
        }
    }

    // --- 속도 ----------------------------------------------------------------------------------

    /** 권한 `inmcessentials.player.speed.max.<n>` 중 가장 큰 것. 없으면 끝까지(걷기 5 · 비행 10). */
    private fun cap(sender: CommandSender, fly: Boolean): Double {
        val absolute = if (fly) SpeedMath.FLY_MAX else SpeedMath.WALK_MAX
        if (sender !is Player) return absolute
        val granted = (1..10).filter { sender.hasPermission(PlayerFeature.SPEED_MAX_PREFIX + it) && sender.isPermissionSet(PlayerFeature.SPEED_MAX_PREFIX + it) }.map { it.toDouble() }
        return SpeedMath.cap(granted, absolute)
    }

    private fun speed(name: String, fly: Boolean?): LiteralCommandNode<CommandSourceStack> =
        cmd.flex(name, PlayerFeature.SPEED, PlayerFeature.SPEED_OTHERS, listOf("1", "2", "-s"), listOf("1", "2", "3", "-s"),
            looksLikeValue = { Tail.isFlag(it) || it.toDoubleOrNull() != null }) { ctx, targets, tail, _ ->
            val sender = ctx.source.sender
            val target = targets.first()
            val useFly = fly ?: target.isFlying
            val text = tail.word(0)
            if (text == null) {
                val current = if (useFly) target.flySpeed else target.walkSpeed
                return@flex ess.messages.send(sender, if (useFly) "player-flyspeed-now" else "player-walkspeed-now",
                    Ph.of().target(target.name).value(MaxHpMath.format(SpeedMath.fromBukkit(current, useFly))))
            }
            val amount = text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return@flex ess.messages.send(sender, "invalid-number", Ph.of().value(text))
            val max = cap(sender, useFly)
            if (amount > max) return@flex ess.messages.send(sender, "player-speed-max", Ph.of().value(MaxHpMath.format(max)))
            if (useFly) target.flySpeed = SpeedMath.toBukkit(amount, true) else target.walkSpeed = SpeedMath.toBukkit(amount, false)
            val ph = Ph.of().target(target.name).value(MaxHpMath.format(amount))
            ess.messages.send(sender, if (useFly) "player-flyspeed-done" else "player-walkspeed-done", ph)
            cmd.notify(sender, target, tail.silent, if (useFly) "player-flyspeed-changed-by" else "player-walkspeed-changed-by", Ph.of().value(MaxHpMath.format(amount)))
        }

    // --- 충돌 ----------------------------------------------------------------------------------

    private fun collision(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex("충돌", PlayerFeature.COLLISION, PlayerFeature.COLLISION_OTHERS, Toggle.WORDS + "-s", Toggle.WORDS + "-s",
            looksLikeValue = { Tail.isFlag(it) || Toggle.parse(it) != null }) { ctx, targets, tail, _ ->
            val sender = ctx.source.sender
            val target = targets.first()
            val wanted = Toggle.parse(tail.word(0)) ?: !states.get(target.uniqueId).collision
            target.isCollidable = wanted
            states.update(target.uniqueId) { it.copy(collision = wanted) }
            ess.messages.send(sender, "player-collision-done", Ph.of().target(target.name).value(bool(wanted)))
            cmd.notify(sender, target, tail.silent, "player-collision-changed-by", Ph.of().value(bool(wanted)))
        }

    // --- 토템 ----------------------------------------------------------------------------------

    private fun totem(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("토템").requires(cmd.perm(PlayerFeature.TOTEM_TOGGLE))
            .executes { ctx -> cmd.self(ctx)?.let { toggleSetting(it, PlayerFeature.SETTING_TOTEM_INVENTORY, "player-totem-inventory") }; 1 }
            .then(Commands.literal("보스바").executes { ctx -> cmd.self(ctx)?.let { toggleSetting(it, PlayerFeature.SETTING_TOTEM_BOSSBAR, "player-totem-bossbar") }; 1 })
            .build()

    private fun toggleSetting(player: Player, key: String, messageKey: String) {
        val now = !PlayerSettings.enabled(player, key, true)
        PlayerSettings.set(player, key, now)
        ess.messages.sendRaw(player, ess.messages.raw(messageKey), Ph.of().value(bool(now)))
    }
}

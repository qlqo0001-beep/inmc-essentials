package com.inmc.essentials.player

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.command.CommandSender
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.concurrent.ThreadLocalRandom

/**
 * 몸 상태 명령 — 게임모드 · 경험치 · 최대체력 · 회복 · 배고픔 · 효과 · 처치 · 자살 · 핑 · 근처.
 * 남에게 쓴 것은 그 사람에게 누가 했는지 알린다(`-s` 면 조용히 · 투명인 관리자는 "관리자").
 */
internal class StatusCommands(private val ess: Ess, private val feature: PlayerFeature, private val cmd: Cmds) {

    private val config get() = feature.config

    fun register() {
        ess.commands.add(gamemode(), "게임모드 바꾸기", listOf("gm"))
        ess.commands.add(exp(), "경험치 보기·더하기·설정·빼기·초기화", listOf("exp"))
        ess.commands.add(maxHp(), "최대 체력(기본값) 보기·바꾸기", listOf("maxhp"))
        ess.commands.add(heal(), "체력 회복(배고픔·나쁜 효과·불 포함)", listOf("heal"))
        ess.commands.add(feed(), "배고픔 채우기·정하기", listOf("feed", "hunger"))
        ess.commands.add(effect(), "포션 효과 주기·지우기", listOf("peffect"))
        ess.commands.add(kill(), "플레이어 처치(피해 원인·번개)")
        ess.commands.add(suicide(), "스스로 죽기", listOf("suicide"))
        ess.commands.add(ping(), "핑 보기", listOf("ping"))
        ess.commands.add(near(), "근처 플레이어 보기", listOf("near"))
    }

    // --- 게임모드 ---------------------------------------------------------------------------------

    private val modes: Map<String, GameMode> = buildMap {
        for (w in listOf("survival", "s", "0", "서바이벌", "생존")) put(w, GameMode.SURVIVAL)
        for (w in listOf("creative", "c", "1", "크리에이티브", "창작")) put(w, GameMode.CREATIVE)
        for (w in listOf("adventure", "a", "2", "어드벤처", "모험")) put(w, GameMode.ADVENTURE)
        for (w in listOf("spectator", "sp", "3", "관전", "관전자")) put(w, GameMode.SPECTATOR)
    }

    private fun modeName(mode: GameMode): String = ess.messages.raw(
        when (mode) {
            GameMode.SURVIVAL -> "player-mode-survival"
            GameMode.CREATIVE -> "player-mode-creative"
            GameMode.ADVENTURE -> "player-mode-adventure"
            GameMode.SPECTATOR -> "player-mode-spectator"
        },
    )

    private fun gamemode(): LiteralCommandNode<CommandSourceStack> {
        val suggest = SuggestionProvider<CommandSourceStack> { _, b ->
            for (w in listOf("서바이벌", "크리에이티브", "어드벤처", "관전", "survival", "creative", "adventure", "spectator")) if (w.startsWith(b.remainingLowerCase)) b.suggest(w)
            b.buildFuture()
        }
        fun run(ctx: Ctx, modeText: String, who: String?, rest: String?): Int {
            val sender = ctx.source.sender
            // CMI 순서 `/gm <플레이어> <모드>` 도 받는다 — 첫 낱말이 모드가 아니고 둘째가 모드면 바꿔 읽는다.
            if (modes[modeText.lowercase()] == null && who != null && modes[who.lowercase()] != null) return run(ctx, who, modeText, rest)
            val mode = modes[modeText.lowercase()] ?: return 0.also { ess.messages.send(sender, "player-gm-unknown", Ph.of().value(modeText)) }
            val tail = Tail.parse(rest)
            val target = if (who == null || Tail.isFlag(who)) cmd.self(ctx) ?: return 0 else {
                ess.args.online(sender, who) ?: return 0.also { ess.messages.send(sender, "player-not-found", Ph.of().value(who)) }
            }
            val silent = tail.silent || who == "-s"
            if (target != sender && !sender.hasPermission(PlayerFeature.GM_OTHERS)) return 0.also { ess.messages.send(sender, "no-permission") }
            target.gameMode = mode
            if (target == sender) ess.messages.send(sender, "player-gm-self", Ph.of().value(modeName(mode)))
            else ess.messages.send(sender, "player-gm-done", Ph.of().target(target.name).value(modeName(mode)))
            cmd.notify(sender, target, silent, "player-gm-changed-by", Ph.of().value(modeName(mode)))
            return 1
        }
        val mode = Commands.argument("모드", Args.TOKEN).suggests(suggest)
            .executes { ctx -> run(ctx, cmd.str(ctx, "모드"), null, null) }
            .then(
                ess.args.player("대상").executes { ctx -> run(ctx, cmd.str(ctx, "모드"), cmd.str(ctx, "대상"), null) }
                    .then(Commands.argument("값", StringArgumentType.greedyString()).suggests(cmd.tailSuggest(listOf("-s")))
                        .executes { ctx -> run(ctx, cmd.str(ctx, "모드"), cmd.str(ctx, "대상"), cmd.str(ctx, "값")) }),
            )
        return Commands.literal("게임모드").requires(cmd.perm(PlayerFeature.GM)).then(mode).build()
    }

    // --- 경험치 ----------------------------------------------------------------------------------

    private val expOps: Map<String, ExpMath.Op> = mapOf(
        "더하기" to ExpMath.Op.ADD, "add" to ExpMath.Op.ADD, "주기" to ExpMath.Op.ADD,
        "설정" to ExpMath.Op.SET, "set" to ExpMath.Op.SET,
        "빼기" to ExpMath.Op.TAKE, "take" to ExpMath.Op.TAKE,
        "초기화" to ExpMath.Op.CLEAR, "clear" to ExpMath.Op.CLEAR,
    )

    private fun exp(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex(
            "경험치", PlayerFeature.EXP_CHECK, PlayerFeature.EXP_OTHERS,
            firstOptions = listOf("더하기", "설정", "빼기", "초기화", "add", "set", "take", "clear"),
            tailOptions = listOf("더하기", "설정", "빼기", "초기화", "10", "10L", "10%", "-s"),
        ) { ctx, targets, tail, _ ->
            val sender = ctx.source.sender
            val target = targets.first()
            val opWord = tail.word(0)
            if (opWord == null) {
                showExp(sender, target)
                return@flex
            }
            val op = expOps[opWord.lowercase()] ?: return@flex ess.messages.send(sender, "player-exp-usage")
            if (!sender.hasPermission(PlayerFeature.EXP)) return@flex ess.messages.send(sender, "no-permission")
            val amountText = tail.word(1)
            val total = ExpMath.total(target.level, target.exp)
            val amount = if (op == ExpMath.Op.CLEAR) null else {
                if (amountText == null) return@flex ess.messages.send(sender, "player-exp-usage")
                ExpMath.parseAmount(amountText, total, target.level) { a, b -> ThreadLocalRandom.current().nextLong(a, b + 1) }
                    ?: return@flex ess.messages.send(sender, "player-exp-bad-amount", Ph.of().value(amountText))
            }
            val (level, progress) = ExpMath.apply(op, amount, target.level, target.exp)
            target.level = level
            target.exp = progress
            target.totalExperience = ExpMath.total(level, progress).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val ph = Ph.of().target(target.name).raw("{레벨}", level.toString()).raw("{총}", ExpMath.total(level, progress).toString())
            ess.messages.send(sender, "player-exp-done", ph)
            cmd.notify(sender, target, tail.silent, "player-exp-changed-by", Ph.of().raw("{레벨}", level.toString()).raw("{총}", ExpMath.total(level, progress).toString()))
        }

    private fun showExp(sender: CommandSender, target: Player) {
        val need = ExpMath.needed(target.level)
        val inLevel = (target.exp * need).toLong()
        val ph = Ph.of().target(target.name).raw("{레벨}", target.level.toString()).raw("{경험치}", inLevel.toString())
            .raw("{필요}", (need - inLevel).toString()).raw("{총}", ExpMath.total(target.level, target.exp).toString())
        ess.messages.send(sender, "player-exp-check", ph)
    }

    // --- 최대 체력 --------------------------------------------------------------------------------

    private fun maxHp(): LiteralCommandNode<CommandSourceStack> {
        val root = Commands.literal("최대체력").requires(cmd.perm(PlayerFeature.MAXHP_CHECK))
            .executes { ctx -> cmd.self(ctx)?.let { showMaxHp(ctx.source.sender, it) }; 1 }
            .then(
                ess.args.player("대상").requires(cmd.perm(PlayerFeature.MAXHP)).executes { ctx ->
                    val input = cmd.str(ctx, "대상")
                    ess.args.online(ctx.source.sender, input)?.let { showMaxHp(ctx.source.sender, it) }
                        ?: ess.messages.send(ctx.source.sender, "player-not-found", Ph.of().value(input))
                    1
                },
            )
        val ops = linkedMapOf(
            "설정" to MaxHpMath.Op.SET, "더하기" to MaxHpMath.Op.ADD, "빼기" to MaxHpMath.Op.TAKE, "초기화" to MaxHpMath.Op.CLEAR,
            "set" to MaxHpMath.Op.SET, "add" to MaxHpMath.Op.ADD, "take" to MaxHpMath.Op.TAKE, "clear" to MaxHpMath.Op.CLEAR, "reset" to MaxHpMath.Op.CLEAR,
        )
        for ((word, op) in ops) {
            root.then(
                Commands.literal(word).requires(cmd.perm(PlayerFeature.MAXHP)).then(
                    ess.args.player("대상").executes { ctx -> changeMaxHp(ctx, op, cmd.str(ctx, "대상"), null); 1 }
                        .then(Commands.argument("값", StringArgumentType.greedyString()).suggests(cmd.tailSuggest(listOf("20", "40", "-s")))
                            .executes { ctx -> changeMaxHp(ctx, op, cmd.str(ctx, "대상"), cmd.str(ctx, "값")); 1 }),
                ),
            )
        }
        return root.build()
    }

    /** 기본값과 수정자를 묶음별로 — "기본 20 + 장비 8 + 칭호 4 = 32". 곱하는 수정자는 따로 적고, 최종값은 늘 getValue(). */
    private fun showMaxHp(sender: CommandSender, target: Player) {
        val attr = target.getAttribute(Attribute.MAX_HEALTH) ?: return
        val sums = LinkedHashMap<MaxHpMath.Group, Double>()
        val scalars = ArrayList<Pair<MaxHpMath.Group, AttributeModifier>>()
        for (m in attr.modifiers) {
            val group = MaxHpMath.group(m.key.namespace, m.key.key)
            if (m.operation == AttributeModifier.Operation.ADD_NUMBER) sums[group] = (sums[group] ?: 0.0) + m.amount else scalars += group to m
        }
        val parts = StringBuilder(ess.messages.raw("player-maxhp-part-base").replace("{값}", MaxHpMath.format(attr.baseValue)))
        for ((group, sum) in sums) {
            if (sum == 0.0) continue
            parts.append(' ').append(
                ess.messages.raw("player-maxhp-part").replace("{이름}", groupName(group)).replace("{값}", (if (sum > 0) "+" else "") + MaxHpMath.format(sum)),
            )
        }
        for ((group, m) in scalars) {
            parts.append(' ').append(
                ess.messages.raw("player-maxhp-part-scalar").replace("{이름}", groupName(group))
                    .replace("{값}", (if (m.amount >= 0) "+" else "") + MaxHpMath.format(m.amount * 100) + "%"),
            )
        }
        ess.messages.send(
            sender, "player-maxhp-view",
            Ph.of().target(target.name).value(parts.toString()).amount(MaxHpMath.format(attr.value)).raw("{체력}", MaxHpMath.format(target.health)),
        )
    }

    private fun groupName(group: MaxHpMath.Group): String = ess.messages.raw(
        when (group) {
            MaxHpMath.Group.GEAR -> "player-maxhp-group-gear"
            MaxHpMath.Group.SET -> "player-maxhp-group-set"
            MaxHpMath.Group.TITLE -> "player-maxhp-group-title"
            MaxHpMath.Group.DUNGEON -> "player-maxhp-group-dungeon"
            MaxHpMath.Group.OTHER -> "player-maxhp-group-other"
        },
    )

    /** 상한 = 설정(CMI Optimizations.MaxHp)과 spigot.yml `settings.attribute.maxHealth.max` 중 작은 것. */
    fun maxHpCap(): Double {
        val spigot = runCatching { Bukkit.spigot().config.getDouble("settings.attribute.maxHealth.max", 1024.0) }.getOrDefault(1024.0)
        return minOf(config.maxHpCap, spigot)
    }

    private fun changeMaxHp(ctx: Ctx, op: MaxHpMath.Op, who: String, rest: String?) {
        val sender = ctx.source.sender
        val target = ess.args.online(sender, who) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(who))
        val tail = Tail.parse(rest)
        val amount = if (op == MaxHpMath.Op.CLEAR) 0.0 else {
            val text = tail.word(0) ?: return ess.messages.send(sender, "player-maxhp-usage")
            text.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(text))
        }
        val result = feature.applyMaxHp(target, op, amount, maxHpCap())
        val ph = Ph.of().target(target.name).value(MaxHpMath.format(result.first)).amount(MaxHpMath.format(result.second))
        ess.messages.send(sender, "player-maxhp-done", ph)
        if (result.third) ess.messages.send(sender, "player-maxhp-capped", Ph.of().value(MaxHpMath.format(maxHpCap())))
        cmd.notify(sender, target, tail.silent, "player-maxhp-changed-by", Ph.of().value(MaxHpMath.format(result.first)).amount(MaxHpMath.format(result.second)))
    }

    // --- 회복 · 배고픔 ----------------------------------------------------------------------------

    private fun heal(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex(
            "회복", PlayerFeature.HEAL, PlayerFeature.HEAL_OTHERS,
            firstOptions = listOf("-nofeed", "-ignoreeffects", "-ignoreffects", "-dontextinguish", "-s"),
            tailOptions = listOf("50%", "10", "-nofeed", "-ignoreeffects", "-ignoreffects", "-dontextinguish", "-s"),
            allowAll = true,
            looksLikeValue = { Tail.isFlag(it) || it.endsWith("%") || it.toDoubleOrNull() != null },
        ) { ctx, targets, tail, other ->
            val sender = ctx.source.sender
            val amountText = tail.word(0)
            val percent = amountText?.endsWith("%") == true
            val amount = amountText?.removeSuffix("%")?.toDoubleOrNull()
            if (amountText != null && (amount == null || amount < 0 || !amount.isFinite())) return@flex ess.messages.send(sender, "invalid-number", Ph.of().value(amountText))
            var count = 0
            for (target in targets) {
                if (target.isDead || target.health <= 0) continue
                feature.heal(target, amount, percent, feed = !tail.has("-nofeed"), cleanse = !(tail.has("-ignoreeffects") || tail.has("-ignoreffects")), extinguish = !tail.has("-dontextinguish"))
                count++
                cmd.notify(sender, target, tail.silent, "player-healed-by", Ph.of())
            }
            when {
                targets.size > 1 || (other && targets.isEmpty()) -> ess.messages.send(sender, "player-heal-multi", Ph.of().count(count))
                targets.firstOrNull() == sender -> ess.messages.send(sender, "player-heal-self")
                else -> targets.firstOrNull()?.let { ess.messages.send(sender, "player-heal-done", Ph.of().target(it.name)) }
            }
        }

    private fun feed(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex(
            "배고픔", PlayerFeature.FEED, PlayerFeature.FEED_OTHERS,
            firstOptions = listOf("-s"), tailOptions = listOf("20", "10", "-s"), allowAll = true,
            looksLikeValue = { Tail.isFlag(it) || it.toIntOrNull() != null },
        ) { ctx, targets, tail, other ->
            val sender = ctx.source.sender
            val levelText = tail.word(0)
            val level = levelText?.let { it.toIntOrNull() ?: return@flex ess.messages.send(sender, "invalid-number", Ph.of().value(it)) }
                ?.let { if (config.feedAllowOverMax) it.coerceIn(0, 1000) else it.coerceIn(0, 20) }
            for (target in targets) {
                if (level == null) {
                    target.foodLevel = maxOf(target.foodLevel, 20)
                    target.saturation = 20f
                    target.exhaustion = 0f
                    cmd.notify(sender, target, tail.silent, "player-fed-by", Ph.of())
                } else {
                    target.foodLevel = level
                    target.saturation = minOf(target.saturation, level.toFloat())
                    cmd.notify(sender, target, tail.silent, "player-hunger-set-by", Ph.of().value(level.toString()))
                }
            }
            when {
                level != null && targets.size == 1 -> ess.messages.send(sender, "player-hunger-set", Ph.of().target(targets[0].name).value(level.toString()))
                targets.size > 1 || (other && targets.isEmpty()) -> ess.messages.send(sender, "player-feed-multi", Ph.of().count(targets.size))
                targets.firstOrNull() == sender -> ess.messages.send(sender, "player-feed-self")
                else -> targets.firstOrNull()?.let { ess.messages.send(sender, "player-feed-done", Ph.of().target(it.name)) }
            }
        }

    // --- 효과 ----------------------------------------------------------------------------------

    private fun effectRegistry() = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT)

    private fun effect(): LiteralCommandNode<CommandSourceStack> {
        val effects = SuggestionProvider<CommandSourceStack> { _, b ->
            val typed = b.remainingLowerCase
            for (w in listOf("지우기", "clear")) if (w.startsWith(typed)) b.suggest(w)
            for (type in effectRegistry()) type.key.key.let { if (it.startsWith(typed)) b.suggest(it) }
            b.buildFuture()
        }
        val tail = Commands.argument("값", StringArgumentType.greedyString())
            .suggests(cmd.tailSuggest(listOf("30", "60", "300", "무한", "+10", "-10", "1", "2", "-s", "-visual")))
            .executes { ctx -> runEffect(ctx, cmd.str(ctx, "대상"), cmd.str(ctx, "효과"), cmd.str(ctx, "값")); 1 }
        val type = Commands.argument("효과", Args.TOKEN).suggests(effects)
            .executes { ctx -> runEffect(ctx, cmd.str(ctx, "대상"), cmd.str(ctx, "효과"), null); 1 }
            .then(tail)
        return Commands.literal("효과").requires(cmd.perm(PlayerFeature.EFFECT)).then(ess.args.players("대상").then(type)).build()
    }

    private fun runEffect(ctx: Ctx, who: String, effectText: String, rest: String?) {
        val sender = ctx.source.sender
        val targets = ess.args.targets(sender, who) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(who))
        if ((Args.isAll(who) || targets.any { it != sender }) && !sender.hasPermission(PlayerFeature.EFFECT_OTHERS)) return ess.messages.send(sender, "no-permission")
        val tail = Tail.parse(rest)
        if (effectText.lowercase() in setOf("지우기", "clear")) {
            for (t in targets) {
                for (e in t.activePotionEffects) t.removePotionEffect(e.type)
                cmd.notify(sender, t, tail.silent, "player-effect-cleared-by", Ph.of())
            }
            return ess.messages.send(sender, "player-effect-cleared", Ph.of().target(if (targets.size == 1) targets[0].name else targets.size.toString()))
        }
        val type = effectRegistry().get(NamespacedKey.minecraft(EffectNames.key(effectText)))
            ?: return ess.messages.send(sender, "player-effect-unknown", Ph.of().value(effectText))
        val durationText = tail.word(0)
        val infinite = durationText?.lowercase() in setOf("무한", "infinite", "inf")
        val time = if (infinite || durationText == null) null else TimeArg.parse(durationText) ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(durationText))
        val power = tail.word(1)?.let { it.toIntOrNull() ?: return ess.messages.send(sender, "invalid-number", Ph.of().value(it)) }?.coerceIn(1, 256) ?: 1
        val visual = tail.has("-visual")
        val name = "<lang:${type.translationKey()}>"
        for (t in targets) {
            val existing = t.getPotionEffect(type)
            val ticks: Int = when {
                infinite -> PotionEffect.INFINITE_DURATION
                time == null -> 30 * 20
                time.sign == 0 -> (time.seconds * 20).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                existing == null || existing.isInfinite -> if (time.sign > 0) (time.seconds * 20).toInt() else 0
                else -> (existing.duration + time.sign * time.seconds * 20).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            }
            val amplifier = if (time != null && time.sign != 0 && tail.word(1) == null && existing != null) existing.amplifier else power - 1
            if (ticks == 0) {
                t.removePotionEffect(type)
                ess.messages.send(sender, "player-effect-removed", Ph.of().target(t.name).subject(name))
                continue
            }
            t.addPotionEffect(PotionEffect(type, ticks, amplifier, false, visual, visual))
            val seconds = if (ticks == PotionEffect.INFINITE_DURATION) ess.messages.raw("player-effect-infinite") else (ticks / 20).toString()
            if (targets.size == 1) ess.messages.send(sender, "player-effect-done", Ph.of().target(t.name).subject(name).value(seconds).count(amplifier + 1))
            cmd.notify(sender, t, tail.silent, "player-effect-given-by", Ph.of().subject(name).value(seconds).count(amplifier + 1))
        }
        if (targets.size != 1) ess.messages.send(sender, "player-effect-multi", Ph.of().subject(name).count(targets.size))
    }

    // --- 처치 · 자살 ------------------------------------------------------------------------------

    private fun damageTypes() = RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE)

    private fun kill(): LiteralCommandNode<CommandSourceStack> {
        val causes = SuggestionProvider<CommandSourceStack> { _, b ->
            val text = b.remaining
            val start = text.lastIndexOf(' ') + 1
            val typed = text.substring(start).lowercase()
            val o = b.createOffset(b.start + start)
            for (f in listOf("-forced", "-lightning", "-s")) if (f.startsWith(typed)) o.suggest(f)
            for (t in damageTypes()) t.key.key.let { if (it.startsWith(typed)) o.suggest(it) }
            o.buildFuture()
        }
        return Commands.literal("처치").requires(cmd.perm(PlayerFeature.KILL)).then(
            ess.args.player("대상").executes { ctx -> runKill(ctx, cmd.str(ctx, "대상"), null); 1 }
                .then(Commands.argument("값", StringArgumentType.greedyString()).suggests(causes).executes { ctx -> runKill(ctx, cmd.str(ctx, "대상"), cmd.str(ctx, "값")); 1 }),
        ).build()
    }

    private fun runKill(ctx: Ctx, who: String, rest: String?) {
        val sender = ctx.source.sender
        val target = ess.args.online(sender, who) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(who))
        val tail = Tail.parse(rest)
        val forced = tail.has("-forced")
        if (forced && !sender.hasPermission(PlayerFeature.KILL_FORCED)) return ess.messages.send(sender, "player-kill-no-force")
        val type = tail.word(0)?.let { word ->
            damageTypes().get(NamespacedKey.minecraft(word.lowercase().removePrefix("minecraft:")))
                ?: return ess.messages.send(sender, "player-kill-unknown-cause", Ph.of().value(word))
        } ?: DamageType.GENERIC_KILL
        // 무적은 명령에서 먼저 본다 — 기본 원인(generic_kill = KILL)은 바닐라 `/kill` 처럼 무적 리스너를 지나가므로(그건 바닐라 `/kill` 의 몫).
        if (!forced && feature.isGod(target)) return ess.messages.send(sender, "player-kill-protected", Ph.of().target(target.name))
        if (tail.has("-lightning")) target.world.strikeLightningEffect(target.location)
        if (forced) feature.forceKill(target, DamageSource.builder(type).build()) else target.damage(1.0e9, DamageSource.builder(type).build())
        if (target.isDead || target.health <= 0.0) ess.messages.send(sender, "player-kill-done", Ph.of().target(target.name))
        else ess.messages.send(sender, "player-kill-protected", Ph.of().target(target.name))
    }

    private fun suicide(): LiteralCommandNode<CommandSourceStack> =
        cmd.flex("자살", PlayerFeature.SUICIDE, PlayerFeature.SUICIDE_OTHERS, firstOptions = listOf("-s"), tailOptions = listOf("-s")) { ctx, targets, tail, _ ->
            val sender = ctx.source.sender
            val target = targets.first()
            val hands = listOf(target.inventory.itemInMainHand.type, target.inventory.itemInOffHand.type)
            if (Material.TOTEM_OF_UNDYING in hands) return@flex ess.messages.send(sender, "player-suicide-totem")
            if (target == sender) ess.messages.send(target, "player-suicide-self")
            target.damage(1.0e9, DamageSource.builder(DamageType.GENERIC_KILL).build())
            if (!target.isDead && target.health > 0.0) target.health = 0.0
            if (!tail.silent && config.suicideBroadcast) {
                // 투명인 사람의 죽음은 그를 볼 수 있는 사람에게만(CLAUDE.md 8).
                for (viewer in Bukkit.getOnlinePlayers()) {
                    if (viewer == target || Vanish.canSee(viewer, target)) ess.messages.send(viewer, "player-suicide-broadcast", Ph.of().player(target.name))
                }
            }
        }

    // --- 핑 · 근처 ------------------------------------------------------------------------------

    private fun ping(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("핑").requires(cmd.perm(PlayerFeature.PING))
            .executes { ctx -> cmd.self(ctx)?.let { ess.messages.send(it, "player-ping-self", Ph.of().value(it.ping.toString())) }; 1 }
            .then(
                ess.args.player("대상").requires(cmd.perm(PlayerFeature.PING_OTHERS)).executes { ctx ->
                    val input = cmd.str(ctx, "대상")
                    val target = ess.args.online(ctx.source.sender, input)
                    if (target == null) ess.messages.send(ctx.source.sender, "player-not-found", Ph.of().value(input))
                    else ess.messages.send(ctx.source.sender, "player-ping-other", Ph.of().target(target.name).value(target.ping.toString()))
                    1
                },
            ).build()

    private fun near(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("근처").requires(cmd.perm(PlayerFeature.NEAR))
            .executes { ctx -> cmd.self(ctx)?.let { showNear(it, config.nearDefault.toDouble()) }; 1 }
            .then(
                Commands.literal("가리키기").requires { config.nearPointTo }.then(
                    ess.args.player("대상").executes { ctx ->
                        val me = cmd.self(ctx) ?: return@executes 0
                        val input = cmd.str(ctx, "대상")
                        val target = ess.args.online(me, input)?.takeIf { pointable(me, it) }
                        if (target == null) ess.messages.send(me, "player-not-found", Ph.of().value(input)) else point(me, target)
                        1
                    },
                ),
            )
            .then(
                Commands.argument("거리", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes { ctx ->
                    val me = cmd.self(ctx) ?: return@executes 0
                    val wanted = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "거리")
                    if (wanted > config.nearMax) ess.messages.send(me, "player-near-capped", Ph.of().value(config.nearMax.toString()))
                    showNear(me, wanted.coerceAtMost(config.nearMax).toDouble())
                    1
                },
            ).build()

    private fun showNear(me: Player, radius: Double) {
        val here = me.location
        val c = config
        val found = me.world.players.asSequence()
            .filter { it != me && Vanish.canSee(me, it) && me.canSee(it) }
            .filter { NearMath.listed(it.gameMode == GameMode.SPECTATOR, it.hasPotionEffect(PotionEffectType.INVISIBILITY), c.nearHideInvisible, c.nearObfuscateInvisible, forPoint = false) }
            .map { it to it.location.distance(here) }
            .filter { it.second <= radius }
            .sortedBy { it.second }
            .toList()
        if (found.isEmpty()) return ess.messages.send(me, "player-near-none", Ph.of().value(radius.toInt().toString()))
        ess.messages.send(me, "player-near-header", Ph.of().value(radius.toInt().toString()).count(found.size))
        val exact = !config.nearApproximate || me.hasPermission(PlayerFeature.NEAR_EXACT)
        for ((index, pair) in found.take(config.nearCount).withIndex()) {
            val (other, distance) = pair
            val hidden = config.nearHideInvisible && config.nearObfuscateInvisible && other.hasPotionEffect(PotionEffectType.INVISIBILITY)
            val dir = ess.messages.raw("player-dir-" + NearMath.direction(other.location.x - here.x, other.location.z - here.z))
            val ph = Ph.of().count(index + 1).player(if (hidden) "<obfuscated>xxxxxxxx</obfuscated>" else other.name)
                .raw("{방향}", dir).value(if (exact) distance.toLong().toString() else NearMath.approximate(distance).toString())
            val key = when {
                hidden -> if (exact) "player-near-line-hidden" else "player-near-line-hidden-approx"
                config.nearPointTo -> if (exact) "player-near-line-point" else "player-near-line-point-approx"
                else -> if (exact) "player-near-line" else "player-near-line-approx"
            }
            ess.messages.sendRaw(me, ess.messages.raw(key), ph)
        }
    }

    /**
     * `/근처 가리키기` 로 가리킬 수 있나 — `/근처` 목록과 같은 숨김 규칙(2026-10-10 리뷰: 명령을 직접 치면 투명 물약·관전 모드인 사람도 가리켰다).
     * 나 자신·볼 수 없는 사람·관전 모드·`near.invisibility.hide` 의 투명 물약(가린 이름이어도 안 됨)은 "없음". 다른 월드·`near.max-distance` 밖은 [point] 가 "멀다".
     * `near.point-to: false` 면 하위 명령 자체가 없다.
     */
    private fun pointable(me: Player, target: Player): Boolean {
        val c = config
        if (target == me || !Vanish.canSee(me, target) || !me.canSee(target)) return false
        return NearMath.listed(target.gameMode == GameMode.SPECTATOR, target.hasPotionEffect(PotionEffectType.INVISIBILITY), c.nearHideInvisible, c.nearObfuscateInvisible, forPoint = true)
    }

    /** 그 사람 쪽으로 몇 블록 동안 입자를 뿌린다(나에게만 보인다 — CMI /point). */
    private fun point(me: Player, target: Player) {
        if (me.world != target.world || me.location.distance(target.location) > config.nearMax) return ess.messages.send(me, "player-near-point-far", Ph.of().target(target.name))
        val from = me.eyeLocation
        val dir = target.eyeLocation.toVector().subtract(from.toVector()).normalize()
        val dust = Particle.DustOptions(org.bukkit.Color.fromRGB(255, 200, 40), 1.2f)
        var d = 1.0
        while (d <= 8.0) {
            me.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(d)), 2, 0.02, 0.02, 0.02, 0.0, dust)
            d += 0.4
        }
        ess.messages.send(me, "player-near-point", Ph.of().target(target.name))
    }
}

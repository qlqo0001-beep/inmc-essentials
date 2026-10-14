package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.Mail
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.VanillaNames
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.command.CommandSender
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * `/지급`(cgive, i) · `/전원지급`(giveall) · `/머리`(head, skull) · `/몹머리`(mobhead) — 카탈로그 cmd3:cmd-give · cmd3:cmd-giveall · cmd4:sys-mobhead(지급만).
 *
 * 대상: 이름(접속 중이면 바로, 아니면 우편) · `*`(접속자 전원 — 보내는 사람이 못 보는 투명인도 받고 인원에서만 빠진다) ·
 * `**`(최근 N일 안에 들어온 전원 — 접속자는 바로, 나머지는 우편). 첫 낱말이 아는 사람이 아니면 `/i diamond 64` 처럼 자기 지급으로 읽는다.
 * 여러 사람이면 한 틱에 [GiveSettings.perTick] 명씩 나눠 준다.
 */
class GiveCommands(private val ess: Ess, private val feature: ItemsFeature) {

    private val give: ItemGive get() = feature.give
    private val settings: ItemsSettings get() = feature.settings

    fun register() {
        ess.commands.add(
            Commands.literal("지급").requires { it.sender.hasPermission(ItemsFeature.GIVE) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "items-give-usage"); 1 }
                .then(ess.args.players("대상").suggests(TARGETS)
                    .executes { ctx -> selfOrUsage(ctx.source.sender, StringArgumentType.getString(ctx, "대상")); 1 }
                    .then(Commands.argument("아이템", StringArgumentType.greedyString()).suggests(itemSuggestions)
                        .executes { ctx ->
                            give(ctx.source.sender, StringArgumentType.getString(ctx, "대상"), StringArgumentType.getString(ctx, "아이템"), false)
                            1
                        }))
                .build(),
            "아이템 지급 — 참조·손·만들기 꼬리, 대상 이름/*/**", listOf("cgive", "i"),
        )
        ess.commands.add(
            Commands.literal("전원지급").requires { it.sender.hasPermission(ItemsFeature.GIVE_ALL) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "items-giveall-usage"); 1 }
                .then(Commands.argument("아이템", StringArgumentType.greedyString()).suggests(itemSuggestions)
                    .executes { ctx -> give(ctx.source.sender, "*", StringArgumentType.getString(ctx, "아이템"), true); 1 })
                .build(),
            "접속자 전원(offline 을 붙이면 최근 접속자 전원)에게 아이템 지급", listOf("giveall"),
        )
        ess.commands.add(
            Commands.literal("머리").requires { it.sender.hasPermission(ItemsFeature.HEAD) }
                .executes { ctx -> (ctx.source.sender as? Player)?.let { head(it, it.name, it, 1) } ?: ess.messages.send(ctx.source.sender, "player-only"); 1 }
                .then(Commands.argument("이름", Args.TOKEN).suggests(ess.args.names)
                    .executes { ctx -> headSelf(ctx); 1 }
                    .then(ess.args.player("받을사람").requires { it.sender.hasPermission(ItemsFeature.HEAD_OTHERS) }
                        .executes { ctx -> headOther(ctx, 1); 1 }
                        .then(Commands.argument("개수", IntegerArgumentType.integer(1, 2304))
                            .executes { ctx -> headOther(ctx, IntegerArgumentType.getInteger(ctx, "개수")); 1 })))
                .build(),
            "플레이어·텍스처 머리 받기", listOf("head", "skull"),
        )
        ess.commands.add(
            Commands.literal("몹머리").requires { it.sender.hasPermission(ItemsFeature.MOBHEAD) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "items-mobhead-usage"); 1 }
                .then(Commands.argument("몹", Args.TOKEN).suggests(mobSuggestions)
                    .executes { ctx -> (ctx.source.sender as? Player)?.let { mob(it, StringArgumentType.getString(ctx, "몹"), it, 1) } ?: ess.messages.send(ctx.source.sender, "player-only"); 1 }
                    .then(ess.args.player("받을사람").requires { it.sender.hasPermission(ItemsFeature.HEAD_OTHERS) }
                        .executes { ctx -> mobOther(ctx, 1); 1 }
                        .then(Commands.argument("개수", IntegerArgumentType.integer(1, 2304))
                            .executes { ctx -> mobOther(ctx, IntegerArgumentType.getInteger(ctx, "개수")); 1 })))
                .build(),
            "몹 머리 받기", listOf("mobhead"),
        )
    }

    // --- /지급 ---------------------------------------------------------------------------------------

    fun give(sender: CommandSender, targetRaw: String, tail: String, everyoneOnline: Boolean) {
        // CMI `/i <아이템> [개수|꼬리]` 손버릇 — 첫 낱말이 아는 사람이 아니면, 꼬리를 읽기 **전에** "첫 낱말 + 꼬리" 를 자기 지급으로 해 본다
        // (꼬리만으로는 `64`·`n{…}` 처럼 아이템이 없어 읽기 오류가 난다).
        val selfTry = !everyoneOnline && sender is Player && GiveTarget.parse(targetRaw) is GiveTarget.Named
        if (selfTry && !knownPlayer(targetRaw.trim()) && selfGive(sender as Player, GiveSpec.selfText(targetRaw, tail))) return
        val spec = when (val parsed = GiveSpec.parse(tail)) {
            is GiveSpec.Parsed.Error -> {
                // 첫 낱말이 사람 이름이기도 하고(예: `Stone` 이라는 사람) 꼬리가 아이템 없이 읽히지 않으면, 오류를 알리기 전에 자기 지급으로 한 번 더.
                if (selfTry && selfGive(sender as Player, GiveSpec.selfText(targetRaw, tail))) return
                val key = SPEC_PREFIX + parsed.reason // items-spec-unclosed · -slot · -days · -enchant · -amount · -flag · -no-item
                return ess.messages.send(sender, key, Ph.of().value(parsed.token))
            }
            is GiveSpec.Parsed.Ok -> parsed.spec
        }
        if ((spec.amount ?: 1) > settings.give.maxAmount) return ess.messages.send(sender, "items-amount-too-large", Ph.of().count(settings.give.maxAmount))
        var target = if (everyoneOnline) GiveTarget.Online else GiveTarget.parse(targetRaw)
        if (spec.offline && target == GiveTarget.Online) target = GiveTarget.Everyone
        if (target !is GiveTarget.Named && !sender.hasPermission(ItemsFeature.GIVE_ALL)) return ess.messages.send(sender, "no-permission")
        val (sources, error) = give.sources(sender, spec)
        if (error != null) return ess.messages.send(sender, error.first, Ph.of().value(error.second))
        val label = sources.joinToString(", ") { it.label } + (spec.amount?.let { " x$it" } ?: "")
        val source = "관리자 지급 (${sender.name})"
        when (target) {
            is GiveTarget.Named -> {
                val record = ess.args.offline(sender, target.name) ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(target.name))
                val online = Bukkit.getPlayer(record.id)
                if (online != null) {
                    val overflow = give.deliver(online, give.build(sources, spec), source, spec.slot)
                    if (!spec.silent && settings.give.notifyTarget && online != sender) {
                        ess.messages.send(online, "items-received", Ph.of().item(label).player(sender.name))
                    }
                    // 규칙 8 — 보내는 사람이 못 보는 투명인이면 '접속 중' 이 새지 않게 우편 길과 같은 말을.
                    if (Vanish.canSee(sender, online)) ess.messages.send(sender, "items-given", Ph.of().item(label).player(online.name).count(overflow))
                    else ess.messages.send(sender, "items-given-mail", Ph.of().item(label).player(record.name))
                } else {
                    if (!Mail.available) return ess.messages.send(sender, "items-offline-no-mail", Ph.of().player(record.name))
                    if (give.mail(record.id, give.build(sources, spec), source)) ess.messages.send(sender, "items-given-mail", Ph.of().item(label).player(record.name))
                    else ess.messages.send(sender, "items-mail-failed", Ph.of().player(record.name))
                }
            }
            // 받는 사람은 접속자 **전원**(보내는 사람이 못 보는 투명인도 — CMI giveall 과 같다). 보내는 사람에게 보이는 인원·이름만 투명을 거른다(규칙 8).
            GiveTarget.Online -> {
                val players = Bukkit.getOnlinePlayers().toList()
                run(sender, players.map { it.uniqueId to it.name }, sources, spec, label, source)
            }
            GiveTarget.Everyone -> {
                val days = spec.days ?: settings.give.offlineDays
                val cutoff = System.currentTimeMillis() - days * 86_400_000L
                val online = Bukkit.getOnlinePlayers().map { it.uniqueId }.toSet()
                val people = ess.users.all().filter { it.id in online || it.lastJoin >= cutoff }
                if (!Mail.available && people.any { it.id !in online }) ess.messages.send(sender, "items-everyone-no-mail")
                run(sender, people.filter { Mail.available || it.id in online }.map { it.id to it.name }, sources, spec, label, source)
            }
        }
    }

    /** `/지급 <낱말>` 만 쳤을 때 — 사람이 아니고 아이템으로 읽히면 자기에게, 아니면 쓰는 법. */
    private fun selfOrUsage(sender: CommandSender, raw: String) {
        if (sender is Player && GiveTarget.parse(raw) is GiveTarget.Named && !knownPlayer(raw.trim()) && selfGive(sender, raw)) return
        ess.messages.send(sender, "items-give-usage")
    }

    /**
     * [word] 가 사람인가 — **정확한 이름**(온 적 있는 기록, 대소문자 무시) 또는 닉네임만. `ess.args.offline` 의 앞부분·부분 일치는 쓰지 않는다:
     * `stone` 이 `Stoneheart` 로 읽혀 `/i stone 64` 가 자기 지급이 되지 못했다(2026-10-14 검토).
     */
    private fun knownPlayer(word: String): Boolean =
        word.isNotEmpty() && (ess.users.byName(word) != null || ess.args.nicknameOwner(word) != null)

    /** [text] 전체가 아이템 낱말로 읽히고 만들 수 있으면 [sender] 에게 준다. 아니면 아무것도 안 하고 false. */
    private fun selfGive(sender: Player, text: String): Boolean {
        val spec = (GiveSpec.parse(text) as? GiveSpec.Parsed.Ok)?.spec ?: return false
        if (give.sources(sender, spec).second != null) return false
        give(sender, sender.name, text, false)
        return true
    }

    /** 여러 사람 — 한 틱에 perTick 명씩. 접속자는 바로, 나머지는 우편. */
    private fun run(sender: CommandSender, people: List<Pair<UUID, String>>, sources: List<ItemGive.Source>, spec: GiveSpec, label: String, source: String) {
        // 인원은 보내는 사람이 볼 수 있는 사람만 센다(규칙 8) — 못 보는 투명인도 받지만 숫자로 새지 않게.
        fun shown(id: UUID): Boolean = Bukkit.getPlayer(id)?.let { Vanish.canSee(sender, it) } ?: true
        val shownTotal = people.count { shown(it.first) }
        if (people.isEmpty()) return ess.messages.send(sender, "items-nobody")
        // 보이는 사람이 하나도 없으면(못 보는 투명인뿐) '받을 사람이 없다' 로만 알리고 조용히 준다.
        val quiet = shownTotal == 0
        if (quiet) ess.messages.send(sender, "items-nobody")
        val per = settings.give.perTick
        var index = 0
        var direct = 0
        var mailed = 0
        var failed = 0
        if (!quiet) ess.messages.send(sender, "items-give-start", Ph.of().count(shownTotal).item(label))
        fun step() {
            val end = (index + per).coerceAtMost(people.size)
            while (index < end) {
                val (id, _) = people[index++]
                val online = Bukkit.getPlayer(id)
                val counted = shown(id)
                try {
                    if (online != null) {
                        give.deliver(online, give.build(sources, spec), source, spec.slot)
                        if (!spec.silent && settings.give.notifyTarget && online != sender) {
                            ess.messages.send(online, "items-received", Ph.of().item(label).player(sender.name))
                        }
                        if (counted) direct++
                    } else if (give.mail(id, give.build(sources, spec), source)) mailed++ else failed++
                } catch (t: Throwable) {
                    if (counted) failed++
                    ess.logger.warning("지급 실패 ($id): $t")
                }
            }
            if (index < people.size) {
                if (!quiet) ess.messages.send(sender, "items-give-progress", Ph.of().count(people.take(index).count { shown(it.first) }).amount(shownTotal.toString()))
                Bukkit.getScheduler().runTask(ess.plugin, Runnable { step() })
            } else if (!quiet) {
                ess.messages.send(sender, "items-give-done", Ph.of().count(direct).amount(mailed.toString()).value(failed.toString()).item(label))
            }
        }
        step()
    }

    // --- 머리 ----------------------------------------------------------------------------------------

    private fun headSelf(ctx: CommandContext<CommandSourceStack>) {
        val sender = ctx.source.sender as? Player ?: return ess.messages.send(ctx.source.sender, "player-only")
        head(sender, StringArgumentType.getString(ctx, "이름"), sender, 1)
    }

    private fun headOther(ctx: CommandContext<CommandSourceStack>, amount: Int) {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, "받을사람")
        val receiver = ess.args.online(sender, name) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(name))
        head(sender, StringArgumentType.getString(ctx, "이름"), receiver, amount)
    }

    fun head(sender: CommandSender, input: String, receiver: Player, amount: Int) {
        val texture = Heads.textureValue(input)
        if (texture != null) return handHead(sender, receiver, Heads.fromTexture(texture, null), "머리", amount)
        // 보내는 사람이 볼 수 있는 접속자만 바로 — 못 보는 투명인은 접속하지 않은 사람과 같은 길(불러오는 중 → 다음에)로, 접속 중임이 새지 않게.
        val online = Bukkit.getPlayerExact(input)?.takeIf { Vanish.canSee(sender, it) }
        if (online != null) return handHead(sender, receiver, Heads.fromProfile(online.playerProfile, null), "${online.name}의 머리", amount)
        if (!NAME.matches(input)) return ess.messages.send(sender, "items-head-invalid", Ph.of().value(input))
        // 접속하지 않은 사람 — 프로필을 모장에서 받아 온다(비동기). 받는 사람이 그새 나가면 버린다.
        ess.messages.send(sender, "items-head-loading", Ph.of().value(input))
        val profile = Bukkit.createProfile(input)
        profile.update().whenComplete { done, _ ->
            Bukkit.getScheduler().runTask(ess.plugin, Runnable {
                if (!receiver.isOnline) return@Runnable
                handHead(sender, receiver, Heads.fromProfile(done ?: profile, null), "${done?.name ?: input}의 머리", amount)
            })
        }
    }

    private fun handHead(sender: CommandSender, receiver: Player, head: ItemStack, label: String, amount: Int) {
        head.editMeta { it.displayName(ItemGive.mini("<yellow>$label</yellow>")) }
        val stacks = ItemGive.repeatCreate(amount) { left -> head.clone().also { it.amount = left.coerceAtMost(64) } }
        give.deliver(receiver, stacks, "머리 지급 (${sender.name})")
        ess.messages.send(sender, "items-head-given", Ph.of().item(label).player(receiver.name).count(amount))
        if (receiver != sender) ess.messages.send(receiver, "items-received", Ph.of().item(label).player(sender.name))
    }

    // --- 몹 머리 ----------------------------------------------------------------------------------------

    private fun mobOther(ctx: CommandContext<CommandSourceStack>, amount: Int) {
        val sender = ctx.source.sender
        val name = StringArgumentType.getString(ctx, "받을사람")
        val receiver = ess.args.online(sender, name) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(name))
        mob(sender, StringArgumentType.getString(ctx, "몹"), receiver, amount)
    }

    fun mob(sender: CommandSender, input: String, receiver: Player, amount: Int) {
        val key = mobKey(input) ?: return ess.messages.send(sender, "items-mobhead-unknown", Ph.of().value(input))
        val choice = settings.mobHeads[key]?.randomOrNull() ?: return ess.messages.send(sender, "items-mobhead-unknown", Ph.of().value(input))
        val label = (mobName(key) ?: key) + " 머리"
        val material = Material.matchMaterial(choice)
        val stack = if (material != null && material.isItem) ItemStack(material) else Heads.fromTexture(Heads.encode(choice), null)
        handHead(sender, receiver, stack, label, amount)
    }

    /** 영어 id 또는 한글 이름 → 설정의 열쇠. */
    private fun mobKey(input: String): String? {
        val lower = input.trim().lowercase().removePrefix("minecraft:")
        if (lower in settings.mobHeads) return lower
        return settings.mobHeads.keys.firstOrNull { mobName(it) == input.trim() }
    }

    private fun mobName(key: String): String? =
        runCatching { Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(key)) }.getOrNull()?.let { type: EntityType -> VanillaNames.of(type) }

    // --- 제안 ---------------------------------------------------------------------------------------

    private val mobSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remainingLowerCase
        for (key in settings.mobHeads.keys) {
            if (key.startsWith(typed)) builder.suggest(key)
            mobName(key)?.let { if (it.startsWith(builder.remaining)) builder.suggest(it) }
        }
        builder.buildFuture()
    }

    private val itemSuggestions = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val remaining = builder.remaining
        val start = GiveSpec.lastTokenStart(remaining)
        if (start < 0) return@SuggestionProvider builder.buildFuture()
        val word = remaining.substring(start)
        val out = builder.createOffset(builder.start + start)
        for (candidate in candidates(ctx.source.sender, word)) out.suggest(candidate)
        out.buildFuture()
    }

    /**
     * 지금 치는 낱말의 후보 — 특수 낱말 · 재질 · `네임스페이스:id`(CustomItemHook.ids).
     *
     * **만들기 전용** 네임스페이스(`CustomItemHook.makeOnlyNamespaces` — 낚시 물고기·인첸트 부여서·상점 블록, `identify` 가 늘 null)는
     * [includeMakeOnly] 가 false 면(찾기·지우기처럼 참조로 **맞춰 보는** 명령) 아예 빼고, true 여도 `네임스페이스:` 를 치기 시작했을 때만 내놓는다 —
     * 접두어 없는 id 로도 맞추면 `s` 하나에 인첸트 수백 줄이 재질·`inmc:` 아이템을 뒤덮는다.
     */
    fun candidates(sender: CommandSender, word: String, includeMakeOnly: Boolean = true): List<String> {
        val lower = word.lowercase()
        for (prefix in listOf(GiveSpec.HAND_OF, GiveSpec.INV_OF)) {
            if (lower.startsWith(prefix)) {
                val typed = lower.removePrefix(prefix)
                return Vanish.visibleNames(sender).filter { it.lowercase().startsWith(typed) }.map { prefix + it }
            }
        }
        val out = ArrayList<String>()
        SPECIAL.filterTo(out) { it.startsWith(lower) }
        val makeOnly = runCatching { CustomItemHook.makeOnlyNamespaces() }.getOrDefault(emptySet())
        val custom = runCatching { CustomItemHook.ids() }.getOrDefault(emptyMap()).filterKeys { includeMakeOnly || it.lowercase() !in makeOnly }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        if (lower.isEmpty()) {
            custom.keys.mapTo(out) { "$it:" }
            out += "mmoitems:"
            return out
        }
        // 커스텀 아이템은 **전부**(정렬, 제한 없음) — 사용자 요구 "모든 inmc 아이템이 보여야 한다". 네임스페이스를 쳤으면 그 네임스페이스만.
        val namespaceTyped = lower.substringBefore(':', "").takeIf { lower.contains(':') && it != "minecraft" }
        for ((namespace, ids) in custom) {
            if (namespaceTyped != null && !namespace.equals(namespaceTyped, ignoreCase = true)) continue
            val bareIdToo = namespaceTyped == null && namespace.lowercase() !in makeOnly
            for (id in ids.sortedWith(String.CASE_INSENSITIVE_ORDER)) {
                val full = "$namespace:$id"
                if (full.lowercase().startsWith(lower) || (bareIdToo && id.lowercase().startsWith(lower))) out += full
            }
        }
        if (namespaceTyped != null) return out
        // 바닐라 재질만 [LIMIT] 개까지(1,400 개 넘는 재질을 한꺼번에 보내지 않게 — 더 치면 좁혀진다).
        val bare = lower.removePrefix("minecraft:")
        var materials = 0
        for (name in MATERIALS) {
            if (!name.startsWith(bare)) continue
            out += if (lower.startsWith("minecraft:")) "minecraft:$name" else name
            if (++materials >= LIMIT) break
        }
        return out
    }

    companion object {
        const val SPEC_PREFIX = "items-spec-"
        private val NAME = Regex("^[A-Za-z0-9_]{1,16}$")
        private const val LIMIT = 120
        private val SPECIAL = listOf("hand", "hand:", "inv:", "-s", "-slot:", "-d:", "n{", "l{", "e{", "unbreakable", "offline")

        private val MATERIALS: List<String> by lazy {
            Material.entries.filter { !it.name.startsWith("LEGACY_") && it.isItem && !it.isAir }.map { it.key.value() }.sorted()
        }

        private val TARGETS = SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val typed = builder.remainingLowerCase
            for (special in listOf("*", "**")) if (special.startsWith(typed)) builder.suggest(special)
            for (name in Vanish.visibleNames(ctx.source.sender)) if (name.lowercase().startsWith(typed)) builder.suggest(name)
            builder.buildFuture()
        }
    }
}

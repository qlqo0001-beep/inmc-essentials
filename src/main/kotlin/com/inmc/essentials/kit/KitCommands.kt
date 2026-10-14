package com.inmc.essentials.kit

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.Durations
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/킷`(kit, kits) · `/킷편집`(kiteditor) · `/킷초기화`(kitreset) — 카탈로그 cfg4:cmd-kit · cmd4:cmd-kiteditor · fix:cfg-kit-groups-conditions.
 *
 * - `/킷` 목록 화면(설정 gui: false 면 채팅 목록) · `/킷 <이름|그룹>` 받기 · `/킷 <이름> 미리보기` · `/킷 <이름> <대상> [-skipusage]` 남에게(검사·비용 없이).
 * - `/킷초기화 <킷|all> <플레이어|all> [쿨타임|횟수]` — CMI kitcdreset·kitusagereset 를 하나로(셋째 인자가 없으면 둘 다).
 */
class KitCommands(private val ess: Ess, private val feature: KitsFeature) {

    private val service: KitService get() = feature.service
    private val store: KitStore get() = feature.store

    fun register() {
        val kitArg = Commands.argument("이름", Args.TOKEN).suggests(KITS)
            .executes { ctx -> claim(ctx.source.sender, StringArgumentType.getString(ctx, "이름")); 1 }
        for (word in listOf("미리보기", "preview")) kitArg.then(Commands.literal(word).executes { ctx -> preview(ctx.source.sender, StringArgumentType.getString(ctx, "이름")); 1 })
        kitArg.then(ess.args.player("대상").requires { it.sender.hasPermission(KitsFeature.OTHERS) }
            .executes { ctx -> giveOther(ctx.source.sender, StringArgumentType.getString(ctx, "이름"), StringArgumentType.getString(ctx, "대상"), true); 1 }
            .then(Commands.literal("-skipusage").executes { ctx ->
                giveOther(ctx.source.sender, StringArgumentType.getString(ctx, "이름"), StringArgumentType.getString(ctx, "대상"), false); 1
            }))
        ess.commands.add(
            Commands.literal("킷").requires { it.sender.hasPermission(KitsFeature.USE) }
                .executes { ctx -> open(ctx.source.sender); 1 }
                .then(kitArg)
                .build(),
            "킷 목록·받기·미리보기", listOf("kit", "kits"),
        )
        ess.commands.add(
            Commands.literal("킷편집").requires { it.sender.hasPermission(KitsFeature.EDIT) }
                .executes { ctx ->
                    val p = ctx.source.sender as? Player
                    if (p == null) ess.messages.send(ctx.source.sender, "player-only") else KitAdminMenu(ess, p, feature).show()
                    1
                }
                .build(),
            "킷 만들기·고치기(화면)", listOf("kiteditor"),
        )
        val reset = Commands.argument("대상", Args.TOKEN).suggests(PLAYERS_ALL)
            .executes { ctx -> reset(ctx.source.sender, StringArgumentType.getString(ctx, "킷"), StringArgumentType.getString(ctx, "대상"), null); 1 }
        for ((word, mode) in listOf("쿨타임" to "cooldown", "cooldown" to "cooldown", "횟수" to "uses", "uses" to "uses")) {
            reset.then(Commands.literal(word).executes { ctx -> reset(ctx.source.sender, StringArgumentType.getString(ctx, "킷"), StringArgumentType.getString(ctx, "대상"), mode); 1 })
        }
        ess.commands.add(
            Commands.literal("킷초기화").requires { it.sender.hasPermission(KitsFeature.RESET) }
                .executes { ctx -> ess.messages.send(ctx.source.sender, "kits-reset-usage"); 1 }
                .then(Commands.argument("킷", Args.TOKEN).suggests(KITS_ALL).then(reset))
                .build(),
            "킷 쿨타임·사용 횟수 되돌리기", listOf("kitreset"),
        )
    }

    // --- 찾기 ----------------------------------------------------------------------------------------

    fun find(input: String): Kit? {
        val text = input.trim()
        store.get(text)?.let { return it }
        return store.all().firstOrNull { KitService.plainName(it).equals(text, ignoreCase = true) }
    }

    // --- 받기 · 목록 ---------------------------------------------------------------------------------

    private fun open(sender: CommandSender) {
        val player = sender as? Player
        if (player != null && feature.settings.gui) return KitListMenu(ess, player, feature).show()
        list(sender)
    }

    private fun list(sender: CommandSender) {
        val player = sender as? Player
        val kits = store.all().filter { !it.hidden && (player == null || (service.canAccess(player, it) && service.listed(player, it))) }
        if (kits.isEmpty()) return ess.messages.send(sender, "kits-none")
        ess.messages.send(sender, "kits-list-header", Ph.of().count(kits.size))
        for (kit in kits) {
            val state = if (player == null) "" else when (val v = service.verdict(player, kit)) {
                KitService.Verdict(null) -> ess.messages.raw("kits-state-ready")
                else -> if (v.denied == KitService.Denied.COOLDOWN) Durations.formatShort(v.left) else ess.messages.raw("kits-state-locked")
            }
            ess.messages.send(sender, "kits-list-entry", Ph.of().subject(kit.name).value(kit.id).raw("{상태}", state))
        }
    }

    private fun claim(sender: CommandSender, input: String) {
        val player = sender as? Player ?: return ess.messages.send(sender, "player-only")
        val kit = find(input)
        if (kit == null) {
            if (service.claimGroup(player, input)) return
            return ess.messages.send(sender, "kits-unknown", Ph.of().value(input))
        }
        service.claim(player, kit)
    }

    private fun preview(sender: CommandSender, input: String) {
        val player = sender as? Player ?: return ess.messages.send(sender, "player-only")
        val kit = find(input) ?: return ess.messages.send(sender, "kits-unknown", Ph.of().value(input))
        if (!service.canPreview(player, kit)) return ess.messages.send(sender, "kits-preview-denied")
        KitPreviewMenu(ess, player, feature, kit, null).show()
    }

    private fun giveOther(sender: CommandSender, input: String, targetName: String, record: Boolean) {
        val kit = find(input) ?: return ess.messages.send(sender, "kits-unknown", Ph.of().value(input))
        val targets = ess.args.targets(sender, targetName) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(targetName))
        for (target in targets) service.claim(target, kit, by = sender, free = true, record = record)
    }

    // --- 초기화 ---------------------------------------------------------------------------------------

    private fun reset(sender: CommandSender, kitInput: String, targetInput: String, mode: String?) {
        val kitId = if (kitInput.lowercase() in ALL) null else (find(kitInput)?.id ?: kitInput.lowercase().takeIf { Kit.validId(it) }
            ?: return ess.messages.send(sender, "kits-unknown", Ph.of().value(kitInput)))
        val player = if (targetInput.lowercase() in ALL) null else (ess.args.offline(sender, targetInput)?.id
            ?: return ess.messages.send(sender, "user-not-found", Ph.of().value(targetInput)))
        // 바뀐 줄 수는 DB 에서 센다(접속하지 않은 사람의 기록은 DB 에만 있다).
        store.reset(player, kitId, cooldownOnly = mode == "cooldown", usesOnly = mode == "uses") { n ->
            ess.messages.send(sender, "kits-reset-done", Ph.of().value(kitId ?: "*").player(if (player == null) "*" else targetInput).count(n))
        }
    }

    private val KITS = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val typed = builder.remainingLowerCase
        val player = ctx.source.sender as? Player
        val seen = HashSet<String>()
        for (kit in store.all()) {
            if (player != null && ((!service.canAccess(player, kit) && !service.canPreview(player, kit)) || !service.listed(player, kit))) continue
            if (kit.id.startsWith(typed) && seen.add(kit.id)) builder.suggest(kit.id)
            if (kit.group.isNotEmpty() && kit.group.lowercase().startsWith(typed) && seen.add(kit.group.lowercase())) builder.suggest(kit.group)
        }
        builder.buildFuture()
    }

    private val KITS_ALL = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remainingLowerCase
        if ("all".startsWith(typed)) builder.suggest("all")
        for (kit in store.all()) if (kit.id.startsWith(typed)) builder.suggest(kit.id)
        builder.buildFuture()
    }

    companion object {
        private val ALL = setOf("all", "*", "전부", "전원")

        private val PLAYERS_ALL = SuggestionProvider<CommandSourceStack> { ctx, builder ->
            val typed = builder.remainingLowerCase
            if ("all".startsWith(typed)) builder.suggest("all")
            for (name in Vanish.visibleNames(ctx.source.sender)) if (name.lowercase().startsWith(typed)) builder.suggest(name)
            builder.buildFuture()
        }
    }
}

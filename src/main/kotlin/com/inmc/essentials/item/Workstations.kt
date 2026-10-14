package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.util.VanillaNames
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.MenuType

/**
 * 가상 작업대 — `/작업대`(workbench, wb, craft) · `/모루`(anvil) · `/숫돌`(grindstone) · `/베틀`(loom) · `/석재절단기`(stonecutter) ·
 * `/대장장이`(smithingtable) · `/지도제작대`(cartographytable) · `/거래창 <직업> [레벨 | 플레이어 [레벨]]`(merchant) — 카탈로그 mod:sys-workstations.
 *
 * Paper `MenuType` 빌더(서버가 품은 paper-api 26.1.2 에도 있음 — javap 로 확인)로 연다. **자리(location)를 꼭 준다** — 자리가 없으면
 * 작업대의 결과 칸이 계산되지 않는다(바닐라 CraftingMenu 가 자리 접근으로만 결과를 고친다). `checkReachable(false)` — 블록이 없어도 열려 있게.
 * 거래창은 소환하지 않은 주민(`createEntity`)에게 직업·레벨을 주어 바닐라 거래 목록을 뽑고, 그 목록을 가상 상인에 옮겨 연다.
 * **같은 사람·직업·레벨의 상인은 [RESTOCK_MS] 동안 다시 쓴다** — 다시 열 때마다 새로 뽑으면 거래 횟수 잠김이 풀리고(썩은 살 무한 판매)
 * 높은 레벨 거래(수선 책)를 원하는 것이 나올 때까지 다시 굴릴 수 있다. 그 시간이 지나면 새 상인(= 주민의 보충).
 */
class Workstations(private val ess: Ess) {

    private class Cached(val merchant: org.bukkit.inventory.Merchant, val at: Long)

    /** `<uuid>:<직업>:<레벨>` → 상인. 메인에서만. */
    private val merchants = HashMap<String, Cached>()

    enum class Station(val command: String, val aliases: List<String>, val permission: String, val translation: String) {
        CRAFTING("작업대", listOf("workbench", "wb", "craft"), ItemsFeature.WORKBENCH, "container.crafting"),
        ANVIL("모루", listOf("anvil"), ItemsFeature.ANVIL, "container.repair"),
        GRINDSTONE("숫돌", listOf("grindstone"), ItemsFeature.GRINDSTONE, "container.grindstone_title"),
        LOOM("베틀", listOf("loom"), ItemsFeature.LOOM, "container.loom"),
        STONECUTTER("석재절단기", listOf("stonecutter"), ItemsFeature.STONECUTTER, "container.stonecutter"),
        SMITHING("대장장이", listOf("smithingtable"), ItemsFeature.SMITHING, "container.upgrade"),
        CARTOGRAPHY("지도제작대", listOf("cartographytable"), ItemsFeature.CARTOGRAPHY, "container.cartography_table"),
    }

    fun register() {
        for (station in Station.entries) {
            val root = Commands.literal(station.command).requires { it.sender.hasPermission(station.permission) }
                .executes { ctx -> self(ctx.source.sender, station); 1 }
                .then(ess.args.player().requires { it.sender.hasPermission(ItemsFeature.WORKSTATION_OTHERS) }
                    .executes { ctx -> other(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), station, false); 1 }
                    .then(Commands.literal("-s").executes { ctx -> other(ctx.source.sender, StringArgumentType.getString(ctx, "플레이어"), station, true); 1 }))
            ess.commands.add(root.build(), "가상 " + station.command + " 열기", station.aliases)
        }
        ess.commands.add(merchantCommand().build(), "주민 없이 거래창 열기(직업·레벨)", listOf("merchant"))
        ess.ticks.add("items-merchant-restock", 60) { now -> merchants.values.removeIf { now - it.at > RESTOCK_MS } }
    }

    private fun self(sender: CommandSender, station: Station) {
        val player = sender as? Player ?: return ess.messages.send(sender, "player-only")
        if (!open(player, station)) ess.messages.send(sender, "items-station-failed")
    }

    private fun other(sender: CommandSender, name: String, station: Station, silent: Boolean) {
        val target = ess.args.online(sender, name) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(name))
        if (!open(target, station)) return ess.messages.send(sender, "items-station-failed")
        ess.messages.send(sender, "items-station-opened", Ph.of().player(target.name).value(station.command))
        if (!silent && target != sender) ess.messages.send(target, "items-station-opened-by", Ph.of().player(sender.name).value(station.command))
    }

    /** 연다. 서버가 그 화면을 못 만들면 false. */
    fun open(player: Player, station: Station): Boolean = try {
        val title = Component.translatable(station.translation)
        val location = player.location
        val view: InventoryView = when (station) {
            Station.CRAFTING -> MenuType.CRAFTING.builder().title(title).location(location).checkReachable(false).build(player)
            Station.ANVIL -> MenuType.ANVIL.builder().title(title).location(location).checkReachable(false).build(player)
            Station.GRINDSTONE -> MenuType.GRINDSTONE.builder().title(title).location(location).checkReachable(false).build(player)
            Station.LOOM -> MenuType.LOOM.builder().title(title).location(location).checkReachable(false).build(player)
            Station.STONECUTTER -> MenuType.STONECUTTER.builder().title(title).location(location).checkReachable(false).build(player)
            Station.SMITHING -> MenuType.SMITHING.builder().title(title).location(location).checkReachable(false).build(player)
            Station.CARTOGRAPHY -> MenuType.CARTOGRAPHY_TABLE.builder().title(title).location(location).checkReachable(false).build(player)
        }
        player.openInventory(view)
        true
    } catch (t: Throwable) {
        ess.logger.warning("가상 ${station.command} 을(를) 열지 못했습니다: $t")
        false
    }

    // --- 거래창 ---------------------------------------------------------------------------------------

    private fun merchantCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("거래창").requires { it.sender.hasPermission(ItemsFeature.MERCHANT) }
            .executes { ctx -> ess.messages.send(ctx.source.sender, "items-merchant-usage"); 1 }
            .then(Commands.argument("직업", com.inmc.essentials.command.Args.TOKEN).suggests(PROFESSIONS)
                .executes { ctx -> merchant(ctx.source.sender, StringArgumentType.getString(ctx, "직업"), null, 1); 1 }
                // `/거래창 <직업> <레벨>` — 자기 거래창의 레벨은 남에게 열어 주는 권한 없이. 숫자는 먼저 단 이 가지가 읽는다(brigadier 는 둘 다 읽히면 먼저 단 형제를 고른다).
                .then(Commands.argument("레벨", IntegerArgumentType.integer(1, 5))
                    .executes { ctx -> merchant(ctx.source.sender, StringArgumentType.getString(ctx, "직업"), null, IntegerArgumentType.getInteger(ctx, "레벨")); 1 })
                .then(ess.args.player().requires { it.sender.hasPermission(ItemsFeature.WORKSTATION_OTHERS) }
                    .executes { ctx -> merchant(ctx.source.sender, StringArgumentType.getString(ctx, "직업"), StringArgumentType.getString(ctx, "플레이어"), 1); 1 }
                    .then(Commands.argument("레벨", IntegerArgumentType.integer(1, 5))
                        .executes { ctx ->
                            merchant(ctx.source.sender, StringArgumentType.getString(ctx, "직업"), StringArgumentType.getString(ctx, "플레이어"), IntegerArgumentType.getInteger(ctx, "레벨"))
                            1
                        })))

    private fun merchant(sender: CommandSender, professionRaw: String, targetName: String?, level: Int) {
        val target = if (targetName == null) sender as? Player ?: return ess.messages.send(sender, "player-only")
        else ess.args.online(sender, targetName) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(targetName))
        val profession = profession(professionRaw) ?: return ess.messages.send(sender, "items-merchant-unknown", Ph.of().value(professionRaw))
        val label = professionName(profession)
        val opened = try {
            val cacheKey = "${target.uniqueId}:${key(profession)}:$level"
            val now = System.currentTimeMillis()
            val merchant = merchants[cacheKey]?.takeIf { now - it.at <= RESTOCK_MS }?.merchant ?: run {
                // 소환하지 않은 주민 — 월드에 들어가지 않는다. 거래 목록만 뽑고 버린다.
                val villager = target.world.createEntity(target.location, Villager::class.java)
                villager.profession = profession
                villager.villagerLevel = 1
                villager.recipes // 1레벨 거래를 만들게 한다(바닐라: 처음 읽을 때 채운다)
                if (level > 1) villager.increaseLevel(level - 1)
                val recipes = villager.recipes.toList()
                if (recipes.isEmpty()) {
                    ess.messages.send(sender, "items-merchant-empty", Ph.of().value(label))
                    return
                }
                Bukkit.createMerchant(Component.text(label)).also {
                    it.recipes = recipes
                    merchants[cacheKey] = Cached(it, now)
                }
            }
            val view = MenuType.MERCHANT.builder().title(Component.text("$label · ${level}레벨")).merchant(merchant).checkReachable(false).build(target)
            target.openInventory(view)
            true
        } catch (t: Throwable) {
            ess.logger.warning("거래창을 열지 못했습니다($label): $t")
            false
        }
        if (!opened) return ess.messages.send(sender, "items-station-failed")
        if (target != sender) {
            ess.messages.send(sender, "items-station-opened", Ph.of().player(target.name).value(label))
            ess.messages.send(target, "items-station-opened-by", Ph.of().player(sender.name).value(label))
        }
    }

    companion object {

        /** 같은 상인을 다시 쓰는 시간(마인크래프트 하루 = 20분 — 주민이 보충하는 주기쯤). */
        const val RESTOCK_MS = 20 * 60_000L

        /** 영어 id(`farmer`) 또는 한글 이름(`농부`). 직업 없음(`none`)·바보(`nitwit`)는 거래가 없다. */
        fun profession(input: String): Villager.Profession? {
            val lower = input.trim().lowercase().removePrefix("minecraft:")
            runCatching { Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(lower)) }.getOrNull()
                ?.takeIf { lower != "none" && lower != "nitwit" }?.let { return it }
            return professions().firstOrNull { professionName(it) == input.trim() }
        }

        fun professions(): List<Villager.Profession> =
            runCatching { Registry.VILLAGER_PROFESSION.toList() }.getOrDefault(emptyList())
                .filter { key(it) !in setOf("none", "nitwit") }

        fun key(profession: Villager.Profession): String =
            runCatching { Registry.VILLAGER_PROFESSION.getKey(profession)?.key }.getOrNull() ?: "?"

        fun professionName(profession: Villager.Profession): String =
            VanillaNames.ofKey("entity.minecraft.villager." + key(profession)) ?: key(profession)

        private val PROFESSIONS = SuggestionProvider<CommandSourceStack> { _, builder ->
            val typed = builder.remainingLowerCase
            for (p in professions()) {
                val key = key(p)
                if (key.startsWith(typed)) builder.suggest(key)
                val name = professionName(p)
                if (name != key && name.startsWith(builder.remaining)) builder.suggest(name)
            }
            builder.buildFuture()
        }
    }
}

package com.inmc.essentials.item

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.verify.Check
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Villager
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.permissions.PermissionDefault
import org.bukkit.persistence.PersistentDataType

/**
 * 아이템(2026-10-10) — 지급·전원 지급·머리·몹 머리 · 장식 · 이름/설명 · 가상 작업대·거래창 · 셜커 배낭 · 비우기 · 염색 · 검색.
 * 카탈로그 cmd3:cmd-give · cmd3:cmd-giveall · cmd4:sys-mobhead(지급만) · cmd8:cmd-trim · cmd4:cmd-itemname-lore · mod:sys-workstations ·
 * cfg4:cfg-shulker-backpack · cmd1:inv-clear · cmd2:cmd-dye · cmd6:cmd-search. 운영 문서 `docs/items.md`.
 */
class ItemsFeature(ess: Ess) : Feature(ess) {

    override val id = "items"

    override val hasSettings = true

    override val permissions = listOf(
        Perm(GIVE, PermissionDefault.OP, "/지급 — 한 사람에게(오프라인은 우편)"),
        Perm(GIVE_ALL, PermissionDefault.OP, "/지급 * · ** · /전원지급"),
        Perm(HEAD, PermissionDefault.OP, "/머리 — 자기 것"),
        Perm(HEAD_OTHERS, PermissionDefault.OP, "/머리·/몹머리 <…> <받을사람>"),
        Perm(MOBHEAD, PermissionDefault.OP, "/몹머리"),
        Perm(TRIM, PermissionDefault.OP, "/장식 — 손에 든 갑옷"),
        Perm(TRIM_OTHERS, PermissionDefault.OP, "/장식 <플레이어>"),
        Perm(NAME, PermissionDefault.OP, "/이름"),
        Perm(NAME_OTHERS, PermissionDefault.OP, "/이름 -p:<플레이어>"),
        Perm(LORE, PermissionDefault.OP, "/설명"),
        Perm(LORE_OTHERS, PermissionDefault.OP, "/설명 -p:<플레이어>"),
        Perm(COLOR, PermissionDefault.OP, "/이름·/설명 에 색(MiniMessage·& 코드)"),
        Perm(RENAME_BYPASS, PermissionDefault.OP, "이름 제한(전체 금지·글자 수·금지 이름·설명 금지 재질)과 모루 제한 무시"),
        Perm(WORKBENCH, PermissionDefault.OP, "/작업대"),
        Perm(ANVIL, PermissionDefault.OP, "/모루"),
        Perm(GRINDSTONE, PermissionDefault.OP, "/숫돌"),
        Perm(LOOM, PermissionDefault.OP, "/베틀"),
        Perm(STONECUTTER, PermissionDefault.OP, "/석재절단기"),
        Perm(SMITHING, PermissionDefault.OP, "/대장장이"),
        Perm(CARTOGRAPHY, PermissionDefault.OP, "/지도제작대"),
        Perm(MERCHANT, PermissionDefault.OP, "/거래창"),
        Perm(WORKSTATION_OTHERS, PermissionDefault.OP, "가상 작업대·거래창을 남에게 열어 주기"),
        Perm(SHULKER, PermissionDefault.TRUE, "가방 안의 셜커 상자 열기(셜커 배낭)"),
        Perm(SHULKER_EDIT, PermissionDefault.TRUE, "셜커 배낭 내용 고치기(설정 require-edit-permission 일 때만 따짐)"),
        Perm(SHULKER_FREE, PermissionDefault.OP, "셜커 배낭 여는 비용 면제"),
        Perm(CLEAR, PermissionDefault.TRUE, "/비우기 — 자기 가방"),
        Perm(CLEAR_OTHERS, PermissionDefault.OP, "/비우기 <플레이어>"),
        Perm(DYE, PermissionDefault.TRUE, "/염색 — 자기 손의 가죽 갑옷(고정 색)"),
        Perm(DYE_DYNAMIC, PermissionDefault.OP, "/염색 의 움직이는 색(무지개·낮밤·바이옴·체력 …)"),
        Perm(DYE_OTHERS, PermissionDefault.OP, "/염색 <플레이어>"),
        Perm(SEARCH, PermissionDefault.OP, "/검색"),
        Perm(SEARCH_PURGE, PermissionDefault.OP, "/검색 지우기"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: ItemsSettings = ItemsSettings()
        private set

    internal lateinit var combat: Combat
    internal lateinit var give: ItemGive
    internal lateinit var giveCommands: GiveCommands
    internal lateinit var backpack: ShulkerBackpack
    internal lateinit var dye: Dye
    internal lateinit var edit: ItemEdit
    internal lateinit var stations: Workstations
    internal lateinit var search: Search
    internal lateinit var clear: Clear

    override fun load(settings: YamlConfiguration) {
        this.settings = ItemsSettings.from(settings)
    }

    override fun enable() {
        combat = Combat()
        give = ItemGive(ess)
        giveCommands = GiveCommands(ess, this)
        backpack = ShulkerBackpack(ess, this)
        dye = Dye(ess, this)
        edit = ItemEdit(ess, this)
        stations = Workstations(ess)
        search = Search(ess, this)
        ess.listen(combat)
        ess.listen(backpack)
        ess.listen(edit)
        giveCommands.register()
        edit.register()
        stations.register()
        clear = Clear(ess, this)
        clear.register()
        dye.register()
        search.register()
    }

    override fun disable() {
        if (::backpack.isInitialized) backpack.closeAll()
    }

    // --- 검증기 --------------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("지급 — 꼬리 읽기와 개수만큼 되풀이 만들기(칼 10개 = 10묶음)") { _ ->
            val spec = (GiveSpec.parse("diamond_sword n{<red>시험</red>} 10 -s") as? GiveSpec.Parsed.Ok)?.spec ?: return@Check "꼬리를 못 읽었습니다"
            val source = give.refSource("diamond_sword") ?: return@Check "diamond_sword 를 만들지 못했습니다"
            val stacks = give.build(listOf(source), spec)
            val dirt = give.refSource("dirt")?.build(130).orEmpty()
            when {
                stacks.size != 10 || stacks.any { it.amount != 1 } -> "칼 10개가 ${stacks.size}묶음으로 나왔습니다"
                stacks.first().itemMeta?.hasDisplayName() != true -> "n{} 이름이 붙지 않았습니다"
                dirt.sumOf { it.amount } != 130 || dirt.size != 3 -> "흙 130개가 ${dirt.sumOf { it.amount }}개(${dirt.size}묶음)로 나왔습니다"
                else -> null
            }
        },
        Check("전투 — 맞으면 전투 중(진짜 피해 사건, 피해 0)") { p ->
            val saved = combat.snapshot(p.uniqueId)
            try {
                combat.mark(p.uniqueId, null)
                val source = DamageSource.builder(DamageType.GENERIC).withDirectEntity(p).withCausingEntity(p).build()
                @Suppress("UnstableApiUsage")
                val event = EntityDamageByEntityEvent(p, p, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 0.0)
                ess.plugin.server.pluginManager.callEvent(event)
                when {
                    event.isCancelled -> null // 다른 플러그인(보호구역)이 막았다 — 우리 탓이 아니다
                    !combat.inCombat(p.uniqueId, settings.combatSeconds) -> "피해 사건 뒤에도 전투 중이 아닙니다 — Combat 리스너를 보세요"
                    else -> null
                }
            } finally {
                combat.restore(p.uniqueId, saved)
            }
        },
        Check("셜커 배낭 — 열고 넣고 닫으면 상자에 되써 넣고 표를 뗀다") { p ->
            val s = settings.shulker
            if (!s.enabled) return@Check null
            if (s.cost > 0.0 && !p.hasPermission(SHULKER_FREE)) return@Check null // 돈이 나가므로 건너뜀
            val slot = (9..35).firstOrNull { p.inventory.getItem(it).let { i -> i == null || i.type.isAir } } ?: return@Check null
            if (p.openInventory.topInventory.type != InventoryType.CRAFTING) return@Check null
            val saved = combat.snapshot(p.uniqueId)
            try {
                combat.mark(p.uniqueId, null)
                p.inventory.setItem(slot, ItemStack(Material.SHULKER_BOX))
                if (!backpack.open(p, slot)) return@Check "열지 못했습니다(막힘 — 채팅 안내를 보세요)"
                val top = p.openInventory.topInventory
                if (top.holder !is ShulkerBackpack.Session) return@Check "배낭 창이 열리지 않았습니다"
                top.setItem(0, ItemStack(Material.DIRT, 7))
                p.closeInventory()
                val item = p.inventory.getItem(slot) ?: return@Check "닫은 뒤 상자가 사라졌습니다"
                val box = (item.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)?.blockState as? org.bukkit.block.ShulkerBox
                when {
                    ShulkerBackpack.token(item) != null -> "닫은 뒤에도 열기 표가 남았습니다"
                    box?.inventory?.getItem(0)?.amount != 7 -> "넣은 흙 7개가 상자에 써지지 않았습니다"
                    backpack.openCount() != 0 -> "열린 배낭 기록이 남았습니다"
                    else -> null
                }
            } finally {
                p.inventory.setItem(slot, null)
                combat.restore(p.uniqueId, saved)
            }
        },
        Check("염색 — 움직이는 색은 입은 갑옷만, 진짜 아이템은 그대로 두고 보이는 것만 칠한다") { p ->
            val current = p.inventory.helmet
            if (current != null && !current.type.isAir) return@Check null // 투구를 쓰고 있으면 건드리지 않는다
            try {
                val helmet = ItemStack(Material.LEATHER_HELMET)
                helmet.editMeta(LeatherArmorMeta::class.java) {
                    it.setColor(Color.fromRGB(0x000001))
                    it.persistentDataContainer.set(Dye.KEY, PersistentDataType.STRING, "hunger:#00ff00")
                    it.persistentDataContainer.set(Dye.OWNER, PersistentDataType.STRING, p.uniqueId.toString())
                }
                p.inventory.setItem(EquipmentSlot.HEAD, helmet)
                val now = System.currentTimeMillis()
                dye.repaint(now, force = true)
                val expected = DyeColors.color(DyeColors.Spec.Dynamic(DyeColors.Mode.HUNGER, listOf(0x00FF00)), dye.context(p, 0, now))
                val got = dye.colors(p, now)[EquipmentSlot.HEAD]
                val real = (p.inventory.helmet?.itemMeta as? LeatherArmorMeta)?.color?.asRGB()
                when {
                    got != expected -> "칠할 색이 ${got?.let { DyeColors.hex(it) }} — 기대 ${DyeColors.hex(expected)}"
                    real != 0x000001 -> "진짜 투구의 색이 바뀌었습니다(${real?.let { DyeColors.hex(it) }}) — 갑옷 바뀜 사건이 매번 납니다"
                    else -> null
                }
            } finally {
                p.inventory.setItem(EquipmentSlot.HEAD, null)
            }
        },
        Check("이름 제한 — CMI 기본 규칙(spawner:…spawner)이 스포너를 다른 스포너 이름으로 바꾸는 것을 막는다") { _ ->
            val rule = RenameRule.parse("spawner:([A-z]+ (?i)\\w*spawner)") ?: return@Check "규칙을 읽지 못했습니다"
            val s = settings.naming.copy(prevent = true, globalDisable = false, nameMaxLength = 0, rules = listOf(rule))
            when {
                NamingRules.problem(s, Material.SPAWNER, null, "Zombie Spawner") != "blocked" -> "Zombie Spawner 를 막지 않았습니다"
                NamingRules.problem(s, Material.SPAWNER, null, "보물") != null -> "평범한 이름을 막았습니다"
                else -> null
            }
        },
        Check("가상 작업대 — 서버가 품은 API 로 작업대·모루 화면을 만들 수 있다(열지 않음)") { p ->
            try {
                val view = MenuType.CRAFTING.builder().location(p.location).checkReachable(false).build(p)
                val anvil = MenuType.ANVIL.builder().location(p.location).checkReachable(false).build(p)
                if (view.topInventory.type != InventoryType.WORKBENCH || anvil.topInventory.type != InventoryType.ANVIL) "화면 종류가 다릅니다" else null
            } catch (t: Throwable) {
                "MenuType 빌더 실패: $t (지뢰 25 — 서버 API 가 오래됐는지 보세요)"
            }
        },
        Check("거래창 — 소환하지 않은 주민으로 농부 거래 목록을 뽑는다") { p ->
            try {
                val villager = p.world.createEntity(p.location, Villager::class.java)
                villager.profession = Workstations.profession("farmer") ?: return@Check "farmer 직업을 못 찾았습니다"
                villager.villagerLevel = 1
                if (villager.recipes.isEmpty()) "거래 목록이 비었습니다" else null
            } catch (t: Throwable) {
                "주민 만들기 실패: $t"
            }
        },
        Check("검색 — 아이템을 NBT 로 풀었다 다시 묶어도 같은 아이템") { _ ->
            val original = ItemStack(Material.DIAMOND_SWORD).apply { editMeta { it.displayName(ItemGive.mini("<aqua>검색 시험</aqua>")) } }
            val root = Nbt.readCompressed(original.serializeAsBytes().inputStream())
            val again = ItemStack.deserializeBytes(Nbt.writeCompressed(root))
            if (!again.isSimilar(original)) "다시 읽은 아이템이 다릅니다" else null
        },
        Check("/비우기 — 재질:개수 는 그 개수만 지운다(진짜 가방, 끝나면 되돌림)") { p ->
            val slot = (9..35).firstOrNull { p.inventory.getItem(it).let { i -> i == null || i.type.isAir } } ?: return@Check null
            val saved = p.inventory.contents.map { it?.clone() }.toTypedArray()
            try {
                p.inventory.setItem(slot, ItemStack(Material.DIRT, 5))
                val before = p.inventory.all(Material.DIRT).values.sumOf { it.amount }
                val spec = (ClearSpec.parse(listOf("dirt:3")) as? ClearSpec.Parsed.Ok)?.spec ?: return@Check "dirt:3 을 읽지 못했습니다"
                val removed = clear.clear(p, spec, listOf(kr.inmc.core.item.ItemRef.parse("dirt") to 3))
                val after = p.inventory.all(Material.DIRT).values.sumOf { it.amount }
                when {
                    removed != 3 -> "지운 수가 $removed — 3이어야 합니다"
                    before - after != 3 -> "흙이 ${before - after}개 줄었습니다 — 3개여야 합니다"
                    else -> null
                }
            } finally {
                p.inventory.contents = saved
            }
        },
        Check("/검색 — 셜커 안까지 찾고, 지우기는 맞는 것만 지운다 · 아이템 조건 없는 지우기는 거절") { p ->
            val slot = (9..35).firstOrNull { p.inventory.getItem(it).let { i -> i == null || i.type.isAir } } ?: return@Check null
            val saved = p.inventory.contents.map { it?.clone() }.toTypedArray()
            try {
                val marked = ItemStack(Material.DIRT, 4).apply { editMeta { it.displayName(ItemGive.mini("ess-verify-search")) } }
                val box = ItemStack(Material.SHULKER_BOX).apply {
                    editMeta(org.bukkit.inventory.meta.BlockStateMeta::class.java) { meta ->
                        val state = meta.blockState as org.bukkit.block.ShulkerBox
                        state.inventory.setItem(0, marked)
                        state.inventory.setItem(1, ItemStack(Material.COBBLESTONE, 2))
                        meta.blockState = state
                    }
                }
                p.inventory.setItem(slot, box)
                val criteria = (SearchCriteria.parse("이름:ess-verify-search") as? SearchCriteria.Parsed.Ok)?.criteria ?: return@Check "조건을 읽지 못했습니다"
                val matcher = search.matcher(p, criteria) ?: return@Check "조건을 판정으로 바꾸지 못했습니다"
                val hits = ArrayList<Search.Hit>()
                search.collect(p.name, p.uniqueId, matcher, criteria, hits) { visit -> search.scanInventory(p.inventory, PlayerDataFile.WHERE_INVENTORY, visit) }
                val found = hits.filter { it.where.endsWith("셜커") }.sumOf { it.count }
                val removed = search.purgeInventory(p.inventory, matcher)
                val inner = ((p.inventory.getItem(slot)?.itemMeta as? org.bukkit.inventory.meta.BlockStateMeta)?.blockState as? org.bukkit.block.ShulkerBox)?.inventory
                val all = (SearchCriteria.parse("모드:creative 최소:5") as? SearchCriteria.Parsed.Ok)?.criteria
                when {
                    found != 4 -> "셜커 안의 표시한 흙을 ${found}개 찾았습니다 — 4개여야 합니다"
                    removed != 4 -> "지운 수가 $removed — 4여야 합니다"
                    inner == null -> "셜커가 사라졌습니다(안의 것만 지워야 합니다)"
                    inner.getItem(0) != null || inner.getItem(1)?.amount != 2 -> "셜커 안에서 맞지 않는 것까지 지웠거나 맞는 것을 남겼습니다"
                    all == null || all.selectsItems -> "아이템 조건 없는 조건(모드·최소)을 지우기가 받습니다 — 모두 지워집니다"
                    else -> null
                }
            } finally {
                p.inventory.contents = saved
            }
        },
        Check("/이름 — 손의 아이템 이름을 바꾼다") { p ->
            if (!p.inventory.itemInMainHand.type.isAir || settings.naming.globalDisable) return@Check null
            try {
                p.inventory.setItemInMainHand(ItemStack(Material.PAPER))
                edit.rename(p, "ess시험")
                val name = p.inventory.itemInMainHand.itemMeta?.displayName()?.let { ItemEdit.PLAIN.serialize(it) }
                if (name != "ess시험") "이름이 '$name' — 'ess시험' 이어야 합니다" else null
            } finally {
                p.inventory.setItemInMainHand(null)
            }
        },
        Check("셜커 배낭 — 연 채로 서버가 꺼져 남은 표는 뗀다") { p ->
            val slot = (9..35).firstOrNull { p.inventory.getItem(it).let { i -> i == null || i.type.isAir } } ?: return@Check null
            if (backpack.openCount() > 0) return@Check null
            try {
                val stale = ItemStack(Material.SHULKER_BOX).apply { editMeta { it.persistentDataContainer.set(ShulkerBackpack.KEY, PersistentDataType.STRING, "stale") } }
                p.inventory.setItem(slot, stale)
                val n = backpack.stripStale(p)
                if (n < 1 || ShulkerBackpack.token(p.inventory.getItem(slot)) != null) "남은 열기 표를 떼지 않았습니다" else null
            } finally {
                p.inventory.setItem(slot, null)
            }
        },
        Check("몹 머리 — 설정의 텍스처가 읽힌다") { _ ->
            if (settings.mobHeads.isEmpty()) "settings/items.yml 의 mob-heads 가 비었습니다" else null
        },
    )

    companion object {
        const val GIVE = "inmcessentials.items.give"
        const val GIVE_ALL = "inmcessentials.items.giveall"
        const val HEAD = "inmcessentials.items.head"
        const val HEAD_OTHERS = "inmcessentials.items.head.others"
        const val MOBHEAD = "inmcessentials.items.mobhead"
        const val TRIM = "inmcessentials.items.trim"
        const val TRIM_OTHERS = "inmcessentials.items.trim.others"
        const val NAME = "inmcessentials.items.itemname"
        const val NAME_OTHERS = "inmcessentials.items.itemname.others"
        const val LORE = "inmcessentials.items.itemlore"
        const val LORE_OTHERS = "inmcessentials.items.itemlore.others"
        const val COLOR = "inmcessentials.items.itemname.color"
        const val RENAME_BYPASS = "inmcessentials.items.rename.bypass"
        const val WORKBENCH = "inmcessentials.items.workbench"
        const val ANVIL = "inmcessentials.items.anvil"
        const val GRINDSTONE = "inmcessentials.items.grindstone"
        const val LOOM = "inmcessentials.items.loom"
        const val STONECUTTER = "inmcessentials.items.stonecutter"
        const val SMITHING = "inmcessentials.items.smithingtable"
        const val CARTOGRAPHY = "inmcessentials.items.cartographytable"
        const val MERCHANT = "inmcessentials.items.merchant"
        const val WORKSTATION_OTHERS = "inmcessentials.items.workstation.others"
        const val SHULKER = "inmcessentials.items.shulker"
        const val SHULKER_EDIT = "inmcessentials.items.shulker.edit"
        const val SHULKER_FREE = "inmcessentials.items.shulker.free"
        const val CLEAR = "inmcessentials.items.clear"
        const val CLEAR_OTHERS = "inmcessentials.items.clear.others"
        const val DYE = "inmcessentials.items.dye"
        const val DYE_DYNAMIC = "inmcessentials.items.dye.dynamic"
        const val DYE_OTHERS = "inmcessentials.items.dye.others"
        const val SEARCH = "inmcessentials.items.search"
        const val SEARCH_PURGE = "inmcessentials.items.search.purge"

        val MESSAGES: Map<String, String> = linkedMapOf(
            // 지급
            "items-give-usage" to "<yellow>/지급 [대상] [아이템…] [개수]</yellow> <gray>— 대상: 이름 · * (접속자) · ** (최근 접속자, 오프라인은 우편). 아이템: diamond · inmc:[id] · mmoitems:[종류]:[id] · hand · hand:[사람] · inv:[사람]. 꼬리: n{이름} l{줄|줄} e{sharpness:5} unbreakable -s -slot:[칸] -d:[일]</gray>",
            "items-giveall-usage" to "<yellow>/전원지급 [아이템…] [개수] [offline]</yellow> <gray>— 접속자 전원(offline 을 붙이면 최근 접속자 전원, 오프라인은 우편)</gray>",
            "items-spec-unclosed" to "<red>괄호 {…} 가 닫히지 않았습니다.</red>",
            "items-spec-slot" to "<red>칸 번호가 이상합니다: {값}</red> <gray>(-slot:0~40)</gray>",
            "items-spec-days" to "<red>기간이 이상합니다: {값}</red> <gray>(-d:1~3650)</gray>",
            "items-spec-enchant" to "<red>인챈트를 읽지 못했습니다: {값}</red> <gray>(e{sharpness:5,unbreaking:3})</gray>",
            "items-spec-amount" to "<red>개수가 이상합니다: {값}</red> <gray>(아이템 뒤에 숫자 하나)</gray>",
            "items-spec-flag" to "<red>모르는 옵션입니다: {값}</red>",
            "items-spec-no-item" to "<red>줄 아이템을 적으세요.</red>",
            "items-amount-too-large" to "<red>한 번에 {개수}개까지 줄 수 있습니다.</red>",
            "items-unknown-item" to "<red>만들 수 없는 아이템입니다: {값}</red> <gray>(재질 · inmc:[id] · mmoitems:[종류]:[id] · 네임스페이스:[id])</gray>",
            "items-unknown-enchant" to "<red>모르는 인챈트입니다: {값}</red>",
            "items-make-only" to "<red>{값} 은(는) 지급만 되는 참조라 찾거나 지울 수 없습니다.</red> <gray>(물고기·부여서·상점 블록은 재질이나 hand 로)</gray>",
            "items-hand-empty" to "<red>{값}님의 손이 비었습니다.</red>",
            "items-inv-empty" to "<red>{값}님의 가방이 비었습니다.</red>",
            "items-received" to "<green>{플레이어}님에게서 <white>{아이템}</white> 을(를) 받았습니다.</green>",
            "items-given" to "<green>{플레이어}님에게 <white>{아이템}</white> 을(를) 줬습니다.</green> <gray>(넘쳐서 우편·바닥 {개수}묶음)</gray>",
            "items-given-mail" to "<green>{플레이어}님은 접속 중이 아니라 <white>{아이템}</white> 을(를) 우편으로 보냈습니다.</green>",
            "items-offline-no-mail" to "<red>{플레이어}님은 접속 중이 아니고 우편 기능이 꺼져 있어 줄 수 없습니다.</red>",
            "items-mail-failed" to "<red>{플레이어}님에게 우편을 넣지 못했습니다 — 서버 로그를 보세요.</red>",
            "items-everyone-no-mail" to "<yellow>우편 기능이 꺼져 있어 접속하지 않은 사람에게는 주지 않습니다.</yellow>",
            "items-nobody" to "<gray>받을 사람이 없습니다.</gray>",
            "items-give-start" to "<gray>{개수}명에게 <white>{아이템}</white> 을(를) 나눠 줍니다…</gray>",
            "items-give-progress" to "<gray>… {개수}/{금액}명</gray>",
            "items-give-done" to "<green>지급 끝 — 바로 {개수}명 · 우편 {금액}명</green> <gray>(실패 {값}명) {아이템}</gray>",
            "items-overflow-mailed" to "<yellow>가방이 가득 차서 {개수}묶음을 우편으로 보냈습니다.</yellow> <gray>/우편</gray>",
            "items-overflow-dropped" to "<yellow>가방이 가득 차서 {개수}묶음을 발밑에 떨어뜨렸습니다.</yellow>",
            // 머리
            "items-head-invalid" to "<red>사람 이름도 텍스처도 아닙니다: {값}</red>",
            "items-head-loading" to "<gray>{값} 의 스킨을 불러옵니다…</gray>",
            "items-head-given" to "<green>{플레이어}님에게 <white>{아이템}</white> {개수}개를 줬습니다.</green>",
            "items-mobhead-usage" to "<yellow>/몹머리 [몹] [받을사람] [개수]</yellow> <gray>— 몹: zombie · 좀비 …</gray>",
            "items-mobhead-unknown" to "<red>머리가 등록되지 않은 몹입니다: {값}</red> <gray>(settings/items.yml mob-heads)</gray>",
            // 이름 · 설명 · 장식
            "items-name-usage" to "<yellow>/이름 [새 이름]</yellow> <gray>· /이름 지우기 · /이름 -p:[플레이어] [새 이름]</gray>",
            "items-lore-usage" to "<yellow>/설명 [글]</yellow> <gray>(덧붙이기) · /설명 [줄] [글] · /설명 [줄] 지우기 · /설명 [줄] 끼우기 [글] · /설명 * [줄|줄] · /설명 지우기 · -p:[플레이어]</gray>",
            "items-naming-disabled" to "<red>이 서버에서는 아이템 이름을 바꿀 수 없습니다.</red>",
            "items-naming-too-long" to "<red>이름은 {개수}자까지입니다(색 빼고).</red>",
            "items-naming-blocked" to "<red>쓸 수 없는 이름입니다.</red>",
            "items-naming-source-blocked" to "<red>이 아이템은 이름을 바꿀 수 없습니다.</red>",
            "items-name-set" to "<green>{플레이어}님 손의 아이템 이름을 <white>{값}</white> (으)로 바꿨습니다.</green>",
            "items-name-removed" to "<green>{플레이어}님 손의 아이템 이름을 지웠습니다.</green>",
            "items-lore-blacklisted" to "<red>{아이템} 에는 설명을 달 수 없습니다.</red>",
            "items-lore-no-line" to "<red>그 줄이 없습니다(지금 {개수}줄).</red>",
            "items-lore-too-long" to "<red>설명 한 줄은 {개수}자까지입니다(색 빼고).</red>",
            "items-lore-set" to "<green>{플레이어}님 손의 아이템 설명을 바꿨습니다.</green> <gray>({개수}줄)</gray>",
            "items-custom-redraw-warning" to "<yellow>커스텀 아이템입니다 — 정의를 고쳐 다시 그려지면 이름·설명·색이 정의대로 돌아갈 수 있습니다.</yellow>",
            "items-trim-not-armor" to "<red>장식을 달 수 있는 갑옷을 손에 드세요.</red>",
            "items-trim-changed" to "<red>손에 든 아이템이 바뀌어 장식을 달지 않았습니다.</red>",
            "items-trim-set" to "<green>{플레이어}님 손의 갑옷에 장식 <white>{값}</white> 을(를) 달았습니다.</green>",
            "items-trim-cleared" to "<green>{플레이어}님 손의 갑옷 장식을 지웠습니다.</green>",
            // 작업대 · 거래창
            "items-station-failed" to "<red>그 화면을 열지 못했습니다 — 서버 로그를 보세요.</red>",
            "items-station-opened" to "<green>{플레이어}님에게 <white>{값}</white> 을(를) 열어 줬습니다.</green>",
            "items-station-opened-by" to "<gray>{플레이어}님이 <white>{값}</white> 을(를) 열어 줬습니다.</gray>",
            "items-merchant-usage" to "<yellow>/거래창 [직업] [레벨 1~5]</yellow> <gray>· /거래창 [직업] [플레이어] [레벨] — 직업: farmer · 농부 · librarian · 사서 …</gray>",
            "items-merchant-unknown" to "<red>모르는 직업입니다: {값}</red>",
            "items-merchant-empty" to "<red>{값} 의 거래 목록을 만들지 못했습니다.</red>",
            // 셜커 배낭
            "items-shulker-combat" to "<red>전투 중에는 셜커 상자를 열 수 없습니다.</red>",
            "items-shulker-moving" to "<red>달리거나 날거나 탄 채로는 셜커 상자를 열 수 없습니다.</red>",
            "items-shulker-no-money" to "<red>셜커 상자를 열려면 {금액}이(가) 필요합니다.</red>",
            "items-shulker-readonly" to "<gray>보기만 할 수 있습니다(고칠 권한이 없음).</gray>",
            "items-shulker-moved" to "<yellow>움직여서 셜커 상자를 닫았습니다.</yellow>",
            // 비우기
            "items-clear-who" to "<red>콘솔에서는 비울 사람을 적어야 합니다: /비우기 [플레이어] [옵션]</red>",
            "items-clear-bad" to "<red>모르는 옵션입니다: {값}</red> <gray>(+핫바 +가방 +윗칸 +갑옷칸 +손 +왼손 +무기 +도구 +갑옷 · 재질[:개수] · -exact · -s)</gray>",
            "items-clear-confirm" to "<yellow>{플레이어}님의 가방을 전부 비웁니다.</yellow> <click:run_command:'/비우기 확인'><hover:show_text:'<red>눌러서 비우기 — 되돌릴 수 없습니다</red>'><red>[확인]</red></hover></click> <gray>(30초)</gray>",
            "items-clear-no-pending" to "<gray>확인할 것이 없습니다(30초가 지났거나 이미 했습니다).</gray>",
            "items-clear-done" to "<green>{플레이어}님의 가방에서 아이템 {개수}개를 지웠습니다.</green>",
            "items-clear-target" to "<yellow>{플레이어}님이 가방에서 아이템 {개수}개를 지웠습니다.</yellow>",
            // 염색
            "items-dye-not-dyeable" to "<red>염색할 수 있는 가죽 갑옷을 손에 드세요.</red>",
            "items-dye-current" to "<gray>지금 색: <white>{값}</white> <white>{색}</white> · 움직이는 색: <white>{모드}</white></gray>",
            "items-dye-unknown" to "<red>색을 읽지 못했습니다: {값}</red> <gray>(r,g,b · #hex · 색 이름 · random · 지우기 · 무지개 · 낮밤 · 바이옴 · 체력 · 숨쉬기 · 팔레트 · 물결 · 피격 · 전투 · 배고픔 · 산소 · 디스코)</gray>",
            "items-dye-custom-refused" to "<red>정의에 색이 있는 커스텀 아이템이라 움직이는 색을 달 수 없습니다(다시 그릴 때 정의 색과 다툽니다).</red>",
            "items-dye-custom-warning" to "<yellow>정의에 색이 있는 커스텀 아이템입니다 — 다시 그려지면 정의 색으로 돌아갑니다.</yellow>",
            "items-dye-set" to "<green>{플레이어}님 손의 갑옷을 <white>{값}</white> (으)로 염색했습니다.</green>",
            "items-dye-set-by" to "<gray>{플레이어}님이 손의 갑옷을 <white>{값}</white> (으)로 염색했습니다.</gray>",
            // 검색
            "items-search-usage" to "<yellow>/검색 [조건…]</yellow> <gray>— 아이템(diamond · inmc:[id]) · hand · oversize · 이름:[글] · 설명:[글] · 인챈트:[id][:레벨] · 최소:[개수] · 모드:survival. /검색 결과 [쪽] · /검색 취소 · /검색 지우기 [조건]</gray>",
            "items-search-bad" to "<red>조건을 읽지 못했습니다: {값}</red>",
            "items-search-busy" to "<red>다른 검색이 돌고 있습니다.</red> <gray>/검색 취소</gray>",
            "items-search-started" to "<gray>{개수}명을 찾아봅니다 — 접속하지 않은 사람은 파일을 읽어 시간이 걸립니다.</gray>",
            "items-search-progress" to "<gray>… {개수}/{금액}명 · 찾음 {값}</gray>",
            "items-search-cancelling" to "<gray>검색을 멈춥니다…</gray>",
            "items-search-cancelled" to "<yellow>검색을 멈췄습니다({개수}명까지 봄).</yellow>",
            "items-search-none-running" to "<gray>돌고 있는 검색이 없습니다.</gray>",
            "items-search-done" to "<green>검색 끝 — {개수}곳에서 찾음</green> <gray>(본 사람 {금액}명 · 못 읽은 파일 {값}개)</gray>",
            "items-search-no-result" to "<gray>검색 결과가 없습니다. 먼저 /검색 [조건]</gray>",
            "items-search-empty" to "<gray>찾은 것이 없습니다.</gray>",
            "items-search-header" to "<gold>검색 결과 {개수}곳</gold> <gray>({쪽})</gray>",
            "items-search-entry" to "<gray>{순위}.</gray> <white>{플레이어}</white> <gray>{값}</gray> <yellow>{개수}개</yellow>",
            "items-search-next" to "<click:run_command:'/검색 결과 {다음}'><gray>[다음 쪽 ▶]</gray></click>",
            "items-search-purge-disabled" to "<red>검색 지우기는 꺼져 있습니다(settings/items.yml search.enable-purge).</red>",
            "items-search-purge-confirm" to "<yellow>접속 중인 사람에게서 '{값}' 에 맞는 아이템을 전부 지웁니다.</yellow> <click:run_command:'/검색 지우기 확인'><hover:show_text:'<red>되돌릴 수 없습니다</red>'><red>[확인]</red></hover></click> <gray>(30초 · 접속하지 않은 사람의 파일은 고치지 않습니다)</gray>",
            "items-search-purged" to "<green>{금액}명에게서 아이템 {개수}개를 지웠습니다.</green>",
            "items-search-purge-no-item" to "<red>지울 아이템을 고르는 조건(아이템 · hand · oversize · 이름: · 설명: · 인챈트:)이 하나는 있어야 합니다</red> <gray>— 최소:·모드: 는 사람만 고릅니다</gray>",
        )
    }
}

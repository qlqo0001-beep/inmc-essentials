package com.inmc.essentials.kit

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.service.KitsApi
import com.inmc.essentials.verify.Check
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import kr.inmc.core.reward.GiveMode
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kr.inmc.core.store.SqlSource
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.Bukkit
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault
import java.io.File

/**
 * 킷(2026-10-10) — 카탈로그 cfg4:cmd-kit · fix:cfg-kit-groups-conditions · cmd4:cmd-kiteditor. 운영 문서 `docs/kits.md`.
 * 첫 접속 킷은 `ess.kits`([KitsApi]) — spawn 기능이 첫 스폰 뒤에 부른다(spawn 이 꺼져 있으면 여기서 접속 사건으로 대신).
 */
class KitsFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "kits"

    override val hasSettings = true

    override val permissions = listOf(
        Perm(USE, PermissionDefault.TRUE, "/킷 — 목록·받기(킷마다 권한이 따로 필요할 수 있음)"),
        Perm(ALL_KITS, PermissionDefault.OP, "모든 킷 받기(킷 하나는 inmcessentials.kits.kit.<id>)"),
        Perm(PREVIEW_ALL, PermissionDefault.OP, "모든 킷 미리보기(킷 하나는 inmcessentials.kits.kit.<id>.preview)"),
        Perm(OTHERS, PermissionDefault.OP, "/킷 <이름> <대상> — 남에게 주기(검사·비용 없이)"),
        Perm(EDIT, PermissionDefault.OP, "/킷편집"),
        Perm(RESET, PermissionDefault.OP, "/킷초기화"),
        Perm(BYPASS_COOLDOWN, PermissionDefault.OP, "킷 쿨타임 무시"),
        Perm(BYPASS_USES, PermissionDefault.OP, "킷 횟수 제한(1회용 포함) 무시"),
        Perm(BYPASS_MONEY, PermissionDefault.OP, "킷 돈 비용 면제"),
        Perm(BYPASS_EXP, PermissionDefault.OP, "킷 경험치 비용 면제"),
        Perm(BYPASS_CONDITIONS, PermissionDefault.OP, "킷 조건 권한 무시"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: KitsSettings = KitsSettings()
        private set

    internal lateinit var store: KitStore
    internal lateinit var service: KitService
    internal lateinit var commands: KitCommands

    private var api: KitsApi? = null

    override fun schema(sql: SqlSource) {
        KitStore(ess).schema(sql)
    }

    override fun load(settings: YamlConfiguration) {
        this.settings = KitsSettings.from(settings)
    }

    override fun enable() {
        store = KitStore(ess)
        store.loadAll(Bukkit.getOnlinePlayers().map { it.uniqueId })
        service = KitService(ess, this)
        commands = KitCommands(ess, this)
        commands.register()
        ess.listen(object : Listener {
            /** 그 사람의 사용 기록을 들어오기 직전에 읽는다(비동기 사건이라 기다려도 된다 — 공용 DB 면 다른 서버의 기록도). */
            @EventHandler(priority = EventPriority.MONITOR)
            fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
                if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
                runCatching { store.reload(event.uniqueId) }.onFailure { ess.logger.warning("킷 기록을 읽지 못했습니다(${event.name}) — 접속 뒤 다시 읽습니다: $it") }
            }

            /** 들어올 때 읽지 못했으면(DB 오류) 다시 — 읽기 전에는 킷을 주지 않는다([KitService.verdict] 의 BUSY). */
            @EventHandler(priority = EventPriority.MONITOR)
            fun onJoin(event: PlayerJoinEvent) {
                val id = event.player.uniqueId
                if (!store.isLoaded(id)) store.loadLater(id)
            }

            /** 나가면 내린다 — 받는 중(DB 쓰기를 기다리는)이면 끝난 뒤에([KitService]). */
            @EventHandler(priority = EventPriority.MONITOR)
            fun onQuit(event: PlayerQuitEvent) {
                val id = event.player.uniqueId
                if (!service.busy(id)) store.unload(id)
            }
        })
        val mine = KitsApi { player -> service.giveFirstJoin(player) }
        ess.kits = mine
        api = mine
    }

    override fun ready() {
        // spawn 기능이 첫 스폰 뒤에 부른다. 꺼져 있으면 아무도 부르지 않으니 접속 사건으로 대신한다.
        if (ess.enabled("spawn")) return
        ess.listen(object : Listener {
            @EventHandler(priority = EventPriority.MONITOR)
            fun onJoin(event: PlayerJoinEvent) {
                val player = event.player
                // ess_users 에 줄이 없으면 isFirstJoin 이 참이다 — 이 플러그인을 처음 넣은 날 기존 플레이어가 모두 '처음' 이 되지 않게
                // 바닐라 판정(플레이어 파일이 있었나)도 함께 본다(spawn 기능의 판정과 같다).
                if (!ess.users.isFirstJoin(player) || player.hasPlayedBefore()) return
                player.scheduler.runDelayed(ess.plugin, { _ -> ess.kits?.giveFirstJoin(player) }, null, 20L)
            }
        })
    }

    override fun disable() {
        if (api != null && ess.kits === api) ess.kits = null
        api = null
    }

    // --- CMI 가져오기 --------------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::store.isInitialized) return report.skipped(KIND_DEF, "킷 기능이 켜지지 않았습니다")
        val imported = ArrayList<Pair<String, Kit>>()
        importDefinitions(data.kits, report, "Kits.yml", imported)
        // 킷 편집기는 Kits 폴더의 다른 파일에도 킷을 둔다(FileName) — 가져오기 자료는 Kits.yml 만 실으므로 나머지는 여기서 직접 읽는다.
        File(data.folder, "Kits").listFiles { f -> f.isFile && f.name.endsWith(".yml") && !f.name.equals("Kits.yml", ignoreCase = true) }
            ?.sortedBy { it.name }?.forEach { file ->
                val yaml = runCatching { YamlConfiguration.loadConfiguration(file) }.getOrNull()
                if (yaml == null) report.unreadable(KIND_DEF, "Kits/${file.name} — YAML 로 읽지 못했습니다") else importDefinitions(yaml, report, "Kits/${file.name}", imported)
            }
        firstJoinKit(data, report, imported)
        if (imported.isNotEmpty()) {
            report.note("킷 권한이 바뀝니다 — " + imported.joinToString(", ") { (name, kit) -> "cmi.kit.$name → ${kit.permission}" } +
                " (미리보기는 끝에 .preview). LuckPerms 등에서 옮겨 주세요")
            val cmiConditions = imported.flatMap { (_, kit) -> kit.conditions }.filter { it.startsWith("cmi.", ignoreCase = true) }.distinct()
            if (cmiConditions.isNotEmpty()) report.note("킷 조건에 CMI 권한이 남았습니다: ${cmiConditions.joinToString(", ")} — CMI 를 빼면 이 권한의 기본값이 사라져(op 만) 일반 사람이 못 받습니다. /킷편집 에서 지우거나 바꾸세요")
        }
        // 사용 기록(users.Kits — 이름 → 마지막으로 받은 때). 같은 id 의 킷이 있을 때만.
        for (user in data.users) {
            for ((name, at) in user.kits) {
                report.read(KIND_USE)
                val kit = commands.find(name) ?: store.get(Kit.sanitize(name) ?: "") ?: run {
                    report.skipped(KIND_USE, "${user.name} 의 '$name' — 같은 id 의 킷이 없습니다")
                    null
                } ?: continue
                // 접속한 사람은 메모리에서 견주고, 아니면 DB 가 더 새것을 남기며 합친다(mergeUsage) — 기록은 접속자 것만 메모리에 있다.
                val existing = store.usage(user.id, kit.id)
                if (existing != null && existing.lastUsed >= at) { report.skipped(KIND_USE, "${user.name} 의 '$name' — 이미 더 새 기록이 있습니다"); continue }
                val value = KitUsage(at, maxOf(existing?.uses ?: 0, 1))
                if (store.isLoaded(user.id)) store.markMemory(user.id, kit.id, value)
                ess.db.run("킷 기록 가져오기") { store.mergeUsage(user.id, kit.id, value) }
                report.imported(KIND_USE)
            }
        }
        report.note("킷 사용 기록: CMI 는 받은 횟수(u)도 적지만 가져오기 자료(CmiUser.kits)에는 마지막 시각만 있어 횟수는 최소 1로 둡니다")
    }

    /** CMI config.yml `Newbie.Kit` → 그 킷의 첫 접속 킷 표. */
    private fun firstJoinKit(data: CmiData, report: ImportReport, imported: List<Pair<String, Kit>>) {
        val config = File(data.folder, "config.yml").takeIf { it.isFile } ?: return
        val name = runCatching { YamlConfiguration.loadConfiguration(config).getString("Newbie.Kit") }.getOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return
        val kit = imported.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        if (kit == null) {
            val existing = store.get(Kit.sanitize(name) ?: "")
            return report.note("첫 접속 킷: CMI Newbie.Kit = $name — 이번에 가져온 킷이 아니라 정하지 않았습니다" +
                if (existing != null) "(같은 id 의 킷 ${existing.id} 이 이미 있으니 /킷편집 에서 '첫 접속 킷' 을 켜세요)" else "(CMI 예제 킷이라 건너뛰었거나 없음)")
        }
        kit.firstJoin = true
        store.save(kit)
        report.note("첫 접속 킷: CMI Newbie.Kit = $name → 킷 ${kit.id} 을(를) 첫 접속 킷으로 정했습니다")
    }

    private fun importDefinitions(yaml: YamlConfiguration?, report: ImportReport, file: String, imported: MutableList<Pair<String, Kit>>) {
        if (yaml == null) return report.note("킷 정의: CMI Kits.yml 이 없거나 jar 기본값과 같아 가져오지 않습니다")
        val names = yaml.getKeys(false)
        if (file == "Kits.yml" && KitRules.isCmiDefault(yaml)) {
            for (name in names) report.skipped(KIND_DEF, "$name — CMI 가 처음 깔아 주는 예제(Newbie·Advanced·Ad1~3)와 내용까지 같습니다")
            return
        }
        for (name in names) {
            report.read(KIND_DEF)
            val s = yaml.getConfigurationSection(name) ?: run { report.unreadable(KIND_DEF, "$name — 섹션이 아닙니다"); null } ?: continue
            val id = Kit.sanitize(name) ?: run { report.unreadable(KIND_DEF, "$name — id 로 바꿀 수 없는 이름(영문·숫자 없음)"); null } ?: continue
            if (store.get(id) != null) { report.skipped(KIND_DEF, "$name ($file) — 같은 id($id)의 킷이 이미 있습니다"); continue }
            val kit = Kit(id, name = s.getString("DisplayName")?.takeIf { it.isNotBlank() }?.let { Text.legacyToMiniMessage(it) } ?: name)
            kit.cooldown = s.getLong("Delay", 0L).coerceAtLeast(0L)
            kit.uses = s.getInt("MaxUsages", 0).coerceAtLeast(0)
            kit.showDespiteWeight = s.getBoolean("ShowDespiteWeight", false)
            readIcon(s)?.let { kit.icon = ess.itemResolver.capture(it) } ?: s.get("Icon")?.let { report.note("$name: 아이콘 '${it.toString().take(80)}' 을(를) 읽지 못해 상자로 둡니다") }
            val slot = s.getIntegerList("Slots").firstOrNull() ?: s.getInt("Slot", -1)
            val page = s.getInt("Page", 1)
            if (slot >= 0) {
                if (page <= 1 && slot in 0 until 45) kit.slot = slot
                else report.note("$name: 목록 자리(${page}쪽 ${slot}칸)는 1쪽 0~44칸만 고정할 수 있어 차례대로 둡니다")
            }
            if (!s.getBoolean("Enabled", true)) {
                kit.hidden = true
                report.note("$name: CMI 에서 꺼진 킷(Enabled: false) — 목록에서 숨겨 가져왔습니다(권한이 있으면 /킷 ${kit.id} 로는 받힘)")
            }
            if (s.contains("IconOff")) report.note("$name: IconOff(못 받을 때 아이콘)는 없습니다 — 잠긴 킷은 같은 아이콘에 잠김 줄이 붙습니다")
            if (s.getBoolean("DropOnGround", false)) report.note("$name: DropOnGround — 가방이 차면 바닥 대신 우편으로 갑니다(규칙)")
            kit.money = s.getDouble("MoneyCost", 0.0).coerceAtLeast(0.0)
            kit.exp = s.getInt("ExpCost", 0).coerceAtLeast(0)
            kit.conditions = s.getStringList("Conditions").toMutableList()
            kit.description = s.getStringList("Description").map { Text.legacyToMiniMessage(it) }.toMutableList()
            val commandName = s.getString("CommandName")?.trim()?.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
            val cmiGroup = s.getString("Group")?.trim().orEmpty()
            // CMI Group+Weight = '받을 수 있는 최고 무게만' — 우리 그룹이 바로 그 뜻. CommandName 은 같은 명령 이름으로 묶는 것이라
            // Group 이 있으면 Group 을 쓰고, CommandName 만 있으면 그것을 그룹으로(/킷 <그룹> 이 그 명령 이름). 버린 값은 보고서에.
            kit.group = KitRules.importGroup(cmiGroup, commandName)
            if (cmiGroup.isNotEmpty() && commandName != null && !commandName.equals(cmiGroup, ignoreCase = true)) {
                report.note("$name: CMI Group=$cmiGroup 를 그룹으로 썼고 CommandName=$commandName 는 옮기지 않았습니다 — /킷 $commandName 대신 /킷 $cmiGroup 또는 /킷 ${kit.id}")
            }
            kit.weight = s.getInt("Weight", 1).coerceAtLeast(1)
            val pool = RewardBundle(GiveMode.ALL)
            s.getConfigurationSection("Items")?.let { items ->
                val armor = items.getKeys(false).filter { it in ARMOR_KEYS }
                if (armor.isNotEmpty()) report.note("$name: 장비 칸(${armor.joinToString(", ")}) 아이템은 입히지 않고 가방에 넣습니다")
                for (key in items.getKeys(false)) {
                    val stack = readItem(items, key)
                    if (stack == null) { report.unreadable(KIND_DEF, "$name 의 아이템 $key — ItemStack 으로 읽지 못했습니다: ${items.get(key)?.toString()?.take(120)}"); continue }
                    pool.entries += RewardEntry(item = ess.itemResolver.capture(stack), chance = 100.0, minAmount = stack.amount, maxAmount = stack.amount)
                }
            }
            val cmds = s.getStringList("Commands").map { KitRules.cmiCommand(it) }.filter { it.isNotEmpty() }
            if (cmds.isNotEmpty()) {
                pool.entries += RewardEntry(item = StoredItem(ItemRef.Vanilla(Material.COMMAND_BLOCK), Material.COMMAND_BLOCK), commands = cmds.toMutableList(), giveItem = false)
                cmds.filter { it.contains("!") }.forEach { report.note("$name 의 명령 '$it' 에 CMI 전용 꼴(…!)이 남았습니다 — /킷편집 에서 고치세요") }
            }
            kit.pools[0] = pool
            val unknown = s.getKeys(false) - HANDLED
            if (unknown.isNotEmpty()) report.note("$name: 옮기지 않은 CMI 키 ${unknown.joinToString(", ")} — /킷편집 에서 맞추세요")
            store.save(kit)
            imported += name to kit
            report.imported(KIND_DEF)
        }
    }

    /** CMI 아이콘 — ItemStack 섹션 또는 `재질[;…]`·`재질:…` 글. */
    private fun readIcon(s: ConfigurationSection): ItemStack? {
        if (!s.contains("Icon")) return null
        s.getItemStack("Icon")?.let { return it }
        s.getConfigurationSection("Icon")?.let { sub -> return runCatching { ItemStack.deserialize(sub.getValues(false)) }.getOrNull()?.takeIf { !it.type.isAir } }
        val raw = s.getString("Icon")?.trim().orEmpty()
        val material = Material.matchMaterial(raw.substringBefore(';').substringBefore(':').substringBefore(' ').trim()) ?: return null
        return if (material.isItem && !material.isAir) ItemStack(material) else null
    }

    private fun readItem(section: ConfigurationSection, key: String): ItemStack? {
        section.getItemStack(key)?.let { return it }
        val sub = section.getConfigurationSection(key) ?: return null
        return runCatching { ItemStack.deserialize(sub.getValues(false)) }.getOrNull()?.takeIf { !it.type.isAir }
    }

    // --- 검증기 --------------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("첫 접속 킷 창구가 걸려 있다") { _ -> if (ess.kits == null || ess.kits !== api) "ess.kits 가 비었거나 다른 것입니다" else null },
        Check("킷 정의 — YAML 로 쓰고 되읽어도 같다") { _ ->
            val kit = Kit("ess-verify", name = "<red>시험</red>", cooldown = 90, uses = 1, conditions = mutableListOf("a.b"), money = 5.0, exp = 7, group = "g", weight = 3)
            kit.pools[0].entries += RewardEntry(item = StoredItem(ItemRef.Vanilla(Material.DIRT), Material.DIRT), minAmount = 2, maxAmount = 2)
            kit.pools += RewardBundle(GiveMode.ROLL_N, 2)
            val again = Kit.fromYaml(kit.id, kit.toYaml())
            when {
                again.name != kit.name || again.cooldown != 90L || again.uses != 1 || again.exp != 7 || again.weight != 3 || again.group != "g" -> "설정 값이 달라졌습니다"
                again.pools.size != 2 || again.pools[1].mode != GiveMode.ROLL_N || again.pools[1].rollCount != 2 -> "묶음이 달라졌습니다"
                again.pools[0].entries.singleOrNull()?.minAmount != 2 -> "보상이 달라졌습니다"
                else -> null
            }
        },
        Check("킷 받기 검사 — 쿨타임·1회용·변수 채우기(메모리만)") { p ->
            val kit = Kit("ess-verify", name = "시험 킷", requirePermission = false, cooldown = 3600, uses = 1)
            val named = ItemStack(Material.DIRT).apply { editMeta { it.displayName(Text.renderFlat("{USERNAME} 의 {KITNAME}")) } }
            kit.pools[0].entries += RewardEntry(item = ess.itemResolver.capture(named), minAmount = 1, maxAmount = 1)
            if (store.get(kit.id) != null) return@Check null
            val before = store.usage(p.uniqueId, kit.id)
            try {
                store.putMemory(kit)
                store.markMemory(p.uniqueId, kit.id, KitUsage(System.currentTimeMillis(), 0))
                val cooldown = service.verdict(p, kit).denied
                store.markMemory(p.uniqueId, kit.id, KitUsage(0L, 1))
                val usedUp = service.verdict(p, kit).denied
                val stack = service.roll(kit, p).stacks.firstOrNull()
                val label = stack?.let { StoredItem.plainName(it) }
                when {
                    !p.hasPermission(USE) -> null
                    cooldown != KitService.Denied.COOLDOWN && !p.hasPermission(BYPASS_COOLDOWN) -> "쿨타임 중인데 받을 수 있다고 합니다($cooldown)"
                    usedUp != KitService.Denied.USED_UP && !p.hasPermission(BYPASS_USES) -> "1회용을 이미 받았는데 받을 수 있다고 합니다($usedUp)"
                    label != "${p.name} 의 시험 킷" -> "이름 변수가 채워지지 않았습니다: $label"
                    else -> null
                }
            } finally {
                store.removeMemory(kit.id)
                store.restoreMemory(p.uniqueId, kit.id, before)
            }
        },
        Check("킷 받기 — 사용 기록을 먼저 쓰고(그때 아직 안 받음) 쓰기가 끝난 뒤에 준다(기록 쓰기는 갈아 끼움 — DB 에 쓰지 않음)") { p ->
            if (!p.hasPermission(USE) || p.inventory.firstEmpty() < 0) return@Check null
            val kit = Kit("ess-verify-order", name = "시험 킷", requirePermission = false, cooldown = 60)
            val marker = ItemStack(Material.DIRT).apply { editMeta { it.displayName(Text.renderFlat("ess-verify-kit")) } }
            kit.pools[0].entries += RewardEntry(item = ess.itemResolver.capture(marker), minAmount = 1, maxAmount = 1)
            if (store.get(kit.id) != null) return@Check null
            val before = store.usage(p.uniqueId, kit.id)
            val saved = p.inventory.contents.map { it?.clone() }.toTypedArray()
            fun has(): Boolean = p.inventory.contents.any { it != null && StoredItem.plainName(it) == "ess-verify-kit" }
            try {
                store.putMemory(kit)
                var wrote = false
                var givenBeforeWrite = false
                val started = service.claim(p, kit, persist = { records, done ->
                    wrote = records.any { (id, usage) -> id == kit.id && usage.uses >= 1 }
                    givenBeforeWrite = has()
                    done(true)
                })
                when {
                    !started -> "받기가 막혔습니다"
                    !wrote -> "사용 기록 쓰기가 불리지 않았습니다"
                    givenBeforeWrite -> "기록을 쓰기 전에 이미 줬습니다(크래시 사이에 기록 없이 받음)"
                    !has() -> "기록을 쓴 뒤에 주지 않았습니다"
                    store.usage(p.uniqueId, kit.id)?.uses != (before?.uses ?: 0) + 1 -> "메모리의 받은 횟수가 늘지 않았습니다"
                    else -> null
                }
            } finally {
                p.inventory.contents = saved
                store.removeMemory(kit.id)
                store.restoreMemory(p.uniqueId, kit.id, before)
            }
        },
        Check("킷 기록 — 접속한 사람 것만 메모리에 있다") { p ->
            if (!store.isLoaded(p.uniqueId)) "접속 중인데 킷 기록이 메모리에 없습니다(들어올 때 읽기)"
            else if (store.loadedPlayers() > org.bukkit.Bukkit.getOnlinePlayers().size + 20) "메모리의 킷 기록이 ${store.loadedPlayers()}명 — 나간 사람 것을 내리지 않습니다"
            else null
        },
        Check("그룹 무게 뽑기") { _ ->
            // 무게 1:3 — a 는 [0, 0.25), b 는 [0.25, 1). 0.24 는 a, 0.26 부터 b.
            val picks = listOf(0.0, 0.24, 0.26, 0.99).map { KitRules.weighted(listOf("a" to 1, "b" to 3), { it.second }, it)?.first }
            if (picks != listOf("a", "a", "b", "b")) "무게 뽑기가 이상합니다: $picks" else null
        },
        Check("CMI 예제 Kits.yml 알아보기 — `==: ItemMeta` 로 풀린 아이템(서버)과 jar 기본값(섹션)이 같은 지문") { _ ->
            // 서버에서는 `==: ItemMeta` 가 CraftMetaItem 으로 역직렬화된다 — 이름·설명의 레거시 되돌림·인챈트 이름 차이가 지문을 갈라놓지 않아야 한다.
            val asMeta = runCatching { YamlConfiguration().apply { loadFromString(KitRules.cmiDefaultsAsItemMeta()) } }
                .getOrElse { return@Check "`==: ItemMeta` 가 든 예제를 읽지 못했습니다: $it" }
            val meta = asMeta.get("Newbie.Items.1.meta")
            if (meta !is org.bukkit.inventory.meta.ItemMeta) return@Check "서버인데 meta 가 ItemMeta 로 풀리지 않았습니다(${meta?.javaClass?.simpleName})"
            if (!KitRules.isCmiDefault(asMeta)) "ItemMeta 로 풀린 예제를 jar 기본값과 다르게 봅니다 — 가져오기가 예제 킷 5개를 진짜 킷으로 들여옵니다" else null
        },
        Check("CMI 예제 Kits.yml 알아보기 — 실제 plugins/CMI/Kits/Kits.yml 이 예제 글 그대로면 isCmiDefault(있을 때만 · 읽기만)") { _ ->
            val file = File(ess.plugin.dataFolder.parentFile, "CMI/Kits/Kits.yml").takeIf { it.isFile } ?: return@Check null
            val text = runCatching { file.readText() }.getOrElse { return@Check "CMI Kits.yml 을 읽지 못했습니다: $it" }
            if (!KitRules.looksLikeCmiDefaultText(text)) return@Check null // 운영자가 고친 파일 — 판단할 것이 없다
            val yaml = runCatching { YamlConfiguration().apply { loadFromString(text) } }.getOrElse { return@Check "CMI Kits.yml 을 YAML 로 읽지 못했습니다: $it" }
            if (!KitRules.isCmiDefault(yaml)) "실제 CMI Kits.yml 은 예제 글 그대로인데 isCmiDefault 가 false — 서버 역직렬화 뒤 지문이 다릅니다" else null
        },
        Check("그룹 — 낮은 무게는 밀리고, /킷 <그룹> 을 두 번 쳐도 같은 킷(쿨타임)이라 둘 다 받지 못한다(메모리만)") { p ->
            if (!p.hasPermission(USE)) return@Check null
            val low = Kit("ess-verify-low", requirePermission = false, cooldown = 3600, group = "ess-verify-g", weight = 1)
            val high = Kit("ess-verify-high", requirePermission = false, cooldown = 3600, group = "ess-verify-g", weight = 2)
            for (k in listOf(low, high)) k.pools[0].entries += RewardEntry(item = StoredItem(ItemRef.Vanilla(Material.DIRT), Material.DIRT), minAmount = 1, maxAmount = 1)
            if (store.get(low.id) != null || store.get(high.id) != null) return@Check null
            val before = listOf(low, high).map { it.id to store.usage(p.uniqueId, it.id) }
            try {
                store.putMemory(low)
                store.putMemory(high)
                val members = listOf(low, high)
                if (settings.groupMode == KitsSettings.GroupMode.RANDOM) {
                    // random — 하나를 받으면 그룹 전체에 기록이 남아 두 번째 뽑기는 받을 것이 없다.
                    val first = service.pickGroup(p, members, 0.0) ?: return@Check "받을 수 있는 그룹 킷이 없다고 합니다"
                    val records = KitRules.recordKits(first, store.all(), true)
                    records.forEach { store.markMemory(p.uniqueId, it.id, KitUsage(System.currentTimeMillis(), 1)) }
                    val second = service.pickGroup(p, members, 0.99)
                    if (second != null && !p.hasPermission(BYPASS_COOLDOWN)) "한 번 뽑은 뒤에도 ${second.id} 를 또 받을 수 있습니다" else null
                } else {
                    val lowVerdict = service.verdict(p, low).denied
                    val first = service.pickGroup(p, members)
                    store.markMemory(p.uniqueId, high.id, KitUsage(System.currentTimeMillis(), 1))
                    val second = service.pickGroup(p, members)
                    val secondVerdict = second?.let { service.verdict(p, it).denied }
                    when {
                        lowVerdict != KitService.Denied.SUPERSEDED -> "낮은 무게 킷이 밀리지 않았습니다($lowVerdict)"
                        first !== high -> "그룹에서 높은 무게를 고르지 않았습니다(${first?.id})"
                        second !== high -> "쿨타임 중에 낮은 무게로 넘어갔습니다(${second?.id}) — 그룹을 되풀이해 다 받습니다"
                        secondVerdict != KitService.Denied.COOLDOWN && !p.hasPermission(BYPASS_COOLDOWN) -> "두 번째가 쿨타임이 아닙니다($secondVerdict)"
                        else -> null
                    }
                }
            } finally {
                store.removeMemory(low.id)
                store.removeMemory(high.id)
                before.forEach { (id, usage) -> store.restoreMemory(p.uniqueId, id, usage) }
            }
        },
    )

    companion object {
        const val USE = "inmcessentials.kits.use"
        const val ALL_KITS = "inmcessentials.kits.kit"
        const val PREVIEW_ALL = "inmcessentials.kits.preview"
        const val OTHERS = "inmcessentials.kits.others"
        const val EDIT = "inmcessentials.kits.edit"
        const val RESET = "inmcessentials.kits.reset"
        const val BYPASS_COOLDOWN = "inmcessentials.kits.bypass.cooldown"
        const val BYPASS_USES = "inmcessentials.kits.bypass.uses"
        const val BYPASS_MONEY = "inmcessentials.kits.bypass.money"
        const val BYPASS_EXP = "inmcessentials.kits.bypass.exp"
        const val BYPASS_CONDITIONS = "inmcessentials.kits.bypass.conditions"

        private const val KIND_DEF = "킷 정의"
        private const val KIND_USE = "킷 사용 기록"

        private val HANDLED = setOf(
            "CommandName", "Description", "Delay", "MoneyCost", "ExpCost", "Group", "Weight", "Items", "Commands", "Conditions",
            "MaxUsages", "DisplayName", "Icon", "IconOff", "Slot", "Slots", "Page", "Enabled", "ShowDespiteWeight", "DropOnGround",
        )

        private val ARMOR_KEYS = setOf("Helmet", "Chest", "Legs", "Boots", "OffHand")

        val MESSAGES: Map<String, String> = linkedMapOf(
            "kits-none" to "<gray>받을 수 있는 킷이 없습니다.</gray>",
            "kits-list-header" to "<gold>킷 {개수}개</gold> <gray>(눌러서 받기)</gray>",
            "kits-list-entry" to "<click:run_command:'/킷 {값}'><hover:show_text:'<gray>/킷 {값}</gray>'><white>{이름}</white></hover></click> <gray>{상태}</gray>",
            "kits-state-ready" to "<green>받기</green>",
            "kits-state-locked" to "<red>잠김</red>",
            "kits-unknown" to "<red>그런 킷이 없습니다: {값}</red>",
            "kits-received" to "<green>킷 <white>{이름}</white> 을(를) 받았습니다.</green> <gray>{아이템}</gray>",
            "kits-given" to "<green>{플레이어}님에게 킷 <white>{이름}</white> 을(를) 줬습니다.</green>",
            "kits-mailed" to "<yellow>받는 사람이 접속 중이 아니라 킷 {이름} 을(를) 우편으로 보냈습니다.</yellow>",
            "kits-unresolved" to "<yellow>만들 수 없는 보상 {개수}개를 건너뛰었습니다(그 아이템의 플러그인이 없음).</yellow>",
            "kits-failed" to "<red>킷 {이름} 기록을 쓰지 못해 주지 않았습니다(비용은 돌려줬습니다) — 서버 로그를 보세요.</red>",
            "kits-denied-permission" to "<red>킷 {이름} 을(를) 받을 권한이 없습니다.</red>",
            "kits-denied-superseded" to "<red>킷 {이름} 대신 같은 그룹의 더 높은 킷을 받을 수 있습니다.</red>",
            "kits-denied-condition" to "<red>킷 {이름} 을(를) 받으려면 조건이 필요합니다: {값}</red>",
            "kits-denied-cooldown" to "<red>킷 {이름} 은(는) {시간} 뒤에 받을 수 있습니다.</red>",
            "kits-denied-used-up" to "<red>킷 {이름} 은(는) 이미 받을 만큼 받았습니다.</red>",
            "kits-denied-money" to "<red>킷 {이름} 을(를) 받으려면 {금액}이(가) 필요합니다.</red>",
            "kits-denied-exp" to "<red>킷 {이름} 을(를) 받으려면 경험치 {개수}점이 필요합니다.</red>",
            "kits-denied-empty" to "<red>킷 {이름} 이(가) 비어 있습니다.</red>",
            "kits-denied-busy" to "<gray>킷 {이름} 을(를) 주는 중입니다…</gray>",
            "kits-preview-denied" to "<red>이 킷은 미리볼 수 없습니다.</red>",
            "kits-reset-usage" to "<yellow>/킷초기화 [킷|all] [플레이어|all] [쿨타임|횟수]</yellow> <gray>— 셋째를 비우면 쿨타임과 횟수를 모두 되돌립니다</gray>",
            "kits-reset-done" to "<green>킷 {값} · {플레이어} 의 기록 {개수}줄을 되돌렸습니다.</green>",
            // 편집기
            "kits-bad-id" to "<red>id 로 쓸 수 없습니다: {값}</red> <gray>(영문 소문자·숫자·_- 32자)</gray>",
            "kits-exists" to "<red>id {값} 의 킷이 이미 있습니다.</red>",
            "kits-created" to "<green>킷 {값} 을(를) 만들었습니다.</green>",
            "kits-removed" to "<green>킷 {값} 을(를) 지웠습니다.</green>",
            "kits-renamed" to "<green>킷 id 를 {값} → {새id} 로 바꿨습니다(사용 기록도 옮김).</green> <gray>권한은 이제 {권한} — LuckPerms 등의 옛 노드를 고치세요</gray>",
            "kits-copied" to "<green>가방의 {개수}칸을 1번 묶음에 담았습니다.</green>",
            "kits-last-pool" to "<red>묶음이 하나뿐이라 지울 수 없습니다.</red>",
        )
    }
}

/** `settings/kits.yml` (CMI config.yml Kits 에 해당). */
data class KitsSettings(
    /** `/킷` 을 화면으로(false 면 채팅 목록) — CMI Kits.GUI. */
    val gui: Boolean = true,
    /** 권한이 없는 킷도 잠긴 모습으로 보인다. */
    val showLocked: Boolean = true,
    /** 빈 칸을 채운다 — CMI Kits.FillEmptyFields. */
    val fillEmpty: Boolean = true,
    /** 마지막 받은 때의 꼴 — CMI Kits.DateFormat. */
    val dateFormat: String = "yyyy-MM-dd HH:mm",
    /** 그룹 무게의 뜻 — [GroupMode]. */
    val groupMode: GroupMode = GroupMode.PRIORITY,
) {
    enum class GroupMode {
        /** CMI 와 같다 — 같은 그룹에서 받을 수 있는 것 중 무게가 가장 높은 것만(낮은 것은 막힘). */
        PRIORITY,

        /** `/킷 <그룹>` 이 지금 받을 수 있는 것 중 무게로 하나를 뽑고, 그룹 전체에 쿨타임·횟수 기록을 남긴다. */
        RANDOM,
    }

    companion object {
        fun from(y: YamlConfiguration): KitsSettings {
            val d = KitsSettings()
            val format = y.getString("date-format", d.dateFormat).orEmpty()
            return KitsSettings(
                gui = y.getBoolean("gui", d.gui),
                showLocked = y.getBoolean("show-locked", d.showLocked),
                fillEmpty = y.getBoolean("fill-empty", d.fillEmpty),
                dateFormat = if (runCatching { java.text.SimpleDateFormat(format) }.isSuccess && format.isNotBlank()) format else d.dateFormat,
                groupMode = GroupMode.entries.firstOrNull { it.name.equals(y.getString("group-mode", "").orEmpty().trim(), ignoreCase = true) } ?: d.groupMode,
            )
        }
    }
}

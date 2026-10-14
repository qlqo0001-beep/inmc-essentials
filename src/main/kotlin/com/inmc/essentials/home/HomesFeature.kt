package com.inmc.essentials.home

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.spawn.SpawnFeature
import com.inmc.essentials.spawn.SpawnHomes
import com.inmc.essentials.verify.Check
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.permissions.PermissionDefault
import java.io.File

/**
 * 홈(2026-10-10) — 카탈로그 mod:sys-homes · fix:cfg-home-limits-gui · set3:cfg-home-bed. CMI 의 `Settings/Homes.yml` 에 해당하는 것은
 * `settings/homes.yml`, 홈 자체는 DB `ess_homes`(전부 메모리에 들고 쓰는 순간 DB 로).
 *
 * 부활 순서의 침대 홈·기본 홈 칸은 [ready] 에서 spawn 기능에 [SpawnHomes] 창구로 건다(그 패키지의 작은 인터페이스 — 규칙 1 의 예외).
 */
class HomesFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "homes"

    override val hasSettings = true

    override val permissions = listOf(
        Perm(USE, PermissionDefault.TRUE, "/홈 · /홈설정 · /홈삭제 · /홈목록 — 자기 홈"),
        Perm(BED, PermissionDefault.TRUE, "침대를 눌러 침대 홈 만들기"),
        Perm(OTHERS, PermissionDefault.OP, "남의 홈 보기·가기·만들기·지우기(비공개 포함)"),
        Perm(VISIT, PermissionDefault.FALSE, "남의 공개 홈에 가기(/홈 <이름> <주인>)"),
        Perm(UNLIMITED, PermissionDefault.OP, "홈 개수 제한 없음"),
        Perm(BYPASS_LIMIT, PermissionDefault.OP, "월드별 홈 개수 제한 무시"),
        Perm(BYPASS_PROTECTION, PermissionDefault.OP, "보호구역 안 홈 금지 무시"),
        Perm(CUSTOM_LOCATION, PermissionDefault.OP, "/홈설정 … 위치:월드;x;y;z"),
        Perm(NEAR, PermissionDefault.OP, "/홈목록 near:반경 — 근처의 모든 홈"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: HomeSettings = HomeSettings()
        private set

    internal lateinit var store: HomeStore
    internal lateinit var service: HomeService

    /** spawn 에 건 창구 — 뗄 때 우리 것인지 본다. */
    private var link: SpawnHomes? = null

    override fun schema(sql: SqlSource) {
        HomeStore(ess).schema(sql)
    }

    override fun load(settings: YamlConfiguration) {
        this.settings = HomeSettings.from(settings)
    }

    override fun enable() {
        store = HomeStore(ess)
        store.loadAll()
        service = HomeService(ess, this, store, Protection(ess.plugin, ess.logger))
        ess.listen(HomeListener(ess, this, service))
        HomeCommands(ess, service).register()
    }

    override fun ready() {
        val spawn = ess.feature("spawn") as? SpawnFeature ?: return
        val mine = object : SpawnHomes {
            override fun bedHome(player: Player): Location? =
                service.bedHome(player.uniqueId)?.takeIf { service.bedStillThere(it) }?.location()

            override fun defaultHome(player: Player): Location? = service.defaultHome(player.uniqueId)?.location()
        }
        spawn.homes = mine
        link = mine
    }

    override fun disable() {
        val spawn = ess.feature("spawn") as? SpawnFeature
        if (spawn != null && link != null && spawn.homes === link) spawn.homes = null
        link = null
    }

    // --- CMI 가져오기 ----------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::store.isInitialized) return report.skipped(KIND, "홈 기능이 켜지지 않았습니다")
        val s = settings
        var any = false
        val cmiDefault = cmiDefaultName(data)
        var renamedDefault = 0
        for (user in data.users) {
            // 이름 맞추기의 겹침은 이 사람의 CMI 홈끼리만 본다(다시 가져와도 같은 이름이 나와 '이미 있음' 으로 건너뛰게).
            val batch = user.homes.map { it.name.trim() }.filter { HomeRules.nameProblem(it, s) == null }.map(Home::keyOf).toMutableSet()
            // CMI 의 기본 홈(Homes.DefaultName — 테섭 'Home')은 우리 기본 이름으로: 맨 `/홈설정` 이 그 홈을 덮고 부활 '기본 홈' 칸이 그것을 고르게.
            val renameKey = HomeRules.defaultRename(user.homes.map { it.name.trim() }, cmiDefault, s.defaultName)
            if (renameKey != null) {
                batch -= renameKey
                batch += Home.keyOf(s.defaultName)
            }
            for (cmi in user.homes) {
                report.read(KIND)
                any = true
                val loc = cmi.location
                if (loc.world.isBlank()) {
                    report.unreadable(KIND, "${user.name} 의 홈 '${cmi.name}' — 월드가 비었습니다")
                    continue
                }
                val icon = cmi.icon?.let { raw ->
                    Material.matchMaterial(raw.substringBefore(':'))?.takeIf { it.isItem }?.name
                        ?: null.also { report.note("${user.name} 의 홈 '${cmi.name}' 아이콘 '$raw' 은(는) 재질이 아니라 기본 아이콘으로 둡니다") }
                }
                val original = cmi.name.trim().ifEmpty { s.defaultName }
                var toDefault = renameKey != null && Home.keyOf(original) == renameKey
                // 우리 쪽에 이미 **다른 자리**의 기본 홈(넘어온 뒤 맨 /홈설정 으로 만든 것)이 있으면 바꿔 넣을 수 없다 — 자리를 잃지 않게 이름 그대로 넣고
                // 보고서에 남긴다. 같은 자리면 그대로 '이미 있음' 으로 건너뛴다. 다시 가져오면 그 이름(Home)이 이미 있어 건너뛴다.
                val ours = if (toDefault) store.get(user.id, s.defaultName) else null
                val keptName = ours != null && !HomeRules.sameSpot(ours, loc.world, loc.x, loc.y, loc.z)
                if (keptName) toDefault = false
                val name = if (toDefault) s.defaultName else HomeRules.normalizeName(original, s) { Home.keyOf(it) in batch }
                if (toDefault) {
                    renamedDefault++
                } else {
                    if (keptName) batch += Home.keyOf(name)
                    if (name != original) {
                        batch += Home.keyOf(name)
                        report.note("${user.name} 의 홈 '$original' 은(는) 이름 규칙(name-pattern·길이)에 맞지 않아 '$name' (으)로 바꿔 가져옵니다")
                    }
                }
                val home = Home(
                    owner = user.id, name = name, world = loc.world,
                    x = loc.x, y = loc.y, z = loc.z, yaw = loc.yaw, pitch = loc.pitch,
                    icon = icon, private = cmi.private, created = System.currentTimeMillis(),
                )
                if (store.insertIfAbsent(home)) {
                    report.imported(KIND)
                    if (keptName) report.note("${user.name} 의 홈 '${home.name}' 은(는) 다른 자리의 '${s.defaultName}' 이(가) 이미 있어 이름 그대로 넣었습니다")
                } else {
                    report.skipped(KIND, "${user.name} 의 홈 '${home.name}' 이(가) 이미 있습니다")
                }
                if (Bukkit.getWorld(loc.world) == null) report.note("${user.name} 의 홈 '${home.name}' 의 월드 '${loc.world}' 가 지금 없습니다 — 열리면 씁니다")
            }
        }
        if (renamedDefault > 0) {
            report.note("CMI 기본 홈 이름 '$cmiDefault'(Homes.DefaultName) 인 홈 ${renamedDefault}개를 우리 기본 이름 '${s.defaultName}' (으)로 바꿔 가져왔습니다" +
                " — 이름을 그대로 두려면 가져오기 전에 settings/homes.yml default-name 을 '$cmiDefault' 로 두세요")
        }
        if (any) noteLegacyPermissions(data, report)
    }

    /** CMI `Settings/Homes.yml` 의 `Homes.DefaultName`(없으면 CMI 기본 'Home'). */
    private fun cmiDefaultName(data: CmiData): String {
        val file = File(data.folder, "Settings/Homes.yml")
        val raw = if (file.isFile) YamlConfiguration.loadConfiguration(file).getString("Homes.DefaultName") else null
        return raw?.trim()?.takeIf { it.isNotEmpty() } ?: CMI_DEFAULT_NAME
    }

    /** CMI 의 한도 그룹과 권한 노드 — 넘어가는 동안 CMI 노드도 통하지만, 옮길 이름을 보고서에 남긴다. */
    private fun noteLegacyPermissions(data: CmiData, report: ImportReport) {
        val file = File(data.folder, "Settings/Homes.yml")
        if (file.isFile) {
            val groups = YamlConfiguration.loadConfiguration(file).getConfigurationSection("Homes.Groups")
            val cmi = groups?.getKeys(false)?.associate { it.lowercase() to groups.getInt(it) }.orEmpty()
            val ours = settings.groups
            val differ = cmi.filter { (k, v) -> ours[k] != v }
            if (differ.isNotEmpty()) {
                report.note("CMI 홈 그룹 ${differ.entries.joinToString(", ") { "${it.key} ${it.value}" }} 이(가) settings/homes.yml limits.groups 와 다릅니다 — 같게 하려면 그쪽에 적으세요")
            }
        }
        report.note(
            "홈 권한: CMI 노드(cmi.command.sethome.<그룹|숫자>·unlimited·bypasslimit·bypass·customloc)도 지금은 통합니다 — CMI 를 지우기 전에 LuckPerms 에서 " +
                "inmcessentials.homes.limit.<그룹|숫자>·${UNLIMITED}·${BYPASS_LIMIT}·${BYPASS_PROTECTION}·${CUSTOM_LOCATION} 로 바꾸세요(docs/homes.md 'CMI 권한 옮기기')",
        )
    }

    // --- 검증기 --------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("스폰에 홈 창구가 걸려 있다") { _ ->
            val spawn = ess.feature("spawn") as? SpawnFeature ?: return@Check null
            if (spawn.homes == null || spawn.homes !== link) "spawn 기능의 홈 창구가 비었거나 다른 것입니다 — ready() 를 보세요" else null
        },
        Check("홈 한도 — 그룹·숫자 권한을 읽는다") { p ->
            val s = settings
            val limit = service.limitOf(p)
            val expectedUnlimited = p.hasPermission(UNLIMITED) ||
                p.effectivePermissions.any { it.value && it.permission.equals(HomeRules.LEGACY_UNLIMITED, ignoreCase = true) }
            when {
                expectedUnlimited && limit != null -> "무제한 권한이 있는데 한도가 $limit 입니다"
                !expectedUnlimited && limit == null -> "무제한 권한이 없는데 한도가 무제한입니다"
                HomeRules.limit(listOf(HomeRules.LIMIT_PREFIX + "7"), false, s) != 7 -> "숫자 권한(…limit.7)을 못 읽었습니다"
                HomeRules.limit(listOf(HomeRules.LEGACY_PREFIX + "7"), false, s) != 7 -> "CMI 숫자 권한(cmi.command.sethome.7)을 못 읽었습니다"
                else -> null
            }
        },
        Check("홈 찾기 — 이름은 대소문자 무시, 기본 홈·침대 홈 창구(메모리만)") { p ->
            val probe = Home.at(p.uniqueId, PROBE, p.location, Long.MIN_VALUE)
            val bed = probe.copy(name = PROBE_BED, bed = true, bedBlock = null, y = p.location.y + 1)
            val had = store.get(p.uniqueId, PROBE) ?: store.get(p.uniqueId, PROBE_BED)
            if (had != null) return@Check null // 그런 이름의 진짜 홈이 있으면 건드리지 않는다
            // 진짜 침대 홈이 있으면 침대 칸은 그것으로 본다(검사용 침대 홈을 넣지 않는다 — bedHome 은 첫 침대 홈을 돌려준다).
            val realBed = service.bedHome(p.uniqueId)
            try {
                store.putMemory(probe)
                if (realBed == null) store.putMemory(bed)
                val spawn = ess.feature("spawn") as? SpawnFeature
                val expectedBed = realBed?.name ?: PROBE_BED
                when {
                    store.get(p.uniqueId, PROBE.uppercase()) == null -> "대문자로 찾지 못했습니다"
                    service.bedHome(p.uniqueId)?.name != expectedBed -> "침대 홈을 찾지 못했습니다"
                    realBed == null && spawn?.homes != null && spawn.homes?.bedHome(p) == null -> "스폰 창구가 침대 홈을 돌려주지 않았습니다"
                    spawn?.homes != null && service.defaultHome(p.uniqueId) != null && spawn.homes?.defaultHome(p) == null -> "스폰 창구가 기본 홈을 돌려주지 않았습니다"
                    else -> null
                }
            } finally {
                store.removeMemory(p.uniqueId, PROBE)
                store.removeMemory(p.uniqueId, PROBE_BED)
            }
        },
        Check("침대 홈 — 누르기·부수기 리스너(MONITOR), 부서진 침대로 홈 찾기(메모리만)") { p ->
            val interact = PlayerInteractEvent.getHandlerList().registeredListeners.filter { it.plugin == ess.plugin && it.listener is HomeListener }
            val breaks = BlockBreakEvent.getHandlerList().registeredListeners.filter { it.plugin == ess.plugin && it.listener is HomeListener }
            if (interact.none { it.priority == EventPriority.MONITOR }) return@Check "침대 누르기 리스너가 MONITOR 에 없습니다"
            if (breaks.none { it.priority == EventPriority.MONITOR }) return@Check "침대 부수기 리스너가 MONITOR 에 없습니다"
            if (store.get(p.uniqueId, PROBE_BED_BREAK) != null) return@Check null // 그런 이름의 진짜 홈이 있으면 건드리지 않는다
            val block = p.location.block.getRelative(BlockFace.DOWN)
            val head = service.bedHead(block)
            val probe = Home.at(p.uniqueId, PROBE_BED_BREAK, p.location, Long.MIN_VALUE)
                .copy(bed = true, bedBlock = Home.blockKey(head.world.name, head.x, head.y, head.z))
            try {
                store.putMemory(probe)
                when {
                    service.bedHomesAt(block).none { it.owner == p.uniqueId && it.key == probe.key } -> "침대 자리로 침대 홈을 찾지 못했습니다(부서져도 안 지워집니다)"
                    HomeRules.parseBlockKey(probe.bedBlock) == null -> "침대 자리 '${probe.bedBlock}' 을(를) 되읽지 못했습니다"
                    else -> null
                }
            } finally {
                store.removeMemory(p.uniqueId, PROBE_BED_BREAK)
            }
        },
        Check("보호구역 검사 연결(WorldGuard·Lands)") { p ->
            if (!settings.protectionCheck) return@Check null
            service.protection.blockedBy(p, p.location) // 연결만 본다 — 결과는 자리에 따라 다르다
            val broken = service.protection.describe().filter { it.contains("실패") }
            if (broken.isEmpty()) null else "연결 실패: ${broken.joinToString(", ")} — 서버 로그를 보세요(그 플러그인은 검사에서 빠집니다)"
        },
    )

    companion object {
        const val USE = "inmcessentials.homes.use"
        const val BED = "inmcessentials.homes.bed"
        const val OTHERS = "inmcessentials.homes.others"
        const val VISIT = "inmcessentials.homes.visit"
        const val UNLIMITED = "inmcessentials.homes.unlimited"
        const val BYPASS_LIMIT = "inmcessentials.homes.bypasslimit"
        const val BYPASS_PROTECTION = "inmcessentials.homes.bypassprotection"
        const val CUSTOM_LOCATION = "inmcessentials.homes.customloc"
        const val NEAR = "inmcessentials.homes.near"

        private const val KIND = "홈"
        private const val PROBE = "ess-verify-home"
        private const val PROBE_BED = "ess-verify-bed"
        private const val PROBE_BED_BREAK = "ess-verify-bedbreak"

        /** CMI `Homes.DefaultName` 이 없을 때 CMI 가 쓰는 이름. */
        private const val CMI_DEFAULT_NAME = "Home"

        val MESSAGES: Map<String, String> = linkedMapOf(
            "homes-none" to "<gray>저장된 홈이 없습니다.</gray> <yellow>/홈설정 [이름]</yellow>",
            "homes-none-other" to "<gray>{플레이어}님은 홈이 없습니다.</gray>",
            "homes-not-found" to "<red>'{홈}' 홈이 없습니다.</red>",
            "homes-teleported" to "<green>홈 <white>{홈}</white>(으)로 이동했습니다.</green>",
            "homes-sent" to "<green>{대상}님을 홈 <white>{홈}</white>(으)로 보냈습니다.</green>",
            "homes-teleport-failed" to "<red>홈 {홈}(으)로 이동하지 못했습니다.</red>",
            "homes-world-missing" to "<red>홈 {홈} 의 월드({월드})가 없습니다.</red>",
            "homes-bed-missing" to "<red>홈 {홈} 의 침대가 사라져 홈을 지웠습니다.</red>",
            "homes-over-limit" to "<red>홈이 한도보다 많습니다({개수}/{값}) — 몇 개를 지워야 홈으로 갈 수 있습니다.</red> <gray>/홈삭제</gray>",
            "homes-over-limit-login" to "<yellow>홈이 한도보다 많습니다({개수}/{값}). 지우기 전까지 홈 이동이 막힙니다.</yellow> <gray>/홈삭제</gray>",
            "homes-over-limit-notice" to "<yellow>홈이 한도보다 많습니다({개수}/{값}). 새 홈은 만들 수 없습니다.</yellow>",
            "homes-private" to "<red>비공개 홈입니다.</red>",
            "homes-console-who" to "<red>콘솔에서는 보낼 사람을 적어야 합니다: /홈 [이름] [주인] [보낼 사람]</red>",
            "homes-invalid-name" to "<red>홈 이름에 쓸 수 없는 글자가 있습니다.</red> <gray>(한글·영문·숫자·_-)</gray>",
            "homes-name-too-long" to "<red>홈 이름은 {값}자까지입니다.</red>",
            "homes-exists" to "<yellow>'{홈}' 홈이 이미 있습니다.</yellow> <click:run_command:'{명령}'><hover:show_text:'<gray>눌러서 덮어쓰기</gray>'><green>[덮어쓰기]</green></hover></click>",
            "homes-limit-reached" to "<red>홈을 더 만들 수 없습니다(최대 {개수}개).</red> <gray>/홈삭제 로 지우거나 같은 이름으로 덮어쓰세요.</gray>",
            "homes-world-limit" to "<red>이 월드에서는 홈을 {개수}개까지 둘 수 있습니다(지금 {값}개).</red>",
            "homes-protected" to "<red>보호구역({값}) 안에는 홈을 둘 수 없습니다.</red>",
            "homes-slot-invalid" to "<red>칸 번호는 1~{값} 입니다.</red>",
            "homes-slot-taken" to "<red>{값}번 칸은 홈 {다른홈} 이(가) 쓰고 있습니다.</red>",
            "homes-icon-invalid" to "<red>'{값}' 은(는) 아이콘으로 쓸 수 없는 재질입니다.</red> <gray>(재질 이름 또는 '손')</gray>",
            "homes-location-invalid" to "<red>위치를 읽지 못했습니다: {값}</red> <gray>(월드;x;y;z — 그 월드가 열려 있어야 합니다)</gray>",
            "homes-set" to "<green>홈 <white>{홈}</white> 을(를) 정했습니다.</green> <gray>({개수}/{값})</gray>",
            "homes-set-private" to "<green>비공개 홈 <white>{홈}</white> 을(를) 정했습니다.</green> <gray>({개수}/{값})</gray>",
            "homes-set-other" to "<green>{플레이어}님의 홈 <white>{홈}</white> 을(를) 정했습니다.</green>",
            "homes-removed" to "<green>홈 {홈} 을(를) 지웠습니다.</green>",
            "homes-removed-other" to "<green>{플레이어}님의 홈 {홈} 을(를) 지웠습니다.</green>",
            "homes-bed-set" to "<green>침대 홈 <white>{홈}</white> 을(를) 정했습니다.</green> <gray>/홈 {홈}</gray>",
            "homes-bed-name-taken" to "<red>보통 홈 '{홈}' 이(가) 침대 홈 이름을 쓰고 있어 침대 홈을 만들지 않았습니다.</red> <gray>그 홈의 이름을 바꾸거나 지우세요.</gray>",
            "homes-bed-removed" to "<yellow>침대가 부서져 침대 홈 {홈} 을(를) 지웠습니다.</yellow>",
            "homes-icon-set" to "<green>홈 {홈} 의 아이콘을 {값}(으)로 바꿨습니다.</green>",
            "homes-icon-reset" to "<green>홈 {홈} 의 아이콘을 기본으로 되돌렸습니다.</green>",
            "homes-private-on" to "<green>홈 {홈} 을(를) 비공개로 바꿨습니다.</green>",
            "homes-private-off" to "<green>홈 {홈} 을(를) 공개로 바꿨습니다.</green>",
            "homes-list" to "<gold>홈 {개수}/{값}:</gold> {목록}",
            "homes-list-other" to "<gold>{플레이어}님의 홈 {개수}개:</gold> {목록}",
            "homes-list-entry" to "<click:run_command:'{명령}'><hover:show_text:'<gray>{값}</gray>'><white>{홈}</white></hover></click>",
            "homes-list-entry-private" to "<click:run_command:'{명령}'><hover:show_text:'<gray>{값} (비공개)</gray>'><gray>{홈}</gray></hover></click>",
            "homes-list-separator" to "<dark_gray>, </dark_gray>",
            "homes-remove-pick" to "<yellow>지울 홈을 누르세요:</yellow> {목록}",
            "homes-remove-entry" to "<click:run_command:'{명령}'><hover:show_text:'<red>눌러서 지우기</red>'><red>{홈}</red></hover></click>",
            "homes-near-header" to "<gold>{값}블록 안의 홈 {개수}개:</gold>",
            "homes-near-entry" to "<gray>{순위}.</gray> <white>{플레이어}</white> <click:run_command:'{명령}'><yellow>{홈}</yellow></click> <gray>{값}블록 {방향}</gray>",
            "homes-near-none" to "<gray>{값}블록 안에 홈이 없습니다.</gray>",
        )
    }
}

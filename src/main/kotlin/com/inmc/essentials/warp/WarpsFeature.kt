package com.inmc.essentials.warp

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.spawn.SpawnFeature
import com.inmc.essentials.spawn.SpawnWarps
import com.inmc.essentials.verify.Check
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.permissions.PermissionDefault

/**
 * 워프(2026-10-10) — 카탈로그 mod:sys-warps. 저장은 `warps.yml`(원자적), 설정은 `settings/warps.yml`(CMI config `Warps`).
 * 부활 순서의 워프 칸은 [ready] 에서 spawn 기능에 [SpawnWarps] 창구로 건다.
 */
class WarpsFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "warps"

    override val hasSettings = true

    override val permissions = listOf(
        Perm(USE, PermissionDefault.TRUE, "/워프 · /워프그룹 — 워프 쓰기"),
        Perm(OTHERS, PermissionDefault.OP, "/워프 <이름> <플레이어> — 남을 워프로(워프 권한 요구를 건너뜀)"),
        Perm(SET, PermissionDefault.OP, "/워프설정 — 워프 만들기, 자기가 만든 워프 고치기·지우기"),
        Perm(CUSTOM_LOCATION, PermissionDefault.OP, "/워프설정 … 월드;x;y;z — 선 자리가 아닌 좌표에 워프 만들기"),
        Perm(ADMIN, PermissionDefault.OP, "모든 워프 고치기·지우기 · 숨긴 워프 보기 · 개수 제한 없음"),
        Perm(ALL, PermissionDefault.OP, "권한이 필요한 워프 전부 쓰기"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: WarpSettings = WarpSettings()
        private set

    internal lateinit var store: WarpStore
    internal lateinit var service: WarpService

    private var link: SpawnWarps? = null

    override fun load(settings: YamlConfiguration) {
        this.settings = WarpSettings.from(settings)
        // 리로드 — 손으로 고친 warps.yml 을 다시 읽는다(켤 때는 enable 이 읽는다). 읽기는 I/O 워커, 반영·로그는 메인.
        if (::store.isInitialized) store.reloadIfChanged()
    }

    override fun enable() {
        store = WarpStore(ess)
        store.loadNow()
        service = WarpService(ess, this, store)
        WarpCommands(ess, service).register()
    }

    override fun ready() {
        val spawn = ess.feature("spawn") as? SpawnFeature ?: return
        val mine = object : SpawnWarps {
            override fun warp(name: String): Location? = store.get(name)?.location()
            override fun names(): List<String> = store.all().map { it.name }.sortedBy { it.lowercase() }
        }
        spawn.warps = mine
        link = mine
    }

    /** 이름이 바뀌면 부활 워프도 따라가게(spawn 기능 — 같은 작업자가 맡은 창구, 방향은 워프 → 스폰). */
    internal fun renamed(old: String, next: String) {
        (ess.feature("spawn") as? SpawnFeature)?.warpRenamed(old, next)
    }

    /** 지운 워프가 부활 워프였으면 spawn 기능이 알린다. */
    internal fun removed(name: String, by: CommandSender) {
        (ess.feature("spawn") as? SpawnFeature)?.warpRemoved(name, by)
    }

    override fun disable() {
        val spawn = ess.feature("spawn") as? SpawnFeature
        if (spawn != null && link != null && spawn.warps === link) spawn.warps = null
        link = null
    }

    // --- CMI 가져오기 ----------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::store.isInitialized) return report.skipped(KIND, "워프 기능이 켜지지 않았습니다")
        val added = ArrayList<Warp>()
        // 이름 맞추기의 겹침은 이번 CMI 워프끼리만 본다(다시 가져와도 같은 이름이 나와 '이미 있음' 으로 건너뛰게).
        val batch = data.warps.map { it.name.trim() }.filter { WarpRules.nameProblem(it, settings) == null }.map(Warp::keyOf).toMutableSet()
        for (cmi in data.warps) {
            report.read(KIND)
            val loc = cmi.location
            if (cmi.name.isBlank() || loc.world.isBlank()) {
                report.unreadable(KIND, "이름이나 월드가 빈 워프: ${cmi.raw}")
                continue
            }
            val original = cmi.name.trim()
            val name = WarpRules.normalizeName(original, settings) { Warp.keyOf(it) in batch }
            if (name != original) {
                batch += Warp.keyOf(name)
                report.note("워프 '$original' 은(는) 이름 규칙(name-pattern·길이)에 맞지 않아 '$name' (으)로 바꿔 가져옵니다")
            }
            if (store.get(name) != null || added.any { it.key == Warp.keyOf(name) }) {
                report.skipped(KIND, "워프 '$name' 이(가) 이미 있습니다")
                continue
            }
            val icon = cmi.icon?.let { raw ->
                Material.matchMaterial(raw.substringBefore(':'))?.takeIf { it.isItem && !it.isAir }?.name
                    ?: null.also { report.note("워프 '${cmi.name}' 의 아이콘 '$raw' 은(는) 재질이 아니라 기본 아이콘으로 둡니다(머리 텍스처는 /워프편집 에서 손에 들고 다시)") }
            }
            val warp = Warp(
                name = name, world = loc.world, x = loc.x, y = loc.y, z = loc.z, yaw = loc.yaw, pitch = loc.pitch,
                group = cmi.group?.trim()?.takeIf { it.isNotEmpty() }, permission = cmi.permission, icon = icon,
                created = System.currentTimeMillis(),
            )
            if (warp.permission) report.note("워프 '${warp.name}' 은(는) 권한이 필요합니다 — CMI 의 cmi.command.warp.${original.lowercase()} 대신 ${WarpRules.WARP_PREFIX}${warp.key} 를 주세요")
            val unknown = cmi.raw.keys.map { it.toString() }.filterNot { it.lowercase() in KNOWN_RAW }
            if (unknown.isNotEmpty()) report.note("워프 '${warp.name}' 의 CMI 속성 ${unknown.joinToString(", ")} 은(는) 옮기지 않았습니다 — /워프편집 에서 다시 정하세요")
            if (Bukkit.getWorld(loc.world) == null) report.note("워프 '${warp.name}' 의 월드 '${loc.world}' 가 지금 없습니다 — 열리면 씁니다")
            added += warp
            report.imported(KIND)
        }
        store.putAll(added)
    }

    // --- 검증기 --------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("스폰에 워프 창구가 걸려 있다") { _ ->
            val spawn = ess.feature("spawn") as? SpawnFeature ?: return@Check null
            if (spawn.warps == null || spawn.warps !== link) "spawn 기능의 워프 창구가 비었거나 다른 것입니다 — ready() 를 보세요" else null
        },
        Check("warps.yml 을 다 읽었다") { _ ->
            val rejected = store.data.rejected.size
            if (rejected > 0) "읽지 못한 워프 $rejected 칸 — 서버 로그를 보세요(파일에는 남아 있습니다)" else null
        },
        Check("워프 권한 — 권한이 필요한 워프를 권한으로 가른다(메모리만)") { p ->
            val before = store.data
            try {
                val probe = Warp(PROBE, p.world.name, p.location.x, p.location.y, p.location.z, permission = true, showLocked = false)
                store.swap(before.copy(warps = before.warps.filter { it.key != probe.key } + probe))
                val expected = p.hasPermission(ALL) || p.hasPermission(ADMIN) || p.hasPermission(WarpRules.WARP_PREFIX + probe.key)
                val seen = service.visible(p).any { it.key == probe.key }
                when {
                    service.canUse(p, probe) != expected -> "권한 판정이 다릅니다(기대 $expected)"
                    seen != expected -> "목록에 ${if (seen) "보입니다" else "안 보입니다"} — 잠긴 채 보이기를 끈 워프는 쓸 수 있을 때만 보여야 합니다"
                    else -> null
                }
            } finally {
                store.swap(before)
            }
        },
        Check("스폰 창구가 워프를 찾는다(메모리만)") { p ->
            val spawn = ess.feature("spawn") as? SpawnFeature ?: return@Check null
            val link = spawn.warps ?: return@Check null
            val before = store.data
            try {
                val probe = Warp(PROBE, p.world.name, p.location.x, p.location.y, p.location.z, hidden = true, permission = true)
                store.swap(before.copy(warps = before.warps.filter { it.key != probe.key } + probe))
                if (link.warp(PROBE.uppercase()) == null) "숨긴·권한 워프를 부활용으로 찾지 못했습니다(권한과 무관해야 합니다)" else null
            } finally {
                store.swap(before)
            }
        },
    )

    companion object {
        const val USE = "inmcessentials.warps.use"
        const val OTHERS = "inmcessentials.warps.others"
        const val SET = "inmcessentials.warps.set"
        const val ADMIN = "inmcessentials.warps.admin"
        const val ALL = "inmcessentials.warps.all"
        const val CUSTOM_LOCATION = "inmcessentials.warps.customloc"

        private const val KIND = "워프"
        private const val PROBE = "ess-verify-warp"

        /** CMI 워프 원문 중 우리가 읽은 열쇠(소문자) — 나머지는 보고서에 "옮기지 않음" 으로. */
        private val KNOWN_RAW = setOf("name", "loc", "location", "group", "reqpermission", "permission", "icon", "item")

        val MESSAGES: Map<String, String> = linkedMapOf(
            "warps-none" to "<gray>워프가 없습니다.</gray>",
            "warps-not-found" to "<red>'{워프}' 워프가 없습니다.</red>",
            "warps-no-access" to "<red>{워프} 워프를 쓸 권한이 없습니다.</red>",
            "warps-teleported" to "<green>워프 <white>{워프}</white>(으)로 이동했습니다.</green>",
            "warps-sent" to "<green>{대상}님을 워프 <white>{워프}</white>(으)로 보냈습니다.</green>",
            "warps-teleport-failed" to "<red>워프 {워프}(으)로 이동하지 못했습니다.</red>",
            "warps-world-missing" to "<red>워프 {워프} 의 월드({월드})가 없습니다.</red>",
            "warps-console-target" to "<red>콘솔에서는 보낼 사람을 적으세요: /워프 [이름] [플레이어]</red>",
            "warps-set-usage" to "<yellow>/워프설정 [이름] [true/false 권한] [손|머리|재질] [칸] [그룹:이름] [덮어쓰기]</yellow>",
            "warps-edit-usage" to "<yellow>/워프편집 [이름]</yellow> <gray>— 편집 화면</gray> · <yellow>/워프편집 [이름] [새 이름]</yellow> <gray>— 이름 바꾸기</gray>",
            "warps-invalid-name" to "<red>워프 이름에 쓸 수 없는 글자가 있습니다.</red> <gray>(한글·영문·숫자·_-)</gray>",
            "warps-name-length" to "<red>워프 이름은 {개수}~{값}자입니다.</red>",
            "warps-exists" to "<yellow>'{워프}' 워프가 이미 있습니다.</yellow> <click:run_command:'{명령}'><hover:show_text:'<gray>눌러서 덮어쓰기</gray>'><green>[덮어쓰기]</green></hover></click>",
            "warps-rename-exists" to "<red>'{워프}' 이름의 워프가 이미 있습니다.</red>",
            "warps-limit" to "<red>워프를 더 만들 수 없습니다(최대 {개수}개).</red>",
            "warps-not-yours" to "<red>{워프} 은(는) 내가 만든 워프가 아닙니다.</red>",
            "warps-location-invalid" to "<red>위치를 읽지 못했습니다: {값}</red> <gray>(월드;x;y;z;yaw;pitch — 그 월드가 열려 있어야 합니다)</gray>",
            "warps-slot-invalid" to "<red>칸 번호는 1~{값} 입니다.</red>",
            "warps-slot-taken" to "<red>{값}번 칸은 워프 {다른워프} 이(가) 쓰고 있습니다.</red>",
            "warps-icon-invalid" to "<red>'{값}' 은(는) 아이콘으로 쓸 수 없습니다.</red> <gray>(재질 이름 · 손 · 머리 · 머리:이름)</gray>",
            "warps-icon-hand-empty" to "<red>손에 아이콘으로 쓸 아이템을 드세요.</red>",
            "warps-set" to "<green>워프 <white>{워프}</white> 을(를) 만들었습니다.</green>",
            "warps-updated" to "<green>워프 <white>{워프}</white> 을(를) 고쳤습니다.</green>",
            "warps-permission-hint" to "<gray>이 워프는 권한이 필요합니다: <white>{값}</white></gray>",
            "warps-removed" to "<green>워프 {워프} 을(를) 지웠습니다.</green>",
            "warps-renamed" to "<green>워프 이름을 바꿨습니다: {값} → <white>{워프}</white></green>",
            "warps-moved" to "<green>워프 {워프} 을(를) 여기로 옮겼습니다.</green>",
            "warps-icon-set" to "<green>워프 {워프} 의 아이콘을 {값}(으)로 바꿨습니다.</green>",
            "warps-icon-reset" to "<green>워프 {워프} 의 아이콘을 기본으로 되돌렸습니다.</green>",
            "warps-group-set" to "<green>워프 {워프} 의 그룹: <white>{그룹}</white></green>",
            "warps-group-renamed" to "<green>그룹 이름을 바꿨습니다: {값} → <white>{그룹}</white> <gray>(워프 {개수}개)</gray></green>",
            "warps-group-cleared" to "<yellow>그룹 {그룹} 을(를) 풀었습니다.</yellow> <gray>(워프 {개수}개)</gray>",
            "warps-extra-added" to "<green>워프 {워프} 에 추가 위치를 더했습니다 — 모두 {개수}곳.</green>",
            "warps-extra-cleared" to "<yellow>워프 {워프} 의 추가 위치를 모두 지웠습니다.</yellow>",
            "warps-list" to "<gold>워프({그룹}) {개수}개:</gold> {목록}",
            "warps-list-entry" to "<click:run_command:'{명령}'><hover:show_text:'<gray>{값}</gray>'><white>{워프}</white></hover></click>",
            "warps-list-entry-locked" to "<hover:show_text:'<red>쓸 권한이 없습니다</red>'><dark_gray>{워프}</dark_gray></hover>",
            "warps-list-separator" to "<dark_gray>, </dark_gray>",
            "warps-remove-pick" to "<yellow>지울 워프를 누르세요:</yellow> {목록}",
            "warps-remove-entry" to "<click:run_command:'{명령}'><hover:show_text:'<red>눌러서 지우기</red>'><red>{워프}</red></hover></click>",
            "warps-groups-header" to "<gold>워프 그룹 {개수}개:</gold>",
            "warps-groups-entry" to "<gray>{순위}.</gray> <click:run_command:'{명령}'><white>{그룹}</white></click> <gray>({개수})</gray>",
            "warps-groups-none" to "<gray>워프 그룹이 없습니다.</gray> <gray>/워프설정 이름 그룹:이름</gray>",
        )
    }
}

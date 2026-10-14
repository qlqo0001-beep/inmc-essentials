package com.inmc.essentials.portal

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.verify.Check
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Minecart
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.permissions.PermissionDefault

/**
 * 포탈(2026-10-10) — 카탈로그 cfg3:cfg-portals-custom(CMI `/cmi portals` · config `Portals` · `Saves/Portals.yml`).
 * 영역(WorldEdit 선택 또는 `/포탈 위치1·2`) + 들어서면 이동(`ess.teleporter`, PORTAL)·명령·효과, 권한·쿨타임·밀어내기·파티클.
 * 저장은 `portals.yml`(원자적), 설정은 `settings/portals.yml`, 운영 문서 `docs/portals.md`. DB 는 쓰지 않는다.
 */
class PortalsFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "portals"

    override val hasSettings = true

    override val permissions = listOf(
        Perm(USE, PermissionDefault.TRUE, "포탈 쓰기(들어서면 이동·명령)"),
        Perm(ADMIN, PermissionDefault.OP, "/포탈 — 만들기·고치기·지우기·편집 화면(콘솔 명령을 더하고 고치는 것은 .console 이 더 있어야)"),
        Perm(CONSOLE, PermissionDefault.OP, "포탈에 콘솔 명령 더하기·고치기 — 콘솔 권한과 같다(빌더에게 주지 말 것)"),
        Perm(BYPASS, PermissionDefault.OP, "포탈마다의 권한·쿨타임 무시"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: PortalSettings = PortalSettings()
        private set

    internal lateinit var store: PortalStore
    internal lateinit var service: PortalService

    /** 파티클 반복 작업이 돈 횟수 — `particle-seconds` 주기를 벽시계가 아니라 이것으로(렉에 1초가 밀려도 건너뛰거나 두 번 뿌리지 않게). */
    private var particleRuns = 0L

    override fun load(settings: YamlConfiguration) {
        val read = PortalSettings.from(settings)
        // 새 포탈의 기본 파티클이 없는 이름·데이터가 필요한 것이면 PORTAL 로(뿌릴 때 몰래 바뀌지 않게 — 켤 때 한 번 알린다).
        val type = read.defaults.particles.type
        val resolved = runCatching { PortalRules.particleName(type, PortalService.usableParticles()) }.getOrNull()
        this.settings = if (resolved == null) {
            runCatching { ess.logger.warning("settings/portals.yml 의 defaults.particles.type '$type' 은(는) 쓸 수 없는 파티클입니다 — PORTAL 로 씁니다") }
            read.copy(defaults = read.defaults.copy(particles = read.defaults.particles.copy(type = "PORTAL")))
        } else read.copy(defaults = read.defaults.copy(particles = read.defaults.particles.copy(type = resolved)))
    }

    override fun enable() {
        store = PortalStore(ess) { settings.maxVolume }
        store.loadNow()
        service = PortalService(ess, this, store)
        ess.listen(PortalListener(service))
        PortalCommands(ess, service).register()
        ess.ticks.add("portals-particles", 1) { _ ->
            particleRuns++
            if (particleRuns % settings.particleSeconds == 0L) service.particles()
        }
        ess.ticks.add("portals-prune", 60) { now -> service.prune(now) }
    }

    override fun ready() {
        // /reload 로 이미 접속한 사람 — 서 있는 포탈을 들어선 것으로 적어 둔다(다음 걸음에 쏘지 않게).
        for (player in Bukkit.getOnlinePlayers()) service.mark(player, player.location)
    }

    // --- CMI 가져오기 ---------------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::store.isInitialized) return report.skipped(KIND, "포탈 기능이 켜지지 않았습니다")
        val yaml = data.portals ?: return report.note("CMI Saves/Portals.yml 이 비어 있어 가져올 포탈이 없습니다")
        store.locked?.let { return report.skipped(KIND, "portals.yml 이 깨져 있어(YAML 오류 — $it) 가져오지 않습니다. 파일을 고치고 /포탈 다시읽기 뒤 다시 가져오세요") }
        val read = PortalCodec.fromCmi(yaml, settings.maxVolume, PortalService.usableParticles())
        for (line in read.unreadable) report.unreadable(KIND, line)
        for (line in read.notes) report.note(line)
        val added = ArrayList<Portal>()
        for (portal in read.portals) {
            report.read(KIND)
            if (store.get(portal.name) != null || added.any { it.key == portal.key }) {
                report.skipped(KIND, "포탈 '${portal.name}' 이(가) 이미 있습니다")
                continue
            }
            store.rejectedName(portal.name)?.let {
                report.skipped(KIND, "포탈 '${portal.name}' — portals.yml 에 같은 이름의 못 읽은 칸 '$it' 이(가) 있습니다(덮지 않습니다)")
                continue
            }
            // 겹치면 이름순 첫째만 쏜다 — 만들기와 같은 규칙으로 막는다(있는 포탈 · 이번에 넣는 포탈 둘 다).
            val overlap = service.collision(portal.box) ?: added.firstOrNull { it.box.intersects(portal.box) }
            if (overlap != null) {
                report.skipped(KIND, "포탈 '${portal.name}' 이(가) '${overlap.name}' 과(와) 겹칩니다(${portal.box.describe()}) — 영역을 고친 뒤 다시 만드세요")
                continue
            }
            if (portal.permission) report.note("포탈 '${portal.name}' 은(는) 권한이 필요합니다 — CMI 의 cmi.command.portal.${portal.key} 대신 ${PortalRules.node(portal)} 를 주세요")
            if (portal.commands.any { c ->
                    PortalRules.parseCommand(c)?.command?.lowercase()?.startsWith("cmi ") == true || c.lowercase().let { it.contains("!cmi ") || it.contains("! cmi ") || it.contains("! /cmi ") }
                }) {
                report.note("포탈 '${portal.name}' 의 명령에 CMI 명령이 있습니다 — CMI 를 빼면 실패합니다. /포탈 명령 ${portal.name} 으로 고치세요")
            }
            if (Bukkit.getWorld(portal.box.world) == null) report.note("포탈 '${portal.name}' 의 월드 '${portal.box.world}' 가 지금 없습니다 — 열리면 씁니다")
            added += portal
            report.imported(KIND)
        }
        store.putAll(added)
    }

    // --- 검증기 ----------------------------------------------------------------------------------------

    /** 검증용 포탈 — 검사자 바로 앞 칸(서 있는 블록에서 +X 로 3칸 떨어진 1×2×1). 메모리에만. */
    private fun probe(p: org.bukkit.entity.Player, change: (Portal) -> Portal = { it }): Portal {
        val b = p.location.block
        val box = PortalBox(p.world.name, b.x + 3, b.y, b.z, b.x + 3, b.y + 1, b.z)
        return change(Portal(PROBE, box, effects = emptyList(), sound = null, cooldown = 0, commands = listOf("player:ess-verify-portal {player}")))
    }

    /** [p] 를 포탈 밖 → 안으로 걷게 하는 진짜 걷기 사건. */
    private fun walkIn(p: org.bukkit.entity.Player, portal: Portal): PlayerMoveEvent {
        val world = p.world
        val from = Location(world, portal.box.minX - 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5, p.location.yaw, p.location.pitch)
        val to = Location(world, portal.box.minX + 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5, p.location.yaw, p.location.pitch)
        val event = PlayerMoveEvent(p, from, to)
        Bukkit.getPluginManager().callEvent(event)
        return event
    }

    private fun <T> withProbe(p: org.bukkit.entity.Player, portal: Portal, block: () -> T): T {
        val before = store.data
        val entered = service.lastEntered[p.uniqueId]
        try {
            store.swap(before.copy(portals = before.portals.filter { it.key != portal.key } + portal))
            service.capture = ArrayList()
            service.mark(p, p.location.clone().add(0.0, 1000.0, 0.0)) // "포탈 밖" 으로 적어 둔다
            service.lastEntered.remove(p.uniqueId)
            return block()
        } finally {
            service.capture = null
            store.swap(before)
            if (entered != null) service.lastEntered[p.uniqueId] = entered else service.lastEntered.remove(p.uniqueId)
            service.mark(p, p.location)
        }
    }

    override fun checks(): List<Check> = listOf(
        Check("portals.yml 을 다 읽었다") { _ ->
            val rejected = store.data.rejected
            when {
                store.locked != null -> "portals.yml 이 YAML 로 깨져 있습니다(저장 잠김 — 포탈이 쓰이지 않거나 옛것입니다): ${store.locked}"
                rejected.isNotEmpty() -> "읽지 못한 포탈 ${rejected.size} 칸: " + rejected.keys.joinToString("; ") { "$it — ${store.data.reasons[it] ?: "?"}" }
                else -> null
            }
        },
        Check("청크 색인이 포탈을 찾는다(메모리만)") { p ->
            val portal = probe(p)
            withProbe(p, portal) {
                val inside = Location(p.world, portal.box.minX + 0.5, portal.box.minY + 1.5, portal.box.minZ + 0.5)
                val outside = Location(p.world, portal.box.minX - 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5)
                when {
                    store.at(inside)?.key != portal.key -> "포탈 안의 블록에서 포탈을 못 찾았습니다(청크 색인)"
                    store.at(outside)?.key == portal.key -> "포탈 밖의 블록이 포탈로 잡힙니다"
                    else -> null
                }
            }
        },
        Check("들어서면 명령이 돈다 — 진짜 걷기 사건(명령은 실행하지 않고 모은다)") { p ->
            val portal = probe(p)
            withProbe(p, portal) {
                val event = walkIn(p, portal)
                val expected = service.canUse(p, portal)
                val ran = service.capture.orEmpty()
                when {
                    event.isCancelled && expected -> "쓸 수 있는데 걷기가 취소됐습니다(다른 플러그인이 막았을 수도 있습니다)"
                    !expected -> null // 권한 없는 검사자 — 아래 검사가 본다
                    service.lastEntered[p.uniqueId] != portal.key -> "들어서기를 못 알아봤습니다(PortalListener.onMove)"
                    ran != listOf("player:ess-verify-portal ${p.name}") -> "명령이 기대와 다릅니다: $ran"
                    else -> null
                }
            }
        },
        Check("안에서 걸어도 다시 쏘지 않는다(메모리만)") { p ->
            val portal = probe(p)
            withProbe(p, portal) {
                walkIn(p, portal)
                service.capture?.clear()
                // 포탈 안의 아래 칸 → 위 칸(같은 포탈 안의 걸음).
                val a = Location(p.world, portal.box.minX + 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5)
                val b = Location(p.world, portal.box.minX + 0.5, portal.box.minY + 1.0, portal.box.minZ + 0.5)
                Bukkit.getPluginManager().callEvent(PlayerMoveEvent(p, a, b))
                if (service.capture.orEmpty().isNotEmpty()) "같은 포탈 안의 걸음에서 명령이 또 돌았습니다" else null
            }
        },
        Check("권한이 없으면 걷기를 취소(밀어내기)하고 쏘지 않는다 — 검사자의 권한을 메모리에서 잠깐 뺀다") { p ->
            val portal = probe(p) { it.copy(permission = true, kickBack = true, informPermission = false) }
            // op 는 bypass·포탈 노드를 저절로 가지므로 붙임(attachment)으로 잠깐 false — 메모리만, finally 에서 뗀다.
            val attachment = p.addAttachment(ess.plugin)
            try {
                attachment.setPermission(BYPASS, false)
                attachment.setPermission(PortalRules.node(portal), false)
                withProbe(p, portal) {
                    val event = walkIn(p, portal)
                    when {
                        service.canUse(p, portal) -> "권한을 뺐는데 쓸 수 있다고 봅니다(canUse 가 포탈 노드를 안 봄)"
                        service.capture.orEmpty().isNotEmpty() -> "권한이 없는데 명령이 돌았습니다: ${service.capture}"
                        !event.isCancelled -> "권한이 없는데 걷기를 취소(밀어내기)하지 않았습니다(PortalListener.onMoveGuard)"
                        service.insideOf(p) == portal.key -> "막혔는데 포탈 안에 선 것으로 적었습니다"
                        else -> null
                    }
                }
            } finally {
                p.removeAttachment(attachment)
            }
        },
        Check("권한이 있으면 그 포탈 노드로 쓴다(메모리만)") { p ->
            val portal = probe(p) { it.copy(permission = true, kickBack = true, informPermission = false) }
            val attachment = p.addAttachment(ess.plugin)
            try {
                attachment.setPermission(BYPASS, false)
                attachment.setPermission(USE, true)
                attachment.setPermission(PortalRules.node(portal), true)
                withProbe(p, portal) {
                    val event = walkIn(p, portal)
                    when {
                        event.isCancelled -> "권한이 있는데 걷기가 취소됐습니다(다른 플러그인이 막았을 수도 있습니다)"
                        service.capture.orEmpty() != listOf("player:ess-verify-portal ${p.name}") -> "권한이 있는데 명령이 다릅니다: ${service.capture}"
                        else -> null
                    }
                }
            } finally {
                p.removeAttachment(attachment)
            }
        },
        Check("한 걸음에 1블록 넘게 가도 얇은 포탈을 놓치지 않는다(메모리만)") { p ->
            val portal = probe(p) { it.copy(box = it.box.copy(maxY = it.box.minY)) } // 1×1×1
            withProbe(p, portal) {
                val world = p.world
                val from = Location(world, portal.box.minX - 0.7, portal.box.minY.toDouble(), portal.box.minZ + 0.5, p.location.yaw, p.location.pitch)
                val to = Location(world, portal.box.minX + 2.3, portal.box.minY.toDouble(), portal.box.minZ + 0.5, p.location.yaw, p.location.pitch)
                val event = PlayerMoveEvent(p, from, to)
                Bukkit.getPluginManager().callEvent(event)
                when {
                    event.isCancelled && service.canUse(p, portal) -> "걷기가 취소됐습니다(다른 플러그인이 막았을 수도 있습니다)"
                    !service.canUse(p, portal) -> null
                    service.capture.orEmpty() != listOf("player:ess-verify-portal ${p.name}") -> "포탈을 건너뛰었습니다(끝 블록만 봄): ${service.capture}"
                    service.insideOf(p) == portal.key -> "지나간 뒤인데 포탈 안에 선 것으로 적었습니다"
                    else -> null
                }
            }
        },
        Check("다른 플러그인이 취소한 걷기에서는 쏘지 않는다(메모리만)") { p ->
            val portal = probe(p)
            withProbe(p, portal) {
                val world = p.world
                val from = Location(world, portal.box.minX - 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5, p.location.yaw, p.location.pitch)
                val to = Location(world, portal.box.minX + 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5, p.location.yaw, p.location.pitch)
                val event = PlayerMoveEvent(p, from, to)
                event.isCancelled = true // 보호 플러그인이 HIGH 까지 막은 걷기
                Bukkit.getPluginManager().callEvent(event)
                when {
                    service.capture.orEmpty().isNotEmpty() -> "취소된 걷기에서 명령이 돌았습니다: ${service.capture}"
                    service.insideOf(p) == portal.key -> "취소된 걷기인데 포탈 안에 선 것으로 적었습니다"
                    else -> null
                }
            }
        },
        Check("꺼진 포탈은 아무것도 하지 않는다(메모리만)") { p ->
            val portal = probe(p) { it.copy(enabled = false) }
            withProbe(p, portal) {
                val event = walkIn(p, portal)
                if (service.capture.orEmpty().isNotEmpty() || event.isCancelled) "꺼진 포탈이 명령을 돌렸거나 걷기를 막았습니다" else null
            }
        },
        Check("순간이동으로 포탈 안에 도착하면 쏘지 않는다 — 진짜 순간이동·걷기 사건(메모리만)") { p ->
            val portal = probe(p)
            withProbe(p, portal) {
                val world = p.world
                val a = Location(world, portal.box.minX + 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5, p.location.yaw, p.location.pitch)
                val b = Location(world, portal.box.minX + 0.5, portal.box.minY + 1.0, portal.box.minZ + 0.5, p.location.yaw, p.location.pitch)
                // 실제로 옮기지 않는다 — 사건만(MONITOR 가 "이미 안에 있음" 으로 적는지).
                val teleport = PlayerTeleportEvent(p, p.location, a, PlayerTeleportEvent.TeleportCause.PLUGIN)
                Bukkit.getPluginManager().callEvent(teleport)
                if (teleport.isCancelled) return@withProbe null // 다른 플러그인이 이 순간이동을 막았다 — 판정할 수 없다
                Bukkit.getPluginManager().callEvent(PlayerMoveEvent(p, a, b))
                when {
                    service.insideOf(p) != portal.key -> "포탈 안으로 순간이동했는데 안에 선 것으로 적지 않았습니다(PortalListener.onTeleport)"
                    service.capture.orEmpty().isNotEmpty() -> "도착한 포탈 안에서 걸었는데 쐈습니다(연쇄): ${service.capture}"
                    else -> null
                }
            }
        },
        Check("쿨타임 안에 다시 들어서면 쏘지 않는다 — 검사자의 bypass 를 메모리에서 잠깐 뺀다") { p ->
            val portal = probe(p) { it.copy(cooldown = 60) }
            val attachment = p.addAttachment(ess.plugin)
            try {
                attachment.setPermission(BYPASS, false)
                attachment.setPermission(USE, true)
                withProbe(p, portal) {
                    try {
                        service.forgetUse(p, portal.key) // 앞 검사들이 같은 검증용 포탈을 지나며 남긴 사용 기록 — 두면 첫 진입부터 쿨타임에 걸린다
                        walkIn(p, portal)
                        service.mark(p, p.location.clone().add(0.0, 1000.0, 0.0)) // 나갔다
                        walkIn(p, portal)
                        val ran = service.capture.orEmpty()
                        if (ran.size != 1) "쿨타임 60초인데 두 번 들어서서 명령이 ${ran.size}번 돌았습니다: $ran" else null
                    } finally {
                        service.forgetUse(p, portal.key)
                    }
                }
            } finally {
                p.removeAttachment(attachment)
            }
        },
        Check("탈것을 탄 채 들어서도 쏜다 — 진짜 탈것 움직임 사건(검사자를 잠깐 수레에 태웠다 내린다)") { p ->
            if (p.isInsideVehicle || p.gameMode == GameMode.SPECTATOR) return@Check null // 이미 무언가를 탔거나 관전 — 판정하지 않는다
            val portal = probe(p)
            val cart = p.world.spawn(p.location, Minecart::class.java) { it.isPersistent = false }
            try {
                if (!cart.addPassenger(p)) return@Check null // 다른 플러그인이 태우기를 막았다 — 판정할 수 없다
                withProbe(p, portal) {
                    val world = p.world
                    val from = Location(world, portal.box.minX - 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5)
                    val to = Location(world, portal.box.minX + 0.5, portal.box.minY.toDouble(), portal.box.minZ + 0.5)
                    Bukkit.getPluginManager().callEvent(VehicleMoveEvent(cart, from, to))
                    when {
                        !service.canUse(p, portal) -> null
                        service.capture.orEmpty() != listOf("player:ess-verify-portal ${p.name}") -> "수레를 타고 들어섰는데 명령이 다릅니다(PortalListener.onVehicleMove): ${service.capture}"
                        else -> null
                    }
                }
            } finally {
                cart.eject()
                cart.remove()
            }
        },
    )

    companion object {
        const val USE = "inmcessentials.portals.use"
        const val ADMIN = "inmcessentials.portals.admin"
        const val BYPASS = "inmcessentials.portals.bypass"
        const val CONSOLE = "inmcessentials.portals.console"

        private const val KIND = "포탈"
        private const val PROBE = "ess-verify-portal"

        val MESSAGES: Map<String, String> = linkedMapOf(
            "portals-usage" to listOf(
                "<gold>/포탈</gold> <gray>— 목록 화면</gray>",
                "<gold>/포탈 위치1 · 위치2</gold> <gray>— 선 블록을 모서리로(둘 다 찍으면 WorldEdit 선택보다 먼저, 쓰고 나면 지웁니다)</gray>",
                "<gold>/포탈 만들기 <이름></gold> · <gold>영역 <이름></gold> <gray>— 선택으로 만들기 · 다시 정하기</gray>",
                "<gold>/포탈 목적지 <이름> [여기|월드;x;y;z|지우기]</gold> · <gold>안전위치 <이름> [...]</gold>",
                "<gold>/포탈 켜기|끄기 <이름></gold> · <gold>정보 · 편집 · 삭제 · 이동 <이름> [목적지]</gold> · <gold>가까이</gold>",
                "<gold>/포탈 명령 <이름> [추가 <명령>|삭제 <번호>|비우기]</gold> <gray>— player: 로 시작하면 그 사람이, 아니면 콘솔이</gray>",
                "<gold>/포탈 다시읽기</gold> <gray>— portals.yml 을 다시 읽기</gray>",
            ).joinToString("\n"),
            "portals-no-permission" to "<red>{포탈} 포탈을 쓸 권한이 없습니다.</red>",
            "portals-cooldown" to "<gray>{시간}초 뒤에 다시 쓸 수 있습니다.</gray>",
            "portals-target-missing" to "<red>포탈 {포탈} 의 위치(월드 {월드})가 없습니다.</red>",
            "portals-teleport-failed" to "<red>포탈 {포탈} 로 이동하지 못했습니다.</red>",
            "portals-invalid-name" to "<red>포탈 이름은 한글·영문·숫자·_- 32자까지입니다: {포탈}</red>",
            "portals-exists" to "<red>'{포탈}' 포탈이 이미 있습니다.</red>",
            "portals-not-found" to "<red>'{포탈}' 포탈이 없습니다.</red>",
            "portals-no-selection" to "<red>영역이 없습니다.</red> <gray>WorldEdit 으로 고르거나(//wand) /포탈 위치1 · /포탈 위치2 로 두 모서리를 찍으세요.</gray>",
            "portals-too-big" to "<red>영역이 너무 큽니다: {개수}블록(최대 {값}).</red>",
            "portals-collides" to "<red>{포탈} 포탈과 겹칩니다.</red>",
            "portals-created" to "<green>포탈 <white>{포탈}</white> 을(를) 만들었습니다.</green> <gray>{값} · {출처}</gray>",
            "portals-source-corners" to "/포탈 위치1·2 로 찍은 영역",
            "portals-source-worldedit" to "WorldEdit 선택",
            "portals-out-of-bounds" to "<red>영역이 월드 경계 밖이거나 너무 넓게 걸칩니다(청크 {개수}개):</red> <gray>{값}</gray>",
            "portals-particle-invalid" to "<red>'{값}' 은(는) 포탈에 쓸 수 있는 파티클이 아닙니다 — 그대로 둡니다.</red> <gray>(PORTAL · DUST · END_ROD · WITCH · FLAME … 데이터가 필요한 것은 DUST 만)</gray>",
            "portals-hint-target" to "<gray>목적지가 없습니다 — </gray><click:run_command:'/포탈 목적지 {포탈}'><hover:show_text:'<gray>지금 선 자리를 목적지로'><yellow>[여기를 목적지로]</yellow></hover></click> <click:run_command:'/포탈 편집 {포탈}'><yellow>[편집]</yellow></click>",
            "portals-redefined" to "<green>포탈 {포탈} 의 영역을 바꿨습니다.</green> <gray>{값} · {출처}</gray>",
            "portals-corner" to "<green>모서리 {값}:</green> <white>{월드} {좌표}</white>",
            "portals-target-set" to "<green>포탈 {포탈} 의 목적지: <white>{값}</white></green>",
            "portals-target-cleared" to "<yellow>포탈 {포탈} 의 목적지를 지웠습니다 — 명령만 돕니다.</yellow>",
            "portals-safe-set" to "<green>포탈 {포탈} 의 안전 위치: <white>{값}</white></green>",
            "portals-safe-cleared" to "<yellow>포탈 {포탈} 의 안전 위치를 지웠습니다 — 밀어냅니다.</yellow>",
            "portals-location-invalid" to "<red>위치를 읽지 못했습니다: {값}</red> <gray>(여기 · 월드;x;y;z;yaw;pitch · x y z)</gray>",
            "portals-enabled" to "<green>포탈 {포탈} 을(를) 켰습니다.</green>",
            "portals-disabled" to "<yellow>포탈 {포탈} 을(를) 껐습니다.</yellow>",
            "portals-removed" to "<green>포탈 {포탈} 을(를) 지웠습니다.</green>",
            "portals-none" to "<gray>포탈이 없습니다.</gray> <gray>/포탈 만들기 <이름></gray>",
            "portals-list-header" to "<gold>포탈 {개수}개:</gold>",
            "portals-list-entry" to "<click:run_command:'/포탈 정보 {포탈}'><white>{포탈}</white></click> <gray>{상태} · {영역} → {목적지}</gray>",
            "portals-nearest" to "<gold>가장 가까운 포탈: <white>{포탈}</white></gold> <gray>({값}블록)</gray> <click:run_command:'/포탈 편집 {포탈}'><yellow>[편집]</yellow></click>",
            "portals-info" to listOf(
                "<gold>포탈 <white>{포탈}</white></gold> <gray>({상태})</gray>",
                "<gray>영역:</gray> <white>{영역}</white>",
                "<gray>목적지:</gray> <white>{목적지}</white>",
                "<gray>권한:</gray> <white>{권한}</white> <gray>· 명령 {개수}개 · 쿨타임 {시간}초</gray>",
            ).joinToString("\n"),
            "portals-word-none" to "없음",
            "portals-word-on" to "<green>켜짐</green>",
            "portals-word-off" to "<red>꺼짐</red>",
            "portals-teleported" to "<green>포탈 {포탈} 로 왔습니다.</green>",
            "portals-commands-none" to "<gray>포탈 {포탈} 에 명령이 없습니다.</gray> <gray>/포탈 명령 {포탈} 추가 <명령></gray>",
            "portals-commands-header" to "<gold>포탈 {포탈} 의 명령 {개수}개:</gold>",
            "portals-commands-entry" to "<gray>{순위}.</gray> <white>{값}</white> <click:run_command:'/포탈 명령 {포탈} 삭제 {순위}'><hover:show_text:'<red>눌러서 지우기'><red>[x]</red></hover></click>",
            "portals-command-added" to "<green>포탈 {포탈} 에 명령을 더했습니다({개수}번):</green> <white>{값}</white>",
            "portals-command-removed" to "<green>포탈 {포탈} 의 명령을 지웠습니다:</green> <white>{값}</white>",
            "portals-commands-cleared" to "<yellow>포탈 {포탈} 의 명령을 모두 지웠습니다.</yellow>",
            "portals-command-index" to "<red>명령 번호는 1~{값} 입니다.</red>",
            "portals-effect-invalid" to "<red>읽지 못한 효과(빼고 저장했습니다): {값}</red> <gray>효과:초:레벨</gray>",
            "portals-reloaded" to "<green>portals.yml 을 다시 읽었습니다 — 포탈 {개수}개</green> <gray>(못 읽은 칸 {값})</gray>",
            "portals-reload-failed" to "<red>portals.yml 을 YAML 로 읽지 못했습니다 — 지금 포탈 {개수}개를 그대로 쓰고, 고칠 때까지 저장하지 않습니다.</red> <gray>{값}</gray>",
            "portals-locked" to "<red>portals.yml 이 깨져 있어(YAML 오류) 고칠 수 없습니다 — 저장하면 파일을 덮습니다.</red> <gray>파일을 고치고 /포탈 다시읽기 · {값}</gray>",
            "portals-rejected-exists" to "<red>portals.yml 에 같은 이름의 못 읽은 포탈 '{포탈}' 이(가) 있습니다 — 파일을 먼저 고치세요(서버 로그에 까닭).</red>",
            "portals-console-denied" to "<red>콘솔 명령을 더하거나 바꿀 권한이 없습니다(inmcessentials.portals.console) — player: 를 붙이세요:</red> <white>{값}</white>",
        )
    }
}

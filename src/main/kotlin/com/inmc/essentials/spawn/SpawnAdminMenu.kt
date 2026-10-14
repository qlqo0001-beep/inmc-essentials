package com.inmc.essentials.spawn

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * `/스폰 관리` — 서버 스폰 · 첫 스폰 · 접속할 때 보내기 · 흩는 반경 · 부활 워프 · **부활 순서**(눌러서 앞뒤로 옮기고 끄기).
 * inmc-menu 의 화면을 옮기고 칸을 더했다. `/스폰` 대기·쿨타임은 commands 기능으로 옮겨 여기서는 안내만 한다.
 */
class SpawnAdminMenu(ess: Ess, viewer: Player, private val service: SpawnService) : Menu(ess, viewer, SIZE, "스폰 관리") {

    private val feature: SpawnFeature? get() = ess.feature("spawn") as? SpawnFeature

    override fun draw() {
        clear()
        val c = service.config
        set(SLOT_POINT, Icon.of(Material.BEACON, "<yellow>서버 스폰</yellow>",
            "<gray>지금: <white>${c.point?.describe() ?: "정하지 않음 — 기본 월드의 스폰"}</white></gray>", "",
            "<yellow>▶ 클릭: 내가 선 곳으로 정하기</yellow>", "<yellow>▶ 우클릭: 그곳으로 가 보기</yellow>")) { event ->
            if (event.isRightClick) {
                viewer.closeInventory()
                ess.teleporter.teleport(viewer, service.target(viewer), TeleportKind.ADMIN)
                return@set
            }
            setSpawnHere(ess, service, viewer)
            refresh()
        }
        set(SLOT_FIRST_POINT, Icon.of(Material.RED_BED, "<yellow>첫 스폰</yellow>",
            "<gray>처음 들어온 사람이 서는 곳.</gray>",
            "<gray>지금: <white>${c.firstPoint?.describe() ?: "따로 없음 — 서버 스폰"}</white></gray>", "",
            "<yellow>▶ 클릭: 내가 선 곳으로 정하기</yellow>", "<yellow>▶ 우클릭: 그곳으로 가 보기</yellow>",
            "<yellow>▶ Shift+클릭: 지우기(서버 스폰을 씀)</yellow>")) { event ->
            when {
                event.isShiftClick -> {
                    service.update { it.copy(firstPoint = null) }
                    ess.messages.send(viewer, "spawn-first-cleared")
                    refresh()
                }
                event.isRightClick -> {
                    val to = service.firstTarget(viewer.uniqueId) ?: service.target(viewer)
                    viewer.closeInventory()
                    ess.teleporter.teleport(viewer, to, TeleportKind.ADMIN)
                }
                else -> {
                    val point = SpawnPoint.of(viewer.location)
                    service.update { it.copy(firstPoint = point) }
                    ess.messages.send(viewer, "spawn-first-set", Ph.of().value(point.describe()))
                    refresh()
                }
            }
        }
        set(SLOT_FIRST, toggleIcon("처음 접속 → 첫 스폰", c.firstJoin, "<gray>처음 들어온 사람을 첫 스폰에서 시작하게.</gray>")) {
            service.update { it.copy(firstJoin = !it.firstJoin) }
            refresh()
        }
        set(SLOT_EVERY, toggleIcon("매 접속 → 서버 스폰", c.everyJoin, "<gray>들어올 때마다 서버 스폰에서.</gray>", "<gray>끄면 나간 자리에서.</gray>",
            "<dark_gray>우회 권한: ${SpawnFeature.JOIN_BYPASS}</dark_gray>",
            "<dark_gray>뺄 월드: settings/spawn.yml ignored-worlds</dark_gray>")) {
            service.update { it.copy(everyJoin = !it.everyJoin) }
            refresh()
        }
        set(SLOT_RADIUS, valueIcon(Material.COMPASS, "흩는 반경", if (c.radius > 0) "${c.radius}블록" else "없음",
            "<gray>/스폰·부활(서버 스폰) 때 서버 스폰 둘레의</gray>", "<gray>아무 곳에 내려 줍니다(땅 위로).</gray>")) {
            ask(DialogForm("<yellow>흩는 반경</yellow>").long("radius", "반경(블록, 0 = 그 자리)", c.radius.toLong(), 0, SpawnConfig.MAX_RADIUS.toLong())) { v ->
                val radius = (v.long("radius") ?: c.radius.toLong()).toInt().coerceIn(0, SpawnConfig.MAX_RADIUS)
                service.update { it.copy(radius = radius) }
            }
        }
        set(SLOT_TIMING, Icon.of(Material.CLOCK, "<yellow>/스폰 대기·쿨타임·비용</yellow>",
            "<gray>명령 공통 설정으로 옮겼습니다 —</gray>",
            "<gray>commands 기능 설정에서 명령 이름 <white>스폰</white> 에 겁니다.</gray>",
            "<dark_gray>(움직이거나 맞으면 취소 · 성공해야 쿨타임)</dark_gray>"))
        val warpName = c.respawnWarp.ifBlank { "정하지 않음" }
        // 지워졌거나 이름이 바뀌었거나 월드가 없으면 빨갛게 — 부활 때는 그 칸을 건너뛴다.
        val warpProblem = when {
            c.respawnWarp.isBlank() -> null
            feature?.warps == null -> "<red>워프 기능이 꺼져 있어 건너뜁니다</red>"
            feature?.warps?.warp(c.respawnWarp) == null -> "<red>워프가 없습니다(지웠거나 월드가 없음) — 건너뜁니다</red>"
            else -> null
        }
        set(SLOT_WARP, valueIcon(Material.ENDER_PEARL, "부활 워프", warpName,
            *listOfNotNull(warpProblem, "<gray>부활 순서의 '부활 워프' 칸이 보낼 워프.</gray>", "<gray>권한·숨김과 무관하게 보냅니다.</gray>",
                "<yellow>▶ Shift+클릭: 지우기</yellow>").toTypedArray())) { event ->
            if (event.isShiftClick) {
                service.update { it.copy(respawnWarp = "") }
                ess.messages.send(viewer, "spawn-warp-cleared")
                refresh()
                return@set
            }
            val names = feature?.warps?.names().orEmpty()
            val form = DialogForm("<yellow>부활 워프</yellow>")
            if (names.isNotEmpty()) form.line("<gray>워프: <white>${names.take(30).joinToString(", ")}</white></gray>")
            ask(form.text("warp", "워프 이름", c.respawnWarp, 64)) { v ->
                val name = v.text("warp").trim()
                if (name.isEmpty()) return@ask
                val warps = feature?.warps
                if (warps == null || warps.warp(name) == null) return@ask ess.messages.send(viewer, "spawn-warp-missing", Ph.of().raw("{워프}", name))
                val exact = warps.names().firstOrNull { it.equals(name, ignoreCase = true) } ?: name
                service.update { it.copy(respawnWarp = exact) }
                ess.messages.send(viewer, "spawn-warp-set", Ph.of().raw("{워프}", exact))
            }
        }

        val s = feature?.settings ?: SpawnSettings()
        set(SLOT_ORDER_INFO, Icon.of(Material.BOOK, "<aqua>부활 순서</aqua>",
            "<gray>죽으면 왼쪽부터 처음 되는 곳으로 부활합니다.</gray>",
            "<gray>꺼진 것은 건너뛰고, 다 안 되면 바닐라 그대로.</gray>",
            "<gray>엔드에서 돌아올 때는 건드리지 않습니다.</gray>", "",
            "<gray>부활 처리: ${Icon.toggle(s.handleRespawn)} <dark_gray>(settings/spawn.yml handle-respawn)</dark_gray></gray>",
            "<gray>접속 무적 <white>${s.joinInvulnerable}초</white> · 부활 무적 <white>${s.respawnInvulnerable}초</white></gray>", "",
            "<red>CMI 등 다른 플러그인의 스폰·부활 처리는 꺼 두세요.</red>"))
        for ((index, step) in c.respawn.withIndex()) {
            val material = when (step.source) {
                Source.BED -> Material.RED_BED
                Source.ANCHOR -> Material.RESPAWN_ANCHOR
                Source.SERVER -> Material.BEACON
                Source.BED_HOME -> Material.LIGHT_BLUE_BED
                Source.HOME -> Material.OAK_DOOR
                Source.WARP -> Material.ENDER_PEARL
                Source.WORLD -> Material.GRASS_BLOCK
            }
            val extra = when (step.source) {
                Source.WARP -> listOfNotNull("<gray>워프: <white>$warpName</white></gray>", warpProblem)
                Source.BED_HOME, Source.HOME -> if (feature?.homes == null) listOf("<red>홈 기능이 꺼져 있어 건너뜁니다</red>") else emptyList()
                else -> emptyList()
            }
            val stack = Icon.of(material, "<white>${index + 1}. ${step.source.label}</white> " + Icon.toggle(step.enabled),
                listOf("<gray>${step.source.description}</gray>") + extra + listOf("",
                    "<yellow>▶ 클릭: 앞으로</yellow>", "<yellow>▶ 우클릭: 뒤로</yellow>", "<yellow>▶ Shift+클릭: 켜기/끄기</yellow>"))
            if (step.enabled) stack.editMeta { it.setEnchantmentGlintOverride(true) }
            set(SLOT_ORDER + index, stack) { event ->
                service.update {
                    when {
                        event.isShiftClick -> it.toggle(index)
                        event.isRightClick -> it.move(index, +1)
                        else -> it.move(index, -1)
                    }
                }
                refresh()
            }
        }
        fillEmpty(Icon.FILLER)
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SIZE = 36
        const val SLOT_POINT = 10
        const val SLOT_FIRST_POINT = 11
        const val SLOT_FIRST = 12
        const val SLOT_EVERY = 13
        const val SLOT_RADIUS = 14
        const val SLOT_TIMING = 15
        const val SLOT_WARP = 16
        const val SLOT_ORDER_INFO = 18
        /** 부활 순서 7칸 — 19..25. */
        const val SLOT_ORDER = 19
        const val SLOT_CLOSE = 35
    }
}

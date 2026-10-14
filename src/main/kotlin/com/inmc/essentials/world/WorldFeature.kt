package com.inmc.essentials.world

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.verify.Check
import kr.inmc.core.util.AtomicFiles
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Item
import org.bukkit.entity.Wolf
import org.bukkit.entity.Zombie
import org.bukkit.entity.minecart.RideableMinecart
import org.bukkit.entity.minecart.StorageMinecart
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault
import java.io.File

/**
 * 월드·날씨·시간·바이옴 — `/바이옴찾기` · `/게임규칙` · `/청크복구` · `/조명수정` · `/몹제거` · `/날씨` · `/시간`, 그리고 설정만 있는
 * 월드 제한 · 잠자기 밤 가속 · 네더 포탈 제한 · 몹 탈것 막기.
 *
 * CMI 항목: cmd-findbiome · cmd-gamerule · cmd-fixchunk · cmd-lfix · mod-worldlimits · cmd-killall · cmd-weather · cmd-time ·
 * sleep-night-speedup · cfg-netherportal-limit · prevent-entity-boat. 운영 문서 `docs/world.md`. DB 는 쓰지 않는다(상태는 게임 규칙·설정 파일).
 */
class WorldFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "world"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(FINDBIOME, PermissionDefault.OP, "/바이옴찾기 — 가장 가까운 생물군계로 이동"),
        Perm(FINDBIOME_STOPALL, PermissionDefault.OP, "/바이옴찾기 전부멈춤"),
        Perm(GAMERULE, PermissionDefault.OP, "/게임규칙"),
        Perm(FIXCHUNK, PermissionDefault.OP, "/청크복구"),
        Perm(LFIX, PermissionDefault.OP, "/조명수정"),
        Perm(LFIX_OTHERS, PermissionDefault.OP, "/조명수정 <범위> <플레이어>"),
        Perm(KILLALL, PermissionDefault.OP, "/몹제거"),
        Perm(KILLALL_FORCE, PermissionDefault.OP, "/몹제거 -force — 이름·길들임·끈·커스텀 몹 보호 무시"),
        Perm(WEATHER, PermissionDefault.OP, "/날씨 — 바꾸기·고정"),
        Perm(WEATHER_CHECK, PermissionDefault.TRUE, "/날씨 — 지금 날씨 보기"),
        Perm(TIME, PermissionDefault.OP, "/시간 — 바꾸기·멈춤·현실 시간"),
        Perm(TIME_CHECK, PermissionDefault.TRUE, "/시간 — 지금 시간 보기"),
        Perm(LIMIT_GAMEMODE_BYPASS, PermissionDefault.OP, "월드 제한 — 게임모드를 바꾸지 않음"),
        Perm(LIMIT_FLY_BYPASS, PermissionDefault.OP, "월드 제한 — 비행 금지 월드에서도 비행"),
        Perm(LIMIT_GOD_BYPASS, PermissionDefault.OP, "월드 제한 — 무적 금지 월드에서도 무적"),
        Perm(LIMIT_ELYTRA_BYPASS, PermissionDefault.OP, "월드 제한 — 겉날개 금지 월드에서도 활공"),
        Perm(LIMIT_ABOVE_ROOF, PermissionDefault.OP, "월드 제한 — 지붕 높이 위로 비행"),
        Perm(SLEEP_IGNORE, PermissionDefault.FALSE, "잠자기 밤 가속의 인원에서 빠짐"),
        Perm(PORTAL_BYPASS, PermissionDefault.OP, "네더 포탈 생성 금지·크기 제한 무시"),
    )

    override val messageDefaults: Map<String, String> = linkedMapOf(
        // --- 공통 ---
        "world-on" to "<green>켬</green>",
        "world-off" to "<red>끔</red>",
        "world-all-worlds" to "모든 월드",
        "world-unknown-world" to "<red>'{값}' 은(는) 없는 월드입니다.</red>",
        "world-need-world" to "<red>월드를 적어 주세요.</red>",
        "world-need-player" to "<red>플레이어를 적어 주세요.</red>",
        "world-gamemode-survival" to "서바이벌",
        "world-gamemode-creative" to "크리에이티브",
        "world-gamemode-adventure" to "어드벤처",
        "world-gamemode-spectator" to "관전",
        // --- 바이옴 찾기 ---
        "world-biome-usage" to "<red>/바이옴찾기 <생물군계> [-r:반경] [-좌표]</red> <gray>· 멈춤 · 전부멈춤 — 타이틀포지 이름·한국어 이름·영어 id 모두 됩니다</gray>",
        "world-biome-unknown" to "<red>'{값}' 에 맞는 생물군계가 없습니다.</red>",
        "world-biome-ambiguous" to "<yellow>'{값}' 에 여러 생물군계가 맞습니다:</yellow> <gray>{목록}</gray>",
        "world-biome-generator-world" to "<red>{월드} 은(는) 생성기가 붙은 월드라 생물군계를 찾을 수 없습니다.</red>",
        "world-biome-not-in-world" to "<red>{월드} 에는 {이름} 이(가) 생기지 않습니다.</red>",
        "world-biome-already" to "<red>이미 찾는 중입니다.</red> <gray>/바이옴찾기 멈춤</gray>",
        "world-biome-busy" to "<red>지금 {개수}명이 찾는 중입니다. 잠시 뒤에 다시 해 주세요.</red>",
        "world-biome-started" to "<gray>{이름} 을(를) 반경 {값}칸 안에서 찾습니다…</gray>",
        "world-biome-progress" to "<gray>TPS</gray> <white>{tps}</white> <gray>· 남은 칸</gray> <white>{개수}</white> <gray>({시간}) · 속도</gray> <white>{속도}</white><gray>칸/초</gray>",
        "world-biome-not-found" to "<red>{이름} 을(를) 찾지 못했습니다.</red> <gray>(청크 {개수}개 확인)</gray>",
        "world-biome-timeout" to "<red>시간이 다 돼 멈췄습니다 — {이름} 을(를) 찾지 못했습니다.</red> <gray>(청크 {개수}개 확인)</gray>",
        "world-biome-no-spot" to "<red>{이름} 은(는) 찾았지만 설 자리가 없습니다.</red> <gray>(마지막 좌표 {값})</gray>",
        "world-biome-stopped" to "<gray>생물군계 찾기를 멈췄습니다.</gray>",
        "world-biome-stopped-all" to "<gray>찾기 {개수}개를 멈췄습니다.</gray>",
        "world-biome-nothing-to-stop" to "<gray>찾는 중이 아닙니다.</gray>",
        "world-biome-teleported" to "<green>{이름} (으)로 이동했습니다.</green> <gray>({x}, {y}, {z})</gray>",
        "world-biome-found-coords" to "<green>가장 가까운 {이름}:</green> <white>{x}, {y}, {z}</white>",
        "world-biome-deep-dark" to "<dark_red>깊은 어둠입니다 — 워든을 깨우지 않게 조심하세요!</dark_red>",
        "world-biome-teleport-failed" to "<red>{이름} (으)로 이동하지 못했습니다.</red> <gray>({x}, {y}, {z})</gray>",
        "world-biome-error" to "<red>찾는 중 오류가 났습니다: {값}</red>",
        "world-biome-radius-capped" to "<yellow>반경은 최대 {값}칸입니다.</yellow>",
        // --- 게임 규칙 ---
        "world-gamerule-header" to "<gold>{월드} 게임 규칙 {개수}개</gold> <gray>(노란색 = 기본값과 다름)</gray>",
        "world-gamerule-unknown" to "<red>'{값}' 은(는) 없는 게임 규칙입니다.</red>",
        "world-gamerule-value" to "<gray>{월드}</gray> <white>{이름}</white> <gray>({규칙}) =</gray> <yellow>{값}</yellow> <gray>(기본 {기본})</gray>",
        "world-gamerule-bad-value" to "<red>{규칙} 에 '{값}' 을(를) 넣을 수 없습니다.</red>",
        "world-gamerule-updated" to "<green>{월드} 의 게임 규칙 {이름}({규칙}) 을(를) {값}(으)로 바꿨습니다.</green>",
        // --- 청크 복구 ---
        "world-fix-usage" to listOf(
            "<red>/청크복구 [w <월드>] [r <청크 반경|g>] [c <x:z>]</red> <gray>— 지역 파일을 읽기만 해서 손상된 청크를 찾습니다(반경이 없으면 월드 전체)</gray>",
            "<gray>상태 · 일시정지 · 계속 · 멈춤 · 전부멈춤 · 속도 <초당 청크> · 자동속도 <켜기|끄기> · 메시지 <켜기|끄기> · 정보 · 복구</gray>",
            "<gray>복구 = 찾은 청크를 서버가 다시 읽게 해 새로 만들게 합니다(</gray><red>그 청크의 건축은 사라집니다</red><gray>)</gray>",
        ).joinToString("\n"),
        "world-fix-no-region" to "<red>{월드} 의 지역 파일(.mca) 폴더를 찾지 못했습니다.</red> <gray>(Leaf linear 형식이면 검사할 수 없습니다)</gray>",
        "world-fix-already" to "<red>{월드} 은(는) 이미 검사 중입니다.</red> <gray>/청크복구 상태</gray>",
        "world-fix-started" to "<green>{월드} 에서 청크 {개수}개를 검사합니다.</green>",
        "world-fix-paused" to "<yellow>검사를 잠시 멈췄습니다.</yellow> <gray>/청크복구 계속</gray>",
        "world-fix-continuing" to "<green>검사를 계속합니다.</green>",
        "world-fix-stopped" to "<gray>검사를 멈췄습니다 — 찾은 것 {개수}개.</gray> <gray>/청크복구 정보</gray>",
        "world-fix-stopped-all" to "<gray>검사 {개수}개를 멈췄습니다.</gray>",
        "world-fix-none" to "<gray>진행 중인 검사가 없습니다.</gray>",
        "world-fix-pick-world" to "<yellow>검사가 여럿입니다 — w <월드> 를 붙여 주세요.</yellow>",
        "world-fix-bad-center" to "<red>가운데는 x:z 청크 좌표입니다: {값}</red>",
        "world-fix-still-running" to "<red>검사가 끝난 뒤에 복구할 수 있습니다.</red>",
        "world-fix-speed" to "<green>검사 속도를 초당 {값}청크로 바꿨습니다.</green>",
        "world-fix-autospeed" to "<green>자동 속도(TPS 따라 줄이기): {값}</green>",
        "world-fix-messages" to "<green>진행 메시지: {값}</green>",
        "world-fix-no-info" to "<gray>찾은 손상 청크가 없습니다.</gray>",
        "world-fix-progress" to "<gray>TPS</gray> <white>{tps}</white> <gray>· 남은</gray> <white>{개수}</white> <gray>({시간}) · 속도</gray> <white>{속도}</white><gray>/초 · 찾음</gray> <white>{찾음}</white>",
        "world-fix-scan-line" to "<gray>{개수}.</gray> <white>X:{x} Z:{z}</white> <gray>— {사유}</gray>",
        "world-fix-more" to "<gray>…그리고 {개수}개 더</gray>",
        "world-fix-finished" to "<green>{월드} 검사가 끝났습니다 — 손상 {개수}개.</green> <gray>/청크복구 정보 · /청크복구 복구 (</gray><red>청크가 새로 만들어집니다</red><gray>)</gray>",
        "world-fix-unsupported" to "<yellow>압축 방식을 몰라 확인하지 못한 청크 {개수}개는 건너뛰었습니다.</yellow>",
        "world-fix-fix-started" to "<yellow>청크 {개수}개를 다시 읽게 합니다…</yellow>",
        "world-fix-fixed" to "<green>청크 {개수}개를 서버가 다시 읽었습니다 — 못 읽던 것은 새로 만들어졌습니다.</green> <gray>다시 검사해 확인하세요.</gray>",
        "world-fix-stats" to listOf(
            "<gold>****** 청크 검사 — {월드} ******</gold>",
            "<gray>청크:</gray> <white>{확인}/{전체}</white> <gray>· 범위:</gray> <white>{범위}</white>",
            "<gray>속도:</gray> <white>{속도}</white> <gray>({자동}) · 실제:</gray> <white>{실속도}/초</white>",
            "<gray>찾음:</gray> <white>{찾음}</white> <gray>· 걸린 시간:</gray> <white>{시간}</white>",
        ).joinToString("\n"),
        // --- 조명 수정 ---
        "world-lfix-unavailable" to "<red>이 서버에서는 빛 다시 계산을 쓸 수 없습니다: {값}</red> <gray>(/paper fixlight 를 써 보세요)</gray>",
        "world-lfix-capped" to "<yellow>범위는 서버 시야 거리({값}청크)를 넘을 수 없어 맞췄습니다.</yellow>",
        "world-lfix-started" to "<green>청크 {개수}개의 빛을 다시 계산합니다.</green>",
        "world-lfix-progress" to "<gray>TPS</gray> <white>{tps}</white> <gray>· 남은</gray> <white>{개수}</white> <gray>({시간}) · 속도</gray> <white>{속도}</white><gray>/초</gray>",
        "world-lfix-finished" to "<green>빛 다시 계산이 끝났습니다 — 청크 {개수}개, {시간}.</green>",
        "world-lfix-stopped" to "<gray>빛 다시 계산을 멈췄습니다 — 청크 {개수}개 끝남.</gray>",
        "world-lfix-stopped-all" to "<gray>빛 다시 계산 {개수}개를 멈췄습니다.</gray>",
        "world-lfix-none" to "<gray>진행 중인 빛 다시 계산이 없습니다.</gray>",
        "world-lfix-already" to "<red>이미 빛을 다시 계산하는 중입니다.</red> <gray>/조명수정 멈춤</gray>",
        "world-lfix-timeout" to "<red>서버가 1분 동안 빛 계산에 답하지 않아 멈췄습니다 — 청크 {개수}개 끝남.</red> <gray>(월드가 내려갔거나 서버가 바쁩니다)</gray>",
        // --- 몹 제거 ---
        "world-killall-bad" to "<red>모르는 깃발: {값}</red> <gray>-monsters -pets -npc -animals -ambient -vehicles -named -withitems -all -force -lightning -list -s -m:종류 -r:반경 -w:월드</gray>",
        "world-killall-capped" to "<yellow>반경은 최대 {값}칸입니다.</yellow>",
        "world-killall-need-center" to "<red>-r 반경은 그 월드에 서 있을 때만 씁니다(콘솔이거나 -w 로 다른 월드).</red> <gray>-r 을 빼면 그 월드에 읽힌 것 전부</gray>",
        "world-killall-nothing" to "<gray>지울 것이 없습니다.</gray>",
        "world-killall-list" to "<white>{분류}</white><gray>: {개수}</gray> <yellow>({값})</yellow>",
        "world-killall-total" to "<green>모두 {개수}마리를 지웠습니다.</green> <gray>({값})</gray>",
        "world-killall-protected" to "<gray>보호돼 남긴 것 {개수}개(이름·길들임·끈·아이템·커스텀 몹 · 탈것에 탄 것 · 짐 실은 탈것) — -force 로 무시(짐은 떨군다)</gray>",
        "world-killall-cat-monsters" to "몬스터",
        "world-killall-cat-pets" to "애완동물",
        "world-killall-cat-npc" to "주민",
        "world-killall-cat-animals" to "동물",
        "world-killall-cat-ambient" to "주변 생물",
        "world-killall-cat-vehicles" to "탈것",
        // --- 날씨 ---
        "world-weather-sun" to "맑음",
        "world-weather-rain" to "비",
        "world-weather-storm" to "폭풍",
        "world-weather-locked" to "고정",
        "world-weather-usage" to "<red>/날씨 <맑음|비|폭풍> [고정|시간] [월드|all]</red> <gray>· /날씨 고정해제 · 시간: 120 · 10m · 1h · 1d</gray>",
        "world-weather-bad" to "<red>'{값}' 을(를) 읽지 못했습니다.</red> <gray>(날씨 · 고정 · 시간 · 월드)</gray>",
        "world-weather-set" to "<green>{월드} 의 날씨를 {값}(으)로 — {시간}.</green>",
        "world-weather-current" to "<white>{월드}</white> <gray>→</gray> <white>{값}</white> <gray>({시간})</gray>",
        "world-weather-unlocked" to "<green>{월드} 의 날씨 고정을 풀었습니다.</green>",
        // --- 시간 ---
        "world-time-usage" to "<red>/시간 <시각|낮|밤|아침|저녁> [월드|all] [-smooth]</red> <gray>· 더하기·빼기 <양> · 정지 · 재개 · 실제 · 자동실제 <시작|정지> — 13:00 · 1pm · 13 · 7000t</gray>",
        "world-time-cant-set" to "<red>'{값}' 을(를) 시간으로 읽지 못했습니다.</red> <gray>(13:00 · 1pm · 오후 1 · 13 · 7000t · 낮)</gray>",
        "world-time-set" to "<green>{월드} 의 시간을 {24시} ({12시} · {틱}틱)(으)로 맞췄습니다.</green>",
        "world-time-check" to "<white>{월드}</white><gray>:</gray> <white>{24시}</white> <gray>· {12시} · {틱}틱</gray>{상태}",
        "world-time-frozen-mark" to " <red>(멈춤)</red>",
        "world-time-real-mark" to " <aqua>(현실 시간)</aqua>",
        "world-time-frozen" to "<green>{월드} 의 시간을 멈췄습니다.</green>",
        "world-time-unfrozen" to "<green>{월드} 의 시간이 다시 흐릅니다.</green>",
        "world-time-auto-start" to "<green>{월드} 의 시간을 현실 시간에 맞춰 갑니다.</green>",
        "world-time-auto-stop" to "<gray>{월드} 의 현실 시간 맞추기를 껐습니다.</gray>",
        "world-time-auto-overrides" to "<yellow>{월드} 은(는) 현실 시간을 따라가므로 곧 다시 맞춰집니다.</yellow> <gray>/시간 자동실제 정지</gray>",
        // --- 월드 제한 ---
        "world-limit-gamemode" to "<yellow>{월드} 에서는 {값} 모드입니다.</yellow>",
        "world-limit-fly" to "<yellow>{월드} 에서는 날 수 없습니다.</yellow>",
        "world-limit-fly-restored" to "<green>다시 날 수 있습니다.</green>",
        "world-limit-roof" to "<red>{값}칸 위로는 날 수 없습니다.</red>",
        "world-limit-god" to "<yellow>{월드} 에서는 무적을 쓸 수 없어 껐습니다.</yellow>",
        "world-limit-elytra" to "<red>{월드} 에서는 겉날개로 날 수 없습니다.</red>",
        "world-limit-elytra-unequipped" to "<yellow>{월드} 에서는 겉날개를 쓸 수 없어 가방에 넣었습니다.</yellow>",
        // --- 잠자기 ---
        "world-sleep-speeding" to "<aqua>{개수}/{필요}명이 자는 중</aqua> <gray>— 밤이 {속도}배 빨리 갑니다</gray>",
        "world-sleep-waiting" to "<gray>{개수}/{필요}명이 자는 중 — 더 자면 밤이 빨라집니다</gray>",
        "world-sleep-inform" to "<aqua>{플레이어}님이 잠들었습니다.</aqua> <gray>({개수}/{필요} · {남은}명 더)</gray>",
        // --- 네더 포탈 ---
        "world-portal-prevented" to "<red>{월드} 에서는 네더 포탈을 만들 수 없습니다.</red>",
        "world-portal-too-big" to "<red>포탈이 너무 큽니다 — {가로}×{세로} (최대 {최대가로}×{최대세로})</red>",
        "world-portal-size" to "<gray>네더 포탈 {가로}×{세로}</gray>",
    )

    @Volatile
    var settings: WorldSettings = WorldSettings()
        private set

    internal val names = BiomeNames()
    internal lateinit var biomes: BiomeSearch
    internal lateinit var chunks: ChunkRepair
    internal lateinit var light: LightFix
    internal lateinit var timeWeather: TimeWeather
    private lateinit var limits: WorldLimits
    private lateinit var rules: WorldRules
    private lateinit var commands: WorldCommands

    override fun load(settings: YamlConfiguration) {
        this.settings = WorldSettings.from(settings)
        if (::timeWeather.isInitialized) timeWeather.loadAuto(this.settings.time.autoRealWorlds)
    }

    override fun enable() {
        biomes = BiomeSearch(ess, { settings }, names)
        chunks = ChunkRepair(ess) { settings }
        light = LightFix(ess) { settings }
        timeWeather = TimeWeather(ess) { settings }.also { it.loadAuto(settings.time.autoRealWorlds) }
        limits = WorldLimits(ess) { settings }
        rules = WorldRules(ess) { settings }
        ess.listen(limits)
        ess.listen(rules)
        ess.listen(object : org.bukkit.event.Listener {
            @org.bukkit.event.EventHandler
            fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) = biomes.quit(event.player.uniqueId)

            @org.bukkit.event.EventHandler
            fun onWorldLoad(event: org.bukkit.event.world.WorldLoadEvent) = timeWeather.worldLoaded(event.world)
        })
        commands = WorldCommands(ess, this).also { it.register() }
        ess.ticks.add("world", 1) { now ->
            biomes.tick(now)
            chunks.tick(now)
            light.tick(now)
            timeWeather.tick(now)
            limits.tick(now)
            rules.tick(now)
        }
    }

    override fun disable() {
        if (::biomes.isInitialized) biomes.shutdown()
        if (::chunks.isInitialized) chunks.shutdown()
        if (::light.isInitialized) light.shutdown()
        if (::limits.isInitialized) limits.clear()
        if (::rules.isInitialized) rules.clear()
    }

    /** `/시간 자동실제` 를 설정 파일에 남긴다(워커에서 읽고 원자적으로 쓴다 — 주석은 Bukkit YAML 이 지킨다). */
    internal fun saveAutoReal(worlds: Set<String>) {
        val file = ess.io.file("settings", "$id.yml")
        ess.io.asyncRun {
            val y = YamlConfiguration.loadConfiguration(file)
            y.set("time.auto-real.worlds", worlds.toList())
            AtomicFiles.write(file, y.saveToString())
        }
    }

    // --- CMI 가져오기 --------------------------------------------------------------------------------

    /**
     * CMI `config.yml` 의 월드 설정(WorldLimits · Sleeping.Speedup · NetherPortal · Optimizations.PreventEntityBoatEnter · Time)을
     * `settings/world.yml` 로 옮긴다. **읽기와 보고서 줄은 여기서(메인) 바로** — `CmiImportable` 계약대로. server 기능은 importCmi 들이 돌아오자마자
     * 보고서 파일·요약을 만들므로 워커로 미루면 이 줄들이 빠졌다(2026-10-10 리뷰). 작은 YAML 두세 개라 관리자가 한 번 치는 명령에서 메인을 오래 막지 않는다.
     * 우리 설정 파일 쓰기만 `ess.io` 로. 서버에 없는 월드를 가리키는 줄(CMI 기본값의 testWorld·worldName 같은 것)은 건너뛰고 적는다. 몇 번 돌려도 같은 결과다.
     */
    override fun importCmi(data: CmiData, report: ImportReport) {
        val cmiFile = File(data.folder, "config.yml")
        if (!cmiFile.isFile) return report.skipped(KIND, "CMI config.yml 이 없습니다")
        val ours = ess.io.file("settings", "$id.yml")
        val worlds = Bukkit.getWorlds().map { it.name.lowercase() }.toSet()
        val cmi = YamlConfiguration.loadConfiguration(cmiFile)
        val y = YamlConfiguration.loadConfiguration(ours)
        val modules = File(data.folder, "Settings/Modules.yml").takeIf { it.isFile }?.let { YamlConfiguration.loadConfiguration(it) }
        CmiWorldImport.apply(cmi, y, worlds, report, modules)
        val text = y.saveToString()
        ess.io.asyncRun { AtomicFiles.write(ours, text) }
        load(y)
    }

    // --- 검증기 ----------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    /**
     * 검증용 적대 몹 — 난이도가 평화로움이면 `World.spawn` 이 던진다(Cannot spawn monster … in Peaceful). 만드는 순간만 난이도를 올렸다 되돌린다.
     * 검사는 같은 틱 안에서 끝나므로(틱이 지나야 평화로움 정리가 돈다) 되돌린 뒤에도 몹은 남아 있고, 검사가 `finally` 에서 지운다.
     */
    private fun <T : org.bukkit.entity.Entity> spawnMonster(world: org.bukkit.World, at: org.bukkit.Location, type: Class<T>, prepare: (T) -> Unit): T {
        val before = world.difficulty
        if (before != org.bukkit.Difficulty.PEACEFUL) return world.spawn(at, type) { prepare(it) }
        world.difficulty = org.bukkit.Difficulty.EASY
        try {
            return world.spawn(at, type) { prepare(it) }
        } finally {
            world.difficulty = before
        }
    }

    override fun checks(): List<Check> = listOf(
        Check("바이옴 이름: plains·평원 이 평원을 가리킨다") { _ ->
            val all = names.entries()
            val byId = BiomeNames.match("plains", all).exact.map { it.key }
            val korean = all.firstOrNull { it.key == "minecraft:plains" }?.korean
            when {
                "minecraft:plains" !in byId -> "plains 로 평원을 못 찾습니다: $byId"
                korean != null && "minecraft:plains" !in BiomeNames.match(korean, all).exact.map { it.key } -> "'$korean' 로 평원을 못 찾습니다"
                else -> null
            }
        },
        Check("조명 수정: 서버 내부 길이 열려 있다") { p -> light.probe(p.world)?.let { "리플렉션 실패 — $it" } },
        Check("청크 복구: 지역 파일 폴더가 있다") { p ->
            // 폴더가 있는지만 본다 — 안의 .mca 를 훑는 것은 워커의 일(메인에서 큰 폴더를 나열하지 않는다).
            if (chunks.regionCandidates(p.world).any { java.nio.file.Files.isDirectory(it.toPath()) }) null
            else "${p.world.name} 의 region 폴더를 못 찾습니다(${p.world.worldFolder})"
        },
        Check("바이옴 찾기: 공급자 → 나선 → 이름표가 이어진다") { p ->
            val world = p.world
            if (world.generator != null) return@Check null // 생성기 월드는 찾기를 거절한다 — 볼 것이 없다
            val provider = world.vanillaBiomeProvider()
            val loc = p.location
            val biome = provider.getBiome(world, loc.blockX, loc.blockY, loc.blockZ)
            val possible = provider.getBiomes(world).toSet()
            val entry = names.entries().firstOrNull { it.biome == biome }
            val spiral = Spiral(1)
            val first = spiral.next()
            var ring = 0
            while (spiral.next() != null) ring++
            when {
                biome !in possible -> "공급자가 준 ${biome.key} 가 그 월드의 생물군계 목록에 없습니다"
                entry == null -> "${biome.key} 가 이름표에 없습니다"
                entry.key !in BiomeNames.match(entry.path, names.entries()).exact.map { it.key } -> "'${entry.path}' 로 ${entry.key} 를 못 찾습니다"
                first != (0 to 0) || ring != 8 -> "나선이 가운데·첫 고리 8칸으로 돌지 않습니다($first, $ring)"
                else -> null
            }
        },
        Check("월드 제한: 게임모드를 맞추고 비행 금지 월드에서 비행을 끈다") { p ->
            val before = settings
            val mode = p.gameMode
            val allow = p.allowFlight
            val flying = p.isFlying
            try {
                val w = p.world.name.lowercase()
                settings = before.copy(limits = before.limits.copy(enabled = true, gamemode = mapOf(w to "survival"), noFly = setOf(w), noGod = emptySet(), noElytra = emptySet()))
                limits.ignoreBypass = true
                p.gameMode = GameMode.ADVENTURE
                limits.enforce(p, entering = true)
                val modeOk = p.gameMode == GameMode.SURVIVAL
                p.allowFlight = true
                limits.enforce(p, entering = false)
                when {
                    !modeOk -> "게임모드가 survival 로 바뀌지 않았습니다(${p.gameMode})"
                    p.allowFlight -> "비행 금지 월드에서 비행이 꺼지지 않았습니다"
                    else -> null
                }
            } finally {
                limits.ignoreBypass = false
                settings = before
                p.gameMode = mode
                p.allowFlight = allow
                if (allow) p.isFlying = flying
                limits.forget(p.uniqueId)
            }
        },
        Check("날씨: 바꾸기·고정이 게임 규칙에 남고 되돌린다") { p ->
            val w = p.world
            val rule = w.getGameRuleValue(GameRules.ADVANCE_WEATHER)
            val storm = w.hasStorm()
            val thunder = w.isThundering
            val wd = w.weatherDuration
            val td = w.thunderDuration
            val cd = w.clearWeatherDuration
            try {
                timeWeather.setWeather(w, TimeWeather.Weather.RAIN, 60, lock = true)
                val rainLocked = timeWeather.weatherOf(w) == TimeWeather.Weather.RAIN && timeWeather.isLocked(w)
                timeWeather.unlock(w)
                val unlocked = !timeWeather.isLocked(w)
                timeWeather.lock(w)
                val lockOnly = timeWeather.isLocked(w) && timeWeather.weatherOf(w) == TimeWeather.Weather.RAIN
                when {
                    !rainLocked -> "비·고정이 되지 않았습니다"
                    !unlocked -> "고정이 풀리지 않았습니다"
                    !lockOnly -> "지금 날씨 그대로 고정(/날씨 고정)이 되지 않았습니다"
                    else -> null
                }
            } finally {
                w.setGameRule(GameRules.ADVANCE_WEATHER, rule)
                w.setStorm(storm)
                w.isThundering = thunder
                w.weatherDuration = wd
                w.thunderDuration = td
                w.clearWeatherDuration = cd
            }
        },
        Check("시간: 바로 맞추기·부드러운 전환") { p ->
            val w = p.world
            val full = w.fullTime
            try {
                timeWeather.setTime(w, 6000, smooth = false)
                val set = w.time == 6000L
                val step = settings.time.smoothSpeed * 20L
                timeWeather.setTime(w, 6000L + step * 3, smooth = true)
                timeWeather.stepTransitions()
                val moved = w.time - 6000L
                when {
                    !set -> "시간이 6000 으로 맞춰지지 않았습니다(${w.time})"
                    moved != step -> "부드러운 전환 한 걸음이 ${step}틱이 아닙니다($moved)"
                    else -> null
                }
            } finally {
                timeWeather.cancelTransition(w)
                w.fullTime = full
            }
        },
        Check("현실 시간: 끄면 다시 켠 뒤에도 멈춤을 푼다") { p ->
            val w = p.world
            if (timeWeather.isAuto(w)) return@Check null // 이미 현실 시간인 월드는 건드리지 않는다
            val rule = w.getGameRuleValue(GameRules.ADVANCE_TIME)
            val marked = timeWeather.isAutoMarked(w)
            val full = w.fullTime
            try {
                w.setGameRule(GameRules.ADVANCE_TIME, true)
                timeWeather.setAuto(w, true)
                val frozen = timeWeather.isFrozen(w) && timeWeather.isAutoMarked(w)
                // 재시작 흉내 — 메모리 목록만 다시 읽는다(표식은 월드에 남는다).
                timeWeather.loadAuto(timeWeather.autoWorlds())
                timeWeather.setAuto(w, false)
                when {
                    !frozen -> "현실 시간을 켰는데 멈추지 않았습니다"
                    timeWeather.isFrozen(w) -> "현실 시간을 껐는데 시간이 멈춰 있습니다"
                    else -> null
                }
            } finally {
                if (timeWeather.isAuto(w)) timeWeather.setAuto(w, false)
                timeWeather.cancelTransition(w)
                timeWeather.setAutoMarked(w, marked)
                w.setGameRule(GameRules.ADVANCE_TIME, rule)
                w.fullTime = full
            }
        },
        Check("몹제거: 기본은 몬스터만, -all 도 길들인 것·짐 실은 탈것은 남긴다") { p ->
            var zombie: Zombie? = null
            var wolf: Wolf? = null
            var cart: StorageMinecart? = null
            try {
                val at = p.location.clone().add(0.0, 200.0, 0.0).let { if (it.y >= p.world.maxHeight) p.location.clone() else it }
                zombie = spawnMonster(p.world, at, Zombie::class.java) { it.setAI(false); it.isSilent = true; it.isPersistent = false; it.setGravity(false) }
                wolf = p.world.spawn(at, Wolf::class.java) { it.setAI(false); it.isSilent = true; it.isPersistent = false; it.setGravity(false); it.owner = p }
                cart = p.world.spawn(at, StorageMinecart::class.java) { it.setGravity(false); it.inventory.addItem(ItemStack(Material.DIRT)) }
                val plain = (KillAll.parse(emptyList()) as KillAll.Parse.Ok).options
                val all = (KillAll.parse(listOf("-all")) as KillAll.Parse.Ok).options
                val pets = (KillAll.parse(listOf("-pets")) as KillAll.Parse.Ok).options
                fun removable(e: org.bukkit.entity.Entity, o: KillAll.Options) =
                    KillAll.categoryOf(e)?.let { KillAll.selected(e, it, o) && KillAll.protection(e, o) == null } == true
                when {
                    !removable(zombie, plain) -> "이름 없는 좀비가 기본 몹제거에 걸리지 않습니다"
                    removable(wolf, plain) -> "길들인 늑대가 기본 몹제거에 걸립니다"
                    KillAll.protection(wolf, all) != KillAll.Protection.TAMED -> "-all 이 길들인 늑대의 보호를 풀었습니다"
                    !removable(wolf, pets) -> "-pets 인데 길들인 늑대가 남습니다"
                    KillAll.protection(cart, all) != KillAll.Protection.CARGO -> "-all 이 짐 실은 상자 수레를 지웁니다"
                    else -> null
                }
            } finally {
                zombie?.remove()
                wolf?.remove()
                cart?.inventory?.clear()
                cart?.remove()
            }
        },
        Check("네더 포탈: 최대 크기를 넘으면 막는다") { p ->
            val before = settings
            try {
                settings = before.copy(portal = WorldSettings.Portal(base = WorldSettings.PortalRule(maxWidth = 2, maxHeight = 3)))
                val base = p.location.block
                val states = ArrayList<BlockState>()
                for (x in 0 until 3) for (y in 0 until 4) states += base.getRelative(x, y, 0).state.also { it.type = Material.NETHER_PORTAL }
                val event = PortalCreateEvent(states, p.world, null, PortalCreateEvent.CreateReason.FIRE)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "3×4 포탈이 2×3 제한에 막히지 않았습니다"
            } finally {
                settings = before
            }
        },
        Check("겉날개 금지 월드에서 활공이 막힌다") { p ->
            val before = settings
            try {
                settings = before.copy(limits = before.limits.copy(enabled = true, noElytra = setOf(p.world.name.lowercase())))
                limits.ignoreBypass = true
                val event = EntityToggleGlideEvent(p, true)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "활공 사건이 취소되지 않았습니다"
            } finally {
                limits.ignoreBypass = false
                settings = before
            }
        },
        Check("스폰 사유 제한·몹 탈것 막기·몹제거 보호") { p ->
            val before = settings
            var zombie: Zombie? = null
            var cart: RideableMinecart? = null
            try {
                val at = p.location.clone().add(0.0, 200.0, 0.0).let { if (it.y >= p.world.maxHeight) p.location.clone() else it }
                zombie = spawnMonster(p.world, at, Zombie::class.java) { z ->
                    z.setAI(false)
                    z.isSilent = true
                    z.isPersistent = false
                    z.setGravity(false)
                }
                cart = p.world.spawn(at, RideableMinecart::class.java) { it.setGravity(false) }
                val z = zombie!!
                settings = before.copy(
                    limits = before.limits.copy(enabled = true, spawnReasons = mapOf(p.world.name.lowercase() to setOf("SPAWNER"))),
                    vehicles = WorldSettings.Vehicles(minecarts = WorldSettings.Riders(monsters = true)),
                )
                val spawn = CreatureSpawnEvent(z, CreatureSpawnEvent.SpawnReason.SPAWNER)
                Bukkit.getPluginManager().callEvent(spawn)
                val enter = VehicleEnterEvent(cart!!, z)
                Bukkit.getPluginManager().callEvent(enter)
                z.customName(Component.text("검증"))
                val named = KillAll.protection(z, KillAll.Options())
                val forced = KillAll.protection(z, KillAll.Options(force = true))
                when {
                    !spawn.isCancelled -> "SPAWNER 사유 스폰이 막히지 않았습니다"
                    !enter.isCancelled -> "좀비가 수레에 타는 것이 막히지 않았습니다"
                    named != KillAll.Protection.NAMED -> "이름 붙은 좀비가 보호되지 않습니다($named)"
                    forced != null -> "-force 인데 보호됩니다($forced)"
                    else -> null
                }
            } finally {
                settings = before
                zombie?.remove()
                cart?.remove()
            }
        },
        Check("시간 멈춤이 게임 규칙을 바꾸고 되돌린다") { p ->
            val world = p.world
            val original = world.getGameRuleValue(GameRules.ADVANCE_TIME)
            try {
                timeWeather.freeze(world, true)
                val frozen = timeWeather.isFrozen(world)
                timeWeather.freeze(world, false)
                if (frozen && !timeWeather.isFrozen(world)) null else "advance_time 이 바뀌지 않았습니다"
            } finally {
                if (original != null) world.setGameRule(GameRules.ADVANCE_TIME, original)
            }
        },
        Check("잠자기 가속 계산(4명 중 2명 → 50배)") { _ ->
            when (val r = SleepMath.decide(WorldSettings.Sleep(), 4, 2, true)) {
                is SleepMath.Result.Speed -> if (r.speed == 50) null else "속도가 ${r.speed}"
                else -> "가속이 아닙니다: $r"
            }
        },
    ) + reviewChecks()

    /** 2026-10-10 리뷰 뒤 더한 검사들 — player 기능 연결 · 지붕 위 비행 · 멈춘 월드의 잠 · 날씨 고정 · 게임 규칙 · 몹제거 범위·장비 · 네더 천장 · 조명 멈춤. */
    private fun reviewChecks(): List<Check> = listOf(
        Check("월드 제한: player 기능이 켜져 있으면 접점 ess.abilities 가 끼워져 있다") { _ ->
            val missing = limits.abilitiesMissing() ?: return@Check null // player 기능이 꺼져 있으면 볼 것이 없다
            if (!missing) null else "ess.abilities 가 기본(NONE)입니다 — 무적 금지 월드가 꺼진 것과 같습니다"
        },
        Check("월드 제한: 지붕 높이 위 비행이 막힌다") { p ->
            val before = settings
            try {
                val w = p.world.name.lowercase()
                settings = before.copy(limits = before.limits.copy(enabled = true, flyAboveRoof = false, roof = mapOf(w to p.location.blockY - 1), noFly = emptySet()))
                limits.ignoreBypass = true
                val event = PlayerToggleFlightEvent(p, true)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "지붕 위에서 날기 시작하는 것이 막히지 않았습니다"
            } finally {
                limits.ignoreBypass = false
                settings = before
            }
        },
        Check("잠자기: 시간이 멈춘 월드는 가속하지 않고, 아침이 와도 날씨 고정은 그대로") { p ->
            val w = p.world
            val timeRule = w.getGameRuleValue(GameRules.ADVANCE_TIME)
            val weatherRule = w.getGameRuleValue(GameRules.ADVANCE_WEATHER)
            val storm = w.hasStorm()
            val thunder = w.isThundering
            val wd = w.weatherDuration
            val td = w.thunderDuration
            val before = settings
            try {
                settings = before.copy(sleep = before.sleep.copy(clearWeather = true))
                w.setGameRule(GameRules.ADVANCE_TIME, false)
                val frozenAccelerates = rules.accelerates(w)
                w.setGameRule(GameRules.ADVANCE_WEATHER, false)
                w.setStorm(true)
                rules.morning(w)
                val stillRaining = w.hasStorm()
                when {
                    frozenAccelerates -> "시간이 멈춘 월드에서도 밤을 가속합니다"
                    !stillRaining -> "날씨가 고정된 월드에서 아침이 비를 걷었습니다"
                    else -> null
                }
            } finally {
                settings = before
                if (timeRule != null) w.setGameRule(GameRules.ADVANCE_TIME, timeRule)
                if (weatherRule != null) w.setGameRule(GameRules.ADVANCE_WEATHER, weatherRule)
                w.setStorm(storm)
                w.isThundering = thunder
                w.weatherDuration = wd
                w.thunderDuration = td
            }
        },
        Check("게임 규칙: 영어 id·한국어 이름으로 찾고 값을 읽어 바꾸고 되돌린다") { p ->
            val w = p.world
            val rule = GameRules.ADVANCE_TIME
            val original = w.getGameRuleValue(rule)
            try {
                val byKey = commands.findRule(commands.ruleKey(rule))
                val korean = commands.ruleKorean(rule)
                val byKorean = korean?.let { commands.findRule(it) }
                val off = commands.parseRuleValue(rule, "끄기")
                if (off == false) w.setGameRule(rule, false)
                when {
                    byKey != rule -> "${commands.ruleKey(rule)} 로 규칙을 못 찾습니다($byKey)"
                    korean != null && byKorean != rule -> "'$korean' 로 규칙을 못 찾습니다($byKorean)"
                    off != false -> "'끄기' 를 false 로 읽지 못했습니다($off)"
                    w.getGameRuleValue(rule) != false -> "규칙이 바뀌지 않았습니다"
                    else -> null
                }
            } finally {
                if (original != null) w.setGameRule(rule, original)
            }
        },
        Check("몹제거: -r 인데 가운데가 없으면 월드 전체가 아니라 거절") { _ ->
            val console = KillAll.scope(20, 16, 64, hasCenter = false, worldGiven = true)
            val player = KillAll.scope(null, 16, 64, hasCenter = true, worldGiven = false)
            when {
                console != KillAll.Scope.NeedCenter -> "가운데 없는 -r 이 $console 로 됩니다"
                player != KillAll.Scope.Radius(16, false) -> "기본 반경이 아닙니다: $player"
                else -> null
            }
        },
        Check("몹제거: 주운 장비(2.0)는 떨구고 태어날 때 입은 것·플러그인의 반드시 떨구는 장비(1.0)는 떨구지 않는다") { p ->
            var zombie: Zombie? = null
            val at = p.location.clone().add(0.0, 200.0, 0.0).let { if (it.y >= p.world.maxHeight) p.location.clone() else it }
            try {
                zombie = spawnMonster(p.world, at, Zombie::class.java) { z ->
                    z.setAI(false); z.isSilent = true; z.isPersistent = false; z.setGravity(false)
                    z.equipment.setItemInMainHand(ItemStack(Material.DIAMOND_SWORD))
                    z.equipment.setItemInMainHandDropChance(2.0f) // 주운 것(바닐라 Mob.setGuaranteedDrop)
                    z.equipment.setHelmet(ItemStack(Material.LEATHER_HELMET))
                    z.equipment.setHelmetDropChance(0.085f) // 태어날 때 입은 것
                    z.equipment.setChestplate(ItemStack(Material.IRON_CHESTPLATE))
                    z.equipment.setChestplateDropChance(1.0f) // 플러그인이 준 반드시 떨구는 장비(inmc-monster drop-chance 100) — 죽여야 얻는다
                }
                val z = zombie!!
                commands.dropCargo(z)
                val drops = z.world.getNearbyEntities(z.location, 3.0, 3.0, 3.0) { it is Item }.map { it as Item }
                val types = drops.map { it.itemStack.type }
                drops.forEach { it.remove() }
                when {
                    Material.DIAMOND_SWORD !in types -> "주운 칼이 떨어지지 않았습니다(지우면 사라집니다)"
                    Material.LEATHER_HELMET in types -> "태어날 때 입은 투구까지 떨궜습니다"
                    Material.IRON_CHESTPLATE in types -> "반드시 떨구는(1.0) 보스 장비까지 떨궜습니다(주운 것은 2.0)"
                    !z.equipment.itemInMainHand.type.isAir -> "떨군 칼이 몹 손에도 남았습니다(복사)"
                    else -> null
                }
            } finally {
                zombie?.remove()
            }
        },
        Check("바이옴 찾기: 지붕 월드(네더)는 천장 아래에서만 고른다") { _ ->
            val top = BiomeSearch.standTop(0, 256, 128, ceiling = true)
            val nether = BiomeSearch.heights(0, 256, 32, 128, cave = false, ceiling = true)
            val loaded = Bukkit.getWorlds().firstOrNull { it.hasCeiling() }
            val real = loaded?.let { biomes.sampleHeights(it, true) }
            val realTop = loaded?.let { BiomeSearch.standTop(it.minHeight, it.maxHeight, it.logicalHeight, true) }
            when {
                top + 1 >= 123 -> "네더 머리 높이 ${top + 1} 이 천장(123~) 안입니다"
                nether.any { it > top } -> "표본 높이가 천장 위입니다: ${nether.toList()}"
                real != null && realTop != null && real.any { it > realTop } -> "${loaded.name} 표본이 천장 위입니다: ${real.toList()}"
                else -> null
            }
        },
        Check("조명 수정: 멈춤이 작업을 바로 뺀다(서버 답을 기다리지 않는다)") { p ->
            if (light.probe(p.world) != null) return@Check null // 쓸 수 없는 서버 — 위 검사가 알린다
            if (light.isRunning(p)) return@Check null // 이미 돌고 있으면 건드리지 않는다
            light.start(p, p, 0)
            val stopped = light.stop(p)
            when {
                !stopped -> "멈출 작업이 없습니다(시작하지 않음)"
                light.isRunning(p) -> "멈춘 뒤에도 작업이 남아 있습니다"
                else -> null
            }
        },
    )


    companion object {
        const val KIND = "월드 설정"

        const val FINDBIOME = "inmcessentials.world.findbiome"
        const val FINDBIOME_STOPALL = "inmcessentials.world.findbiome.stopall"
        const val GAMERULE = "inmcessentials.world.gamerule"
        const val FIXCHUNK = "inmcessentials.world.fixchunk"
        const val LFIX = "inmcessentials.world.lfix"
        const val LFIX_OTHERS = "inmcessentials.world.lfix.others"
        const val KILLALL = "inmcessentials.world.killall"
        const val KILLALL_FORCE = "inmcessentials.world.killall.force"
        const val WEATHER = "inmcessentials.world.weather"
        const val WEATHER_CHECK = "inmcessentials.world.weather.check"
        const val TIME = "inmcessentials.world.time"
        const val TIME_CHECK = "inmcessentials.world.time.check"
        const val LIMIT_GAMEMODE_BYPASS = "inmcessentials.world.limit.gamemode.bypass"
        const val LIMIT_FLY_BYPASS = "inmcessentials.world.limit.fly.bypass"
        const val LIMIT_GOD_BYPASS = "inmcessentials.world.limit.god.bypass"
        const val LIMIT_ELYTRA_BYPASS = "inmcessentials.world.limit.elytra.bypass"
        const val LIMIT_ABOVE_ROOF = "inmcessentials.world.limit.aboveroof"
        const val SLEEP_IGNORE = "inmcessentials.world.sleep.ignore"
        const val PORTAL_BYPASS = "inmcessentials.world.netherportal.bypass"
    }
}

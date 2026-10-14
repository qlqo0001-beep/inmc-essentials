package com.inmc.essentials.spawn

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.CmiLocation
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.util.Ph
import com.inmc.essentials.verify.Check
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.permissions.PermissionDefault
import java.io.File

/**
 * 스폰·첫 접속·부활(2026-10-10 — inmc-menu 의 스폰을 옮겨 왔다). 카탈로그 cmd6:cmd-spawn-set · mod:mod-firstjoin(첫 스폰·새내기 킷 —
 * 첫 접속 **메시지**는 타이틀포지) · cfg1:respawn-handling · cfg1:opt-immortality-join.
 *
 * 데이터는 `spawn.yml`(게임 안에서 고침), 운영 설정은 `settings/spawn.yml`. 처음 켜질 때 `spawn.yml` 이 없으면 `plugins/inmc-menu/spawn.yml` 을
 * **복사**하고(이름 바꾸기가 아니다 — 메뉴의 옛 판이 켜져 있으면 제 파일을 다시 만든다), 서버 스폰이 비어 있으면 CMI config 의
 * `Spawn.Main.Location`·`Spawn.FirstSpawn.Location`(+ 매 접속·흩는 반경·부활 순서)을 한 번 읽는다.
 */
class SpawnFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "spawn"

    override val hasSettings = true

    override val permissions = listOf(
        Perm(USE, PermissionDefault.TRUE, "/스폰 — 서버 스폰으로"),
        Perm(OTHERS, PermissionDefault.OP, "/스폰 <플레이어> — 남을 스폰으로"),
        Perm(ADMIN, PermissionDefault.OP, "/스폰 설정·처음설정·관리"),
        Perm(JOIN_BYPASS, PermissionDefault.FALSE, "매 접속 스폰에서 빠진다(나간 자리로)"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    /** `settings/spawn.yml` 의 스냅샷 — [load] 가 바꿔 끼운다(규칙 12). */
    @Volatile
    var settings: SpawnSettings = SpawnSettings()
        private set

    /** 부활 순서의 홈 칸 — homes 기능이 `ready()` 에서 넣는다([SpawnHomes]). */
    @Volatile
    var homes: SpawnHomes? = null

    /** 부활 순서의 워프 칸 — warps 기능이 `ready()` 에서 넣는다([SpawnWarps]). */
    @Volatile
    var warps: SpawnWarps? = null

    internal lateinit var service: SpawnService

    override fun load(settings: YamlConfiguration) {
        this.settings = SpawnSettings.from(settings)
        // 리로드 — 손으로 고친 spawn.yml 을 다시 읽는다(켤 때는 enable 이 읽는다). 읽기는 I/O 워커, 반영·로그는 메인.
        if (::service.isInitialized) service.reloadIfChanged()
    }

    override fun enable() {
        service = SpawnService(ess, this)
        migrateFromMenu()
        service.loadNow()
        importCmiConfigOnce()
        ess.listen(SpawnListener(ess, this, service))
        SpawnCommand(ess, service).register()
    }

    override fun ready() {
        // 메뉴의 옛 판(스폰을 아직 가진 판)이 같이 켜져 있으면 접속·부활 자리를 둘이 고치고 /스폰 이 두 번 등록된다.
        val menu = Bukkit.getPluginManager().getPlugin("inmc-menu")?.takeIf { it.isEnabled } ?: return
        val stillHasSpawn = runCatching { Class.forName("com.inmc.menu.listener.SpawnListener", false, menu.javaClass.classLoader) }.isSuccess
        if (stillHasSpawn) ess.logger.warning("inmc-menu 의 옛 판이 아직 스폰을 처리합니다 — 스폰을 뺀 inmc-menu 로 같이 바꾸세요(접속·부활 자리를 둘이 고칩니다)")
    }

    override fun disable() {
        if (::service.isInitialized) service.shutdown()
        homes = null
        warps = null
    }

    // --- 워프 기능이 알리는 것 -------------------------------------------------------------------

    /** 부활 워프의 이름이 바뀌었으면 따라 바꾼다(warps 기능이 부른다). */
    internal fun warpRenamed(old: String, next: String) {
        if (!::service.isInitialized || !service.config.respawnWarp.equals(old, ignoreCase = true)) return
        service.update { it.copy(respawnWarp = next) }
        ess.logger.info("부활 워프 이름을 따라 바꿨습니다: $old → $next")
    }

    /** 부활 워프를 지웠으면 지운 사람에게 알린다(이름은 남긴다 — 같은 이름으로 다시 만들면 이어진다). */
    internal fun warpRemoved(name: String, by: CommandSender) {
        if (!::service.isInitialized || !service.config.respawnWarp.equals(name, ignoreCase = true)) return
        ess.messages.send(by, "spawn-warp-removed", Ph.of().raw("{워프}", name))
        ess.logger.warning("부활 워프 '$name' 을(를) 지웠습니다 — 부활 순서의 '부활 워프' 칸은 건너뜁니다(/스폰 관리 에서 다른 워프를 고르세요)")
    }

    // --- 옮기기 --------------------------------------------------------------------------------

    /** `spawn.yml` 이 없고 inmc-menu 의 것이 있으면 복사한다(켤 때 한 번 — 메인이지만 시작 중이다). */
    private fun migrateFromMenu() {
        val file = service.file
        if (file.isFile) return
        val old = File(ess.plugin.dataFolder.parentFile, "inmc-menu/spawn.yml")
        if (!old.isFile) return
        try {
            file.parentFile?.mkdirs()
            old.copyTo(file)
            ess.logger.info("inmc-menu 의 spawn.yml 을 복사했습니다 → ${file.path}")
            val y = YamlConfiguration.loadConfiguration(old)
            val wait = y.getInt("wait", 0)
            val cooldown = y.getInt("cooldown", 0)
            if (wait > 0 || cooldown > 0) {
                ess.logger.info("inmc-menu 의 /스폰 대기 ${wait}초 · 쿨타임 ${cooldown}초는 옮기지 않았습니다 — commands 기능 설정에서 명령 '스폰' 에 다시 거세요")
            }
        } catch (t: Throwable) {
            ess.logger.warning("inmc-menu 의 spawn.yml 을 복사하지 못했습니다: ${t.message}")
        }
    }

    /**
     * 서버 스폰이 비었고 아직 안 했으면 CMI config 의 스폰을 한 번 읽는다(추천 (b) — 테섭의 진짜 스폰이 CMI 에만 있다).
     * 이때는 우리 자료가 비었으므로 CMI 의 매 접속(SpawnOnJoin)·흩는 반경(Main.Rng)·부활 순서(ReSpawn.Global.PriorityOrder)도 옮긴다.
     * 운영 설정(settings/spawn.yml)에 해당하는 것은 로그로 알린다.
     */
    private fun importCmiConfigOnce() {
        val c = service.config
        if (c.cmiImported || c.point != null) return
        val cmi = File(ess.plugin.dataFolder.parentFile, "CMI/config.yml")
        if (!cmi.isFile) return
        val y = YamlConfiguration.loadConfiguration(cmi)
        val main = SpawnPoint.parseCmi(y.getString("Spawn.Main.Location"))
        val first = if (y.getBoolean("Spawn.FirstSpawn.Use", false)) SpawnPoint.parseCmi(y.getString("Spawn.FirstSpawn.Location")) else null
        val order = SpawnConfig.fromCmiOrder(y.getStringList("ReSpawn.Global.PriorityOrder"), y.getBoolean("Spawn.Main.RespawnLocation", false))
        service.update {
            it.copy(
                point = main ?: it.point, firstPoint = it.firstPoint ?: first?.takeIf { f -> f != main },
                everyJoin = y.getBoolean("Spawn.SpawnOnJoin", it.everyJoin),
                radius = y.getInt("Spawn.Main.Rng", it.radius).coerceIn(0, SpawnConfig.MAX_RADIUS),
                respawn = order.steps, respawnWarp = order.warp ?: it.respawnWarp,
                cmiImported = true,
            )
        }
        if (main != null) ess.logger.info("CMI 의 서버 스폰을 가져왔습니다: ${main.describe()}" + (first?.takeIf { it != main }?.let { " · 첫 스폰 ${it.describe()}" } ?: ""))
        else ess.logger.info("CMI config 에 서버 스폰(Spawn.Main.Location)이 없습니다 — /스폰 설정 으로 정하세요")
        for (line in cmiSettingNotes(y, applied = true)) ess.logger.info(line)
    }

    /**
     * CMI config 의 스폰 관련 설정을 우리 것과 견준 안내 줄들. [applied] = 매 접속·반경·부활 순서를 spawn.yml 로 옮겼나(아니면 견주기만).
     * 운영 설정(settings/spawn.yml)은 고쳐 쓰지 않는다(운영자의 주석이 지워지지 않게) — 다르면 알린다.
     */
    private fun cmiSettingNotes(y: YamlConfiguration, applied: Boolean): List<String> = buildList {
        val c = service.config
        val s = settings
        val onJoin = y.getBoolean("Spawn.SpawnOnJoin", false)
        val rng = y.getInt("Spawn.Main.Rng", 0)
        val order = SpawnConfig.fromCmiOrder(y.getStringList("ReSpawn.Global.PriorityOrder"), y.getBoolean("Spawn.Main.RespawnLocation", false))
        if (applied) {
            add("CMI 의 매 접속 스폰(SpawnOnJoin=$onJoin)·흩는 반경(Rng=$rng)·부활 순서(${y.getStringList("ReSpawn.Global.PriorityOrder").joinToString(",")})를 spawn.yml 로 옮겼습니다")
        } else {
            if (onJoin != c.everyJoin) add("CMI Spawn.SpawnOnJoin=$onJoin 인데 우리 '매 접속 → 서버 스폰' 은 ${c.everyJoin} 입니다 — /스폰 관리 에서 맞추세요")
            if (rng != c.radius) add("CMI Spawn.Main.Rng=$rng 인데 우리 흩는 반경은 ${c.radius} 입니다 — /스폰 관리 에서 맞추세요")
            if (order.steps != c.respawn) add("CMI 부활 순서 ${y.getStringList("ReSpawn.Global.PriorityOrder")} 는 지금 우리 순서와 다릅니다 — /스폰 관리 에서 맞추세요")
        }
        if (order.unknown.isNotEmpty()) add("CMI 부활 순서의 ${order.unknown} 은(는) 옮기지 못했습니다")
        if (y.getConfigurationSection("ReSpawn.Specific")?.getKeys(false)?.isNotEmpty() == true) {
            add("CMI ReSpawn.Specific(월드별 부활 순서)은 없습니다 — 모든 월드가 같은 순서를 씁니다")
        }
        val ignored = y.getStringList("Spawn.IgnoredWorlds")
        if (ignored.toSet() != s.ignoredWorlds) add("CMI Spawn.IgnoredWorlds=$ignored → settings/spawn.yml ignored-worlds(지금 ${s.ignoredWorlds}) 에 적으세요")
        val respawnImmortal = y.getInt("ReSpawn.Immortality", 3)
        if (respawnImmortal != s.respawnInvulnerable) add("CMI ReSpawn.Immortality=$respawnImmortal → settings/spawn.yml respawn-invulnerable-seconds(지금 ${s.respawnInvulnerable})")
        val joinImmortal = y.getInt("Optimizations.ImmortalityOnJoin", 3)
        if (joinImmortal != s.joinInvulnerable) add("CMI Optimizations.ImmortalityOnJoin=$joinImmortal → settings/spawn.yml join-invulnerable-seconds(지금 ${s.joinInvulnerable})")
        if (!y.getBoolean("ReSpawn.Enabled", true) && s.handleRespawn) add("CMI ReSpawn.Enabled=false 였습니다 — 부활을 다른 플러그인에 맡기려면 settings/spawn.yml handle-respawn: false")
        add("CMI 를 끄기 전에 ReSpawn.Enabled·SpawnOnJoin 을 꺼 두세요(같은 사건을 둘이 고칩니다). 권한 cmi.spawnonjoin.bypass → $JOIN_BYPASS")
    }

    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::service.isInitialized) return report.skipped(KIND, "스폰 기능이 켜지지 않았습니다")
        val main = data.spawn
        val first = data.firstSpawn
        report.read(KIND, listOfNotNull(main, first).size)
        val c = service.config
        var point = c.point
        var firstPoint = c.firstPoint
        if (main != null) {
            if (point == null) {
                point = main.toPoint()
                report.imported(KIND)
            } else {
                report.skipped(KIND, "서버 스폰이 이미 있습니다(${point.describe()}) — CMI 의 ${main.world};${main.x};${main.y};${main.z} 는 두지 않음")
            }
        }
        if (first != null) {
            if (firstPoint == null && first.toPoint() != point) {
                firstPoint = first.toPoint()
                report.imported(KIND)
            } else {
                report.skipped(KIND, "첫 스폰이 이미 있거나 서버 스폰과 같습니다")
            }
        }
        if (point != c.point || firstPoint != c.firstPoint) service.update { it.copy(point = point, firstPoint = firstPoint, cmiImported = true) }
        listOfNotNull(point, firstPoint).filter { Bukkit.getWorld(it.world) == null }
            .forEach { report.note("스폰 월드 '${it.world}' 가 지금 없습니다 — 그 월드가 열리면 씁니다") }
        val config = File(data.folder, "config.yml")
        if (config.isFile) cmiSettingNotes(YamlConfiguration.loadConfiguration(config), applied = false).forEach(report::note)
        else report.note("CMI config.yml 이 없어 스폰 설정(매 접속·반경·부활 순서·무적)을 견주지 못했습니다")
    }

    private fun CmiLocation.toPoint() = SpawnPoint(world, x, y, z, yaw, pitch)

    // --- 검증기 --------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("부활 순서 — 서버 스폰이 앞이면 침대보다 먼저") { p ->
            if (ess.moderation.isJailed(p.uniqueId)) return@Check null // 감옥이면 부활을 감옥에 맡긴다
            withSpawn(SpawnConfig(point = SpawnPoint.of(p.location), respawn = SpawnConfig.parseOrder(listOf("server", "bed", "anchor", "world")))) {
                val elsewhere = p.location.clone().add(0.0, 50.0, 0.0)
                val event = PlayerRespawnEvent(p, elsewhere, true, false, false, PlayerRespawnEvent.RespawnReason.DEATH)
                listener().onRespawn(event)
                if (event.respawnLocation.distanceSquared(p.location) > 0.01) "서버 스폰이 아니라 ${event.respawnLocation.toVector()} 입니다" else null
            }
        },
        Check("부활 순서 — 침대가 앞이면 침대 그대로, 엔드 귀환은 안 건드린다") { p ->
            withSpawn(SpawnConfig(point = SpawnPoint.of(p.location))) {
                val bed = p.location.clone().add(0.0, 50.0, 0.0)
                val event = PlayerRespawnEvent(p, bed, true, false, false, PlayerRespawnEvent.RespawnReason.DEATH)
                listener().onRespawn(event)
                val portal = PlayerRespawnEvent(p, bed, false, false, false, PlayerRespawnEvent.RespawnReason.END_PORTAL)
                listener().onRespawn(portal)
                when {
                    event.respawnLocation != bed -> "침대 부활을 바꿨습니다"
                    portal.respawnLocation != bed -> "엔드 귀환을 바꿨습니다"
                    else -> null
                }
            }
        },
        Check("부활 순서 — 기본 홈 칸이 앞이면 홈으로(홈 창구)") { p ->
            if (ess.moderation.isJailed(p.uniqueId)) return@Check null // 감옥이면 부활을 감옥에 맡긴다
            val home = p.location.clone().add(0.0, 30.0, 0.0)
            val before = homes
            try {
                homes = object : SpawnHomes {
                    override fun bedHome(player: Player): Location? = null
                    override fun defaultHome(player: Player): Location? = home
                }
                withSpawn(SpawnConfig(respawn = SpawnConfig.parseOrder(listOf("home", "server", "world")))) {
                    val event = PlayerRespawnEvent(p, p.location.clone(), false, false, false, PlayerRespawnEvent.RespawnReason.DEATH)
                    listener().onRespawn(event)
                    if (event.respawnLocation != home) "기본 홈이 아니라 ${event.respawnLocation.toVector()} 입니다" else null
                }
            } finally {
                homes = before
            }
        },
        Check("홈·워프 창구가 걸려 있다") { _ ->
            val missing = buildList {
                if (ess.enabled("homes") && homes == null) add("홈")
                if (ess.enabled("warps") && warps == null) add("워프")
            }
            if (missing.isEmpty()) null else "안 걸림: ${missing.joinToString(", ")} — 그 기능의 ready() 를 보세요"
        },
        Check("부활 워프 칸을 켰으면 그 워프가 있다") { _ ->
            val c = service.config
            if (c.respawn.none { it.source == Source.WARP && it.enabled }) return@Check null
            when {
                c.respawnWarp.isBlank() -> "부활 순서의 '부활 워프' 칸이 켜졌는데 워프를 정하지 않았습니다 — /스폰 관리"
                warps == null -> "부활 순서의 '부활 워프' 칸이 켜졌는데 워프 기능이 꺼져 있습니다"
                warps?.warp(c.respawnWarp) == null -> "부활 워프 '${c.respawnWarp}' 이(가) 없거나 월드가 없습니다 — /스폰 관리 에서 다시 고르세요"
                else -> null
            }
        },
        Check("부활 자리를 덮는 다른 플러그인이 없다(사건 등록 목록)") { _ ->
            if (!settings.handleRespawn) return@Check null
            // 진짜 부활 사건은 쏘지 않는다 — 다른 플러그인의 부활 처리(명령 실행·비행 켜기 등)가 관리자에게 일어난다.
            val listeners = PlayerRespawnEvent.getHandlerList().registeredListeners
            val ours = listeners.any { it.plugin == ess.plugin && it.listener is SpawnListener && it.priority == EventPriority.HIGH }
            val others = listeners.filter {
                it.plugin != ess.plugin && it.plugin.name !in RESPAWN_ALLOWED &&
                    it.priority.slot >= EventPriority.HIGH.slot && it.priority != EventPriority.MONITOR
            }.map { "${it.plugin.name}(${it.priority})" }.distinct()
            when {
                !ours -> "부활 리스너가 HIGH 에 없습니다"
                others.isNotEmpty() -> "부활 자리를 뒤에서 바꿀 수 있는 플러그인: ${others.joinToString(", ")} — CMI ReSpawn.Enabled 같은 부활 처리를 끄세요"
                else -> null
            }
        },
        Check("접속 무적 — 피해를 막는다(진짜 사건)") { p ->
            val before = service.invulnerableSnapshot(p.uniqueId)
            try {
                service.protect(p.uniqueId, 5)
                val event = damage(p) ?: return@Check null
                Bukkit.getPluginManager().callEvent(event)
                if (!event.isCancelled) "무적인데 피해 사건이 취소되지 않았습니다" else null
            } finally {
                service.restoreInvulnerable(p.uniqueId, before)
            }
        },
        Check("첫 접속 — 접속 자리 리스너(HIGH)·첫 접속 킷 창구") { _ ->
            val spawnListeners = AsyncPlayerSpawnLocationEvent.getHandlerList().registeredListeners
                .filter { it.plugin == ess.plugin && it.listener is SpawnListener }
            when {
                spawnListeners.none { it.priority == EventPriority.HIGH } -> "접속 자리 리스너가 HIGH 에 없습니다(던전 HIGHEST 가 뒤에서 이겨야 합니다)"
                settings.firstJoinKit && ess.enabled("kits") && ess.kits == null -> "kits 기능이 켜졌는데 첫 접속 킷 창구(ess.kits)가 비었습니다"
                else -> null
            }
        },
    )

    private fun listener() = SpawnListener(ess, this, service)

    private fun withSpawn(config: SpawnConfig, check: () -> String?): String? {
        val before = service.config
        try {
            service.swap(config)
            return check()
        } finally {
            service.swap(before)
        }
    }

    /** 검증용 1 피해 사건(선인장 접촉). 만들지 못하면 null(건너뜀). 생성자는 "내부용" 으로 표시돼 있지만 검증기가 사건을 쏘는 길은 이것뿐이다. */
    @Suppress("DEPRECATION")
    private fun damage(p: Player): EntityDamageEvent? = runCatching {
        EntityDamageEvent(p, EntityDamageEvent.DamageCause.CONTACT, DamageSource.builder(DamageType.CACTUS).build(), 1.0)
    }.getOrNull()

    companion object {
        const val USE = "inmcessentials.spawn.use"
        const val OTHERS = "inmcessentials.spawn.others"
        const val ADMIN = "inmcessentials.spawn.admin"
        const val JOIN_BYPASS = "inmcessentials.spawn.joinbypass"

        /** inmc-menu 시절 권한 — 넘어가는 동안 함께 인정한다(선언하지 않는다 — 메뉴가 선언해 두었거나 LuckPerms 에 있다). */
        const val OLD_USE = "inmcmenu.spawn"
        const val OLD_ADMIN = "inmcmenu.admin"

        /**
         * 부활 자리를 우리 뒤에서 고쳐도 되는 플러그인 — 던전 판 안 부활(RunListener HIGHEST)은 일부러 이긴다.
         * inmc-worlds(HIGHEST)는 다른 모드의 월드로 부활하려 할 때만 그 사람 모드의 스폰으로 고친다(2026-10-15 — 모드 사이로 몸이 새지 않게),
         * WorldGuard·Lands 는 관리자가 그 구역에 부활 깃발을 걸었을 때만 바꾼다(의도된 것).
         */
        private val RESPAWN_ALLOWED = setOf("inmc-dungeon", "inmc-worlds", "WorldGuard", "Lands")

        private const val KIND = "스폰"

        val MESSAGES: Map<String, String> = linkedMapOf(
            "spawn-done" to "<green>스폰으로 이동했습니다.</green>",
            "spawn-failed" to "<red>스폰으로 이동하지 못했습니다.</red>",
            "spawn-failed-other" to "<red>{대상}님을 스폰으로 보내지 못했습니다.</red>",
            "spawn-sent" to "<green>{대상}님을 스폰으로 보냈습니다.</green>",
            "spawn-sent-target" to "<yellow>{플레이어}님이 당신을 스폰으로 보냈습니다.</yellow>",
            "spawn-set" to "<green>서버 스폰을 정했습니다:</green> <white>{값}</white>",
            "spawn-first-set" to "<green>첫 스폰을 정했습니다:</green> <white>{값}</white> <gray>(처음 들어온 사람이 서는 곳)</gray>",
            "spawn-first-cleared" to "<yellow>첫 스폰을 지웠습니다 — 처음 들어온 사람은 서버 스폰으로 갑니다.</yellow>",
            "spawn-warp-set" to "<green>부활 워프를 <white>{워프}</white>(으)로 정했습니다.</green> <gray>부활 순서에서 '부활 워프' 칸을 켜세요.</gray>",
            "spawn-warp-cleared" to "<yellow>부활 워프를 지웠습니다.</yellow>",
            "spawn-warp-missing" to "<red>'{워프}' 워프가 없습니다.</red>",
            "spawn-warp-removed" to "<yellow>워프 {워프} 은(는) 부활 워프였습니다 — 부활 순서의 '부활 워프' 칸은 건너뜁니다.</yellow> <gray>/스폰 관리 에서 다른 워프를 고르세요.</gray>",
        )
    }
}

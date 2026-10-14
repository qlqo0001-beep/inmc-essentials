package com.inmc.essentials.world

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Vanish
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.GameRules
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Boat
import org.bukkit.entity.Enemy
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.world.PortalCreateEvent
import java.time.Duration

/**
 * 명령 없는 월드 규칙 — 잠자기 밤 가속(Sleeping.Speedup) · 네더 포탈 크기·생성 제한(NetherPortal) · 몹의 탈것 타기 막기
 * (Optimizations.PreventEntityBoatEnter — 수레는 우리가 더함).
 */
class WorldRules(private val ess: Ess, private val settings: () -> WorldSettings) : Listener {

    // --- 잠자기 밤 가속 --------------------------------------------------------------------------------

    private val bars = HashMap<String, BossBar>()
    private val barViewers = HashMap<String, MutableSet<Player>>()
    private val lastInform = HashMap<String, Long>()

    /** 가속 중이던 월드 — 아침이 되면 날씨를 걷는다. */
    private val speeding = HashSet<String>()

    /** 검증기용 — 다음 계산 결과(메모리만). */
    @Volatile
    internal var lastResult: SleepMath.Result? = null

    /** 인원에 드는 사람(CMI: cmi.sleepignore · 잠 무시 · 관전 · 잠수 · 투명 빼고). */
    fun counts(player: Player): Boolean {
        val s = settings().sleep
        if (player.hasPermission(WorldFeature.SLEEP_IGNORE) || player.isSleepingIgnored || player.gameMode == GameMode.SPECTATOR) return false
        if (s.excludeAfk && ess.afk.isAfk(player)) return false
        return !Vanish.isVanished(player)
    }

    fun tick(now: Long) {
        val s = settings().sleep
        val active = HashSet<String>()
        if (s.enabled) {
            for (world in Bukkit.getWorlds()) {
                if (!accelerates(world, s)) continue
                val players = world.players
                if (players.none { it.isSleeping }) continue
                val eligible = players.count { counts(it) }
                val sleeping = players.count { it.isSleeping && counts(it) }
                val night = world.time in s.nightStartsAt until TimeText.TICKS_PER_DAY
                val result = SleepMath.decide(s, eligible, sleeping, night || !s.onlyNight)
                lastResult = result
                val key = world.name.lowercase()
                when (result) {
                    is SleepMath.Result.Skip -> {
                        world.time = 0 // 다음 아침 6시(앞으로만 간다)
                        morning(world)
                    }
                    is SleepMath.Result.Speed -> {
                        active += key
                        speeding += key
                        val before = world.time
                        world.fullTime = world.fullTime + result.speed * 20L
                        if (world.time < before) morning(world)
                        else show(world, s.info, sleeping, result.required, result.speed)
                    }
                    is SleepMath.Result.Waiting -> {
                        active += key
                        show(world, s.info, sleeping, result.required, 0)
                    }
                    SleepMath.Result.Idle -> Unit
                }
            }
        }
        for (key in bars.keys.toList()) if (key !in active) hideBar(key)
        speeding.retainAll(active)
    }

    /**
     * 이 월드의 밤을 가속할 수 있나 — 보통 월드이고 설정이 이 월드에 걸리며, **시간이 흐르는 월드**(`advance_time`)일 때만. 바닐라도 시간이 멈춘 월드에서는
     * 잠으로 시간을 넘기지 않는다 — `/시간 정지`·현실 시간 월드에서 잠이 시간을 밀어 다음 맞춤 때 하루가 넘어가던 것(2026-10-10 리뷰). 검증기도 부른다.
     */
    internal fun accelerates(world: World, s: WorldSettings.Sleep = settings().sleep): Boolean =
        world.environment == World.Environment.NORMAL && s.applies(world.name) && world.getGameRuleValue(GameRules.ADVANCE_TIME) != false

    /** 아침 — 바닐라처럼 날씨가 흐르는 월드(`advance_weather`)에서만 비를 걷는다(`/날씨 … 고정` 을 깨지 않게 — 2026-10-10 리뷰). 검증기도 부른다. */
    internal fun morning(world: World) {
        val key = world.name.lowercase()
        val weatherLocked = world.getGameRuleValue(GameRules.ADVANCE_WEATHER) == false
        if (settings().sleep.clearWeather && !weatherLocked && (world.hasStorm() || world.isThundering)) {
            world.setStorm(false)
            world.isThundering = false
        }
        speeding -= key
        hideBar(key)
    }

    private fun show(world: World, info: WorldSettings.Sleep.Info, sleeping: Int, required: Int, speed: Int) {
        val ph = Ph.of().count(sleeping).raw("{필요}", required.toString()).raw("{속도}", speed.toString())
        val key = if (speed > 0) "world-sleep-speeding" else "world-sleep-waiting"
        when (info) {
            WorldSettings.Sleep.Info.NONE -> Unit
            WorldSettings.Sleep.Info.TITLE -> for (p in world.players) {
                p.showTitle(Title.title(Component.empty(), ess.messages.component(key, ph, p), Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(250))))
            }
            WorldSettings.Sleep.Info.ACTIONBAR -> for (p in world.players) p.sendActionBar(ess.messages.component(key, ph, p))
            WorldSettings.Sleep.Info.BOSSBAR -> {
                val name = world.name.lowercase()
                val bar = bars.getOrPut(name) { BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS) }
                bar.name(ess.messages.component(key, ph))
                bar.progress((sleeping.toFloat() / required.coerceAtLeast(1)).coerceIn(0f, 1f))
                val viewers = barViewers.getOrPut(name) { HashSet() }
                for (p in world.players) if (viewers.add(p)) p.showBossBar(bar)
                val gone = viewers.filter { !it.isOnline || it.world != world }
                for (p in gone) {
                    p.hideBossBar(bar)
                    viewers -= p
                }
            }
        }
    }

    private fun hideBar(key: String) {
        val bar = bars.remove(key) ?: return
        barViewers.remove(key)?.forEach { it.hideBossBar(bar) }
    }

    /** 누가 잠들면 그 월드에 "몇 명 더" 를 알린다(InformDelay 마다 한 번). 투명인 사람이 잠들면 볼 수 있는 사람에게만. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBed(event: PlayerBedEnterEvent) {
        val s = settings().sleep
        if (event.bedEnterResult != PlayerBedEnterEvent.BedEnterResult.OK || !s.enabled || !s.inform) return
        val player = event.player
        val world = player.world
        if (!s.applies(world.name)) return
        val now = System.currentTimeMillis()
        val key = world.name.lowercase()
        if (now - (lastInform[key] ?: 0L) < s.informDelaySeconds * 1000L) return
        lastInform[key] = now
        val eligible = world.players.count { counts(it) }
        val sleeping = world.players.count { (it.isSleeping || it == player) && counts(it) }
        val required = s.minSleeping.required(eligible).coerceAtMost(eligible.coerceAtLeast(1))
        val left = (required - sleeping).coerceAtLeast(0)
        val ph = Ph.of().player(player.name).count(sleeping).raw("{필요}", required.toString()).raw("{남은}", left.toString())
        for (p in world.players) if (p == player || Vanish.canSee(p, player)) ess.messages.send(p, "world-sleep-inform", ph)
    }

    // --- 네더 포탈 ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPortal(event: PortalCreateEvent) {
        if (event.reason != PortalCreateEvent.CreateReason.FIRE) return
        val rule = settings().portal.of(event.world.name)
        val player = event.entity as? Player
        if (rule.preventCreation && (player == null || !player.hasPermission(WorldFeature.PORTAL_BYPASS))) {
            event.isCancelled = true
            player?.sendActionBar(ess.messages.component("world-portal-prevented", Ph.of().world(event.world.name), player))
            return
        }
        val blocks = event.blocks.filter { it.type == Material.NETHER_PORTAL }.map { Triple(it.x, it.y, it.z) }
        val (width, height) = PortalSize.of(blocks) ?: return
        val ph = Ph.of().raw("{가로}", width.toString()).raw("{세로}", height.toString())
            .raw("{최대가로}", rule.maxWidth.toString()).raw("{최대세로}", rule.maxHeight.toString())
        if ((width > rule.maxWidth || height > rule.maxHeight) && (player == null || !player.hasPermission(WorldFeature.PORTAL_BYPASS))) {
            event.isCancelled = true
            player?.sendActionBar(ess.messages.component("world-portal-too-big", ph, player))
            return
        }
        if (settings().portal.showSize) player?.sendActionBar(ess.messages.component("world-portal-size", ph, player))
    }

    // --- 몹 탈것 --------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEnter(event: VehicleEnterEvent) {
        val entered = event.entered as? LivingEntity ?: return
        if (entered is Player) return
        val v = settings().vehicles
        val riders = when (event.vehicle) {
            is Boat -> v.boats
            is Minecart -> v.minecarts
            else -> return
        }
        if (!riders.any) return
        val blocked = when (entered) {
            is Enemy -> riders.monsters
            is AbstractVillager -> riders.villagers
            else -> riders.animals
        }
        if (blocked) event.isCancelled = true
    }

    fun clear() {
        for (key in bars.keys.toList()) hideBar(key)
    }
}

/** 잠자기 판단(순수 — `WorldMathTest`). */
object SleepMath {

    sealed interface Result {
        data object Idle : Result
        data class Waiting(val required: Int) : Result
        data class Speed(val speed: Int, val required: Int) : Result
        data object Skip : Result
    }

    /**
     * [eligible] 명 중 [sleeping] 명이 잔다. 바로 아침([WorldSettings.Sleep.skipAt]) → 가속([WorldSettings.Sleep.minSleeping] 이상) → 기다림.
     * 속도 = 기본 속도 × 자는 비율, 최소 속도 아래로는 안 내려간다(CMI BaseSpeed·MinSpeed). [nightNow] 가 false 면 아무것도 안 한다(OnlyDurringNight).
     */
    fun decide(s: WorldSettings.Sleep, eligible: Int, sleeping: Int, nightNow: Boolean): Result {
        if (sleeping <= 0 || eligible <= 0 || !nightNow) return Result.Idle
        val skip = s.skipAt.required(eligible)
        if (skip > 0 && sleeping >= skip.coerceAtMost(eligible)) return Result.Skip
        val required = s.minSleeping.required(eligible).coerceAtMost(eligible).coerceAtLeast(1)
        if (sleeping < required) return Result.Waiting(required)
        val speed = (s.baseSpeed.toLong() * sleeping / eligible).toInt().coerceAtLeast(s.minSpeed)
        return Result.Speed(speed, required)
    }
}

/** 네더 포탈 크기(순수). 포탈 블록들의 (가로, 세로). 비면 null. */
object PortalSize {
    fun of(blocks: List<Triple<Int, Int, Int>>): Pair<Int, Int>? {
        if (blocks.isEmpty()) return null
        val dx = blocks.maxOf { it.first } - blocks.minOf { it.first } + 1
        val dz = blocks.maxOf { it.third } - blocks.minOf { it.third } + 1
        val dy = blocks.maxOf { it.second } - blocks.minOf { it.second } + 1
        return maxOf(dx, dz) to dy
    }
}

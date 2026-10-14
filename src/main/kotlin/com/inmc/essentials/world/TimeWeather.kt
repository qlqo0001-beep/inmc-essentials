package com.inmc.essentials.world

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.persistence.PersistentDataType
import java.time.LocalTime
import java.time.ZoneId

/**
 * 월드 시간·날씨(CMI `/time`·`/weather`). 멈춤은 게임 규칙 `advance_time`, 날씨 고정은 `advance_weather`(26.2 `GameRules` — 지뢰 23) —
 * 월드에 저장되므로 다시 켜도 그대로다.
 *
 * 부드러운 전환·현실 시간 맞추기는 1초 틱([tick])으로 — 그래서 한 번에 `smooth-speed × 20` 틱씩 간다(CMI 는 매 틱). 해가 1초마다 조금씩
 * 뛰어 보이지만 따로 타이머를 만들지 않는다(CLAUDE.md 11).
 */
class TimeWeather(private val ess: Ess, private val settings: () -> WorldSettings) {

    enum class Weather(val key: String) { SUN("sun"), RAIN("rain"), STORM("storm") }

    /** 월드 이름(소문자) → 부드럽게 가는 목표 틱. */
    private val transitions = HashMap<String, Long>()

    /** 지금 현실 시간을 따라가는 월드(소문자). 설정의 목록으로 시작하고 명령으로 켜고 끈다(재시작 뒤엔 설정대로). */
    private val autoReal = LinkedHashSet<String>()

    /**
     * 현실 시간 때문에 우리가 멈춘 월드의 표식 — **월드 PDC** 에 둔다. 멈춤(게임 규칙)이 level.dat 에 남으니 표식도 같은 곳에 남아야 다시 켠 뒤에
     * `/시간 자동실제 정지`(또는 설정에서 뺌)가 멈춤을 풀 수 있다(메모리에만 두면 재시작 뒤 영영 멈춘다 — 2026-10-10 리뷰).
     */
    private val autoFrozenKey = NamespacedKey(com.inmc.essentials.Ess.NAMESPACE, "time_frozen_by_auto")

    private fun frozenByAuto(world: World): Boolean = world.persistentDataContainer.has(autoFrozenKey, PersistentDataType.BYTE)

    private fun markAuto(world: World, on: Boolean) {
        if (on) world.persistentDataContainer.set(autoFrozenKey, PersistentDataType.BYTE, 1)
        else world.persistentDataContainer.remove(autoFrozenKey)
    }

    private var autoCounter = 0

    /** 설정의 목록으로 맞춘다(켤 때·리로드). 목록에 없는데 우리 표식이 남은 월드(재시작 사이에 뺐다)는 멈춤을 푼다. */
    fun loadAuto(worlds: Set<String>) {
        autoReal.clear()
        autoReal += worlds
        for (world in Bukkit.getWorlds()) if (world.name.lowercase() !in autoReal && frozenByAuto(world)) unfreezeAuto(world)
    }

    /**
     * 나중에 읽힌 월드(Multiverse `load: OMIT` · 던전 인스턴스) — 켤 때의 [loadAuto] 가 못 본 월드라 여기서 같은 확인을 한다: 목록에 없는데 우리 표식이
     * 남았으면 멈춤을 푼다(2026-10-10 리뷰).
     */
    fun worldLoaded(world: World) {
        if (world.name.lowercase() !in autoReal && frozenByAuto(world)) unfreezeAuto(world)
    }

    fun autoWorlds(): Set<String> = autoReal.toSet()

    fun isAuto(world: World): Boolean = world.name.lowercase() in autoReal

    // --- 시간 ---------------------------------------------------------------------------------------

    fun isFrozen(world: World): Boolean = world.getGameRuleValue(GameRules.ADVANCE_TIME) == false

    fun setTime(world: World, ticks: Long, smooth: Boolean) {
        val key = world.name.lowercase()
        if (smooth) {
            transitions[key] = Math.floorMod(ticks, TimeText.TICKS_PER_DAY)
        } else {
            transitions.remove(key)
            world.time = Math.floorMod(ticks, TimeText.TICKS_PER_DAY)
        }
    }

    /** 더하기(+)·빼기(-) — 빼기는 날짜도 거꾸로 간다(CMI take). */
    fun addTime(world: World, delta: Long) {
        transitions.remove(world.name.lowercase())
        world.fullTime = (world.fullTime + delta).coerceAtLeast(0)
    }

    fun freeze(world: World, on: Boolean) {
        world.setGameRule(GameRules.ADVANCE_TIME, !on)
        // 손으로 멈추거나 풀면 그 멈춤은 이제 사람의 것 — 자동 끄기가 풀지 않는다.
        markAuto(world, false)
    }

    fun realTicks(): Long {
        val zone = settings().time.timezone.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        return TimeText.ofReal(LocalTime.now(zone))
    }

    fun setAuto(world: World, on: Boolean) {
        val key = world.name.lowercase()
        if (on) {
            autoReal += key
            syncReal(world, smooth = settings().time.autoRealSmooth)
        } else {
            autoReal -= key
            if (frozenByAuto(world)) unfreezeAuto(world)
        }
    }

    private fun unfreezeAuto(world: World) {
        markAuto(world, false)
        world.setGameRule(GameRules.ADVANCE_TIME, true)
    }

    /** 현실 시간으로. 멈춰 두고 맞춘다 — 흐르게 두면 1분 사이 게임 시간이 1시간 넘게 가서 맞출 때마다 해가 튄다. */
    private fun syncReal(world: World, smooth: Boolean) {
        if (!isFrozen(world)) {
            world.setGameRule(GameRules.ADVANCE_TIME, false)
            markAuto(world, true)
        }
        val target = realTicks()
        val gap = TimeText.forward(world.time, target)
        // 몇 분 차이(앞으로 1000틱 안)면 부드럽게, 크게 어긋났으면 바로.
        setTime(world, target, smooth && gap in 1..1000)
    }

    // --- 날씨 ---------------------------------------------------------------------------------------

    fun weatherOf(world: World): Weather = when {
        world.isThundering -> Weather.STORM
        world.hasStorm() -> Weather.RAIN
        else -> Weather.SUN
    }

    /** 지금 날씨가 남은 초(고정이면 null). */
    fun weatherLeftSeconds(world: World): Long? {
        if (world.getGameRuleValue(GameRules.ADVANCE_WEATHER) == false) return null
        val ticks = when (weatherOf(world)) {
            Weather.SUN -> world.clearWeatherDuration.takeIf { it > 0 } ?: world.weatherDuration
            Weather.RAIN -> world.weatherDuration
            Weather.STORM -> minOf(world.thunderDuration, world.weatherDuration).takeIf { it > 0 } ?: world.weatherDuration
        }
        return ticks / 20L
    }

    fun isLocked(world: World): Boolean = world.getGameRuleValue(GameRules.ADVANCE_WEATHER) == false

    /** 날씨를 바꾼다. [seconds] null 이면 바닐라 무작위 길이(설정 `weather.default-duration` 이 있으면 그것). [lock] 이면 고정. */
    fun setWeather(world: World, weather: Weather, seconds: Long?, lock: Boolean) {
        val ticks = (seconds ?: settings().weatherDefaultSeconds.takeIf { it > 0 })?.let { (it * 20).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        world.setGameRule(GameRules.ADVANCE_WEATHER, !lock)
        when (weather) {
            Weather.SUN -> {
                world.setStorm(false)
                world.isThundering = false
                world.clearWeatherDuration = ticks ?: (12_000 + RANDOM.nextInt(168_000))
            }
            Weather.RAIN -> {
                world.setStorm(true)
                world.isThundering = false
                world.weatherDuration = ticks ?: (12_000 + RANDOM.nextInt(12_000))
            }
            Weather.STORM -> {
                world.setStorm(true)
                world.isThundering = true
                val t = ticks ?: (3_600 + RANDOM.nextInt(12_000))
                world.weatherDuration = t
                world.thunderDuration = t
            }
        }
    }

    fun unlock(world: World) {
        world.setGameRule(GameRules.ADVANCE_WEATHER, true)
    }

    /** 지금 날씨 그대로 고정(`/날씨 고정`). */
    fun lock(world: World) {
        world.setGameRule(GameRules.ADVANCE_WEATHER, false)
    }

    // --- 1초 ----------------------------------------------------------------------------------------

    fun tick(now: Long) {
        stepTransitions()
        if (autoReal.isEmpty()) return
        if (++autoCounter < settings().time.autoRealIntervalSeconds) return
        autoCounter = 0
        for (world in Bukkit.getWorlds()) if (world.name.lowercase() in autoReal) syncReal(world, settings().time.autoRealSmooth)
    }

    /** 부드러운 전환 한 걸음(1초 몫). 검증기도 부른다. */
    internal fun stepTransitions() {
        if (transitions.isNotEmpty()) {
            val step = settings().time.smoothSpeed * 20L
            val it = transitions.entries.iterator()
            while (it.hasNext()) {
                val (key, target) = it.next()
                val world = Bukkit.getWorlds().firstOrNull { w -> w.name.lowercase() == key }
                if (world == null) {
                    it.remove()
                    continue
                }
                val left = TimeText.forward(world.time, target)
                if (left <= step) {
                    world.time = target
                    it.remove()
                } else {
                    world.fullTime = world.fullTime + step
                }
            }
        }
    }

    /** 검증기 — 이 월드의 부드러운 전환을 버린다. */
    internal fun cancelTransition(world: World) {
        transitions.remove(world.name.lowercase())
    }

    /** 검증기 — 표식을 읽고 되돌린다(메모리에서만 바꾼 상태를 finally 에서 되돌리려고). */
    internal fun isAutoMarked(world: World): Boolean = frozenByAuto(world)

    internal fun setAutoMarked(world: World, on: Boolean) = markAuto(world, on)

    /** 상태 한 줄의 토큰들 — 24시·12시·틱·멈춤·현실. */
    fun timePh(world: World): Ph = Ph.of().world(world.name).raw("{24시}", TimeText.format24(world.time)).raw("{12시}", TimeText.format12(world.time))
        .raw("{틱}", world.time.toString())

    fun weatherName(weather: Weather): String = ess.messages.raw("world-weather-${weather.key}")

    private companion object {
        val RANDOM = java.util.Random()
    }
}

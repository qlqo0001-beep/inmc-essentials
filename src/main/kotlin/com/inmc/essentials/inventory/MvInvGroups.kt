package com.inmc.essentials.inventory

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Multiverse-Inventories 의 월드 그룹 — **리플렉션으로만**(규칙 14). 5.x 의 진입점(서버 jar 5.3.3 을 javap 로 확인):
 * `MultiverseInventoriesApi.get().getWorldGroupManager().getGroupsForWorld(String): List<WorldGroup>` · `WorldGroup.getName()`.
 *
 * 그룹 열쇠: 그 월드가 든 그룹 이름들(소문자·정렬·쉼표) — 같은 열쇠면 같은 가방(프로필)이다. 어느 그룹에도 없는 월드는 `world:<이름>`
 * (MV-Inv 는 그 월드만의 가방을 쓴다). MV-Inv 가 없거나 부르기에 실패하면 null — 그러면 그룹을 따지지 않는다.
 *
 * **마지막으로 안 열쇠를 월드마다 기억한다**(2026-10-14 검토): 서버를 끌 때 MV-Inv 가 우리보다 먼저 꺼질 수 있는데(paper-plugin.yml 에 의존 순서가 없다),
 * 그때 남기는 모습([SavedInventories.snapshot] — `disable()`)의 그룹이 null 이면 그 위의 오프라인 편집은 그룹 검사 없이 어느 프로필에나 얹힌다.
 * 그래서 플러그인이 꺼져 있으면 그 월드의 마지막 열쇠를 준다. 접속·월드 이동마다 [of] 가 불리므로([OfflineEdits.onJoin]·[OfflineEdits.onWorld])
 * 사람이 있는 월드의 열쇠는 늘 있다. MV-Inv 를 아예 안 쓰는 서버는 기억이 비어 전처럼 null.
 */
object MvInvGroups {

    private class Cached(val plugin: Plugin, val manager: Any, val groupsFor: Method, val nameOf: Method)

    @Volatile
    private var cached: Cached? = null

    /** 월드 → 마지막으로 성공한 열쇠(플러그인이 꺼진 뒤의 대체). */
    private val known = ConcurrentHashMap<String, String>()

    /** [world] 의 그룹 열쇠. MV-Inv 가 없으면 null, 꺼져 있으면 마지막으로 안 열쇠(없으면 null). 메인 스레드에서. */
    fun of(world: String): String? {
        val plugin = Bukkit.getPluginManager().getPlugin("Multiverse-Inventories")?.takeIf { it.isEnabled } ?: return lastKnown(world)
        return try {
            val c = cached?.takeIf { it.plugin === plugin } ?: load(plugin).also { cached = it }
            val groups = (c.groupsFor.invoke(c.manager, world) as? Collection<*>).orEmpty()
            key(world, groups.mapNotNull { g -> g?.let { c.nameOf.invoke(it) as? String } }).also { remember(world, it) }
        } catch (_: Throwable) {
            cached = null
            null
        }
    }

    /** [world] 의 마지막으로 안 열쇠(순수 — 기억만 본다). */
    fun lastKnown(world: String): String? = known[world]

    internal fun remember(world: String, key: String) { known[world] = key }

    internal fun forget(world: String) { known.remove(world) }

    private fun load(plugin: Plugin): Cached {
        val loader = plugin.javaClass.classLoader
        val apiClass = Class.forName("org.mvplugins.multiverse.inventories.MultiverseInventoriesApi", true, loader)
        val api = apiClass.getMethod("get").invoke(null)
        val manager = apiClass.getMethod("getWorldGroupManager").invoke(api) ?: error("WorldGroupManager 가 null")
        // 구현 클래스는 공개가 아닐 수 있다 — 공개 인터페이스의 메서드로 부른다.
        val managerType = Class.forName("org.mvplugins.multiverse.inventories.profile.group.WorldGroupManager", true, loader)
        val groupType = Class.forName("org.mvplugins.multiverse.inventories.profile.group.WorldGroup", true, loader)
        return Cached(plugin, manager, managerType.getMethod("getGroupsForWorld", String::class.java), groupType.getMethod("getName"))
    }

    /** 그룹 열쇠(순수). */
    fun key(world: String, groups: List<String>): String =
        if (groups.isEmpty()) "world:$world" else groups.map { it.lowercase() }.distinct().sorted().joinToString(",")

    /** 같은 가방인가(순수) — 어느 쪽이든 모르면(MV-Inv 없음) 같다고 본다. */
    fun matches(recorded: String?, current: String?): Boolean = recorded == null || current == null || recorded == current

    /** 검증기용 — MV-Inv 가 있으면 진입점을 찾을 수 있나. 없으면 null. */
    fun probe(): String? {
        val plugin = Bukkit.getPluginManager().getPlugin("Multiverse-Inventories")?.takeIf { it.isEnabled } ?: return null
        return try {
            load(plugin)
            null
        } catch (t: Throwable) {
            "Multiverse-Inventories 그룹 진입점을 찾지 못했습니다: $t"
        }
    }
}

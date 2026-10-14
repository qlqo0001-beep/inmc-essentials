package com.inmc.essentials.home

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.UUID
import java.util.logging.Logger

/**
 * 보호구역 안에 홈을 못 두게(CMI Homes.CheckBlockBreak) — WorldGuard·Lands 를 **리플렉션으로만** 묻는다(규칙 14, `ReflectionPurityTest`).
 * "이 사람이 여기서 블록을 부술(지을) 수 있나" 로 본다. 플러그인이 없거나 묻다 실패하면 **허락**(홈을 못 만드는 쪽보다 낫다) — 실패는 한 번 경고.
 *
 * 진입점(ARCHITECTURE "리플렉션 진입점"):
 * - WorldGuard 7: `WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(LocalPlayer, weWorld)` ·
 *   `…getRegionContainer().createQuery().testBuild(weLocation, LocalPlayer, StateFlag...)` · `WorldGuardPlugin.wrapPlayer(Player)` ·
 *   WorldEdit `BukkitAdapter.adapt(Location|World)`.
 * - Lands 8: `LandsIntegration.of(Plugin).getArea(Location)?.hasRoleFlag(UUID, Flags.BLOCK_BREAK)`.
 */
class Protection(private val owner: Plugin, private val logger: Logger) {

    @Volatile
    private var warnedWorldGuard = false

    @Volatile
    private var warnedLands = false

    @Volatile
    private var lands: Any? = null

    /** 메인 스레드. 막히면 그 플러그인 이름, 되면 null. */
    fun blockedBy(player: Player, at: Location): String? = when {
        !worldGuard(player, at) -> "WorldGuard"
        !lands(player, at) -> "Lands"
        else -> null
    }

    /** 검증기 — 붙어 있는 보호 플러그인과 그 연결 상태. */
    fun describe(): List<String> = buildList {
        if (enabled("WorldGuard") != null) add("WorldGuard" + if (warnedWorldGuard) "(연결 실패)" else "")
        if (enabled("Lands") != null) add("Lands" + if (warnedLands) "(연결 실패)" else "")
    }

    private fun enabled(name: String): Plugin? = Bukkit.getPluginManager().getPlugin(name)?.takeIf { it.isEnabled }

    private fun worldGuard(player: Player, at: Location): Boolean {
        val wgPlugin = enabled("WorldGuard") ?: return true
        return try {
            val cl = wgPlugin.javaClass.classLoader
            val wgClass = Class.forName("com.sk89q.worldguard.WorldGuard", true, cl)
            val wg = wgClass.getMethod("getInstance").invoke(null)
            val platformClass = Class.forName("com.sk89q.worldguard.internal.platform.WorldGuardPlatform", true, cl)
            val platform = wgClass.getMethod("getPlatform").invoke(wg)
            val localPlayerClass = Class.forName("com.sk89q.worldguard.LocalPlayer", true, cl)
            val localPlayer = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin", true, cl)
                .getMethod("wrapPlayer", Player::class.java).invoke(wgPlugin, player)
            val adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl)

            val sessionClass = Class.forName("com.sk89q.worldguard.session.SessionManager", true, cl)
            val sessions = platformClass.getMethod("getSessionManager").invoke(platform)
            val weWorld = adapter.getMethod("adapt", World::class.java).invoke(null, at.world)
            val hasBypass = sessionClass.methods.first { it.name == "hasBypass" && it.parameterCount == 2 }
            if (hasBypass.invoke(sessions, localPlayer, weWorld) == true) return true

            val containerClass = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer", true, cl)
            val container = platformClass.getMethod("getRegionContainer").invoke(platform)
            val query = containerClass.getMethod("createQuery").invoke(container)
            val weLocation = adapter.getMethod("adapt", Location::class.java).invoke(null, at)
            val stateFlag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag", true, cl)
            val testBuild: Method = Class.forName("com.sk89q.worldguard.protection.regions.RegionQuery", true, cl).methods.first {
                it.name == "testBuild" && it.parameterCount == 3 && it.parameterTypes[1] == localPlayerClass
            }
            testBuild.invoke(query, weLocation, localPlayer, java.lang.reflect.Array.newInstance(stateFlag, 0)) as Boolean
        } catch (t: Throwable) {
            if (!warnedWorldGuard) {
                warnedWorldGuard = true
                logger.warning("WorldGuard 보호구역을 묻지 못했습니다 — 홈 금지 검사에서 WorldGuard 는 건너뜁니다: ${t.javaClass.simpleName} ${t.message}")
            }
            true
        }
    }

    private fun lands(player: Player, at: Location): Boolean {
        val landsPlugin = enabled("Lands") ?: return true
        return try {
            val cl = landsPlugin.javaClass.classLoader
            val integrationClass = Class.forName("me.angeschossen.lands.api.LandsIntegration", true, cl)
            val integration = lands ?: integrationClass.getMethod("of", Plugin::class.java).invoke(null, owner).also { lands = it }
            val area = integrationClass.getMethod("getArea", Location::class.java).invoke(integration, at) ?: return true
            val roleFlag = Class.forName("me.angeschossen.lands.api.flags.type.RoleFlag", true, cl)
            val blockBreak = Class.forName("me.angeschossen.lands.api.flags.type.Flags", true, cl).getField("BLOCK_BREAK").get(null)
            Class.forName("me.angeschossen.lands.api.land.Area", true, cl)
                .getMethod("hasRoleFlag", UUID::class.java, roleFlag).invoke(area, player.uniqueId, blockBreak) as Boolean
        } catch (t: Throwable) {
            if (!warnedLands) {
                warnedLands = true
                logger.warning("Lands 영지를 묻지 못했습니다 — 홈 금지 검사에서 Lands 는 건너뜁니다: ${t.javaClass.simpleName} ${t.message}")
            }
            true
        }
    }
}

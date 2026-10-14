package com.inmc.essentials.command

import com.inmc.essentials.Ess
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Level

/**
 * 기능들의 명령을 모았다가 Paper 의 `COMMANDS` 생애주기 사건에서 한꺼번에 등록한다.
 *
 * 그 사건은 `onEnable` 이 끝난 **뒤**에 온다(모든 플러그인이 켜진 뒤 한 번 · `/minecraft:reload` 와 Bukkit `/reload` 때 다시 —
 * Leaf 26.2 `CraftServer.reload` 에서 `callReloadableRegistrarEvent(COMMANDS, …, RELOAD)` 확인). 그때마다 **모은 것 전부를 다시** 넣는다
 * (등록기는 매번 새것이다). 그래서 기능은 `enable()` 안에서 [add] 만 하면 된다. 사건이 이미 지난 뒤 들어온 것은 다음 사건까지 안 보인다
 * — 경고를 남긴다(그런 일은 `enable()` 밖에서 [add] 를 부를 때뿐이다).
 *
 * 이름 규칙(CLAUDE.md): 바닐라·Paper 명령 루트([RESERVED])는 쓰지 않는다 — 예외는 귓속말(`msg`·`tell`·`w`)과 `list`. 어기면 그 이름만 빼고
 * 로그를 남긴다(메인 이름이면 명령째 뺀다). 다른 기능이 이미 쓴 이름도 뺀다 — 나중에 등록한 쪽이 **조용히** 이기는 것을 막는다.
 */
class CommandRegistry(private val ess: Ess) {

    /** [owner] = 넣을 때 켜고 있던 기능의 id(`ess.owner`) — 그 기능이 켜다 실패하면 [removeOwner] 로 뺀다. */
    class Entry(val node: LiteralCommandNode<CommandSourceStack>, val description: String, val aliases: List<String>, val owner: String? = null)

    private val entries = CopyOnWriteArrayList<Entry>()

    private class AdminChild(val node: LiteralCommandNode<CommandSourceStack>, val owner: String?)

    /** `/에센셜` 아래에 붙을 하위 명령(서버 관리 기능의 데이터 이전·가져오기 등). */
    private val adminChildren = CopyOnWriteArrayList<AdminChild>()

    @Volatile
    private var fired = false

    /**
     * 루트 명령 하나. [node] 는 `Commands.literal("한글이름")…build()`, [aliases] 는 영어 별칭들(4절 표).
     * 권한은 노드의 `requires { it.sender.hasPermission(…) }` 로 — 그 노드는 [com.inmc.essentials.feature.Feature.permissions] 에 있어야 한다.
     */
    fun add(node: LiteralCommandNode<CommandSourceStack>, description: String, aliases: List<String> = emptyList()) {
        val name = node.literal
        val problem = problem(name)
        if (problem != null) {
            ess.logger.severe("명령 /$name 을(를) 등록하지 않습니다 — $problem")
            return
        }
        val kept = aliases.distinct().filter { alias ->
            val why = if (alias == name) "메인 이름과 같음" else problem(alias)
            if (why != null) ess.logger.warning("/$name 의 별칭 $alias 를 뺍니다 — $why")
            why == null
        }
        if (fired) ess.logger.warning("/$name 은(는) 명령 등록이 끝난 뒤에 들어왔습니다 — 다음 명령 다시 읽기(/minecraft:reload)까지 안 보입니다. enable() 안에서 add 할 것")
        entries += Entry(node, description, kept, ess.owner)
    }

    /** `/에센셜 <이름> …` 하위 명령. 권한은 노드의 requires 로(관리 루트 자체는 `inmcessentials.admin`). */
    fun addAdmin(node: LiteralCommandNode<CommandSourceStack>) {
        if (adminChildren.any { it.node.literal == node.literal } || node.literal in AdminCommand.BUILT_IN) {
            ess.logger.severe("/에센셜 ${node.literal} 은(는) 이미 있습니다 — 등록하지 않습니다")
            return
        }
        adminChildren += AdminChild(node, ess.owner)
    }

    /** 켜다 실패한 기능이 넣은 명령(루트·`/에센셜` 하위)을 뺀다 — 이름도 풀린다. 뺀 수. */
    internal fun removeOwner(owner: String): Int {
        val mine = entries.filter { it.owner == owner }
        entries.removeAll(mine.toSet())
        val admin = adminChildren.filter { it.owner == owner }
        adminChildren.removeAll(admin.toSet())
        return mine.size + admin.size
    }

    fun all(): List<Entry> = entries.toList()

    /** 이미 쓰인 이름(메인·별칭, 이 플러그인 안). */
    fun names(): Set<String> = entries.flatMapTo(HashSet()) { listOf(it.node.literal) + it.aliases } + AdminCommand.NAMES

    /** `onEnable` 에서 한 번. 사건이 올 때마다 관리 명령을 새로 짓고 모은 것 전부를 넣는다. */
    fun hook() {
        ess.plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            fired = true
            val registrar = event.registrar()
            val admin = AdminCommand(ess).build(adminChildren.map { it.node })
            registrar.register(admin, "inmc-essentials 관리", AdminCommand.ALIASES)
            for (entry in entries) {
                try {
                    registrar.register(entry.node, entry.description, entry.aliases)
                } catch (t: Throwable) {
                    ess.logger.log(Level.SEVERE, "명령 /${entry.node.literal} 등록 실패", t)
                }
            }
        }
    }

    private fun problem(name: String): String? = when {
        name.isBlank() || name.contains(' ') || name.contains(':') -> "이름에 빈칸·콜론"
        reserved(name) -> "바닐라/Paper 명령 이름"
        name in names() -> "이미 다른 기능이 쓰는 이름"
        else -> null
    }

    companion object {

        /** 바닐라 귓속말과 목록만 덮는다(사용자 결정 — 4절). */
        val ALLOWED_VANILLA: Set<String> = setOf("msg", "tell", "w", "list")

        /** 쓰지 않는 루트 이름 — 바닐라 명령 + Paper/Bukkit 명령(계획 0절). */
        val RESERVED: Set<String> = (
            "advancement attribute ban ban-ip banlist bossbar clear clone damage data datapack debug defaultgamemode deop dialog difficulty " +
                "effect enchant execute experience fill fillbiome forceload function gamemode gamerule give help item jfr kick kill list locate " +
                "loot me msg op pardon pardon-ip particle perf place playsound random recipe reload return ride rotate save-all save-off save-on " +
                "say schedule scoreboard seed setblock setidletimeout setworldspawn spawnpoint spectate spreadplayers stop stopsound summon tag " +
                "team teammsg tell teleport tellraw test tick time title tm tp transfer trigger version w waypoint weather whitelist worldborder xp " +
                "plugins pl ver about rl timings tps mspt paper spigot restart"
            ).split(' ').toSet()

        fun reserved(name: String): Boolean = name.lowercase() in RESERVED && name.lowercase() !in ALLOWED_VANILLA
    }
}

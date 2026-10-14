package com.inmc.essentials.server

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.AtomicFiles
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File

/**
 * 커스텀 텍스트(`texts` 폴더의 txt — CMI CustomText). `/텍스트` 로 보이고, `/책` 이 책으로 만들고, 접속 때 하나를 보일 수 있다(`texts.join-text`).
 * 파일은 I/O 스레드에서 읽는다. `<AutoAlias>` 인 것은 `/<이름>` Bukkit 명령으로도 올린다(이름이 비어 있을 때만).
 */
class TextService(private val ess: Ess, private val feature: ServerFeature) : Listener {

    @Volatile
    var texts: Map<String, TextDoc> = emptyMap()
        private set

    private val aliases = LinkedHashMap<String, Command>()

    val folder: File get() = ess.io.file("texts")

    fun get(name: String): TextDoc? = texts[name.lowercase()]

    fun loadAsync(then: () -> Unit = {}) {
        val lines = feature.settings.textLinesPerPage
        ess.io.async({
            val dir = folder
            if (!dir.isDirectory) {
                dir.mkdirs()
                for (name in DEFAULTS) ess.io.copyDefault("texts/$name.txt", File(dir, "$name.txt"))
            }
            dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }.orEmpty().mapNotNull { f ->
                val name = f.name.removeSuffix(".txt")
                if (!TextDoc.validName(name)) {
                    ess.logger.warning("texts/${f.name} — 이름에 빈칸·점·콜론을 쓸 수 없어 건너뜁니다")
                    null
                } else {
                    TextDoc.parse(name, f.readText(Charsets.UTF_8), lines)
                }
            }
        }) { docs ->
            texts = docs.associateBy { it.name.lowercase() }
            registerAliases()
            then()
        }
    }

    /** 그 사람이 볼 수 있나 — `<ReqPermission>` 은 `inmcessentials.server.text.<이름>`(동적 노드 — 기본 op). */
    fun canSee(sender: CommandSender, doc: TextDoc): Boolean =
        !doc.requiresPermission || sender.hasPermission("${ServerFeature.TEXT_NODE}.${doc.name.lowercase()}") || sender.hasPermission(ServerFeature.TEXT_ADMIN)

    fun listed(sender: CommandSender): List<TextDoc> =
        texts.values.filter { (!it.hidden || sender.hasPermission(ServerFeature.TEXT_ADMIN)) && canSee(sender, it) }.sortedBy { it.name.lowercase() }

    /** 한 쪽을 [target] 에게. [page] 는 1부터. 쪽이 여럿이면 아래에 앞뒤 단추. */
    fun show(target: CommandSender, doc: TextDoc, page: Int) {
        val total = doc.pages.size
        val p = page.coerceIn(1, total)
        val viewer = target as? Player
        val ph = Ph.of().player(target.name)
        if (total > 1) ess.messages.send(target, "server-text-header", Ph.of().value(doc.name).raw("{쪽}", p.toString()).raw("{전체}", total.toString()))
        for (line in doc.pages[p - 1]) target.sendMessage(Text.render(line, ph, viewer))
        if (total > 1) {
            var nav: Component = Component.empty()
            if (p > 1) nav = nav.append(ess.messages.component("server-text-prev", null, viewer).clickEvent(ClickEvent.runCommand("/텍스트 ${doc.name} ${p - 1}")))
            if (p < total) nav = nav.append(Component.space()).append(ess.messages.component("server-text-next", null, viewer).clickEvent(ClickEvent.runCommand("/텍스트 ${doc.name} ${p + 1}")))
            target.sendMessage(nav)
        }
    }

    // --- 접속 글 ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val name = feature.settings.joinText.takeIf { it.isNotEmpty() } ?: return
        val player = event.player
        player.scheduler.runDelayed(ess.plugin, { _ -> get(name)?.let { if (canSee(player, it)) show(player, it, 1) } }, null, 10L)
    }

    // --- 편집 ---------------------------------------------------------------------------------------

    /** 원문을 비동기로 읽어 [then] 에(메인). 없으면 빈 글. */
    fun readRaw(name: String, then: (String) -> Unit) {
        val file = File(folder, "$name.txt")
        ess.io.async({ if (file.isFile) file.readText(Charsets.UTF_8) else "" }, then)
    }

    fun save(name: String, text: String, then: () -> Unit) {
        val file = File(folder, "$name.txt")
        ess.io.async({
            folder.mkdirs()
            AtomicFiles.write(file, text)
        }) { loadAsync(then) }
    }

    // --- 자동 명령(<AutoAlias>) ---------------------------------------------------------------------------

    fun registerAliases() {
        unregisterAliases()
        val map = Bukkit.getCommandMap()
        for (doc in texts.values.filter { it.autoAlias }) {
            val name = doc.name.lowercase()
            if (map.getCommand(name) != null || com.inmc.essentials.command.CommandRegistry.reserved(name)) {
                ess.logger.info("텍스트 ${doc.name} 의 <AutoAlias> — /$name 이(가) 이미 있어 올리지 않습니다(/텍스트 ${doc.name} 로 보세요)")
                continue
            }
            val command = object : Command(name, "텍스트 ${doc.name}", "/$name [쪽]", emptyList()) {
                override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
                    val current = get(name) ?: return true
                    if (!canSee(sender, current)) {
                        ess.messages.send(sender, "no-permission")
                        return true
                    }
                    show(sender, current, args.firstOrNull()?.toIntOrNull() ?: 1)
                    return true
                }

                override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> = emptyList()
            }
            if (runCatching { map.register(FALLBACK, command) }.getOrDefault(false)) aliases[name] = command
            else remove(map, name, command)
        }
        if (aliases.isNotEmpty()) Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
    }

    fun unregisterAliases() {
        if (aliases.isEmpty()) return
        val map = Bukkit.getCommandMap()
        for ((name, command) in aliases) remove(map, name, command)
        aliases.clear()
    }

    private fun remove(map: org.bukkit.command.CommandMap, name: String, command: Command) {
        runCatching {
            command.unregister(map)
            val known = map.knownCommands
            if (known[name] === command) known.remove(name)
            known.remove("$FALLBACK:$name")
        }
    }

    companion object {
        const val FALLBACK = "inmctext"
        val DEFAULTS = listOf("rules", "welcome")
    }
}

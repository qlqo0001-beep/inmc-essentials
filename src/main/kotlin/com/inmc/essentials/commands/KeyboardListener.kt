package com.inmc.essentials.commands

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent
import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendSuggestionsEvent
import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import com.mojang.brigadier.tree.RootCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.command.UnknownCommandEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 한/영 자판 변환(연구 H 추천 1) — 실행·탭 완성·없는 명령 안내.
 *
 * - **실행**(LOWEST): 친 루트가 그 사람이 쓸 수 있는 명령에 없을 때만, 키가 **하나**에 맞으면 루트를 바꾼다. 하위 명령은 그 자리의 자식이
 *   **전부 글자(리터럴)일 때만**(사람 이름·자유 글 인자가 올 수 있는 자리는 건드리지 않는다). 던전 막기(LOW)는 바꾼 뒤의 이름을 본다.
 * - **탭 완성**(선택 C): 손님에게 보내는 명령 트리에 **한글 명령의 영타형**만 리다이렉트 별칭으로 더한다(`/tmv` 에서 `tmvhs` 가 제안되고
 *   뒤의 글자 하위 명령도 손님 쪽에서 완성된다). 영어 명령의 한글형(`넴주`)은 실행만. 이 사건은 대개 두 번 온다 — 한 번만 고친다. 안에서 권한·PAPI 를
 *   묻지 않는다(보내는 트리는 이미 권한으로 걸러져 있다 — 디스코드의 LuckPerms 잠금 교훈). 그 별칭은 서버에는 없으므로 서버가 내는 인자 제안
 *   (사람 이름 등)은 `AsyncPlayerSendSuggestionsEvent` 에서 바꾼 글로 다시 물어 채운다([onSuggestions] — 메인 스레드에서 올 때만).
 * - **안내**(UnknownCommandEvent): 같은 키가 둘 이상이거나 비슷한 이름이 있으면 "혹시 /스폰?". 루트는 있는데 그 사람의 트리에 없으면 권한 안내.
 *   이 사건으로는 다시 실행할 수 없다(메시지만) — 그래서 실행은 위 전처리가 맡는다.
 */
class KeyboardListener(private val ess: Ess, private val feature: CommandsFeature) : Listener {

    /** 손님에게 보낸 트리의 이름만(권한으로 걸러진 것). 깊이 [DEPTH] 까지. */
    private class Node(val children: Map<String, Node>, val hasArgument: Boolean) {
        val index: KeyboardIndex by lazy { KeyboardIndex(children.keys) }
    }

    private val trees = ConcurrentHashMap<UUID, Node>()

    @Volatile
    private var global: KeyboardIndex? = null

    /** 명령이 늘거나 줄면(서버 준비 끝·별칭 등록) 전체 색인을 다시. */
    fun invalidate() {
        global = null
    }

    private fun globalIndex(): KeyboardIndex = global ?: KeyboardIndex(
        runCatching { Bukkit.getCommandMap().knownCommands.keys.filter { ':' !in it }.toList() }.getOrDefault(emptyList()),
    ).also { global = it }

    // --- 실행 ---------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val s = feature.settings
        if (!s.keyboard) return
        val converted = convert(event.player, event.message, s.keyboardSubcommands) ?: return
        event.message = converted
    }

    /** 바꾼 명령(`/` 포함) 또는 null(그대로). */
    fun convert(player: Player, message: String, subcommands: Boolean): String? {
        if (!message.startsWith("/")) return null
        val tokens = message.substring(1).split(' ')
        val root = tokens.firstOrNull()?.takeIf { it.isNotEmpty() && ':' !in it } ?: return null
        val tree = trees[player.uniqueId]
        var changed = false
        val out = tokens.toMutableList()
        // 서버에 그 이름이 있으면(권한이 없어도) 바꾸지 않는다 — 실제 이름이 늘 먼저다.
        val known = tree?.children?.containsKey(root) == true || globalIndex().contains(root)
        if (!known) {
            val target = (tree?.index ?: globalIndex()).convert(root) ?: return null
            out[0] = target
            changed = true
        }
        if (subcommands && tree != null) {
            var node = tree.children[out[0]]
            var i = 1
            while (node != null && i < out.size && node.children.isNotEmpty() && !node.hasArgument) {
                val token = out[i]
                if (token.isEmpty()) break
                val next = node.children[token]
                if (next != null) {
                    node = next
                } else {
                    val target = node.index.convert(token) ?: break
                    out[i] = target
                    changed = true
                    node = node.children[target]
                }
                i++
            }
        }
        return if (changed) "/" + out.joinToString(" ") else null
    }

    // --- 탭 완성 ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onSend(event: AsyncPlayerSendCommandsEvent<CommandSourceStack>) {
        // 두 번(비동기 → 동기) 오는 것 중 하나만. 비동기가 이미 돌았으면 동기는 건너뛴다.
        if (!(event.isAsynchronous || !event.hasFiredAsync())) return
        val root: RootCommandNode<CommandSourceStack> = event.commandNode
        trees[event.player.uniqueId] = snapshot(root, 0)
        if (!feature.settings.keyboard || !feature.settings.keyboardTab) return
        val existing = root.children.map { it.name }.toHashSet()
        for (child in root.children.toList()) {
            if (child !is LiteralCommandNode<CommandSourceStack> || ':' in child.name || !Keyboard.hasHangul(child.name)) continue
            val alias = Keyboard.keys(child.name)
            if (!Keyboard.isTypable(alias) || !existing.add(alias)) continue
            val target = child.redirect ?: child
            val node = LiteralArgumentBuilder.literal<CommandSourceStack>(alias)
                .requires(child.requirement)
                .executes(child.command)
                .redirect(target)
                .build()
            root.addChild(node)
        }
    }

    /**
     * `/dlehd ` 처럼 영타로 친 한글 명령의 **인자** 제안 — 서버 명령 트리에는 그 이름이 없어 제안이 비어 온다. 바꾼 글(`/이동 `)로 명령 표에
     * 다시 물어 마지막 낱말 자리에 넣는다. 다른 플러그인이 제안을 비동기로 처리해 이 사건이 비동기로 오면 건드리지 않는다(명령의 제안 함수는 메인 것).
     *
     * Paper 는 제안이 비어 있으면 이 사건을 **취소한 채** 쏜다(`ServerGamePacketListenerImpl.sendServerSuggestions` — `setCancelled(suggestions.isEmpty())`
     * 뒤 `callEvent`, 취소면 패킷을 안 보낸다). 그래서 `ignoreCancelled` 를 쓰면 바로 이 경우에 한 번도 불리지 않고, 채운 뒤에는 취소를 풀어야 손님에게 간다.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onSuggestions(event: AsyncPlayerSendSuggestionsEvent) {
        if (event.isAsynchronous || !event.suggestions.isEmpty) return
        val s = feature.settings
        if (!s.keyboard || !s.keyboardTab) return
        val buffer = event.buffer
        val space = buffer.indexOf(' ')
        if (!buffer.startsWith("/") || space < 0) return
        val root = buffer.substring(1, space)
        if (root.isEmpty() || ':' in root || globalIndex().contains(root)) return
        val converted = convert(event.player, buffer, s.keyboardSubcommands) ?: return
        val list = runCatching { Bukkit.getCommandMap().tabComplete(event.player, converted.substring(1)) }.getOrNull().orEmpty()
        if (list.isEmpty()) return
        val builder = SuggestionsBuilder(buffer, buffer.lastIndexOf(' ') + 1)
        list.distinct().forEach { builder.suggest(it) }
        event.suggestions = builder.build()
        event.isCancelled = false
    }

    private fun snapshot(node: CommandNode<CommandSourceStack>, depth: Int): Node {
        val children = HashMap<String, Node>()
        var argument = false
        for (child in node.children) {
            if (child is LiteralCommandNode<*>) {
                if (depth == 0 && ':' in child.name) continue
                children[child.name] = if (depth + 1 < DEPTH) snapshot(child, depth + 1) else Node(emptyMap(), true)
            } else {
                argument = true
            }
        }
        return Node(children, argument)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        trees.remove(event.player.uniqueId)
    }

    // --- 없는 명령 안내 ---------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL)
    fun onUnknown(event: UnknownCommandEvent) {
        val player = event.sender as? Player ?: return
        val s = feature.settings
        val line = event.commandLine.removePrefix("/")
        val root = line.substringBefore(' ')
        if (root.isEmpty() || ':' in root) return
        val tree = trees[player.uniqueId]
        val mine = tree?.children?.keys
        val exists = globalIndex().contains(root)
        val hint: Component = when {
            // 루트가 서버에 있는데 그 사람의 트리에 없다 = 권한이 없다(인자 오류는 바닐라 안내를 그대로 둔다).
            exists && mine != null && root !in mine -> {
                if (!s.permissionHint) return
                val node = runCatching { Bukkit.getCommandMap().getCommand(root)?.permission }.getOrNull()
                val text = ess.messages.component("commands-hint-no-permission", Ph.of().value("/$root"), player)
                if (node != null && (s.permissionShowNode || player.hasPermission(CommandsFeature.SEE_PERMISSION))) {
                    text.hoverEvent(HoverEvent.showText(ess.messages.component("commands-hint-permission-node", Ph.of().value(node), player)))
                } else {
                    text
                }
            }
            exists -> return
            else -> {
                if (!s.unknownHint && s.similarThreshold <= 0) return
                val keyboard = if (s.unknownHint) hintCandidates(player, root) else emptyList()
                val candidates = keyboard.ifEmpty {
                    listOfNotNull(Similarity.best(root.lowercase(), mine ?: emptySet(), s.similarThreshold))
                }
                if (candidates.isEmpty()) return
                val rest = line.substringAfter(' ', "")
                candidates.take(3).fold(ess.messages.component("commands-hint-did-you-mean", null, player)) { acc, name ->
                    val full = "/" + name + if (rest.isEmpty()) "" else " $rest"
                    acc.append(Component.space()).append(
                        ess.messages.component("commands-hint-candidate", Ph.of().value("/$name"), player)
                            .clickEvent(ClickEvent.suggestCommand(full))
                            .hoverEvent(HoverEvent.showText(ess.messages.component("commands-hint-candidate-hover", Ph.of().value(full), player))),
                    )
                }
            }
        }
        val original = event.message()
        event.message(if (original == null) hint else original.append(Component.newline()).append(hint))
    }

    /** 없는 명령 [root] 의 자판 후보(그 사람의 트리, 없으면 전체) — [onUnknown] 과 검증기가 같이 쓴다. `UnknownCommandEvent` 는 밖에서 만들 수 없다. */
    internal fun hintCandidates(player: Player, root: String): List<String> = (trees[player.uniqueId]?.index ?: globalIndex()).candidates(root)

    companion object {
        const val DEPTH = 3
    }
}

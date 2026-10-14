package com.inmc.essentials.hologram

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StorageMode
import kr.inmc.core.item.StoredItem
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.Base64
import java.util.UUID
import java.util.logging.Level

/**
 * 홀로그램을 세상에 세우는 쪽. **엔티티는 저장하지 않는다**(`isPersistent = false`) — 정의는 `holograms.yml` 에만 있고, 엔티티는
 * 보는 사람이 있을 때만 세우고(청크가 올라와 있을 때) 아무도 안 보면 거둔다. 우리 엔티티에는 PDC `inmcess:holo`(= 홀로그램 열쇠)를 찍어
 * 남은 것(플러그인 리로드 등)을 알아보고 치운다.
 *
 * **엔티티 묶음(set)**: 한 쪽(page)의 줄마다 TextDisplay/ItemDisplay(+ 누를 수 있으면 Interaction). 묶음의 열쇠는 (쪽, 보는 사람?) 이다 —
 * 그 쪽에 사람마다 다른 글(`{player}`·사람 PAPI)이 없으면 보는 사람 null = 모두가 같은 묶음을 본다. 있으면 보는 사람마다 묶음 하나
 * (상한 [HologramSettings.maxPersonalViewers] — 이미 자기 묶음이 있는 사람이 먼저, 넘는 사람은 사람 토큰을 지운 공용 묶음). 엔티티는 기본으로
 * 아무에게도 안 보이고(`isVisibleByDefault = false`) 정해진 사람에게만 `showEntity` — 그래서 권한·조건·개인 끄기·쪽이 사람마다 갈린다.
 *
 * **쪽**: 줄이 없는 쪽은 건너뛴다(자동 넘김·누르기·`/홀로그램 넘기기`) — 빈 쪽으로 넘어가 아무것도 안 보이고 되돌아갈 것도 없는 일이 없게.
 *
 * 한 홀로그램이 던져도(PAPI 확장·아이템·세우기) 다른 홀로그램은 돈다 — 홀로그램마다 따로 잡고, 같은 정의의 같은 오류는 한 번만 알린다.
 * 전부 메인 스레드(1초 반복 작업 한 칸 + 사건).
 */
class HologramEngine(private val ess: Ess, private val feature: HologramsFeature, val store: HologramStore) {

    val settings: HologramSettings get() = feature.settings

    val tag = NamespacedKey(Ess.NAMESPACE, "holo")
    val lineTag = NamespacedKey(Ess.NAMESPACE, "holo_line")

    private data class SetKey(val page: Int, val viewer: UUID?)

    private class SpawnedLine(val line: HoloLine, val display: Display, val interaction: Interaction?, var last: Component?)

    private class Spawned(val key: SetKey, val lines: List<SpawnedLine>) {
        val viewers = HashSet<UUID>()
        fun valid(): Boolean = lines.all { it.display.isValid && (it.interaction?.isValid ?: true) }
        fun entities(): List<Entity> = lines.flatMap { listOfNotNull(it.display, it.interaction) }
    }

    private class Live(var def: Hologram) {
        /** 모두의 기준 쪽 — [shown] 의 자리(0부터). */
        var globalPage = 0
        val offsets = HashMap<UUID, Int>()
        var assigned = HashMap<UUID, SetKey>()
        val sets = HashMap<SetKey, Spawned>()

        /** 쪽마다 "사람마다의 글이 있나" · 보여 줄 쪽들 — 정의·설정이 바뀔 때만 다시 센다(1초마다 정규식을 돌리지 않게). */
        var personal: List<Boolean> = emptyList()
        var shown: List<Int> = listOf(0)
        var derivedFor: Pair<Hologram, HologramSettings>? = null
    }

    private val live = HashMap<String, Live>()

    /** 우리가 세운 엔티티 — 표시가 달렸는데 여기 없으면 남은 것(고아). */
    private val ours = HashSet<UUID>()

    private val lastClick = HashMap<UUID, Long>()

    private val icons = HashMap<HoloLine, ItemStack>()

    /** 오류를 알린 정의(열쇠 → 그 정의) — 같은 정의의 오류는 한 번만 로그에. 정의가 바뀌면 다시 알린다. */
    private val failing = HashMap<String, Hologram>()

    private var seconds = 0L

    /** 검증기 — 클릭 명령을 실제로 돌리지 않고 여기에 모은다. */
    internal var capture: MutableList<String>? = null

    /** 검증기 — 이 사람에게는 개인 보기를 끈 것처럼(PlayerSettings 를 건드리지 않고 메모리만). */
    internal val forceHidden = HashSet<UUID>()

    // --- 반복 작업 --------------------------------------------------------------------------------------

    fun tick() {
        seconds++
        val defs = store.all()
        val keys = defs.mapTo(HashSet()) { it.key }
        for (key in live.keys.filter { it !in keys }) live.remove(key)?.let { despawn(it) }
        for (h in defs) guarded(h) { step(h, seconds) }
        if (seconds % 60 == 0L) {
            val now = System.currentTimeMillis()
            lastClick.values.removeIf { now - it > 60_000 }
        }
    }

    /** 한 홀로그램의 1초 — [at] 초째(자동 넘김·갱신 주기를 이것으로 센다). 검증기는 이것을 직접 부른다(다른 홀로그램을 건드리지 않게). */
    internal fun step(h: Hologram, at: Long) {
        val l = live.getOrPut(h.key) { Live(h) }
        derive(l, h)
        val pageEvery = h.pageSeconds ?: settings.pageSeconds
        if (pageEvery > 0 && l.shown.size > 1 && at % pageEvery == 0L) l.globalPage = (l.globalPage + 1) % l.shown.size
        val updateEvery = h.updateSeconds ?: settings.updateSeconds
        refresh(h, updateEvery > 0 && at % updateEvery == 0L)
    }

    /** 저장소가 바뀐 직후(메인) — 바뀐 홀로그램만 곧바로 다시 세운다(1초 기다리지 않게). */
    fun sync() {
        icons.clear()
        val defs = store.all()
        val keys = defs.mapTo(HashSet()) { it.key }
        for (key in live.keys.filter { it !in keys }) live.remove(key)?.let { despawn(it) }
        failing.keys.retainAll(keys)
        for (h in defs) {
            val l = live[h.key]
            if (l == null || l.def !== h) guarded(h) { refresh(h) }
        }
    }

    /** 모든 홀로그램을 곧바로(개인 보기 바꿈 등) — 홀로그램마다 따로 잡는다. */
    fun refreshAll() {
        for (h in store.all()) guarded(h) { refresh(h) }
    }

    private inline fun guarded(h: Hologram, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (failing[h.key] !== h) {
                failing[h.key] = h
                ess.logger.log(Level.WARNING, "홀로그램 ${h.name} 을(를) 그리다 오류 — 이 홀로그램만 건너뜁니다(같은 정의의 같은 오류는 다시 알리지 않습니다)", t)
            }
        }
    }

    private fun derive(l: Live, h: Hologram) {
        val s = settings
        val was = l.derivedFor
        if (was != null && was.first === h && was.second === s) return
        // 설정이 바뀌었다(리로드) — 이미 선 묶음은 옛 줄 높이·거리·배경을 들고 있다. 거두면 다음 refresh 가 새 설정으로 모두 다시 세운다
        // (그러지 않으면 새로 세운 쪽·사람만 새 간격이라 한 홀로그램이 두 모양으로 보인다).
        if (was != null && was.second !== s) despawn(l)
        l.personal = h.pages.map { page -> page.lines.any { !it.isIcon && HoloRules.isPersonal(it.text, s.globalPlaceholders) } }
        l.shown = HoloRules.shownPages(h.pages.map { it.lines.size })
        l.derivedFor = h to s
    }

    /** 그 사람이 지금 보는 쪽(정의의 쪽 번호, 0부터). */
    private fun pageFor(l: Live, id: UUID): Int =
        l.shown[HoloRules.pageOf(l.globalPage, l.offsets[id] ?: 0, l.shown.size)]

    /** 한 홀로그램 — 보는 사람을 다시 고르고, 묶음을 세우고/거두고, [update] 면 바뀐 글만 다시 보낸다. */
    fun refresh(h: Hologram, update: Boolean = false) {
        val l = live.getOrPut(h.key) { Live(h) }
        if (l.def !== h) {
            despawn(l)
            l.def = h
        }
        if (!h.enabled) return despawn(l)
        val world = Bukkit.getWorld(h.world) ?: return despawn(l)
        if (!world.isChunkLoaded(chunk(h.x), chunk(h.z))) return despawn(l)
        for ((key, set) in l.sets.toList()) if (!set.valid()) removeSet(l, key) // /kill 등으로 사라진 것
        derive(l, h)

        val s = settings
        val range = (h.viewRange ?: s.viewRange).toDouble()
        val range2 = range * range
        val updateRange = (h.updateRange ?: s.updateRange).takeIf { it > 0 }?.toDouble()?.coerceAtMost(range) ?: range
        val update2 = updateRange * updateRange
        val near = HashSet<UUID>()
        // 보는 사람과 그 쪽 — 사람마다의 쪽이면 묶음 상한을 따진다(이미 자기 묶음이 있는 사람이 먼저 — 들어오고 나갈 때 번갈아 바뀌지 않게).
        val viewers = ArrayList<Pair<Player, Int>>()
        for (p in world.players) {
            val loc = p.location
            val dx = loc.x - h.x
            val dy = loc.y - h.y
            val dz = loc.z - h.z
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > range2) continue
            if (!visibleTo(p, h)) continue
            if (d2 <= update2) near += p.uniqueId
            viewers += p to pageFor(l, p.uniqueId)
        }
        val wantsOwn = viewers.filter { (_, page) -> l.personal.getOrElse(page) { false } }
            .sortedBy { (p, _) -> if (l.assigned[p.uniqueId]?.viewer == p.uniqueId) 0 else 1 }
        val own = wantsOwn.take(s.maxPersonalViewers).mapTo(HashSet()) { it.first.uniqueId }
        val next = HashMap<UUID, SetKey>()
        for ((p, page) in viewers) next[p.uniqueId] = SetKey(page, if (p.uniqueId in own) p.uniqueId else null)

        for (key in next.values.toSet()) if (key !in l.sets) l.sets[key] = spawn(world, h, l, key)
        for ((id, old) in l.assigned) {
            if (next[id] == old) continue
            val set = l.sets[old] ?: continue
            set.viewers.remove(id)
            Bukkit.getPlayer(id)?.let { p -> for (e in set.entities()) p.hideEntity(ess.plugin, e) }
        }
        for ((id, key) in next) {
            if (l.assigned[id] == key) continue
            val set = l.sets[key] ?: continue
            set.viewers.add(id)
            Bukkit.getPlayer(id)?.let { p -> for (e in set.entities()) p.showEntity(ess.plugin, e) }
        }
        l.assigned = next
        for ((key, set) in l.sets.toList()) if (set.viewers.isEmpty()) removeSet(l, key)
        // 갱신 거리(CMI updateRange) 안에 보는 사람이 있는 묶음만 다시 그린다.
        if (update) for (set in l.sets.values) if (set.viewers.any { it in near }) rerender(set, l)
    }

    fun visibleTo(p: Player, h: Hologram): Boolean {
        if (p.uniqueId in forceHidden) return false
        if (!PlayerSettings.enabled(p, HologramsFeature.SETTING_VISIBLE, true)) return false
        h.permission?.let { if (!p.hasPermission(it)) return false }
        h.condition?.let { condition ->
            if (!HoloRules.condition(condition) { side -> Text.substituteOnly(withPlayer(side, p), null, p) }) return false
        }
        return true
    }

    /** 그 사람에게 보일 만한가 — 보이기 조건 + 보이는 거리 안(`/홀로그램 넘기기` 를 관리자 아닌 사람에게 열 때). */
    fun visibleNear(p: Player, h: Hologram): Boolean {
        if (!h.enabled || p.world.name != h.world || !visibleTo(p, h)) return false
        val range = (h.viewRange ?: settings.viewRange).toDouble()
        val loc = p.location
        val dx = loc.x - h.x
        val dy = loc.y - h.y
        val dz = loc.z - h.z
        return dx * dx + dy * dy + dz * dz <= range * range
    }

    // --- 세우기 · 거두기 -----------------------------------------------------------------------------------

    private fun spawn(world: World, h: Hologram, l: Live, key: SetKey): Spawned {
        val viewer = key.viewer?.let(Bukkit::getPlayer)
        val page = h.pages[key.page]
        val interactive = (h.clickPages && l.shown.size > 1) || !page.actions.isEmpty
        val out = ArrayList<SpawnedLine>()
        val created = ArrayList<Entity>()
        try {
            var top = h.y
            for ((index, line) in page.lines.withIndex()) {
                val height = (if (line.isIcon) settings.iconHeight else settings.lineHeight) * h.scale
                val bottom = top - height
                var component: Component? = null
                val display: Display = if (line.isIcon) {
                    val item = icon(line)
                    world.spawn(at(world, h, bottom + height / 2), ItemDisplay::class.java) { d ->
                        prepare(d, h, null)
                        d.setItemStack(item)
                        d.billboard = billboard(h)
                        d.transformation = scaled(0.5f * h.scale.toFloat())
                    }
                } else {
                    component = render(line, viewer, key.viewer == null)
                    val c = component
                    world.spawn(at(world, h, bottom), TextDisplay::class.java) { d ->
                        prepare(d, h, null)
                        d.text(c)
                        d.billboard = billboard(h)
                        d.isShadowed = h.shadow
                        d.isSeeThrough = h.seeThrough
                        d.lineWidth = h.lineWidth
                        d.alignment = alignment(h)
                        if (h.textOpacity < 255) d.textOpacity = h.textOpacity.toByte()
                        when (val bg = HoloRules.background(h.background)) {
                            null -> Unit
                            else -> d.backgroundColor = Color.fromARGB(bg)
                        }
                        d.transformation = scaled(h.scale.toFloat())
                    }
                }
                created += display
                var interaction: Interaction? = null
                if (interactive || !line.actions.isEmpty) {
                    val plainLength = component?.let { Text.plain(it).length } ?: 4
                    interaction = world.spawn(Location(world, h.x, bottom, h.z), Interaction::class.java) { i ->
                        prepare(i, h, "${key.page}:$index")
                        i.interactionWidth = HoloRules.clickWidth(plainLength, h.scale, h.clickWidth).toFloat()
                        i.interactionHeight = height.toFloat()
                        i.isResponsive = true
                    }
                    created += interaction
                }
                out += SpawnedLine(line, display, interaction, component)
                top = bottom
            }
        } catch (t: Throwable) {
            for (e in created) e.remove() // 반쯤 세운 묶음을 남기지 않는다
            throw t
        }
        val set = Spawned(key, out)
        for (e in set.entities()) ours += e.uniqueId
        return set
    }

    private fun at(world: World, h: Hologram, y: Double): Location = Location(world, h.x, y, h.z, if (h.billboard == "FIXED") h.yaw else 0f, 0f)

    private fun prepare(entity: Entity, h: Hologram, line: String?) {
        entity.isPersistent = false
        entity.isVisibleByDefault = false
        entity.persistentDataContainer.set(tag, PersistentDataType.STRING, h.key)
        if (line != null) entity.persistentDataContainer.set(lineTag, PersistentDataType.STRING, line)
        if (entity is Display) entity.viewRange = (((h.viewRange ?: settings.viewRange) + 16) / 64f).coerceAtLeast(0.25f)
    }

    private fun scaled(scale: Float): Transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(scale, scale, scale), AxisAngle4f())

    private fun alignment(h: Hologram): TextDisplay.TextAlignment =
        runCatching { TextDisplay.TextAlignment.valueOf(h.alignment) }.getOrDefault(TextDisplay.TextAlignment.CENTER)

    private fun billboard(h: Hologram): Display.Billboard = runCatching { Display.Billboard.valueOf(h.billboard) }.getOrDefault(Display.Billboard.CENTER)

    /** `{player}` 를 그 사람 이름으로. */
    private fun withPlayer(text: String, viewer: Player): String =
        text.replace("{player}", viewer.name).replace("{플레이어네임}", viewer.name).replace("{플레이어}", viewer.name)

    /**
     * 한 줄 그리기. 사람마다의 묶음은 그 사람으로, 공용 묶음([shared])은 사람 토큰·사람 PAPI 를 `personal-fallback` 으로 바꾼 뒤 사람 없이 —
     * 공용 쪽에는 원래 사람 토큰이 없고, 상한을 넘은 사람의 공용 묶음에서만 실제로 바뀐다.
     */
    private fun render(line: HoloLine, viewer: Player?, shared: Boolean): Component {
        if (viewer != null && !shared) return Text.render(withPlayer(line.text, viewer), null, viewer)
        val s = settings
        return Text.render(HoloRules.stripPersonal(line.text, s.globalPlaceholders, s.personalFallback), null, null)
    }

    private fun rerender(set: Spawned, l: Live) {
        val viewer = set.key.viewer?.let(Bukkit::getPlayer)
        if (set.key.viewer != null && viewer == null) return
        for (sl in set.lines) {
            if (sl.line.isIcon || !HoloRules.isDynamic(sl.line.text)) continue
            val display = sl.display as? TextDisplay ?: continue
            val component = render(sl.line, viewer, set.key.viewer == null)
            if (component != sl.last) { // 바뀐 때만 보낸다
                display.text(component)
                sl.last = component
            }
        }
    }

    /** 아이콘 줄의 아이템 — core 참조(`minecraft:`·`mmoitems:`·커스텀아이템)를 다시 짓는다. 못 지으면 방벽. 정의가 바뀌면 [sync] 가 비운다. */
    fun icon(line: HoloLine): ItemStack = icons.getOrPut(line) { buildIcon(line) ?: ItemStack(Material.BARRIER) }.clone()

    /** 아이콘 줄을 지어 본다 — 못 지으면 null(가져오기가 보고하려고). */
    fun buildIcon(line: HoloLine): ItemStack? {
        val ref = ItemRef.parse(line.icon)
        val snapshot = line.snapshot?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        if (ref is ItemRef.None && snapshot == null) return null
        val material = line.material?.let(Material::matchMaterial) ?: (ref as? ItemRef.Vanilla)?.material ?: Material.PAPER
        val stored = StoredItem(ref, material, if (ref is ItemRef.None) StorageMode.SNAPSHOT else StorageMode.REFERENCE, snapshot)
        return ess.itemResolver.create(stored)
    }

    private fun removeSet(l: Live, key: SetKey) {
        val set = l.sets.remove(key) ?: return
        for (e in set.entities()) {
            ours.remove(e.uniqueId)
            e.remove()
        }
        l.assigned.entries.removeIf { it.value == key }
    }

    private fun despawn(l: Live) {
        for (key in l.sets.keys.toList()) removeSet(l, key)
        l.assigned.clear()
    }

    fun shutdown() {
        for (l in live.values) despawn(l)
        live.clear()
    }

    // --- 사건 -------------------------------------------------------------------------------------------

    /** 청크가 내려갈 때 — 그 청크의 홀로그램 묶음을 거둔다(저장 안 하는 엔티티라 어차피 사라진다 — 참조를 놓는다). */
    fun chunkUnloaded(world: String, cx: Int, cz: Int) {
        for (h in store.inChunk(world, cx, cz)) live[h.key]?.let { despawn(it) }
    }

    /** 엔티티가 올라올 때 — 표시가 달렸는데 우리 것이 아니면 남은 것이다. 지운다. */
    fun sweep(entities: Collection<Entity>): Int {
        var removed = 0
        for (e in entities) {
            if (!e.persistentDataContainer.has(tag, PersistentDataType.STRING)) continue
            if (e.uniqueId in ours) continue
            e.remove()
            removed++
        }
        return removed
    }

    /** 켤 때(ready) — 올라와 있는 모든 월드의 남은 것. */
    fun sweepAll(): Int = Bukkit.getWorlds().sumOf { world -> sweep(world.getEntitiesByClasses(Display::class.java, Interaction::class.java)) }

    fun quit(player: Player) {
        val id = player.uniqueId
        for (l in live.values) {
            l.assigned.remove(id)?.let { key -> l.sets[key]?.viewers?.remove(id) }
            l.offsets.remove(id)
        }
        lastClick.remove(id)
        forceHidden.remove(id)
    }

    /**
     * 누르기 — 우리 Interaction 이면 true(사건을 취소). **그 사람에게 지금 맡긴 묶음의 엔티티일 때만** 돈다 — 숨기기(`hideEntity`)는 클라이언트
     * 쪽 일이라, 고친 클라이언트가 안 보이는 엔티티 번호로 누르기 패킷을 보내도(권한·조건·개인 끄기·다른 쪽) 명령이 돌지 않게. 보이기 조건도 다시 본다.
     */
    fun click(player: Player, entity: Entity, click: HoloClick): Boolean {
        val pdc = entity.persistentDataContainer
        val key = pdc.get(tag, PersistentDataType.STRING) ?: return false
        val h = store.get(key) ?: return true
        val l = live[h.key] ?: return true
        val set = l.assigned[player.uniqueId]?.let { l.sets[it] } ?: return true
        if (set.lines.none { it.interaction?.uniqueId == entity.uniqueId }) return true
        if (!h.enabled || !visibleTo(player, h)) return true
        if (!player.hasPermission(HologramsFeature.CLICK)) return true
        val now = System.currentTimeMillis()
        val last = lastClick[player.uniqueId]
        if (last != null && now - last < settings.clickCooldownMillis) return true
        lastClick[player.uniqueId] = now
        val ref = pdc.get(lineTag, PersistentDataType.STRING)?.split(':') ?: return true
        val page = h.pages.getOrNull(ref.getOrNull(0)?.toIntOrNull() ?: return true) ?: return true
        val line = page.lines.getOrNull(ref.getOrNull(1)?.toIntOrNull() ?: -1)
        val actions = line?.actions?.resolve(click).orEmpty().ifEmpty { page.actions.resolve(click) }
        if (actions.isNotEmpty()) {
            run(player, h, actions)
        } else if (h.clickPages && pageCount(h) > 1) {
            turn(player, h, if (click == HoloClick.LEFT || click == HoloClick.SHIFT_LEFT) -1 else 1)
        }
        return true
    }

    /** 명령 — `[nextPage]`/`[prevPage]` 는 쪽 넘기기, `player:` 는 그 사람이, 아니면 콘솔이. `{player}`·`[playerName]`·PAPI. */
    fun run(player: Player, h: Hologram, actions: List<String>) {
        val ph = Ph.of().player(player.name).raw("[playerName]", player.name).raw("{홀로그램}", h.name).raw("{hologram}", h.name)
        for (action in actions) {
            HoloRules.pageStep(action)?.let { step ->
                capture?.add(action.trim())
                turn(player, h, step)
                continue
            }
            val (asPlayer, command) = HoloRules.parseCommand(action) ?: continue
            val text = Text.substituteOnly(command, ph, player)
            capture?.let {
                it += (if (asPlayer) "player:" else "console:") + text
                continue
            }
            try {
                if (asPlayer) player.performCommand(text) else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), text)
            } catch (t: Throwable) {
                ess.logger.warning("홀로그램 ${h.name} 의 클릭 명령 실패: $text — $t")
            }
        }
    }

    // --- 쪽 -------------------------------------------------------------------------------------------

    private fun liveOf(h: Hologram): Live {
        val l = live.getOrPut(h.key) { Live(h) }
        derive(l, h)
        return l
    }

    /** 넘겨 볼 수 있는 쪽 수(줄이 있는 쪽만). */
    fun pageCount(h: Hologram): Int = HoloRules.shownPages(h.pages.map { it.lines.size }).size

    /** 그 사람의 쪽을 [delta] 만큼(빈 쪽은 건너뛴다). */
    fun turn(player: Player, h: Hologram, delta: Int) {
        val l = liveOf(h)
        val count = l.shown.size
        if (count <= 1) return
        l.offsets[player.uniqueId] = Math.floorMod((l.offsets[player.uniqueId] ?: 0) + delta, count)
        guarded(h) { refresh(h) }
    }

    /** 그 사람이 [page](0부터) 쪽을 보게 — 빈 쪽이면 그 뒤로 처음 줄이 있는 쪽. */
    fun show(player: Player, h: Hologram, page: Int) {
        val l = liveOf(h)
        val target = HoloRules.shownIndexOf(l.shown, page.coerceIn(0, h.pages.lastIndex.coerceAtLeast(0)))
        l.offsets[player.uniqueId] = HoloRules.offsetFor(l.globalPage, target, l.shown.size)
        guarded(h) { refresh(h) }
    }

    /** 그 사람이 지금 보는 쪽(정의의 쪽 번호, 0부터). */
    fun pageOf(player: Player, h: Hologram): Int = pageFor(liveOf(h), player.uniqueId)

    // --- 검증기용 ---------------------------------------------------------------------------------------

    /** 그 사람에게 보이는 그 홀로그램의 엔티티(없으면 빈 목록). */
    internal fun entitiesFor(player: Player, name: String): List<Entity> {
        val l = live[HoloRules.keyOf(name)] ?: return emptyList()
        val key = l.assigned[player.uniqueId] ?: return emptyList()
        return l.sets[key]?.entities().orEmpty()
    }

    /** 그 사람의 묶음이 사람마다의 것인가. */
    internal fun personalFor(player: Player, name: String): Boolean? =
        live[HoloRules.keyOf(name)]?.assigned?.get(player.uniqueId)?.let { it.viewer == player.uniqueId }

    internal fun liveCount(name: String): Int = live[HoloRules.keyOf(name)]?.sets?.values?.sumOf { it.entities().size } ?: 0

    internal fun tracked(id: UUID): Boolean = id in ours

    internal fun resetClickCooldown(player: Player) {
        lastClick.remove(player.uniqueId)
    }

    private fun chunk(v: Double): Int = kotlin.math.floor(v).toInt() shr 4
}

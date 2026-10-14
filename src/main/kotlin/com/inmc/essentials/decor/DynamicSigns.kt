package com.inmc.essentials.decor

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.AtomicFiles
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 동적 표지판(CMI dynamicSigns) — 표지판 글을 플레이스홀더(PAPI·`%…%`)로 주기마다 다시 쓰고, 넷 넘는 줄은 위로 굴리고, `{스크롤}` 줄은 옆으로 흐른다.
 *
 * 갱신은 1초 틱(`ess.ticks`) 하나로 — 그 표지판의 [DynSign.range] 안에 사람이 있을 때만, **글이 바뀐 때만** 보낸다.
 *  - 공용(`individual: false`): 진짜 블록의 앞면을 바꾼다(`Sign.update`) — 모두에게 같은 글, 서버 플레이스홀더만 뜻이 있다.
 *  - 개인(`individual: true`): `Player.sendSignChange` 로 **보는 사람마다** 그 사람의 플레이스홀더로 — 패킷 하나뿐이라 싸다(블록은 그대로라 다시
 *    접속하거나 청크를 다시 받으면 원래 글이 보이다가 다음 갱신에 바뀐다. 범위에 새로 들어온 사람에게는 바로 보낸다).
 *
 * 저장은 `decor/dynamic-signs.yml` 하나(워커에서 원자적으로 — 바꿀 때마다 통째로, 작다). 블록이 표지판이 아니게 되면(부서짐) 지운다.
 */
class DynamicSigns(private val ess: Ess, private val settings: () -> DecorSettings) {

    data class DynSign(
        val id: Int,
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val lines: List<String>,
        val interval: Int,
        val range: Int,
        val individual: Boolean,
    ) {
        fun at(block: Block): Boolean = block.x == x && block.y == y && block.z == z && block.world.name == world
    }

    private class Runtime {
        var frame = 0L
        var second = 0L
        var lastStep = 0L
        var shared: List<Component>? = null
        val viewers = HashMap<UUID, Int>()
    }

    @Volatile
    var signs: Map<Int, DynSign> = emptyMap()
        private set

    private val runtime = HashMap<Int, Runtime>()

    private val file get() = ess.io.file("decor", "dynamic-signs.yml")

    fun load() {
        val f = file
        ess.io.async({ if (f.isFile) read(YamlConfiguration.loadConfiguration(f)) else emptyMap() }) { loaded ->
            signs = loaded
            runtime.clear()
        }
    }

    private fun save() {
        val text = write(signs.values).saveToString()
        val f = file
        ess.io.asyncRun {
            f.parentFile.mkdirs()
            AtomicFiles.write(f, text)
        }
    }

    fun at(block: Block): DynSign? = signs.values.firstOrNull { it.at(block) }

    /** 블록의 글이 바뀌었다(권한 있는 사람이 바닐라 화면으로 고침) — 같은 글이어도 다음 틱에 다시 그린다. */
    fun invalidate(block: Block) {
        val sign = at(block) ?: return
        runtime[sign.id]?.let { rt ->
            rt.shared = null
            rt.viewers.clear()
        }
    }

    /** 검증기 — 저장하지 않는 임시 표지판(번호는 음수로). */
    internal fun putTemporary(sign: DynSign) {
        signs = signs + (sign.id to sign)
    }

    internal fun removeTemporary(id: Int) {
        signs = signs - id
        runtime.remove(id)
    }

    /** 바라보는 표지판을 동적 표지판으로 — 지금 앞면 글이 첫 줄들. */
    fun create(player: Player, block: Block, sign: Sign): DynSign? {
        if (signs.size >= settings().dynamic.maxSigns) {
            ess.messages.send(player, "decor-dsign-limit", Ph.of().count(settings().dynamic.maxSigns))
            return null
        }
        val lines = sign.getSide(Side.FRONT).lines().map { DecorText.toMini(it) }.dropLastWhile { it.isBlank() }.ifEmpty { listOf("") }
        val id = (signs.keys.maxOrNull() ?: 0) + 1
        val d = settings().dynamic
        val created = DynSign(id, block.world.name, block.x, block.y, block.z, lines, d.defaultInterval, d.defaultRange, false)
        signs = signs + (id to created)
        save()
        return created
    }

    /** 가져온 표지판(CMI) — 같은 자리가 이미 있으면 건너뛰고, 번호는 새로 준다. 넣었으면 true. */
    fun adopt(sign: DynSign): Boolean {
        if (signs.values.any { it.world == sign.world && it.x == sign.x && it.y == sign.y && it.z == sign.z }) return false
        val id = (signs.keys.maxOrNull() ?: 0) + 1
        signs = signs + (id to sign.copy(id = id))
        save()
        return true
    }

    fun update(sign: DynSign) {
        signs = signs + (sign.id to sign)
        runtime.remove(sign.id) // 다음 틱에 새로 그린다
        save()
    }

    fun remove(id: Int, restore: Boolean): DynSign? {
        val removed = signs[id] ?: return null
        signs = signs - id
        runtime.remove(id)
        save()
        if (restore) Bukkit.getWorld(removed.world)?.let { w ->
            // 개인 표지판을 보던 사람들에게 진짜 글을 다시 보낸다.
            val block = w.getBlockAt(removed.x, removed.y, removed.z)
            val state = block.state as? Sign ?: return@let
            for (p in w.players) if (p.location.distanceSquared(block.location) <= removed.range.toDouble() * removed.range) p.sendBlockUpdate(block.location, state)
        }
        return removed
    }

    // --- 그리기 ---------------------------------------------------------------------------------------

    /**
     * 한 장면의 네 줄. [viewer] null 이면 공용(서버 플레이스홀더만).
     *
     * core `Text.render` 는 플레이스홀더 값을 넣은 **뒤에** MiniMessage 를 해석한다 — 값(닉네임·칭호·기록 이름)에 든 `<click…>` 이 공용 모드에서는 진짜
     * 블록의 클릭 사건이 되고, 표지판 클릭 명령은 누른 사람이 권한 레벨 2로 실행한다. 그래서 관리자가 쓴 줄 원문에 `<click` 이 없으면 결과의 클릭 사건을
     * 벗긴다([DecorText.withoutInjectedClicks] — 2026-10-10 리뷰). 개인 모드도 같은 결과를 쓴다(패킷뿐이라 해는 없지만 한 길로).
     */
    fun render(sign: DynSign, frame: Long, second: Long, viewer: Player?): List<Component> =
        SignFrames.window(sign.lines, frame).map { line ->
            val rendered = if (SignFrames.isMarquee(line)) {
                val body = SignFrames.stripMarker(line)
                val plain = DecorText.plain(Text.render(body, null, viewer))
                val shown = SignFrames.marquee(plain, settings().dynamic.marqueeWidth, second)
                Text.render(SignFrames.leadingTags(body) + MiniMessage.miniMessage().escapeTags(shown), null, viewer)
            } else {
                Text.render(line, null, viewer)
            }
            DecorText.withoutInjectedClicks(line, rendered)
        }

    fun tick(now: Long) {
        if (signs.isEmpty()) return
        var broken: MutableList<Int>? = null
        for (sign in signs.values) {
            val world = Bukkit.getWorld(sign.world) ?: continue
            if (!world.isChunkLoaded(sign.x shr 4, sign.z shr 4)) continue
            val center = Location(world, sign.x + 0.5, sign.y + 0.5, sign.z + 0.5)
            val r2 = sign.range.toDouble() * sign.range
            val near = world.players.filter { it.location.distanceSquared(center) <= r2 }
            val rt = runtime.getOrPut(sign.id) { Runtime() }
            rt.second++
            if (near.isEmpty()) {
                rt.viewers.clear()
                continue
            }
            val block = world.getBlockAt(sign.x, sign.y, sign.z)
            val state = block.state as? Sign
            if (state == null) {
                (broken ?: ArrayList<Int>().also { broken = it }) += sign.id
                continue
            }
            val step = now - rt.lastStep >= sign.interval * 1000L
            if (step) {
                rt.lastStep = now
                if (sign.lines.size > 4) rt.frame++
            }
            val marquee = sign.lines.any { SignFrames.isMarquee(it) }
            // 흐르는 줄이 있으면 매초, 아니면 주기마다만 다시 그린다.
            if (!step && !marquee && rt.shared != null && !sign.individual) continue
            if (sign.individual) {
                val front = state.getSide(Side.FRONT)
                val seen = HashSet<UUID>()
                for (p in near) {
                    seen += p.uniqueId
                    val fresh = p.uniqueId !in rt.viewers
                    if (!fresh && !step && !marquee) continue
                    val lines = render(sign, rt.frame, rt.second, p)
                    val hash = lines.hashCode()
                    if (!fresh && rt.viewers[p.uniqueId] == hash) continue
                    rt.viewers[p.uniqueId] = hash
                    p.sendSignChange(block.location, lines, front.color ?: org.bukkit.DyeColor.BLACK, front.isGlowingText)
                }
                rt.viewers.keys.retainAll(seen)
            } else {
                val lines = render(sign, rt.frame, rt.second, null)
                if (lines == rt.shared) continue
                rt.shared = lines
                val side = state.getSide(Side.FRONT)
                for (i in 0 until 4) side.line(i, lines[i])
                state.update(false, false)
            }
        }
        broken?.let { ids ->
            for (id in ids) remove(id, restore = false)?.let { ess.logger.info("동적 표지판 #$id 자리(${it.world} ${it.x} ${it.y} ${it.z})가 표지판이 아니어서 지웠습니다") }
        }
    }

    // --- 화면 -------------------------------------------------------------------------------------------

    fun openMenu(player: Player, block: Block) {
        val sign = at(block) ?: return ess.messages.send(player, "decor-dsign-how-to")
        DynamicSignMenu(player, sign.id).show()
    }

    private inner class DynamicSignMenu(viewer: Player, private val id: Int) : Menu(ess, viewer, 27, "<dark_gray>동적 표지판</dark_gray>") {

        override fun draw() {
            clear()
            val sign = signs[id] ?: return viewer.closeInventory()
            for (slot in 0 until 27) set(slot, Icon.FILLER)
            set(SLOT_INTERVAL, Icon.of(Material.CLOCK, "<yellow>갱신 주기: </yellow><white>${sign.interval}초</white>",
                "<gray>글을 다시 쓰는 간격 — 줄이 넷 넘으면 굴리는 빠르기도</gray>", "<gray>좌클릭 +1 · 우클릭 −1 · Shift 5씩</gray>")) { e ->
                val d = (if (e.isRightClick) -1 else 1) * (if (e.isShiftClick) 5 else 1)
                update(sign.copy(interval = (sign.interval + d).coerceIn(1, 3600)))
                refresh()
            }
            set(SLOT_RANGE, Icon.of(Material.SPYGLASS, "<yellow>작동 범위: </yellow><white>${sign.range}블록</white>",
                "<gray>이 안에 사람이 있을 때만 갱신합니다</gray>", "<gray>좌클릭 +1 · 우클릭 −1 · Shift 5씩</gray>")) { e ->
                val d = (if (e.isRightClick) -1 else 1) * (if (e.isShiftClick) 5 else 1)
                update(sign.copy(range = (sign.range + d).coerceIn(1, 256)))
                refresh()
            }
            set(SLOT_INDIVIDUAL, toggleIcon("사람마다 다르게", sign.individual,
                "<gray>켜기 — 보는 사람의 플레이스홀더로(패킷, 블록은 그대로)</gray>", "<gray>끄기 — 진짜 블록을 바꿉니다(모두에게 같은 글)</gray>")) {
                update(sign.copy(individual = !sign.individual))
                refresh()
            }
            set(SLOT_LINES, Icon.of(Material.WRITABLE_BOOK, "<yellow>줄 편집</yellow> <gray>(${sign.lines.size}줄)</gray>",
                sign.lines.take(8).map { "<white>" + MiniMessage.miniMessage().escapeTags(it) + "</white>" } +
                    listOf("", "<gray>%플레이스홀더% · 색 · {스크롤}로 시작하면 옆으로 흐름</gray>", "<gray>넷 넘으면 위로 굴립니다</gray>", "<yellow>▶ 클릭: 고치기</yellow>"))) {
                ask(DialogForm("<yellow>동적 표지판 줄</yellow>").line("<gray>한 줄에 하나씩 — 넷 넘으면 굴립니다</gray>")
                    .text("lines", "줄", sign.lines.joinToString("\n"), 2048, multiline = true)) { v ->
                    val lines = v.text("lines").split('\n').map { it.trimEnd('\r') }.dropLastWhile { it.isBlank() }.ifEmpty { listOf("") }
                    signs[id]?.let { update(it.copy(lines = lines)) }
                }
            }
            set(SLOT_PREVIEW, Icon.of(Material.OAK_SIGN, "<aqua>미리 보기</aqua>", "<gray>지금 당신에게 보일 네 줄을 채팅으로</gray>")) {
                for (line in render(sign, 0, 0, viewer)) viewer.sendMessage(line)
            }
            set(SLOT_REMOVE, Icon.of(Material.LAVA_BUCKET, "<red>동적 표지판 끄기</red>", "<gray>표지판은 남고 마지막 글이 그대로 남습니다</gray>", "<red>Shift+클릭</red>")) { e ->
                if (!e.isShiftClick) return@set
                remove(id, restore = true)
                ess.messages.send(viewer, "decor-dsign-removed")
                viewer.closeInventory()
            }
            set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
        }
    }

    // --- 저장 형식 ----------------------------------------------------------------------------------------

    companion object {
        const val SLOT_INTERVAL = 10
        const val SLOT_RANGE = 11
        const val SLOT_INDIVIDUAL = 12
        const val SLOT_LINES = 14
        const val SLOT_PREVIEW = 15
        const val SLOT_REMOVE = 16
        const val SLOT_CLOSE = 26

        /** 열쇠는 `"s<번호>"`(숫자만인 열쇠는 YAML 이 다르게 다룬다). 못 읽은 항목은 건너뛴다. */
        fun read(y: YamlConfiguration): Map<Int, DynSign> {
            val out = LinkedHashMap<Int, DynSign>()
            for (key in y.getKeys(false)) {
                val s = y.getConfigurationSection(key) ?: continue
                val id = key.removePrefix("s").toIntOrNull() ?: continue
                val world = s.getString("world") ?: continue
                out[id] = DynSign(id, world, s.getInt("x"), s.getInt("y"), s.getInt("z"), s.getStringList("lines").ifEmpty { listOf("") },
                    s.getInt("interval", 5).coerceIn(1, 3600), s.getInt("range", 16).coerceIn(1, 256), s.getBoolean("individual", false))
            }
            return out
        }

        fun write(signs: Collection<DynSign>): YamlConfiguration {
            val y = YamlConfiguration()
            for (d in signs.sortedBy { it.id }) {
                val s = y.createSection("s${d.id}")
                s.set("world", d.world)
                s.set("x", d.x)
                s.set("y", d.y)
                s.set("z", d.z)
                s.set("lines", d.lines)
                s.set("interval", d.interval)
                s.set("range", d.range)
                s.set("individual", d.individual)
            }
            return y
        }
    }
}

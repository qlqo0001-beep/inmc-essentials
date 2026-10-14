package com.inmc.essentials.portal

import com.inmc.essentials.Ess
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 포탈 저장소 — `portals.yml` 한 벌을 메모리에(`@Volatile` 스냅샷 + 청크 색인), 고치면 통째로 I/O 워커에서 원자적으로 쓴다
 * (`ConfigService.save` → `AtomicFiles`). 워커는 한 줄이라 쓰기 순서가 지켜진다.
 *
 * 색인: 월드 → 청크 → 그 청크에 걸친 포탈. 걷기마다 [at] 이 불리므로 비용은 **그 청크의 포탈 수**뿐이다.
 * [maxVolume] 은 읽을 때의 영역 상한(설정 `max-volume` — 부를 때마다 읽는다).
 *
 * **YAML 문법이 깨진 파일은 빈 것으로 읽지 않는다** — `YamlConfiguration.loadConfiguration` 은 오류를 삼키고 빈 설정을 주므로 쓰지 않고
 * `loadFromString` 으로 엄격히 읽는다. 깨졌으면 파일은 그대로 두고 `portals.yml.broken-<시각>` 사본을 남긴 뒤 [locked] 를 세운다 — 잠긴 동안
 * 저장하지 않는다(빈 것으로 덮어 모든 포탈을 지우지 않게). 고친 뒤 `/포탈 다시읽기` 가 성공하면 풀린다.
 */
class PortalStore(private val ess: Ess, private val maxVolume: () -> Long) {

    private class Snapshot(val file: PortalFile, val index: Map<String, Map<Long, List<Portal>>>)

    @Volatile
    private var snap: Snapshot = Snapshot(PortalFile(), emptyMap())

    /** 메인에서 고친 횟수 — 다시 읽기가 워커에 가 있는 동안 고친 것이 있으면 읽은 것을 버리고 다시 읽는다(고친 것을 잃지 않게). */
    private var commits = 0L

    val data: PortalFile get() = snap.file

    /** 파일을 YAML 로 읽지 못한 까닭 — 있으면 저장하지 않는다(고치는 명령·화면은 [PortalService.editable] 로 먼저 막는다). */
    @Volatile
    var locked: String? = null
        private set

    val file: File get() = ess.io.file(FILE)

    fun all(): List<Portal> = snap.file.portals

    fun get(name: String): Portal? {
        val key = PortalRules.keyOf(name)
        return snap.file.portals.firstOrNull { it.key == key }
    }

    /** [loc] 의 블록이 든 포탈(겹치면 이름순 첫째). */
    fun at(loc: Location): Portal? {
        val world = loc.world?.name ?: return null
        return at(world, loc.blockX, loc.blockY, loc.blockZ)
    }

    fun at(world: String, x: Int, y: Int, z: Int): Portal? {
        val list = snap.index[world]?.get(PortalRules.chunkKey(x shr 4, z shr 4)) ?: return null
        for (portal in list) if (portal.box.contains(world, x, y, z)) return portal
        return null
    }

    /** 같은 이름(대소문자 무시)의 못 읽은 칸 — 있으면 그 이름으로 만들지 않는다(되써 넣을 원문을 덮지 않게). */
    fun rejectedName(name: String): String? {
        val key = PortalRules.keyOf(name)
        return snap.file.rejected.keys.firstOrNull { PortalRules.keyOf(it) == key }
    }

    /** 켤 때(메인 — 시작 중). 못 읽은 칸은 까닭과 함께 로그에. 파일이 깨졌으면 빈 채로 잠근다. */
    fun loadNow() {
        val read = readFile()
        if (read.file == null) {
            locked = read.error
            ess.logger.severe("portals.yml 을 YAML 로 읽지 못했습니다 — 포탈을 하나도 쓰지 않고, 고칠 때까지 저장하지 않습니다(파일은 그대로): ${read.error}")
            return
        }
        locked = null
        swap(read.file)
        logRejected(snap.file)
        ess.logger.info("포탈 ${snap.file.portals.size}개를 읽었습니다")
    }

    /** 읽은 결과 — [file] 이 null 이면 YAML 문법 오류([error]). */
    private class Read(val file: PortalFile?, val error: String?)

    private fun readFile(): Read {
        val f = file
        if (!f.isFile) return Read(PortalFile(), null)
        val yaml = try {
            YamlConfiguration().apply { loadFromString(f.readText(Charsets.UTF_8)) }
        } catch (e: Exception) { // InvalidConfigurationException · IOException
            return Read(null, broken(f, e))
        }
        return Read(PortalCodec.read(yaml, maxVolume()), null)
    }

    /** 깨진 파일의 사본(`<이름>.broken-<시각>`)을 남기고 사람이 읽을 까닭을 돌려준다. 원본은 건드리지 않는다. */
    private fun broken(f: File, e: Exception): String {
        val copy = File(f.parentFile, "${f.name}.broken-${LocalDateTime.now().format(STAMP)}")
        val saved = runCatching { f.copyTo(copy, overwrite = true) }.isSuccess
        val why = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: e.toString()
        return why + if (saved) " (사본 ${copy.name})" else ""
    }

    private fun logRejected(read: PortalFile) {
        for (name in read.rejected.keys) {
            ess.logger.warning("portals.yml 의 포탈 '$name' 을(를) 읽지 못했습니다(지우지 않고 둡니다): ${read.reasons[name] ?: "?"}")
        }
    }

    /**
     * `/포탈 다시읽기` — 워커에서 읽고 메인에서 바꿔 끼운다. 읽는 동안 메인에서 고친 것이 있으면(그 저장은 워커 줄에서 이 읽기 **뒤**) 읽은 것을
     * 버리고 다시 읽는다 — 그래야 그 고침이 담긴 파일을 읽는다. 세 번 겹치면 메모리를 그대로 두고 [done] 에 지금 것을 준다.
     * 파일이 YAML 로 깨졌으면 메모리(지금 포탈)는 그대로 두고 잠근 뒤 [done] 의 둘째 값에 까닭을 준다 — 잠긴 동안 저장하면 손으로 고치던 파일을 덮으므로.
     */
    fun reload(done: (PortalFile, String?) -> Unit) = reload(done, 0)

    private fun reload(done: (PortalFile, String?) -> Unit, attempt: Int) {
        val started = commits
        ess.io.async({ readFile() }) { read ->
            if (commits != started) {
                if (attempt < 3) reload(done, attempt + 1) else done(snap.file, null)
                return@async
            }
            val file = read.file
            if (file == null) {
                locked = read.error
                ess.logger.severe("portals.yml 을 YAML 로 읽지 못했습니다 — 지금 포탈을 그대로 쓰고, 고칠 때까지 저장하지 않습니다: ${read.error}")
                return@async done(snap.file, read.error)
            }
            locked = null
            swap(file)
            logRejected(file)
            done(file, null)
        }
    }

    fun put(portal: Portal, old: String = portal.name) {
        val oldKey = PortalRules.keyOf(old)
        commit(snap.file.copy(portals = snap.file.portals.filter { it.key != oldKey && it.key != portal.key } + portal))
    }

    fun putAll(changed: List<Portal>) {
        if (changed.isEmpty()) return
        val keys = changed.map { it.key }.toSet()
        commit(snap.file.copy(portals = snap.file.portals.filter { it.key !in keys } + changed))
    }

    fun remove(name: String): Boolean {
        val key = PortalRules.keyOf(name)
        if (snap.file.portals.none { it.key == key }) return false
        commit(snap.file.copy(portals = snap.file.portals.filter { it.key != key }))
        return true
    }

    /** 검증기 — 파일에 쓰지 않고 바꿨다 되돌린다. */
    internal fun swap(value: PortalFile) {
        snap = Snapshot(value, index(value.portals))
    }

    private fun commit(value: PortalFile) {
        locked?.let {
            ess.logger.warning("portals.yml 이 깨져 있어 고친 것을 버립니다(저장하면 파일을 덮습니다) — 파일을 고치고 /포탈 다시읽기: $it")
            return
        }
        commits++
        swap(value)
        val yaml = PortalCodec.write(value)
        ess.io.asyncRun { ess.io.save(file, yaml) }
    }

    private fun index(portals: List<Portal>): Map<String, Map<Long, List<Portal>>> {
        val out = HashMap<String, HashMap<Long, MutableList<Portal>>>()
        for (portal in portals.sortedBy { it.key }) {
            val chunks = portal.box.chunks()
            if (chunks.isEmpty()) { // 상한을 넘는 상자 — 읽기·만들기가 막으니 여기 오면 버그다. 색인에서만 뺀다.
                ess.logger.warning("포탈 '${portal.name}' 의 영역이 청크 ${portal.box.chunkCount}개에 걸쳐 색인하지 않습니다(들어서기가 안 됩니다)")
                continue
            }
            val byChunk = out.getOrPut(portal.box.world) { HashMap() }
            for (chunk in chunks) byChunk.getOrPut(chunk) { ArrayList() } += portal
        }
        return out
    }

    companion object {
        const val FILE = "portals.yml"

        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

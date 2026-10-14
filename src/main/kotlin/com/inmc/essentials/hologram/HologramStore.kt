package com.inmc.essentials.hologram

import com.inmc.essentials.Ess
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 홀로그램 저장소 — `holograms.yml` 한 벌을 메모리에(`@Volatile` 스냅샷), 고치면 통째로 I/O 워커에서 원자적으로 쓴다
 * (`ConfigService.save` → `AtomicFiles`). 엔티티는 저장하지 않으므로 **정의는 이 파일에만 있다** — 그래서 원자적이어야 한다.
 *
 * **YAML 문법이 깨진 파일은 빈 것으로 읽지 않는다** — `loadConfiguration` 은 오류를 삼키고 빈 설정을 주므로 `loadFromString` 으로 엄격히 읽는다.
 * 깨졌으면 파일은 그대로 두고 `holograms.yml.broken-<시각>` 사본을 남긴 뒤 [locked] 를 세운다 — 잠긴 동안 저장하지 않는다(빈 것으로 덮어 모든
 * 홀로그램을 지우지 않게). 고친 뒤 `/홀로그램 다시읽기` 가 성공하면 풀린다.
 */
class HologramStore(private val ess: Ess) {

    private class Snapshot(val file: HologramFile, val byChunk: Map<String, Map<Long, List<Hologram>>>)

    @Volatile
    private var snap: Snapshot = Snapshot(HologramFile(), emptyMap())

    /** 바뀔 때마다(엔진이 아이콘 캐시를 비우고 다시 세운다). */
    var onChange: (() -> Unit)? = null

    /** 메인에서 고친 횟수 — 다시 읽기가 워커에 가 있는 동안 고친 것이 있으면 읽은 것을 버리고 다시 읽는다(고친 것을 잃지 않게). */
    private var commits = 0L

    val data: HologramFile get() = snap.file

    /** 파일을 YAML 로 읽지 못한 까닭 — 있으면 저장하지 않는다(고치는 명령·화면은 [HologramsFeature.editable] 로 먼저 막는다). */
    @Volatile
    var locked: String? = null
        private set

    val file: File get() = ess.io.file(FILE)

    fun all(): List<Hologram> = snap.file.holograms

    fun get(name: String): Hologram? {
        val key = HoloRules.keyOf(name)
        return snap.file.holograms.firstOrNull { it.key == key }
    }

    /** 그 청크에 선 홀로그램(청크가 내려갈 때). */
    fun inChunk(world: String, cx: Int, cz: Int): List<Hologram> = snap.byChunk[world]?.get(chunkKey(cx, cz)).orEmpty()

    /** 같은 이름(대소문자 무시)의 못 읽은 칸 — 있으면 그 이름으로 만들지 않는다(되써 넣을 원문을 덮지 않게). */
    fun rejectedName(name: String): String? {
        val key = HoloRules.keyOf(name)
        return snap.file.rejected.keys.firstOrNull { HoloRules.keyOf(it) == key }
    }

    /** 켤 때(메인 — 시작 중). 못 읽은 칸은 까닭과 함께 로그에. 파일이 깨졌으면 빈 채로 잠근다. */
    fun loadNow() {
        val read = readFile()
        if (read.file == null) {
            locked = read.error
            ess.logger.severe("holograms.yml 을 YAML 로 읽지 못했습니다 — 홀로그램을 하나도 세우지 않고, 고칠 때까지 저장하지 않습니다(파일은 그대로): ${read.error}")
            return
        }
        locked = null
        swap(read.file)
        logRejected(read.file)
        ess.logger.info("홀로그램 ${snap.file.holograms.size}개를 읽었습니다")
    }

    private fun logRejected(read: HologramFile) {
        for (name in read.rejected.keys) {
            ess.logger.warning("holograms.yml 의 홀로그램 '$name' 을(를) 읽지 못했습니다(지우지 않고 둡니다): ${read.reasons[name] ?: "?"}")
        }
    }

    /** 읽은 결과 — [file] 이 null 이면 YAML 문법 오류([error]). */
    private class Read(val file: HologramFile?, val error: String?)

    private fun readFile(): Read {
        val f = file
        if (!f.isFile) return Read(HologramFile(), null)
        val yaml = try {
            YamlConfiguration().apply { loadFromString(f.readText(Charsets.UTF_8)) }
        } catch (e: Exception) { // InvalidConfigurationException · IOException
            return Read(null, broken(f, e))
        }
        return Read(HologramCodec.read(yaml), null)
    }

    /** 깨진 파일의 사본(`<이름>.broken-<시각>`)을 남기고 사람이 읽을 까닭을 돌려준다. 원본은 건드리지 않는다. */
    private fun broken(f: File, e: Exception): String {
        val copy = File(f.parentFile, "${f.name}.broken-${LocalDateTime.now().format(STAMP)}")
        val saved = runCatching { f.copyTo(copy, overwrite = true) }.isSuccess
        val why = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: e.toString()
        return why + if (saved) " (사본 ${copy.name})" else ""
    }

    /**
     * `/홀로그램 다시읽기` — 워커에서 읽고 메인에서 바꿔 끼운다. 읽는 동안 메인에서 고친 것이 있으면(그 저장은 워커 줄에서 이 읽기 **뒤**) 읽은
     * 것을 버리고 다시 읽는다 — 그래야 그 고침이 담긴 파일을 읽는다. 세 번 겹치면 메모리를 그대로 두고 [done] 에 지금 것을 준다.
     * 파일이 YAML 로 깨졌으면 메모리(지금 홀로그램)는 그대로 두고 잠근 뒤 [done] 의 둘째 값에 까닭을 준다.
     */
    fun reload(done: (HologramFile, String?) -> Unit) = reload(done, 0)

    private fun reload(done: (HologramFile, String?) -> Unit, attempt: Int) {
        val started = commits
        ess.io.async({ readFile() }) { read ->
            if (commits != started) {
                if (attempt < 3) reload(done, attempt + 1) else done(snap.file, null)
                return@async
            }
            val file = read.file
            if (file == null) {
                locked = read.error
                ess.logger.severe("holograms.yml 을 YAML 로 읽지 못했습니다 — 지금 홀로그램을 그대로 쓰고, 고칠 때까지 저장하지 않습니다: ${read.error}")
                return@async done(snap.file, read.error)
            }
            locked = null
            swap(file)
            logRejected(file)
            done(file, null)
        }
    }

    fun put(hologram: Hologram, old: String = hologram.name) {
        val oldKey = HoloRules.keyOf(old)
        commit(snap.file.copy(holograms = snap.file.holograms.filter { it.key != oldKey && it.key != hologram.key } + hologram))
    }

    fun putAll(changed: List<Hologram>) {
        if (changed.isEmpty()) return
        val keys = changed.map { it.key }.toSet()
        commit(snap.file.copy(holograms = snap.file.holograms.filter { it.key !in keys } + changed))
    }

    fun remove(name: String): Boolean {
        val key = HoloRules.keyOf(name)
        if (snap.file.holograms.none { it.key == key }) return false
        commit(snap.file.copy(holograms = snap.file.holograms.filter { it.key != key }))
        return true
    }

    /** 검증기 — 파일에 쓰지 않고 바꿨다 되돌린다. */
    internal fun swap(value: HologramFile) {
        val index = HashMap<String, HashMap<Long, MutableList<Hologram>>>()
        for (h in value.holograms) {
            index.getOrPut(h.world) { HashMap() }.getOrPut(chunkKey(kotlin.math.floor(h.x).toInt() shr 4, kotlin.math.floor(h.z).toInt() shr 4)) { ArrayList() } += h
        }
        snap = Snapshot(value, index)
        onChange?.invoke()
    }

    private fun commit(value: HologramFile) {
        locked?.let {
            ess.logger.warning("holograms.yml 이 깨져 있어 고친 것을 버립니다(저장하면 파일을 덮습니다) — 파일을 고치고 /홀로그램 다시읽기: $it")
            return
        }
        commits++
        swap(value)
        val yaml = HologramCodec.write(value)
        ess.io.asyncRun { ess.io.save(file, yaml) }
    }

    companion object {
        const val FILE = "holograms.yml"

        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun chunkKey(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
    }
}

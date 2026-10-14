package com.inmc.essentials.warp

import com.inmc.essentials.Ess
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 워프 저장소 — `warps.yml` 한 벌을 메모리에(`@Volatile` 스냅샷), 고치면 통째로 I/O 워커에서 원자적으로 쓴다(`ConfigService.save` → `AtomicFiles`).
 * 워커는 한 줄이라 쓰기 순서가 지켜진다. 읽지 못한 칸([WarpFile.rejected])은 그대로 되써 넣는다(지뢰 13).
 */
class WarpStore(private val ess: Ess) {

    @Volatile
    var data: WarpFile = WarpFile()
        private set

    val file: File get() = ess.io.file(FILE)

    fun all(): List<Warp> = data.warps

    fun get(name: String): Warp? {
        val key = Warp.keyOf(name)
        return data.warps.firstOrNull { it.key == key }
    }

    /** 마지막으로 읽었거나 우리가 쓴 뒤의 파일 시각 — 손으로 고친 것을 알아본다([reloadIfChanged]). */
    @Volatile
    private var stamp = 0L

    /** 아직 끝나지 않은 우리 쓰기 수 — 쓰는 중에는 다시 읽지 않는다(반쯤 옛 내용으로 메모리를 되돌리지 않게). */
    private val writing = AtomicInteger()

    /** 켤 때(메인 — 시작 중). 못 읽은 칸은 로그에 남긴다. */
    fun loadNow() {
        if (!file.isFile) {
            data = WarpFile()
            stamp = 0L
            return
        }
        stamp = file.lastModified()
        data = WarpCodec.read(ess.io.load(file))
        for (raw in data.rejected) ess.logger.warning("warps.yml 의 워프 한 칸을 읽지 못했습니다(지우지 않고 둡니다): $raw")
        ess.logger.info("워프 ${data.warps.size}개를 읽었습니다")
    }

    /**
     * `/에센셜 리로드` — 파일이 우리가 마지막으로 읽거나 쓴 뒤 바뀌었으면(손으로 고침) 다시 읽는다. 시각·쓰기 중 검사만 메인에서, **읽기는 I/O 워커**,
     * 반영은 메인에서(리로드 계약 — 메인에서 파일을 읽지 않는다). 우리 쓰기가 진행 중이거나 읽는 사이 게임 안에서 고쳐 스냅샷이 바뀌었으면 반영하지
     * 않고 로그만 남긴다(그 고침이 파일을 다시 쓴다 — 손으로 고친 것은 덮인다).
     */
    fun reloadIfChanged() {
        if (!file.isFile || file.lastModified() == stamp) return
        if (writing.get() > 0) {
            ess.logger.warning("warps.yml 을 쓰는 중이라 다시 읽지 않았습니다 — 잠시 뒤 다시 리로드하세요")
            return
        }
        val before = data
        ess.io.async({ file.lastModified() to WarpCodec.read(ess.io.load(file)) }) { (modified, read) ->
            if (data !== before || writing.get() > 0) {
                ess.logger.warning("warps.yml 을 다시 읽는 사이 게임 안에서 워프를 고쳐 손으로 고친 것을 반영하지 않았습니다 — 잠시 뒤 다시 리로드하세요")
                return@async
            }
            stamp = modified
            data = read
            for (raw in read.rejected) ess.logger.warning("warps.yml 의 워프 한 칸을 읽지 못했습니다(지우지 않고 둡니다): $raw")
            ess.logger.info("손으로 고친 warps.yml 을 다시 읽었습니다 — 워프 ${read.warps.size}개")
        }
    }

    /** 넣거나 바꾼다([old] 이름의 것을 빼고 넣는다 — 이름 바꾸기). */
    fun put(warp: Warp, old: String = warp.name) {
        val oldKey = Warp.keyOf(old)
        val list = data.warps.filter { it.key != oldKey && it.key != warp.key } + warp
        commit(data.copy(warps = list))
    }

    fun putAll(changed: List<Warp>) {
        if (changed.isEmpty()) return
        val keys = changed.map { it.key }.toSet()
        commit(data.copy(warps = data.warps.filter { it.key !in keys } + changed))
    }

    fun remove(name: String): Boolean {
        val key = Warp.keyOf(name)
        if (data.warps.none { it.key == key }) return false
        commit(data.copy(warps = data.warps.filter { it.key != key }))
        return true
    }

    /** 검증기 — 파일에 쓰지 않고 바꿨다 되돌린다. */
    internal fun swap(value: WarpFile) {
        data = value
    }

    private fun commit(value: WarpFile) {
        data = value
        val yaml = WarpCodec.write(value)
        writing.incrementAndGet()
        ess.io.asyncRun {
            try {
                ess.io.save(file, yaml)
                stamp = file.lastModified()
            } finally {
                writing.decrementAndGet()
            }
        }
    }

    companion object {
        const val FILE = "warps.yml"
    }
}

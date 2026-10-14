package com.inmc.essentials.service

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * CMI 데이터 가져오기의 계약(2026-10-10). CMI 를 빼기 **전에** 한 번 돈다(`/에센셜 가져오기 cmi`, server 기능).
 *
 * 나눈 까닭: CMI 의 DB·YAML 을 읽는 일(형식 판독)은 server 기능 한 곳이 하고, 읽은 것을 자기 표에 넣는 일은 그 자료의 주인 기능이 한다 —
 * 홈은 homes 가, 뒤로는 teleport 가, 뮤트는 moderation 이. 기능끼리 import 하지 않게 이 파일의 중립 자료만 오간다.
 *
 * 형식은 CMI 9.8.9.8 jar 로 확인한 것이다(테섭에는 실제 값이 거의 없다 — 유저 4명). **모르는 모양을 조용히 버리지 않는다**:
 * 읽지 못한 줄은 [ImportReport.unreadable] 로 남겨 관리자가 본다(홈·우편이 소리 없이 사라지는 것이 가장 나쁜 결과다).
 */
interface CmiImportable {

    /**
     * [data] 에서 자기 몫을 자기 저장소에 넣는다. **메인 스레드에서 불린다** — DB 쓰기는 `ess.db.run` 으로 넘기고 바로 돌아간다.
     * 같은 것을 두 번 가져와도 겹치지 않게(이미 있으면 [ImportReport.skipped]). 넣은 수·건너뛴 수를 [report] 에 적는다.
     */
    fun importCmi(data: CmiData, report: ImportReport)
}

/** CMI 의 위치 하나. DB 는 `world:x:y:z:pitch:yaw`(CMI `invertLoc` — Homes JSON 안은 yaw 다음 pitch), config 는 `world;x;y;z;yaw;pitch` — 읽는 쪽이 셋 다 이것으로 바꾼다. */
data class CmiLocation(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float = 0f, val pitch: Float = 0f)

data class CmiHome(val name: String, val location: CmiLocation, val icon: String? = null, val private: Boolean = false)

/** CMI 우편 한 통. [keepForSeconds] 는 `sendtemp`·`mailall` 의 보관 기간(초, CMI 는 `time + keepFor*1000` 에 지운다) — 없으면 null(CMI 기본 만료). */
data class CmiMail(val sender: String?, val time: Long?, val message: String, val keepForSeconds: Long? = null)

data class CmiMute(val until: Long?, val reason: String?, val shadow: Boolean = false)

data class CmiJail(val jail: String?, val cell: Int?, val until: Long?, val reason: String?)

/** CMI 유저 한 사람. 값이 없으면 null·빈 목록. 모르는 열은 [extra] 에 원문 그대로. */
data class CmiUser(
    val id: UUID,
    val name: String,
    val nickname: String? = null,
    val lastLogin: Long? = null,
    val lastLogoff: Long? = null,
    /** 밀리초. */
    val totalPlayTime: Long? = null,
    val logout: CmiLocation? = null,
    val back: CmiLocation? = null,
    val death: CmiLocation? = null,
    val homes: List<CmiHome> = emptyList(),
    val mail: List<CmiMail> = emptyList(),
    val ignores: List<UUID> = emptyList(),
    val mute: CmiMute? = null,
    val jail: CmiJail? = null,
    val warnings: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    /** 킷 이름 → 마지막으로 받은 때(밀리초). */
    val kits: Map<String, Long> = emptyMap(),
    val userMeta: Map<String, String> = emptyMap(),
    /** 남은 시간제 비행·무적(초) — 끝났으면 null. */
    val tfly: Long? = null,
    val tgod: Long? = null,
    val votes: Int? = null,
    val extra: Map<String, String> = emptyMap(),
)

data class CmiWarp(
    val name: String,
    val location: CmiLocation,
    val group: String? = null,
    val permission: Boolean = false,
    val icon: String? = null,
    val raw: Map<String, Any?> = emptyMap(),
)

/**
 * 읽어 낸 CMI 전부. YAML 저장 파일은 형식이 기능마다 달라 **원본 그대로**(비었거나 jar 기본값과 같으면 null) 넘긴다 — 주인 기능이 판독한다.
 * [folder] 는 `plugins/CMI` (읽기 전용으로 다룰 것 — 지우지도 고치지도 않는다).
 */
data class CmiData(
    val folder: File,
    val users: List<CmiUser>,
    val warps: List<CmiWarp>,
    /** 서버 스폰(config `Spawn.Main.Location`)·첫 스폰. */
    val spawn: CmiLocation? = null,
    val firstSpawn: CmiLocation? = null,
    val kits: YamlConfiguration? = null,
    val holograms: YamlConfiguration? = null,
    val portals: YamlConfiguration? = null,
    val jails: YamlConfiguration? = null,
    val schedules: YamlConfiguration? = null,
    val playtimeRewards: YamlConfiguration? = null,
    val customAliases: YamlConfiguration? = null,
    val eventCommands: YamlConfiguration? = null,
)

/** 가져오기 결과 — 끝나면 `plugins/inmc-essentials/import/cmi-<시각>.txt` 로 남고 요약이 관리자에게 간다. */
class ImportReport {

    private val counts = linkedMapOf<String, IntArray>() // 종류 → [읽음, 넣음, 건너뜀]
    private val lines = mutableListOf<String>()

    @Synchronized
    fun read(kind: String, n: Int = 1) { slot(kind)[0] += n }

    @Synchronized
    fun imported(kind: String, n: Int = 1) { slot(kind)[1] += n }

    /** 이미 있거나 일부러 뺀 것 — 까닭을 남긴다. */
    @Synchronized
    fun skipped(kind: String, why: String) {
        slot(kind)[2] += 1
        lines += "건너뜀 [$kind] $why"
    }

    /** 형식을 알아보지 못한 것 — **반드시** 원문 일부와 함께 남긴다. */
    @Synchronized
    fun unreadable(kind: String, what: String) {
        lines += "못 읽음 [$kind] $what"
    }

    @Synchronized
    fun note(text: String) { lines += text }

    @Synchronized
    fun summary(): List<String> = counts.map { (kind, c) -> "$kind: 읽음 ${c[0]} · 넣음 ${c[1]} · 건너뜀 ${c[2]}" }

    @Synchronized
    fun details(): List<String> = lines.toList()

    private fun slot(kind: String): IntArray = counts.getOrPut(kind) { IntArray(3) }
}

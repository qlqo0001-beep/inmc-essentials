package com.inmc.essentials.feature

import com.inmc.essentials.Ess
import com.inmc.essentials.verify.Check
import kr.inmc.core.store.SqlSource
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 기능 하나의 틀. 기능은 자기 패키지 안에서 끝난다 — 다른 기능을 import 하지 않고, 서로 닿을 일은 [Ess] 의 서비스 접점
 * (`teleporter`·`afk`·`kits`·`gate`)과 core 창구(`Mail`·`Vanish`)로만 한다.
 *
 * **생성자와 프로퍼티 초기화에서 [ess] 의 서비스를 만지지 않는다.** 시험이 서버 없이 가짜 [Ess] 로 기능을 만들어 [id]·[permissions]·
 * [messageDefaults] 를 읽는다. 서비스는 [enable] 부터.
 *
 * 순서(켤 때): [schema](꺼진 기능도) → [load] → [enable] → (명령 등록 · 틱 시작) → [ready]. 끌 때: [disable](거꾸로).
 */
abstract class Feature(protected val ess: Ess) {

    /** `config.yml modules.<id>` · `messages/<id>.yml` · `settings/<id>.yml` 의 이름. */
    abstract val id: String

    /** 켤 때 등록하는 권한(`inmcessentials.<기능>.<동작>`, 남에게 = `.others`). */
    open val permissions: List<Perm> = emptyList()

    /** 메시지 기본값. 키는 반드시 `"<id>-"` 로 시작하고 `src/main/resources/messages/<id>.yml` 과 키가 정확히 같다(`ResourceTest`). */
    open val messageDefaults: Map<String, String> = emptyMap()

    /** true 면 `src/main/resources/settings/<id>.yml` 을 첫 부팅에 복사하고 [load] 에 넘긴다(없는 키는 배포 파일 값으로 채워진다). */
    open val hasSettings: Boolean = false

    /** 표 만들기(`CREATE TABLE IF NOT EXISTS ess_<…>`). DB 쓰기 스레드에서, **꺼진 기능도** 불린다 — 켰을 때 표가 없어 죽지 않게. */
    open fun schema(sql: SqlSource) {}

    /** 켤 때와 `/에센셜 리로드` 때(메인 스레드 — 파일은 이미 읽혀 있다). 던지지 말고 불변 스냅샷으로 바꿔 끼운다. */
    open fun load(settings: YamlConfiguration) {}

    /**
     * 리스너 `ess.listen(…)` · 명령 `ess.commands.add(…)` · 틱 `ess.ticks.add(…)` · 서비스 바꿔 끼우기. 한 번만 불린다.
     *
     * - 여기서 던지면 이 기능만 빠지고 그때까지 건 것(위 넷 · 권한 · 순위 대상)은 플러그인이 거두고 [disable] 을 부른다.
     * - **다른 기능이 켜졌는지(`ess.enabled(id)`)는 여기서 보지 않는다** — 목록에서 뒤에 있는 기능은 아직 안 켜졌다. 엮기는 [ready] 에서,
     *   또는 부를 때마다 본다. 설정에서 켰는지만이면 `ess.config.enabled(id)`.
     */
    open fun enable() {}

    /** 모든 기능이 켜지고 DB·순위·우편함이 읽힌 뒤(첫 틱). 이미 접속한 사람(`/reload`)을 여기서 다시 읽는다. */
    open fun ready() {}

    /**
     * 종료. 막는 쓰기(flush) 허용 — DB 워커는 이 뒤에 닫힌다.
     *
     * **[load]·[enable] 이 던진 뒤에도 불린다**(반쯤 켜진 상태) — `lateinit` 은 `::x.isInitialized` 로 보고, 건 것만 뗀다(core `Mail`·`Vanish`
     * 공급처, 띄운 엔티티 …). 명령·리스너·틱·권한·접점은 플러그인이 거두므로 여기서 할 일이 아니다.
     */
    open fun disable() {}

    /** `/에센셜 검증` 의 검사. 진짜 사건을 쏘고, 바꾼 것은 메모리에서만 바꿨다 되돌린다. */
    open fun checks(): List<Check> = emptyList()
}

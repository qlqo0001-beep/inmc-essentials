package com.inmc.essentials.service

import java.util.UUID

/**
 * 관리 제재 상태가 다른 기능과 만나는 곳(`ess.moderation`). moderation 기능이 켜지면 자기 구현으로 바꿔 끼우고, 꺼져 있으면 [NONE] — 아무도
 * 묶이지 않은 것으로 본다.
 *
 * 쓰는 쪽: chat(귓속말·답장·도움요청·거리 채팅 — 뮤트면 막고, 그림자 뮤트면 보낸 사람에게만 보인 척), mail(편지 보내기), 그 밖에 사람의 말을
 * 남에게 전하는 곳. 공개 채팅의 뮤트·채팅잠금·감옥·수갑의 **이동·명령 막기**는 moderation 기능이 자기 리스너로 직접 한다 — 여기를 볼 필요가 없다.
 *
 * **어느 스레드에서 불러도 된다**(비동기 채팅 사건에서 부른다) — 구현은 메모리의 동시성 안전한 표에서 답하고, 파일·DB 를 만지지 않는다.
 */
interface ModerationState {

    /** 말이 막혔나(기간 뮤트는 끝났으면 false). 그림자 뮤트도 true. */
    fun isMuted(id: UUID): Boolean

    /** 그림자 뮤트 — 막혔지만 본인은 모른다. 본인에게는 보낸 것처럼 보이고 남에게는 안 간다. [isMuted] 도 true 다. */
    fun isShadowMuted(id: UUID): Boolean

    /** 감옥에 있나. */
    fun isJailed(id: UUID): Boolean

    /** 수갑을 찼나(움직임·상호작용·명령이 묶였다 — 설정에 따라 채팅도). */
    fun isCuffed(id: UUID): Boolean

    companion object {

        /** moderation 기능이 꺼져 있을 때. */
        val NONE: ModerationState = object : ModerationState {
            override fun isMuted(id: UUID): Boolean = false
            override fun isShadowMuted(id: UUID): Boolean = false
            override fun isJailed(id: UUID): Boolean = false
            override fun isCuffed(id: UUID): Boolean = false
        }
    }
}

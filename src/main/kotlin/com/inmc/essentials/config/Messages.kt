package com.inmc.essentials.config

import com.inmc.essentials.feature.Feature
import com.inmc.essentials.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 메시지 카탈로그 **하나** — `messages/common.yml`([COMMON]) + 켜진 기능마다 `messages/<id>.yml`([Feature.messageDefaults]).
 * 기능 키는 `<id>-` 접두라 서로 겹치지 않는다. 읽고 보내는 부분은 core [MessageCatalog] 가 갖고 있다 — `send(target, key, ph)`.
 *
 * `ResourceTest` 가 배포 파일과 기본값 표의 키가 정확히 같은지, 코드가 부르는 키가 다 있는지 본다.
 */
class Messages(values: Map<String, String>, val defaults: Map<String, String>) : MessageCatalog<Ph>(values, defaults) {

    companion object {

        /** 공통 키 + 켜진 기능의 기본값. */
        fun defaults(features: List<Feature>): Map<String, String> {
            val out = LinkedHashMap(COMMON)
            for (feature in features) out.putAll(feature.messageDefaults)
            return out
        }

        /** 기본값 위에 [files](common.yml · 기능 파일들)를 차례로 덮는다. 섹션은 건너뛰고 잎만 — 일부만 고친 파일도 나머지는 기본값. */
        fun build(features: List<Feature>, files: List<YamlConfiguration>): Messages {
            val defaults = defaults(features)
            var values: Map<String, String> = defaults
            for (file in files) values = merge(values, file)
            return Messages(values, defaults)
        }

        /**
         * 공통 키. core 가 호스트에게 보내 달라는 키(보상·우편함·순위·입력)도 여기 있다 — core 는 우리 키를 이름으로 부른다
         * (`RewardService`·`Mailbox`·`RankService`·`ChatPrompt`).
         */
        val COMMON: Map<String, String> = linkedMapOf(
            PREFIX to "<gradient:#56ab2f:#a8e063>[ 에센셜 ]</gradient> ",

            // --- 공통 ---------------------------------------------------------------
            "player-only" to "<red>플레이어만 쓸 수 있습니다.</red>",
            "no-permission" to "<red>권한이 없습니다.</red>",
            "player-not-found" to "<red>'{값}' 은(는) 접속 중인 플레이어가 아닙니다.</red>",
            "user-not-found" to "<red>'{값}' 은(는) 이 서버에 온 적이 없는 플레이어입니다.</red>",
            "invalid-number" to "<red>숫자가 아닙니다: {값}</red>",
            "feature-disabled" to "<red>이 기능은 꺼져 있습니다.</red>",

            // --- 관리(/에센셜) --------------------------------------------------------
            "reloaded" to "<green>다시 불러왔습니다.</green> <gray>(기능 {개수}개)</gray>",
            "reload-restart-needed" to "<yellow>저장소·기능 켜고 끄기는 서버를 다시 켜야 바뀝니다.</yellow>",
            "modules-header" to "<gold>기능 {개수}개</gold> <gray>— config.yml 의 modules 에서 켜고 끕니다(재시작)</gray>",
            "module-on" to "<green>●</green> <white>{값}</white>",
            "module-off" to "<dark_gray>○ {값} (꺼짐)</dark_gray>",
            "module-failed" to "<red>✖ {값}</red> <gray>(켜다 실패 — 서버 로그를 보세요)</gray>",
            "verify-done" to "<green>검증 끝 — 통과 {금액} · 실패 {개수}</green> <gray>(plugins/inmc-essentials/verify/ 에 기록)</gray>",
            "verify-failure" to "<red>✖</red> <gray>{값}</gray>",
            "help-admin" to listOf(
                "<red>/에센셜 리로드</red> <gray>- config·메시지·기능 설정 다시 읽기</gray>",
                "<red>/에센셜 검증</red> <gray>- 서버 안 자동 검사</gray>",
                "<red>/에센셜 모듈</red> <gray>- 기능 목록과 켜짐</gray>",
            ).joinToString("\n"),

            // --- core 보상·우편함·순위 -------------------------------------------------
            "reward-inventory-full" to "<yellow>가방이 가득 차서 일부 보상을 우편으로 보냈습니다.</yellow> <gray>/우편</gray>",
            "reward-dropped" to "<yellow>가방이 가득 차서 일부 보상을 바닥에 떨어뜨렸습니다.</yellow>",
            "reward-announce" to "<gold>★</gold> <yellow>{플레이어}</yellow>님이 <yellow>{이름}</yellow>에서 <aqua>{아이템}</aqua>을(를) 받았습니다!",
            "mailbox-waiting" to "<yellow>받지 않은 보상이 {개수}개 있습니다.</yellow> <gray>/우편</gray>",
            "mailbox-money-held" to "<yellow>돈을 주지 못해 금액 보상을 그대로 보관합니다.</yellow>",
            "rank-reward-received" to "<gold>{이름} {순위}위 보상을 받았습니다!</gold>",
            "rank-reset-broadcast" to "<gold>▶</gold> <yellow>{이름}</yellow> <gray>시즌 {시즌} 이(가) 끝났습니다. 순위 보상을 보냈습니다.</gray>",

            // --- core 입력(채팅 경로 -Dinmc.prompts=chat 에서만) --------------------------
            "prompt-enter" to "<yellow>채팅으로 값을 입력하세요.</yellow> <gray>(취소: 취소)</gray>",
            "prompt-cancelled" to "<gray>입력을 취소했습니다.</gray>",
            "prompt-timeout" to "<gray>입력 시간이 지났습니다.</gray>",
            "prompt-invalid-number" to "<red>숫자를 입력해 주세요.</red>",
        )
    }
}

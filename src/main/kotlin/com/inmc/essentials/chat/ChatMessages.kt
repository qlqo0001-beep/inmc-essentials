package com.inmc.essentials.chat

/**
 * chat 의 메시지 기본값 — `messages/chat.yml` 과 키가 정확히 같다(ResourceTest).
 *
 * 귓속말·스파이·도움요청·스태프 틀은 접두사 없이 그대로 나가고, `{메시지}` 자리에 사람이 친 글이 **따로** 끼워진다(`ChatText` — 틀의
 * MiniMessage·PAPI 를 거치지 않는다). `{실명}` 은 클릭 명령에 넣는 진짜 이름, `{플레이어}`·`{대상}` 은 보이는 이름(타이틀포지 닉네임).
 */
object ChatMessages {

    val ALL: Map<String, String> = linkedMapOf(
        // --- 귓속말 ---
        "chat-msg-to" to "<gray>[<white>나</white> <dark_gray>→</dark_gray> <click:suggest_command:'/귓 {대상실명} '><hover:show_text:'<gray>눌러서 다시 보내기</gray>'><yellow>{대상}</yellow></hover></click>]</gray> <white>{메시지}</white>",
        "chat-msg-from" to "<gray>[<click:suggest_command:'/귓 {실명} '><hover:show_text:'<gray>눌러서 답장</gray>'><yellow>{플레이어}</yellow></hover></click> <dark_gray>→</dark_gray> <white>나</white>]</gray> <white>{메시지}</white>",
        "chat-msg-clean" to "<white>{메시지}</white>",
        "chat-msg-usage" to "<yellow>/귓 [플레이어] [내용]</yellow> <gray>- 귓속말 · /답장 [내용]</gray>",
        "chat-msg-self" to "<red>자기 자신에게는 귓속말을 보낼 수 없습니다.</red>",
        "chat-msg-too-long" to "<red>너무 깁니다. {개수}자까지 보낼 수 있습니다.</red>",
        "chat-msg-muted" to "<red>뮤트 중에는 귓속말을 보낼 수 없습니다.</red>",
        "chat-msg-not-accepting" to "<red>{대상}님은 지금 귓속말을 받지 않습니다.</red>",
        "chat-msg-ignored" to "<red>{대상}님이 당신을 무시하고 있습니다.</red>",
        "chat-msg-group-denied" to "<red>{대상}님({값} 그룹)에게는 귓속말을 보낼 수 없습니다.</red>",
        "chat-msg-target-afk" to "<gray>{대상}님은 지금 자리를 비웠습니다.</gray>",
        "chat-reply-usage" to "<yellow>/답장 [내용]</yellow> <gray>- 마지막 대화 상대에게</gray>",
        "chat-reply-none" to "<red>답장할 상대가 없습니다.</red>",
        "chat-reply-gone" to "<red>답장할 상대가 접속 중이 아닙니다.</red>",

        // --- 스파이 알림 ---
        "chat-spy-msg" to "<dark_gray>[스파이]</dark_gray> <gray>{플레이어} → {대상}:</gray> <white>{메시지}</white>",
        "chat-spy-msg-shadow" to "<dark_gray>[스파이 · 그림자 뮤트]</dark_gray> <gray>{플레이어} → {대상}:</gray> <dark_gray>{메시지}</dark_gray>",
        "chat-spy-command" to "<dark_gray>[명령 스파이]</dark_gray> <gray>{플레이어}:</gray> <white>{메시지}</white>",
        "chat-spy-sign" to "<dark_gray>[표지판 스파이]</dark_gray> <gray>{플레이어}</gray> <dark_gray>({월드} {좌표})</dark_gray><gray>:</gray> <white>{메시지}</white>",
        "chat-spy-range" to "<dark_gray>[범위 밖]</dark_gray> <gray>{플레이어}:</gray> <white>{메시지}</white>",

        // --- 무시 ---
        "chat-ignore-self" to "<red>자기 자신은 무시할 수 없습니다.</red>",
        "chat-ignore-added" to "<green>{대상}님을 무시합니다.</green> <gray>귓속말·채팅·편지가 오지 않습니다. 다시 치면 풀립니다.</gray>",
        "chat-ignore-added-all" to "<green>모두를 무시합니다.</green> <gray>운영진의 글만 옵니다. /무시 all 로 풀기</gray>",
        "chat-ignore-removed" to "<green>{대상}님을 더 이상 무시하지 않습니다.</green>",
        "chat-ignore-removed-all" to "<green>모두 무시를 풀었습니다.</green>",
        "chat-ignore-bypass-note" to "<gray>{대상}님은 운영진이라 무시해도 글이 옵니다.</gray>",
        "chat-ignore-empty" to "<gray>무시 목록이 비었습니다.</gray> <yellow>/무시 [플레이어]</yellow>",
        "chat-ignore-list-header" to "<gold>{플레이어}님의 무시 목록 {개수}명</gold> <gray>(눌러서 풀기)</gray>",
        "chat-ignore-list-entry" to "<gray>{순위}.</gray> <click:run_command:'{명령}'><hover:show_text:'<red>눌러서 무시 풀기</red>'><white>{대상}</white></hover></click>",
        "chat-ignore-all-name" to "모두",

        // --- 도움요청 · 스태프 ---
        "chat-helpop-usage" to "<yellow>/도움요청 [내용]</yellow> <gray>- 접속한 운영진에게</gray>",
        "chat-helpop-message" to "<red>[도움]</red> <gray>[<click:suggest_command:'/귓 {실명} '><hover:show_text:'<gray>눌러서 귓속말</gray>'><white>{플레이어}</white></hover></click>]</gray> <yellow>{메시지}</yellow>",
        "chat-helpop-feedback" to "<green>질문을 접속한 운영진에게 보냈습니다.</green>",
        "chat-helpop-offline" to "<yellow>지금 접속한 운영진이 없습니다. 나중에 다시 물어 주세요.</yellow>",
        "chat-helpop-muted" to "<red>뮤트 중에는 도움요청을 보낼 수 없습니다.</red>",
        "chat-staff-message" to "<dark_aqua>[스태프]</dark_aqua> <gray>[<click:suggest_command:'/귓 {실명} '><white>{플레이어}</white></click>]</gray> <aqua>{메시지}</aqua>",
        "chat-staff-on" to "<green>스태프 채팅을 켰습니다.</green> <gray>이제 채팅창의 글이 전부 스태프 채널로 갑니다. /스태프 로 끄기</gray>",
        "chat-staff-off" to "<yellow>스태프 채팅을 껐습니다.</yellow>",

        // --- 스파이 켜고 끄기 ---
        "chat-spy-on" to "<green>{값} 스파이를 켰습니다.</green>",
        "chat-spy-off" to "<yellow>{값} 스파이를 껐습니다.</yellow>",
        "chat-spy-other-on" to "<green>{플레이어}님의 {값} 스파이를 켰습니다.</green>",
        "chat-spy-other-off" to "<yellow>{플레이어}님의 {값} 스파이를 껐습니다.</yellow>",
        "chat-spy-no-permission-note" to "<gray>{플레이어}님은 스파이 권한이 없어 켜 둬도 아무것도 보이지 않습니다.</gray>",
        "chat-spy-unknown-kind" to "<red>'{값}' 은(는) 스파이 종류가 아닙니다.</red> <gray>(명령 · 표지판 · 채팅 · 전체)</gray>",
        "chat-spy-status-header" to "<gold>{플레이어}님의 스파이</gold> <gray>(눌러서 켜고 끄기)</gray>",
        "chat-spy-status-line" to "<click:run_command:'{명령}'><hover:show_text:'<gray>눌러서 켜고 끄기</gray>'><gray>·</gray> <white>{값}</white> {상태}</hover></click>",
        "chat-spy-kind-command" to "명령",
        "chat-spy-kind-sign" to "표지판",
        "chat-spy-kind-chat" to "귓속말·채팅",
        "chat-state-on" to "<green>켜짐</green>",
        "chat-state-off" to "<red>꺼짐</red>",

        // --- 공개 채팅 ---
        "chat-spam-blocked" to "<red>같은 말을 너무 자주 했습니다. 잠시 뒤에 다시 말해 주세요.</red>",
        "chat-shout-cost-missing" to "<red>외치려면 {금액}이(가) 있어야 합니다.</red> <gray>보통 채팅으로 보냈습니다.</gray>",
        "chat-range-nobody" to "<gray>근처에 들은 사람이 없습니다.</gray> <dark_gray>(! 로 시작하면 외칩니다)</dark_gray>",
        "chat-papi-unlimited" to "무제한",
        "chat-link-label" to "<aqua><u>[링크]</u></aqua>",

        // --- 공지 · 채팅끄기 · 타이틀 ---
        "chat-broadcast-format" to "<gold>[<yellow>공지</yellow>]</gold> <white>{메시지}</white>",
        "chat-broadcast-usage" to "<yellow>/공지 [내용]</yellow> <gray>(-w:월드,월드 -r:거리 -c:월드;x;y;z · 앞에 ! = 머리말 없이)</gray>",
        "chat-broadcast-empty" to "<red>보낼 내용이 없습니다.</red>",
        "chat-broadcast-sent" to "<green>{개수}명에게 공지했습니다.</green>",
        "chat-broadcast-world-unknown" to "<red>월드를 찾을 수 없습니다: {월드}</red>",
        "chat-broadcast-center-invalid" to "<red>중심 위치를 읽지 못했습니다: {값}</red> <gray>(-c:월드;x;y;z — 콘솔에서 -r 을 쓰면 꼭 적어야 합니다)</gray>",
        "chat-broadcast-range-invalid" to "<red>거리 옵션을 읽지 못해 공지하지 않았습니다: {값}</red> <gray>(-r:거리 는 양수 · -c:월드;x;y;z 를 쓰면 -r 도 꼭)</gray>",
        "chat-silence-on" to "<yellow>공개 채팅을 껐습니다.</yellow> <gray>귓속말·공지는 그대로 옵니다. /채팅끄기 로 다시 켜기</gray>",
        "chat-silence-off" to "<green>공개 채팅을 다시 켰습니다.</green>",
        "chat-title-usage" to "<yellow>/타이틀 [플레이어|all] [제목 \\n 부제목]</yellow> <gray>(-in:틱 -keep:틱 -out:틱)</gray>",
        "chat-title-sent" to "<green>{개수}명에게 타이틀을 보냈습니다.</green>",
    )
}

package com.inmc.essentials.admininfo

/**
 * 투명 중 세부 동작(CMI `Vanish.Defaults` · `/cmi vanishedit`). 사람마다 바꾼 것만 저장하고, 안 바꾼 것은 설정의 기본값(`vanish.defaults`)을 따른다.
 *
 * 저장은 [key] 문자열로(순서값 금지 — 지뢰 5). [cmiKey] 는 CMI 이름 — `/투명설정 <대상> <옵션>` 에 둘 다 받는다.
 *
 * CMI 의 `noisyChest`(조용한 상자)·`afkcommands`(잠수 명령)·`PrivateMessages`(귓속말 받기)는 **여기 없다** — 각각 inventory·afk·chat 기능이 자기
 * 설정으로 정한다(docs/admininfo.md "CMI 와 다른 점").
 */
enum class VanishOption(val key: String, val cmiKey: String, val cmiDefault: Boolean, val label: String, val help: String) {
    DAMAGE_TO_ENTITY("damage-to-entity", "damageToEntity", false, "남에게 피해 주기", "꺼지면 투명인 동안 때린 피해(화살 포함)가 들어가지 않습니다"),
    PLAYER_DAMAGE("player-damage", "playerDamage", false, "피해 받기", "꺼지면 투명인 동안 피해를 받지 않습니다(허공·/kill 은 그대로)"),
    ITEM_PICKUP("item-pickup", "itemPickup", false, "아이템·경험치 줍기", "꺼지면 바닥 아이템·화살·경험치 구슬을 줍지 않습니다"),
    MOB_AGGRO("mob-aggro", "mobAggro", false, "몹이 노림", "꺼지면 몹이 노리지 않습니다"),
    INTERACTION("interaction", "interaction", false, "밟기 상호작용", "꺼지면 압력판·실·작물 밟기·거북알이 반응하지 않습니다"),
    INFORM_ON_LEAVE("inform-on-leave", "informOnLeave", false, "퇴장 메시지 보이기", "켜면 투명인 채 나가도 퇴장 메시지가 나갑니다"),
    INFORM_ON_JOIN("inform-on-join", "informOnJoin", false, "접속 메시지 보이기", "켜면 투명인 채 들어와도 접속 메시지가 나갑니다"),
    NIGHT_VISION("night-vision", "nightVision", true, "야간 투시", "투명인 동안 야간 투시"),
    BOSSBAR("bossbar", "bossbar", true, "보스바 표시", "투명인 동안 화면 위에 '투명' 막대"),
    RELOG_DISABLE("relog-disable", "relogDisable", false, "다시 접속하면 풀기", "켜면 나갔다 들어오면 투명이 풀립니다"),
    NO_MESSAGES("no-messages", "noMessages", false, "공개 채팅 막기", "켜면 투명인 동안 공개 채팅을 보내지 못합니다(실수로 들키지 않게)"),
    FAKE_JOIN_LEAVE("fake-join-leave", "fakeJoinLeave", false, "가짜 입퇴장 메시지", "켜면 투명을 켤 때 퇴장, 끌 때 접속 메시지를 못 보는 사람에게 보냅니다"),
    MOB_SPAWNING("mob-spawning", "mobSpawning", false, "주변 몹 생성", "꺼지면 투명인 사람 주변에 몹이 자연 생성되지 않습니다"),
    STOP_PLAYTIME("stop-playtime", "stopPlaytime", true, "플레이 시간 멈춤", "켜면 투명인 동안 플레이 시간(바닐라 통계)이 늘지 않습니다"),
    SLEEP_IGNORE("sleep-ignore", "sleepIgnore", true, "잠자기 인원에서 빼기", "켜면 밤 넘기기 인원 계산에서 빠집니다"),
    JOIN_VANISHED("join-vanished", "joinVanished", false, "늘 투명으로 접속", "켜면 접속할 때마다 투명으로 들어옵니다"),
    DEATH_MESSAGES("death-messages", "deathMessages", false, "죽음 메시지 보이기", "꺼지면 투명인 동안 죽음 메시지는 볼 수 있는 사람에게만"),
    HOOK_PLAYERS("hook-entities", "hookPlayers", false, "낚싯대로 끌기", "꺼지면 투명인 동안 낚싯대가 엔티티를 끌지 않습니다"),
    ;

    companion object {

        /** 설정 키·CMI 이름·한글 이름 무엇으로든(대소문자 무시). */
        fun parse(raw: String): VanishOption? {
            val t = raw.trim()
            return entries.firstOrNull { it.key.equals(t, true) || it.cmiKey.equals(t, true) || it.label.replace(" ", "") == t.replace(" ", "") }
        }

        /** 바꾼 것만 `key=1;key=0` 으로. 기본값과 같은 것도 그대로 남긴다(관리자가 일부러 고른 값 — 기본값이 바뀌어도 그대로). */
        fun encode(overrides: Map<VanishOption, Boolean>): String =
            overrides.entries.sortedBy { it.key.ordinal }.joinToString(";") { "${it.key.key}=${if (it.value) 1 else 0}" }

        /** [encode] 의 반대. 모르는 키·깨진 조각은 건너뛴다(옛 판의 옵션이 사라져도 나머지는 산다). */
        fun decode(text: String?): Map<VanishOption, Boolean> {
            if (text.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<VanishOption, Boolean>()
            for (part in text.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val option = entries.firstOrNull { it.key == part.substring(0, eq).trim() } ?: continue
                when (part.substring(eq + 1).trim().lowercase()) {
                    "1", "true" -> out[option] = true
                    "0", "false" -> out[option] = false
                }
            }
            return out
        }

        /** 켜기/끄기 말(한글·영어). 모르면 null. */
        fun parseState(raw: String): Boolean? = when (raw.trim().lowercase()) {
            "켜기", "켬", "on", "true", "1", "참" -> true
            "끄기", "끔", "off", "false", "0", "거짓" -> false
            else -> null
        }
    }
}

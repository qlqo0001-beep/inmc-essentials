package com.inmc.essentials.player

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.service.Abilities
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.CmiUser
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.util.Ph
import com.inmc.essentials.verify.Check
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.store.SqlSource
import kr.inmc.core.util.Durations
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/**
 * 플레이어 상태·능력 — 게임모드 · 경험치 · 무적/시간제무적 · 최대체력 · 회복 · 배고픔 · 효과 · 처치 · 자살 · 속도 · 비행/시간제비행 · 충돌 ·
 * 토템 · 타기/내리기 · 모자 · 앉기 · 근처 · 핑, 그리고 명령 없는 체력 보스바 · 가방 속 토템 · 의자.
 *
 * CMI 항목: cmd-gm · cmd-exp · cmd-god · cmd-maxhp · cmd-heal · cmd-effect · cmd-kill · sys-hungercache · cmd-speed · cmd-fly · cmd-tfly ·
 * cmd-playercollision · mod-totem · cmd-ride · cmd-hat · cmd-sit · cmd-suicide · near · cmd-ping · bossbar-hpbar. 운영 문서 `docs/player.md`.
 */
class PlayerFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "player"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(GM, PermissionDefault.OP, "/게임모드"),
        Perm(GM_OTHERS, PermissionDefault.OP, "/게임모드 <모드> <플레이어>"),
        Perm(EXP_CHECK, PermissionDefault.TRUE, "/경험치 — 내 경험치 보기"),
        Perm(EXP, PermissionDefault.OP, "/경험치 더하기·설정·빼기·초기화"),
        Perm(EXP_OTHERS, PermissionDefault.OP, "/경험치 <플레이어> — 남의 경험치 보기·바꾸기"),
        Perm(GOD, PermissionDefault.OP, "/무적"),
        Perm(GOD_OTHERS, PermissionDefault.OP, "/무적 <플레이어>"),
        Perm(TGOD, PermissionDefault.OP, "/시간제무적"),
        Perm(TGOD_OTHERS, PermissionDefault.OP, "/시간제무적 <플레이어>"),
        Perm(MAXHP_CHECK, PermissionDefault.TRUE, "/최대체력 — 내 최대 체력 보기"),
        Perm(MAXHP, PermissionDefault.OP, "/최대체력 설정·더하기·빼기·초기화 · 남의 것 보기"),
        Perm(HEAL, PermissionDefault.OP, "/회복"),
        Perm(HEAL_OTHERS, PermissionDefault.OP, "/회복 <플레이어|all>"),
        Perm(FEED, PermissionDefault.OP, "/배고픔"),
        Perm(FEED_OTHERS, PermissionDefault.OP, "/배고픔 <플레이어|all>"),
        Perm(EFFECT, PermissionDefault.OP, "/효과 — 나에게"),
        Perm(EFFECT_OTHERS, PermissionDefault.OP, "/효과 <플레이어|all>"),
        Perm(KILL, PermissionDefault.OP, "/처치"),
        Perm(KILL_FORCED, PermissionDefault.OP, "/처치 -forced — 무적·보호를 무시하고"),
        Perm(SUICIDE, PermissionDefault.TRUE, "/자살"),
        Perm(SUICIDE_OTHERS, PermissionDefault.OP, "/자살 <플레이어>"),
        Perm(SPEED, PermissionDefault.OP, "/속도·/걷기속도·/비행속도"),
        Perm(SPEED_OTHERS, PermissionDefault.OP, "/속도 <플레이어>"),
        *(1..10).map { Perm(SPEED_MAX_PREFIX + it, PermissionDefault.FALSE, "속도 상한 $it(가장 큰 것 · 없으면 걷기 5·비행 10)") }.toTypedArray(),
        Perm(FLY, PermissionDefault.OP, "/비행"),
        Perm(FLY_OTHERS, PermissionDefault.OP, "/비행 <플레이어>"),
        Perm(TFLY, PermissionDefault.OP, "/시간제비행"),
        Perm(TFLY_OTHERS, PermissionDefault.OP, "/시간제비행 <플레이어>"),
        Perm(COLLISION, PermissionDefault.OP, "/충돌"),
        Perm(COLLISION_OTHERS, PermissionDefault.OP, "/충돌 <플레이어>"),
        Perm(TOTEM_TOGGLE, PermissionDefault.TRUE, "/토템 — 가방 속 토템 자동 사용·보스바 켜고 끄기"),
        Perm(TOTEM_INVENTORY, PermissionDefault.TRUE, "가방 속 토템 자동 사용(설정 totem.require-permission 일 때만 본다)"),
        Perm(RIDE_TOGGLE, PermissionDefault.TRUE, "/타기 허용 — 남이 나에게 타는 것 켜고 끄기"),
        Perm(RIDE, PermissionDefault.OP, "/타기 — 바라보는 개체·플레이어에 타기 (종류마다 inmcessentials.player.ride.<종류> 도 필요)"),
        Perm(RIDE_ALL, PermissionDefault.OP, "/타기 — 모든 종류"),
        Perm(RIDE_PREFIX + "player", PermissionDefault.OP, "/타기 — 플레이어"),
        Perm(SHAKE, PermissionDefault.TRUE, "/내리기 — 나에게 탄 것 내리기"),
        Perm(SHAKE_OTHERS, PermissionDefault.OP, "/내리기 <플레이어>"),
        Perm(HAT, PermissionDefault.TRUE, "/모자"),
        Perm(HAT_OTHERS, PermissionDefault.OP, "/모자 <플레이어>"),
        Perm(HAT_INVENTORY, PermissionDefault.TRUE, "가방 화면의 머리 칸에 아무 블록이나 놓기"),
        Perm(SIT, PermissionDefault.TRUE, "/앉기"),
        Perm(SIT_OTHERS, PermissionDefault.OP, "/앉기 <플레이어>"),
        Perm(SIT_CHAIR, PermissionDefault.TRUE, "계단·반 블록(·카펫)을 빈손 우클릭해 앉기"),
        Perm(SIT_PERSISTENT, PermissionDefault.OP, "/앉기 -persistent — 다시 접속해도 앉아 있게"),
        Perm(SIT_LOCATION, PermissionDefault.OP, "/앉기 [플레이어] 좌표 <x> <y> <z> [월드]"),
        Perm(NEAR, PermissionDefault.TRUE, "/근처"),
        Perm(NEAR_EXACT, PermissionDefault.OP, "/근처 — 대략이 아닌 정확한 거리"),
        Perm(PING, PermissionDefault.TRUE, "/핑"),
        Perm(PING_OTHERS, PermissionDefault.OP, "/핑 <플레이어> — 남의 핑(CMI ping.others 처럼 관리자만)"),
        Perm(HPBAR, PermissionDefault.TRUE, "때린 몹·플레이어의 체력 보스바 보기"),
    )

    override val messageDefaults: Map<String, String> = linkedMapOf(
        // --- 공통 ---
        "player-someone" to "관리자",
        "player-on" to "<green>켬</green>",
        "player-off" to "<red>끔</red>",
        "player-until-relog" to "다시 접속할 때까지",
        // --- 게임모드 ---
        "player-mode-survival" to "서바이벌",
        "player-mode-creative" to "크리에이티브",
        "player-mode-adventure" to "어드벤처",
        "player-mode-spectator" to "관전",
        "player-gm-unknown" to "<red>'{값}' 은(는) 모르는 게임모드입니다.</red> <gray>(서바이벌·크리에이티브·어드벤처·관전 · s c a sp · 0~3)</gray>",
        "player-gm-self" to "<green>게임모드를 {값}(으)로 바꿨습니다.</green>",
        "player-gm-done" to "<green>{대상}님의 게임모드를 {값}(으)로 바꿨습니다.</green>",
        "player-gm-changed-by" to "<yellow>{플레이어}님이 당신의 게임모드를 {값}(으)로 바꿨습니다.</yellow>",
        // --- 경험치 ---
        "player-exp-usage" to "<red>/경험치 [플레이어] <더하기|설정|빼기|초기화> <양> [-s]</red> <gray>(10 · 10L 레벨 · 10-50 무작위 · 10% · 10%[5-50])</gray>",
        "player-exp-bad-amount" to "<red>양을 읽지 못했습니다: {값}</red> <gray>(10 · 10L · 10-50 · 10% · 10-20% · 10%L · 10%[5-50])</gray>",
        "player-exp-check" to "<gold>{대상}</gold> <gray>레벨</gray> <white>{레벨}</white> <gray>· 이번 레벨</gray> <white>{경험치}</white> <gray>· 다음 레벨까지</gray> <white>{필요}</white> <gray>· 총</gray> <white>{총}</white>",
        "player-exp-done" to "<green>{대상}님의 경험치 — 레벨 {레벨} (총 {총})</green>",
        "player-exp-changed-by" to "<yellow>{플레이어}님이 당신의 경험치를 바꿨습니다 — 레벨 {레벨} (총 {총})</yellow>",
        // --- 최대 체력 ---
        "player-maxhp-usage" to "<red>/최대체력 <설정|더하기|빼기|초기화> <플레이어> [수치] [-s]</red>",
        "player-maxhp-part-base" to "<white>기본 {값}</white>",
        "player-maxhp-part" to "<gray>+</gray> <white>{이름} {값}</white>",
        "player-maxhp-part-scalar" to "<gray>×</gray> <white>{이름} {값}</white>",
        "player-maxhp-group-gear" to "장비",
        "player-maxhp-group-set" to "세트·부적",
        "player-maxhp-group-title" to "칭호",
        "player-maxhp-group-dungeon" to "던전",
        "player-maxhp-group-other" to "기타",
        "player-maxhp-view" to "<gold>{대상}</gold> <gray>최대 체력:</gray> {값} <gray>=</gray> <green>{금액}</green> <gray>(지금 체력 {체력})</gray>",
        "player-maxhp-done" to "<green>{대상}님의 기본 최대 체력을 {값}(으)로 바꿨습니다.</green> <gray>(장비 등 포함 최종 {금액})</gray>",
        "player-maxhp-capped" to "<yellow>상한 {값}에 맞췄습니다.</yellow>",
        "player-maxhp-changed-by" to "<yellow>{플레이어}님이 당신의 기본 최대 체력을 {값}(으)로 바꿨습니다.</yellow> <gray>(최종 {금액})</gray>",
        // --- 회복 · 배고픔 ---
        "player-heal-self" to "<green>회복했습니다.</green>",
        "player-heal-done" to "<green>{대상}님을 회복했습니다.</green>",
        "player-heal-multi" to "<green>{개수}명을 회복했습니다.</green>",
        "player-healed-by" to "<green>{플레이어}님이 당신을 회복했습니다.</green>",
        "player-feed-self" to "<green>배를 채웠습니다.</green>",
        "player-feed-done" to "<green>{대상}님의 배를 채웠습니다.</green>",
        "player-feed-multi" to "<green>{개수}명의 배를 채웠습니다.</green>",
        "player-fed-by" to "<green>{플레이어}님이 당신의 배를 채웠습니다.</green>",
        "player-hunger-set" to "<green>{대상}님의 배고픔을 {값}(으)로 정했습니다.</green>",
        "player-hunger-set-by" to "<yellow>{플레이어}님이 당신의 배고픔을 {값}(으)로 정했습니다.</yellow>",
        // --- 효과 ---
        "player-effect-unknown" to "<red>'{값}' 은(는) 모르는 효과입니다.</red>",
        "player-effect-infinite" to "무한",
        "player-effect-done" to "<green>{대상}님에게 {이름} 효과를 줬습니다.</green> <gray>({값}초 · 세기 {개수})</gray>",
        "player-effect-multi" to "<green>{개수}명에게 {이름} 효과를 줬습니다.</green>",
        "player-effect-given-by" to "<yellow>{플레이어}님이 당신에게 {이름} 효과를 줬습니다.</yellow> <gray>({값}초 · 세기 {개수})</gray>",
        "player-effect-removed" to "<gray>{대상}님의 {이름} 효과를 지웠습니다.</gray>",
        "player-effect-cleared" to "<green>{대상}의 효과를 모두 지웠습니다.</green>",
        "player-effect-cleared-by" to "<yellow>{플레이어}님이 당신의 효과를 모두 지웠습니다.</yellow>",
        // --- 처치 · 자살 ---
        "player-kill-done" to "<green>{대상}님을 처치했습니다.</green>",
        "player-kill-protected" to "<red>{대상}님을 처치하지 못했습니다 — 무적·게임모드·다른 플러그인의 보호.</red> <gray>-forced 를 붙이세요.</gray>",
        "player-kill-no-force" to "<red>-forced 를 쓸 권한이 없습니다.</red>",
        "player-kill-unknown-cause" to "<red>'{값}' 은(는) 모르는 피해 원인입니다.</red>",
        "player-suicide-totem" to "<red>불사의 토템을 든 채로는 쓸 수 없습니다.</red>",
        "player-suicide-self" to "<gray>잘 있어라, 잔인한 세상아…</gray>",
        "player-suicide-broadcast" to "<gray>{플레이어}님이 스스로 목숨을 끊었습니다.</gray>",
        // --- 핑 · 근처 ---
        "player-ping-self" to "<gray>내 핑:</gray> <white>{값}ms</white>",
        "player-ping-other" to "<gray>{대상}님의 핑:</gray> <white>{값}ms</white>",
        "player-near-none" to "<gray>{값}블록 안에 아무도 없습니다.</gray>",
        "player-near-capped" to "<yellow>최대 {값}블록까지 봅니다.</yellow>",
        "player-near-header" to "<gold>{값}블록 안 {개수}명</gold>",
        "player-near-line" to "<gray>{개수}.</gray> <white>{플레이어}</white> <gray>- {값}m</gray>",
        "player-near-line-approx" to "<gray>{개수}.</gray> <white>{플레이어}</white> <gray>- {방향} ~{값}m</gray>",
        "player-near-line-point" to "<gray>{개수}.</gray> <click:run_command:'/근처 가리키기 {플레이어}'><hover:show_text:'<gray>눌러서 방향 보기'><white>{플레이어}</white></hover></click> <gray>- {값}m</gray>",
        "player-near-line-point-approx" to "<gray>{개수}.</gray> <click:run_command:'/근처 가리키기 {플레이어}'><hover:show_text:'<gray>눌러서 방향 보기'><white>{플레이어}</white></hover></click> <gray>- {방향} ~{값}m</gray>",
        "player-near-line-hidden" to "<gray>{개수}.</gray> <white>{플레이어}</white> <gray>- {값}m</gray>",
        "player-near-line-hidden-approx" to "<gray>{개수}.</gray> <white>{플레이어}</white> <gray>- {방향} ~{값}m</gray>",
        "player-near-point" to "<gray>{대상}님 쪽을 가리킵니다.</gray>",
        "player-near-point-far" to "<red>{대상}님이 너무 멀리 있습니다.</red>",
        "player-dir-0" to "북",
        "player-dir-1" to "북동",
        "player-dir-2" to "동",
        "player-dir-3" to "남동",
        "player-dir-4" to "남",
        "player-dir-5" to "남서",
        "player-dir-6" to "서",
        "player-dir-7" to "북서",
        // --- 무적 · 비행 ---
        "player-god-done" to "<green>{대상}님의 무적: {값}</green>",
        "player-god-changed-by" to "<yellow>{플레이어}님이 당신의 무적을 {값}(으)로 바꿨습니다.</yellow>",
        "player-fly-done" to "<green>{대상}님의 비행: {값}</green>",
        "player-fly-changed-by" to "<yellow>{플레이어}님이 당신의 비행을 {값}(으)로 바꿨습니다.</yellow>",
        // --- 시간제 무적 ---
        "player-tgod-not-set" to "<gray>{대상}님은 시간제 무적이 아닙니다.</gray>",
        "player-tgod-until-relog" to "<green>{대상}님은 다시 접속할 때까지 무적입니다.</green>",
        "player-tgod-left" to "<green>{대상}님의 시간제 무적: {시간} 남음</green>",
        "player-tgod-set" to "<green>{대상}님에게 {시간} 동안 무적을 줬습니다.</green>",
        "player-tgod-set-relog" to "<green>{대상}님에게 다시 접속할 때까지 무적을 줬습니다.</green>",
        "player-tgod-added" to "<green>{대상}님의 무적 시간을 {더한시간} 늘렸습니다.</green> <gray>({시간} 남음)</gray>",
        "player-tgod-taken" to "<green>{대상}님의 무적 시간을 {더한시간} 줄였습니다.</green> <gray>({시간} 남음)</gray>",
        "player-tgod-ended-by-command" to "<gray>{대상}님의 시간제 무적을 끝냈습니다.</gray>",
        "player-tgod-relog-no-math" to "<yellow>{대상}님의 무적은 다시 접속할 때까지라 더하거나 뺄 수 없습니다.</yellow> <gray>(시간을 그냥 쓰면 그 시간으로 바뀝니다)</gray>",
        "player-tgod-got" to "<yellow>{플레이어}님이 {시간} 동안 무적을 줬습니다.</yellow>",
        "player-tgod-got-relog" to "<yellow>{플레이어}님이 다시 접속할 때까지 무적을 줬습니다.</yellow>",
        "player-tgod-got-added" to "<yellow>무적 시간이 {더한시간} 늘었습니다.</yellow> <gray>({시간} 남음)</gray>",
        "player-tgod-got-taken" to "<yellow>무적 시간이 {더한시간} 줄었습니다.</yellow> <gray>({시간} 남음)</gray>",
        "player-tgod-ended" to "<gray>시간제 무적이 끝났습니다.</gray>",
        "player-tgod-warn" to "<yellow>무적이 {시간} 뒤에 끝납니다.</yellow>",
        "player-tgod-expired" to "<red>시간제 무적이 끝났습니다!</red>",
        // --- 시간제 비행 ---
        "player-tfly-not-set" to "<gray>{대상}님은 시간제 비행이 아닙니다.</gray>",
        "player-tfly-until-relog" to "<green>{대상}님은 다시 접속할 때까지 날 수 있습니다.</green>",
        "player-tfly-left" to "<green>{대상}님의 시간제 비행: {시간} 남음</green>",
        "player-tfly-set" to "<green>{대상}님에게 {시간} 동안 비행을 줬습니다.</green>",
        "player-tfly-set-relog" to "<green>{대상}님에게 다시 접속할 때까지 비행을 줬습니다.</green>",
        "player-tfly-added" to "<green>{대상}님의 비행 시간을 {더한시간} 늘렸습니다.</green> <gray>({시간} 남음)</gray>",
        "player-tfly-taken" to "<green>{대상}님의 비행 시간을 {더한시간} 줄였습니다.</green> <gray>({시간} 남음)</gray>",
        "player-tfly-ended-by-command" to "<gray>{대상}님의 시간제 비행을 끝냈습니다.</gray>",
        "player-tfly-relog-no-math" to "<yellow>{대상}님의 비행은 다시 접속할 때까지라 더하거나 뺄 수 없습니다.</yellow> <gray>(시간을 그냥 쓰면 그 시간으로 바뀝니다)</gray>",
        "player-tfly-got" to "<yellow>{플레이어}님이 {시간} 동안 비행을 켜 줬습니다.</yellow>",
        "player-tfly-got-relog" to "<yellow>{플레이어}님이 다시 접속할 때까지 비행을 켜 줬습니다.</yellow>",
        "player-tfly-got-added" to "<yellow>비행 시간이 {더한시간} 늘었습니다.</yellow> <gray>({시간} 남음)</gray>",
        "player-tfly-got-taken" to "<yellow>비행 시간이 {더한시간} 줄었습니다.</yellow> <gray>({시간} 남음)</gray>",
        "player-tfly-ended" to "<gray>시간제 비행이 끝났습니다.</gray>",
        "player-tfly-warn" to "<yellow>비행이 {시간} 뒤에 끝납니다.</yellow>",
        "player-tfly-expired" to "<red>시간제 비행이 끝났습니다!</red> <gray>(이번 착지는 떨어지는 피해가 없습니다)</gray>",
        // --- 속도 · 충돌 ---
        "player-walkspeed-now" to "<gray>{대상}님의 걷기 속도:</gray> <white>{값}</white>",
        "player-flyspeed-now" to "<gray>{대상}님의 비행 속도:</gray> <white>{값}</white>",
        "player-speed-max" to "<red>쓸 수 있는 최대 속도는 {값}입니다.</red>",
        "player-walkspeed-done" to "<green>{대상}님의 걷기 속도를 {값}(으)로 바꿨습니다.</green>",
        "player-flyspeed-done" to "<green>{대상}님의 비행 속도를 {값}(으)로 바꿨습니다.</green>",
        "player-walkspeed-changed-by" to "<yellow>{플레이어}님이 당신의 걷기 속도를 {값}(으)로 바꿨습니다.</yellow>",
        "player-flyspeed-changed-by" to "<yellow>{플레이어}님이 당신의 비행 속도를 {값}(으)로 바꿨습니다.</yellow>",
        "player-collision-done" to "<green>{대상}님의 충돌(밀기): {값}</green>",
        "player-collision-changed-by" to "<yellow>{플레이어}님이 당신의 충돌(밀기)을 {값}(으)로 바꿨습니다.</yellow>",
        // --- 토템 ---
        "player-totem-inventory" to "<green>가방 속 토템 자동 사용: {값}</green>",
        "player-totem-bossbar" to "<green>토템 보스바: {값}</green>",
        "player-totem-cooldown-denied" to "<red>토템 쿨타임 중이라 쓰이지 않았습니다.</red> <gray>({시간} 남음)</gray>",
        "player-totem-void" to "<yellow>허공에 떨어져 토템이 당신을 구했습니다.</yellow>",
        "player-totem-bar-warmup" to "<green>토템 보호: {시간}</green>",
        "player-totem-bar-cooldown" to "<yellow>토템 쿨타임: {시간}</yellow>",
        // --- 타기 · 내리기 ---
        "player-ride-nothing" to "<red>바라보는 곳에 탈 것이 없습니다.</red>",
        "player-ride-too-far" to "<red>{대상}님이 너무 멀리 있습니다.</red> <gray>({값}블록 안)</gray>",
        "player-ride-cant" to "<red>{이름}에는 탈 수 없습니다.</red>",
        "player-ride-refused" to "<red>{대상}님은 남이 타는 것을 꺼 두었습니다.</red>",
        "player-ride-occupied" to "<red>이미 누군가 타고 있습니다.</red>",
        "player-ride-done" to "<green>{이름}에 탔습니다.</green>",
        "player-ride-on-you" to "<yellow>{플레이어}님이 당신 위에 탔습니다.</yellow> <gray>/내리기 로 떨칠 수 있습니다.</gray>",
        "player-ride-list" to "<gold>탈 수 있는 것 {개수}종:</gold> <gray>{값}</gray>",
        "player-ride-list-none" to "<gray>탈 수 있는 종류가 없습니다.</gray>",
        "player-ride-me-on" to "<green>이제 남이 당신에게 탈 수 있습니다.</green>",
        "player-ride-me-off" to "<yellow>이제 남이 당신에게 탈 수 없습니다.</yellow>",
        "player-shake-nothing" to "<gray>떨칠 것이 없습니다.</gray>",
        "player-shake-done" to "<green>{대상}님에게서 떨쳤습니다:</green> <gray>{값}</gray>",
        "player-shake-thrown" to "<yellow>{대상}님에게서 떨어졌습니다.</yellow>",
        // --- 모자 ---
        "player-hat-empty-hand" to "<red>손에 든 아이템이 없습니다.</red>",
        "player-hat-cant" to "<red>이 아이템은 모자로 쓸 수 없습니다.</red>",
        "player-hat-incorrect" to "<red>머리에 쓴 것이 귀속 저주에 걸려 벗길 수 없습니다 — 모자를 바꾸지 못했습니다.</red>",
        "player-hat-full" to "<red>{대상}님의 가방이 가득 차 모자를 바꿀 수 없습니다.</red>",
        "player-hat-done" to "<green>모자를 바꿨습니다!</green>",
        "player-hat-other-done" to "<green>{대상}님의 모자를 바꿨습니다.</green>",
        "player-hat-changed-by" to "<yellow>{플레이어}님이 당신의 모자를 바꿨습니다!</yellow>",
        // --- 앉기 ---
        "player-sit-on" to "<gray>앉았습니다.</gray> <dark_gray>(웅크리면 일어납니다)</dark_gray>",
        "player-sit-off" to "<gray>일어났습니다.</gray>",
        "player-sit-occupied" to "<red>누군가 이미 앉아 있습니다!</red>",
        "player-sit-not-ground" to "<red>땅 위에서만 앉을 수 있습니다.</red>",
        "player-sit-bad-location" to "<red>/앉기 [플레이어] 좌표 <x> <y> <z> [월드]</red> <gray>(~ 는 지금 자리)</gray>",
        "player-sit-by" to "<yellow>{플레이어}님이 당신을 앉혔습니다.</yellow>",
        "player-sit-restored" to "<gray>다시 접속해 앉은 자리로 돌아왔습니다.</gray>",
        // --- 체력 보스바 ---
        "player-hpbar" to "<white>{이름}</white> <green>{값}</green><gray>/{금액}</gray> <red>(-{피해})</red>",
        "player-hpbar-player" to "<white>{이름}</white> <green>{값}{흡수}</green><gray>/{금액}</gray> <red>(-{피해})</red>",
    )

    @Volatile
    var config: PlayerConfig = PlayerConfig()
        private set

    internal lateinit var states: PlayerStates
    internal lateinit var seats: Seats
    internal lateinit var totems: Totems
    internal lateinit var hpBars: HpBars
    private lateinit var cmd: Cmds
    internal lateinit var listener: PlayerListener
    internal lateinit var funCommands: FunCommands
    private var papi: PlayerPapi? = null

    /** 우리가 끼운 `ess.abilities` — 뗄 때 우리 것만. */
    private var abilities: Abilities? = null

    /** 시간제 비행이 끝나 떨어지는 중인 사람 → 이 시각까지 낙하 피해 한 번 막기. 메인. */
    private val landing = HashMap<UUID, Long>()

    private var flushCounter = 0

    /** `/처치 -forced` 로 지금 죽이는 중인 사람 — 무적·토템·다른 플러그인의 취소를 넘는다(그 피해 한 번 동안만). 메인. */
    internal val forcedKill = HashSet<UUID>()

    /** 잠수였던 사람 — 잠수에서 나오면 저장된 충돌(밀기)을 다시 건다(잠수 기능이 밀기 막기로 껐다 되돌린 값이 `/충돌` 과 어긋나지 않게). 메인. */
    private val afkSeen = HashSet<UUID>()

    override fun schema(sql: SqlSource) {
        PlayerStates.schema(sql)
    }

    override fun load(settings: YamlConfiguration) {
        config = PlayerConfig.from(settings)
    }

    override fun enable() {
        states = PlayerStates(ess)
        seats = Seats(ess) { config }
        totems = Totems(ess, this)
        hpBars = HpBars(ess) { config }
        cmd = Cmds(ess)
        listener = PlayerListener(ess, this)
        ess.listen(listener)
        ess.listen(totems)
        ess.listen(hpBars)
        StatusCommands(ess, this, cmd).register()
        AbilityCommands(ess, this, cmd).register()
        funCommands = FunCommands(ess, this, cmd).also { it.register() }
        registerSettings()
        ess.ticks.add("player", 1) { now -> tick(now) }
        abilities = object : Abilities {
            override fun isGod(player: Player): Boolean = this@PlayerFeature.isGod(player)
            override fun setGod(player: Player, on: Boolean) = this@PlayerFeature.setGod(player, on)
            override fun wantsFlight(id: UUID): Boolean = this@PlayerFeature.wantsFlight(id)
            override fun timedGodLeft(player: Player): Long? = if (::states.isInitialized) states.get(player.uniqueId).tgodLeft else null
            override fun timedFlightLeft(player: Player): Long? = if (::states.isInitialized) states.get(player.uniqueId).tflyLeft else null
        }.also { ess.abilities = it }
    }

    override fun ready() {
        states.loadOnline { id, record -> Bukkit.getPlayer(id)?.let { applyRecord(it, record) } }
        // `%inmcplayer_…%` — PlaceholderAPI 가 켜져 있을 때만(이 파일 밖의 클래스가 PAPI 클래스를 만진다).
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) papi = PlayerPapi(ess, this).also { it.setup() }
    }

    override fun disable() {
        papi?.teardown()
        papi = null
        if (abilities != null && ess.abilities === abilities) ess.abilities = Abilities.NONE
        abilities = null
        if (::states.isInitialized) states.flushAll()
        if (::seats.isInitialized) seats.clear()
        if (::totems.isInitialized) totems.clear()
        if (::hpBars.isInitialized) hpBars.clear()
        PlayerSettings.unregisterAll(SETTINGS_OWNER)
    }

    /** 개인 설정(플레이어 메뉴 `/설정` 이 그린다) — 남이 나에게 타기 · 가방 속 토템 · 토템 보스바. */
    private fun registerSettings() {
        PlayerSettings.register(
            PlayerSettings.Setting(SETTING_RIDE_ME, SETTINGS_OWNER, "남이 나에게 타기", Material.SADDLE,
                listOf("켜 두면 /타기 로 다른 사람이 내 위에 탈 수 있습니다.", "/내리기 로 언제든 떨칠 수 있습니다."), PlayerSettings.Toggle(true)),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(SETTING_TOTEM_INVENTORY, SETTINGS_OWNER, "가방 속 토템 자동 사용", Material.TOTEM_OF_UNDYING,
                listOf("손에 토템이 없어도 가방의 토템이 대신 터집니다.", "(서버가 켜 둔 경우에만)"), PlayerSettings.Toggle(true)),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(SETTING_TOTEM_BOSSBAR, SETTINGS_OWNER, "토템 보스바", Material.GOLD_NUGGET,
                listOf("토템 보호·쿨타임이 남은 시간을 보스바로 보여 줍니다."), PlayerSettings.Toggle(true)),
        )
    }

    // --- 상태 ---------------------------------------------------------------------------------------

    fun isGod(player: Player): Boolean = ::states.isInitialized && states.isGod(player.uniqueId)

    /** `/비행` 으로 켰거나 시간제 비행이 남았다. */
    fun wantsFlight(id: UUID): Boolean = ::states.isInitialized && states.get(id).let { it.fly || it.tflyLeft != null }

    /**
     * `/무적` 켜기·끄기. **끄면 시간제 무적도 끝낸다** — [isGod] 은 둘 중 하나만 있어도 참이라, 월드 제한(world 기능이 `/무적 <이름> 끄기 -s`
     * 로 끈다)이 시간제 무적을 못 끄면 막힌 월드에서도 무적이 남는다(2026-10-10 리뷰).
     */
    fun setGod(player: Player, on: Boolean) {
        states.update(player.uniqueId) { it.copy(god = on, tgodLeft = if (on) it.tgodLeft else null) }
        if (on) calmDown(player)
    }

    /** 무적을 켤 때 — 불 끄기(CMI 와 같다). */
    fun calmDown(player: Player) {
        player.fireTicks = 0
    }

    fun setFlight(player: Player, on: Boolean) {
        player.allowFlight = on
        if (!on) player.isFlying = false
    }

    /** 시간제 무적([god])·비행을 [left] 초로(null = 끝, [PlayerRecord.UNTIL_RELOG] = 다시 접속할 때까지). */
    fun setTimed(player: Player, god: Boolean, left: Long?) {
        val before = states.get(player.uniqueId)
        states.update(player.uniqueId) { if (god) it.copy(tgodLeft = left) else it.copy(tflyLeft = left) }
        if (!god) {
            if (left != null) setFlight(player, true)
            else if (!before.fly && player.gameMode in PlayerListener.SURVIVAL_LIKE) endFlight(player)
        }
    }

    /** 시간제 비행이 끝남 — 날던 중이면 이번 착지의 낙하 피해를 막는다(설정). */
    private fun endFlight(player: Player) {
        val wasFlying = player.isFlying
        setFlight(player, false)
        if (wasFlying && config.tflyLandingProtection) landing[player.uniqueId] = System.currentTimeMillis() + 60_000L
    }

    fun consumeLanding(id: UUID): Boolean {
        val until = landing.remove(id) ?: return false
        return until > System.currentTimeMillis()
    }

    fun forgetLanding(id: UUID) {
        landing.remove(id)
    }

    fun isForcedKill(id: UUID): Boolean = id in forcedKill

    /**
     * `/처치 -forced` — 고른 피해 원인([source])으로 죽인다. 그 피해 한 번 동안 무적·토템을 무시하고 다른 플러그인의 취소도 푼다([PlayerListener.onForcedKill]
     * HIGHEST) — 사망 메시지가 그 원인으로 남는다(2026-10-10 리뷰: 체력 0 으로 바로 죽이면 원인이 사라졌다). 그래도 살아 있으면(창작 모드처럼 피해 사건이
     * 아예 없는 경우) 체력 0.
     */
    fun forceKill(target: Player, source: org.bukkit.damage.DamageSource) {
        forcedKill += target.uniqueId
        try {
            target.damage(1.0e9, source)
        } finally {
            forcedKill -= target.uniqueId
        }
        if (!target.isDead && target.health > 0.0) target.health = 0.0
    }

    /** 접속 뒤 — 저장된 비행·충돌을 몸에 건다. */
    fun applyRecord(player: Player, record: PlayerRecord) {
        if ((record.fly || record.tflyLeft != null) && player.gameMode in PlayerListener.SURVIVAL_LIKE) player.allowFlight = true
        if (!record.collision) player.isCollidable = false
    }

    // --- 최대 체력 · 회복 ----------------------------------------------------------------------------

    /**
     * 기본값만 바꾼다(research '# maxhp'). 돌려주는 것: (새 기본값, 새 최종값, 상한에 걸렸나). 바꾼 뒤 체력을 맞추고 `saveData()` 로 바로 저장한다.
     */
    fun applyMaxHp(player: Player, op: MaxHpMath.Op, amount: Double, cap: Double, save: Boolean = true): Triple<Double, Double, Boolean> {
        val attr = player.getAttribute(Attribute.MAX_HEALTH) ?: return Triple(0.0, 0.0, false)
        val oldMax = attr.value
        val raw = when (op) {
            MaxHpMath.Op.SET -> amount
            MaxHpMath.Op.ADD -> attr.baseValue + amount
            MaxHpMath.Op.TAKE -> attr.baseValue - amount
            MaxHpMath.Op.CLEAR -> attr.defaultValue
        }
        val base = MaxHpMath.newBase(op, attr.baseValue, amount, attr.defaultValue, cap)
        attr.baseValue = base
        val newMax = attr.value
        if (!player.isDead) player.health = MaxHpMath.healthAfter(player.health, oldMax, newMax).coerceAtLeast(if (player.health > 0) 0.5 else 0.0)
        if (save) runCatching { player.saveData() }
        return Triple(base, newMax, raw > cap)
    }

    /** 최종 최대 체력까지(또는 [amount]·[percent]) 채운다. 배고픔·나쁜 효과·불까지(깃발로 끔). */
    fun heal(player: Player, amount: Double?, percent: Boolean, feed: Boolean, cleanse: Boolean, extinguish: Boolean) {
        val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        val target = when {
            amount == null -> max
            percent -> player.health + max * amount / 100.0
            else -> player.health + amount
        }
        player.health = target.coerceIn(0.0, max)
        if (feed) {
            player.foodLevel = maxOf(player.foodLevel, 20)
            player.saturation = 20f
            player.exhaustion = 0f
        }
        if (cleanse && config.healRemoveNegative) {
            val bad = config.healNegative.toSet()
            for (effect in player.activePotionEffects) if (effect.type.key.key in bad) player.removePotionEffect(effect.type)
        }
        if (extinguish) player.fireTicks = 0
    }

    /** 모자 규칙 판단(재질 정보는 여기서 — 순수 규칙은 [HatRule]). */
    fun hatVerdict(stack: ItemStack): HatRule.Verdict {
        val meta = stack.itemMeta
        return HatRule.check(
            HatRule.Item(
                material = stack.type.key.key,
                headwear = FunCommands.isHeadwear(stack),
                block = stack.type.isBlock,
                mobHead = stack.type in FunCommands.MOB_HEADS,
                hasLore = meta?.hasLore() == true,
                enchanted = stack.enchantments.isNotEmpty(),
            ),
            config,
        )
    }

    // --- 1초 ----------------------------------------------------------------------------------------

    private fun tick(now: Long) {
        states.sweep(now)
        seats.sweep()
        totems.tick(now)
        hpBars.tick(now)
        landing.entries.removeIf { it.value <= now }
        reapplyCollisionAfterAfk()
        // 남은 초를 DB 에 적는 주기 — 1분, 공용 DB 면 5초(서버를 옮길 때 새 서버가 읽는 값이 덜 묵게 · 2026-10-10 리뷰).
        val flush = ++flushCounter >= (if (states.sharedDb) SHARED_FLUSH_SECONDS else LOCAL_FLUSH_SECONDS)
        if (flush) flushCounter = 0
        for ((id, record) in states.online().toMap()) {
            if (record.tgodLeft == null && record.tflyLeft == null) continue
            val player = Bukkit.getPlayer(id) ?: continue
            tickTimed(player, record, flush && !states.isAwaitingRecheck(id))
        }
    }

    /** 한 사람의 시간제 무적·비행을 1초 줄인다. 끝나면 비행을 끄고(착지 보호) 알린다. [flush] 면 줄인 값을 DB 에도. 검증기도 이것을 부른다. */
    internal fun tickTimed(player: Player, record: PlayerRecord, flush: Boolean) {
        val id = player.uniqueId
        var next = record
        record.tgodLeft?.takeIf { it > 0 }?.let { left -> next = next.copy(tgodLeft = countDown(player, left, "player-tgod-warn")) }
        record.tflyLeft?.takeIf { it > 0 }?.let { left -> next = next.copy(tflyLeft = countDown(player, left, "player-tfly-warn")) }
        if (next == record) return
        states.putMemory(id, next)
        if (record.tgodLeft != null && next.tgodLeft == null) {
            ess.messages.send(player, "player-tgod-expired")
            states.persist(id, next)
        }
        if (record.tflyLeft != null && next.tflyLeft == null) {
            if (!next.fly && player.gameMode in PlayerListener.SURVIVAL_LIKE) endFlight(player)
            ess.messages.send(player, "player-tfly-expired")
            states.persist(id, next)
        } else if (flush) {
            states.persist(id, next)
        }
    }

    /** 잠수에서 나온 사람에게 저장된 충돌 값을 다시 건다(`ess.afk` 접점만 본다 — afk 패키지를 모른다). */
    private fun reapplyCollisionAfterAfk() {
        afkSeen.removeIf { Bukkit.getPlayer(it) == null }
        for (player in Bukkit.getOnlinePlayers()) {
            val id = player.uniqueId
            if (ess.afk.isAfk(player)) {
                afkSeen += id
                continue
            }
            if (!afkSeen.remove(id)) continue
            val want = states.get(id).collision
            if (player.isCollidable != want) player.isCollidable = want
        }
    }

    /** 1초 줄이고, 알릴 때면 알린다. 끝나면 null. */
    private fun countDown(player: Player, left: Long, warnKey: String): Long? {
        val next = left - 1
        if (next <= 0) return null
        if (next in config.timedWarnAt) ess.messages.sendRaw(player, ess.messages.raw(warnKey), Ph.of().time(Durations.formatShort(next)))
        return next
    }

    // --- CMI 가져오기 --------------------------------------------------------------------------------

    /** 접속 안 한 사람 몫 하나 — 한 DB 일로 묶어 넘긴다. [column] = `tfly_left`·`tgod_left`(남은 초 [seconds]) · `fly` · `collision`. */
    private class OfflineImport(val id: UUID, val name: String, val kind: String, val column: String, val seconds: Long = 0)

    /**
     * CMI 의 player 몫을 넣는다 — 시간제 비행·무적의 남은 초(`CmiUser.tfly`·`tgod`) · `/fly` 켜 둠(`users.Flying`) · 밀기 끔(`users.Collision`) ·
     * 개인 설정(`users.Options` 의 rideMe·totemBossBar → PlayerSettings). 우리 쪽에 이미 값(남은 시간·켜 둔 것)이 있으면 그대로 둔다(두 번 돌려도
     * 겹치지 않는다). 음수·0 남은 시간은 끝난 것으로 보고 건너뛴다. 접속 중인 사람은 메모리에 바로, 아닌 사람은 DB 쓰기 줄에 한 일로 넘긴다
     * (가져오기는 메인에서 불리고 보고서는 곧바로 파일이 된다 — 실제로 넣은 수·이미 있어 둔 수는 서버 로그 "CMI 플레이어 상태" 줄).
     */
    override fun importCmi(data: CmiData, report: ImportReport) {
        val offline = ArrayList<OfflineImport>()
        // CmiReader 는 "0"·빈 값을 extra 에 넣지 않는다 — 누군가에게 Collision 이 보이면 그 열이 있고, 안 보이는 사람은 0(끔)이다.
        val hasCollision = data.users.any { "Collision" in it.extra }
        for (user in data.users) {
            importTimed(user, "시간제 비행", "tfly_left", user.tfly, report, offline)
            importTimed(user, "시간제 무적", "tgod_left", user.tgod, report, offline)
            if (user.extra["Flying"]?.let(::truthy) == true) importFlag(user, "비행 켜 둠", "fly", report, offline)
            if (hasCollision && user.extra["Collision"]?.let(::truthy) != true) importFlag(user, "밀기(충돌) 끔", "collision", report, offline)
            user.extra["Options"]?.let { importOptions(user, it, report) }
        }
        if (!hasCollision && data.users.isNotEmpty()) report.note("CMI users.Collision 값이 한 사람에게도 없어 밀기(충돌) 설정은 옮기지 않았습니다(모두 기본값 켬으로 둠)")
        if (offline.isNotEmpty()) queueOffline(offline, report)
    }

    private fun truthy(text: String): Boolean = text.trim().lowercase() in setOf("1", "true", "yes")

    /** 접속해 있고 저장값을 읽어 둔 사람 — 메모리에 바로 넣는다. 아니면 DB 로. */
    private fun loadedOnline(id: UUID): Player? = Bukkit.getPlayer(id)?.takeIf { ::states.isInitialized && states.isLoaded(id) }

    private fun importTimed(user: CmiUser, kind: String, column: String, seconds: Long?, report: ImportReport, offline: MutableList<OfflineImport>) {
        seconds ?: return
        report.read(kind)
        if (seconds <= 0) return report.skipped(kind, "${user.name}: 남은 시간 없음($seconds)")
        if (seconds > 10L * 365 * 86400) return report.unreadable(kind, "${user.name}: 남은 초가 이상합니다($seconds)")
        val online = loadedOnline(user.id)
        if (online == null) {
            offline += OfflineImport(user.id, user.name, kind, column, seconds)
            return
        }
        val record = states.get(user.id)
        val existing = if (column == "tfly_left") record.tflyLeft else record.tgodLeft
        if (existing != null) return report.skipped(kind, "${user.name}: 이미 남은 시간이 있음")
        setTimed(online, column == "tgod_left", seconds)
        report.imported(kind)
    }

    private fun importFlag(user: CmiUser, kind: String, column: String, report: ImportReport, offline: MutableList<OfflineImport>) {
        report.read(kind)
        val online = loadedOnline(user.id)
        if (online == null) {
            offline += OfflineImport(user.id, user.name, kind, column)
            return
        }
        val record = states.get(user.id)
        if (if (column == "fly") record.fly else !record.collision) return report.skipped(kind, "${user.name}: 이미 같음")
        if (column == "fly") {
            states.update(user.id) { it.copy(fly = true) }
            if (online.gameMode in PlayerListener.SURVIVAL_LIKE) setFlight(online, true)
        } else {
            states.update(user.id) { it.copy(collision = false) }
            online.isCollidable = false
        }
        report.imported(kind)
    }

    /**
     * CMI `users.Options`(개인 설정) — `이름:값` 을 `;`·`,` 로 이은 것으로 읽는다. rideMe·totemBossBar 는 우리 개인 설정으로, 옮길 곳이 없는 것은
     * 건너뜀(까닭과 함께), 값을 알아볼 수 없는 것은 못 읽음(원문과 함께)으로 남긴다.
     */
    private fun importOptions(user: CmiUser, raw: String, report: ImportReport) {
        val kind = "개인 설정"
        for (token in raw.split(';', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }) {
            report.read(kind)
            val parts = token.split(':', '=', limit = 2)
            val key = parts[0].trim()
            val value = when (parts.getOrNull(1)?.trim()?.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            val ours = when (key.lowercase()) {
                "rideme" -> SETTING_RIDE_ME
                "totembossbar" -> SETTING_TOTEM_BOSSBAR
                else -> null
            }
            when {
                value == null -> report.unreadable(kind, "${user.name}: CMI Options 의 '$token' 을 읽지 못했습니다 — 원문: ${raw.take(200)}")
                ours == null -> report.skipped(kind, "${user.name}: CMI 개인 설정 '$key'($value) 는 player 기능에 옮길 곳이 없습니다")
                PlayerSettings.set(user.id, ours, value) -> report.imported(kind)
                else -> report.unreadable(kind, "${user.name}: 개인 설정 $ours 에 넣지 못했습니다(등록 안 됨) — '$token'")
            }
        }
    }

    private fun queueOffline(rows: List<OfflineImport>, report: ImportReport) {
        for (r in rows) report.imported("${r.kind}(접속 안 한 사람 — 비었을 때만 넣기로 DB 에 넘김)")
        report.note(
            "접속하지 않은 사람의 시간제 비행·무적·비행·밀기 ${rows.size}건은 우리 쪽 값이 비었을(기본값일) 때만 넣도록 DB 쓰기 줄에 넘겼습니다 — " +
                "실제로 넣은 수와 이미 값이 있어 둔 수는 서버 로그 \"CMI 플레이어 상태\" 줄에 남습니다",
        )
        ess.db.run("CMI 플레이어 상태") {
            var put = 0
            val kept = ArrayList<String>()
            for (r in rows) {
                val ok = try {
                    if (r.column.endsWith("_left")) PlayerStates.importTimed(ess.db.sql, r.id, r.column, r.seconds) else PlayerStates.importFlag(ess.db.sql, r.id, r.column)
                } catch (t: Throwable) {
                    ess.logger.log(java.util.logging.Level.WARNING, "CMI 플레이어 상태 — ${r.name}(${r.kind}) 넣기 실패", t)
                    continue
                }
                if (!ok) {
                    kept += "${r.name}(${r.kind})"
                    continue
                }
                put++
                // 가져오는 사이 접속했으면(읽어 둔 뒤·접속한 뒤) 메모리에도 — 나갈 때 빈 칸으로 덮어쓰지 않게.
                Bukkit.getScheduler().runTask(ess.plugin, Runnable { adoptOffline(r) })
            }
            ess.logger.info("CMI 플레이어 상태 — 넣음 $put · 이미 값이 있어 둠 ${kept.size}" + if (kept.isEmpty()) "" else ": ${kept.joinToString(", ")}")
        }
    }

    private fun adoptOffline(r: OfflineImport) {
        if (!::states.isInitialized) return
        val next = states.adoptImported(r.id) { rec ->
            when (r.column) {
                "tfly_left" -> if (rec.tflyLeft == null) rec.copy(tflyLeft = r.seconds) else rec
                "tgod_left" -> if (rec.tgodLeft == null) rec.copy(tgodLeft = r.seconds) else rec
                "fly" -> rec.copy(fly = true)
                else -> rec.copy(collision = false)
            }
        } ?: return
        Bukkit.getPlayer(r.id)?.let { applyRecord(it, next) }
    }

    // --- 검증기 --------------------------------------------------------------------------------------

    @Suppress("DEPRECATION") // 사건 생성자는 26.2 에서 내부용 표시 — 검증기에서 쏘기만 한다
    override fun checks(): List<Check> = listOf(
        Check("무적이면 피해 사건이 취소된다") { p -> withState(p, PlayerRecord(god = true)) { if (damage(p).isCancelled) null else "무적인데 피해 사건이 취소되지 않았습니다" } },
        Check("시간제 무적도 피해를 막는다") { p -> withState(p, PlayerRecord(tgodLeft = 30)) { if (damage(p).isCancelled) null else "시간제 무적인데 피해가 들어갑니다" } },
        Check("무적이 아니면 우리 판단은 무적 아님") { p -> withState(p, PlayerRecord()) { if (!isGod(p)) null else "무적이 아닌데 무적으로 봅니다" } },
        Check("/무적 끄기는 시간제 무적도 끝낸다(월드 제한이 끌 수 있게)") { p ->
            withState(p, PlayerRecord(god = true, tgodLeft = 600)) {
                setGod(p, false)
                if (!isGod(p)) null else "끈 뒤에도 무적입니다(시간제 무적이 남았습니다)"
            }
        },
        Check("저장값을 못 읽은 기록은 이번 접속의 것을 얹는다") { _ ->
            val merged = PlayerRecord(god = true).over(PlayerRecord(fly = true, tflyLeft = 7200, collision = false))
            if (merged == PlayerRecord(god = true, fly = true, collision = false, tflyLeft = 7200)) null else "얹기가 틀렸습니다: $merged"
        },
        // 진짜 사건을 **방송하지 않고** 우리 리스너에 바로 넣는다 — 방송하면 다른 플러그인의 MONITOR·피해 리스너(전투 기록 등)가 관리자에게 반응한다(2026-10-10 리뷰).
        Check("무적은 바닐라 /kill(KILL) 을 막지 않는다") { p ->
            withState(p, PlayerRecord(god = true)) {
                val event = EntityDamageEvent(p, EntityDamageEvent.DamageCause.KILL, DamageSource.builder(DamageType.GENERIC_KILL).build(), 0.0)
                listener.onDamage(event)
                if (!event.isCancelled) null else "무적이 KILL 피해까지 막았습니다"
            }
        },
        Check("/처치 -forced 중에는 무적·다른 플러그인의 취소를 넘는다") { p ->
            withState(p, PlayerRecord(god = true)) {
                forcedKill += p.uniqueId
                try {
                    val event = EntityDamageEvent(p, EntityDamageEvent.DamageCause.LAVA, DamageSource.builder(DamageType.LAVA).build(), 0.0)
                    listener.onDamage(event)
                    val godPassed = !event.isCancelled
                    event.isCancelled = true // 다른 플러그인이 막았다고 치고
                    listener.onForcedKill(event)
                    when {
                        !godPassed -> "-forced 인데 무적이 막았습니다"
                        event.isCancelled -> "-forced 인데 다른 플러그인의 취소가 풀리지 않았습니다"
                        else -> null
                    }
                } finally {
                    forcedKill -= p.uniqueId
                }
            }
        },
        Check("무적이면 배고픔이 줄지 않는다") { p ->
            if (!config.godKeepsHunger || p.foodLevel <= 0) return@Check null
            withState(p, PlayerRecord(god = true)) {
                val event = FoodLevelChangeEvent(p, p.foodLevel - 1)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "무적인데 배고픔 감소가 취소되지 않았습니다"
            }
        },
        // 토템: 부활 사건을 쏘지 않고 판단만 본다 — 쏘면 인첸트 REVIVE·DEATH 효과가 관리자에게 발동한다(2026-10-10 리뷰).
        Check("토템 판단: 손에 없고 취소됨 → 가방 토템 · 남이 살림/막음 → 손대지 않음") { _ ->
            val now = 1_000_000L
            when {
                Totems.decide(false, true, false, false, now, null, null) != Totems.Decision.TRY_INVENTORY -> "손 토템 없이 취소된 부활에 가방 토템을 보지 않습니다"
                Totems.decide(false, false, false, false, now, null, null) != Totems.Decision.IGNORE -> "다른 플러그인이 살린 부활에 손댑니다"
                Totems.decide(true, true, false, false, now, null, null) != Totems.Decision.IGNORE -> "다른 플러그인이 막은 손 토템 부활에 쿨타임을 겁니다"
                Totems.decide(true, false, true, false, now, null, null) != Totems.Decision.CANCEL -> "토템 금지 월드에서 막지 않습니다"
                Totems.decide(true, false, false, true, now, now + 5000, null) != Totems.Decision.IGNORE -> "바닐라 월드에 손댑니다"
                else -> null
            }
        },
        Check("토템 판단: 쿨타임이면 막고, 웜업은 쿨타임보다 먼저") { _ ->
            val now = 1_000_000L
            when {
                Totems.decide(true, false, false, false, now, now + 60_000, null) != Totems.Decision.CANCEL_COOLDOWN -> "쿨타임인데 손 토템 부활이 허용됩니다"
                Totems.decide(true, false, false, false, now, now + 60_000, now + 10_000) != Totems.Decision.ALLOW_WARMUP -> "웜업 중인데 쿨타임에 막힙니다"
                Totems.decide(false, true, false, false, now, now + 60_000, now + 10_000) != Totems.Decision.ALLOW_WARMUP -> "웜업 중인데 토템 없이 살지 못합니다"
                Totems.decide(true, false, false, false, now, null, null) != Totems.Decision.HAND -> "손 토템 부활에 쿨타임·웜업을 걸지 않습니다"
                else -> null
            }
        },
        Check("토템: 손 토템 없이 살린 부활은 효과를 직접 건다(바닐라는 아이템이 있어야 건다)") { _ ->
            if (Totems.effectsNeeded(vanillaApplied = false, config) && !Totems.effectsNeeded(vanillaApplied = true, PlayerConfig())) null
            else "효과 판단이 틀렸습니다"
        },
        Check("시간제 비행이 끝나면 비행을 끈다(1초 틱)") { p -> tflyExpiryCheck(p) },
        Check("시간제 무적이 끝나면 무적이 아니다(1초 틱)") { p ->
            withState(p, PlayerRecord(tgodLeft = 1)) {
                tickTimed(p, states.get(p.uniqueId), flush = false)
                if (!isGod(p) && states.get(p.uniqueId).tgodLeft == null) null else "남은 1초가 지났는데 무적입니다"
            }
        },
        Check("시간제 값: 끄기·빼기·다시 접속할 때까지") { _ ->
            val off = TimeArg.Parsed(0, 0)
            when {
                TimedMath.next(null, off, end = true) != TimedMath.Next.NotSet -> "없는 것을 끄면 '없음' 이어야 합니다"
                TimedMath.next(null, TimeArg.Parsed(300, -1), end = false) != TimedMath.Next.NotSet -> "없는 것에서 빼면 '없음' 이어야 합니다"
                TimedMath.next(PlayerRecord.UNTIL_RELOG, TimeArg.Parsed(600, 1), end = false) != TimedMath.Next.UntilRelog -> "다시 접속할 때까지에 더하면 막아야 합니다"
                !TimedMath.looksLikeValue("끄기") || !TimedMath.looksLikeValue("off") || !TimedMath.looksLikeValue("+10m") -> "끄기·off·+10m 을 사람 이름으로 읽습니다"
                else -> null
            }
        },
        Check("비행 유지: 게임모드·부활 판단") { _ ->
            when {
                !FlightRule.restoreOnGameMode(true, true, true) || FlightRule.restoreOnGameMode(false, true, true) || FlightRule.restoreOnGameMode(true, false, true) -> "게임모드 판단이 틀렸습니다"
                !FlightRule.restoreOnRespawn(false, true) || FlightRule.restoreOnRespawn(false, false) -> "부활 판단이 틀렸습니다"
                else -> null
            }
        },
        Check("타기: 막은 종류는 아무도 못 탄다 · 권한 없으면 못 탄다 · 남이 나에게 타기는 개인 설정") { p ->
            when {
                funCommands.canRide(p, "pig", config.copy(rideBlacklist = setOf("pig"))) -> "막은 종류(pig)에 탈 수 있습니다"
                RideRule.allowed("horse", emptySet(), hasAll = false, hasType = false) -> "권한 없이 탈 수 있습니다"
                !RideRule.allowed("horse", emptySet(), hasAll = false, hasType = true) -> "종류 권한으로 못 탑니다"
                funCommands.rideMeAllows(p) != PlayerSettings.enabled(p, SETTING_RIDE_ME, true) -> "남이 나에게 타기 판단이 개인 설정과 다릅니다"
                else -> null
            }
        },
        Check("근처: 관전 모드·숨긴 투명은 빼고, 가린 투명은 가리키지 못한다") { _ ->
            when {
                NearMath.listed(spectator = true, invisible = false, hide = false, obfuscate = false, forPoint = false) -> "관전 모드가 보입니다"
                NearMath.listed(spectator = false, invisible = true, hide = true, obfuscate = false, forPoint = false) -> "숨긴 투명이 보입니다"
                !NearMath.listed(spectator = false, invisible = true, hide = true, obfuscate = true, forPoint = false) -> "가린 투명이 목록에 없습니다"
                NearMath.listed(spectator = false, invisible = true, hide = true, obfuscate = true, forPoint = true) -> "가린 투명을 가리킬 수 있습니다"
                else -> null
            }
        },
        Check("최대체력: 기본값만 바뀌고 최종값은 수정자를 더한다") { p ->
            val attr = p.getAttribute(Attribute.MAX_HEALTH) ?: return@Check "MAX_HEALTH 속성이 없습니다"
            val base = attr.baseValue
            val health = p.health
            try {
                val (newBase, newMax, _) = applyMaxHp(p, MaxHpMath.Op.ADD, 2.0, 100_000.0, save = false) // 파일(playerdata)에 쓰지 않는다
                when {
                    newBase != base + 2.0 -> "기본값이 ${base + 2.0} 이 아니라 $newBase"
                    kotlin.math.abs(newMax - attr.value) > 1e-6 -> "최종값이 getValue() 와 다릅니다"
                    else -> null
                }
            } finally {
                attr.baseValue = base
                p.health = health.coerceAtMost(attr.value)
            }
        },
        Check("최대체력 묶음: 네임스페이스로 나뉜다") { _ ->
            val ok = MaxHpMath.group("inmc", "sword_max-health") == MaxHpMath.Group.GEAR && MaxHpMath.group("inmc", "set_max-health") == MaxHpMath.Group.SET &&
                MaxHpMath.group("titleforge", "stat_max_health") == MaxHpMath.Group.TITLE && MaxHpMath.group("inmcdungeon", "buff_max_health") == MaxHpMath.Group.DUNGEON
            if (ok) null else "묶음 규칙이 틀렸습니다"
        },
        Check("모자: 손에 든 블록은 쓸 수 있고 칼은 안 된다") { _ ->
            val block = hatVerdict(ItemStack(Material.GLASS))
            val sword = hatVerdict(ItemStack(Material.DIAMOND_SWORD))
            when {
                block != HatRule.Verdict.OK && config.hatWhitelist.isEmpty() -> "유리를 못 씁니다: $block"
                config.hatBlockNoneHat && sword == HatRule.Verdict.OK -> "칼을 모자로 쓸 수 있습니다"
                else -> null
            }
        },
        Check("의자: 아래 계단은 의자, 위 계단은 아니다") { _ ->
            if (!config.sitChairs || !config.sitStairs) return@Check null
            val bottom = Bukkit.createBlockData("minecraft:oak_stairs[half=bottom]")
            val top = Bukkit.createBlockData("minecraft:oak_stairs[half=top]")
            val okBottom = bottom is org.bukkit.block.data.type.Stairs && bottom.half == org.bukkit.block.data.Bisected.Half.BOTTOM
            val okTop = top is org.bukkit.block.data.type.Stairs && top.half == org.bukkit.block.data.Bisected.Half.TOP
            if (okBottom && okTop) null else "계단 블록 상태를 읽지 못했습니다"
        },
        Check("좌석 표시 열쇠가 inmcess:seat 이다") { _ -> if (Seats.SEAT_KEY.toString() == "inmcess:seat") null else "열쇠가 ${Seats.SEAT_KEY}" },
        Check("모자 칸: 클릭 안에서 바로 바꾼다(한 틱 뒤 덮어쓰기 없음)") { p -> hatSlotCheck(p) },
        Check("모자: 귀속 저주에 걸린 투구는 벗기지 않는다") { p -> boundHelmetCheck(p) },
        Check("체력 보스바: 띄우고 내린다") { p ->
            if (!config.hpBarEnabled) return@Check null
            val showing = hpBars.isShowing(p)
            try {
                hpBars.show(p, p, 1.0)
                if (hpBars.isShowing(p)) null else "보스바가 뜨지 않았습니다"
            } finally {
                if (!showing) hpBars.hide(p)
            }
        },
        Check("충돌 끄기가 몸에 걸린다") { p ->
            val before = p.isCollidable
            try {
                p.isCollidable = false
                if (!p.isCollidable) null else "setCollidable(false) 가 먹지 않습니다"
            } finally {
                p.isCollidable = before
            }
        },
        Check("남이 나에게 타기 개인 설정이 올라가 있다") { _ ->
            if (PlayerSettings.get(SETTING_RIDE_ME) != null && PlayerSettings.get(SETTING_TOTEM_INVENTORY) != null) null else "PlayerSettings 에 없습니다"
        },
        Check("효과 이름: 옛 이름을 읽는다") { _ ->
            val type = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                .get(org.bukkit.NamespacedKey.minecraft(EffectNames.key("nightvision")))
            if (type == PotionEffectType.NIGHT_VISION) null else "nightvision → night_vision 이 아닙니다"
        },
    )

    /** 검증기 — 머리 칸 클릭 사건을 쏜다(관리자 자신의 머리·커서를 메모리에서 바꿨다 되돌린다). */
    @Suppress("DEPRECATION")
    private fun headClick(p: Player): InventoryClickEvent =
        InventoryClickEvent(p.openInventory, InventoryType.SlotType.ARMOR, PlayerListener.HEAD_RAW_SLOT, ClickType.LEFT, InventoryAction.PLACE_ALL)
            .also { Bukkit.getPluginManager().callEvent(it) }

    private fun hatSlotCheck(p: Player): String? {
        if (!config.hatInventory || !p.hasPermission(HAT_INVENTORY) || p.openInventory.topInventory.type != InventoryType.CRAFTING) return null
        if (hatVerdict(ItemStack(Material.GLASS)) != HatRule.Verdict.OK) return null
        val helmet = p.inventory.helmet?.clone()
        val cursor = p.itemOnCursor.clone()
        try {
            p.inventory.setHelmet(null)
            p.setItemOnCursor(ItemStack(Material.GLASS, 3))
            val event = headClick(p)
            return when {
                !event.isCancelled -> "클릭이 취소되지 않았습니다(다른 플러그인이 먼저 막았을 수 있음)"
                p.inventory.helmet?.type != Material.GLASS || p.inventory.helmet?.amount != 1 -> "사건 안에서 머리 칸이 바뀌지 않았습니다(한 틱 뒤로 미루면 복사가 생깁니다)"
                p.itemOnCursor.type != Material.GLASS || p.itemOnCursor.amount != 2 -> "커서에 남은 개수가 2 가 아닙니다: ${p.itemOnCursor.amount}"
                else -> null
            }
        } finally {
            p.inventory.setHelmet(helmet)
            p.setItemOnCursor(cursor)
        }
    }

    private fun boundHelmetCheck(p: Player): String? {
        if (!HatRule.canReplace(true, false) && HatRule.canReplace(true, true) && HatRule.canReplace(false, false)) Unit else return "규칙이 틀렸습니다"
        if (!config.hatInventory || !p.hasPermission(HAT_INVENTORY) || p.gameMode == org.bukkit.GameMode.CREATIVE) return null
        if (p.openInventory.topInventory.type != InventoryType.CRAFTING || hatVerdict(ItemStack(Material.GLASS)) != HatRule.Verdict.OK) return null
        val helmet = p.inventory.helmet?.clone()
        val cursor = p.itemOnCursor.clone()
        try {
            val bound = ItemStack(Material.CARVED_PUMPKIN).also { it.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.BINDING_CURSE, 1) }
            p.inventory.setHelmet(bound)
            p.setItemOnCursor(ItemStack(Material.GLASS, 1))
            headClick(p)
            return when {
                p.inventory.helmet?.type != Material.CARVED_PUMPKIN -> "귀속 저주 호박이 벗겨졌습니다"
                p.itemOnCursor.type != Material.GLASS -> "커서의 유리가 사라졌습니다"
                else -> null
            }
        } finally {
            p.inventory.setHelmet(helmet)
            p.setItemOnCursor(cursor)
        }
    }

    /** 검증기 — 남은 1초짜리 시간제 비행을 틱에 넣어 비행이 꺼지는지(날던 중이면 착지 보호가 걸리는지) 본다. 비행·착지 표시는 되돌린다. */
    private fun tflyExpiryCheck(p: Player): String? {
        if (p.gameMode !in PlayerListener.SURVIVAL_LIKE) return null // 창작·관전은 바닐라가 비행을 준다 — 끌 것이 없다
        val allow = p.allowFlight
        val flying = p.isFlying
        val landed = landing[p.uniqueId]
        return withState(p, PlayerRecord(tflyLeft = 1)) {
            try {
                p.allowFlight = true
                tickTimed(p, states.get(p.uniqueId), flush = false)
                when {
                    p.allowFlight -> "남은 1초가 지났는데 비행이 켜져 있습니다"
                    states.get(p.uniqueId).tflyLeft != null -> "남은 시간이 지워지지 않았습니다"
                    flying && config.tflyLandingProtection && landing[p.uniqueId] == null -> "날던 중에 끝났는데 착지 보호가 없습니다"
                    else -> null
                }
            } finally {
                p.allowFlight = allow
                if (flying) p.isFlying = true
                if (landed == null) landing.remove(p.uniqueId) else landing[p.uniqueId] = landed
            }
        }
    }

    /** 검증기 — 상태를 메모리에서만 바꾸고 되돌린다(DB 에 쓰지 않는다). */
    private fun withState(player: Player, record: PlayerRecord, block: () -> String?): String? {
        val before = states.get(player.uniqueId)
        states.dryRun = true
        try {
            states.putMemory(player.uniqueId, record)
            return block()
        } finally {
            states.putMemory(player.uniqueId, before)
            states.dryRun = false
        }
    }

    @Suppress("DEPRECATION")
    private fun damage(player: Player): EntityDamageEvent {
        val event = EntityDamageEvent(player, EntityDamageEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), 0.0)
        Bukkit.getPluginManager().callEvent(event)
        return event
    }

    companion object {
        const val LOCAL_FLUSH_SECONDS = 60
        const val SHARED_FLUSH_SECONDS = 5

        const val SETTINGS_OWNER = "inmc-essentials/player"
        const val SETTING_RIDE_ME = "essentials.ride-me"
        const val SETTING_TOTEM_INVENTORY = "essentials.totem-inventory"
        const val SETTING_TOTEM_BOSSBAR = "essentials.totem-bossbar"

        const val GM = "inmcessentials.player.gamemode"
        const val GM_OTHERS = "inmcessentials.player.gamemode.others"
        const val EXP_CHECK = "inmcessentials.player.exp.check"
        const val EXP = "inmcessentials.player.exp"
        const val EXP_OTHERS = "inmcessentials.player.exp.others"
        const val GOD = "inmcessentials.player.god"
        const val GOD_OTHERS = "inmcessentials.player.god.others"
        const val TGOD = "inmcessentials.player.tgod"
        const val TGOD_OTHERS = "inmcessentials.player.tgod.others"
        const val MAXHP_CHECK = "inmcessentials.player.maxhp.check"
        const val MAXHP = "inmcessentials.player.maxhp"
        const val HEAL = "inmcessentials.player.heal"
        const val HEAL_OTHERS = "inmcessentials.player.heal.others"
        const val FEED = "inmcessentials.player.feed"
        const val FEED_OTHERS = "inmcessentials.player.feed.others"
        const val EFFECT = "inmcessentials.player.effect"
        const val EFFECT_OTHERS = "inmcessentials.player.effect.others"
        const val KILL = "inmcessentials.player.kill"
        const val KILL_FORCED = "inmcessentials.player.kill.forced"
        const val SUICIDE = "inmcessentials.player.suicide"
        const val SUICIDE_OTHERS = "inmcessentials.player.suicide.others"
        const val SPEED = "inmcessentials.player.speed"
        const val SPEED_OTHERS = "inmcessentials.player.speed.others"
        /** 속도 상한 노드의 앞부분(뒤에 1~10). 문자열 그대로 적지 않는다 — ResourceTest 가 끝이 점인 노드를 선언 안 된 것으로 본다. */
        const val SPEED_MAX_PREFIX = "$SPEED.max."
        const val FLY = "inmcessentials.player.fly"
        const val FLY_OTHERS = "inmcessentials.player.fly.others"
        const val TFLY = "inmcessentials.player.tfly"
        const val TFLY_OTHERS = "inmcessentials.player.tfly.others"
        const val COLLISION = "inmcessentials.player.collision"
        const val COLLISION_OTHERS = "inmcessentials.player.collision.others"
        const val TOTEM_TOGGLE = "inmcessentials.player.totem.toggle"
        const val TOTEM_INVENTORY = "inmcessentials.player.totem.inventory"
        const val RIDE_TOGGLE = "inmcessentials.player.ride.toggle"
        const val RIDE = "inmcessentials.player.ride"
        const val RIDE_ALL = "inmcessentials.player.ride.all"
        /** 종류별 타기 노드의 앞부분(뒤에 엔티티 종류 열쇠 — `horse`·`player` …). */
        const val RIDE_PREFIX = "$RIDE."
        const val SHAKE = "inmcessentials.player.shakeitoff"
        const val SHAKE_OTHERS = "inmcessentials.player.shakeitoff.others"
        const val HAT = "inmcessentials.player.hat"
        const val HAT_OTHERS = "inmcessentials.player.hat.others"
        const val HAT_INVENTORY = "inmcessentials.player.hat.inventory"
        const val SIT = "inmcessentials.player.sit"
        const val SIT_OTHERS = "inmcessentials.player.sit.others"
        const val SIT_CHAIR = "inmcessentials.player.sit.chair"
        const val SIT_PERSISTENT = "inmcessentials.player.sit.persistent"
        const val SIT_LOCATION = "inmcessentials.player.sit.location"
        const val NEAR = "inmcessentials.player.near"
        const val NEAR_EXACT = "inmcessentials.player.near.exact"
        const val PING = "inmcessentials.player.ping"
        const val PING_OTHERS = "inmcessentials.player.ping.others"
        const val HPBAR = "inmcessentials.player.hpbar"
    }
}

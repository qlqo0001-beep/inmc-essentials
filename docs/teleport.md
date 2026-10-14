# 이동(teleport) — 운영 안내

CMI 의 tp · tphere/tpo/tpohere · tpall/tpallworld · tppos/tpopos · tpa 계열 · back/dback/resetback · teleportWith 모듈 ·
이동 효과 · 이동 금지 아이템 · 이동 뒤 무적을 대신한다. 설정 `plugins/inmc-essentials/settings/teleport.yml`, 메시지 `messages/teleport.yml`.

**모든 기능의 이동(홈·워프·스폰·포탈·감옥·바이옴 찾기 …)이 이 기능을 지난다** — 안전한 자리, 금지 아이템, 종류별 효과, 이동 뒤 무적,
탄 것·태운 것 동반은 이 기능이 켜져 있을 때 모든 이동에 걸린다. 이 기능을 끄면(`modules.teleport: false`) 다른 기능의 이동은 그냥 옮기기만 한다.

바닐라 `/tp` 는 그대로 바닐라다. 그 이동도 `/뒤로` 에 기록된다(원인 COMMAND).

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/이동 <대상>` (tpto) | 대상에게 간다. 대상이 나가 있으면 마지막으로 나간 자리로(`offline` 권한) | `inmcessentials.teleport.tp` (OP) · `.offline` (OP) |
| `/이동 <누구> <어디로> [-s]` | 누구를 어디로(사람) 보낸다. `tp.switch-places: false` 면 인자 순서가 반대 | `inmcessentials.teleport.tp.others` (OP) |
| `/소환 <대상> [-s]` (tphere, s) | 대상을 내 자리로. 대상이 나가 있으면 **다음 접속 때** 지금 내 자리로 옮겨진다 | `inmcessentials.teleport.here` (OP) · `.here.offline` (OP) |
| `/소환 취소 <이름>` | 나가 있는 사람에게 걸어 둔 다음 접속 이동을 거둔다 | `.here.offline` (OP) |
| `/소환 <누구> <어디로> [-s]` | `/이동 <누구> <어디로>` 와 같다 | `.tp.others` |
| `/강제이동 <대상> [어디로] [-s]` (tpo) | 요청 받기를 끈 사람에게도 이동(관리자 이동 — 금지 아이템 무시, 안전한 자리가 없으면 그 자리 그대로) | `inmcessentials.teleport.tpo` (OP) |
| `/강제소환 <대상> [어디로] [-s]` (tpohere) | 요청 받기를 끈 사람도 소환 | `inmcessentials.teleport.tpohere` (OP) |
| `/전원소환 [대상] [-s]` (tpall) | 내가 볼 수 있는 접속자 전원을 나(또는 대상)에게 | `inmcessentials.teleport.tpall` (OP) |
| `/월드전원소환 <월드\|-a> [위치]` (tpallworld) | 그 월드의 (내가 볼 수 있는) 접속자는 지금, **마지막으로 그 월드에서 나간 사람은 다음 접속 때** 위치(없으면 내 자리)로. 나간 사람은 최근 `tp.tpallworld-offline-days` 일 안에 나간 사람만. `-a` = 지금 없는 월드에서 나간 사람 전부(월드를 지운 뒤 되살리기). 알림의 인원은 **실제로 옮겨진 수** | `inmcessentials.teleport.tpallworld` (OP) |
| `/월드전원소환 취소` | 걸어 둔 다음 접속 이동을 **전부** 거둔다(`/소환 <나간 사람>` 것 포함) | 위와 같음 |
| `/좌표이동 <x y z> [월드] [pitch yaw] [-p:이름] [-rng:범위] [-s]` (tppos) | 좌표로. `~` `~5` 는 지금 자리 기준, 소수점 없는 x·z 는 블록 가운데. 각도는 CMI 순서(pitch 다음 yaw) — 첫 값이 ±90 밖이면 바닐라 순서(yaw pitch)로 본다. `월드;x;y;z;yaw;pitch` 한 낱말도 된다. `-p:` 남을 보냄, `-rng:` 그 둘레 무작위 | `inmcessentials.teleport.tppos` (OP) · `-p` 는 `.tppos.others` (OP) |
| `/강제좌표이동 …` (tpopos) | 같은 꼴, 안전 확인 없이 그 좌표 그대로(관리자 이동). `-rng` 면 안전한 자리를 찾는다 | `inmcessentials.teleport.tpopos` (OP) |
| `/이동요청 <대상>` (tpa) | 대상에게 가도 되는지 묻는다 | `inmcessentials.teleport.tpa` (모두) |
| `/소환요청 <대상>` (tpahere) | 대상에게 내게 오라고 한다 | `inmcessentials.teleport.tpahere` (모두) |
| `/이동요청 취소 [대상]` · `/소환요청 취소 [대상]` | 보낸 요청 거두기(대상 없으면 전부 · 이미 수락돼 기다리는 이동도 멈춤) | 위와 같음 |
| `/전원소환요청` (tpaall) | 보이는 접속자 전원에게 소환 요청 | `inmcessentials.teleport.tpaall` (OP) |
| `/수락 [대상]` (tpaccept, tpyes) | 받은 요청 수락(대상 없으면 가장 최근 것) | `inmcessentials.teleport.tpaccept` (모두) |
| `/거절 [대상]` (tpdeny, tpno) | 받은 요청 거절 — 그 사람은 `tpa.deny-block-seconds` 동안 다시 요청 못 함 | `inmcessentials.teleport.tpdeny` (모두) |
| `/요청차단` (tptoggle) | 요청 받기 켜고 끄기(저장됨). 끄면 `/이동`·`/소환` 도 막힌다(강제·우회 권한 빼고) | `inmcessentials.teleport.tptoggle` (모두) |
| `/요청차단 <이름>` | 그 사람의 요청만 막기/풀기(저장됨, 나가 있는 사람도 됨) — CMI `tpblock` | 위와 같음 |
| `/뒤로 [대상] [-s]` (back) | 마지막 이동 전 자리로 | `inmcessentials.teleport.back` (모두) · `.back.others` (OP) |
| `/사망지점 [대상] [-s]` (dback) | 마지막으로 죽은 자리로 | `inmcessentials.teleport.dback` (모두) · `.dback.others` (OP) |
| `/뒤로초기화 [대상] [-death\|사망] [-s] [사유]` (resetback) | 뒤로 자리 지우기(`-death` 면 사망 자리도). 나가 있는 사람도 된다. 대상 없이 `-death`·`사망`·`-s` 부터 적으면 나 자신 | `inmcessentials.teleport.resetback` (OP) · `.resetback.others` (OP) |

`-s` = 옮겨지는 사람에게 알리지 않는다. 남에게 알리는 메시지에서 보낸 사람이 투명이고 받는 사람이 그를 볼 수 없으면 이름 대신 "관리자"(`teleport-someone`).

### 그 밖의 권한

| 권한 | 기본 | 뜻 |
|---|---|---|
| `inmcessentials.teleport.with` | OP | 이동할 때 **타고 있던 것·태운 것·끈에 묶인 동물**이 같이 온다(사용자 결정: 권한이 있어야 한다). 없으면 내려서 혼자 간다 |
| `inmcessentials.teleport.blacklist.bypass` | OP | 이동 금지 아이템을 들고도 이동 |
| `inmcessentials.teleport.toggle.bypass` | OP | 요청 받기를 끈 사람·막은 사람에게도 이동·소환·요청, 거절 뒤 막힘도 무시 |
| `inmcessentials.teleport.tpa.nodistance` | OP | 요청 거리 제한 무시 |

`/뒤로`·`/사망지점` 은 플레이어용이라 기본이 "모두" 다(CMI 는 op). 막으려면 LuckPerms 에서 `inmcessentials.teleport.back` · `.dback` 을 false 로.

## 동작

- **쿨타임·대기·비용**은 commands 기능의 설정에서 명령 이름으로 건다. 이 기능은 **이동이 성공한 순간** 친 명령 이름으로 `ess.gate.commit` 을 부른다
  ("성공해야 쿨타임"). 이동 요청은 **요청한 사람**의 명령(`/이동요청`·`/소환요청`)으로 기록된다(수락한 쪽이 아니라).
- **이동 요청**: 받은 사람에게 [수락] [거절] 버튼(보낸 사람에게는 [취소] 버튼), 소리. 같은 상대에게 다시 보내면 옛 요청을 바꾼다. 수락하면 옮겨질 사람이
  `tpa.warmup-seconds` 초 기다리고(움직이면 취소 — `tpa.cancel-on-move`) 옮겨진다. 나가면 그 사람의 요청은 사라지고 상대에게 알린다. 요청은 저장하지 않는다(재시작하면 사라짐).
  **감옥·수갑**(moderation)인 사람은 요청도 수락도 못 하고, 그런 사람에게 요청할 수도 없다. 볼 수 없는 투명인 사람에게는 요청할 수 없다(이름 찾기에서 빠진다).
  수락 뒤 대기가 끝날 때 **한 번 더** 본다 — 그사이 감옥·수갑이 됐거나 상대가 제외 월드에 들어갔으면 옮기지 않는다. 옮기기가 막히면(금지 아이템·위험한 자리 등) 기다리던 쪽에도 알린다.
  **`tpa.excluded-worlds`**(기본 `inmc_dungeon`)에 있는 사람은 요청을 보내지도 받지도 못하고, 그 월드로는 요청으로 갈 수 없다(던전에 판·입장료 없이 들어가지 않게).
  던전 플러그인의 `blocked-commands` 는 **친 명령 이름**으로 막는다 — 던전 안에서 `/뒤로`·`/사망지점`·`/수락` 등을 막으려면 그 목록에 한글 이름과 별칭
  (`이동요청 소환요청 전원소환요청 수락 거절 tpyes tpno 뒤로 사망지점 dback 이동 소환`)을 더해야 한다.
- **안전한 자리**: 서바이벌·모험 모드만. 처음 자리가 안전하면 그대로, 아니면 위아래 `safe.vertical` 칸 · 옆 `safe.horizontal` 칸 안에서 찾는다
  (읽혀 있지 않은 옆 청크는 건너뛴다). 날 수 있는 사람(`allowFlight`)은 발밑을 보지 않는다. 못 찾으면 이동하지 않는다 — 관리자 이동(강제·소환·전원·`/이동 A B`)·감옥·
  **다른 기능의 시스템 이동(종류 OTHER — 토템 허공 구조·앉기·접속 복귀)** 은 그 자리 그대로(막으면 허공에서 구조가 안 된다).
  월드 경계 밖이면 경계 안으로 당긴다.
- **이동 뒤 무적**: 이 플러그인으로 옮긴 뒤 `invulnerability.seconds` 초(월드별 `per-world`). 허공·/kill·자살 피해는 막지 않는다. 무적인 동안 남을 때릴 수도 없다(`no-attack`).
  블록을 놓거나 부수거나(`end-on-block`) 블록·엔티티를 건드리면(`end-on-interact`) 바로 풀린다.
- **뒤로 기록**: 모든 이동 사건(바닐라 `/tp`·다른 플러그인 포함) 중 `back.causes` 에 든 원인, 같은 월드 `back.min-distance` 블록 이상, `back.excluded-worlds` 에서 떠난 것이 아닌 것.
  `/뒤로` 자신의 이동은 기록하지 않는다(`back.record-back-moves: true` 면 기록 — 두 자리를 오간다). **감옥 이동(가두기·풀기)도 기록하지 않는다** — 풀려난 뒤 `/뒤로` 로 감방에 돌아가지 않게.
  제외 월드로는 `/뒤로` 할 수 없다. 접속을 끊거나 서버를 다시 켜도 남는다(DB).
- **사망 지점**: 죽을 때마다(제외 월드 빼고). 다른 플러그인(부활·대결)이 취소한 죽음은 적지 않는다. DB 에 남는다.
- **금지 아이템**: `blacklisted-items.enabled: true` 일 때 `kinds` 의 이동에서. `TP` = `/이동`·`/좌표이동` 으로 하는 자기 이동(CMI `EnabledFor.tp`).
  ADMIN·JAIL·OTHER 는 적어도 보지 않는다(OTHER 는 다른 기능의 시스템 이동 — 토템 구조가 금지 아이템 때문에 막혀 허공에서 죽지 않게). 기본에 `BACK`·`DEATH_BACK` 이
  들어 있다 — 빼면 `/뒤로`(모두 허용)가 금지 아이템을 옮기는 길이 된다. 가방(갑옷·왼손 포함)과 들고 다니는 보관함(커스텀아이템 배낭)을 센다.
  `최대` 개수보다 많으면 막고, 메시지의 [목록] 에 마우스를 올리면 넘은 것이 보인다. 아이템은 바닐라 이름(`diamond`, CMI 의 `DiamondBlock` 꼴도 된다) ·
  `mmoitems:종류:ID` · 커스텀아이템 `이름공간:ID`. 바닐라 이름은 플러그인 아이템(같은 재료로 만든 커스텀 아이템)을 세지 않는다.
- **동반 이동**(`with` 권한): 타고 있던 탈것(그 탈것의 다른 손님은 내려 둔다)과 내 위에 태운 것을 옮겨 다시 태운다. 끈에 묶인 동물은 **같은 월드 이동만** 따라온다.
  옮기려면 먼저 내려야 하는데, 이동이 막히면(다른 플러그인이 취소 등) **권한과 무관하게** 다시 태운다.
- **효과**: `effects.<종류>.from/to/sound` 와 이 기능 명령마다 `effects.<TP|TPHERE|TPALL|TPAALL|TPPOS|TPOPOS>`(있으면 종류보다 먼저 — CMI TeleportEffects 이름).
  TP = `/이동`·`/강제이동`·`/이동 A B`, TPHERE = `/소환`·`/강제소환`·`/소환 A B`, TPALL = `/전원소환`·`/월드전원소환`, TPAALL = `/전원소환요청` 수락 이동,
  TPPOS = `/좌표이동`, TPOPOS = `/강제좌표이동`. 그 밖의 이동은 종류(ADMIN·OTHER …)의 효과. 기본은 CMI 와 같이 홈·워프·스폰 도착 소리만. 막혔을 때 `fail-sound`.
- **나가 있는 사람 소환**(`/소환`·`/월드전원소환`): 다음 접속 한 틱 뒤에 **한 번만** 시도한다 — 옮기기 전에 기록을 지우고, 막히면(감옥 등) 그 사람에게
  "취소되었습니다" 라고 알린다(접속마다 되풀이하거나 몇 주 뒤 옛 자리로 옮기지 않게). `tp.pending-expire-seconds`(기본 7일)가 지난 기록은 조용히 버린다.
  부른 사람이 투명이었으면 이름 대신 "관리자". DB 에만 적고, 나간 사람 수천 명을 메모리에 들고 있지 않는다.

## 설정 키 (`settings/teleport.yml`)

`safe.enabled` · `safe.down-then-up` · `safe.vertical` · `safe.horizontal` ·
`invulnerability.seconds` · `invulnerability.per-world.<월드>` · `invulnerability.no-attack` · `invulnerability.end-on-block` · `invulnerability.end-on-interact` ·
`tp.switch-places` · `tp.pending-expire-seconds` · `tp.tpallworld-offline-days` ·
`tpa.expire-seconds` · `tpa.warmup-seconds` · `tpa.cancel-on-move` · `tpa.deny-block-seconds` · `tpa.max-distance` · `tpa.here-max-distance` · `tpa.current-location` ·
`tpa.here-current-location` · `tpa.buttons` · `tpa.request-sound` · `tpa.default-accepting` · `tpa.excluded-worlds` ·
`back.causes` · `back.min-distance` · `back.excluded-worlds` · `back.expire-seconds` · `back.record-back-moves` ·
`death-back.excluded-worlds` · `death-back.expire-seconds` ·
`blacklisted-items.enabled` · `blacklisted-items.kinds` · `blacklisted-items.items` ·
`effects.<HOME|WARP|SPAWN|TPA|BACK|DEATH_BACK|ADMIN|PORTAL|JAIL|BIOME|OTHER|TP|TPHERE|TPALL|TPAALL|TPPOS|TPOPOS>.from|to|sound` · `fail-sound`

읽지 못한 값(없는 원인·종류, 월드별 무적의 이상한 줄, 금지 아이템 줄, 없는 효과 열쇠)은 켤 때·리로드 때 **한 줄씩 경고 로그**로 남기고 건너뛴다.
`back.causes` 가 전부 틀리면 "아무것도 기록되지 않습니다" 경고가 따로 나온다.

CMI 에서 옮길 때: `Teleport.Invulnerability`→`invulnerability.seconds`, `InvulnerabilityPerWorld: [w-5]`→`per-world: {w: 5}`, `DisableInvulnerability.*`→`end-on-*`,
`SafeLocationDownThenUp`→`safe.down-then-up`, `SwitchPlaces`→`tp.switch-places`, `Tpa.Time/Warmup/Move/Block/MaxDistance`→`tpa.expire-seconds/warmup-seconds/cancel-on-move(=!Move)/deny-block-seconds/max-distance`,
`TpaHere.MaxDistance`→`tpa.here-max-distance`, `CurrentLoc.tpa/tpahere`→`tpa.current-location/here-current-location`, `DenyConfirm`→`tpa.buttons`,
`Back.MinDistance/BlackList/Expiration`→`back.min-distance/excluded-worlds/expire-seconds`, `DBack.*`→`death-back.*`, `BlackListedItems.*`→`blacklisted-items.*`
(`EnabledFor.tp/tpa/tpahere/warp/home/spawn`→`kinds: [TP, TPA, HOME, WARP, SPAWN]` — tpa·tpahere 는 둘 다 TPA), `TeleportEffects.Tp/TpHere/TpAll/TpaAll/TpPos/TpoPos`→`effects.TP/…`,
`Sounds.TpaRequest`→`tpa.request-sound`, `Sounds.TeleportHome/Warp/Spawn`→`effects.HOME/WARP/SPAWN.sound`, `Sounds.TeleportFail`→`fail-sound`.

## 저장

| 표 | 내용 |
|---|---|
| `ess_teleport_spots(uuid, kind, world, x, y, z, yaw, pitch, saved_at)` | `kind` = `back` · `death` |
| `ess_teleport_tpa(uuid, accepting)` | 요청 받기 |
| `ess_teleport_blocks(owner, blocked)` | 그 사람의 요청을 막음 |
| `ess_teleport_pending(uuid, world, x, y, z, yaw, pitch, saved_at, by_name)` | 나가 있는 동안 불린 자리 |

사람마다의 자료는 접속 직전(비동기)에 읽고 나가면 메모리에서 뺀다. 바뀌는 순간 DB 로 쓴다. 읽은 뒤 접속하기 전에 `/뒤로초기화`·`/소환`·CMI 가져오기가
바꾼 것은 읽어 둔 자료에도 고쳐 넣는다(접속이 옛 값을 되살리지 않게).

## CMI 가져오기

`/에센셜` 의 CMI 가져오기가 부르면: 유저마다 `/back` 자리(TeleportLocation) · 사망 자리(DeathLocation) · 개인 옵션의 `acceptingTPA`(기본값과 다를 때만).
**이미 있는 기록은 그대로 둔다**(두 번 돌려도 겹치지 않는다). 제외 월드의 자리는 건너뛰고, 모양을 모르는 옵션 줄은 보고서의 "못 읽음" 으로 남긴다.
지금 접속 중인 사람은 메모리에도 바로 넣는다(다시 접속하지 않아도 `/뒤로` 가 된다). `acceptingTPA` 는 `acceptingTPA:false` · JSON `"acceptingTPA":false` ·
`=0` · `%%true` 꼴을 읽는다(테섭 DB 에 값이 없어 실제 형식은 못 봤다).

## 검증기(`/에센셜 검증`)

이동 구현이 바꿔 끼워졌나 · 이동 사건(COMMAND) 기록(진짜 사건 한 번) · 가까운 이동 안 기록 · 엔더 진주 안 기록 · `/뒤로` 자신 안 기록 · 감옥 이동 안 기록 ·
제외 월드 안 기록 · 사망 자리 기록 · 이동 뒤 무적 부여 · 무적이면 피해 사건 취소 · 무적이 아니면 건드리지 않음 · 금지 아이템(가방에 bedrock 하나를 잠깐 넣고
실제 판별로 센다 — 끝나면 뺀다, 가방이 가득이면 건너뜀) · 시스템 이동(OTHER)은 안 막음 · 요청 받기 끄기 · 한 사람 막기 · 이동 요청 제외 월드 · 동반 권한 등록 ·
선 자리 근처 안전한 자리 · 요청 장부. 상태는 메모리에서만 바꿨다 되돌린다(DB 에 쓰지 않는다).

**곁효과**: 진짜 `PlayerTeleportEvent` 는 **COMMAND 한 번만** 쏜다 — 그 사건은 다른 리스너에도 간다(commands 기능의 이벤트 명령 `playerTeleport` 가 켜져 있으면
한 번 돈다, 명령 대기·상자 열기 세션이 취소될 수 있다). 감옥에 있으면 그 검사는 건너뛴다. 나머지 기록 규칙은 사건 없이 확인한다. 무적 검사는 `EntityDamageEvent`(피해 0)를 쏜다.

## CMI 와 다른 점

- **위험한 자리 확인 창·`/tpbypass`(15초 강행) 없음** — 안전한 자리를 못 찾으면 이동하지 않는다. 관리자는 `/강제이동`·`/강제좌표이동` 으로 그 자리 그대로 간다.
- **`cmi.safeteleport` 권한 없음** — 서바이벌·모험 모드면 누구나 안전한 자리로 보정된다.
- **파티클은 `이름:개수` 한 가지 모양** — CMI 의 애니메이션 프리셋(`circle;effect:…`)은 읽지 않는다(이 서버는 CMI 에서 전부 비어 있었다).
- **요청 거리 우회는 권한 하나**(`tpa.nodistance`) — CMI 의 블록 수 권한(`cmi.command.tpa.max.<n>`)은 없다.
- **WorldEdit 나침반 이동 빼기(`Back.WithWE: false`) 없음** — 그 이동은 PLUGIN 원인이라 5블록 이상이면 기록된다(CMI 기본 `WithWE: true` 와 같다).
- **BungeeCord 이전 서버로 `/뒤로` 없음.**
- **`/월드전원소환` 의 나가 있는 사람**은 CMI 처럼 플레이어 데이터 파일을 훑지 않고 `ess_users` 의 마지막으로 나간 자리로 고른다 — 이 플러그인(또는 CMI 가져오기) 이전에 나간 사람은 빠진다.
  나가 있는 사람은 그 파일을 고치는 대신 **다음 접속 때** 옮긴다(`/소환 <나간 사람>` 도 같다). 최근 `tp.tpallworld-offline-days`(기본 30)일 안에 나간 사람만,
  기록은 `tp.pending-expire-seconds`(기본 7일) 뒤 버린다. CMI 에 없는 `/소환 취소`·`/월드전원소환 취소` 가 있다. 투명인 접속자는 (볼 수 없는 사람이 치면) 옮기지 않는다.
- **`/좌표이동` 각도**: CMI 순서(pitch yaw)를 따른다. 바닐라 `/tp` 습관(yaw pitch)은 첫 값이 ±90 밖일 때만 알아본다 — `0 45` 처럼 둘 다 ±90 안이면 CMI 순서.
- **금지 아이템의 이동 칸**: CMI `EnabledFor` 의 `tpa`·`tpahere` 를 하나(`TPA`)로 묶었다. CMI 에 없는 `BACK`·`DEATH_BACK`·`PORTAL`·`BIOME` 칸이 있고 기본에 `BACK`·`DEATH_BACK` 이 들어 있다.
- **효과 열쇠**: CMI TeleportEffects 중 이 기능의 명령(Tp·TpHere·TpAll·TpaAll·TpPos·TpoPos)과 이동 종류(Home·Warp·Spawn·Back·DBack·Portal·Biome …)에 맞는 것만 있다.
  `/강제이동` 은 TP, `/강제소환` 은 TPHERE 를 같이 쓴다(CMI 와 같다). 엘리베이터·점프·totem 등 다른 기능의 이동은 그 기능이 고른 종류(대개 OTHER·ADMIN)의 효과다.
- **끈에 묶인 동물은 같은 월드 이동만** 따라온다(월드를 넘으면 바닐라가 끈을 떨어뜨리는데, 다시 묶으면 끈이 복사된다).
- **코러스 열매 이동 권한 없음** — 그 이동은 바닐라대로(`back.causes` 에 `CHORUS_FRUIT` 를 넣으면 뒤로에 기록된다).
- `/요청차단 <이름>` 이 CMI `tpblock` 을 대신한다(따로 명령을 두지 않았다). 남의 요청 받기를 관리자가 켜고 끄는 기능은 없다.
- 이동 요청 대기(수락 뒤 N초)는 이 기능의 설정이다 — commands 기능의 명령 대기와 따로다(CMI 도 `Tpa.Warmup` 이 따로다).

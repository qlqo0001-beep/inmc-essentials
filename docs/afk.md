# 잠수(afk) — 운영 안내

CMI 의 afk · afkcheck 명령과 `Settings/Afk.yml`(자동 잠수 · 제목 · 진입/해제 명령 · 잠수 중 보호 · 우회 막기 · 자동 추방 · 몹 생성 막기)을 대신한다.
설정 `plugins/inmc-essentials/settings/afk.yml`, 메시지 `messages/afk.yml`. 테섭 CMI 는 `Afk.Enabled: false` 였다 — 이 기능은 켜져 있다
(끄려면 `config.yml modules.afk: false`, 자동 잠수만 끄려면 `auto-afk-seconds: 0`).

잠수 상태는 `ess.afk` 로 다른 기능에 내놓는다 — 플레이 시간 멈추기(CMI StopPlayTime)·감옥 시간 멈추기는 그 기능(admininfo·moderation)이 `ess.afk.isAfk` 를 본다.
탭 목록·이름표의 잠수 표시는 타이틀포지의 일이다.

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/잠수 [사유…] [-s]` (afk) | 잠수 켜고 끄기. 사유는 방송·`/잠수확인`·귓속말 안내에 보인다(100자, 서식 태그는 글자 그대로). `-s` = 방송 없이. 때리거나 맞은 뒤 `manual.combat-block-seconds`(15)초 동안은 못 한다("싸우는 중") | `inmcessentials.afk.afk` (모두) |
| `/잠수 -p:<플레이어> [사유] [-s]` | 남을 잠수로 / 풀기 | `inmcessentials.afk.afk.others` (OP) |
| `/잠수확인 [대상\|all]` (afkcheck) | 잠수면 "잠수 N분째 · 사유", 아니면 "마지막 활동 N초 전". `all` 은 볼 수 있는 접속자 전부(잠수 먼저) | `inmcessentials.afk.check` (모두) |

### 그 밖의 권한

| 권한 | 기본 | 뜻 |
|---|---|---|
| `inmcessentials.afk.auto` | 모두 | 쉰 시간이 지나면 자동 잠수(CMI cmi.command.afk.auto) |
| `inmcessentials.afk.kickbypass` | OP | 자동 추방 면제 |
| `inmcessentials.afk.kickoutin.<초>` | (선언 안 함) | 이 사람의 추방 시간(가장 큰 것, 단계·기본보다 이긴다). **직접 준 노드만** 본다 — op 라고 생기지 않는다 |
| `inmcessentials.afk.staff` | OP | 잠수 중 귓속말을 받으면 보낸 사람에게 "스태프는 디스코드로" 안내 |

## 동작

- **자동 잠수**: `check-interval` 초마다 본다. 마지막 활동에서 `auto-afk-seconds` 가 지났고 `disabled-worlds` 가 아니면 잠수.
- **활동**: `leave-on.*` 에서 켠 종류만 활동이다 — 상호작용 · 가방 클릭 · 버리기 · 명령 · 공개 채팅 · 귓속말(`may-not-respond.whisper-commands`·`reply-commands`) ·
  이동 · 둘러보기(기본 끔) · 낚시(기본 끔 — **낚싯대를 든 우클릭(던지기·거두기)과 물고기 낚기 전부**. 상호작용으로 세지 않으므로 낚싯대 자동 클릭기로
  자동 잠수를 피하지 못한다). **끈 종류는 잠수를 풀지도 쉰 시간을 되돌리지도 않는다.** `/잠수` 자신은 명령 활동으로 세지 않는다.
- **우회 막기**:
  - `anti-afk-machines`: 이동은 손님이 그 순간 **이동 키(앞·뒤·옆)를 누르고 있을 때만** 활동이다(Paper 가 손님의 키 입력을 서버에 알린다) — 물길·피스톤에 실려 다니는 것은 아니다.
    키를 누르기 시작하는 것(웅크리기·달리기 포함)도 활동이다.
  - `prevent-jumping`: 높이만 바뀐 움직임과 점프 키는 활동이 아니다.
  - `smart-interact-check`: 같은 블록·같은 손 아이템에 같은 동작을 1초 안에 되풀이하면 처음 한 번만(버튼 꾹 누르기).
- **들어갈 때**: 화면 가운데 제목(`afk-title`) + 무작위 부제(`afk-subtitle-1`~`5` 중 빈 것 빼고) — 잠수 동안 10분마다 다시 보내 계속 보인다. 방송(볼 수 있는 사람에게만 + 콘솔).
  명령(`commands.auto-enter`·`manual-enter` — 콘솔, `player:` 로 시작하면 그 사람이 · `{player}` `{reason}` `{time}` `{idle}`). **`{reason}` 은 사람이 친 글**이라
  `< > { } [ ] § & \ / | ; "`·줄바꿈을 지우고 낱말 앞의 `-` 를 떼어 넣는다(태그·클릭 명령·`-s` 같은 깃발을 끼워 넣지 못하게). **투명인 사람**은 `commands.when-vanished` 가 아니면 방송·명령 없이 상태만.
- **나올 때**: 제목 지우기(우리가 제목을 띄웠을 때만 — `title: false` 면 지우지 않는다), "N분 동안 잠수" 안내, 방송, `commands.leave-manual`·`leave-auto`.
- **명령 속 시간**: `{time}`(`{시간}`) = 잠수가 된 뒤 지난 시간(나올 때는 잠수했던 시간) — **단 `auto-kick.commands` 에서는 쉰 시간**(기본 추방 문구
  `afk-kick-reason` 과 같다 — CMI AutoKickCmds 의 `[time]`). `{idle}`(`{쉰시간}`) = 마지막 활동 뒤 쉰 시간(어디서나).
- **잠수 중 보호**(`protect.*`): 피해 막기(허공·/kill 빼고 — 자동 잠수는 바로, **손 잠수는 `protect.damage-manual-delay`(30)초 뒤부터**,
  `protect.damage-disabled-worlds`(기본 `inmc_dungeon`)에서는 안 막는다) · 잠수인 사람이 때린 피해 막기(사냥터) · 낚싯대로 끌리지 않기 · 밀리지 않기(충돌 끄기 + 넉백 막기 —
  나올 때 원래 충돌로 되돌린다. 잠수 중 `/충돌` 로 바꿨으면 그 값을 둔다 — player 기능이 나온 뒤 1초 안에 저장된 `/충돌` 값을 다시 건다) · 아이템·경험치 줍지 않기(경험치는 구슬이 남는다) · 셜커가 노리지 않기 · 근처 몹 생성 막기(자연·스포너·철 골렘을 각각 —
  반경 안에 **잠수인 사람만** 있을 때. 깨어 있는 사람이 같이 있으면 그대로 생긴다).
- **귓속말 안내**: **chat 기능이 켜져 있으면 chat 이 맡는다** — 귓속말이 실제로 전해진 뒤에만 "자리를 비웠습니다"(`chat-msg-target-afk`)를 보낸다
  (받기를 끄거나 나를 무시한 사람의 잠수 사유가 새지 않는다). 이 기능의 `may-not-respond` 는 chat 기능을 끄고 **다른 귓속말 플러그인**을 쓸 때만 돈다:
  잠수인 사람에게 `/귓 <이름> …` 등을 치면 보낸 사람에게 "답하지 못할 수 있습니다(사유)" (볼 수 없는 투명인 사람은 이름 찾기에서 빠져 새지 않는다).
  답장(`/답장`)은 받는 사람이 명령에 없어 안내하지 않는다.
- **자동 추방**: `auto-kick.seconds`(-1 = 끔)가 켜져 있으면 잠수가 된 뒤 그 초(손으로 잠수한 사람은 `auto-afk-seconds` 를 더)가 지나면 추방.
  잠수가 될 때의 접속 인원으로 `by-player-count` 의 "인원 이하 중 가장 큰" 단계를 고른다(그 뒤 인원이 바뀌어도 다시 고르지 않는다 — CMI 와 같다).
  추방할 때 인원이 `from-player-count` 보다 적으면 미룬다. `commands` 가 비면 기본 추방(`afk-kick-reason`), 적으면 그 명령들. `repeat` 면 추방되지 않은 사람에게
  `repeat-interval` 초마다 다시(경고 메시지 같은 반복 동작에도 쓸 수 있다).

## 설정 (`settings/afk.yml`)

| 키 | 기본 | CMI |
|---|---|---|
| `auto-afk-seconds` · `check-interval` · `disabled-worlds` | 300 · 10 · [] | AutoAfkIn · CheckInterval · DisabledWorlds |
| `title` · `subtitle` | true · true | TitleMessage · SubTitleMessage |
| `broadcast.enter` · `broadcast.leave` | true · true | (CMI 는 명령의 broadcast 로) |
| `commands.auto-enter` · `.manual-enter` · `.leave-manual` · `.leave-auto` · `.when-vanished` | [] … · false | AutoAfkCmds · ManualAfkCmds · LeaveCmds.Manual/Auto · vanish afkcommands |
| `protect.damage` · `.mob-damage` · `.hook` · `.pushing` · `.item-pickup` · `.exp-pickup` · `.shulker-targeting` | true · true · true · false · false · false · true | PreventDamage · PreventMobDamage · PreventHook · PreventPushing · DisableItemPickup · DisableExpPickup · Disable.ShulkerTargeting |
| `protect.damage-manual-delay` · `protect.damage-disabled-worlds` · `manual.combat-block-seconds` | 30 · [inmc_dungeon] · 15 | — (새로 — 아래 "CMI 와 다른 점") |
| `protect.mob-spawning.enabled` · `.natural` · `.spawners` · `.village-defence` · `.radius` | false · false · true · true · 64 | PreventMobSpawning.* (반경은 새로) |
| `anti-abuse.prevent-jumping` · `.smart-interact-check` · `.anti-afk-machines` | true · true · true | PreventJumping · SmartInteractCheck · AntiAfkMachines |
| `leave-on.interact` · `.inventory-click` · `.item-drop` · `.command` · `.public-chat` · `.private-chat` · `.move` · `.look-around` · `.fishing` | true ×7 · false · false | DisableOn* |
| `auto-kick.seconds` · `.from-player-count` · `.by-player-count` · `.repeat` · `.repeat-interval` · `.commands` | -1 · 0 · [10-600 … 50-300] · false · 300 · [] | AutoKickTimes.* · RepeatKickCommand · RepeatingAutoKickInterval · AutoKickCmds |
| `may-not-respond.enabled` · `.whisper-commands` · `.reply-commands` | true · [msg tell w m whisper 귓 귓말 귓속말 pm dm] · [r reply 답장] | afk.MayNotRespond(Staff) — 알림은 chat 기능이 꺼져 있을 때만(위). 활동 판정(귓속말·답장 명령)은 늘 |

## 저장

없음 — 잠수 상태는 메모리뿐이다(나가면 풀린다, 방송·명령 없이).

## CMI 와 다른 점

- **끈 활동 종류는 활동이 아니다**: CMI 의 DisableOn* 는 "잠수 풀기" 만 정했다. 여기서는 끄면 쉰 시간도 되돌리지 않는다(둘러보기를 끈 채 마우스만 흔들어 자동 잠수를 피하지 못하게).
- 물길 기계 막기는 손님의 이동 키 입력으로 판정한다(CMI 의 판정 방식은 공개되지 않았다). 이동 키에 물건을 올려 두고 걷게 하는 장치는 막지 못한다.
- 밀림 막기는 충돌 끄기 + Paper 넉백 사건 취소다. 사람끼리의 밀림은 손님이 예측하는 경우가 있어 완전하지 않다(player 기능의 `/충돌` 과 같은 한계).
- 몹 생성 막기는 반경(`protect.mob-spawning.radius`)을 두고, 깨어 있는 사람이 근처에 있으면 막지 않는다.
- 기본 방송·추방 문구는 메시지 파일에 있고 명령 목록은 비어 있다(CMI 는 `cmi broadcast`·`cmi kick` 명령으로 했다).
- **싸우다 `/잠수` 로 무적이 되지 않게**(CMI 에 없음 — 이 서버는 CMI 잠수를 꺼 두었으므로 기본값으로 새 무적이 생기지 않게): 손 잠수는 30초 뒤부터 피해 보호,
  때리거나 맞은 뒤 15초는 스스로 `/잠수` 를 못 한다(관리자의 `-p:` 는 된다), 던전 월드에서는 피해 보호를 하지 않는다(쓰러짐·전멸 판정을 막지 않게).
  던전 월드 이름을 바꾸면 `protect.damage-disabled-worlds` 도 고칠 것.
- 명령 속 `{reason}` 은 걸러 넣는다(위). CMI 는 그대로 넣었다.
- 자동 추방·들어가기 명령은 접속자 목록의 사본으로 돈다(한 번에 둘을 추방해도 나머지 처리가 빠지지 않는다).
- 플레이 시간 멈추기(StopPlayTime)는 admininfo 의 플레이 시간 기능이 `ess.afk` 를 보고 정한다.
- 귓속말 잠수 안내는 chat 기능이 켜져 있으면 chat 의 "자리를 비웠습니다" 하나뿐이다 — 사유·스태프 안내(`afk-may-not-respond-staff`)는 chat 이 쓰지 않는다
  (chat 이 사유를 읽을 접점 `AfkState` 에 사유가 없다).
- 잠수에서 나올 때 제목을 지우면, 잠수 중에 **다른 플러그인이 띄운 제목**(던전 카운트다운 등)이 아직 보이고 있었다면 그것도 지워진다 — 서버는 지금 보이는
  제목을 알 수 없다(패킷 감시 플러그인을 쓰지 않는다). 매초 다시 보내는 카운트다운은 다음 초에 다시 뜬다.
- 검증기(`/에센셜 검증`)는 명령 사건을 방송하지 않고 리스너의 판단을 직접 부른다(명령 쿨타임·별칭·인첸트 COMMAND 가 관리자에게 걸리지 않게).
  몹 생성 막기는 사건을 만들 수 없어(엔티티가 있어야 한다) 판정 함수만 본다.

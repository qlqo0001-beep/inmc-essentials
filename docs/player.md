# 플레이어 상태·능력(player) — 운영 안내

CMI 의 gm · exp/checkexp · god/tgod · maxhp · heal · feed/hunger · effect · kill · suicide · speed/walkspeed/flyspeed · fly/tfly ·
playercollision · totemtoggle(totemBehavior 모듈) · ride/shakeitoff(rideAnimation) · hat(hat 모듈) · sit(sitAnimation) · near · ping ·
hpBossBar 를 대신한다. 설정 `plugins/inmc-essentials/settings/player.yml`, 메시지 `messages/player.yml`.

월드별 게임모드·비행·무적 제한(CMI WorldLimits)은 **world 기능**이 월드를 옮길 때 건다 — 이 기능은 월드를 보지 않는다.

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/게임모드 <모드> [대상] [-s]` (gm) | 모드: 서바이벌·크리에이티브·어드벤처·관전 · survival/s/0 · creative/c/1 · adventure/a/2 · spectator/sp/3. CMI 순서 `/gm <대상> <모드>` 도 된다 | `inmcessentials.player.gamemode` (OP) · `.gamemode.others` (OP) |
| `/경험치 [대상]` (exp) | 레벨 · 이번 레벨 경험치 · 다음 레벨까지 · 총 경험치 보기(CMI checkexp) | `inmcessentials.player.exp.check` (모두) · 남 `.exp.others` (OP) |
| `/경험치 [대상] <더하기\|설정\|빼기\|초기화> <양> [-s]` | 양: `10` 경험치 · `10L` 레벨 · `10-50` 무작위 · `10%` 지금 총 경험치의 비율 · `10%L` 지금 레벨의 비율 · `10-20%` 무작위 비율 · `10%[5-50]` 비율이되 5~50 사이 (add/set/take/clear 도 된다) | `inmcessentials.player.exp` (OP) |
| `/최대체력 [대상]` (maxhp) | 기본값 + 묶음별 수정자 = 최종값(예: 기본 20 + 장비 8 + 칭호 4 = 32) | 나: `.maxhp.check` (모두) · 남: `.maxhp` (OP) |
| `/최대체력 <설정\|더하기\|빼기\|초기화> <대상> [수치] [-s]` | **기본값만** 바꾼다(아래 "최대 체력") | `inmcessentials.player.maxhp` (OP) |
| `/회복 [대상\|all] [양\|%] [-nofeed] [-ignoreeffects] [-dontextinguish] [-s]` (heal) | 최종 최대 체력까지(또는 양·비율만큼) + 배고픔 + 나쁜 효과 지우기 + 불 끄기. CMI 철자 `-ignoreffects` 도 된다 | `inmcessentials.player.heal` (OP) · `.heal.others` (OP) |
| `/배고픔 [대상\|all] [양] [-s]` (feed, hunger) | 양이 없으면 배고픔·포화를 가득, 있으면 배고픔을 그 값으로(CMI hunger) | `inmcessentials.player.feed` (OP) · `.feed.others` (OP) |
| `/효과 <대상\|all> <효과\|지우기> [초\|+초\|-초\|무한] [세기] [-s] [-visual]` (peffect) | 효과 주기. `+10`/`-10` 은 지금 남은 시간에 더하고 빼기, `지우기`(clear) 는 전부 지움. 세기 1 = I. `-visual` 이면 거품·아이콘이 보인다. 옛 이름(nightvision·slow …)도 읽는다. 시간은 `30`·`5m`·`1시간` | `inmcessentials.player.effect` (OP) · 남·all `.effect.others` (OP) |
| `/처치 <대상> [피해원인] [-forced] [-lightning]` | 바닐라 피해 원인(`lava`·`fall`·`drown`… 제안 목록, 없으면 `generic_kill`)으로 죽인다 — 사망 메시지가 그 원인. **무적(`/무적`·`/시간제무적`)이면 막고 알린다**, 다른 플러그인의 보호·게임모드로 안 죽어도 알린다. `-forced` = 그 피해 한 번 동안 무적·토템·다른 플러그인의 취소를 넘는다(**고른 원인이 사망 메시지에 남는다** — 창작 모드처럼 피해가 아예 안 들어가면 그때만 체력 0). `-lightning` 은 번개 연출 | `inmcessentials.player.kill` (OP) · `-forced` 는 `.kill.forced` (OP) |
| `/자살 [대상] [-s]` (suicide) | 즉사. 불사의 토템을 손에 들고 있으면 막힌다. 볼 수 있는 사람에게 "스스로 목숨을 끊었습니다" | `inmcessentials.player.suicide` (모두) · `.suicide.others` (OP) |
| `/속도 [대상] [값] [-s]` (speed) | 날고 있으면 비행 속도, 아니면 걷기 속도. 값이 없으면 지금 값 | `inmcessentials.player.speed` (OP) · `.speed.others` (OP) |
| `/걷기속도 [대상] [0~5]` (walkspeed) · `/비행속도 [대상] [0~10]` (flyspeed) | 1 = 바닐라 기본 | 위와 같음 · 상한 `inmcessentials.player.speed.max.<1~10>` (가장 큰 것) |
| `/무적 [대상] [켜기\|끄기] [-s]` (god) | 피해를 받지 않는다(허공·바닐라 `/kill` 은 그대로, `/처치` 는 `-forced` 일 때만). 불을 끄고 배고픔이 줄지 않는다(설정). **저장됨**. 끄면(또는 시간제 무적 중에 그냥 `/무적`) **시간제 무적도 끝난다** — world 기능의 무적 금지 월드가 이 명령으로 끈다 | `inmcessentials.player.god` (OP) · `.god.others` (OP) |
| `/시간제무적 [대상] [시간\|+시간\|-시간\|0\|끄기] [-s]` (tgod) | 그 시간 동안 무적. `0` = 다시 접속할 때까지, `끄기`(off·끝·end) = 지금 끝내기, 시간 없으면 남은 시간 보기. 첫 자리에 바로 `끄기`·`0`·`+10m` 을 쓰면 나에게(사람 이름으로 찾지 않는다). 없는 것을 끄거나 빼면 "시간제 무적이 아닙니다", "다시 접속할 때까지" 에는 더하기·빼기를 받지 않는다(그냥 시간을 쓰면 그 시간으로). 남은 시간은 **나갔다 들어와도 남는다**(접속해 있는 동안만 준다) | `inmcessentials.player.tgod` (OP) · `.tgod.others` (OP) |
| `/비행 [대상] [켜기\|끄기] [-s]` (fly) | 비행 켜고 끄기. **저장됨**(다시 접속·서바이벌로 바꿔도 다시 켜진다) | `inmcessentials.player.fly` (OP) · `.fly.others` (OP) |
| `/시간제비행 [대상] [시간\|+시간\|-시간\|0\|끄기] [-s]` (tfly) | 그 시간 동안 비행. `+30m` 더하기 · `-5m` 빼기 · `0` 다시 접속할 때까지 · `끄기` 지금 끝내기 · 없으면 남은 시간. 나머지는 `/시간제무적` 과 같다 | `inmcessentials.player.tfly` (OP) · `.tfly.others` (OP) |
| `/충돌 [대상] [켜기\|끄기] [-s]` (playercollision) | 다른 엔티티와 밀고 밀리기. **저장됨** | `inmcessentials.player.collision` (OP) · `.collision.others` (OP) |
| `/토템` (totemtoggle) · `/토템 보스바` | 가방 속 토템 자동 사용 · 토템 보스바를 나만 켜고 끄기(개인 설정) | `inmcessentials.player.totem.toggle` (모두) |
| `/타기` (mount) | 바라보는 개체(`ride.max-distance` 안)에 탄다 | `inmcessentials.player.ride` (OP) + 종류 권한 |
| `/타기 <플레이어>` | 그 사람(가까이 있어야 함) 위에 탄다. 그 사람에게 알림 | 위와 같음 + `.ride.player` |
| `/타기 목록` | 내가 탈 수 있는 종류 | `.ride` |
| `/타기 허용` | 남이 나에게 타기 켜고 끄기(개인 설정, 기본 켬 — CMI rideMe) | `inmcessentials.player.ride.toggle` (모두) |
| `/내리기 [대상] [-s]` (shakeitoff) | 나(대상)에게 탄 것을 모두 내린다 | `inmcessentials.player.shakeitoff` (모두) · `.shakeitoff.others` (OP) |
| `/모자 [대상] [-s]` (hat) | 손에 든 아이템 하나를 머리에. 쓰던 것은 가방으로(가방이 차면 안 바꾼다). 남에게 쓰면 **그 사람이 든 것**을 씌운다(CMI 와 같다). 쓰던 것이 **귀속 저주**면 창작 모드가 아니면 바꾸지 않는다(CMI `hat.info.incorrect`) | `inmcessentials.player.hat` (모두) · `.hat.others` (OP) |
| `/앉기 [대상] [-persistent] [-s]` (sit) | 그 자리에 앉기(앉아 있으면 일어나기). 발밑이 계단·반 블록이면 그 의자에. `-persistent` = 나갔다 들어와도 다시 앉힘 | `inmcessentials.player.sit` (모두) · `.sit.others` (OP) · `.sit.persistent` (OP) |
| `/앉기 [대상] 좌표 <x> <y> <z> [월드]` | 그 자리로 옮겨 앉히기(`~` = 지금 자리) | `inmcessentials.player.sit.location` (OP) |
| `/근처 [거리]` (near) | 거리(기본 200, 최대 `near.max-distance`) 안의 사람을 가까운 순으로. 기본은 방향과 대략 거리(~100m). 이름을 누르면 그쪽을 입자로 가리킨다 | `inmcessentials.player.near` (모두) · 정확한 거리 `.near.exact` (OP) |
| `/근처 가리키기 <플레이어>` | 그 사람 쪽으로 입자(나에게만 보임 — CMI `/point`). `/근처` 목록과 같은 숨김 규칙(관전 모드·숨긴 투명은 "없음", 가린 투명도 가리키지 않음). `near.point-to: false` 면 이 하위 명령이 없다 | `.near` |
| `/핑 [대상]` (ping) | 핑(ms) | `inmcessentials.player.ping` (모두) · `.ping.others` (OP — CMI ping.others 처럼) |

`[대상]` 자리에 사람 대신 값(`켜기`·`30m`·`-s` …)을 쓰면 나에게 쓴 것이다. `-s` = 대상에게 알리지 않는다. 남에게 한 일을 알릴 때 한 사람이 투명이고
대상이 그를 볼 수 없으면 이름 대신 "관리자"(`player-someone`). 사람 이름 제안·찾기는 볼 수 있는 사람만.

### 명령 없는 것

| 무엇 | 권한 (기본) | 설정 |
|---|---|---|
| **체력 보스바** — 몹·플레이어를 때리면 상대 체력 `현재/최대 (-피해)`, 안 때리고 `hp-bar.seconds` 초 지나면 사라짐. inmc-monster 의 커스텀 몹(던전 보스 포함 — 자기 보스바가 있다)은 `hp-bar.skip-custom-mobs`(기본 켬)로 뺀다 | `inmcessentials.player.hpbar` (모두) | `hp-bar.*` (제외 몹 기본 `ender_dragon`) |
| **가방 속 토템** — 손에 없어도 가방의 토템이 터진다 | 설정 `totem.require-permission` 이면 `inmcessentials.player.totem.inventory` (모두) | `totem.from-inventory` (기본 끔, CMI 와 같다) |
| **의자** — 빈손으로 계단(아래쪽)·반 블록(아래쪽)·카펫(설정)을 우클릭하면 앉는다. 웅크리면 일어남 — **일어나면 앉기 전 자리로 돌아간다**(창살 틈·구멍 너머의 의자로 빠져나가지 못하게). 한 의자에 한 사람 | `inmcessentials.player.sit.chair` (모두) | `sit.*` |
| **모자 칸** — 가방 화면의 머리 칸에 커서로 아무 블록이나 놓아 모자로. 왼쪽·오른쪽 클릭만(숫자 키·쉬프트는 바닐라). 다른 플러그인이 막은 클릭(가방 잠금·감옥·메뉴)은 손대지 않는다. 쓰던 것이 귀속 저주면 바꾸지 않는다 | `inmcessentials.player.hat.inventory` (모두) — 이 권한은 **바닐라가 머리 칸에 안 받는 것**에만 쓰인다(투구·몹 머리·호박은 바닐라 그대로, 권한과 상관없음) | `hat.*` |
| **탄 채 질식** — 사람 위에 탄 채 블록에 끼어 숨이 막히면 내려 준다(CMI rideAnimation) | — | `ride.eject-on-suffocation` |

### 개인 설정 (플레이어 메뉴 `/설정` 에도 나온다)

| 열쇠 | 기본 | 뜻 |
|---|---|---|
| `essentials.ride-me` | 켬 | 남이 `/타기 <나>` 로 내 위에 탈 수 있다 |
| `essentials.totem-inventory` | 켬 | 가방 속 토템 자동 사용(서버 설정이 켜져 있을 때만 뜻이 있다) |
| `essentials.totem-bossbar` | 켬 | 토템 보호·쿨타임 보스바 |

## 동작

- **최대 체력**: `/최대체력` 은 MAX_HEALTH 의 **기본값(base)만** 바꾼다. 커스텀아이템(`inmc:*` 장비, `inmc:set_*` 세트·부적), 타이틀포지(`titleforge:*` 칭호),
  던전(`inmcdungeon:*` 축복)은 자기 수정자를 따로 달므로 서로 싸우지 않는다. `더하기`·`빼기`는 **지금 기본값**에서 계산한다(CMI 는 장비가 포함된 최종값에서
  계산해 장비 보너스가 기본값에 영구히 섞였다 — 따라 하지 않았다). 범위 1 ~ min(`maxhp.cap`, spigot.yml `settings.attribute.maxHealth.max`).
  줄면 체력을 잘라 맞추고, 늘면 늘어난 만큼 채운다. 바꾼 뒤 `saveData()` 로 바로 저장. 기본값은 바닐라 playerdata 에 남는다(죽어도 그대로) — CMI 로
  바꿔 둔 값도 이미 거기 있어 옮길 것이 없다. 조회 화면은 수정자를 네임스페이스로 묶고, 곱하는 수정자(ADD_SCALAR 등)는 `× 이름 +10%` 로 따로 적으며
  최종값은 늘 서버가 계산한 값이다. 타이틀포지 GUI 의 "플레이어 기본값 20" 은 설정의 고정값이라 `/최대체력` 으로 바꾼 값과 다를 수 있다.
- **회복**은 최종 최대 체력(장비 포함)까지 채운다(CMI 와 같다). 나쁜 효과 목록은 `heal.negative-effects`.
- **무적·비행·충돌**은 DB(`ess_player_state`)에 남아 다시 접속하면 다시 걸린다(접속 2틱 뒤). 시간제 무적·비행의 **남은 초**도 남는다 — 1분마다·나갈 때·끌 때
  적으므로 서버가 갑자기 죽으면 최대 1분을 돌려받을 수 있다. `0`(다시 접속할 때까지)은 저장하지 않는다. `timed.warn-at` 의 초가 남으면 알린다.
- **공용 DB(MySQL — 여러 서버)**: 프록시는 새 서버에 먼저 붙고 옛 서버를 나중에 끊어, 새 서버가 옛 서버의 나감 쓰기보다 먼저 읽을 수 있다. 그래서 공용 DB 면
  남은 초를 **5초마다** 적고, 남은 시간이 있는 사람은 접속 3초 뒤 한 번 더 읽어 **작은 쪽**을 쓴다(그 전에는 주기 쓰기를 하지 않아 옛 서버의 늦은 쓰기를 덮지 않는다).
  서버를 오가며 시간을 버는 일은 없고, 잃는 것은 길어야 몇 초다.
  시간제 비행이 날던 중에 끝나면 그 착지의 낙하 피해를 한 번 막는다(1분 안 · `timed.tfly-landing-protection`).
- **비행 유지**: 서바이벌·어드벤처로 바꾸면 바닐라가 비행을 끄는데, `/비행`·`/시간제비행` 으로 켠 사람은 다시 켠다(`fly.keep-on-gamemode-change`).
  `fly.restore-after-death` 면 죽기 전에 날 수 있던 사람은 살아나면 다시 날 수 있다.
- **효과 오프라인 감소**(`effect.deduct-while-offline`): 다시 들어올 때 나가 있던 시간만큼 효과를 줄인다(무한 효과는 그대로). 지난 접속이 크래시로
  끝났으면(나간 시각이 지난 접속 시각보다 앞) 나가 있던 시간을 모르므로 줄이지 않는다.
- **저장값을 못 읽으면 쓰지 않는다**: 접속 직전 DB 읽기가 실패하면 기본값으로 돌되 **쓰지 않고**, 접속 뒤 쓰기 스레드에서 다시 읽어 이번 접속에 켠 것을
  얹는다. 다시 읽기도 실패하면 이번 접속의 변경은 저장하지 않는다(로그 경고) — 기본값 = 줄 지우기라 저장된 무적·비행·남은 시간을 지우지 않게.
- **잠수와 충돌**: 잠수 기능이 밀기 막기(`protect.pushing`)로 충돌을 꺼 두었다 잠수에서 나오면, 저장된 `/충돌` 값을 다시 건다(1초 안).
- **토템**: 쿨타임(`totem.cooldown-seconds`) 동안은 손의 토템도 막는다(토템은 남고 죽는다). 웜업(`totem.warmup-seconds`) = 한 번 터진 뒤 그 초 동안은
  토템 없이도 다시 살아난다 — **웜업을 쿨타임보다 먼저 본다**(둘 다 켜도 웜업 동안은 산다). 효과 시간은 `totem.effects.*`(0 = 그 효과 없음, 바닐라 값이면 손대지 않음). `protect-from-void` = 토템을 가진 채(손 또는
  가방 — 가방은 위 조건이 맞을 때) 허공에 들어서면 리스폰 자리(없으면 첫 월드 스폰)로 옮기고 **옮기기에 성공한 뒤에** 토템 하나를 쓴다. 이동이 막히면(감옥·수갑·
  이동 금지·월드 없음) 토템을 쓰지 않고 5초 동안 다시 하지 않는다 — 허공 피해는 토템이 막지 못하므로 그대로 죽고 토템은 남는다. `vanilla-worlds` 는 손대지 않고,
  `disabled-worlds` 에서는 토템이 아예 안 된다. 허공 구하기는 `protect-from-void-skip-worlds`(기본 `inmc_dungeon`)에서도 하지 않는다 — 던전은 허공 추락을
  "쓰러짐" 으로 다루고, 다른 월드로 옮기면 판에서 빠진다. 던전 월드 이름을 바꾸면 이 목록도 고칠 것.
  가방 속 토템·웜업으로 살아나면(손에 토템 없음) 바닐라가 효과를 걸지 않으므로 우리가 `totem.effects.*` 를 건다(1.21.2+ 는 효과가 아이템 구성요소에서 온다).
- **부활 사건 순서**: 토템 처리는 `EntityResurrectEvent` **HIGHEST** 다. inmc-enchants 의 REVIVE(HIGH)가 이미 살렸으면(손 토템 없이 취소가 풀림) 손대지
  않는다 — 가방 토템을 쓰지도, 쿨타임을 걸지도, 금지 월드에서 막지도 않는다. 다른 플러그인이 손 토템 부활을 **막았으면**(손 토템 있음 + 취소됨) 역시 손대지
  않는다(쿨타임·웜업을 걸지 않는다). 손에 토템을 든 채 쿨타임 중이면 막는다(그때는 REVIVE 도 못 살린다 —
  손 토템이 있으면 사건이 처음부터 취소되지 않아 REVIVE 가 할 일이 없기 때문). `/처치 -forced` 중에는 어떤 부활도 막는다.
- **맞으면 일어나기**(`sit.stand-on-damage`)는 피해가 **실제로 들어갈 때**(MONITOR)만 — 잠수 보호·보호구역 PvP 금지 등으로 막힌 주먹질로는 일어나지 않는다.
- **앉기 좌석**은 보이지 않는 `ItemDisplay`(저장되지 않음)에 PDC `inmcess:seat` 를 단 것이다. 일어나면 지운다. 남은 좌석은 server 기능의 `/유틸` 이 이 열쇠로 치운다.
  일어날 때 바닐라가 세우는 이동(`PlayerTeleportEvent` DISMOUNT)의 도착지를 의자에 앉기 전 자리로 고친다 — 새 이동이 아니라 있던 이동 사건을 고치는 것이라
  이동 기능의 "이동 뒤 무적" 이 걸리지 않는다. `/앉기`(그 자리에 앉기)는 고칠 것이 없다.
  보호 구역 플러그인이 블록 사용을 막은 곳의 의자에는 앉지 않는다.
- **타기 종류 권한**: `inmcessentials.player.ride.<엔티티 열쇠>`(예 `.horse`·`.zombie`·`.player`) 또는 `.ride.all`. 종류 노드는 선언하지 않았으므로 op 는 다 된다.
  `ride.blacklist` 의 종류는 아무도 못 탄다. 이미 누가 탄 것·좌석·나 자신에는 못 탄다.
- **근처**는 같은 월드, 내가 볼 수 있는 사람만(투명·숨김 제외, 관전 모드 제외). `near.invisibility.hide` 면 투명 물약을 먹은 사람을 빼고,
  `obfuscate` 도 켜면 빼지 않되 이름을 알아볼 수 없게(눌러 가리키기도 없음). `/근처 가리키기 <이름>` 을 직접 쳐도 같은 규칙이다.
  `near.approximate` 는 **거리**만 대략으로 보인다 — 가리키기 입자는 방향을 그대로 보여 준다(목록에도 8방위가 나온다 · CMI `/point` 와 같다).

### 플레이스홀더 (PlaceholderAPI 가 있을 때)

| 자리표시 | 값 | CMI |
|---|---|---|
| `%inmcplayer_flying%` · `%inmcplayer_canfly%` | 지금 나는 중 · 날 수 있음(true/false) | `%cmi_user_flying%` |
| `%inmcplayer_god%` | 무적(`/무적` 또는 시간제) | `%cmi_user_god%` |
| `%inmcplayer_tfly_left%` · `%inmcplayer_tgod_left%` | 남은 시간 글(없으면 빈 값 · `0` 이면 "다시 접속할 때까지") | `%cmi_user_tfly%` · `%cmi_user_tgod%` |
| `%inmcplayer_tfly_seconds%` · `%inmcplayer_tgod_seconds%` | 남은 초(없으면 0 · 다시 접속할 때까지면 -1) | — |
| `%inmcplayer_collision%` | 저장된 `/충돌` 값 | — |

접속 안 한 사람은 `flying`·`canfly`·`god` 가 false, 남은 시간은 빈 값이다(시간제 값은 접속해 있는 동안만 메모리에 있다).

## 설정 (`settings/player.yml`)

| 키 | 기본 | CMI |
|---|---|---|
| `maxhp.cap` | 1000 | Optimizations.MaxHp |
| `heal.remove-negative` · `heal.negative-effects` | true · 실명 멀미 즉시피해 허기 독 느림 채굴피로 나약함 시듦 어둠 | heal.RemoveNegative |
| `feed.allow-over-max` | false | hunger.overide |
| `effect.deduct-while-offline` | false | PotionEffects.DeductWhileOffline |
| `god.keep-hunger` | true | — |
| `fly.restore-after-death` · `fly.keep-on-gamemode-change` | false · true | WorldLimits.ReenabledFlyAfterDeath · — |
| `timed.warn-at` · `timed.tfly-landing-protection` | [60,30,10,5,3,2,1] · true | timedCommands |
| `hat.block.with-lore` · `.non-hat-items` · `.non-hat-enchanted` | false · true · true | Optimizations.Hat.Block.* |
| `hat.whitelist` · `hat.inventory-slot` | [] · true | Hat.WhiteList · hat 모듈 (Hat.Allow.MobHeads 는 옮기지 않았다 — 몹 머리는 바닐라가 머리 칸에 받는다) |
| `sit.chairs` · `.stairs` · `.slabs` · `.carpets` | true · true · true · false | Animations.SitOnStairs · StairsAsChairs · SlabsAsChairs · CarpetsAsChairs |
| `sit.stand-on-damage` · `.double-click` · `.double-click-millis` · `.chair-range` · `.height-offset` | true · false · 200 · 4 · 0 | RemoveFromChairOnDamage · DoubleClick · DoubleClickDelay · ChairRange · — |
| `ride.eject-on-suffocation` · `ride.blacklist` · `ride.max-distance` | true · [ender_dragon, wither] · 6 | rideAnimation · — · — |
| `near.default-distance` · `.max-distance` · `.approximate` · `.invisibility.hide` · `.invisibility.obfuscate` · `.point-to` · `.count` | 200 · 1000 · true · false · false · true · 10 | Optimizations.Commands.Near.* |
| `suicide.broadcast` | true | — |
| `totem.from-inventory` · `.require-permission` · `.cooldown-seconds` · `.warmup-seconds` · `.protect-from-void` | false · false · 0 · 0 · true | Totem.RemoveFromInventory · RequirePermission · Cooldown · Warmup · ProtectFromVoid |
| `totem.effects.regeneration` · `.fire-resistance` · `.absorption` | 45 · 40 · 5 | Totem.Effects.* |
| `totem.vanilla-worlds` · `.disabled-worlds` · `.boss-bar` | [] · [] · true | Totem.BlackListedWorlds · FullDisableInWorlds · Defaults.totemBossBar |
| `totem.protect-from-void-skip-worlds` | [inmc_dungeon] | — (허공 구하기를 하지 않는 월드) |
| `hp-bar.enabled` · `.blacklist` · `.seconds` · `.players` · `.skip-custom-mobs` | true · [ender_dragon] · 5 · true · true | BossBar.HpBarEnabled · HpBarBlackList · — (inmc-monster 몹 빼기) |

## 저장

- 표 `ess_player_state(uuid, god, fly, collision, tgod_left, tfly_left, updated_at)` — 기본값과 같으면 줄을 지운다. 접속 직전(비동기)에 읽는다.
- 개인 설정 3개는 core `PlayerSettings`(PlayerStore 네임스페이스 `settings`).
- CMI 가져오기(`/에센셜 가져오기 cmi`):
  - 남은 시간제 비행·무적(초, `users.tFly`·`tGod`) — **우리 쪽에 이미 남은 시간이 있으면 두고**, 0 이하는 건너뛰고, 10년이 넘는 값은 "못 읽음".
  - `/fly` 켜 둠(`users.Flying`) → 비행 저장, 밀기 끔(`users.Collision` 0) → 충돌 끔 — 우리 쪽이 기본값일 때만.
  - 개인 설정(`users.Options`) — `rideMe` → `essentials.ride-me`, `totemBossBar` → `essentials.totem-bossbar`. 다른 CMI 개인 설정은 "건너뜀"(까닭과 함께),
    `이름:값` 으로 읽히지 않는 것은 "못 읽음"(원문과 함께).
  - 접속해 있는 사람은 메모리에 바로 넣고 보고서에 넣음/건너뜀으로 센다. **접속 안 한 사람**은 "비었을 때만 넣기" 로 DB 쓰기 줄에 넘기고 보고서에는
    `…(접속 안 한 사람 — 비었을 때만 넣기로 DB 에 넘김)` 으로 센다 — 실제로 넣은 수와 이미 값이 있어 둔 사람은 서버 로그 `CMI 플레이어 상태 — 넣음 N · 이미 값이 있어 둠 M: …`.
  - 최대 체력은 바닐라 playerdata 에 이미 있어 옮기지 않는다. CMI 의 god 상태는 CMI 가 저장하지 않아 옮길 것이 없다.

## CMI 와 다른 점

- `/maxhp add/take` 는 최종값이 아니라 기본값에서 계산(위). 오프라인 대상은 CMI 처럼 받지 않는다.
- `/heal` 의 `all` 은 볼 수 있는 접속자만.
- `/kill` 은 바닐라 이름이라 `/처치` 만 있다(영어 별칭 없음). 바닐라 `/kill` 은 그대로 무적을 지나간다(무적은 KILL 피해를 막지 않는다 — 관리자 처치가
  먹지 않는 일이 없게). `/처치` 는 무적이면 막는다(`-forced` 로 넘는다). 피해 원인은 바닐라 피해 종류 열쇠(CMI 의 damageCause 이름과 대부분 같다 — `lava`·`fall`·`drown`·`magic`…;
  `fire_tick`·`entity_attack` 처럼 바닐라에 없는 것은 `on_fire`·`mob_attack` 으로).
- `/effect` 는 바닐라 이름이라 `/효과`(peffect). 세기는 1부터(1 = I). 시간 없이 주면 30초.
- 충돌은 `LivingEntity.setCollidable` 이다(스코어보드 팀을 쓰지 않는다 — 타이틀포지·커스텀아이템이 메인 스코어보드 팀을 쓴다). **손님(클라이언트)이 플레이어끼리의
  밀림을 스스로 예측하는 경우가 있어 사람과 사람 사이에서는 완전하지 않을 수 있다**(Paper API 문서의 경고). 몹·엔티티와의 충돌은 확실히 꺼진다.
- `/sit` 의 CMI `on/off` 는 `/앉기 켜기|끄기`, 앉아 있을 때 `/앉기` 는 일어나기. `-persistent` 는 서버를 다시 켜면 잊는다(메모리).
- 좌석 높이는 `sit.height-offset` 으로 맞춘다(바닐라의 탈 것 높이가 버전마다 달라 고정값을 두지 않았다).
- `/ride` 는 바닐라 이름이라 `/타기`(mount). CMI 처럼 바라보는 것에 타고, 이름으로 고르면 가까운 사람만.
- `/near` 의 `/point` 는 `/근처 가리키기`(입자 줄, 나에게만).
- 체력 보스바 권한은 사건마다 본다(CMI 는 1분마다). 권한 기본은 모두(CMI `cmi.bossbar.hpbar` 는 줘야 보였다) — 끄려면 권한을 빼거나 `hp-bar.enabled: false`.
  커스텀 몹은 기본으로 뺀다(inmc-monster 의 `inmc-monster:mob_id` PDC — 몬스터 플러그인 이름이 바뀌면 이 판단이 조용히 꺼진다).
- 검증기(`/에센셜 검증`)는 부활 사건·처치(KILL) 사건을 쏘지 않는다 — 인첸트 REVIVE·DEATH 효과나 전투 기록이 관리자에게 걸리지 않게. 토템·처치는 판단 함수와
  우리 리스너를 직접 부르고, 시간제 끝남은 1초 틱의 한 사람 몫을 직접 돌린다(관리자에게 "끝났습니다" 메시지가 한 번 간다).
  허공 구하기는 이동이 들어가 검증기에 넣지 않았다.
- `/suicide` 방송은 바닐라 사망 메시지와 따로 나온다(끄려면 `suicide.broadcast: false`).
- `/모자`·모자 칸의 귀속 저주 막기는 바닐라와 같다(창작 모드는 바꿀 수 있다). CMI `Hat.Allow.MobHeads` 는 없다(위).
- 의자에서 일어나면 앉기 전 자리로 돌아간다(CMI 는 좌석 자리에 남긴다 — 손이 닿지만 걸어갈 수 없는 의자로 벽·창살을 넘는 것을 막는다).
- 토템 처리는 부활 사건 HIGHEST — 다른 플러그인이 살린 부활(인첸트 REVIVE)에는 쿨타임·가방 토템·금지 월드를 걸지 않는다.
- `sys-hungercache`(CMI 내부 허기 저장)는 따로 두지 않는다 — 배고픔은 바닐라 값이 진실이고, 감옥·인벤 불러오기가 필요하면 그 기능이 직접 읽고 쓴다.

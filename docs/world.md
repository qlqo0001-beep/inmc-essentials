# 월드·날씨·시간·바이옴(world) — 운영 안내

CMI 의 findbiome · gamerule · fixchunk · lfix · killall · weather(sun/rain/storm) · time(day/night) 명령과, 명령 없는 WorldLimits(worldLimits ·
worldLimitsElytra 모듈) · Sleeping.Speedup(nightSpeedup) · NetherPortal(portalCreation) · Optimizations.PreventEntityBoatEnter 를 대신한다.
설정 `plugins/inmc-essentials/settings/world.yml`, 메시지 `messages/world.yml`. DB 는 쓰지 않는다 — 멈춤·날씨 고정은 월드의 게임 규칙에 남는다.

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/바이옴찾기 <생물군계> [-r:반경] [-좌표]` (findbiome) | 가장 가까운 그 생물군계를 찾아 이동. 이름은 **타이틀포지 이름(places.yml) · 공식 한국어 이름 · id**(`plains`·`minecraft:plains`) 다 된다 — 빈칸·밑줄·대소문자 무시. 같은 이름이 여럿(‘관목 지대’ = brushland·shrubland)이면 전부 한꺼번에 찾는다. `-좌표` = 이동하지 않고 좌표만. 진행 상황(TPS·남은 칸·속도)은 행동 표시줄 | `inmcessentials.world.findbiome` (OP) |
| `/바이옴찾기 멈춤` · `전부멈춤` (stop · stopall) | 내 찾기 · 모두의 찾기 멈춤 | `.findbiome` · `.findbiome.stopall` (OP) |
| `/게임규칙 [월드] [규칙] [값] [all]` | 없으면 그 월드의 규칙 전부(기본값과 다른 것은 노란색), 규칙만이면 값·기본값, 값까지면 바꾸기(`all` = 모든 월드). 규칙 이름은 id(`advance_time`)·한국어 이름 | `inmcessentials.world.gamerule` (OP) |
| `/청크복구 [w <월드>] [r <청크 반경>\|g] [c <x:z>]` (fixchunk) | 지역 파일(.mca)을 **읽기만** 해서 서버가 못 읽을 청크를 찾는다. 반경이 없으면(또는 `g`) 월드 전체, 가운데 기본은 내 청크 | `inmcessentials.world.fixchunk` (OP) |
| `/청크복구 상태·일시정지·계속·멈춤·전부멈춤·속도 <초당>·자동속도 <켜기\|끄기>·메시지 <켜기\|끄기>·정보·복구` | stats·pause·continue·stop·stopall·speed·autospeed·messages·info·fix 도 된다. `w <월드>` 로 고른다(하나뿐이면 그것). **복구 = 찾은 청크를 서버가 다시 읽게 해서 새로 만들게 한다 — 그 청크의 건축은 사라진다** | 같음 |
| `/조명수정 [범위] [플레이어]` (lfix) | 둘레 청크(기본 3, 서버 시야 거리까지)의 빛을 다시 계산. 읽혀 있는 청크만 | `inmcessentials.world.lfix` (OP) · 남 `.lfix.others` (OP) |
| `/조명수정 멈춤` · `전부멈춤` | 멈춤 — 바로 빠지고 끝난 청크 수를 알린다. 서버가 1분 동안 한 청크도 끝내지 못하면(월드가 내려감 등) 스스로 멈춘다. 콘솔에서 시작한 것은 진행 상황(TPS·남은 청크·예상 시간)을 콘솔 줄로 | 같음 |
| `/몹제거 [깃발…]` (killall) | 기본은 반경 64 안의 **이름 없는 몬스터**. 깃발 아래 | `inmcessentials.world.killall` (OP) · `-force` 는 `.killall.force` (OP) |
| `/날씨` | 지금 날씨와 남은 시간(고정이면 "고정") — 플레이어는 자기 월드, 콘솔은 전부 | `inmcessentials.world.weather.check` (모두) |
| `/sun` · `/rain` · `/storm` `[고정\|시간] [월드\|all]` | 친 이름이 곧 날씨(`/날씨 맑음…`). **따로 세운 루트**라 바꾸기 권한이 없는 사람의 탭 목록에 뜨지 않는다 | `inmcessentials.world.weather` (OP) |
| `/날씨 <맑음\|비\|폭풍> [고정\|시간] [월드\|all]` | 시간은 `120`(초) · `10m` · `1h` · `1d` · `1w` · `1M` · `1Y` · `30분`. 없으면 바닐라처럼 무작위(설정 `weather.default-duration`). `/sun lock` = `/날씨 맑음 고정`, `/rain 10m` 처럼 별칭이 곧 날씨 | `inmcessentials.world.weather` (OP) |
| `/날씨 고정 [월드\|all]` (lock) | 날씨를 바꾸지 않고 **지금 날씨 그대로** 고정 | 같음 |
| `/날씨 [월드…\|all]` | 그 월드들의 날씨 보기(낱말이 월드 이름뿐일 때만 — `/날씨 10m` 처럼 날씨 없는 시간은 사용법) | 보기 `.weather.check` |
| `/날씨 고정해제 [월드\|all]` | 날씨가 다시 바뀐다 | 같음 |
| `/시간` | 지금 시각 `13:00 · 오후 1:00 · 7000틱` + (멈춤)·(현실 시간) | `inmcessentials.world.time.check` (모두) |
| `/day` · `/night` `[월드\|all] [-smooth]` | 프리셋 낮·밤(`/시간 낮`). 위와 같이 따로 세운 루트 | `inmcessentials.world.time` (OP) |
| `/시간 <시각\|낮\|밤\|아침\|저녁> [월드\|all] [-smooth]` | 시각: `13:00:00` · `13:00` · `1pm` · `오후 1` · `13시 30분` · `13` · `7000t`. 프리셋은 설정 `time.presets`(CMI Day 12:00 · Night 24:00 · Morning 06:00 · Dusk 18:00). `/day`·`/night` = 프리셋. `-smooth` 는 해가 빨리 돌아가며 맞춘다. 시간은 앞으로만 간다 | `inmcessentials.world.time` (OP) |
| `/시간 더하기\|빼기 <양> [월드\|all]` (add · take) | `0:30`(시:분) · `2`(시간) · `1h30m` · `500t` · `30분`. 빼기는 날짜도 거꾸로 | 같음 |
| `/시간 정지\|재개 [월드\|all]` (freeze · unfreeze) | 게임 규칙 `advance_time` | 같음 |
| `/시간 실제 [월드\|all]` (realtime) | 지금 현실 시각으로 한 번 | 같음 |
| `/시간 자동실제 <시작\|정지> [월드\|all]` (autorealtime start/stop) | 그 월드의 시간을 멈춰 두고 `auto-real.interval` 초마다 현실 시각으로 맞춘다. 설정 파일 `time.auto-real.worlds` 에 적혀 재시작해도 남는다. 끄면(또는 설정에서 빼면) 멈춤도 푼다 — "이 기능이 멈췄다" 는 표식을 월드 PDC(`inmcess:time_frozen_by_auto`)에 둬서 재시작 뒤에도 안다. 멈춘 뒤 `/시간 정지`·`재개` 를 직접 쓰면 그 멈춤은 사람의 것이 된다 | 같음 |

### `/몹제거` 깃발

`-monsters` 몬스터 · `-pets` 길들인 것 · `-npc` 주민·떠돌이 상인 · `-animals` 동물(물고기·골렘 포함) · `-ambient` 박쥐 · `-vehicles` 보트·수레 ·
`-named` 이름 붙은 것 · `-withitems` 주운 아이템을 든 것 · `-all` 모든 범주(**보호는 풀지 않는다**) · `-force`(`-f`) 보호 무시 · `-lightning` 번개 연출 · `-list` 지우지 않고 분류별 수·종류 ·
`-s` 조용히 · `-m:zombie,skeleton` 종류 · `-r:30` 반경(최대 `kill-all.max-radius`) · `-w:월드` 월드 전체(반경 없이). 한글도 된다(`-몬스터` `-전부` `-강제` `-목록` `-종류:` `-반경:` `-월드:` …).
`-r` 은 **그 월드에 서 있는 사람의 둘레**다 — 콘솔이거나 `-w` 로 다른 월드를 고르고 `-r` 을 주면 월드 전체로 넓히지 않고 거절한다(`-r` 을 빼면 그 월드에 읽힌 것 전부).

**보호(늘 남김, `-force` 로 무시)**: 이름 붙은 것(`-named` 가 아니면) · 길들인 것(`-pets` 를 **직접** 쓰지 않으면 — `-all` 로는 안 풀린다) · 끈에 묶인 것 ·
주운 아이템을 든 것(`-withitems` 가 아니면) · **inmc 커스텀 몬스터**(엔티티 PDC `inmc-monster:mob_id` 가 비어 있지 않음) · **짐이 든 탈것**(상자 수레·호퍼 수레·상자 보트 —
`remove()` 는 안의 아이템을 없앤다). **늘 빼는 것(`-force` 도)**: 탈것에 탄 것 · 사람이 탄 탈것 · 플레이어 · 갑옷 거치대 · 액자·그림 ·
Citizens NPC(메타데이터 `NPC`) · Shopkeepers 상인(메타데이터 `shopkeeper`). 지우기는 `remove()` — 시체·경험치는 없고, 지운 것의 **짐(상자 수레·말 상자 등)과
몹이 주운 장비(떨굼 확률이 1 보다 큰 칸 — 바닐라가 주운 아이템에 붙이는 표시 2.0)는 그 자리에 떨군다**(죽은 사람의 장비를 주운 좀비를 `-withitems`·`-force` 로 지워도
장비가 사라지지 않게). 태어날 때 입은 장비(0.085)와 **플러그인이 준 반드시 떨구는 장비(정확히 1.0 — inmc-monster `drop-chance: 100`)** 는 떨구지 않는다
(지우기가 장비 농장·공짜 보스 장비가 되지 않게 — 그 장비는 죽여야 얻는다).

## 명령 없는 것

| 무엇 | 설정 | 우회 권한 (기본) |
|---|---|---|
| **월드 제한** — 들어올 때(접속·월드 이동·부활)와 `limits.check-every` 초마다: 게임모드 맞추기 · 비행 끄기(서바이벌·어드벤처, **우리가 끈 비행은 제한 없는 월드로 나가면 돌려준다**) · 무적 끄기 · 지붕 높이 위 비행 막기 · 겉날개 활공 막기(들어오면 겉날개를 가방으로) · 스폰 사유 막기 | `limits.*` (기본 꺼짐 — CMI 와 같다) | `inmcessentials.world.limit.gamemode.bypass` · `.fly.bypass` · `.god.bypass` · `.elytra.bypass` · `.aboveroof` (전부 OP) |
| **잠자기 밤 가속** — 침대에 누운 비율만큼 밤이 빨리 간다. 인원에서 빠짐: 잠수(설정) · 관전 · 투명 · 잠 무시 · 권한. 누가 잠들면 그 월드에 "n/m명 · 몇 명 더"(`inform-delay` 마다). **시간이 멈춘 월드**(`/시간 정지` · 현실 시간 — `advance_time` 끔)는 가속하지 않고(바닐라와 같다), 아침에 비를 걷는 것(`clear-weather`)은 **날씨가 고정되지 않은** 월드에서만 | `sleep.*` | 인원 제외 `inmcessentials.world.sleep.ignore` (기본 없음) |
| **네더 포탈** — 불로 여는 포탈을 막거나 크기를 제한(월드별 덮어쓰기 `nether-portal.worlds`), 만들면 크기를 행동 표시줄로 | `nether-portal.*` | `inmcessentials.world.netherportal.bypass` (OP) |
| **몹 탈것 막기** — 몬스터·동물·주민이 보트·수레에 타지 못하게 | `entity-vehicle.*` | — |

바닐라 `playersSleepingPercentage`(바로 넘기기)는 그대로 돈다 — 그 비율에 닿으면 바닐라가 바로 넘기고, 그 아래에서 우리가 빨리 돌린다. `sleep.skip-at` 은
우리 쪽의 바로 넘기기(기본 끔).

## 설정 요점

- 월드 이름은 대소문자를 가리지 않는다. 목록 줄의 서버에 없는 월드는 그냥 쓰이지 않는다.
- `find-biome`: 반경 6400(최대 20000) · 간격 32 · 동시 2명(사람마다 하나) · 최대 120초.
- `fix-chunk.speed` 는 초당 검사 청크 수 — `auto-speed` 면 TPS 18 아래에서 반, 15 아래에서 10분의 1.
- `/에센셜 가져오기 cmi` 가 CMI config.yml 의 WorldLimits · Sleeping.Speedup · NetherPortal · PreventEntityBoatEnter · Time 을 이 파일에 옮겨 적는다
  (서버에 없는 월드를 가리키는 줄 — CMI 기본값의 `testWorld`·`worldName` — 은 건너뛰고 보고서에 적는다). 읽기와 보고서 줄은 명령을 친 그 자리에서 — 보고서
  파일·요약에 `월드 설정` 줄과 못 읽은 줄이 함께 나온다.

## 메모

- **바이옴 찾기**: 워커 스레드에서 노이즈만 계산한다(청크를 읽지 않는다). 찾으면 그 청크를 비동기로 읽어 **실제 블록의 생물군계를 다시 확인**하고 설 자리를 찾는다 —
  못 찾으면 이어서 찾는다(8번까지). 동굴 생물군계(`…caves`·`deep_dark`·데이터팩 `cave/…`)는 지표가 아니라 찾은 높이 근처의 빈 곳으로 보내고, 깊은 어둠이면 경고.
  **지붕이 있는 월드**(네더)는 모든 생물군계를 동굴처럼 — 기반암 천장 아래(발 높이 121까지)의 빈 곳으로만 보낸다(천장 위로 보내지 않는다).
  생성기가 붙은 월드(던전)는 거부한다. 이동은 teleport 기능을 거친다(종류 BIOME — 뒤로 기록·대기·쿨타임은 거기와 commands 기능). 데이터팩 생물군계도 서버
  레지스트리에 있으므로 함께 찾는다(테섭에는 데이터팩 생물군계가 없어 실제로 시험하지는 못했다).
- **청크 복구**: Paper 는 `World.regenerateChunk` 를 지원하지 않는다("Not supported in this Minecraft version"). 대신 서버(Moonrise)는 못 읽은 청크를 새로 만든다 —
  그래서 복구는 찾은 청크를 비동기로 읽고 내려놓기다. 멀쩡한 청크를 읽어도 아무 일이 없으므로 검사가 틀려도 해가 없다. Leaf 의 linear 지역 형식은 검사하지 못한다.
  지역 폴더 찾기(파일 나열)도 워커에서 한다 — 지역 파일이 수만 개인 월드에서도 메인이 멈추지 않는다.
  압축: zlib·gzip·없음·LZ4(서버의 lz4-java)·바깥 파일(.mcc).
- **조명 수정**: Bukkit API 에 없어 서버 내부(Paper `/paper fixlight` 와 같은 길)를 리플렉션으로 부른다. 막히면 "쓸 수 없음" 과 까닭을 알린다 — 그때는 `/paper fixlight`.
  작업은 월드를 이름으로만 들고 있다(내려간 던전 월드를 붙들지 않는다).
- **청크 복구 알림**: 복구를 부른 사람마다 따로 세어, 그 사람의 청크를 **다 읽은 뒤** "다시 읽었습니다" 를 보낸다(둘이 겹쳐도 각자 받는다).
- **현실 시간 표식**은 켤 때뿐 아니라 나중에 읽히는 월드(inmc-worlds 가 불러오는 월드·던전)에서도(`WorldLoadEvent`) 본다 — 목록에서 뺀 월드의 멈춤이 남지 않게.
- **무적 끄기**(월드 제한)는 접점 `ess.abilities.setGod(player, false)` 로 player 기능에 맡긴다(무적 상태의 주인이 그 기능이다 — **시간제 무적도 함께 끈다**, 저장도 그 기능이.
  2026-10-14 — 전에는 그 기능의 메서드를 이름으로 부르고 없으면 콘솔 명령을 쳤다). player 기능이 꺼져 있으면 아무도 무적이 아닌 것으로 본다. 끄고도 무적이 남으면(다른 플러그인의
  무적) 그 사람이 그 월드에 있는 동안은 다시 시도하지 않고 로그 한 줄만 남긴다(5초마다 알림이 쌓이지 않게).
- **비행 돌려주기**: 비행 금지 월드에서 우리가 끈 비행은 제한 없는 월드로 나가면 돌려준다 — 단 player 기능이 아직 날게 해 주는 사람(`/비행` 켬 또는 시간제 비행이 남음)에게만.
  그 사이 시간제 비행이 끝났거나 `/비행 끄기` 를 받았으면 돌려주지 않는다(player 기능이 꺼져 있으면 예전처럼 돌려준다).
- 부드러운 시간 전환·밤 가속은 1초 틱으로 돈다 — 해가 1초마다 조금씩 뛴다(CMI 는 매 틱).

## CMI 와 다른 점

- `/cmi time` 의 `TimeSpeed`(낮·밤 길이)는 이 기능에 없다(별도 항목).
- 날씨 별칭 `/sun`·`/rain`·`/storm`, 시간 별칭 `/day`·`/night` 은 있지만 `/morning`·`/dusk` 는 없다(`/시간 아침`·`/시간 저녁`). 이 다섯은 별칭이 아니라
  따로 세운 루트(바꾸기 권한만 — 보기 권한만 있는 사람의 탭 목록에 뜨지 않게). `/weather`·`/time`·`/gamerule` 은 바닐라 명령 그대로 둔다(이름을 덮지 않는다).
- `/몹제거` 의 `-r` 은 가운데가 없으면(콘솔 · 다른 월드) 거절한다 — CMI 는 반경을 무시했다.
- 바이옴 찾기 진행 상황은 채팅이 아니라 행동 표시줄.
- 월드 제한의 비행을 제한 없는 월드로 나가면 돌려준다(아직 날 자격이 있을 때).
- 죽은 뒤 비행 되돌리기(CMI `WorldLimits.ReenabledFlyAfterDeath`)는 player 기능이 늘 한다 — 옮길 설정이 없고, 가져오기 보고서에 값과 함께 적힌다.
- CMI `Settings/Modules.yml` 에서 꺼 둔 모듈(worldLimits · worldLimitsElytra · nightSpeedup · portalCreation)은 가져오기가 우리 쪽도 끈다(보고서에 적힘).
- `/몹제거 -all` 은 길들인 것·짐 실은 탈것을 지우지 않는다(CMI 는 지웠다). Shopkeepers 상인은 늘 뺀다.
- `/날씨 고정`(날씨 없이)은 지금 날씨 그대로 고정한다.
- 청크 복구의 "손상" 판정은 우리가 파일을 읽어 본 결과다(서버가 읽다 실패하는 모양 — 머리표·길이·압축·NBT 구조).

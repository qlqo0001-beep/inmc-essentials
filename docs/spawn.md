# 스폰·첫 접속(spawn) — 운영 안내

CMI 의 setspawn · spawn · setfirstspawn · `Spawn`(SpawnOnJoin·IgnoredWorlds·Main·FirstSpawn) · `ReSpawn`(부활 순서·부활 무적) ·
`Optimizations.ImmortalityOnJoin`(접속 무적) · `Newbie.Kit`(첫 접속 킷)을 대신한다. **inmc-menu 의 스폰(`/스폰`·`/스폰 설정`·`/스폰 관리`)을 그대로
옮겨 왔다**(2026-10-10 — 메뉴에서는 뺐다). 첫 접속 **메시지**는 타이틀포지가 한다(여기 없음).

- 데이터: `plugins/inmc-essentials/spawn.yml` — 서버 스폰·첫 스폰·접속 때 보내기·부활 순서·부활 워프·흩는 반경. 게임 안 `/스폰 관리` 에서 고친다.
  손으로 고쳤으면 바로 `/에센셜 리로드`(파일 시각이 바뀌었으면 I/O 워커에서 다시 읽어 메인에서 끼운다 — 그 전에 게임 안에서 고치면 손으로 고친 것이 덮이고,
  읽는 사이에 고쳤으면 반영하지 않고 로그에 남긴다 — 다시 리로드).
- 운영 설정: `settings/spawn.yml` — 접속·부활 무적 초, 부활 처리 켜기, 첫 접속 킷, 매 접속에서 뺄 월드. `/에센셜 리로드`.
- 메시지: `messages/spawn.yml`.

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/스폰` (spawn) | 서버 스폰으로(흩는 반경이 있으면 그 둘레) | `inmcessentials.spawn.use` (모두) — 옛 `inmcmenu.spawn` 도 인정 |
| `/스폰 <플레이어> [-s]` | 남을 서버 스폰으로. `-s` = 그 사람에게 알리지 않음 | `inmcessentials.spawn.others` (OP) |
| `/스폰 설정` | 선 자리를 서버 스폰으로 | `inmcessentials.spawn.admin` (OP) — 옛 `inmcmenu.admin` 도 인정 |
| `/스폰 처음설정` | 선 자리를 첫 스폰으로(처음 들어온 사람이 서는 곳 — CMI setfirstspawn). 지우기는 관리 화면 | `inmcessentials.spawn.admin` (OP) |
| `/스폰 관리` | 관리 화면 | `inmcessentials.spawn.admin` (OP) |

그 밖의 권한: `inmcessentials.spawn.joinbypass` (기본 없음) — '매 접속 → 서버 스폰' 에서 빠진다(나간 자리로 돌려보낸다). CMI `cmi.spawnonjoin.bypass` 대신.

### 옛 권한(inmc-menu) — 넘어가는 동안

`inmcmenu.spawn`(쓰기)·`inmcmenu.admin`(관리)도 함께 인정한다. 단 **`inmcmenu.spawn` 을 명시적으로 끈(false) 사람은 막는다** — 감옥·행사 그룹에서 옛 노드를
꺼 두었으면 새 노드(기본 TRUE)에 묻히지 않게. 다 옮겼으면 그 그룹에서 `inmcessentials.spawn.use` 를 false 로 바꾸고 옛 노드를 지우세요.

### `/스폰` 대기·쿨타임·비용

이 기능에는 없다 — **commands 기능의 명령 설정에서 명령 이름 `스폰`(별칭 `spawn` 도)에 건다.** 옮기기 전(inmc-menu)의 기본은 **대기 3초**
(움직여 블록이 바뀌거나 맞으면 취소), 쿨타임 0 이었다 — 같은 값을 권한다. 이동이 성공한 순간 `ess.gate.commit` 을 부르므로 "성공해야 쿨타임" 으로
걸 수 있다. 처음 켤 때 inmc-menu 의 `spawn.yml` 에 대기·쿨타임이 있었으면 서버 로그에 그 값을 한 줄 남긴다.

**권장 설정(commands 기능 `rules`)**: 명령 `스폰` — 대기 3초(움직이거나 맞으면 취소) · 쿨타임 0 · `commit-on-success: true`.

Shift+F 메뉴의 스폰 버튼(`player: 스폰`)은 2026-10-10 부터 `/스폰` 을 **친 것으로** 부른다(명령 전처리 사건을 쏘고 취소되지 않으면 실행 — `player.chat`
이 아니라 채팅을 '숨김' 으로 둔 사람도 된다) — 위 대기가 버튼에도 걸린다.
다른 플러그인이 `performCommand` 로 부르는 `/스폰` 은 명령 전처리 사건이 없어 대기가 걸리지 않는다.

## 관리 화면 (`/스폰 관리`)

| 칸 | 하는 일 |
|---|---|
| 서버 스폰 | 클릭 = 선 곳으로 · 우클릭 = 가 보기 |
| 첫 스폰 | 클릭 = 선 곳으로 · 우클릭 = 가 보기 · Shift+클릭 = 지우기(서버 스폰을 씀) |
| 처음 접속 → 첫 스폰 | 처음 들어온 사람을 첫 스폰(없으면 서버 스폰)에서 시작 |
| 매 접속 → 서버 스폰 | 들어올 때마다 서버 스폰에서(CMI SpawnOnJoin). `ignored-worlds` 의 월드에서 나간 사람·우회 권한은 나간 자리 |
| 흩는 반경 | `/스폰`·부활(서버 스폰 칸) 때 서버 스폰 둘레 N블록 안 아무 곳(땅 위로 — CMI Rng). 접속 자리에는 쓰지 않는다. `/스폰` 은 고른 칸의 청크를 비동기로 올린 뒤, 부활은 이미 올라와 있는 칸에서만 고른다(메인 스레드에서 청크를 읽거나 만들지 않는다). 맨 위가 물·용암·마그마·선인장 등이거나 그 위 두 칸(발·머리)에 불·가시덤불·위더 장미·가루눈·고인 물이 있으면 다른 칸, 몇 번 해서 안 되면 서버 스폰 그 자리 |
| 대기·쿨타임 | 안내만(위 절) |
| 부활 워프 | 부활 순서의 '부활 워프' 칸이 보낼 워프(권한·숨김 무시 — CMI `warp!이름`). 워프 이름을 바꾸면 따라 바뀌고, 지웠거나 월드가 없으면 빨갛게 보인다 |
| 부활 순서 7칸 | 클릭 = 앞으로 · 우클릭 = 뒤로 · Shift+클릭 = 켜고 끄기 |

## 부활 순서

죽으면 왼쪽부터 처음 되는 곳으로. 꺼진 칸은 건너뛰고 다 안 되면 바닐라 그대로. **엔드에서 돌아올 때(END_PORTAL)는 건드리지 않는다.**
감옥에 있는 사람(moderation 기능)은 건드리지 않는다 — 감방으로 부활시키는 것은 moderation 의 몫.

| 칸 (`respawn-order` 키) | 되는 때 |
|---|---|
| 침대 (`bed`) | 바닐라가 침대로 부활시킬 때 — 그대로 둔다 |
| 리스폰 앵커 (`anchor`) | 바닐라가 앵커로 부활시킬 때 — 그대로 둔다 |
| 서버 스폰 (`server`) | 서버 스폰을 정했고 그 월드가 있을 때 |
| 침대 홈 (`bed-home`) | 침대를 눌러 만든 홈이 있고 침대가 남아 있을 때(homes 기능) — CMI bedLocation |
| 기본 홈 (`home`) | 기본 이름(`settings/homes.yml default-name`)의 홈, 없으면 가장 먼저 만든 홈(homes 기능) — CMI homeLocation |
| 부활 워프 (`warp`) | 부활 워프를 정했고 그 워프가 있을 때(warps 기능) |
| 월드 스폰 (`world`) | 늘 — 죽은 월드의 스폰(네더·엔드면 기본 월드) |

기본값: 침대 · 앵커 · 서버 스폰 · (침대 홈 · 기본 홈 · 부활 워프 — 꺼짐) · 월드 스폰. inmc-menu 의 옛 파일(4칸)은 새 3칸이 꺼진 채 붙는다.

## 매 접속 → 서버 스폰 의 우회

우회 권한은 들어오기 전 사건(`AsyncPlayerSpawnLocationEvent`)에서 볼 수 없어 일단 서버 스폰으로 옮기고, 들어온 직후 **원래 접속 자리**로 되돌린다.
원래 자리는 우리가 바꾸기 전의 접속 자리(서버가 저장해 둔 나간 자리 — 서버가 튕긴 뒤에도 맞다)를 기억해 쓴다. 뒤의 플러그인(던전의 복귀 자리 등)이 접속
자리를 다시 정했으면 되돌리지 않는다(그 결정을 따른다).

## 첫 접속

- 판정: 바닐라(`AsyncPlayerSpawnLocationEvent.isNewPlayer` — 이 서버에서 논 적 없음)로 **첫 스폰**에 세운다.
- **첫 접속 킷**(`first-join-kit: true`, kits 기능이 켜져 있을 때 `ess.kits.giveFirstJoin`)은 바닐라 판정과 에센셜 기록(`ess_users` 에 처음)이
  **둘 다** 처음일 때만 준다 — 이 플러그인을 새로 깐 날 모든 사람이 "처음" 으로 보여 킷을 받는 일이 없게. 둘이 다르면 서버 로그에 한 줄.
  어떤 킷인지·한 번만 주기는 kits 기능의 몫.

## 무적

- 접속 직후 `join-invulnerable-seconds`(기본 3), 부활 직후 `respawn-invulnerable-seconds`(기본 3) 동안 피해를 막는다(최대 60, 0 = 끔).
  공허·`/kill`·자살·월드 경계 피해는 막지 않는다. 플레이어 데이터에 무적 표시를 쓰지 않는다(메모리만 — 서버가 죽어도 남지 않음).

## 사건 우선순위 (다른 플러그인과)

- 접속 자리 `AsyncPlayerSpawnLocationEvent` **HIGH**, 부활 `PlayerRespawnEvent` **HIGH** — 던전(`GuardListener`·`RunListener`, HIGHEST)이 뒤에서 이긴다.
- CMI·Multiverse 도 부활 자리를 고친다. 같은 HIGH 면 켜진 순서로 갈리므로 **바꾸는 날 CMI `ReSpawn.Enabled: false` · `Spawn.FirstSpawn.Use: false` ·
  `Newbie.Kit` 비우기 · `Optimizations.ImmortalityOnJoin: 0` · Modules `homeInteractions` 끄기**, Multiverse 는 inmc-worlds 로 바꿨다(2026-10-15) — 모드가 있으면 스폰은 그 사람 모드의 스폰(`/스폰 설정` = 선 곳의 모드 스폰), inmc-worlds 는 다른 모드 월드로 부활하려 할 때만 고친다.
  `/에센셜 검증` 의 "부활 자리를 덮는 다른 플러그인이 없다" 가 부활 사건의 등록 목록(HIGH·HIGHEST 의 다른 플러그인 — 던전 빼고)을 본다
  (진짜 부활 사건은 쏘지 않는다 — 다른 플러그인의 부활 처리가 관리자에게 일어나지 않게).
- inmc-menu 의 옛 판(스폰을 아직 가진 판)이 같이 켜져 있으면 켤 때 경고한다 — 스폰을 뺀 inmc-menu 와 같은 재시작에 넣을 것.

## 데이터 옮기기 (처음 켤 때 한 번)

1. `spawn.yml` 이 없고 `plugins/inmc-menu/spawn.yml` 이 있으면 **복사**한다(형식이 같다 — 옛 파일은 지우지 않는다).
2. 서버 스폰이 여전히 비었으면 `plugins/CMI/config.yml` 의 `Spawn.Main.Location`(과 `FirstSpawn.Use: true` 면 `FirstSpawn.Location` — 서버 스폰과 다를 때만 첫 스폰으로)을
   한 번 읽고 `cmi-imported: true` 를 적는다(다시 덮지 않는다). 이때 우리 자료가 비었으므로 **매 접속(`Spawn.SpawnOnJoin`)·흩는 반경(`Spawn.Main.Rng`)·
   부활 순서(`ReSpawn.Global.PriorityOrder` — anchor→앵커, bedLocation→침대, spawn→서버 스폰(`Main.RespawnLocation` 이 false 면 끈 채로), homeLocation→기본 홈,
   worldSpawn→월드 스폰, `warp!이름`→부활 워프)** 도 옮긴다. 운영 설정에 해당하는 것(`IgnoredWorlds`·`ReSpawn.Immortality`·`ImmortalityOnJoin`·`ReSpawn.Enabled`)은
   우리 값과 다르면 서버 로그에 한 줄씩 남긴다(`settings/spawn.yml` 은 고쳐 쓰지 않는다 — 운영자의 주석이 지워지지 않게).
3. `/에센셜 가져오기 cmi`(server 기능) 때도 서버 스폰·첫 스폰이 **비어 있을 때만** 넣는다. 위 설정들은 우리 값과 견준 안내를 보고서에 남긴다(덮지 않는다).

## CMI 와 다른 점

- 권한 그룹별 스폰(`setspawn -g:`)·월드별 스폰 이동(`-w:` TeleportFrom)·월드별 부활 순서(`ReSpawn.Specific`)는 없다 — 테섭은 쓰지 않았다(월드별 순서 = 전역 순서).
- CMI `RespawnLocation`(서버 스폰을 부활에 쓸지)은 부활 순서의 '서버 스폰' 칸 켜고 끄기로 대신한다.
- 매 접속 우회 권한은 들어오기 전 사건에서 볼 수 없어(권한이 아직 없다) 들어온 직후 나간 자리로 **되돌려 보낸다**(잠깐 보일 수 있다).
- 흩는 반경은 접속 자리에 쓰지 않는다(비동기 사건에서 땅 높이를 볼 수 없다). 부활 때는 이미 올라와 있는 청크에서만 고른다 — 주변이 비어 있으면 서버 스폰 그 자리.

# inmc-essentials

**CMI 대체 플러그인.** 이동·홈·워프·스폰·채팅·우편·플레이어 명령·잠수·아이템·인벤·킷·월드·꾸밈·포탈·홀로그램·명령 체계·서버 관리·제재·관리자 정보를
**기능 19개**로 나눠 하나의 플러그인에 담는다. **무엇을 넣는지(범위)는 `../CMI-대체-범위.md`**, 명령 이름 표·작업 나눔·왜 그렇게 정했나는
`~/.claude/plans/inmc-essentials.md`(4절·6절). 사용법·전환일 점검표는 `GUIDE.md`, 바꾼 것은 `CHANGELOG.md`, 작업 규칙은 `CLAUDE.md`,
**기능마다의 운영 문서는 `docs/<기능>.md`**(아래 표).

> 2026-10-14 기능 19개 구현·검토 2차·통합 끝. 테섭 적용(CMI 가져오기 → CMI 제거)은 `GUIDE.md` "바꾸는 날" 대로.

## 요구 사항

| 항목 | 값 |
|---|---|
| 서버 | Paper/Leaf 26.2 (`api-version` 은 빌드가 넣는다) |
| **필수 의존** | **`inmc-core`** (Kotlin 런타임 · 보상/우편함/순위 · `Mail`·`Vanish`·`Selections`·`ChatVisibility` 창구 · `CustomItemHook.ids()`/`makeOnlyNamespaces()`) — **core 와 같은 묶음으로 배포**(옛 core jar 와 섞으면 `NoSuchMethodError`) |
| 선택 연동 | PlaceholderAPI(컴파일 의존 — 식별자 `inmcplayer`·`inmcchat`·`inmcmod`) · LuckPerms · WorldEdit/FAWE · WorldGuard · Lands · VotifierPlus/NuVotifier · CoreProtect · InMc-TitleForge · inmc-monster(PDC 읽기만) · inmc-dungeon — 전부 리플렉션(`ReflectionPurityTest`). 모드(inmc-worlds)는 core `Realms`·월드·생물군계 이름은 core `PlaceNames` 로 |
| 데이터 | `plugins/inmc-essentials/` — `config.yml` · `messages/*.yml` · `settings/<기능>.yml` · `data/essentials.db`(SQLite, `storage.type: mysql` 이면 공용 DB) · `commands/aliases.yml`·`schedules.yml` · `warps.yml` · `spawn.yml` · `portals.yml` · `holograms.yml` · `jails.yml` · `text/`·`books/` · `verify/` · `import/` · `logs/` |

## 기능 19개

| id | 패키지 | 무엇 | 문서 |
|---|---|---|---|
| teleport | `teleport` | `/이동`·`/소환`·강제·전원·월드전원·좌표 · 이동 요청(tpa/tpahere/tpaall)·수락·거절·차단 · `/뒤로`·`/사망지점` · 대기 뒤 재확인 · 금지 아이템 · 이동 뒤 무적 · 탑승 동반 · 나간 사람의 다음 접속 이동 | [docs/teleport.md](docs/teleport.md) |
| homes | `home` | `/홈`·`/홈설정`·`/홈삭제`·`/홈목록` — 개수 그룹·월드 한도·보호구역(WorldGuard/Lands)·침대 홈·화면·남의 홈·`near:` | [docs/homes.md](docs/homes.md) |
| warps | `warp` | `/워프`·`/워프설정`·`/워프삭제`·`/워프편집`·`/워프그룹` — 그룹·권한·아이콘·화면·`warps.yml` | [docs/warps.md](docs/warps.md) |
| spawn | `spawn` | `/스폰`(설정·처음설정·관리) · 접속·첫 접속·부활 자리(홈·워프 창구) · 접속 무적 · 첫 접속 킷 호출 | [docs/spawn.md](docs/spawn.md) |
| chat | `chat` | `/귓`·`/답장`·`/무시`·`/도움요청`·`/스태프`·`/스파이`·`/공지`·`/채팅끄기`·`/타이틀` · 거리 채팅·외침(`!`)·도배·링크 축약·바닐라 msg 가로채기 | [docs/chat.md](docs/chat.md) |
| mail | `mail` | `/우편`(받기·읽기·보내기·임시·첨부·지우기·보기·비우기)·`/우편전체` — **서버의 유일한 우편함**(core `Mail` 공급처) | [docs/mail.md](docs/mail.md) |
| player | `player` | 게임모드·경험치·최대체력·회복·배고픔·효과·처치·자살·속도·무적/시간제·비행/시간제·충돌·토템·타기·내리기·모자·앉기·근처·핑 · 체력 보스바 · PAPI `inmcplayer` | [docs/player.md](docs/player.md) |
| afk | `afk` | `/잠수`·`/잠수확인` — 자동 잠수·제목·진입/해제 명령·잠수 중 보호·우회 막기·자동 추방·몹 생성 막기 | [docs/afk.md](docs/afk.md) |
| items | `item` | `/지급`·`/전원지급`·`/머리`·`/몹머리`·`/장식`·`/이름`·`/설명`·`/염색`·`/비우기`·`/검색` · 가상 작업대 7종·`/거래창` · 셜커 배낭 | [docs/items.md](docs/items.md) |
| inventory | `inventory` | `/인벤`·`/엔더`·`/엔더비우기`·`/인벤저장`·`/인벤목록`·`/인벤불러오기`·`/인벤삭제`·`/몰래열기` · 오프라인 편집(대기 편집)·나갈 때 스냅샷·가방 칸(모드) | [docs/inventory.md](docs/inventory.md) |
| kits | `kit` | `/킷`·`/킷편집`·`/킷초기화` — 쿨타임·횟수·비용·조건·명령·그룹과 무게·첫 접속 킷·화면 | [docs/kits.md](docs/kits.md) |
| world | `world` | `/바이옴찾기`·`/게임규칙`·`/청크복구`·`/조명수정`·`/몹제거`·`/날씨`(sun·rain·storm)·`/시간`(day·night) · 월드 제한(게임모드·비행·무적·지붕·겉날개·몹 스폰) · 잠자기 가속·현실 시간 | [docs/world.md](docs/world.md) |
| decor | `decor` | `/갑옷거치대`(편집기·템플릿)·`/표지판편집`·`/표지판복사`·`/동적표지판`·`/액자` · 그림 편집 · 웅크림 표지판 편집 · 바닐라 편집 화면 제어 | [docs/decor.md](docs/decor.md) |
| portals | `portal` | `/포탈` — 영역(WorldEdit 선택)·목적지·안전 위치·권한·명령·입자·쿨타임·탈것 동반 · `portals.yml` | [docs/portals.md](docs/portals.md) |
| holograms | `hologram` | `/홀로그램` — TextDisplay 줄·아이콘 줄·쪽·누르기 동작·거리·개인 보기 · `holograms.yml` | [docs/holograms.md](docs/holograms.md) |
| commands | `commands` | `/별칭`·`/일정` · 한/영 자판 변환·탭 완성 · 대기·쿨타임·비용("성공해야 쿨타임") · 비슷한 명령 안내 · 이벤트 명령 · `inmcalias:` 등록 | [docs/commands.md](docs/commands.md) |
| server | `server` | `/서버상태`·`/점검`·`/최대인원`·`/서버소개`·`/플레이스홀더`·`/책`·`/텍스트`·`/서식메시지`·`/유틸` · `/에센셜 데이터 이전`·**`/에센셜 가져오기 cmi`**·`/에센셜 로그` | [docs/server.md](docs/server.md) |
| moderation | `moderation` | 밴·기간밴·IP밴·밴해제·밴목록·밴확인·추방 · 뮤트·그림자뮤트·채팅잠금 · 감옥(편집·목록·석방) · 경고(분류·점수) · 수갑·경보·메모·부계정·같은아이피·아이피잠금·순찰·대신실행·유저메타 · 데이터 도구(교환·삭제·열 초기화·미접속 정리·옛 유저) · PAPI `inmcmod` | [docs/moderation.md](docs/moderation.md) |
| admininfo | `admininfo` | `/투명`·`/투명설정` · 플레이 시간(순위·상세·편집·보상) · 투표(순위·편집·보상, Votifier) · `/정보`·`/마지막접속`·`/최근접속`·`/실명`·`/목록`·`/오피목록`·`/권한확인`·`/통계`·`/통계편집` | [docs/admininfo.md](docs/admininfo.md) |

## 설계

```
com.inmc.essentials
├─ EssentialsPlugin   배선만 (켜기·끄기·리로드 · 켜다 실패한 기능 되돌리기)
├─ Ess                로케이터 — core RewardHost (보상·우편함·순위) + 기능 사이 접점 여섯
├─ feature/           Feature(틀) · Features(고정 목록 19) · Perm
├─ command/           CommandRegistry(모았다가 COMMANDS 사건에 등록) · Args(사람 인자) · AdminCommand(/에센셜)
├─ config/            EssConfig(config.yml) · Messages(공통 + 기능별 파일을 합친 카탈로그 하나)
├─ store/             EssDb(core SqlWorker + 덮어쓰기 문장) · UserService·UserIndex(ess_users — 온 사람 전부, 메모리)
├─ service/           Teleporter·TeleportKind · AfkState · KitsApi · CommandGate · ModerationState · Abilities · CmiImport(CmiImportable·CmiData·ImportReport)
├─ gui/Menu           core Menu 에 Ess 를 붙인 층
├─ scheduler/Ticker   반복 태스크 하나 — 기능이 add(이름, 주기초) 로 건다
├─ util/Ph            토큰 주머니
├─ verify/Verifier    /에센셜 검증 — 뼈대 검사 + 기능마다 checks() (전부 ~290개)
└─ <기능 패키지>/      teleport home warp spawn chat mail player afk item inventory kit world decor portal hologram commands server moderation admininfo
```

- **기능 = `Feature`**: `id` · `permissions`(켤 때 등록) · `messageDefaults`(`<id>-` 접두) · `hasSettings` · `schema(sql)` · `load(settings)` · `enable()` ·
  `ready()` · `disable()` · `checks()`. `config.yml modules.<id>: false` 면 표만 만들고 아무것도 하지 않는다(재시작 반영).
- **켜는 순서**: config → 메시지 → DB(모든 기능의 표 + `ess_users`, 사람 기록 전부 읽기) → 기능 `load` → `enable` → 명령 등록(Paper `COMMANDS` 사건은
  `onEnable` 뒤에 온다 — 모아 둔 것을 그때 전부, 명령 다시 읽기 때마다 다시) → 틱 → 첫 틱에 순위·우편함 읽기 → 기능 `ready` → 준비 끝.
  끄기는 기능 `disable`(거꾸로) → 접속자 나감 기록 → 틱 → 권한 → 순위·우편함 → DB → I/O. 기능 하나가 만들다·켜다 던지면 그 기능만 빠진다 —
  켜는 동안 들어온 명령·리스너·틱·권한에 주인(`ess.owner`)이 적혀 있어 그 기능 몫을 거두고, 바꿔 끼운 접점·순위 대상을 되돌리고, `disable()` 을 부른다.
  `ess.features` 는 하나 켜질 때마다 늘어난다 — `enable()` 안의 `ess.enabled(id)` 는 앞선 기능만 true(기능끼리 엮기는 `ready()` 에서).
- **기능 사이 접점**(`Ess`, 주인이 `enable()` 에서 끼우고 `disable()` 에서 되돌린다 — `CLAUDE.md` 6):
  `teleporter`(teleport — **모든 이동**) · `afk`(afk) · `kits`(kits — 첫 접속 킷) · `gate`(commands — "성공해야 쿨타임") · `moderation`(moderation — 뮤트·감옥·수갑 상태) ·
  `abilities`(player — 무적·비행·시간제, 2026-10-14). core 창구: `Mail`(공급처 = mail) · `Vanish`(상태의 주인 = admininfo) · `ChatVisibility`(공급자 = moderation) · `Selections`.
  홈·워프 → 스폰은 `spawn/SpawnLinks.kt` 의 `SpawnHomes`·`SpawnWarps`(유일한 패키지 사이 import 예외 — 방향은 홈·워프 → 스폰).
- **저장소**: core `SqlWorker` — 로컬 `data/essentials.db`, `storage.type: mysql` 이면 공용 DB. 표 접두 `ess_`. 쓰기는 스레드 하나, `REPLACE INTO` 대신
  `EssDb.upsertSql`(SQLite `ON CONFLICT DO UPDATE` / MySQL `ON DUPLICATE KEY UPDATE`). 아이템·돈·1회용 기록(우편·킷 사용·인벤 저장·밴)은 바뀌는 순간 쓴다.
  표: `ess_users` · `ess_teleport_{spots,tpa,blocks,pending}` · `ess_homes` · `ess_chat_{ignores,spies}` · `ess_mail`·`ess_mail_keys` · `ess_player_state` ·
  `ess_inventory_{snapshots,pending}`·`ess_saved_inventories` · `ess_kits`·`ess_kit_usage` · `ess_commands_cooldowns` ·
  `ess_moderation_{mutes,jailed,release,warnings,cuffs,alerts,notes,ips,lockip,usermeta,pending}` ·
  `ess_admininfo_{vanish,playtime,playtime_day,playtime_edit,rewards,preward,votes,vote_log,vote_milestone}`(`playtime` 은 늘 로컬 DB).
  DB 열 계약(데이터 도구가 사람을 바꾸거나 지울 때): `uuid`·`owner`·`player` 또는 `_uuid` 로 끝나는 열 = 그 사람. 다른 사람을 가리키는 열은 그 이름을 쓰지 않는다.
- **`ess_users`**: uuid · name · name_lower · first_join · last_join · last_quit · last_ip · 나간 자리. 접속(LOWEST)·나감(MONITOR)에서 고친다. 서버를 끌 때는
  `onDisable` 에서 접속자 전부를 나간 것으로 적는다. 전부 메모리라 오프라인 이름 찾기가 메인에서 DB 를 기다리지 않는다(`UserIndex`). 공용 DB 면 메모리에
  없는 사람의 줄을 접속 직전(비동기)에 한 번 읽는다.
- **사람 인자**(`Args`): 빈칸까지 한 낱말(한글 닉네임·`*`·`전원` 도 — 손님에게는 `word()` 로 보인다), 제안은 보이는 접속자만(투명).
  접속자 찾기(`online`)는 정확히 → 대소문자 무시 → 앞부분 하나 → 들어 있는 것 하나 → TitleForge 닉네임.
  오프라인 찾기(`offline`)는 **정확한 이름이 먼저** — 보이는 접속자의 정확한 이름 → 기록의 정확한 이름 → 접속자 부분 이름 → 닉네임(2026-10-14).
- **보상**: core `RewardService`·`Mailbox`·`RankService` — 가방이 차면 우편(`rewards.drop-when-full: false`), 우편 기능이 core `Mail` 공급처가 되면
  `Mailbox` 가 그리로 넘긴다. 공급처가 없으면(우편 기능이 꺼졌거나 켜다 실패) 남는 것은 바닥에 떨군다 — 꺼낼 화면이 없는 보관함에 쌓이지 않게.
- **CMI 가져오기**: server 기능이 `plugins/CMI`(DB 사본·YAML)를 I/O 스레드에서 읽어 `CmiData` 로 만들고, 켜진 기능 중 `CmiImportable` 을 구현한 것
  (homes·warps·spawn·teleport·chat·mail·player·inventory·kits·world·decor·portals·holograms·commands·server·moderation·admininfo)을 차례로 부른다.
  못 읽은 것은 `report.unreadable` 로 보고서(`import/cmi-<시각>.txt`)에 — 조용히 버리지 않는다. 두 번 돌려도 겹치지 않는다.
- **PDC 네임스페이스** `inmcess`(상수 `Ess.NAMESPACE`): `seat`(앉기 좌석 엔티티 — player 가 만들고 server `/유틸 좌석정리` 가 같은 열쇠로 치운다) ·
  `holo`·`holo_line`(홀로그램 엔티티) · `backpack`(셜커 배낭 아이템) · `dye`·`dye_owner`(염색) · `modified_name`·`modified_lore`(아이템 편집) ·
  `as_template`(거치대 템플릿 아이템) · `time_frozen_by_auto`(월드 — 현실 시간이 멈춘 표식). 읽기만 하는 남의 열쇠: `inmc-monster:mob_id`.
  PlayerStore 는 쓰지 않는다(데이터는 DB). 개인 설정(core `PlayerSettings`): `essentials.ride-me`·`totem-inventory`·`totem-bossbar`·`shift-sign-edit`·`holograms`·`silence`…
- **명령**: 164개 루트(한글 주 이름 + 영어 별칭). 바닐라·Paper 이름과 겹치는 것은 등록하지 않는다 — 예외 `msg`·`tell`·`w`(귓속말)·`list`(목록).
  `PlayerCommandPreprocessEvent` 순서: LOWEST 자판 변환 → LOW 반복 막기·던전 막기 → NORMAL 쿨타임·비용·대기 → HIGH 별칭 → MONITOR 명령 기록.
  화면에서 명령을 돌릴 때는 **친 것으로**(사건을 쏘고 취소되지 않았을 때만 `performCommand`) — `player.chat("/…")` 는 쓰지 않는다(채팅 숨김인 손님은 무시된다).

## 시험

JVM 시험 443개(`gradle :essentials:build`): 뼈대 `ResourceTest`(메시지 파일 ↔ 기본값 · 키 접두 · 코드가 부르는 키 · 권한 선언 · 설정 파일 · config 19개 · 의존) ·
`FeaturesTest` · `ReflectionPurityTest` · `PureTest` · `SkeletonRulesTest` + 기능마다 순수 규칙 시험(`src/test/kotlin/com/inmc/essentials/<패키지>/`).
서버 안 검증은 `/에센셜 검증`(기능마다 `checks()` — 진짜 사건을 쏘고 메모리만 바꿨다 되돌린다, 결과 `verify/essentials-<시각>.txt`).

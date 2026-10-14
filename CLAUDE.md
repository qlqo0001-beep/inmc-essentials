# inmc-essentials — 이 플러그인의 규칙

워크스페이스 공통 지침(`../CLAUDE.md`)에 더해 여기서만 통하는 것들. 설계는 `README.md`, 범위는 `../CMI-대체-범위.md`,
계획(왜 그렇게 정했나 · 4절 명령 이름 표)은 `~/.claude/plans/inmc-essentials.md`.

1. **기능은 자기 패키지 안에서 끝난다.** 기능 하나 = `com.inmc.essentials.<패키지>` 하나 + `messages/<id>.yml` + (있으면) `settings/<id>.yml`.
   다른 기능의 패키지를 import 하지 않는다. 기능끼리 만나는 곳은 `Ess` 의 접점 여섯(아래 6)과 core 창구(`Mail`·`Vanish`·`Selections`·`ChatVisibility`)뿐.
   **여럿이 같이 쓰는 파일**(`Ess.kt`·`EssentialsPlugin.kt`·`feature/*`·`command/*`·`config/*`·`store/*`·`util/Ph.kt`·`gui/Menu.kt`·`scheduler/*`·
   `verify/*`·`paper-plugin.yml`·`config.yml`)은 기능 작업에서 고치지 않는다 — 모자라면 고칠 내용을 보고한다.

2. **기능의 생성자·프로퍼티 초기화에서 `ess` 의 서비스를 만지지 않는다.** 시험이 서버 없이 가짜 `Ess`(빈 객체)로 기능을 만들어 `id`·
   `permissions`·`messageDefaults`·`hasSettings` 를 읽는다. 서비스는 `enable()` 부터. 위 넷은 상수처럼 짓는다.

3. **메시지 키는 `<id>-` 로 시작하고 `messageDefaults` 와 `messages/<id>.yml` 의 키가 정확히 같다**(`ResourceTest`). 공통 키
   (`player-only`·`no-permission`·`player-not-found`·`user-not-found`·`invalid-number`·`feature-disabled` …)는 `messages/common.yml` 의 것을 쓴다.
   보내기는 `ess.messages.send(대상, "키", Ph…)` — 시험이 이 모양에서 키를 읽는다. 기능만의 토큰은 `Ph.of().raw("{홈}", 이름)`(Ph.kt 에 더하지 않는다).
   Kotlin 표 → YAML 은 `linkedMapOf("키" to "값", …)` 모양이면 한 줄씩 옮기면 된다(값의 `'` 는 `''`).

4. **권한은 `Feature.permissions` 에만**(`inmcessentials.<기능>.<동작>`, 남에게 = `.others`) — 켤 때 등록하고 끌 때 내린다. `paper-plugin.yml` 에는
   관리 루트 `inmcessentials.admin` 하나뿐. 코드의 `"inmcessentials.…"` 문자열은 전부 선언돼 있어야 한다(`ResourceTest` — 선언 안 된 노드는 op 기본,
   지뢰 11). 문자열로 짓는 노드(`"…limit.$n"`)는 시험이 못 보니 직접 확인할 것.

5. **DB 규칙.** 표는 `schema(sql)` 에서 `CREATE TABLE IF NOT EXISTS ess_<기능>_…`(꺼진 기능도 불린다). 메인에서 `ess.db.call` 금지(켤 때·비동기
   사건만), 메인은 `ess.db.run("이름") { ess.db.sql… }`. **`REPLACE INTO` 금지** — `ess.db.upsert(표, 키, 고칠 열, 처음만 열)`. 아이템·돈·1회용 기록
   (우편·킷 사용·인벤 저장·밴)은 바뀌는 순간 DB 로(30초 묶음 금지). 사람 이름 ↔ uuid · 처음/마지막 접속 · 나간 자리는 `ess.users`(메모리 — 메인에서 읽어도 된다).

6. **접점 계약**(`Ess` — 쓰는 쪽은 부를 때마다 읽는다, 붙들어 두지 않는다):
   - `ess.teleporter.teleport(player, to, TeleportKind, safe = true): CompletableFuture<Boolean>` — **모든 이동은 이것으로**(직접 `teleport` 금지).
     기본은 `teleportAsync` 뿐, teleport 기능이 뒤로 기록·안전 자리·금지 아이템·이동 뒤 무적·탑승 동반을 단 구현으로 바꿔 끼운다.
   - `ess.afk.isAfk(player)` · `lastActive(player)` — afk 기능이 바꿔 끼운다(기본: 아무도 잠수 아님).
   - `ess.kits?.giveFirstJoin(player)` — kits 기능이 넣는다. spawn 기능이 첫 스폰 뒤에 부른다. 한 번만 주기는 kits 의 몫.
   - `ess.gate?.commit(player, 명령 이름)` — commands 기능이 넣는다. 이동 기능은 **이동이 성공한 순간** 부른다("성공해야 쿨타임").
   - `ess.moderation.isMuted/isShadowMuted/isJailed/isCuffed(uuid)` — moderation 기능이 바꿔 끼운다(기본: 아무도 안 묶임). 어느 스레드에서나.
     사람의 말을 남에게 전하는 기능(귓속말·도움요청·편지·거리 채팅)이 본다. 공개 채팅 뮤트·감옥·수갑의 막기는 moderation 이 직접.
   - `ess.abilities.isGod/setGod/wantsFlight/timedGodLeft/timedFlightLeft` — player 기능이 바꿔 끼운다(기본: 아무도 무적이 아니고 바꾸는 것은 아무 일도 없음,
     2026-10-14). world(무적 금지 월드·비행 돌려주기)·admininfo(`/정보`)가 본다 — 다른 기능의 메서드를 이름으로 부르거나 PAPI 로 읽지 않는다. 메인 스레드.
   - CMI 가져오기: 자료의 주인 기능이 `service/CmiImport.kt` 의 `CmiImportable.importCmi(data, report)` 를 구현한다. server 기능이 CMI 를 읽어
     `CmiData` 를 만들고 켜진 기능 중 이것을 구현한 것을 차례로 부른다. 못 읽은 것은 `report.unreadable` 로 — 조용히 버리지 않는다.
   - 우편은 core `Mail.send(...)`(공급처 = mail 기능), 투명은 core `Vanish`(상태의 주인 = admininfo 기능의 투명).

7. **명령.** `enable()` 안에서 `ess.commands.add(Commands.literal("한글").…build(), "설명", listOf("영어 별칭"))` — 이름은 계획 4절 표 그대로.
   `/에센셜` 아래 하위 명령은 `ess.commands.addAdmin(node)`. 바닐라·Paper 루트 이름(`CommandRegistry.RESERVED`)은 쓰지 않는다 — 예외는 귓속말
   (`msg`·`tell`·`w`)과 `list` 뿐. 어기거나 다른 기능과 겹치면 그 이름은 등록되지 않는다(로그). 한글 인자는 `greedyString()`, 이름이 둘이면
   `Args.split`. 사람 인자는 `ess.args.player()`/`players()`(빈칸까지 한 낱말 — 한글 닉네임·`*`·`전원` 도 읽고 뒤에 인자를 더 붙여도 된다) +
   `ess.args.online()`/`targets()`/`offline()` — 투명·부분 이름·닉네임 규칙이 거기 있다.

8. **투명이 새지 않게**(사용자 결정 2026-10-10): 이름 제안은 `Vanish.visibleNames(sender)`, 사람 목록·인원수는 보는 사람이 볼 수 있는 사람만
   (보는 사람을 모르면 `null` → 숨김), 투명인 사람을 이름으로 알리는 방송은 `Vanish.canSee(받는 사람, 그 사람)` 인 사람(+ 본인)에게만.

9. **우편 규칙**(사용자 결정): 못 받은 보상·가방이 찬 지급은 바닥이 아니라 `Mail.send(uuid, origin = "inmc-essentials", source = 한국어 설명, …)`
   (`Mail.available` 일 때 — 아니면 지금 하던 대로). 명령 속 받는 사람은 `{player}`. 보내기 또는 떨구기 **둘 중 하나만**.

10. **채팅 순서 계약**(CMI 를 뺀 뒤 — 계획 6절): 모양(렌더러)은 타이틀포지 `NameDisplayService.onChat`(LOW) **하나**. chat 기능은 `event.renderer()`
    를 부르지 않는다 — **LOW 한 처리기**: 스태프 모드 가로채기 → 도배 취소 → 외침 `!` 떼기(‘!’ 로 시작하는 글만)·비용 잡아 두기 · HIGH `viewers()` 고치기
    (거리·외침 범위·안 받기·무시) + 링크 축약 · MONITOR 외침 비용(취소된 것은 잡아 둔 것을 풀기) + 명령 스파이. 뮤트·채팅잠금(moderation)은 LOWEST 취소,
    그림자 뮤트·감옥 거리 채팅은 `viewers()` 줄이기 + core `ChatVisibility`(디스코드가 그것을 보고 중계하지 않는다). 디스코드는 LOW 감싸기 + MONITOR 전송 —
    디스코드의 LOW 가 우리 LOW 보다 먼저 돌면 앞의 `!` 를 그대로 볼 수 있다.

11. **반복 작업은 `ess.ticks.add(이름, 주기초) { now -> }` 하나로**(가이드 5.3). 따로 `runAtFixedRate` 를 만들지 않는다(사람마다의 카운트다운은
    `player.scheduler` 로 — 그건 반복 태스크가 아니라 그 사람의 일). 준비 전에는 돌지 않는다.

12. **설정은 불변 스냅샷 교체.** `load(settings)` 는 켤 때와 리로드 때 불린다 — 새 스냅샷을 만들어 `@Volatile` 필드에 대입. 값이 이상하면 기본값
    (던지지 않는다). 리로드는 `enable()` 을 다시 부르지 않는다 — 리스너·명령·틱은 한 번만.

13. **검증기**: 새 동작마다 `checks()` 에 검사를 더한다. 진짜 사건을 쏘고(`callEvent`), 바꾼 상태는 메모리에서만 바꿨다 `finally` 에서 되돌린다
    (파일·DB 에 쓰지 않는다). 메인 스레드라 DB 를 기다리지 않는다.

14. **선택 플러그인은 리플렉션으로만**(WorldEdit·LuckPerms·Votifier·CoreProtect·TitleForge·Multiverse — `ReflectionPurityTest`). 컴파일 의존은
    PlaceholderAPI 하나. 남의 메서드를 이름으로 부르면 ARCHITECTURE "리플렉션 진입점" 표에 적는다.

15. **켜기 실패와 기능끼리 엮기.** `enable()` 이 던지면 그 기능만 빠진다 — 그때까지 건 명령·리스너·틱·권한·접점·순위 대상은 플러그인이 거두고
    `disable()` 을 부른다. 그래서 `disable()` 은 반쯤 켜진 상태에서도 던지지 않게(`::x.isInitialized`), core 창구(`Mail`·`Vanish` 공급처) 떼기는 기능이.
    `enable()` 안의 `ess.enabled(다른 기능)` 은 목록에서 앞선 기능만 true — 다른 기능을 보고 엮을 것은 `ready()` 에서(또는 부를 때마다 본다).

# 서버 관리(server) — 운영 안내

CMI 의 status · tps · maintenance · setmotd(+ config `Motd`) · maxplayers · reload · migratedatabase · importfrom · util · placeholders ·
getbook · openbook · book(+ config `Books`) · ctext · editctext · ctellraw · Logs 폴더를 대신한다.
설정 `plugins/inmc-essentials/settings/server.yml`, 메시지 `messages/server.yml`, 명령으로 바뀌는 상태 `server/state.yml`(점검 켬·사유 · `/서버소개` · `/최대인원`),
커스텀 텍스트 `texts/<이름>.txt`, 기록 `logs/`, 가져오기 보고서 `import/`. DB 표는 없다.

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/서버상태` (status, lag) | 서버 판 · TPS(1·5·15분) · MSPT · CPU(서버·전체·코어·스레드) · 메모리(마우스 = 남음·할당·최대) · 켜진 지 · Java·OS · 디스크 · 접속(내가 볼 수 있는 사람만)·대기 작업 · 월드마다 청크·엔티티·타일·사람 | `inmcessentials.server.status` (OP) |
| `/서버상태 틱` | 3·10·30초·1·10분 TPS · 최근 10분 최저·최고 · TPS 가 `status.spike-below` 밑으로 내려간 순간(시각) — CMI `tps -spikes` | 같음 |
| `/서버상태 월드` | 월드마다 엔티티·타일이 가장 많은 청크 `status.busiest-chunks` 개 | 같음 |
| `/점검` (maintenance) | 지금 상태 | `inmcessentials.server.maintenance` (OP) |
| `/점검 켜기 [사유]` · `끄기` (on/true · off/false) | 점검 모드. 켜면 우회가 아닌 접속자를 내보내고(`auto-kick`) 우회한 사람에게 보스바. 재시작해도 남는다 | 같음 |
| `/최대인원 [수]` (maxplayers) | 지금 접속/최대 · 바꾸기(재시작해도 남는다 — 켤 때 다시 건다) | `inmcessentials.server.maxplayers` (OP) |
| `/서버소개 [글\|초기화]` (setmotd) | 서버 목록 글. 두 줄은 `\|` 또는 `\n` 으로. 없으면 지금 글, `초기화` = 설정 `motd.lines`(비었으면 server.properties) | `inmcessentials.server.motd` (OP) |
| `/플레이스홀더` (placeholders) | PlaceholderAPI 확장을 **플러그인별로** — 누르면 그 확장의 자리표시 목록 | `inmcessentials.server.placeholders` (OP) |
| `/플레이스홀더 목록 <식별자> [쪽]` | 그 확장의 `getPlaceholders()` 10개씩 — **자리표시 옆에 지금 값**(`%inmceco_balance% → 94687`). 매개변수 자리가 있으면 `(매개변수 필요)` · 누르면 입력창에, 아니면 누르면 자리표시 복사 | 같음 |
| `/플레이스홀더 값 <글>` · `보기 <플레이어> <글>` | 글 속 자리표시를 풀어 본다(`값` 은 나, `보기` 는 그 사람 — 오프라인도. 남의 값일 때만 누구 값인지 적는다) | 같음 · 남 `.placeholders.others` (OP) |
| `/책` (getbook) | 책으로 만들 텍스트 목록 | `.book`·`.book.open`·`.book.edit` 중 하나 |
| `/책 <이름> [대상\|all]` · `/책 받기 <이름> …` | 그 텍스트를 쓴 책 아이템(가방이 차면 우편 — 우편 기능이 없으면 발밑) | `inmcessentials.server.book` (OP) · 남 `.book.others` (OP) |
| `/책 열기 <이름> [대상\|all]` | 아이템 없이 책 화면으로 바로 — CMI `openbook` | `inmcessentials.server.book.open` (모두) · 남 `.book.others` |
| `/책 저자 <이름>` · `제목 <제목>` · `풀기` | 손에 든 쓴 책의 저자·제목 바꾸기 · 다시 쓸 수 있게(책과 깃펜으로) | `inmcessentials.server.book.edit` (OP) |
| `/텍스트` (ctext) | 볼 수 있는 텍스트 목록(누르면 보기) | `inmcessentials.server.text` (모두) |
| `/텍스트 <이름> [쪽]` · `<이름> <대상\|all> [쪽]` | 보기 · 남에게 보이기. 쪽이 여럿이면 아래 ◀ ▶ | 같음 · 남 `.text.others` (OP) |
| `/텍스트 편집 <이름>` | 입력창으로 고치기(없으면 새로) — 저장하면 다시 읽는다. CMI `editctext` | `inmcessentials.server.text.admin` (OP) |
| `/서식메시지 <대상\|all> <글>` (ctellraw) | MiniMessage(+ `&` 색 · PAPI · CMI `<T>…</T><H>…</H><C>…</C>` 표식) 글을 보낸다 | `inmcessentials.server.tellraw` (OP) |
| `/유틸 좌석정리` (cutil) | 탄 사람 없는 앉기 좌석(PDC `inmcess:seat` 이 달린 ItemDisplay — player 기능의 `/앉기`) 지우기 — CMI `util removeseats` | `inmcessentials.server.util` (OP) |
| `/유틸 대상확인` | 64칸 안 바라보는 엔티티(종류·이름·좌표·거리·uuid 누르면 복사)와 블록(상태·좌표) — CMI `util testtarget` | 같음 |
| `/에센셜 리로드` | config·메시지·**모든 기능의 설정**을 다시 읽는다(뼈대). 저장소·기능 켜고 끄기는 재시작 | `inmcessentials.admin` |
| `/에센셜 데이터 이전 <mysql\|sqlite> [확인 [덮어쓰기]]` | 지금 DB 의 `ess_` 표를 전부 다른 쪽으로 베낀다 — 아래 | `inmcessentials.admin` |
| `/에센셜 가져오기 cmi [확인]` | CMI 자료 가져오기 — 아래 | `inmcessentials.admin` |
| `/에센셜 로그` | 기록 켜짐·오늘 파일 크기·보관 일수 | `inmcessentials.admin` |

## 점검 · 가득 참 · 서버 목록

- **점검 우회**: op · `inmcessentials.server.maintenance.bypass` · `maintenance.allow`(이름·uuid). 접속 **직전**에는 Player 가 없어 권한을 LuckPerms 에 직접 묻는다
  (리플렉션 — 접속 직전에 LuckPerms 가 읽어 둔 것). LuckPerms 가 없으면 op·목록만 보고, 들어온 뒤 권한으로 한 번 더 본다(없으면 바로 내보냄).
- **가득 참 우회**: Paper `PlayerServerFullCheckEvent` 에서 op 또는 LuckPerms 의 `inmcessentials.server.fullbypass` 면 들인다(`full-server.bypass-enabled`).
- **서버 목록**: 점검 중이면 `maintenance.motd` 두 줄과 오른쪽 위 `maintenance.version-text`(빨간 글 — 손님 판과 다른 프로토콜 번호를 보낸다; 접속 단추는 그대로라
  막기는 접속 직전에). 아니면 `/서버소개` 글 → `motd.lines` → server.properties. `motd.max-players` 로 보이는 최대 인원, `motd.hover` 로 인원 호버 줄.
  **투명인 사람은 인원수·이름 목록에서 뺀다**(`motd.hide-vanished` — 보는 사람을 모르니 숨김, CLAUDE.md 8).
- ⚠ 테섭에 **ServerListPlus** 가 있다 — 둘 다 서버 목록을 고친다. 이 기능은 HIGH 에서 고치므로 SLP 가 더 늦으면 SLP 가 이긴다. 하나만 쓰거나 `motd.enabled: false`.

## 커스텀 텍스트 (`texts/<이름>.txt`)

- 글은 MiniMessage(`<click:run_command:'/스폰'>` · `<hover:show_text:'…'>` …) + `&` 색 + `{player}`(보는 사람) + PlaceholderAPI(보는 사람 기준).
- CMI 표식(줄에 그것만): `<NextPage>` 쪽 나눔 · `<AutoPage>` `texts.lines-per-page` 줄씩 · `<Hidden>` 목록에서 숨김(관리자만 보임) ·
  `<ReqPermission>` `inmcessentials.server.text.view.<이름>` 이 있어야(선언 안 된 노드 — 기본 op) · `<AutoAlias>` `/<이름>` 명령으로도(그 이름이 비어 있을 때만).
- CMI 줄 표식 `<T>글</T>` + `<H>호버</H>` · `<C>/명령</C>`(누르면 실행) · `<SC>`·`<SI>`(입력창에) · `<URL>주소</URL>` 와 `{#rrggbb}` 는 MiniMessage 로 바꿔 읽는다.
  **`<CC>`·`<CCI>`(누르면 콘솔 명령)는 버린다** — 누른 사람의 명령으로 바꾸면 권한이 달라진다.
- 처음 켤 때 예제 `rules.txt`·`welcome.txt`(숨김)를 만든다. `texts.join-text` 에 이름을 적으면 접속할 때 1쪽을 보인다(CMI `Motd: welcomeMessage`).
- 이름: 빈칸·점·콜론·슬래시 안 됨. 파일을 고친 뒤 `/에센셜 리로드`(또는 `/텍스트 편집`).

## 책

- `/책` 은 텍스트 한 쪽을 14줄씩 책 쪽으로. 저자 `books.default-author`, `books.add-date` 면 설명에 만든 날짜. PAPI·`{player}` 는 **받는 사람 기준으로 줄 때** 풀린다.
- 플레이어가 쓰는 책: 쪽 수 `books.max-pages`(넘으면 저장 안 됨, `inmcessentials.server.book.unlimited` 면 무제한) · 서명할 때 `&` 색 코드는
  `inmcessentials.server.book.color` 가 있어야 색이 된다(`<…>` 태그와 PAPI 는 풀지 않는다 — 주입 막기).

## 기록 (`logs/`)

`logs.commands`(플레이어·콘솔 명령, 막힌 것은 "(막힘)") · `logs.chat`(공개 채팅 원문) — 기본 꺼짐(CMI 테섭의 Logs 폴더도 비어 있었다). 날짜별 파일
`commands-2026-10-10.log`. `logs.hidden-commands`(로그인 비밀번호 등)는 인자를 `***` 로. `logs.keep-days` 보다 오래된 파일은 지운다. 쓰기는 5초마다 I/O 스레드.

## 데이터 이전 (`/에센셜 데이터 이전`)

1. sqlite → mysql: `config.yml` 의 `storage.mysql.url`·`user`·`password` 를 적고(**`type` 은 아직 sqlite**) `/에센셜 리로드`.
2. 모두 나간 뒤 `/에센셜 데이터 이전 mysql` 로 계획을 보고 `/에센셜 데이터 이전 mysql 확인`. 받는 쪽 표는 모든 기능의 표 만들기로 먼저 만든다.
   받는 쪽에 자료가 있으면 **멈추고 표 목록만** 알린다 — 지우고 베끼려면 `… 확인 덮어쓰기`.
3. 끝나면 `storage.type: mysql` 로 바꾸고 재시작. mysql → sqlite 는 반대로(`type` 이 mysql 인 채로 `… sqlite 확인`, 로컬 `data/essentials.db` 로 베낌).

DB 쓰기 스레드에서 표마다 한 트랜잭션으로 돈다 — 그동안 다른 DB 쓰기는 줄을 선다. 받는 쪽에 없는 열은 뺀다.

**이전 잠금**: `확인` 을 친 순간부터 서버를 다시 켤 때까지 **아무도(점검 우회도) 들어올 수 없다** — 베끼는 중이나 끝난 뒤 재시작 전에 들어온 사람의
기록(킷·우편·홈·쿨타임)은 옛 DB 에만 남아 재시작 뒤 사라지거나 되살아난다. 멈추거나 실패하면 잠금이 풀린다. 그만두려면 `/에센셜 데이터 잠금해제`.
`/에센셜 데이터` 가 잠금 상태를 보여 준다. 잠금은 메모리에만 있다(재시작하면 풀린다 — 새 DB 로 켜진 뒤에는 막을 까닭이 없다).

## CMI 가져오기 (`/에센셜 가져오기 cmi`)

CMI 를 빼기 **전에** 한 번. 먼저 읽어서 보여 주고(유저·홈·우편·워프 수, 넘어갈 파일, 못 읽은 줄 수), 5분 안에 `확인` 을 붙이면 넣는다. 두 번 해도 겹치지 않는다.

- **읽기**(I/O 스레드): `Settings/DataBaseInfo.yml` 이 sqlite 면 `cmi.sqlite.db`(+ `-wal`·`-shm`)를 `import/tmp` 에 **베껴** 읽기 전용으로 연다(CMI 폴더는 고치지 않는다,
  사본은 끝나면 지운다). mysql 이면 `hostname`/`database`/`tablePrefix`(`CMI_`)로. `users` 표 → 유저마다 이름·닉네임·마지막 접속·나간 시각·플레이 시간·나간/뒤로/사망 자리·
  홈(JSON·옛 `$-` 형식)·우편(JSON·옛 `:` 형식)·무시·뮤트·감옥·경고·메모·킷 사용·UserMeta·시간제 비행/무적·투표, 나머지 열은 `extra` 로.
  `playtime` 표는 날짜별 합을 `extra.playtimeDaily` 로. YAML: `Saves/Warps.yml` · `Kits/Kits.yml` · `Saves/Holograms.yml` · `Portals.yml` · `Jails.yml` ·
  `Settings/Schedules.yml` · `PlayTimeRewards.yml` · `EventCommands.yml` · `CustomAlias` 폴더 · config `Spawn.Main/FirstSpawn.Location` — **비었거나 CMI jar 의 기본
  파일과 같으면 넘기지 않는다**(테섭은 Kits·PlayTimeRewards·Schedules 가 예제 그대로).
- **위치 형식**: DB 열은 `world:x:y:z:pitch:yaw`(CMI 가 읽을 때 둘을 바꾼다 — 표본 `…:15.34:-100.95`), config 는 `world;x;y;z;yaw;pitch`, 홈 안은 yaw 다음 pitch.
  어느 한쪽이 ±90 을 넘으면 그것을 yaw 로 본다.
- **넣기**(메인): 유저 기본 기록을 `ess_users` 에(없는 사람만 새로 — 첫 접속 시각은 CMI 에 없어 마지막 접속으로, 있는 사람은 빈 칸만) → 켜진 기능 중 가져오기를
  구현한 것을 차례로(홈·워프·킷·뒤로·감옥·일정·별칭 …). 한 기능이 던져도 나머지는 돈다. 가져오기를 하지 않은 기능(꺼졌거나 구현이 없음)은 보고서에 이름을 적는다.
- **server 기능의 몫**: config `Optimizations.Maintenance: true` 면 이 플러그인의 점검도 켠다(CMI 는 재시작해도 점검을 지킨다 — 여기서 끄면 CMI 를 뺀 날 서버가
  모두에게 열린다; `MaintenanceAutoKick`·`MaintenanceBossBar` 가 설정과 다르면 알림) · `CustomText/*.txt` 는 같은 이름이 없을 때만 `texts/` 로 베낀다
  (표식은 그대로 — `/텍스트` 가 CMI 표식을 읽는다) · 접속 글 `Motd: <이름>` 은 `texts.join-text` 에 적으라고 알린다(설정 파일은 고치지 않는다) ·
  `Books.DefaultAuthor`·`AddDate` 가 설정과 다르면 알림.
- CMI 가 **아직 켜져 있으면** 가져온 이벤트 명령·일정은 꺼 둔다(commands 기능 — 같은 명령이 CMI 와 두 번 돌지 않게). 켜는 법은 보고서와 채팅에.
- **옮기지 않는 것**(보고서에 숫자·목록): 잔고(inmc-economy 가 진실) · 닉네임(타이틀포지가 주인 — 목록만) · 저장 인벤(`inventories` 표 — CMI 직렬화) ·
  받은 플레이 보상 기록(`playtimereward` 표) · CMI 쿨타임/대기/비용 규칙(명령 이름이 CMI 것) · 밴(`extra` 로 넘김 — moderation 이 읽지 않으면 바닐라 `/ban`).
- **보고서** `import/cmi-<시각>.txt` — 종류마다 읽음·넣음·건너뜀, 그리고 **못 읽은 줄 전부(원문 일부와 함께)**. 못 읽은 줄이 있으면 채팅에 빨갛게 알린다.
  테섭에는 표본이 거의 없다(유저 4명, 홈·우편 0) — 운영 서버 사본으로 한 번 돌려 보고서를 볼 것.

## CMI 와 다른 점

- `/cmi reload` 의 "걸린 ms · 실패한 파일 이름" 은 뼈대 `/에센셜 리로드` 에 없다(뼈대 변경 요청). YAML 이 깨지면 그 파일은 기본값으로 돌고 서버 로그에 남는다.
- DB 저장(`DataBaseInfo.yml` · `AutoSaveInterval` · `/cmi saveall`) · 비동기 파일 저장(`FileSave.Async`)은 뼈대가 이미 한다 — 바뀌는 순간 DB 쓰기 스레드로,
  메인 스레드에서 파일·DB 를 만지지 않는다. 따로 고를 것이 없다.
- `/cmi importfrom essentials|hd`(EssentialsX·HolographicDisplays) 는 없다 — 이 서버에 그 플러그인이 없다. CMI 에서만 가져온다.
- `/cmi util convertitems`(CMI 저장 아이템 형식 바꾸기)는 없다 — CMI 를 빼면 뜻이 없다.
- 점검 상태·서버 목록 글을 PlaceholderAPI 자리표시로 내지 않는다(CMI `%cmi_maintenance%` 류 없음).
- `/텍스트` 의 "보낸 사람(sourcePlayer)" 인자 · `img:` 그림 · 콘솔 클릭 명령(`<CC>`)은 없다.
- 책 쪽 수 제한은 권한 단계(`cmi.book.pages.20~100`) 대신 설정 하나 + 무제한 권한.
- `/최대인원` 은 `Server#setMaxPlayers`(26.1.2 에 있다)로 바꾸고 `server/state.yml` 에 적어 켤 때 다시 건다 — server.properties 는 고치지 않는다.

## 검증기(`/에센셜 검증`)

서버 목록에서 투명인 사람 빼기(진짜 목록 사건, 접속자로) · 점검 중 서버 목록 글(메모리에서만 점검 켬) · 점검 우회 권한 · 점검 중 처음 보는 사람 접속 직전 거절
(권한을 모르면 들이고 접속 때 다시 봄) · 데이터 이전 잠금 중 접속 거절 · 가득 참 우회가 아닌 사람 거절 · TPS 기록 · CMI 표식 바꾸기 · 텍스트 폴더 읽기.
접속 직전·가득 참 사건은 비동기 사건이라 `callEvent` 로 쏘지 않고 우리 리스너에 바로 준다.

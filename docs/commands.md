# 명령어 체계(commands) — 운영 안내

CMI 의 CustomAlias·Alias(aliaseditor) · CommandCost · WarmUps · Cooldowns · CommandFilter(Duplicate·SimilarCommandChecker·PermisionOnError·
checkcommand·checkperm) · EventCommands · Schedules(schedule) 를 대신하고, CMI 에 없던 **한/영 자판 변환**을 더한다.
설정 `plugins/inmc-essentials/settings/commands.yml`, 메시지 `messages/commands.yml`, 데이터 파일 `commands/aliases.yml` · `commands/events.yml` ·
`commands/schedules.yml`(처음 켤 때 예제로 만든다 — 예제는 전부 꺼져 있다). DB 표 `ess_commands_cooldowns`(쿨타임 기록).
`/에센셜 리로드` 가 설정과 세 데이터 파일을 다시 읽는다.

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/별칭` (aliaseditor) | 별칭 편집 화면 — 누르면 입력창으로 고치기 · 우클릭 켜기/끄기 · Shift+우클릭 지우기(확인창) · "새 별칭". 콘솔은 목록 | `inmcessentials.commands.aliaseditor` (OP) |
| `/별칭 목록` | 채팅으로 목록 | 같음 |
| `/일정` (schedules) | 일정 목록 — 켬/끔 · 간격 일정은 다음까지 남은 시간 · 시각 일정은 시각들 | `inmcessentials.commands.schedule` (OP) |
| `/일정 실행 <이름>` | 지금 돌린다(접속자 조건은 본다) — CMI `/cmi schedule <이름>` | 같음 |
| `/일정 켜기·끄기 <이름>` | 켜고 끈다(파일 `Enabled` 를 고쳐 쓴다) | 같음 |
| `/일정 타이머 <이름>` | 간격을 지금부터 다시 센다 — CMI `-updatetimer` | 같음 |
| `/에센셜 명령찾기 <글>` | 이름·설명에 글이 든 명령(설명 · 권한 노드 · 플러그인) — CMI `checkcommand` | `inmcessentials.admin` |
| `/에센셜 권한찾기 <글>` | 이름·설명에 글이 든 등록 권한(기본값 · 내가 가졌나) — CMI `checkperm` | `inmcessentials.admin` |

## 한/영 자판 변환 (`keyboard.*`)

- **실행**: 친 명령이 그 사람이 쓸 수 있는 명령에 없고, 두벌식 키로 풀었을 때 **하나**에만 맞으면 그 명령으로 바꿔 실행한다.
  `/tmvhs` → `/스폰`, `/qufcld 목록` → `/별칭 목록`, `/넴주`·`/네ㅁ주` → `/spawn`. 실제 이름이 언제나 먼저다(서버에 있는 이름은 권한이 없어도 바꾸지 않는다).
- **하위 명령**(`subcommands`): 그 자리의 자식이 **전부 글자(리터럴)**일 때만 바꾼다 — 사람 이름·자유 글이 올 수 있는 자리는 건드리지 않는다.
- **탭 완성**(`tab-complete`): 손님에게 보내는 명령 목록에 **한글 명령의 영타형**(`tmvhs`)만 더한다(`/tmv` 에서 제안되고 뒤 인자 완성도 된다).
  뒤 인자(`/dlehd ` 의 사람 이름 등)는 서버 트리에 그 이름이 없어 제안이 **비어, 취소된 채** 온다(Paper 가 빈 제안은 미리 취소한다) — `AsyncPlayerSendSuggestionsEvent`
  를 `ignoreCancelled` 없이 받아 바꾼 글(`/이동 `)로 명령 표에 다시 물어 채우고 취소를 푼다. 비동기로 오면(다른 플러그인이 비동기 처리) 건드리지 않는다.
  영어 명령의 한글형(`넴주`)은 실행만(목록이 지저분해지지 않게 — 사용자 결정 C).
- **안내**(`unknown-hint` · `similar.threshold`): 없는 명령이면 "혹시 /스폰?"(같은 키가 둘 이상이면 둘 다, 아니면 이름이 75% 이상 닮은 명령). 누르면 입력창에 넣는다.
- **권한 안내**(`permission-hint.*`): 서버에 있지만 그 사람의 명령 목록에 없는 명령(=권한 없음)을 치면 "권한이 없습니다". 마우스를 올리면 권한 노드
  (`show-node: true` 이거나 `inmcessentials.commands.seepermission`).
- 순서: 자판 변환은 `PlayerCommandPreprocessEvent` **LOWEST**. 던전의 명령 막기는 **LOW** 로 내려 바꾼 뒤의 이름을 본다(inmc-dungeon 2026-10-10).

## 대기 · 쿨타임 · 비용 (`rules.<id>`)

명령 이름(별칭)마다 규칙을 건다. 우리 명령은 등록표로, 남의 명령은 Bukkit 이름으로 **대표 이름에 맞춰** 보므로 별칭을 다 적지 않아도 된다.

| 키 | 뜻 |
|---|---|
| `enabled` | 끄면 없는 규칙 |
| `commands` | 이름 목록(앞 `/`·`플러그인:` 은 떼고 본다) |
| `args` | `any` · `none`(인자 없을 때만 — CMI `cmd :10`, 예: `/홈` 화면만) · `some`(인자 있을 때만 — CMI `cmd_`) |
| `cooldown` | 초. **-1 = 평생 한 번**. 기록은 DB 라 재시작해도 남는다(규칙 id 가 열쇠 — 이름을 바꾸면 처음부터) |
| `group-cooldowns` | `{ 권한노드: 초 }` — 가진 것 중 가장 짧은 것이 기본값을 덮는다(LuckPerms 그룹이면 `group.vip`). 평생 한 번은 덮지 않는다 |
| `warmup` · `group-warmups` | 대기 초. 대기 중 표시는 `warmup.display`(title·actionbar·bossbar·chat) · 소리 `warmup.sound` |
| `move` | `block`(같은 블록 안) · `any`(어디로든) · 숫자(그 블록 수까지) — CMI `false` / `true` / `false-3` |
| `cancel-on-damage` | 대기 중 맞으면 취소 |
| `cost` · `currency` | 금액 · 화폐 id(비우면 기본 화폐 — core `EconomyHook`) |
| `cost-notify` · `cost-confirm` | 낸 뒤 알림 · 확인을 받고 실행(같은 명령을 다시 치거나 채팅 글을 누르면 — `cost.confirm-seconds` 안에) |
| `commit-on-success` | **명령이 성공했을 때만** 쿨타임·비용. 이동 기능(홈·워프·스폰·이동·뒤로·바이옴찾기 …)은 이동이 성공한 순간 `ess.gate.commit` 을 부른다. 남의 명령에는 쓰지 말 것(알림이 오지 않아 기록이 안 된다) |

- 대기: 명령을 멈추고 초마다 카운트다운. 움직이면(`move`)·맞으면·순간이동하면 취소, 나가거나 죽으면 조용히 취소 — **뺀 돈은 돌려준다**. 대기 중에 규칙 걸린
  다른 명령을 치면 새 것이 옛 것을 바꾼다. 끝나면 쿨타임을 적고 그 명령을 실행한다.
- 비용: 먼저 화폐마다 있는지 보고, 확인이 필요하면 확인을 받고, 대기가 있으면 대기 시작 때 빼고(취소 땐 돌려줌), 없으면 실행 직전에 뺀다.
  `commit-on-success` 규칙은 성공 알림 때 뺀다(그 사이 돈이 줄었으면 알리기만 — 이미 성공한 것은 되돌리지 않는다).
- 우회(전부 OP 기본): `inmcessentials.commands.bypass.cooldown` · `.bypass.warmup` · `.bypass.cost` · 규칙 하나만 `inmcessentials.commands.bypass.rule.<id>`.
  **관리자(op)는 다 건너뛰므로 시험은 op 를 빼고**(검증기는 우회를 잠깐 끄고 본다).

## 반복 명령 막기 (`spam.*`)

같은(`similarity`% 이상 닮은) 명령을 `interval` 초 안에 이미 `max-repeats` 번 쳤으면 막는다(기본 꺼짐 — CMI 와 같다). `whitelist` 는 예외(이름·별칭).
우회 `inmcessentials.commands.spam.bypass` (OP).

## 별칭 (`commands/aliases.yml` · `/별칭`)

| 키 | 뜻 |
|---|---|
| 이름(키) | 한 낱말(`밥`) · 여러 낱말 = 하위 명령 별칭(`스폰 설정`) · `?` = 아무 낱말(`ca ?` — 틀린 하위 명령이면 도움말 같은 것). 점·콜론·슬래시 안 됨 |
| `commands` | 줄마다 명령(앞 `/` 없이). `$1` 첫 인자 · `$1-` 첫 인자부터 끝까지 · `$0` 친 이름. 필요한 `$n` 이 없으면 `usage` 를 보인다 |
| `permission` | `''` 누구나 · `true` = `inmcessentials.commands.alias.<첫 낱말>`(선언하지 않은 노드라 기본 op — LuckPerms 로 준다) · 그 밖 = 그 노드 |
| `tab` · `tab-completions` | 탭 완성. 인자 자리마다 한 줄(빈칸으로 여럿, `[player]`·`[world]`). 비우면 **첫 명령의 탭 완성을 빌린다**(`warp $1` → `/warp` 의 제안) |
| `exact` | 이름만 쳤을 때만(인자가 붙으면 같은 이름의 원래 명령으로) |
| `description` · `usage` · `enabled` | 설명 · 인자가 모자랄 때 보일 글 · 켜기 |

- 기본 실행자는 **친 사람**(별칭은 그 사람의 명령을 대신 친다). `asConsole!` 로 콘솔.
- 한 낱말 별칭은 실행 중에 Bukkit 명령 표에 올리고 접속자에게 명령 목록을 다시 보낸다(탭 완성·`/` 목록에 보인다). 이름이 **이미 다른 명령이면**
  올리지 않고 가로채서 덮는다(`exact` 면 이름만 쳤을 때만). 바닐라 이름(`give` …)은 늘 가로채기로.
- 여러 낱말 별칭과 덮는 별칭은 `PlayerCommandPreprocessEvent` HIGH 에서 가로챈다 — 쿨타임·대기·비용(NORMAL)이 별칭 이름으로 먼저 본다.
  콘솔·명령 블록(`ServerCommandEvent`)과 일정·이벤트 명령·다른 별칭이 `performCommand`·`dispatchCommand` 로 부르는 것도 가로챈다.
- **별칭이 그 사람으로 돌리는 명령 줄은 `PlayerCommandPreprocessEvent` 를 다시 거친다** — 대상 명령의 대기·쿨타임·비용·던전 막기·자판 변환이
  별칭으로 빠지지 않는다(`/ㅅ → 스폰` 에 스폰의 규칙이 그대로 걸린다). 이벤트 명령·일정이 `asPlayer!` 로 시키는 것은 관리자가 시킨 것이라 거치지 않는다.
- 인자는 템플릿을 `;;` 로 나누고 머리(`asConsole!` …)를 읽은 **뒤에** 명령 본문에만 넣는다 — 사람이 친 `;;asConsole! op 나` 는 그냥 글자다.
  인자 속 `;;` 는 `;` 로, 명령이 아닌 줄(`msg!`·`broadcast!` …)에 들어가는 인자의 `<` 는 글자로(태그·클릭 끼워 넣기 막기).
- 템플릿의 PlaceholderAPI 는 친 사람 기준으로 **먼저** 풀고 그 뒤에 인자를 넣는다 — 사람이 친 `%…%` 는 풀리지 않는다.
- 고리 막기(CMI `InfiniteLoopDetection`): **자기 자신을 부르는 별칭은 켜지 않는다**(`delay!` 뒤에 불러도 — CMI 와 같다; 원래 명령은 접두 이름
  `inmc-essentials:워프` 로). 그 밖에 실행이 8번 넘게 겹치거나 `delay!` 를 건너 16번 넘게 이어지면 멈추고 로그.

## 특수 머리 (별칭 · 이벤트 명령 · 일정 공통)

줄 앞에 몇 개든 잇는다(`perm:vip! asConsole! say 안녕`). 한 줄에 여러 명령은 `;;`.

| 머리 | 뜻 |
|---|---|
| `delay! N` | 이 줄 뒤의 나머지를 N초 뒤에(소수 가능) |
| `asConsole!` · `asPlayer!` | 콘솔로 · 주인공 플레이어로 |
| `fromConsole!` | 친 쪽이 콘솔일 때만 이 줄을(별칭) |
| `perm:<노드>!` | 주인공이 그 권한이 있을 때만 |
| `moneycost:<금액>!` · `expcost:<경험치>!` | 그만큼 내고(없으면 이 줄을 건너뛰고 알림) |
| `allPlayers!` | 접속자(투명 빼고)마다 한 번 — 명령 속 `[allPlayers]` 에 그 이름 |
| `msg! <이름> <글>` | 그 사람에게 글 |
| `broadcast! <글>` · `actionbar! <글>` · `title! <제목>\n<부제>` · `subtitle! <글>` | 모두에게(주인공이 투명이면 그를 볼 수 있는 사람에게만) |
| `kickall! <글>` | 모두 내보내기 |

변수: `[playerName]` `[playerDisplayName]` `[lvl]` `[exp]` `[hp]` `[maxHp]` `[hunger]` `[gameMode]` `[world]` `[x]` `[y]` `[z]`, 죽인 쪽 `[sourceName]` `[sourceLvl]` …,
`[value]` `[value2]`(사건마다), `[randomPlayer]`(투명 아님 · `inmcessentials.commands.schedule.exclude` 없음), `%rand/1-5%`, `{player}`, PlaceholderAPI.
글은 MiniMessage + `&` 색.

## 이벤트 명령 (`commands/events.yml`)

CMI `EventCommands.yml` 과 같은 이름·키(`Enabled` · `Commands` · `SourceCommands` · `CancelEvent`). 기본 실행자 콘솔.

`firstJoinServer`(첫 접속 — 그때는 joinServer 가 돌지 않는다) · `joinServer` · `quitServer` · `playerDeath`(+ 죽인 사람이 있으면 SourceCommands) ·
`playerKillPlayer`(Commands = 죽은 쪽, SourceCommands = 죽인 쪽) · `playerRespawn` · `playerTeleport`(`[cause]`) · `bedEnter`* · `bedLeave` · `playerWorldChange`(`[value]` 새 월드 · `[value2]` 옛 월드) ·
`playerPreWorldChange`* · `playerGameModeChange`*(`[value]` 새 · `[value2]` 옛) · `playerKick`*(`[value]` 사유) · `playerBan`*(밴으로 나갈 때) · `playerLevelChange` ·
`voidFall`(5초에 한 번) · `elytraStartGlide`* · `elytraEndGlide` · `advancementDone`(`[value]` id · `[value2]` 이름 — 숨은 것 빼고) · `sneakingSwapHandItems`* · `swapHandItems`* ·
`pvpstart` · `pvpend`(플레이어끼리 친 뒤 `combat-seconds` 초 동안 없으면). `*` = `CancelEvent: true` 로 그 사건을 막을 수 있다.

## 일정 (`commands/schedules.yml`)

CMI `Schedules.yml` 과 같은 키: `Enabled` · `Delay`(초마다 — `PerformOn` 이 없을 때만) · `PerformOn`(목록: `Month` · `Day`(1~31 또는 요일 `monday`·`월요일`·`월`) ·
`FirstMonthDay`/`LastMonthDay`(그 달 첫/마지막 ○요일) · `Hour` · `Minute` · `Second` — 빠진 월·일·요일·시 = 아무 때나, 분·초 = 0) · `Repeat`(false = 이번 실행에 한 번) ·
`MinPlayers` · `MaxPlayers`(투명 빼고) · `MinPlayersCommands` · `FeedBack` · `Randomize` · `DuplicateRandomize` · `SingleLinear` · `DontTranslatePlaceholders` · `Commands`.
1초 틱에서 돈다 — 서버가 느려 초를 건너뛰어도 그 사이 초(2분까지)를 다 본다. 시각은 서버 시간대.

## CMI 가져오기 (`/에센셜 가져오기 cmi`)

- `CustomAlias` 폴더의 별칭 → `aliases.yml`(같은 이름은 건너뜀). `Cmds` · `Perm`(true → `permission: true`) · `Tab` · `CustomTabs` · `ExactMatch`.
  **`cmi …` 명령을 부르는 별칭은 꺼 두고** 가져온다(CMI 를 빼면 안 돈다 — 보고서에 줄마다).
- `EventCommands.yml` 의 **켜진** 사건 → `events.yml`(이미 켠 사건은 건너뜀, `cmi …` 면 꺼 둠).
- `Schedules.yml` 의 일정 → `schedules.yml`(같은 이름 건너뜀, `cmi …` 면 꺼 둠). 테섭의 예제 4개는 jar 기본과 같아 아예 넘어오지 않는다.
- config 의 `Cooldowns.List` · `WarmUps.List` · `CommandCost.yml` 은 **옮기지 않는다** — 명령 이름이 CMI 것(`cmi heal`)이라. 켜져 있었으면 줄마다 보고서에 남는다.

## CMI 와 다른 점

- 쿨타임·대기·비용은 `config.yml`의 콜론 문자열(`cmi heal:180`) 대신 규칙 하나에 셋을 함께. 권한으로 시간 바꾸기는 `cmi.cooldown.<cmd>.<초>` 대신 `group-cooldowns`.
  대기 보스바 칸 나눔(Segments)·GlyphHead 파티클은 없다.
- `Alias.yml`(CMI 하위 명령 330개의 짧은 이름 켜고 끄기)은 없다 — 우리 명령은 처음부터 한글 이름 + 영어 별칭이다.
- 별칭 편집은 채팅 UI 대신 화면 + 입력창. 커스텀 탭은 "자리마다 한 줄"(CMI 의 AddTabs 는 "첫 명령의 탭을 빌림" 으로).
- `/cmi checkcommand`·`checkperm` → `/에센셜 명령찾기`·`권한찾기`. RCON(`AllowRconCommands`)은 따로 막지 않는다 — 우리 명령은 RCON 에서도 그대로 된다.
- **부분 이름**(`PartialPlayerName`)은 뼈대의 `ess.args` 가 늘 한다(정확히 → 대소문자 무시 → 앞부분 하나 → 들어 있는 것 하나 → 닉네임). 끄는 설정과
  `-exa-`(오프라인 정확 이름 우선)는 아직 없다 — 지금은 접속 중인 `Luuk666` 이 오프라인 `Luuk` 보다 먼저 잡힌다(뼈대 변경 요청).
- 메뉴 버튼처럼 **채팅으로 치지 않은** 명령(`performCommand`)은 쿨타임·대기·비용을 보지 않는다(이 사건이 오지 않는다) — CMI 와 같다.
  **별칭은 예외** — 사람이 친 별칭이 돌리는 명령 줄은 이 사건을 다시 쏘므로 대상 명령의 규칙이 걸린다(위 "별칭"). inmc-menu 의 버튼은 `player.chat("/…")` 라 걸린다.
- `commit-on-success` 는 같은 명령을 다시 치면 앞의 기다림(실패한 시도)을 버린다 — 확정 한 번은 언제나 한 시도 몫만 낸다.
- 쿨타임 기록은 접속 직전에 읽어 **대기칸**에 두고 접속 사건에서 옮긴다 — 같은 계정이 접속 중에 다시 들어와도(옛 접속이 뒤늦게 나가도) '한 번만'
  기록이 지워지지 않는다. 거절된 접속의 대기칸은 2분 뒤 버린다.

## 검증기(`/에센셜 검증`)

`ess.gate` 연결 · 자판 변환 두 방향 · 영타 루트의 인자 탭 제안(취소된 채 온 빈 사건을 채우고 취소를 푸나) · 별칭 인자 끼워 넣기 막기 · 쿨타임 막기 · 대기 시작 ·
반복 막기 · 하위 명령 별칭 가로채기 · 한 낱말 별칭이 명령 표에 있나 · 이벤트 명령(레벨 바뀜) · 비용 확인(돈은 빼지 않음) · 성공해야 쿨타임(확정·앞 시도 버리기) ·
없는 명령 안내 · 일정 시각 맞추기.
전부 메모리에서만 바꿨다 되돌린다(우회 권한은 잠깐 끈다). 사건은 `callEvent` 로 쏘지 않고 **우리 리스너에만** 준다 — 명령 로그(server)·명령 엿보기(chat)·
다른 플러그인의 레벨 보상이 가짜 사건에 반응하지 않게.

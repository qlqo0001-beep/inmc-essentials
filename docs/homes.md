# 홈(homes) — 운영 안내

CMI 의 sethome · home · homes · removehome · `Settings/Homes.yml`(개수 그룹·초과 막기·월드 한도·보호구역 금지·덮어쓰기 확인·이름 규칙·GUI) ·
homeInteractions 모듈(침대 홈)을 대신한다.

- 저장: DB 표 `ess_homes`(주인·이름 열쇠가 기본 키). 켤 때 전부 메모리로 읽고, 바뀌는 순간 DB 로 쓴다. 공용 DB(mysql)면 그 사람이 들어오기 직전에 다시 읽는다.
- 설정: `settings/homes.yml`(`/에센셜 리로드`), 메시지: `messages/homes.yml`.

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/홈` (home) | 홈이 하나면 바로, 여럿이면 화면(`gui.use: false` 면 채팅 목록), 없으면 안내 | `inmcessentials.homes.use` (모두) |
| `/홈 <이름>` | 그 홈으로(이름은 대소문자 무시) | `.use` |
| `/홈 <이름> <주인>` · `/홈 주인:이름` | 남의 홈으로 — 공개 홈은 `visit`, 비공개까지는 `others` | `inmcessentials.homes.visit` (없음) · `.others` (OP) |
| `/홈 <이름> <주인> <보낼 사람>` | 그 사람을 그 홈으로 보낸다(콘솔도) | `.others` (OP) |
| `/홈설정 [이름] [비공개\|-p] [칸] [재질\|손] [덮어쓰기] [주인:이름] [위치:월드;x;y;z]` (sethome) | 선 자리에 홈. 이름 없으면 `default-name`. 순서는 자유 — 첫 낱말이 이름, 숫자는 화면 칸, 그다음 낱말은 아이콘 재질 | `.use` · `주인:` 은 `.others` · `위치:` 는 `inmcessentials.homes.customloc` (OP) |
| `/홈삭제 [이름] [주인]` (delhome, removehome) | 지우기. 이름 없이 치면 눌러서 지우는 목록 | `.use` · 주인은 `.others` |
| `/홈목록` (homes) | 내 홈 화면(또는 채팅 목록) | `.use` |
| `/홈목록 <주인>` | 그 사람의 홈 화면 — 나가 있는 사람도(이름·닉네임) | `.others` (OP) |
| `/홈목록 near:<반경>` | 같은 월드에서 가까운 모든 사람의 홈 20개(거리·방향) | `inmcessentials.homes.near` (OP) |

그 밖의 권한: `inmcessentials.homes.bed` (모두) 침대 홈 · `.unlimited` (OP) 개수 무제한 · `.bypasslimit` (OP) 월드 한도 무시 ·
`.bypassprotection` (OP) 보호구역 금지 무시. 개수 권한(아래)은 글자로 짓는 노드라 선언돼 있지 않다. CMI 노드도 넘어가는 동안 통한다(아래 "CMI 권한 옮기기").

`/홈설정` 에서 같은 이름이 있으면 `confirm-overwrite: true` 일 때 채팅의 **[덮어쓰기]** 를 누르거나 `덮어쓰기` 를 붙여 다시 친다. 덮으면 아이콘·비공개·칸은
새로 적은 것만 바뀐다.

## 개수 제한

- `limits.groups` 의 그룹: 권한 `inmcessentials.homes.limit.<그룹>`(예: `…limit.vip`), 숫자: `inmcessentials.homes.limit.<숫자>`. 여럿이면 가장 큰 것,
  하나도 없으면 `limits.default`(1). 이 노드들은 **실제로 준 것만** 센다(LuckPerms 등) — op 라고 저절로 갖지 않는다. op 는 `.unlimited` 를 기본으로 가진다.
- `block-extra-homes: true` — 한도보다 홈이 많으면(강등 등) 침대 홈 말고는 홈 이동을 막는다. `notify-on-login` 이면 들어올 때 1초 뒤 알린다.
- 침대 홈은 `bed.counts-toward-limit: false`(기본)면 개수에 세지 않는다.
- `world-limits` — 월드 묶음마다 둘 수 있는 홈 수(0 = 그 월드들에선 못 만듦, 음수 = 이 묶음 무시). 우회 `.bypasslimit`. 침대 홈에도 걸린다
  (0 이면 그 월드에 침대 홈도 못 만듦, 그 밖에는 `bed.counts-toward-limit: true` 일 때만 침대 홈을 센다).

## CMI 권한 옮기기

넘어가는 동안 CMI 노드를 **함께** 인정한다(LuckPerms 에 이미 준 것이 바로 통하게 — 안 그러면 `block-extra-homes` 때문에 VIP 의 홈 이동이 막힌다).
CMI 를 지우기 전에 아래처럼 바꿔 두고, 다 바꿨으면 CMI 노드를 지우세요. `/에센셜 가져오기 cmi` 보고서에도 같은 안내가 남는다(CMI 그룹 수가 다르면 그것도).

| CMI | inmc-essentials |
|---|---|
| `cmi.command.sethome.<그룹>` (Homes.yml `Groups`) | `inmcessentials.homes.limit.<그룹>` (settings `limits.groups` — 그룹 이름은 대소문자 무시) |
| `cmi.command.sethome.<숫자>` | `inmcessentials.homes.limit.<숫자>` |
| `cmi.command.sethome.unlimited` | `inmcessentials.homes.unlimited` |
| `cmi.command.sethome.bypasslimit` | `inmcessentials.homes.bypasslimit` |
| `cmi.command.sethome.bypass` | `inmcessentials.homes.bypassprotection` |
| `cmi.command.sethome.customloc` | `inmcessentials.homes.customloc` |
| `cmi.bedhome` | `inmcessentials.homes.bed` (모두 — 기본 TRUE) |

## 보호구역 금지

`protection.check: true` 면 그 자리에서 블록을 부술 수 없을 때(WorldGuard 구역 — `testBuild`·우회 권한 확인, Lands 영지 — `BLOCK_BREAK` 역할 깃발) 홈을
못 만든다. 침대 홈도 같다. 둘 다 **리플렉션**으로 묻고, 없거나 묻다 실패하면 허락한다(실패는 서버 로그에 한 번 · `/에센셜 검증` 에 보임).

## 침대 홈

`bed.enabled: true` 면 침대를 우클릭할 때(`shift-only` 면 웅크린 채) 선 자리를 홈 `bed.name`(기본 `침대`)으로 정한다 — 잠자기·바닐라 리스폰 지정은 그대로.
같은 침대를 다시 누르면 아무 일도 없다. 그 침대를 부수거나 터지거나(TNT·크리퍼) 타면(`remove-on-break`) 홈을 지우고, 그 밖의 까닭으로 사라졌으면
이동할 때 알아채고 지운다.
- **오버월드 같은 보통 월드에서만** — 네더·엔드에서 누른 침대(터지는 침대, 침대 캐기)는 침대 홈을 바꾸지 않는다.
- 만들기와 같은 월드 한도·보호구역 검사를 거친다.
- `bed.name` 과 같은 이름의 **보통 홈**이 있으면 덮지 않고 알린다(그 홈의 이름을 바꾸거나 지우면 된다). 침대를 옮겨 다시 정해도 아이콘·비공개·칸은 남는다.
부활 순서(`/스폰 관리`)의 '침대 홈'·'기본 홈' 칸이 이 홈을 쓴다.

## 화면

54칸, 쪽당 45개(침대 홈 먼저, 그다음 만든 순서 — `/홈설정 이름 <칸>` 으로 자리를 정한 것은 그 자리). 아이콘: 클릭 = 이동 · 우클릭 = 손에 든 재질로 아이콘
(빈손 = 기본) · Shift+클릭 = 비공개 켜고 끄기 · Shift+우클릭 = 지우기(확인 창). 아래 줄: 침대 홈 · ◀ ▶ · 기본 홈 · 정보(개수/한도) · 여기에 새 홈 ·
`/뒤로` · `/사망지점`(teleport 기능의 명령이 있을 때만) · 닫기. 관리자가 남의 홈 화면을 열면 누르면 관리자가 그 홈으로 간다.
화면에서 누른 이동은 **`/홈 이름`(남의 홈이면 `/홈 이름 주인`)을 친 것과 같다** — commands 기능의 대기·쿨타임·비용이 명령 이름 `홈` 으로 걸린다
(화면을 `/홈목록` 으로 열었어도). 고치기(아이콘·비공개·지우기)는 누른 순간의 홈을 다시 읽어 고친다(그 사이 지워졌으면 알림).

## CMI 가져오기

`/에센셜 가져오기 cmi`(server 기능)가 CMI 유저의 홈(이름·위치·아이콘·비공개)을 넣는다. **같은 이름의 홈이 이미 있으면 건너뛴다**(DB 에도 넣기만 —
공용 DB 에서 다른 서버가 만든 홈을 덮지 않는다). 아이콘이 재질이 아니면(머리 텍스처 등) 기본 아이콘으로 두고 보고서에 남긴다. 월드가 지금 없어도 넣는다(열리면 쓴다).
이름이 `name-pattern`·길이에 맞지 않으면(빈칸·따옴표 등) 못 쓰는 글자를 `_` 로 바꿔 넣고 보고서에 "A → B" 로 남긴다(다시 가져와도 같은 이름이 나와 건너뛴다).
운영자가 `name-pattern` 을 넓히더라도 따옴표·`<`·`>`·빈칸은 넣지 마세요(명령으로 닿지 않습니다).
CMI 의 기본 홈 이름(`Settings/Homes.yml` `Homes.DefaultName`, 테섭 `Home`)인 홈은 **우리 기본 이름(`default-name`, 기본 `집`)으로 바꿔** 넣는다 —
맨 `/홈설정` 이 예전처럼 그 홈을 덮고, 부활 순서의 '기본 홈' 칸이 그 홈을 고른다. 그 사람의 CMI 홈에 이미 우리 기본 이름의 홈이 있으면 바꾸지 않는다.
우리 쪽에 이미 **다른 자리**의 기본 홈이 있으면(넘어온 뒤 맨 `/홈설정` 으로 만든 것) CMI 의 홈은 `Home` 이름 **그대로** 넣고 보고서에 남긴다 — 자리를
잃지 않는다(같은 자리면 '이미 있음' 으로 건너뛴다. 다시 가져오면 `Home` 이 이미 있어 건너뛴다).
바꾼 개수는 보고서에 남는다. 이름을 `Home` 그대로 두려면 가져오기 **전에** `default-name: Home` 으로 두세요.

## 검증기(`/에센셜 검증`)

- 스폰에 홈 창구가 걸려 있다 · 한도 권한(우리 것·CMI 것)을 읽는다 · 이름 대소문자 무시와 기본·침대 홈 찾기(메모리만)
- 침대 홈: 누르기·부수기 리스너가 MONITOR 에 있고, 침대 자리로 그 침대의 홈을 찾는다(발 밑 블록으로 메모리에만 넣었다 뺀다)

## 홈 화면에서 이동

화면의 홈을 누르면 `/홈 이름 [주인]` 을 **친 것으로** 돈다(명령 전처리 사건을 쏘고 취소되지 않으면 실행 — commands 기능의 `홈` 대기·쿨타임·비용이 그대로
걸린다). `player.chat` 을 쓰지 않으므로 클라이언트 채팅을 '숨김' 으로 둔 사람도 된다.
- WorldGuard·Lands 연결(`protection.check` 를 켰을 때)

## CMI 와 다른 점

- 홈 이름 규칙은 '쓸 수 있는 글자' 정규식(한글 포함)이다(CMI ValidNameRegex 는 '안 되는' 글자).
- 즐겨찾기(favorite) 표시·Q 키 편집·화면 버튼 자리/재질 설정(InfoButton·CloseButton·테두리·채움·Expanded)은 없다 — 고정 배치.
- 보호구역 검사는 WorldGuard·Lands 만 본다(CMI 는 가짜 블록 부수기 사건으로 모든 보호 플러그인을 물었다 — 다른 플러그인의 부수기 처리를 건드려 쓰지 않았다).
- 홈 개수·목록 플레이스홀더, Essentials 홈 가져오기는 없다.
- 침대 홈은 따로 표시된 홈이라 기본 홈 이름과 겹치지 않는다(CMI 는 DefaultBedName 이 DefaultName 과 같으면 같은 홈을 덮었다).

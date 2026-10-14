# 홀로그램(holograms) — 운영 안내

CMI 의 `/cmi hologram` · `/cmi hologrampages` · 홀로그램 편집기 · config `Holograms` · `Saves/Holograms.yml` · 개인 옵션 `visibleHolograms` 를 대신한다.

- 저장: `plugins/inmc-essentials/holograms.yml`(고칠 때마다 원자적으로 통째로 씀 — 엔티티는 저장하지 않으므로 **정의는 이 파일에만** 있다).
  읽지 못한 칸은 지우지 않고 그대로 되써 넣는다(서버 로그에 **까닭과 함께** 경고 — 예: `pages 1쪽 1번째 줄 의 actions 의 'rigth' 은(는) 클릭 종류가 아닙니다`).
  모르는 클릭 종류 · 목록이 아닌 `actions`/`pages`/`lines` · 위치를 못 읽는 홀로그램은 조용히 고쳐 읽지 않고 못 읽은 칸으로 둔다(다음 저장에서 명령이 사라지지 않게).
  같은 이름(대소문자 무시)의 못 읽은 칸이 있으면 그 이름으로 새로 만들지 않는다.
- **YAML 문법이 깨진 `holograms.yml`** 은 빈 것으로 읽지 않는다 — 파일은 그대로, 옆에 `holograms.yml.broken-<시각>` 사본, 서버 로그에 까닭, 그리고
  **저장을 잠근다**(고치는 명령·편집 화면·CMI 가져오기 거절). 엔티티는 저장하지 않아 정의가 이 파일에만 있으므로 빈 것으로 덮으면 모두 사라진다.
  켤 때 깨졌으면 홀로그램이 하나도 서지 않고, `/홀로그램 다시읽기` 때 깨졌으면 지금 것을 그대로 쓴다. 고치고 `/홀로그램 다시읽기` 가 성공하면 풀린다.
- 설정: `settings/holograms.yml`(`/에센셜 리로드`), 메시지: `messages/holograms.yml`. DB 는 쓰지 않는다.

## 명령

| 명령 (영어) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/홀로그램` (hologram, holo) | 관리자: 목록 화면 · 그 밖: 사용법 | 아래 셋 중 하나 |
| `/홀로그램 목록` (list) · `/홀로그램 가까이 [반경]` (near, 기본 32) | 목록(화면 — 가까운 순, 클릭 = 편집, Shift+우클릭 = 그리로) · 근처 채팅 목록 | `inmcessentials.holograms.admin` (OP) |
| `/홀로그램 만들기 <이름> [첫 줄]` (new) | 눈높이 조금 위에 만들기 | admin |
| `/홀로그램 삭제 <이름>` (remove) · `편집 <이름>` (edit) · `여기로 <이름>` (movehere) · `이동 <이름>` (teleport) | | admin |
| `/홀로그램 줄추가 <이름> <글>` (addline) | 마지막 쪽 끝에. `icon:손` = 손에 든 아이템, `icon:minecraft:diamond` · `icon:mmoitems:종류:아이디` · `icon:<커스텀아이템 참조>` = 아이콘 줄, `!nextpage!` = 새 쪽 | admin |
| `/홀로그램 줄수정 <이름> <줄> <글>` (setline) · `줄끼우기` (insertline) · `줄삭제 <이름> <줄>` (removeline) | 줄 = `3`(1쪽 3번째) 또는 `2:3`(2쪽 3번째). 고친 줄의 누르기 동작은 남는다 | admin |
| `/홀로그램 쪽 <이름> [추가\|삭제 <번호>]` (page add/remove) | 쪽 보기·더하기·지우기 | admin |
| `/홀로그램 동작 <이름> <줄\|쪽\|쪽:N> <좌\|우\|shift좌\|shift우\|아무> [명령]` (action) | 누르기 명령 더하기. 명령 없이 치면 그 클릭의 명령을 비운다 | admin |
| `/홀로그램 다시읽기` (reload) | `holograms.yml` 을 다시 읽기 | admin |
| `/홀로그램 넘기기 <이름> [다음\|이전\|번호]` (pages) | 내 화면의 쪽 넘기기(CMI `/hologrampages`) | `inmcessentials.holograms.pages` (모두) |
| `/홀로그램 넘기기 <이름> <플레이어> [다음\|이전\|번호]` | 남의 쪽 | `inmcessentials.holograms.pages.others` (OP) |
| `/홀로그램 보기` (toggle) | 내 화면의 홀로그램 켜고 끄기(CMI `visibleHolograms`). 플레이어 메뉴 `/설정` 에도 뜬다 | `inmcessentials.holograms.toggle` (모두) |

**`admin` 만으로는 콘솔 누르기 명령을 더하거나 바꿀 수 없다** — `inmcessentials.holograms.console`(OP)이 더 있어야 한다(콘솔 명령 = 콘솔 권한).
없으면 `player:` 명령 · `[nextPage]`/`[prevPage]` 만 더할 수 있고, 이미 있는 콘솔 명령은 지우거나 순서를 바꿀 수만 있다. 빌더에게는 `admin` 만 줄 것.

그 밖의 권한: `inmcessentials.holograms.click` (모두) 홀로그램 누르기. 홀로그램마다 `권한` 을 정하면 그 노드가 있어야 보인다(아무 노드나 —
CMI 에서 가져온 것은 `inmcessentials.holograms.see.<이름>`, 선언 안 된 노드라 OP 는 저절로 가진다).

## 줄 · 쪽 · 누르기

- 글 줄: MiniMessage · `&` 색 · PAPI · `{player}`. 아이콘 줄: core 아이템 참조(`minecraft:` · `mmoitems:` · 커스텀아이템) — 정의가 바뀌면 따라간다. 손에 든 꾸민 아이템은 그대로(스냅샷).
- 위치는 **첫 줄의 윗자리**이고 줄은 아래로 쌓인다(글 `line-height`, 아이콘 `icon-height` × 크기).
- 쪽은 **사람마다** 따로다. 자동 넘김(`page-seconds`)은 모두의 기준 쪽을 넘기고, 누르거나 `/홀로그램 넘기기` 로 넘긴 사람은 그 기준에서 떨어진 만큼을 갖는다.
- 누르기: 줄의 동작 → 없으면 그 쪽의 동작 → 그것도 없고 `누르면 쪽 넘기기` 가 켜져 있고 쪽이 둘 이상이면 우클릭 = 다음, 좌클릭 = 이전.
  클릭 종류는 정확히 → Shift 없는 같은 쪽 → 아무 클릭 순으로 찾는다. 동작 한 줄: `player:` 로 시작하면 그 사람이, 아니면 콘솔이(CMI `asPlayer!`/`asConsole!` 도),
  `[nextPage]`·`[prevPage]`(`[다음쪽]`·`[이전쪽]`)는 쪽 넘기기. `{player}` · `[playerName]` · `{홀로그램}` · PAPI.
- 누르기는 **그 사람에게 지금 보여 준 엔티티일 때만** 돈다 — 숨기기(`hideEntity`)는 클라이언트 쪽 일이라, 고친 클라이언트가 안 보이는 엔티티 번호로
  누르기를 보내도(권한·조건·개인 끔·다른 쪽) 명령은 돌지 않는다. 누를 때 보이기 조건도 다시 본다.
- 보이기: 거리(`view-range`) 안 + 개인 보기 켜짐 + 권한 + 조건. 조건은 `%플레이스홀더% 연산 값`(연산 `==` `!=` `>=` `<=` `>` `<` `포함`/`contains` `안포함`/`!contains`,
  둘 다 숫자면 숫자 비교) — **못 읽는 조건이면 아무에게도 안 보인다**(편집 화면이 빨갛게 알린다).

## 편집 화면

한 쪽의 줄들 — 클릭 = 글 고치기(아이콘 줄은 손에 든 것으로 바꾸기) · 우클릭 = 누르기 동작(클릭 종류마다 여러 줄) · Shift+좌클릭 = 위로 · Shift+우클릭 = 지우기(확인).
아래: 글 줄 더하기 · 아이콘 줄 더하기(손) · 쪽 누르기 동작 · 쪽 더하기 · 쪽 지우기(확인) · 설정 · 켜짐 · 여기로 옮기기 · 그리로 이동 · ◀ ▶ 쪽 · 지우기(확인).
설정: 보이는 거리 · 글 갱신 · **갱신 거리**(이 안에 보는 사람이 있을 때만 다시 그림 — 비우면 설정의 `update-range`) · 자동 쪽 넘김(비우면 기본값) · 권한 · 조건 ·
방향(늘 나를 봄/세로축/가로축/고정 + 각도) · 크기 · 배경(`default`·`none`·`#AARRGGBB`) · 글 그림자 · 벽 너머로 보이기 · 누르면 쪽 넘기기 · 누르는 너비(0 = 글 길이로) ·
**글 정렬**(가운데/왼쪽/오른쪽 — 여러 줄로 감길 때) · **감기 너비**(기본 1000 — 줄이면 긴 글이 여러 줄로) · **글자 불투명도**(0~255 — 마크는 아주 낮은 값(≈ 26 아래)을
불투명으로 그린다). 바꾸면 바로 저장하고 다시 세운다. `holograms.yml` 열쇠: `update-range` · `alignment` · `line-width` · `text-opacity`.

## 어떻게 세우나 (성능)

- 엔티티는 **저장하지 않는다**(`isPersistent = false`) — 크러시에도 남지 않는다. 보는 사람이 있을 때만(청크가 올라와 있을 때) 세우고 아무도 안 보면 거둔다.
  PDC `inmcess:holo`(= 홀로그램 이름) 표시가 달렸는데 우리 것이 아닌 엔티티는 엔티티가 올라올 때·켤 때 지운다(플러그인 리로드 뒤 남은 것).
- 줄마다 TextDisplay/ItemDisplay 하나(+ 누를 것이 있으면 Interaction). 엔티티는 기본으로 아무에게도 안 보이고 정해진 사람에게만 보인다(`showEntity`).
- 한 쪽에 `{player}` 나 사람 PAPI 가 없으면 그 쪽을 보는 사람 모두가 엔티티 **한 벌**을 같이 본다. 있으면 **보는 사람마다 한 벌**(상한 `max-personal-viewers`,
  넘는 사람은 플레이스홀더를 사람 없이 푼 공용 글). `global-placeholders` 로 시작하는 PAPI(`%server_online%` 등)는 사람과 무관한 것으로 본다.
- 갱신: `update-seconds`(기본 2초)마다 플레이스홀더가 있는 줄만, `update-range` 안에 보는 사람이 있는 묶음만 다시 풀고 **글이 바뀐 때만** 보낸다.
  0 으로 두면 플레이스홀더 줄이 세울 때의 값에 멈춘다. 보는 사람 고르기는 1초마다(반복 작업 한 칸).
- 설정을 다시 읽으면(`/에센셜 리로드`) 이미 선 묶음을 거두고 새 줄 높이·거리·배경으로 모두 다시 세운다(한 홀로그램이 두 간격으로 보이지 않게).

## 설정 (`settings/holograms.yml`)

| 열쇠 | 기본 | 뜻 (CMI) |
|---|---|---|
| `view-range` | 16 | `Defaults.viewRange` |
| `update-seconds` | 2 | `Defaults.Interval.Update`(틱 → 초). 0 = 다시 그리지 않음(플레이스홀더가 멈춘다) |
| `update-range` | 8 | `Defaults.updateRange` — 이 거리 안에 보는 사람이 있는 묶음만 다시 그린다(0 = 보이는 거리 전체). 홀로그램마다 덮어쓸 수 있다 |
| `page-seconds` | 0 | `Defaults.pageChangeInterval`(틱 → 초) |
| `line-height` · `icon-height` | 0.28 · 0.55 | 줄 높이(블록) |
| `max-personal-viewers` | 30 | 사람마다의 글 상한 |
| `personal-fallback` | '' | 상한을 넘은 사람의 공용 글에서 `{player}`·사람 PAPI 자리에 보일 글(비우면 지운다) |
| `global-placeholders` | server_, math_, time_ | 사람과 무관한 PAPI |
| `click-cooldown-ms` | 250 | 같은 사람의 클릭 간격 |
| `create-height` | 0.6 | 새 홀로그램을 눈높이에서 얼마나 위에 |

## CMI 가져오기

`/에센셜 가져오기 cmi`(server 기능)가 `Saves/Holograms.yml` 을 넘기면 읽는다(jar `CMIHologram.serialize/deserialize` 로 확인한 열쇠 — 새 모양 `Settings/Text/Page/Interaction`,
옛 모양 `Range/Interval/RequiresPermission/Commands/PageInterval/AutoPage`). 줄의 `!nextpage!` 는 쪽, `{#rrggbb}`·`{#이름}` 은 MiniMessage 색, `[playerName]` 은 `{player}`,
`ICON:`/`SICON:` 은 아이콘 줄, `_` 는 빈 줄, CText(`<T>글</T><C>명령</C>`)는 글 + 그 줄의 명령으로. 같은 이름(또는 같은 이름의 못 읽은 칸)이 있으면 건너뛴다.

CMI 가 **기본값이면 쓰지 않는** 열쇠는 CMI 기본값으로 읽는다(jar javap): `Interaction.Enabled`(옛 `Interactable`)가 없으면 **누르기 꺼짐** — 그때는
클릭 명령도 CText 명령도 붙이지 않고 알린다. 배경은 `BColor` 없음 = 검정, `BAlpha` 없음 = 125(둘 다 없어도 검정 125 — 갑옷 거치대 방식이었으면 배경 없음).
`Page.Change`(옛 `PageInterval`)만 자동 넘김이고, `Page.Auto`(옛 `ND.AutoPage`)는 **쪽 넘김 버튼 줄**(`<<<< >>>>`)이라 대신 "누르면 쪽 넘기기" 를 켠다(알림).
정렬 `Text.Align` · 감기 너비 `Text.Line` · 글자 투명도 `Text.TAlpha`(옛 `ND.Align`·`ND.LineW`·`ND.TAlpha`)도 옮긴다.
**위치**: CMI 의 `Loc` 은 홀로그램의 아래다(가운데 = Loc + 0.25 + 첫 쪽 줄 수 × 0.25 / 2). 우리 위치는 첫 줄의 윗자리이므로 **가운데가 같도록** 올려 넣는다
(옛 모양은 CMI 처럼 높이만큼 먼저 내린다). 줄 간격이 달라(우리 0.28) 위아래 끝은 조금 다를 수 있다.
`[playerDisplayName]` 은 `{player}`(이름)로 바뀐다(알림). 그 밖의 CMI 변수(`[world]` 등)와 `%cmi_…%` PAPI 는 CMI 를 빼면 글자 그대로 보이므로 줄·명령마다 알린다. 위치를 못 읽은 홀로그램 · 모르는 속성 · 줄(Lines) 모양은 `못 읽음`,
옮기지 못한 것(Icon/Animation/Board 설정, 마우스 올리기 글, 그라데이션)은 보고서에 남는다. **테섭 CMI 의 `Saves/Holograms.yml` 은 비어 있어 실제 표본으로 검증하지 못했다.**

## CMI 와 다른 점

- 패킷이 아니라 진짜 Display 엔티티(저장 안 함)다 — 사람마다 다른 글은 사람마다 엔티티 한 벌(상한 있음).
- 갱신·자동 넘김의 가장 짧은 주기는 1초(CMI 는 틱). 0.x 초는 1초로 올린다.
- 이미지 줄(`img:`) · 파티클 줄 · 보드 · 애니메이션(나타나기·사라지기) · 시선(LOS) · 밝기 · 오프셋/회전 세부 · 마우스 올리기 글·파티클은 없다.
- CText 의 구간별 명령은 줄 하나의 명령으로 합친다(줄마다 누르는 상자 하나). 좌/우/Shift 클릭은 줄마다 따로 정할 수 있다.
- `[prevPage]`/`[nextPage]` 버튼 줄 대신 "누르면 쪽 넘기기"(우 = 다음, 좌 = 이전)와 동작 `[nextPage]`/`[prevPage]`.
- 양면 글(`Double`) · 글 채우기(`Fill`/`Filler`) · 아이콘 줄 따로 크기·방향은 없다. CMI 편집기의 "아래로 놓기"(order — `DownOrner`)는 9.8.9.8 에서 쓰이지 않는 옵션이라
  (값을 늘 false 로 돌려준다) 옮기지 않는다 — 우리는 늘 위치에서 아래로 쌓는다.
- 홀로그램 그룹 · HolographicDisplays 가져오기(`/cmi importfrom`)는 없다.

## 검증기 (`/에센셜 검증`)

holograms.yml 을 다 읽었다(깨져 잠겼는지 · 못 읽은 칸과 까닭) · 보는 사람에게만 세우고 보임 · 조건이 틀리면 안 보임 · 사람마다의 글 · 누르기(진짜 상호작용·공격 사건) ·
누르면 쪽 넘김(빈 쪽 건너뜀) · 개인 보기 끔 · 상한을 넘은 공용 글 · 청크 내림 · 남은 엔티티 청소 · **권한이 없으면 안 보이고 있으면 보임**(붙임) ·
**아이콘 줄은 ItemDisplay** · **자동 넘김 주기가 되면 쪽이 넘어감**(이 홀로그램의 1초 일만) · **안 보이는 홀로그램은 누르기가 와도 명령을 안 돌림**.
전부 메모리만(명령은 모으기만).

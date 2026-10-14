# 갑옷 거치대·그림·액자·표지판(decor) — 운영 안내

CMI 의 armorstand(편집기 · armorstand 모듈) · paintingEditor 모듈 · itemframe · dsign(dynamicSigns 모듈) · se/sc(signEdit 모듈) 를 대신한다.
설정 `plugins/inmc-essentials/settings/decor.yml`, 메시지 `messages/decor.yml`. 저장: 거치대 템플릿 `decor/armorstand-templates.yml` · 동적 표지판
`decor/dynamic-signs.yml`(둘 다 워커에서 원자적으로 · DB 없음).

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/갑옷거치대` (armorstand) | 바라보는 거치대의 편집기. **웅크리고 우클릭**해도 열린다 | `inmcessentials.decor.armorstand` (모두) |
| `/갑옷거치대 가까이` · `마지막` (near · last) | 16블록(`near-radius`) 안의 거치대 목록(누르면 편집) · 최근에 편집한 것 | 같음 |
| `/갑옷거치대 템플릿 <이름>` (template) | 가방의 갑옷 거치대 하나를 그 템플릿 거치대로 바꿔 준다 — 놓으면 그 모양. 크리에이티브·`.template.free` 는 그냥 받는다 | `.armorstand.template.give` (모두) |
| `/갑옷거치대 템플릿목록` · `템플릿삭제 <이름>` | 목록 · 지우기 | 목록 모두 · 지우기 `.armorstand.template.save` (OP) |
| `/표지판편집 <줄 1-4> [글]` (se) | 바라보는 표지판(바라보는 면)의 그 줄. `\n` 으로 다음 줄들까지. 글을 비우면 그 줄을 지움 | `inmcessentials.decor.sign.command` (모두) |
| `/표지판복사 [플레이어] [한면]` (signcopy) | 바라보는 표지판의 글(양면·색·빛나는 글)을 기억하고, **다른 표지판을 치면** 붙인다(여러 번). 표지판이 아닌 블록을 치면 끝. `한면`(oneside) = 바라본 면만 친 면에 | `inmcessentials.decor.sign.copy` (OP) · 남에게 맡기기 `.sign.copy.others` (OP) |
| `/동적표지판 [생성\|편집\|삭제\|목록]` (dsign) | 바라보는 표지판을 동적 표지판으로(`생성` — 지금 앞면 글이 첫 줄들) · 설정 화면 · 끄기 · 전부 보기 | `inmcessentials.decor.dsign` (OP) |
| `/액자 <투명\|고정\|무적\|발광\|전부\|정보> [켜기\|끄기] [주변 [반경] [강제]]` (itemframe) | 바라보는 액자를 바꾼다(없으면 뒤집기). `발광` = 액자 ↔ 발광 액자(새 엔티티로 — 아이템·회전·방향을 옮긴다). `전부` = 투명·고정·무적. `주변 8` = 반경 안 액자 전부(`켜기`가 기본) — **액자마다 보호 구역을 본다**(`.itemframe.any` 가 아니면), **다른 플러그인이 PDC 표식을 붙인 액자(가구·지도 그림)는 건너뛴다**(`강제` 를 붙이면 바꾼다). 건너뛴 수를 알린다 | `inmcessentials.decor.itemframe` (OP) · 주변 `.itemframe.area` (OP) |

## 편집기(갑옷 거치대)

- 자세 6부위 × X·Y·Z: 좌클릭 +5° · 우클릭 −5° · **Shift 와 함께 10배** · **Q(버리기 키) 0으로**. 몸 방향도 같다.
- 위치 X·Y·Z: ±0.05 블록(Shift 10배). **처음 연 자리에서 `max-move`(2) 블록까지만** — 조금씩 밀어 남의 땅에 넣지 못하게(서버를 다시 켜면 잊는다).
  다른 블록으로 넘어갈 때마다 **그 블록에 거치대를 놓을 수 있는지**(거치대를 놓는 사건 `EntityPlaceEvent` 를 가짜로)를 보고 막히면 옮기지 않는다 — 영지 경계에서
  이웃 영지로 밀어 넣지 못하게(`.armorstand.any` 는 건너뜀). 위아래는 중력을 꺼야 남는다.
- 크기: ±0.05(Shift 10배, Q 는 1), 설정 `min-scale`~`max-scale`(0.5~3).
- 켜고 끄기: 받침대 · 작게 · 보이기 · 팔 · 중력 · 발광 · 무적 · 이름 보이기 · 장비 잠금(모든 장비 칸을 바닐라에서 못 바꾸게). **장비 잠금을 푸는 것은 `.armorstand.any`
  만**(잠그기는 `.toggle`) — 관리자가 잠근 전시용 거치대를 풀고 장비를 가져가지 못하게. 잠긴 거치대에 잠기지 않은 모양을 붙여 넣어도 잠금은 그대로다.
- 장비 6칸(머리·몸·다리·발·주 손·보조 손): 커서에 든 것과 맞바꾼다. 바꾸기 전에 바닐라와 같은 `PlayerArmorStandManipulateEvent` 를 쏜다 — 보호 구역(Lands)이 막으면
  안 바뀌고, 기록(CoreProtect)에 남는다. 클릭한 틱에 화면을 닫았으면 바꾸지 않는다.
- 이름: 입력창(색 권한이면 색). 모양 복사·붙여넣기(사람마다, 장비 빼고). 템플릿 저장(이름: 소문자 영문·숫자·한글·`_ -` 32자)·템플릿 고르기.
- 한 거치대는 한 사람만 편집한다. 명령으로 열 때(`/갑옷거치대` · `가까이` 의 [편집] · `마지막`)는 `.armorstand.any` 가 아니면 **늘** 그 거치대를 만지는 사건을
  가짜로 쏴 보고 보호 구역(Lands·WorldGuard)이 막으면 못 연다(바라보기는 벽을 뚫고, 가까이 목록은 64블록까지라). 가짜 사건은 **보조 손**으로 쏘고 HIGHEST 에서
  **처음 판정만** 읽은 뒤 우리가 취소한다 — 인첸트 우클릭 발동(주 손만 본다)·기록(CoreProtect)·업적(MONITOR)이 가짜 클릭에 반응하지 않게. `/액자` 도 같다.
  웅크려 우클릭은 그 우클릭이 이미 막혔으면 열리지 않는다.
- **다른 플러그인의 거치대는 열지 않는다**(`.armorstand.any` 제외): 마커 거치대 · Citizens NPC(메타데이터 `NPC`) · Shopkeepers 상인(`shopkeeper`) · PDC 에
  `minecraft`·`inmcess` 가 아닌 열쇠가 붙은 것(가구·모델·홀로그램 플러그인). 웅크려 우클릭이면 그 플러그인이 클릭을 다루게 두고, `가까이` 목록에서도 뺀다.
- 붙여넣기 · 템플릿 고르기 · 템플릿 거치대 놓기는 **편집기 버튼과 같은 권한으로 칸마다** 거른다 — 자세 `.pose` · 받침대·작게·보이기·팔·중력·발광 `.toggle` ·
  이름 보이기 `.name` · 무적 `.invulnerable` · 크기 `.scale`(범위 안으로) · 잠금 풀기 `.any`. 권한이 없는 칸은 거치대의 지금 값 그대로. 편집기의 템플릿 고르기는
  `.copy` 가 있어야 한다. 템플릿 거치대를 받을 때 가방이 차 있으면 우편으로(우편이 없으면 발밑에).

| 권한 (기본) | 동작 |
|---|---|
| `.armorstand.pose` (모두) · `.move` (모두) · `.scale` (모두) · `.toggle` (모두) · `.name` (모두) · `.equip` (모두) · `.hands` (모두) · `.offhand` (모두) · `.copy` (모두) | 각 버튼 |
| `.armorstand.invulnerable` (OP) | 무적 켜고 끄기 |
| `.armorstand.move.unlimited` (OP) · `.scale.unlimited` (OP, 0.0625~16) · `.any` (OP, 보호 확인·장비 잠금 무시) | 제한 풀기 |
| `.armorstand.template.save` (OP) · `.template.give` (모두) · `.template.free` (OP) | 템플릿 |
| `inmcessentials.decor.armorstand.template.use.<이름>` | 설정 `template-permission: true` 일 때 그 템플릿을 쓰는 권한(이름으로 짓는 노드 — 선언하지 않았으므로 기본 OP). `use.` 가지라 `give`·`save`·`free` 라는 이름의 템플릿도 위 노드와 겹치지 않는다 |

## 명령 없는 것

| 무엇 | 권한 (기본) | 설정 |
|---|---|---|
| **그림 바꾸기** — 웅크리고 그림을 우클릭하면 같은 크기의 다음 그림(한 번에 한 칸 — 클라이언트가 보내는 두 사건 중 하나만 센다), 그 뒤 5초 동안 웅크린 채 **마우스 휠**로 앞뒤로(단축바는 그대로). 행동 표시줄에 이름·id·n/m. 데이터팩 그림도. 바꾸기 전에 **그 그림을 뗄 수 있는지**(`HangingBreakByEntityEvent` 를 가짜로)를 본다 — 그림 우클릭을 막지 않는 보호 플러그인에서도 남의 땅 그림은 못 바꾼다 | `inmcessentials.decor.painting` (모두) | `painting.*` |
| **표지판 다시 편집** — 웅크리고 **맨손으로** 표지판을 우클릭하면 바닐라 표지판 화면(설정 `dialog: true` 면 입력창). **밀랍칠한 것**은 권한이 있으면 늘 웅크려서 **입력창**으로(밀랍은 그대로 — 벗기지 않는다). 바닐라와 같이 어드벤처·관전 모드는 손대지 않고, **클릭 명령이 든 면**은 명령이 먼저(편집 화면을 열지 않는다), **금지 목록** 표지판은 우클릭을 막지 않는다(우클릭 상점이 돈다). 동적 표지판이면 그 설정 화면(권한이 있을 때) | `.sign.edit` (모두) · 밀랍 `.sign.edit.waxed` (OP) | `sign-edit.*` |
| **바닐라 우클릭 편집 화면도 같은 규칙** — 1.20+ 바닐라는 맨손 우클릭으로 편집 화면을 스스로 연다. `sign-edit.enabled` 면 그 화면(`PlayerOpenSignEvent` 원인 INTERACT)을 막는다: **`.sign.edit` 권한이 없을 때**(`.sign.any` 는 통과 — 그래서 기본 그룹에서 `.sign.edit` 를 빼면 바닐라 화면으로도 표지판을 못 고친다) · 개인 설정을 껐을 때 · `require-sneaking: true`(기본)인데 웅크리지 않았을 때 · **금지 목록** 표지판(우회 권한 제외) · `/동적표지판` 권한 없이 동적 표지판. 막아도 우클릭 자체는 그대로(상점 표지판이 돈다). 놓을 때 열리는 화면·우리가 여는 화면은 건드리지 않는다. **기본값으로는 웅크리지 않은 맨손 우클릭이 더는 편집 화면을 열지 않는다** — 바닐라처럼 두려면 `require-sneaking: false` | `.sign.edit` (모두) · 금지 목록 우회 `.sign.blacklist.bypass` (OP) | `sign-edit.enabled` · `require-sneaking` |
| **표지판 색** — 바닐라 화면·입력창·`/표지판편집` 으로 쓴 글의 `&a`·`&#rrggbb`·`<red>`·`<gradient>` 를 색으로(새로 놓은 표지판도). 바닐라 화면은 **이번에 고친 줄만**, **색·꾸밈까지만**(서식 권한이어도) — 남이 글자로 써 둔 `<click…>` 이 관리자가 열었다 닫을 때 살아나지 않게. 클릭·마우스 올리기는 서식 권한이 입력창·`/표지판편집` 으로 직접 친 글에만 | `inmcessentials.decor.color` (모두 — 색·꾸밈만) · `inmcessentials.decor.format` (OP — `<click>`·`<hover>` 까지) | — |
| **금지 목록** — 첫 줄이 `[Shop]` 등이면 편집·복사·붙여넣기 금지 | 우회 `.sign.blacklist.bypass` (OP) | `sign-edit.blacklist` |

개인 설정 `essentials.shift-sign-edit`(웅크려 우클릭 표지판 편집, 기본 켬 — CMI `shiftSignEdit`)이 플레이어 메뉴 `/설정` 에 나온다. 끄면 우클릭으로는
표지판 편집 화면이 열리지 않는다(바닐라 화면도 — `/표지판편집` 명령은 된다).

표지판을 바꾸는 길은 전부 `SignChangeEvent` 를 지난다 — 보호 구역(Lands·WorldGuard 가 이 사건을 막는다 — 서버 jar 로 확인)·상점·기록(CoreProtect) 플러그인이 보고,
막으면 바뀌지 않는다. 그래서 `/표지판편집` 은 가짜 우클릭을 쏘지 않는다(가짜 우클릭이 손에 든 커스텀 아이템·인첸트를 발동시켰다). `/표지판복사` 의 붙여넣기는 진짜 좌클릭이
보호 구역에 막혔으면 하지 않고(`.sign.any` (OP) 는 건너뜀), 통과해도 `SignChangeEvent` 를 다시 지난다. 표지판의 클릭 사건은 **누른 사람의 권한으로 명령을 실행**하므로 색 권한으로는 넣을 수 없다.
동적 표지판은 `/동적표지판` 권한이 없으면 바닐라 화면으로도 고칠 수 없다(고친 글이 다음 갱신까지 남던 것 — 권한 있는 사람이 고치면 다음 틱에 다시 그린다).

## 동적 표지판

- 화면: 갱신 주기(초, 기본 5 — 줄이 넷 넘으면 굴리는 빠르기도) · 작동 범위(블록, 기본 16 — 이 안에 사람이 있을 때만 갱신) · 사람마다 다르게 · 줄 편집(입력창,
  한 줄에 하나) · 미리 보기 · 끄기(Shift+클릭).
- 줄: MiniMessage·`&` 색 · `%PAPI%` · core 토큰. **넷 넘으면** 한 칸씩 위로 굴린다. **`{스크롤}`로 시작하는 줄**은 글자가 1초에 한 칸씩 옆으로 흐른다(`marquee-width`
  글자 창, 줄 앞의 색 태그를 다시 입힌다).
- **사람마다 다르게 = 끔**(기본): 진짜 블록의 앞면을 바꾼다 — 모두에게 같은 글이라 서버 플레이스홀더(`%server_online%`)만 뜻이 있다.
  **켬**: 보는 사람마다 `Player.sendSignChange`(패킷 하나)로 그 사람의 플레이스홀더(`%player_name%`) — 싸다. 블록 자체는 그대로라 청크를 다시 받으면 원래 글이
  잠깐 보였다가 다음 갱신에 바뀐다(범위에 새로 들어온 사람에게는 바로 보낸다).
- 글이 **바뀐 때만** 보낸다. 표지판이 부서지면 그 동적 표지판을 지운다(로그). 최대 `max-signs`(200).
- 플레이스홀더 값(닉네임·칭호·기록 이름)에 든 `<click…>` 은 **클릭 사건이 되지 않는다** — 줄 원문에 `<click` 이 없으면 그린 결과의 클릭 사건을 벗긴다(공용 모드는
  진짜 블록에 쓰고, 표지판 클릭 명령은 누른 사람이 권한 레벨 2로 실행하므로). 관리자가 줄에 직접 쓴 `<click…>` 은 그대로.

## CMI 가져오기

`/에센셜 가져오기 cmi` 가 CMI config.yml 의 `Signs.editBlackList` + `Optimizations.SignEdit.BlackList`(합침) · `Signs.RequireSneaking` · `Signs.DialogEditor` ·
`Optimizations.ArmorStands`(MinScale · MaxScale · Templates.SpecificPermission)를 이 설정에, `Saves/DynamicSigns.yml` 의 동적 표지판
(`Loc`·`Lines`·`Interval`·`Personal`·`Range`)을 우리 것으로 옮긴다(같은 자리가 이미 있으면 건너뜀). 거치대 템플릿 파일(`armorStands.yml`)은 형식을 확인하지 못해
옮기지 않고 보고서에 "못 읽음" 으로 남긴다 — 편집기에서 다시 저장하세요. 테섭에는 두 파일이 없어 실제 값으로는 확인하지 못했다.
`CheckBlockPlace` 는 옮기지 않는다(우리는 명령으로 거치대를 열 때 늘 보호 구역을 본다 — 보고서에 값과 함께 적힌다). `RequireSneaking: false` 를 옮기면 보고서에 그 뜻을 적는다.
CMI `Settings/Modules.yml` 에서 꺼 둔 모듈(paintingEditor · armorstand · signEdit)은 우리 쪽도 끄고, dynamicSigns 가 꺼져 있었으면 동적 표지판을 옮기지 않는다.
사람마다의 CMI 개인 옵션 `shiftSignEdit`(`users.Options` 2번째 칸)을 끈 사람은 개인 설정 `essentials.shift-sign-edit` 를 끈다(못 읽은 칸은 보고서에 "못 읽음").
읽기와 보고서 줄은 명령을 친 그 자리에서 — 보고서 파일·요약에 이 기능의 줄과 못 읽은 줄이 함께 나온다.

## CMI 와 다른 점

- 템플릿·복사에 **장비는 담지 않는다** — 템플릿 아이템·붙여넣기로 아이템이 생기면 복사 길이 된다. 템플릿 거치대를 받으려면 가방의 거치대 하나를 낸다.
- 거치대 이동은 처음 연 자리에서 `max-move` 블록까지(관리자 제외).
- 크기 권한은 `cmi.command.armorstand.scalemin/scalemax.<값>` 대신 설정 범위 + `scale.unlimited` 하나.
- 표지판 편집은 기본 **웅크림 + 맨손** — 바닐라가 스스로 여는 맨손 우클릭 편집 화면도 웅크려야 열린다. `require-sneaking: false` 면 웅크리지 않아도(바닐라와 같음,
  밀랍칠한 것은 늘 웅크림). `.sign.edit` 권한·금지 목록·개인 설정도 바닐라 화면에까지 듣는다(CMI 는 1.20 바닐라 화면을 막지 못했다 — 권한을 빼도 바닐라로는 고칠 수 있었다).
- 그림 바꾸기는 그 그림을 뗄 수 있는 사람만(CMI 는 보호 확인이 없었다). 다른 플러그인의 거치대(NPC·상점·가구·마커)는 편집기로 열지 않는다.
- `/표지판복사` 의 영어 별칭은 `signcopy`(`sc` 는 스태프 채팅이 쓴다).
- 액자 `발광` 은 CMI 에 없던 것(액자 ↔ 발광 액자). CMI 는 주변 전부에 걸었고 우리는 바라보는 하나가 기본, `주변 <반경>` 으로 전부.
- 동적 표지판의 범위 확인은 1초마다(CMI `Signs.CheckInterval` 3초).

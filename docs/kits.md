# 킷(kits) — 운영 안내

CMI 의 kit · kitcdreset · kitusagereset · kiteditor · kitpreview · kitopen, `Kits/Kits.yml`(Delay · MoneyCost · ExpCost · Conditions · Commands ·
Group/Weight/CommandName · 변수) · `config.yml` 의 `Kits`(GUI · KitPreview · FillEmptyFields · DateFormat) · `Newbie.Kit`(첫 접속 킷)을 대신한다.

- 저장: DB `ess_kits(id, data)`(킷 정의 — YAML 글, 공용 DB 면 서버끼리 같은 킷 · 켤 때 전부 메모리로) · `ess_kit_usage(uuid, kit, last_used, uses)`(사용 기록).
  사용 기록은 **접속한 사람의 것만** 메모리에 둔다 — 들어오기 직전(비동기 접속 사건)에 그 사람 것을 DB 에서 읽고, 나가면 내린다(받는 중이면 끝난 뒤).
  서버가 오래 돌아도 메모리가 늘지 않는다. 읽기가 실패하면 접속 뒤 다시 읽고, 그때까지는 그 사람에게 킷을 주지 않는다('주는 중').
- 설정: `settings/kits.yml`, 메시지: `messages/kits.yml`.

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/킷` (kit, kits) | 목록 화면(설정 `gui: false` 면 채팅 목록). 클릭 = 받기 · 우클릭 = 미리보기 | `inmcessentials.kits.use` (모두) |
| `/킷 <이름\|그룹>` | 받기. 그룹 이름이면 아래 '그룹과 무게' 대로 하나 | `.use` + 킷 권한 |
| `/킷 <이름> 미리보기` (preview) | 미리보기 화면(CMI kitpreview) | 아래 |
| `/킷 <이름> <대상\|all> [-skipusage]` | 남에게 — 검사·비용 없이(CMI kitopen). `-skipusage` 면 사용 기록도 남기지 않음 | `.others` (OP) |
| `/킷편집` (kiteditor) | 킷 만들기·고치기 화면 | `.edit` (OP) |
| `/킷초기화 <킷\|all> <플레이어\|all> [쿨타임\|횟수]` (kitreset) | 쿨타임·사용 횟수 되돌리기(CMI kitcdreset · kitusagereset — 셋째를 비우면 둘 다). 접속하지 않은 사람 것도(DB) — 바뀐 줄 수는 DB 에서 센다 | `.reset` (OP) |

킷 권한: 킷 설정 '권한 필요' 가 켜져 있으면 `inmcessentials.kits.kit.<id>`(또는 모든 킷 `inmcessentials.kits.kit`, OP 기본). 꺼져 있으면 `.use` 만.
미리보기: 킷의 '미리보기 허용' 이 켜져 있고 받을 권한이 있거나 `inmcessentials.kits.kit.<id>.preview` / `inmcessentials.kits.preview`(OP).
우회(모두 OP): `.bypass.cooldown` · `.bypass.uses`(1회용 포함) · `.bypass.money` · `.bypass.exp` · `.bypass.conditions`.
킷마다의 노드(`…kit.<id>` · `…kit.<id>.preview`)는 글자로 짓는 노드라 선언돼 있지 않다 — op 는 저절로 가지고, 다른 사람은 LuckPerms 로 준다(CMI `cmi.kit.<이름>` 과 같음).

## 킷 하나

`/킷편집` → 킷을 누르면: 보이는 이름(MiniMessage) · 아이콘(손에 든 것) · 설명 · 권한 필요 · 조건 권한(CMI Conditions — 모두 있어야 받음) · 그룹·무게(우클릭 = 밀려도 보이기) ·
목록에서 숨김 · 쿨타임(`1d 12h` 꼴) · 받을 수 있는 횟수(0 = 무제한, 1 = 한 번) · 돈 비용(우클릭 = 화폐 고르기) · 경험치 비용(**점수**, 레벨 아님 — CMI ExpCost) ·
미리보기 허용 · 첫 접속 킷 · 목록 칸(고정 칸) · **id 바꾸기**(CMI 편집기의 '설정 이름 바꾸기' — 사용 기록(쿨타임·횟수·1회용)을 새 id 로 옮기고, 새 id 에 남아 있던
옛 기록(지운 킷의 것)은 지운다. 권한 노드가 `…kit.<새 id>` 로 바뀌므로 LuckPerms 의 옛 노드를 고칠 것).

**묶음**(보상): 1번 묶음은 '전부(각자 확률)', 더한 묶음은 '하나 뽑기(가중치)' 로 시작한다. 묶음을 누르면 core 보상 편집기(urb 보상 화면의 쓰는 법 — 끌어 넣고
✔ 확인 · 제거 모드 · 보상을 누르면 확률/가중치·수량·참조/스냅샷·참조 플러그인·돈과 화폐·명령어·공지·아이템 지급 여부, 지급 방식은 좌/우클릭, N개 뽑기는 Shift).
끌어 넣은 아이템은 **사본**이 들어가고 원본은 돌려준다. Shift+우클릭 = 묶음 지우기. '내 가방을 1번 묶음에 담기' = CMI 편집기의 인벤토리 복제. '시험 받기' = 검사·
비용·기록 없이 나에게.

변수(아이템 이름·설명·명령): `{USERNAME}`·`{player}` 받는 사람 · `{DISPLAYNAME}`(타이틀포지 닉네임) · `{WORLDNAME}`·`{월드}` · `{KITNAME}`·`{킷}` · `{RANDOMPLAYER}`.
명령은 콘솔이 실행한다(core 보상 — `%…%` PAPI 도 풀린다).

## 그룹과 무게(설정 `group-mode`)

CMI 의 킷 편집기 글(Locale_EN `kiteditor` — "Higher value have bigger priority / Kits should be in same group", "Show despite weight: Player is not
getting access to kit, but can preview it")대로 **무게는 우선순위**다. 카탈로그에는 '무게로 뽑기' 라고 적혀 있어 둘 다 둔다 — 기본은 CMI 와 같은 `priority`.

- `priority`(기본): 같은 그룹에서 받을 수 있는(권한) 킷 중 **무게가 가장 높은 것만** 받는다. 낮은 것은 '같은 그룹의 더 높은 킷을 받을 수 있습니다' 로 막히고
  목록·미리보기에서도 빠진다 — 킷의 '밀려도 보이기'(CMI ShowDespiteWeight)를 켜면 잠긴 채 보이고 미리볼 수 있다. `/킷 <그룹>` 은 그 하나를 준다(쿨타임이면
  그 까닭을 알리고 낮은 무게로 넘어가지 않는다). 예: daily_member(무게 1) · daily_vip(무게 2) — VIP 는 vip 만, 일반은 member 만.
- `random`: `/킷 <그룹>` 이 지금 받을 수 있는 것 중 무게로 하나를 뽑는다. 그룹 킷 하나를 받으면(그룹으로든 이름으로든) **그룹 전체에** 같은 사용 기록이 남아
  각 킷의 쿨타임·횟수가 같이 걸린다 — `/킷 <그룹>` 을 되풀이해 나머지까지 다 받지 못한다.

## 주는 순서(크래시 안전)

검사(권한 · 조건 · 횟수 · 쿨타임 · 돈 · 경험치) → 비용 받기 → **사용 기록을 DB 에 쓰고**(쓰기 스레드) → 쓴 뒤 메인에서 묶음마다 굴려 준다. 기록을 못 쓰면 비용을
돌려주고 주지 않는다 — 크래시 사이에 기록 없이 받아 1회용을 다시 받는 일을 막는다. 그새 나갔으면 돈은 오프라인 계좌로 돌려주고, 경험치는 돌려줄 길이 없어
서버 로그에 남긴다. 같은 킷을 그새 또 누르면 '주는 중'. 가방이 차면 **우편**(core 보상 → mail 기능). 받는 사람이 그새 나갔으면 우편으로.
`{RANDOMPLAYER}` 는 투명이 아닌 접속자 중에서 고른다(방송 명령에 투명인 관리자 이름이 새지 않게).

## 첫 접속 킷

'첫 접속 킷' 을 켠 킷 전부를 처음 들어온 사람에게 한 번(검사·비용 없이). 표 `*first-join` 을 사용 기록에 **먼저** 쓰고 준다 — 사람마다 한 번뿐(킷 기록을 초기화해도
첫 접속 표는 남는다). spawn 기능이 첫 스폰 뒤에 `ess.kits.giveFirstJoin` 을 부르고, spawn 이 꺼져 있으면 접속 1초 뒤에 이 기능이 직접 준다 — 그때는
`ess_users` 의 첫 접속 **과** 바닐라 판정(플레이어 파일이 없던 사람 — spawn 기능과 같은 판정)이 둘 다 '처음' 일 때만. 이 플러그인을 이미 돌던 서버에 처음
넣은 날 기존 플레이어가 모두 '처음' 으로 잡혀 첫 접속 킷을 받지 않게. 그 사람의 킷 기록을 아직 못 읽었으면 주지 않고 로그를 남긴다(두 번 주기보다 낫다).
기록 쓰기가 실패하면 첫 접속 표와 킷마다의 기록을 모두 이전으로 되돌린다(주지 않은 1회용 킷이 '이미 받음' 으로 남지 않게).

## 화면

목록: 받을 수 있음 · ⏳ 남은 쿨타임 · 횟수 다 씀 · 🔒 권한 없음/조건 · 돈·경험치 모자람, 비용·쿨타임·받은 횟수·마지막 받은 때(`date-format`). `show-locked` 면 권한
없는 킷도 잠긴 모습으로 보인다. 숨긴 킷은 안 보인다(명령으로는 받는다). 미리보기: 묶음마다 보상(개수·확률 — 뽑기 묶음은 실제 뽑힐 확률로 바꿔 보여 줌) · 아래 줄 쿨타임 ·
받은 횟수 · 돈 · 경험치 · 설명 · ▶ 받기 · 뒤로(CMI Kits.Buttons).

## CMI 가져오기

- 정의(`Kits.yml` + `Kits` 폴더의 다른 `.yml` — CMI 편집기의 FileName): `Kits.yml` 이 CMI 가 처음 깔아 주는 예제(Newbie·Advanced·Ad1~3)와 **내용까지**
  같으면(킷 이름과 키 목록 · Delay · MoneyCost · ExpCost · Group · CommandName · Weight · Description · Commands · Conditions · 아이템의 종류·개수·이름·설명) 가져오지
  않는다(보고서에 건너뜀). 이름은 그대로 두고 내용을 고쳐 쓰던 서버는 가져온다. 아이템 이름·설명은 색 코드를 뗀 평문으로, 인챈트는 종류(옛 Bukkit 이름
  `ARROW_DAMAGE` 도 새 열쇠 `power` 로 고쳐서)·레벨로 견준다 — 색만 바꾼 것은 예제로 본다. 아니면 킷마다 id(이름을 소문자·숫자·_- 로)로: Delay → 쿨타임 · MaxUsages → 횟수 · DisplayName → 이름(& 색 → MiniMessage) ·
  Icon → 아이콘(아이템 또는 재질 글) · Slot/Slots·Page → 목록 칸(1쪽 0~44 만) · Enabled: false → 목록에서 숨김(보고서) · ShowDespiteWeight · MoneyCost ·
  ExpCost(점수) · Conditions · Description · Items(Bukkit 직렬화 아이템 → 보상, 개수 그대로 — Helmet 등 장비 칸은 가방으로, 보고서) · Commands(`asConsole!` 떼고
  `[playerName]`·`{USERNAME}` → `{player}` — CMI 전용 꼴 `msg!` 등이 남으면 보고서에) · **Group**(+ Weight — CMI 의 '받을 수 있는 최고 무게만' 이 우리 그룹의 뜻)
  · Group 이 없으면 CommandName(이름과 다를 때 — 같은 명령 이름 묶음)을 그룹으로. 둘 다 있고 다르면 Group 을 쓰고 CommandName 은 보고서에(`/킷 <CommandName>` 대신
  `/킷 <Group>` 또는 킷 id).
  IconOff · DropOnGround 는 보고서에 적고 옮기지 않는다. 그 밖의 모르는 키도 보고서에. 같은 id 의 킷이 있으면 건너뛴다.
- `config.yml` 의 `Newbie.Kit` → 그 킷(이번에 가져온 것)을 '첫 접속 킷' 으로. 가져오지 않은 킷이면 보고서에 적는다.
- 보고서에 **권한 바뀜**(`cmi.kit.<이름>` → `inmcessentials.kits.kit.<id>`, 미리보기는 `.preview`)과 조건에 남은 `cmi.` 권한(예: `cmi.kit.get` — CMI 를
  빼면 기본값이 사라져 op 만 가진다)을 적는다.
- 사용 기록(`users.Kits`): 같은 id 의 킷이 있을 때 마지막으로 받은 때를 넣는다 — DB 에서 **더 새것을 남기며 합친다**(마지막 받은 때는 더 늦은 것, 횟수는 더 큰 것;
  접속하지 않은 사람의 기록은 메모리에 없으므로). CMI 는 받은 횟수도 적지만 가져오기 자료에는 시각만 있어 횟수는 최소 1로 둔다(보고서에 적음).

## 검증기

첫 접속 창구 · 킷 정의 YAML 왕복 · 쿨타임/1회용 판단과 이름 변수 채우기(메모리만) · **받기 순서**(사용 기록 쓰기를 갈아 끼워 — 쓰는 순간 아직 안 받았고 쓰기가 끝난
뒤에 받는지, 진짜 가방으로 받고 끝나면 되돌림 · DB 에 쓰지 않음) · 킷 기록이 접속자 것만 메모리에 있는지 · 무게 뽑기 · 그룹(priority: 낮은 무게가 밀리고
`/킷 <그룹>` 을 두 번 쳐도 같은 킷이라 쿨타임 / random: 하나를 받으면 그룹 전체가 기록돼 두 번째 뽑기가 없다 — 메모리만) · **CMI 예제 Kits.yml 알아보기**
둘(jar 예제에 `==: ItemMeta` 를 끼워 서버에서 ItemMeta 로 풀린 것이 섹션 기본값과 같은 지문인지 · `plugins/CMI/Kits/Kits.yml` 이 있고 글이 예제 그대로면
`isCmiDefault` 가 true 인지 — 읽기만).

## CMI 와 다른 점

- 킷 아이템의 칸 배치(갑옷·보조손·핫바 자리 지정)는 하지 않는다 — 빈 칸에 넣는다(연구 give-kits 결정, 2단계 후보).
- `kitcdreset`·`kitusagereset` 은 `/킷초기화` 하나로. 인자 없이 치면 클릭 목록 대신 사용법.
- CMI 의 '닫기·정보 단추 명령'(Kits.Complex)은 없다.
- CMI 편집기의 '명령 이름(CommandName)' 은 따로 두지 않고 그룹이 그 역할을 한다(Group 이 있으면 Group). '파일 이름(FileName)' 은 없다(정의는 DB 한 곳).

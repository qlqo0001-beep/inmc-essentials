# 관리 정보(admininfo) — 운영 문서

투명 · 플레이 시간(+보상) · 투표 · 정보·마지막 접속·최근 접속·실명·목록·OP 목록·권한 확인·통계.
CMI 항목: cmd-vanish · cmd-votes · playtime-tracking · playtime-top · sys-playtimerewards · playtime-edit · cmd-seen · cmd-realname ·
cmd-list · cmd-oplist · cmd-haspermission · cmd-stats · cmd-info · cmd-lastonline.

설정 `settings/admininfo.yml`(`/에센셜 리로드`), 메시지 `messages/admininfo.yml`, 기능 끄기 `config.yml modules.admininfo: false`(재시작).

## 명령

| 명령 (별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/투명 [대상] [켜기\|끄기] [-s]` (vanish, v) | 투명 켜고 끄기. 대상 없으면 자기. `-s` = 가짜 입퇴장·스태프 알림 없이 | `inmcessentials.admininfo.vanish` (op) · 남: `.vanish.others` (op) |
| `/투명 목록` | 투명인 사람(접속 중 + 다음 접속 때 투명) | `.vanish.list` (op) |
| `/투명설정 [대상] [옵션] [켜기\|끄기]` (vanishedit) | 투명 옵션 화면 / 명령으로 옵션 하나 바꾸기(값 없으면 뒤집기). 접속 안 한 사람도 됨 | `.vanishedit` (op) · 남: `.vanishedit.others` (op) |
| `/플레이시간 [플레이어]` (playtime) | 전체 플레이 시간 · 첫 접속 · 순위 | `.playtime` (모두) · 남: `.playtime.others` (모두) |
| `/플레이시간순위 [쪽]` (playtimetop) | 순위(자기 줄 강조, 쪽 밖이면 맨 아래에 자기 줄) | `.playtime.top` (모두) |
| `/상세플레이시간 [플레이어]` (cplaytime) | 오늘·어제·이번 주·최근 7일·이번 달·최근 30일·올해·최근 365일·기록 전체 | `.playtime.detail` (모두) · 남: `.playtime.detail.others` (op) |
| `/플레이시간편집 <플레이어> <더하기\|빼기\|설정> <시간> [-s]` (editplaytime) | 접속 중이면 바로, 아니면 다음 접속 때 적용. 시간: `1h 30m`·`2일`·`45s`, **그냥 숫자는 분** | `.playtime.edit` (op) |
| `/플레이보상` (prewards) | 플레이 시간 보상 화면 — 누르면 받기(`playtime-rewards.enabled: false` 면 "꺼져 있음") | `.prewards` (모두) |
| `/플레이보상 <플레이어>` | 남의 보상 화면(보기만) | `.prewards.others` (op) |
| `/플레이보상 편집` | 보상 만들기·고치기(core 보상 편집기) | `.prewards.edit` (op) |
| `/투표 [플레이어]` (votes) | 투표 수 · 마지막 투표 · 24시간 안 투표 수 · 다음 투표까지 | `.votes` (모두) · 남: `.votes.others` (모두) |
| `/투표순위 [쪽]` (votetop) | 투표 순위 | `.votes.top` (모두) |
| `/투표편집 <플레이어> [더하기\|빼기\|설정\|초기화] [수] [-s]` (voteedit) | 투표 수 고치기(값 없이 치면 지금 수) | `.votes.edit` (op) |
| `/투표편집 보상` | 투표마다 보상 · 누적 단계 보상 편집 | `.votes.edit` (op) |
| `/정보 [플레이어]` (info, whois) | 종합 정보(아래) | `.info` (op) · IP: `.info.ip` (op) · 비행·무적 전환 단추: `.info.toggle` (op) |
| `/마지막접속 <플레이어\|uuid>` (seen) | 접속 중 몇 분째 / 나간 지 얼마 | `.seen` (모두) |
| `/최근접속 [쪽]` (lastonline) | 최근에 나간 순서 + "1시간·24시간·7일·30일 안 몇 명" | `.lastonline` (op) |
| `/실명 <닉네임>` (realname) | 닉네임 → 실제 이름(타이틀포지). 실제 이름을 치면 그 사람의 닉네임 | `.realname` (모두) |
| `/목록` (list — **바닐라 /list 를 덮는다**) | 접속자 수·목록. LuckPerms 주 그룹으로 묶음, `[잠수]`, 볼 수 있는 사람에게만 `[투명]` | `.list` (모두) |
| `/오피목록` (oplist) | OP 목록(번호 · 접속 표시) | `.oplist` (op) |
| `/권한확인 <플레이어> <권한>` (haspermission) | 직접 정해짐(어느 플러그인)/OP/선언된 기본값/없음, 숫자 권한 최소·최대. 접속 안 한 사람은 LuckPerms 로 | `.haspermission` (op) |
| `/통계 [플레이어]` (stats) | 바닐라 통계 요약. 접속 안 한 사람은 통계 파일을 비동기로 읽어 보기만 | `.stats` (모두) · 남: `.stats.others` (op) |
| `/통계편집 <플레이어> <더하기\|빼기\|설정> <통계> [블록·아이템·몹] <값> [-s]` (statsedit) | 바닐라 통계 고치기(접속자만) | `.stats.edit` (op) |

권한 앞부분은 전부 `inmcessentials.admininfo.`. 그 밖의 권한:
- `inmcessentials.admininfo.prewards.notify` (모두) — 안 받은 보상 알림(액션바).
- `inmcessentials.admininfo.prewards.reward.<보상 id>` — 보상마다 "권한 필요" 를 켰거나 `require-permission: true` 일 때. **선언하지 않는 노드라 기본은 op 만 가진다** — 일반 플레이어에게 주려면 LuckPerms 로.
- 투명인 사람을 보는 권한은 core 의 `inmc.vanish.see` (op).

## 투명

- 상태 표식은 core `Vanish`(메타데이터 `vanished`) — 타이틀포지(접속 메시지·이름표·`%tf_online%`)·디스코드(알림·목록)·이 플러그인의 목록·제안·방송이 이것을 보고 숨긴다.
  안 보이게 하기는 이 기능이 `hidePlayer` 로(볼 권한이 없는 사람에게만). 볼 권한이 바뀌면 `vanish.resync-seconds` 안에 맞춘다.
- 투명으로 들어오는 사람(저장된 투명 · "늘 투명으로 접속")은 접속 사건 **LOWEST** 에서 표식을 건다 → 디스코드 MONITOR 가 알리지 않고, 타이틀포지 HIGH 가
  접속 메시지를 뺀다. 나갈 때 표식은 **한 틱 뒤에** 지운다(퇴장 MONITOR 까지 다른 플러그인이 읽는다).
- 투명은 저장된다 — 나갔다 들어와도 투명("다시 접속하면 풀기" 를 켜면 풀린다). 투명 권한이 없어진 사람은 투명으로 들어오지 않는다.
- **못 보는 사람에게는 투명을 켠 때 나간 것처럼** 보인다(`/마지막접속` · `/최근접속` · `/정보` 의 마지막 접속·나간 지 얼마). 투명으로 들어오면
  **지난 접속**의 값을 쓴다 — 이번 접속을 드러내지 않게. 접속 중에 켜면 그 순간. 이 값은 DB(`ess_admininfo_vanish.seen`·`seen_join`)에 남아
  투명인 채 나가도·재시작해도 그대로이고, 보이게 되면(투명을 끄거나 보이는 채로 접속) 지운다.
- 서버를 끌 때(`/stop`)는 투명인 사람을 보이게 하지 않는다(효과만 뗀다) — Paper 가 플러그인을 먼저 끄고 접속자를 나중에 내보내기 때문.
  내보낼 때 바닐라 퇴장 줄은 남은 사람에게 갈 수 있다(그때는 모든 플러그인이 꺼져 있어 막을 수 없다). 기능만 끄면(리로드) 다 보이게 한다.
- **진짜 접속·퇴장 메시지의 모양은 타이틀포지가 정한다.** 이 기능은 투명인 사람의 메시지를 뺄지만 정하고, "접속/퇴장 메시지 보이기" 를 켠 사람·"가짜 입퇴장" 은
  가짜 입퇴장(옵션)은 core `FakeConnectionEvent` 로 — 타이틀포지가 있으면 진짜와 같은 모양, 없으면 이 기능의 메시지(`admininfo-vanish-fake-join/quit` — 접두 없음).
  디스코드도 진짜처럼 채널에 올린다. 받는 사람은 못 보던 사람과 본인(2026-10-15).
- 투명인 동안 늘: 발걸음·스컬크 진동(게임 사건) 막기, 발전과제 알림은 볼 수 있는 사람에게만.
- 죽음 메시지는 **MONITOR** 에서 가른다 — 다른 플러그인(던전 등)이 지웠거나 사건을 취소했으면 다시 보내지 않는다.
- 옵션(기본값은 `vanish.defaults`, 사람마다 `/투명설정`): 남에게 피해 주기 · 피해 받기(허공·/kill 은 그대로) · 아이템·화살·경험치 줍기 · 몹이 노림 · 밟기 상호작용 ·
  퇴장/접속 메시지 보이기 · 야간 투시 · 보스바 · 다시 접속하면 풀기 · 공개 채팅 막기 · 가짜 입퇴장 · 주변 몹 생성 · 플레이 시간 멈춤 · 잠자기 인원에서 빼기 ·
  늘 투명으로 접속 · 죽음 메시지(꺼지면 볼 수 있는 사람에게만) · 낚싯대로 끌기.

## 플레이 시간

- **원본은 바닐라 통계 `PLAY_ONE_MINUTE`**(결정 2026-10-10 — CMI 값은 가져오지 않는다). 접속자는 늘 통계에서 바로 읽는다.
- 접속 안 한 사람의 조회·순위는 사본(`ess_admininfo_playtime`, `playtime.flush-seconds` 마다·나갈 때 갱신). 켤 때(와 CMI 가져오기 뒤) 사본이 없는
  사람은 월드 통계 파일(`world/players/stats/<uuid>.json`, 옛 `world/stats`)을 비동기로 읽어 채운다(`scan-stats-on-start`).
- 바닐라 통계는 서버마다 따로라 **사본은 공용 DB 를 써도 로컬 DB 에** 둔다(서버마다 자기 값). 하루별 기록은 공용 DB 에 더한다(서버들의 합).
- 하루별 기록(`ess_admininfo_playtime_day`)은 이 플러그인을 넣은 뒤부터 쌓인다 — `/상세플레이시간` 의 "기록 전체" 는 그 합, 아래 줄의 "전체" 는 바닐라 통계.
- 투명 + "플레이 시간 멈춤" 이면 통계를 되돌린다(업적 등 통계를 읽는 다른 곳도 같이 멈춘다).
- 접속 안 한 사람의 편집은 걸어 두었다가 다음 접속 때 통계에 적용한다(여러 번 걸면 합쳐짐: 설정 뒤 더하기 = 설정(합), 더하기끼리 = 합).
  적용한 줄은 **나갈 때** 지운다(바닐라 통계 파일은 자동 저장·퇴장 때만 쓰인다). 그 전에 서버가 죽으면 다음 접속 때 통계가 적용 전 그대로인지 보고
  그렇다면 다시 적용한다.

## 플레이 시간 보상 (`/플레이보상 편집`)

- 반복(CMI PayEvery): 정한 시간마다. **보상을 만든 뒤부터** 센다(옛 플레이 시간으로 몰아 주지 않는다). `exclude-afk` 면 잠수 시간은 세지 않는다.
  "쌓기" 를 끄면 안 받고 둔 보상은 하나까지만.
- 한 번(CMI PayFor): 전체 플레이 시간(바닐라 통계)이 정한 시간에 닿으면 한 번.
- 저절로 받기 / 화면에서 누르기. 손으로 받는 것이 남으면 `inform-minutes` 마다 액션바 알림. 화면에는 아직 안 닿은 한 번 보상을 `one-time-shown` 개만 미리 보인다.
- 보상 내용은 core 보상 편집기(아이템 끌어 넣기 · 돈 · 명령 `{player}` · 확률·추첨). **받기 기록을 먼저 쓰고 준다** — 가방이 차면 우편, 그 사이 나갔으면 우편.
- 공용 DB: 진행·쌓인 수는 더하기로, 받기는 조건부(반복 = DB 의 쌓인 수가 그만큼 있을 때만 빼기 · 한 번 = 아직 안 받은 줄만 '받음')로 쓴다 —
  두 서버가 같은 보상을 두 번 주지 않는다(늦게 누른 쪽은 "이미 받음" 으로 맞추고 주지 않는다).
- `playtime-rewards.enabled: false` 면 전부 멈춘다(진행도 안 늘고 받을 수도 없다 — 편집은 된다). 보상마다 끄려면 편집 화면의 켜고 끄기.

## 투표

- VotifierPlus · NuVotifier(Votifier) 의 `VotifierEvent` 를 리플렉션으로 받는다(없으면 켤 때 로그 한 줄, `/투표` 는 저장된 수만).
- 이 서버에 온 적 없는 이름의 투표는 세지 않는다(로그). 접속 안 한 사람의 보상은 우편.
- 투표마다 보상 + 누적 단계 보상(단계마다 한 번 — 수를 내렸다 올려도 다시 안 받는다. `/투표편집 … 초기화` 로 0 이 되면 단계 기록도 지운다).
  단계는 **그 수를 새로 지나는 투표에만** 준다 — 이미 그 수를 넘은 사람, `/투표편집 … 설정` 으로 올린 수, CMI 에서 가져온 수 아래의 단계는 몰아 주지 않는다.
  보상이 빈 단계(편집기에서 막 만든 것)는 '받음' 으로 적지 않는다 — 채운 뒤 그 수를 지나는 사람이 받는다. 보상 명령의 `{service}` = 투표 사이트 이름.
- `votes.count: false` 면 수와 누적 단계만 건너뛴다 — 알림·투표마다 보상은 그대로(CMI CountVotes·PerformCommands 처럼 따로).
- 서버가 꺼지는 중에 들어온 투표는 수는 남고 보상을 못 줄 수 있다 — 그때는 이름·사이트·누적·단계를 SEVERE 로그로 남긴다(직접 보충).
- 아이피 하루 한도 · 같은 사이트 대기 · 순위 제외는 설정.

## 정보 화면(`/정보`)

이름·닉네임·주 그룹·접두(LuckPerms — 접속 안 한 사람도 LuckPerms 가 비동기로 읽는다) · UUID(클릭 복사) · IP(클릭 복사)·핑 · 첫/마지막 접속 ·
접속 중 몇 분째/나간 지 얼마 · 플레이 시간·투표 · 잔액 · OP/잠수/투명/뮤트/감옥 · (접속 중) 게임 모드·비행(+시간제 비행 남은 시간)·속도·무적(+시간제 무적
남은 시간)·체력·공기·배고픔·포만·경험치·위치(클릭하면 `/이동` 명령)·침대·마지막 죽음 · `[비행 전환]`·`[무적 전환]` 단추.
- 무적·시간제 비행/무적은 player 기능의 것이라 접점 `ess.abilities` 로 읽는다(2026-10-14 — 전에는 `%inmcplayer_…%` 플레이스홀더). player 기능이 꺼져 있으면
  무적은 바닐라 `isInvulnerable` 로, 남은 시간은 안 보인다.
- 단추는 player 기능의 `/비행 <이름>` · `/무적 <이름>` 을 그대로 친다 — 그 명령의 권한(`inmcessentials.player.fly.others` 등)·저장·알림을 거친다. 그 명령이 없으면 단추도 없다.
- 접속 안 했으면 나간 자리. 투명인 사람은 볼 권한이 없는 사람에게 "오프라인"(투명을 켠 때 나간 것처럼 — 위 '투명').

## 표 · 열쇠

DB 표(전부 `ess_admininfo_`): `vanish`(+`seen`·`seen_join`) · `playtime`(**로컬 DB**) · `playtime_day` · `playtime_edit`(+`base`) ·
`rewards`(정의 YAML — kind `playtime`/`vote`) · `preward` · `votes` · `vote_log` · `vote_milestone`. 나중에 더한 열은 켤 때 없으면 `ALTER TABLE` 로 붙인다.
PDC 열쇠는 쓰지 않는다.

## CMI 에서 가져오기 (`/에센셜 가져오기 cmi`)

- 투표 수(이미 수가 있는 사람은 건너뜀). 가져온 수 아래의 누적 단계는 '받음' 으로 적는다(CMI 가 이미 줬다).
- 사람마다 투명 상태·옵션(CMI users 표의 `Vanish` 열 — `VanishAction` 순서의 `1`/`0` 자리값). 이 플러그인에 이미 줄이 있는 사람은 건너뛴다.
  지금 기본값과 같은 옵션은 저장하지 않는다. 투명 레벨과 다른 기능 몫의 옵션(noisyChest·afkcommands·PrivateMessages)은 보고서에.
- `Settings/PlayTimeRewards.yml` 의 보상 정의(명령만 있는 보상으로 — `[playerName]`→`{player}`, `asConsole!` 뗌, 다른 `xxx!` 특수 명령은 "못 읽음").
  이름·설명의 `&` 색 부호는 MiniMessage 로. 같은 id 가 있으면 건너뜀. CMI `PlaytimeRewards.Enabled` 가 꺼져 있으면(또는 config.yml 을 못 읽으면) **꺼진 채로** 넣는다.
  `Permission: true` 인 보상은 보고서에 새 권한 노드(`inmcessentials.admininfo.prewards.reward.<id>` — op 기본)를 적는다.
- 한 번(PayFor) 보상의 **받은 기록**: CMI `playtimereward.onetime` 열을 server 기능의 읽기가 넘겨 주면 그대로 옮긴다. 아직 넘기지 않으므로 지금은
  **이미 그 시간을 넘은 사람(바닐라 통계 사본과 CMI TotalPlayTime 중 큰 값)을 '받음' 으로** 적는다 — 두 번 주지 않게. 보고서에 적는다.
- `config.yml` 의 `Votifier.CommandsOnVote`(`PerformCommands: true` 이고 투표 보상이 비어 있을 때만)·`ExtraRewards`(`ExtraRewardsEnabled: true` 일 때만 —
  jar 예시가 켜진 채 들어오지 않게). `cmi …` 명령은 CMI 를 빼면 안 도니 보고서에 따로 적는다 — 바꿀 것.
- 순위 제외(`PlaytimeTopExclude`·`PlaytimeTopOffline`·`Votifier.ExcludeList`), `PlaytimeRewards`·`Votifier` 값, `Vanish.Defaults`, `lastonline.Timers`,
  `List.ASCOrder` 는 이 플러그인 설정과 **다를 때 보고서에만** 적는다(설정 파일을 고치지 않는다).
- **플레이 시간은 가져오지 않는다**(결정) — 보고서에 CMI 값이 있던 사람 수만 남긴다. 가져오기 뒤 새 사용자의 사본은 통계 파일에서 채운다.

## CMI 와 다른 점

- 투명 레벨(1~10, `cmi.vanishlevel`)이 없다 — 볼 권한 하나(`inmc.vanish.see`). 투명 파티클·진입/해제 API 이벤트도 없다(core `Vanish.listen` 이 그 자리).
- 투명 옵션 중 셋은 다른 기능의 몫이라 여기 없다: 조용한 상자(noisyChest → inventory 설정 `silent-chest.auto-when-vanished`·`/몰래열기`), 잠수 명령(afkcommands →
  afk 의 `commands.when-vanished`), 귓속말 받기(PrivateMessages — chat 의 귓속말은 볼 수 없는 투명인 사람을 "없음" 으로 본다 = CMI 의 false).
- `/플레이시간편집` 의 숫자만 친 양은 **분**이다(CMI 와 단위가 다를 수 있음 — `1h`·`30m` 처럼 단위를 붙이는 것을 권한다).
- `/상세플레이시간` 은 하루 단위(CMI 의 시간대별 줄 — "이번 시간·지난 시간" 은 없다).
- `/목록` 의 관리자·스태프 줄(`cmi.command.list.admin/staff/group.N`)은 LuckPerms 주 그룹으로 대신한다(그룹 보이는 이름·무게).
- `/통계` 는 접속 안 한 사람도 보인다(통계 파일 — 마지막 저장 기준). `/통계편집` 은 접속자만.
- 편집 명령(`/투표편집`·`/플레이시간편집`·`/투명설정`·`/정보`·`/마지막접속` …)의 대상은 **정확한 이름이 먼저**(보이는 접속자 → 기록 → uuid), 그다음에야
  부분 이름·닉네임 — 접속 안 한 `Bob` 을 친 것이 접속 중인 `Bobby` 로 가지 않는다.
- 투표 플레이스홀더·랭크업 조건은 없다.

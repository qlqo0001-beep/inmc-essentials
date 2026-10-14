# 제재(moderation) — 운영 안내

CMI 의 밴(ban·tempban·ipban·tempipban·unban·banlist·ipbanlist·checkban) · kick · mute·shadowmute·unmute · mutechat·unmutechat · 감옥(jail·jailedit·jaillist·unjail)
· warn·warnings·editwarnings · cuff · alert · note · checkaccount·sameip · lockip · patrol · sudo · usermeta · switchplayerdata · removeuser · resetdbfields · purge ·
importoldusers 를 대신한다. 설정 `plugins/inmc-essentials/settings/moderation.yml`, 메시지 `messages/moderation.yml`, 감옥 정의 `data/jails.yml`.

- **밴은 Bukkit 의 밴 목록**(`banned-players.json`·`banned-ips.json`) 위에 있다 — 바닐라 `/ban`·`/pardon`·`/banlist` 와 같은 목록이라 어느 쪽으로 넣고 빼도 어긋나지 않는다.
  기간밴은 만료 시각이 있는 항목이다.
- 뮤트·감옥·수갑·경고·메모·경보·유저 메타·IP 기록·IP 잠금은 DB(`ess_moderation_*`) + 메모리. 바뀌는 순간 DB 로 간다(감옥 남은 시간만 30초마다).
- 다른 기능이 보는 접점 `ess.moderation`(뮤트·그림자 뮤트·감옥·수갑) — 귓속말·답장·도움요청·우편이 본다. 공개 채팅·명령·이동의 막기는 이 기능이 직접 한다.
- `-s` = 조용히: 공개 방송 대신 운영진(`inmcessentials.moderation.notify`)·보낸 사람·콘솔에게만 `[조용히]` 를 붙여 알린다.
- 투명인 사람이 대상이면 그 사람을 이름으로 알리는 방송은 그를 볼 수 있는 사람에게만 간다. 이름 제안도 볼 수 있는 사람만.

## 명령

시간은 `30s` · `10m` · `1h` · `1d2h` · `1w` · `1y` · `1일 2시간` · 맨 숫자(초). 사람은 접속자(부분 이름·닉네임) 또는 온 적 있는 사람의 이름·uuid.

| 명령 (영어 별칭) | 하는 일 | 권한 (기본 OP) |
|---|---|---|
| `/밴 [플레이어] (사유) (-s)` | 영구 차단 + 접속 중이면 추방 | `…moderation.ban` |
| `/기간밴 [플레이어] [시간] (사유) (-s)` (tempban) | 기간 차단. 최대 길이는 권한(아래) | `…moderation.tempban` |
| `/아이피밴 [IP/플레이어] (사유) (-s)` | IP 차단. 사람을 치면 접속 주소 → 마지막 IP. 그 IP 로 접속 중인 사람 모두 추방. 그 사람·그 IP 를 쓴 적 있는 사람 중 보호 대상(`ipban.bypass`, 오프라인이면 op)이 있으면 거절 | `…moderation.ipban` |
| `/기간아이피밴 [IP/플레이어] [시간] (사유) (-s)` (tempipban) | 기간 IP 차단 | `…moderation.ipban` |
| `/밴해제 [플레이어/IP] (-s)` (unban) | 차단 풀기 | `…moderation.unban` |
| `/밴목록 (아이피)` | 차단 목록 화면(머리·사유·차단한 사람·때·남은 기간 → 누르면 자세히·해제). `checkaccount.showip` 가 있으면 **연관 계정**(같은 IP 를 쓴 계정)도. 콘솔은 채팅 | `…moderation.banlist` |
| `/밴확인 (플레이어/IP)` (checkban) | 그 사람의 차단 정보(화면). 이름 없이 = 목록(`banlist` 권한이 있을 때만) | `…moderation.checkban` |
| `/추방 [플레이어/all] (사유) (-s)` | 추방. `all` 은 보이는 접속자 전원(우회 권한·자신 빼고) | `…moderation.kick` (+ `kick.all`) |
| `/뮤트 [플레이어] (시간\|+시간\|-시간) (사유) (-s)` (mute) | 채팅 막기. 시간 없으면 영구. `+30s`·`-15m` 은 남은 시간에 더하고 빼기 | `…moderation.mute` |
| `/그림자뮤트 [플레이어] (시간) (사유) (-s)` (shadowmute) | 본인은 모르게 — 본인 화면엔 보낸 것처럼 보이고 남에겐 안 간다 | `…moderation.shadowmute` |
| `/뮤트해제 [플레이어] (-s)` (unmute) | 뮤트 풀기 | `…moderation.unmute` |
| `/채팅잠금 (시간) (사유) (-s)` (mutechat) | 공개 채팅 전체 잠금(시간 없으면 풀 때까지) — 치면 액션바로 남은 시간·사유 | `…moderation.mutechat` |
| `/채팅잠금해제 (-s)` (unmutechat) | 잠금 풀기 | `…moderation.mutechat` |
| `/감옥 [플레이어] (시간) (감옥) (감방) (-s) (r:사유)` (jail) | 가두기. 시간 없으면 `jail.default-time`, 감옥·감방 없으면 사람이 가장 적은 곳. 이미 갇혔으면 시간만 바꾼다(`+`·`-` 도) | `…moderation.jail` |
| `/석방 [플레이어] (-s)` (unjail) | 풀기 — 석방 위치(없으면 가두기 전 자리 → 월드 스폰)로. 나가 있는 사람은 자리를 적어 뒀다가(`ess_moderation_release`) 다음 접속 때 보낸다 | `…moderation.unjail` |
| `/감옥목록 (감옥) (감방)` (jaillist) | 감옥 → 갇힌 사람(남은 시간·사유·가둔 사람, 누르면 석방) 화면. 콘솔은 채팅 | `…moderation.jaillist` |
| `/감옥편집` (jailedit) | 감옥 편집 화면(새 감옥 · 석방 위치 · 영역 · 감방 더하기/지우기 · 영역 보기 · 이동 · 지우기) | `…moderation.jailedit` |
| `/감옥편집 만들기\|삭제\|영역\|석방위치\|감방추가\|감방삭제\|보기\|이동 [이름] (번호)` | 같은 일을 명령으로(영어 create·remove·area·outside·addcell·removecell·show·tp). `영역` 을 다시 정하면 새 영역 밖 감방은 지운다(알림) | `…moderation.jailedit` |
| `/경고 [플레이어] (분류) (사유) (-s)` (warn) | 경고 — 분류의 점수·만료. 점수 합이 `warnings.perform` 의 문턱을 넘으면 그 명령 | `…moderation.warn` |
| `/경고목록 (플레이어)` (warnings) | 경고와 점수 합(때·준 사람·분류·사유, 마우스를 올리면 만료·점수) | 자기 것 모두 · 남의 것 `warnings.others` |
| `/경고편집 [플레이어]` (editwarnings) | 화면(누르면 하나 지우기 · 전부 지우기). `… 비우기` · `… 삭제 [번호]` · `/경고편집 전부지우기 (확인)` | `…moderation.editwarnings` |
| `/수갑 [플레이어] (true/false) (-s)` (cuff) | 이동·블록·상호작용·공격·버리기·탈것 타기·명령 막기(허용 명령 빼고), 설정에 따라 채팅도. 타고 있던 탈것에서는 내린다. 값 없이 = 바꾸기 | `…moderation.cuff` |
| `/경보 [추가\|삭제\|목록\|하루추가] [플레이어] (사유)` (alert) | 그 사람이 `alert.timer-minutes` 안에 들어오면 운영진에게 알림(+ 메모가 있다고) | `…moderation.alert` |
| `/메모 (플레이어) [추가\|삭제\|비우기\|목록] (번호/내용)` (note) | 운영진 메모. 사람 없이 = 자기 | `…moderation.note` |
| `/부계정 (이름/IP)` (checkaccount) | 같은 IP 를 쓴 계정(이름(횟수), 누르면 그 계정 조회). IP 를 보고 IP 로 찾기는 `checkaccount.showip` | `…moderation.checkaccount` |
| `/같은아이피` (sameip) | 지금 같은 IP 로 접속 중인 사람 묶음 | `…moderation.sameip` |
| `/아이피잠금 (플레이어) [추가\|삭제\|목록\|비우기] (IP)` (lockip) | 그 계정은 목록의 IP 로만 접속(추가에 IP 없으면 지금·마지막 IP). 남의 계정은 보호 대상(`lockip.bypass`, 오프라인이면 op)이면 못 고친다 — 자기 계정은 된다 | `…moderation.lockip` |
| `/순찰` (patrol) | 보이는 접속자 중 가장 오래 안 찾아간 사람에게 이동(+ 마지막 순찰·메모 수) | `…moderation.patrol` |
| `/대신실행 [플레이어] [명령 \| c:채팅]` (sudo) | 그 사람이 명령을 치거나(`/` 없이) `c:` 뒤 글을 채팅 | `…moderation.sudo` |
| `/유저메타 [플레이어] [추가\|삭제\|비우기\|목록\|증가] (키) (값\|수) (-s)` (usermeta) | 사람마다 키·값. 키는 한글·영문·숫자·_- 32자, **소문자로 저장**(`%inmcmod_meta_<키>%` 도 소문자로 찾는다). 증가는 숫자에 더하기(없으면 0 에서, 기본 1) | `…moderation.usermeta` |
| `/데이터교환 [원본] [대상] (확인)` (switchplayerdata) | 두 사람의 데이터를 서로 바꾸기 — 아래 "데이터 도구" | `…moderation.switchplayerdata` |
| `/유저삭제 [이름/uuid \| 중복] (확인)` (removeuser) | 유저 기록과 그 데이터 지우기 · 중복 계정 정리(정식 uuid 는 `online-mode` 또는 프록시 온라인 모드로 판단 — 미리 보기에 어느 쪽인지 나온다) | `…moderation.removeuser` |
| `/열초기화 [표.열] (-p:플레이어) (-w:월드) (확인)` (resetdbfields) | DB 열을 기본값으로 | `…moderation.resetdbfields` |
| `/미접속정리 (일수) (-파일) (확인)` (purge) | 오래 안 온 사람의 데이터 정리 | `…moderation.purge` |
| `/유저가져오기` (importoldusers) | 메인 월드 `playerdata` 의 uuid + `usercache.json` 의 이름을 유저 기록으로(없는 사람만) | `…moderation.importoldusers` |

`ban`·`ban-ip`·`banlist`·`kick`·`pardon` 은 바닐라 이름이라 별칭이 없다 — 바닐라 명령도 같은 목록을 쓰므로 그대로 써도 된다.

### 그 밖의 권한

| 권한 | 기본 | 뜻 |
|---|---|---|
| `inmcessentials.moderation.notify` | OP | 조용히 한 제재 · 그림자 뮤트 · 뮤트 · 경고 문턱 알림을 받는다 |
| `….ban.bypass` · `….ipban.bypass` · `….kick.bypass` · `….mute.bypass` · `….jail.bypass` · `….warn.bypass` · `….cuff.bypass` · `….sudo.bypass` · `….lockip.bypass` | OP | 그 제재를 받지 않는다(오프라인이면 op 인지로만 본다). `ipban.bypass` 는 그 IP 를 쓴 적 있는 사람까지 본다 |
| `….tempban.max.unlimited` · `….tempban.max.<초>` | OP / 직접 | 기간밴 최대 길이(가장 큰 것). 없으면 `ban.tempban-max-without-permission`(60초) |
| `….mute.max.unlimited` · `….mute.max.<초>` | OP / 직접 | 뮤트 최대 길이(영구 뮤트는 unlimited 또는 설정 -1). 없으면 `mute.max-without-permission`(-1 = 제한 없음) |
| `….jail.max.unlimited` · `….jail.max.<초>` | OP / 직접 | 감옥 최대 시간. 없으면 `jail.max-without-permission` |
| `….mutechat.bypass` | OP | 채팅이 잠겨도 말한다 |
| `….alert.inform` | OP | 경보가 걸린 사람이 들어오면 알림 |
| `….checkaccount.showip` | OP | `/부계정` 에서 IP 보기·IP 로 찾기 |
| `….patrol.exempt` | 없음 | 순찰 대상에서 빠진다 |
| `….warnings` | 모두 | `/경고목록` — 내 경고 |

`<초>` 노드는 문자열로 짓는 노드라 선언하지 않는다 — LuckPerms 로 준 것만 본다(op 라고 생기지 않는다).

## 동작

- **밴 접속 화면**(`ban.override-login-message`): 들어오기 직전(비동기)에 우리 문구 — 사유 · 남은 기간 · 차단한 사람. 끄면 바닐라 화면.
- **채팅**(LOWEST, 계획 6절): 채팅 잠금(우회 권한 빼고, 액션바) → 수갑(`cuff.mute`) → 뮤트(본인에게 남은 시간·사유) / 그림자 뮤트(받는 사람에서 남을 빼 본인·콘솔만) →
  감옥(`jail.chat-range` 블록 밖 사람을 받는 사람에서 뺌, -1 = 막기). 귓속말·답장·도움요청·우편은 chat·mail 기능이 `ess.moderation` 으로 막는다(`mute.deny-…` 는 chat 설정).
  **디스코드**: 그림자 뮤트·감옥 거리 채팅(범위 > 0)은 core `ChatVisibility.isHidden` 으로 알리고, inmc-discord 가 MONITOR 에서 그 글은 중계하지 않는다.
- **명령**(LOWEST): 수갑 = `cuff.allowed-commands` 만 · 감옥 = `jail.whitelisted-commands` 만 · 뮤트 = `mute.blocked-commands`(바닐라 me·say·teammsg) 막기.
  첫 낱말로 견주고(이름공간 `minecraft:` 무시) 별칭은 따로 적어야 한다.
- **감옥**: 남은 시간은 접속해 있는 동안 1초씩 준다(`count-while-offline` 이면 나가 있는 동안도 · `no-afk` 면 잠수 중 멈춤). 보스바·제목으로 남은 시간.
  영역 밖으로 한 걸음은 막고, 그래도 밖이면(밀림·영역을 고침) 1초 안에 감방으로. 감옥 밖으로 가는 순간이동·포탈은 모두 막는다(관리자 소환 포함 — 먼저 석방). 이 기능이 감방으로 보내는 이동만 지난다.
  죽으면 감방에서 부활. 피해(/kill·공허 빼고)·배고픔 막기. 가둘 때·풀 때 `jail.commands`. 모든 이동은 이동 기능(teleport)을 지난다.
  감방이 없는 감옥은 영역 **가운데 높이**의 가운데로 보낸다(감방을 두는 것이 낫다). 감방은 영역 안에 있어야 한다 — `jails.yml` 의 영역 밖·못 읽는 감방은 **쓰지 않되 파일에는 그대로
  남긴다**(로그 경고. 영역을 바로잡고 리로드하거나 `/감옥편집 영역` 으로 새 영역 안에 들면 돌아온다 — 손으로 고친 실수가 영구 손실이 되지 않게). CMI 가져오기는 영역 밖 감방을 빼고 보고한다.
  나가 있는 사람을 석방하면(`/석방`·감옥 삭제·시간 다 됨) 석방 자리를 적어 뒀다가 다음 접속 때 보낸다(감방 안에서 로그아웃했어도 갇히지 않게).
  `data/jails.yml` 을 손으로 고쳤으면 `/에센셜 리로드` 가 다시 읽는다.
- **수갑**: 제자리(떨어지기만 둔다) · 상호작용·블록·버리기·공격·탈것 타기 막기(타고 있던 탈것은 움직이는 순간 내린다) · 진주·후렴과·포탈·관전 이동 막기(관리자가 옮기는 것은 둔다).
- **경고 문턱**: 살아 있는 경고 점수 합이 문턱을 **새로 넘는 순간**(예: 2 → 3) 그 문턱의 명령. 이미 넘은 문턱은 다시 돌지 않는다. 만료된 경고는 10초마다 치운다.
- **IP 기록**: 들어오기 직전(`ip.record-delay-seconds: 0`) 또는 들어온 뒤 그만큼 지나서. 같은 uuid·IP 는 횟수만 늘린다.
- **경보**: 경보가 걸린 사람이 들어오면 `alert.inform` 운영진(그를 볼 수 있는 사람)과 콘솔에게 + 메모가 있으면 메모 수.

## 데이터 도구 (교환 · 삭제 · 열 초기화 · 미접속 정리)

1. 먼저 `확인` 없이 친다 → **미리 보기**: 바뀔 표·열과 줄 수(DB 에서 센다), 대상 사람, 바닐라 파일 수.
2. 60초 안에 같은 명령 끝에 `확인`(미리 보기의 `[확인]` 을 눌러도 된다) → **예약**(번호).
3. **서버를 다시 켤 때** 다른 기능이 표를 읽기 전에(DB 표 만들기 단계) 적용과 예약 지우기를 **한 트랜잭션**으로 하고 로그에 남긴다(적용 뒤 죽어도 두 번 돌지 않는다).
   - 왜 바로 하지 않나: 홈·우편·킷 등 여러 기능이 켤 때 표를 메모리에 든다 — 돌고 있는 서버에서 줄을 바꾸면 메모리의 값이 다음 쓰기에 되살아난다.
   - 기능이 쓰는 DB(`storage.type: mysql` 이면 공용, 아니면 로컬)에서만, 켤 때만 적용한다 — DB 옮기기가 받는 쪽에 표를 만들 때나 "다른 쪽" DB 의 옛 예약은 돌지 않는다.
   - **공용 DB 를 여러 서버가 쓰면** 모든 서버를 끈 뒤 하나만 켜서 적용할 것 — 켜져 있던 서버의 메모리 값이 다음 저장에 되써진다(적용하면 경고 로그).
   - 적용은 켜기의 DB 대기 한도(core 30초) 안에서 **15초**까지만 한다. 미접속 정리는 사람 500명씩 조각마다 한 트랜잭션(지우기 + 예약 줄을 남은 사람으로 고치기)으로 하고,
     15초가 지나면 멈춰 나머지는 **다음 시작에 이어서**(로그에 "다음 시작에 이어서"). 기준 시각으로 다시 거르므로 몇 번에 나눠도 같은 결과. 교환·삭제·초기화는 쪼개지 않는다.
   - `… 예약목록` 으로 보고 `… 예약취소 [번호]` 로 뺀다 — **그 종류의 명령 권한이 있는 것만** 보이고 지워진다(열초기화 권한만으로 미접속 정리 예약을 못 지운다).
- **사람 열**: `ess_` 표의 `uuid` · `owner` · `player` · `…_uuid` 열 + `data-tools.extra-columns`(기본 `ess_chat_ignores.target` · `ess_teleport_blocks.blocked` — 남이 그 사람을 무시·차단한 기록도 따라간다), 뺄 표는 `exclude-tables`.
  "준 사람"(경고·메모의 `source`·`author`)은 이름으로 들어 있어 따라가지 않는다. 우편의 `sender_id` 는 일부러 넣지 않았다(지우면 남이 받은 우편까지 지운다).
- **교환**: 사람 열에서 두 uuid 를 맞바꾼다. 계정 자체의 기록(`ess_users` 이름·접속 · IP 기록 · IP 잠금)은 바꾸지 않는다. Bukkit 밴 목록·바닐라 파일·다른 플러그인 DB 도 그대로.
- **삭제**: 사람 열에서 그 uuid 의 줄을 지운다(`ess_users` 포함). `중복` = 같은 이름의 기록 중 서버 방식과 맞지 않는 uuid(온라인 서버의 오프라인 v3 uuid 등) —
  같은 이름의 진짜 계정이 남을 때만. 이름을 넘겨받은 다른 사람의 옛 기록은 건드리지 않는다. 서버 방식은 `server.properties online-mode` **또는 프록시(Velocity·BungeeCord)의 온라인 모드**(Paper `ServerConfiguration`) — 미리 보기 제목에 어느 쪽인지 나온다.
- **열 초기화**: 그 열의 DB 기본값(없고 비울 수 있으면 NULL)으로. 키 열·기본값을 알 수 없는 열은 거절. `-p:` 는 그 표의 사람 열로, `-w:` 는 `world` 열이 있는 표만.
- **미접속 정리**: 마지막 접속이 `(일수)`(기본 `purge.offline-days`)보다 오래된, 접속 중이 아닌 사람. 적용 때 그 사이 들어온 사람은 다시 뺀다.
  바닐라 파일(메인 월드의 `purge.folders` 안 `<uuid>.dat`·`.dat_old`·`.json`)은 **`-파일` 을 붙였을 때만** — `purge.delete-files: false` 면 `<폴더>_backup` 으로 옮긴다.

## PlaceholderAPI — `%inmcmod_…%`

`meta_<키>` · `metaint_<키>`(정수) · `muted` · `mute_time` · `mute_reason`(그림자 뮤트는 false·빈칸) · `cuffed` · `jailed` · `jail_name` · `jail_cell` · `jail_time` ·
`jail_count_<감옥>` · `warnings` · `warning_points` · `notes` · `chat_locked`.

## CMI 가져오기 (`/에센셜 가져오기 cmi`)

감옥 정의(`Saves/Jails.yml` — `Loc`·`safeLoc`·`cells.<번호>.location`, 영역 밖 감방은 뺌) · 뮤트 · 갇힌 사람(남은 시간 = 풀릴 때 − 지금) · 경고(분류는 이름·별칭으로 맞추고, 점수·만료는
지금 설정의 분류로) · 메모(`notes.expire-days` 가 지난 것은 건너뜀 · 때가 초 단위면 밀리초로) · 유저 메타(키는 소문자로, 이름 규칙에 안 맞는 키는 "못 읽음") · 수갑 · 경보 · IP 기록 · IP 잠금 ·
밴(`BannedUntil`·`BanReason`·`BannedBy`·`BannedAt` → Bukkit 밴 목록, 이미 있으면 건너뜀).
이미 있는 것은 건너뛰고, 모양을 모르는 것(풀릴 때가 없는 CMI 밴 등)은 보고서의 "못 읽음" 으로 남는다.

## CMI 와 다른 점

- 데이터 도구(교환·삭제·열 초기화·정리)는 **다음 시작 때 적용**된다(위). CMI 는 바로 했다. `purge.CleanOnStart`(확인 없는 자동 정리)는 만들지 않았다.
- 교환·삭제는 이 플러그인의 `ess_` 표만 다룬다 — 다른 inmc 플러그인의 DB·PlayerStore(`players/<uuid>.yml`)·바닐라 파일은 그대로(정리의 `-파일` 빼고). LWC·Essentials 정리는 없다.
- 감옥 이탈 검사는 걸음마다 + 1초(CMI `CheckInterval` 500ms 설정 없음). 감옥·감방 플레이스홀더는 PAPI `jail_count_<감옥>` 하나.
- 수갑·감옥 중 관리자의 소환도 막힌다(감옥) — 먼저 석방할 것. 수갑은 관리자 이동은 둔다.
- 채팅 필터 위반 시 자동 경고·수갑(CMI 채팅 필터 연동)은 없다 — 필터가 이 플러그인에 없다.
- 순찰 이동 입자(`ParticleEffects.yml TeleportEffects.Patrol`)는 이동 기능의 ADMIN 이동 효과를 따른다.
- 오프라인 대상의 우회 권한은 op 인지로만 본다(LuckPerms 를 리플렉션으로 묻지 않는다).
- 그림자 뮤트·감옥 거리 채팅은 디스코드로 나가지 않는다(core `ChatVisibility` — inmc-discord 2026-10-13 부터). 다른 채팅 중계 플러그인을 쓰면 그쪽도 이 창구를 봐야 한다.
- `/정보`(admininfo)의 뮤트 표시는 그림자 뮤트를 따로 보이지 않는다(그 기능이 `ess.moderation.isShadowMuted` 를 보면 된다).

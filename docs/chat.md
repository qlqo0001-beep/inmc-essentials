# 채팅(chat) — 운영 안내

CMI 의 msg/reply · ignore · helpop · staffmsg · 명령·표지판·귓속말 스파이(options cmdSpy/signSpy/chatSpy) · 같은 말 도배 막기 ·
거리 채팅과 `!` 외침 · broadcast · silence · 링크 축약 · titlemsg 를 대신한다. 설정 `plugins/inmc-essentials/settings/chat.yml`, 메시지 `messages/chat.yml`.

**채팅의 모양(이름·칭호·색)은 이 기능이 아니라 타이틀포지가 정한다.** 이 기능은 렌더러를 부르지 않고 받는 사람(`viewers()`)·취소·글 내용만
고친다(채팅 순서 계약 — ARCHITECTURE). 디스코드는 지금처럼 모든 공개 채팅을 받는다(거리 채팅이어도 — 사용자 결정).

## 명령

| 명령 (영어 별칭) | 하는 일 | 권한 (기본) |
|---|---|---|
| `/귓 <플레이어> <내용>` (msg, tell, w, m, whisper, 귓말, 귓속말) | 귓속말. 플레이어 자리에 `콘솔`(`console`·`server`·`서버`)이면 서버 콘솔에게 — 단 이름이 정확히 같은 (보이는) 접속자가 있으면 그 사람이 먼저 | `inmcessentials.chat.msg` (모두) |
| `/답장 <내용>` (r, reply) | 대화 상대에게(아래 "답장 상대") | `inmcessentials.chat.reply` (모두) |
| `/무시` (ignore) | 내 무시 목록(누르면 풀기) | `inmcessentials.chat.ignore` (모두) |
| `/무시 <플레이어\|uuid\|all>` | 무시하기 — 다시 치면 풀기. `all` = 모두 무시(운영진 글만 온다) | 위와 같음 |
| `/무시 -p:<이름> [대상]` | 남의 무시 목록 보기·고치기 | `inmcessentials.chat.ignore.others` (OP) |
| `/도움요청 <내용>` (helpop) | 접속한 운영진에게 | `inmcessentials.chat.helpop` (모두) · 받기 `.helpop.receive` (OP) |
| `/스태프 <내용>` (staffmsg, sc) | 스태프 채널로 보내기 | `inmcessentials.chat.staff` (OP) |
| `/스태프` · `/스태프 켜기\|끄기\|토글` | 스태프 모드 — 켜 두면 채팅창의 글이 전부 스태프 채널로(재시작하면 꺼짐) | 위와 같음 |
| `/스파이` (spy) | 내 스파이 상태(누르면 켜고 끄기) | `inmcessentials.chat.spy` (OP) |
| `/스파이 <명령\|표지판\|채팅\|전체> [켜기\|끄기\|토글] [플레이어]` | 스파이 켜고 끄기(저장됨). CMI 이름 `cmdspy`·`signspy`·`chatspy`·`socialspy` 도 된다 | `.spy` · 남의 것은 `.spy.others` (OP) |
| `/공지 <내용>` (broadcast, bc) | 공지. `-w:월드,월드` 그 월드만 · `-r:거리` 그 거리 안만(중심은 내 자리 또는 `-c:월드;x;y;z`) · 앞에 `!` = 머리말 없이. 한글 `-월드:` `-거리:` `-위치:` 도. 거리가 양수가 아니거나(`-r:5O`) `-c:` 만 주고 `-r:` 를 빠뜨리면 **공지하지 않고** 알린다(서버 전체 공지가 되지 않게) | `inmcessentials.chat.broadcast` (OP) |
| `/채팅끄기` (silence) | 내 화면에서만 공개 채팅 끄고 켜기(저장됨 — `/설정` 의 "공개 채팅 끄기" 와 같다) | `inmcessentials.chat.silence` (모두) |
| `/타이틀 <플레이어\|all> <제목 \n 부제목>` (titlemsg) | 화면 가운데 제목. 글자 그대로의 `\n` 으로 부제목을 나눈다. `-in:틱` `-keep:틱` `-out:틱`(한글 `-들어옴:` `-유지:` `-나감:`) | `inmcessentials.chat.title` (OP) |

바닐라 `/minecraft:msg`·`/minecraft:tell`·`/minecraft:w` 를 쳐도 우리 `/귓` 으로 바뀐다(사용자 결정 — 바닐라 귓속말은 무시·뮤트·스파이를 모른다).
CMI 를 뺀 뒤 LuckPerms 에서 `minecraft.command.msg/tell/w` 를 막아 두면 더 확실하다.

### 그 밖의 권한

| 권한 | 기본 | 뜻 |
|---|---|---|
| `inmcessentials.chat.msg.clean` | OP | 귓속말 앞 `!` — 보낸 이름 없는 깨끗한 글(CMI `cmi.command.msg.clean`) |
| `inmcessentials.chat.msg.noreply` | OP | 귓속말 앞 `!-` — 깨끗하고 답장할 수 없는 글 |
| `inmcessentials.chat.msg.bypass` | OP | 귓속말 받기를 끈 사람·그룹 제한에도 보내기 |
| `inmcessentials.chat.msg.group.<그룹>` | (선언 안 함 — OP) | `private.groups.enabled` 일 때 그 그룹에게 귓속말(CMI PrivateMessagesGroups). LuckPerms 로 준다. 받는 사람의 그룹은 `order` 의 앞에서부터 **실제로 준** `group.<이름>` 노드(LuckPerms 그룹 소속)로 정한다 — OP 라고 모든 그룹이 되지 않는다 |
| `inmcessentials.chat.color` | OP | 귓속말·도움요청·스태프 글에 색(`&a`·`<red>` — 색·꾸밈·그라데이션만, 클릭·호버는 안 됨) |
| `inmcessentials.chat.ignore.bypass` | OP | 무시당해도 귓속말·채팅·편지가 간다(운영진) |
| `inmcessentials.chat.spy.all` | OP | 명령 스파이의 보기 목록(`spy.only-commands`)을 무시하고 전부 본다 |
| `inmcessentials.chat.spy.bypass` | OP | 명령 스파이의 숨김 목록(`spy.hidden-commands` — 로그인 등)까지 본다(CMI `cmi.command.commandspy.bypass`) |
| `inmcessentials.chat.spy.exempt` | 없음 | 이 사람의 명령·표지판은 스파이에게 안 보인다(서버 주인용) |
| `inmcessentials.chat.spam.bypass` | OP | 도배 막기 면제(CMI `cmi.chatfilter.spambypass`) |
| `inmcessentials.chat.shout` | 모두 | `!` 로 외치기(CMI `cmi.chat.shout`) |
| `inmcessentials.chat.shout.free` | OP | 외침 비용 면제 |
| `inmcessentials.chat.range.bypass` | OP | 내 채팅은 거리 제한 없이 모두에게(CMI `cmi.chat.rangebypass`) |
| `inmcessentials.chat.range.spy` | OP | 채팅 스파이를 켜면 거리 밖 채팅을 `[범위 밖]` 으로 본다(CMI rangespy) |
| `inmcessentials.chat.silence.bypass` | OP | 채팅을 끈 사람에게도 내 채팅이 보인다(CMI `cmi.command.silence.bypass`) |

## 개인 설정(`/설정`)

core 개인 설정 창구에 올린다 — 플레이어 메뉴의 `/설정` 화면에 "채팅·귓속말" 줄로 저절로 뜬다(CMI `/cmi options` 의 대신).

| 열쇠 | 기본 | 뜻 |
|---|---|---|
| `essentials.accept-pm` | 켬 | 귓속말 받기(CMI acceptingPM). 끄면 "받지 않습니다" — `.msg.bypass` 권한자는 예외 |
| `essentials.pm-sound` | 켬 | 귓속말 소리(CMI pmSound). 소리는 `private.sound` |
| `essentials.silence` | 끔 | 공개 채팅 끄기(`/채팅끄기` 와 같은 값) |

## 동작

- **답장 상대**(CMI 와 같다): `private.reply-to-last-messenger: true` 면 늘 마지막으로 내게 보낸 사람. 기본(false)은 `reply-timeout-seconds`(120초) 안에
  대화한 상대가 있으면 다른 사람이 보내도 그 상대에게, 아니면 마지막으로 보낸 사람에게. 상대가 나갔거나 볼 수 없는 투명이면 "접속 중이 아님".
  나가면 그 사람의 답장 상대 기록을 지운다(CMI 와 같다 — 다시 들어와 `/답장` 하면 "상대 없음").
- **귓속말 막기 순서**: 자기 자신 → 길이(`max-length`) → 뮤트 → 도배(`spam.include-private`) → 그룹 권한 → 받는 사람이 나를 무시 → 받는 사람이 귓속말을 끔.
  받는 사람이 잠수면 보낸 사람에게 알린다. 뮤트: **그림자 뮤트는 `deny-when-muted` 와 상관없이 늘 보낸 사람에게만 보낸 것처럼**(받는 사람에게 가지 않고,
  스파이에게는 `[그림자 뮤트]` 로 보인다). 그룹·무시·받기 끔은 그림자 뮤트여도 똑같이 막는다(결과가 달라지면 본인이 그림자 뮤트를 알아챈다). 보통 뮤트는 `deny-when-muted: true`(기본)일 때만 막는다. 도움요청도 같다.
- **투명**: 볼 수 없는 투명인 사람에게는 귓속말할 수 없다(이름 찾기에서 빠져 "접속 중이 아님"). 투명인 사람이 보낸 귓속말은 그대로 간다.
  스파이·도움요청·스태프 글은 보낸 사람(귓속말은 받는 사람도)을 볼 수 있는 사람에게만 간다. 도움요청은 볼 수 있는 운영진이 없으면 "지금 없음" 이지만 투명인 운영진에게는 간다.
- **무시**: 무시한 사람의 귓속말·편지(`/우편 보내기`)가 오지 않고, `ignore.public-chat: true`(기본, CMI IgnorePublicMessage)면 공개 채팅도 내 화면에서 빠진다.
  `.ignore.bypass` 권한자(운영진)의 것은 온다. 저장은 DB(`ess_chat_ignores`). "운영진이라 무시해도 글이 옵니다" 안내는 그 사람이 **내가 볼 수 있는** 접속자일 때만
  (투명인 운영진의 접속 여부가 새지 않게).
- **공개 채팅의 길**(채팅 순서 계약): LOW 한 처리기에서 차례로 — ① 스태프 모드면 스태프 채널로 돌리고 취소(그림자 뮤트면 본인에게만 스태프 글처럼)
  ② 도배면 취소 ③ `!` 로 시작한 글만 외침으로 보고 `!` 를 뗀다(외침 색 · 비용을 잡아 둔다). 다른 플러그인의 LOWEST 입력 대기(타이틀포지 입력창 등)와
  moderation 의 뮤트·채팅잠금이 다 돈 뒤라 입력값의 `!` 를 떼지 않고 뮤트된 사람의 글을 돌리지 않는다. HIGH — 받는 사람 거르기(나를 무시한 사람 · 채팅을 끈 사람 ·
  거리 밖)와 링크 축약. MONITOR — 외침 비용(나간 외침은 빼고, 취소된 외침은 잡아 둔 몫을 놓는다). 렌더러는 건드리지 않는다.
  (계획의 계약은 "LOWEST 외침" 이었다 — 같은 LOWEST 의 입력 대기와 순서가 정해지지 않아 LOW 로 옮겼다. 그래서 디스코드가 같은 LOW 에서 글을 먼저 뜨면
  디스코드에는 외침의 `!` 가 붙은 채 갈 수 있다.)
- **거리 채팅**: `ranged.general` 이 -1(기본)이면 거리 제한 없음 — 그때는 `!` 도 그냥 글자다. 거리를 켜면 같은 월드의 그 거리 안만 받는다.
  `!` 외침은 `ranged.shout` 거리(0 = 모든 월드). `dynamic` 은 접속 인원에 따라 거리를 바꾼다.
  거리 안에 **내가 볼 수 있는 다른 접속자가 하나도 없으면** 알려 준다(`notify-nobody`) — 받는 사람 목록이 아니라 자리로만 본다. 그래서 투명인 운영진이 근처에
  있어도, 무시·채팅 끄기·그림자 뮤트로 받는 사람이 줄어도 알림이 달라지지 않는다(투명·그림자 뮤트가 새지 않게).
  **디스코드는 거리 채팅도 전부 보낸다**(사용자 결정). 디스코드 플러그인의 @멘션 알림(소리·타이틀)은 그 줄을 실제로 받는 사람(`viewers()`)에게만 간다
  — 나를 무시한 사람·채팅을 끈 사람·거리 밖 사람은 멘션 알림도 받지 않는다.
- **외침 비용**: LOW 에서 (잔액 - 이미 잡아 둔 외침 비용)으로 판단하고 잡아 둔다 — 한 틱에 여러 번 외쳐도 잔액보다 많이 외칠 수 없다. 빼기는 **실제로 나간 외침만**
  MONITOR 뒤 그 사람의 스레드에서, 막힌 외침(뮤트·채팅잠금·도배·다른 플러그인의 취소)은 잡아 둔 몫을 놓는다. 돈이 모자라면 외침이 아니라 보통 글(`!` 그대로)로 간다.
  그림자 뮤트여도 비용은 낸다(본인에게는 외친 것처럼 보이므로 돈이 그대로면 그림자 뮤트가 샌다).
- **도배**: 기본 꺼짐(`spam.enabled: false` — CMI DuplicatedMessagePrevention 기본과 같다). 켜면 `interval-seconds` 안에 `percentage`% 이상 비슷한 글을
  `min-amount` 번 한 뒤의 글을 막는다(빈칸·대소문자 무시 편집 거리). 공개 채팅과 귓속말은 따로 센다.
- **링크 축약**: `link.regex` 에 맞는 주소를 `[링크]`(메시지 `chat-link-label`)로 — 누르면 열리고 올리면 주소가 보인다. 스킴이 없으면 `https://` 를 붙인다.
  디스코드에는 원래 주소가 간다(디스코드가 LOW 에서 글을 떠 간 뒤).
- **사람이 친 글은 틀과 따로 렌더링한다** — 귓속말·도움요청·스태프·스파이 틀의 `{메시지}` 자리에 끼우므로 `<click:…>` 나 `%player_ip%` 를 쳐도 그대로 글자다.
- **명령 스파이**: 취소된 명령도 보인다. 본인 것은 본인에게 안 보낸다. **표지판 스파이**: 다 쓴 표지판 글(**표지판의** 월드·좌표와 함께).
- 귓속말·도움요청은 `private.log-to-console` 이면 서버 로그에 남는다.

## CMI 가져오기

`/에센셜 가져오기 cmi` 때 CMI 사용자의 **무시 목록**을 `ess_chat_ignores` 로 넣는다(이미 있으면 건너뜀, 이 서버에 온 적 없는 uuid 도 넣고 보고에 적는다).

CMI 사용자의 **개인 설정**(`users.Options` — CMI `PlayerOption` 순서대로 `1`/`0` 을 `:` 로 이은 줄)도 읽는다:
`chatSpy`·`cmdSpy`·`signSpy` 가 켜져 있으면 그 스파이를 켜고(`ess_chat_spies`), `acceptingPM`·`pmSound` 가 꺼져 있으면 core 개인 설정 `essentials.accept-pm`·
`essentials.pm-sound` 를 끈다. 기본값과 같은 것은 쓰지 않는다. 1/0 이 아닌 칸·이 CMI 판에 없는 칸은 "못 읽음" 으로 보고한다. 다른 칸(acceptingTPA 등)은 이 기능의 것이 아니다.
CMI 의 "모두 무시"(`/ignore all`)는 무시 목록 속의 특별한 uuid `ffffffff-ffff-ffff-ffff-ffffffffffff` 라 우리 "모두 무시"(`all`)로 바꿔 넣는다.

## CMI 와 다른 점

- CMI `Chat.yml` 의 채팅 형식(GeneralFormat·ClickHoverMessages·HoverItems `[item]`)·채팅방·@태그·말풍선은 여기 없다 — 모양은 타이틀포지, `[item]`·멘션은 디스코드 플러그인 몫.
- `%cmi_chat_range%` 대신 `%inmcchat_range%`(지금 공개 채팅이 닿는 거리 — 동적 거리면 접속 인원에 따라. 제한이 없으면 메시지 `chat-papi-unlimited`, 기본 "무제한").
  PlaceholderAPI 가 켜져 있을 때만 걸린다.
- 번지(BungeeCord) 사이 귓속말·스태프 채팅은 없다.
- 귓속말 받기 끔·소리·채팅 끄기는 CMI options 대신 core 개인 설정(`/설정`)에 있다.
- 스태프 모드는 저장하지 않는다(재시작하면 꺼진다 — 나갔다 들어와도 그대로).
- `/스태프` 글은 보통 뮤트로는 막지 않는다(스태프끼리의 채널). 그림자 뮤트면 본인에게만 보낸 것처럼 보인다.
- "근처에 들은 사람이 없다" 알림은 받는 사람 수가 아니라 보이는 접속자의 자리로 판단한다(위 "거리 채팅").
- 명령 스파이에서 "보기 목록" 을 무시하는 권한은 CMI 의 `cmi.security.admin` 대신 `inmcessentials.chat.spy.all`.
- 스파이를 남에게 켜 주기(`/스파이 … <플레이어>`)는 그 사람이 접속해 있을 때만.

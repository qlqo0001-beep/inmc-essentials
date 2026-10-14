# inmc-essentials 작동법

CMI 를 대신하는 플러그인의 운영 안내. 기능마다 자세한 것(설정 키·CMI 와 다른 점·검증기 목록)은 `docs/<기능>.md`. 설계는 `README.md`.

## 설치

1. `inmc-core` 를 먼저 넣는다(필수 — **같은 묶음으로 빌드한 core jar** 여야 한다). 서버를 끈 채로 `inmc-essentials-1.0.0.jar` 를 `plugins/`(테섭은 `plugins/update/`)에 넣는다.
2. 처음 켜면 `plugins/inmc-essentials/` 에 `config.yml`·`messages/`·`settings/`·`data/essentials.db` 가 생긴다.
3. 여러 서버가 같은 데이터를 쓰려면 `config.yml` 의 `storage.type: mysql` 과 `storage.mysql.url/user/password` 를 적고 다시 켠다
   (이미 쌓인 SQLite 자료는 `/에센셜 데이터 이전 mysql 확인` 으로 옮긴다 — 이전 중에는 접속이 막힌다).
4. CMI 를 쓰던 서버면 **CMI 를 빼기 전에** `/에센셜 가져오기 cmi` → `/에센셜 가져오기 cmi 확인`(아래 "바꾸는 날").

## 관리 명령 — `/에센셜` (별칭 `/ess`, `/inmcess`, 권한 `inmcessentials.admin` · 기본 op)

| 명령 | 하는 일 |
|---|---|
| `/에센셜` | 관리 도움말(플레이어 명령은 `/도움말`) |
| `/도움말 [쪽\|명령]` (ehelp) | **모두** — 이 플러그인의 명령을 기능별로(쓸 수 있는 것만 · 12줄씩 · 누르면 입력창에). `/도움말 홈` 은 그 명령의 쓰는 법 전부 |
| `/에센셜 리로드` | `config.yml`·`messages/*.yml`·`settings/*.yml` 과 기능의 파일(별칭·일정·워프·스폰·감옥…)을 다시 읽는다. 저장소와 기능 켜고 끄기는 재시작 |
| `/에센셜 검증` | 서버 안 자동 검사(기능마다 `checks()`, 전부 ~290개) — 실패만 채팅에, 전부는 `verify/essentials-<시각>.txt` |
| `/에센셜 모듈` | 기능 19개와 켜짐(● 켜짐 · ○ 꺼짐 · ✖ 켜다 실패 — 서버 로그에 까닭) |
| `/에센셜 가져오기 cmi [확인]` | CMI 자료 가져오기 — `확인` 없이 치면 무엇이 있는지만 보여 준다(5분 안에 `확인`). 보고서 `import/cmi-<시각>.txt` |
| `/에센셜 데이터 이전 <mysql\|sqlite> [확인 [덮어쓰기]]` | `ess_` 표 전부를 다른 저장소로 베낀다(이전 중 접속 거절) |
| `/에센셜 명령찾기 <글>` · `권한찾기 <글>` | 이름·설명에 글이 든 명령 · 등록 권한(CMI checkcommand·checkperm) |
| `/에센셜 로그` | 명령 기록 켜짐·오늘 파일 크기·보관 일수(`logs/`) |

## 기능 켜고 끄기

`config.yml` 의 `modules.<id>` 를 `false` 로 바꾸고 **서버를 다시 켠다**. 끈 기능은 명령·리스너가 하나도 없다(DB 표만 남는다 — 다시 켜면 데이터 그대로).
기능이 켜다 실패하면(✖) 그 기능만 빠지고 나머지는 돈다 — 접점(이동·잠수·킷·쿨타임·제재·무적)은 기본값으로 돌아간다.

## 명령 — 기능별

권한은 `inmcessentials.<기능>.<동작>`(남에게 쓰는 것은 `.others`). "모두" = 기본으로 모든 플레이어, "op" = 기본으로 OP 만. 선언되지 않은 노드는 Bukkit 이 op 로 다룬다.
한글 명령은 영어 자판으로 쳐도(`/tmvhs` = `/스폰`) 된다(commands 기능 — 하위 명령까지, 탭 완성에 영타형이 뜬다).

### teleport — `docs/teleport.md`

| 명령 (별칭) | 하는 일 | 권한 |
|---|---|---|
| `/이동 <대상>` · `/이동 <누구> <어디로> [-s]` (tpto) | 대상에게 · 누구를 어디로. 나간 사람은 마지막 자리로(`.offline`) | `.teleport.tp` (op) · `.offline` |
| `/소환 <대상> [-s]` · `/소환 취소 <이름>` (tphere, s) | 내 자리로. 나간 사람은 **다음 접속 때** · 걸어 둔 것 거두기 | `.teleport.here` (op) · `.here.offline` |
| `/강제이동` (tpo) · `/강제소환` (tpohere) | 요청 차단을 무시(관리자 이동 — 금지 아이템 무시, 안전 자리 없으면 그대로) | `.teleport.tpo` · `.tpohere` (op) |
| `/전원소환 [대상] [-s]` (tpall) · `/월드전원소환 <월드\|-a> [위치]` · `취소` (tpallworld) | 보이는 접속자 전원 · 그 월드의 접속자(지금)와 나간 사람(다음 접속) | `.teleport.tpall` · `.tpallworld` (op) |
| `/좌표이동 <x y z> [월드] [pitch yaw] [-p:이름] [-rng:범위] [-s]` (tppos) · `/강제좌표이동` (tpopos) | 좌표로(안전 확인) · 그대로 | `.teleport.tppos` (op) · `-p` `.tppos.others` · `.tpopos` |
| `/이동요청 <대상>` (tpa) · `/소환요청 <대상>` (tpahere) · `… 취소 [대상]` | 요청 · 거두기 | `.teleport.tpa` · `.tpahere` (모두) |
| `/전원소환요청` (tpaall) | 보이는 전원에게 소환 요청 | `.teleport.tpaall` (op) |
| `/수락 [대상]` (tpaccept, tpyes) · `/거절 [대상]` (tpdeny, tpno) · `/요청차단 [이름]` (tptoggle) | 받은 요청 · 받기 켜고 끄기(한 사람만도) | `.tpaccept` · `.tpdeny` · `.tptoggle` (모두) |
| `/뒤로 [대상] [-s]` (back) · `/사망지점 [대상] [-s]` (dback) · `/뒤로초기화 [대상] [-death] [-s]` (resetback) | 마지막 이동 전 · 죽은 자리 · 지우기 | `.back`·`.dback` (모두, 남 `.others` op) · `.resetback` (op) |

### homes · warps · spawn — `docs/homes.md` · `docs/warps.md` · `docs/spawn.md`

| 명령 (별칭) | 하는 일 | 권한 |
|---|---|---|
| `/홈 [이름] [주인] [보낼 사람]` (home) | 하나면 바로, 여럿이면 화면. 남의 홈은 공개 `.visit`, 비공개까지 `.others` | `.homes.use` (모두) |
| `/홈설정 [이름] [비공개] [칸] [재질] [덮어쓰기] [주인:] [위치:]` (sethome) · `/홈삭제 [이름] [주인]` (delhome) · `/홈목록 [주인\|near:반경]` (homes) | 만들기(개수는 `inmcessentials.homes.limit.<n>`·그룹) · 지우기 · 화면 | `.homes.use` · `주인:` `.others` · `위치:` `.customloc` (op) |
| `/워프 [이름] [플레이어] [-s]` · `그룹:<그룹>` · `쪽:N` (warp, warps) | 화면 · 이동 · 남을 보내기 | `.warps.use` (모두) · 워프마다 `inmcessentials.warps.warp.<이름>`(권한 필요 워프) |
| `/워프설정 <이름> […]` (setwarp) · `/워프삭제 [이름]` (delwarp) · `/워프편집 <이름> [새 이름]` (editwarp) · `/워프그룹` (warpgroups) | 만들기·지우기·편집 화면·그룹 화면 | `.warps.set` (op) · 전부 `.admin` · 좌표 `.customloc` |
| `/스폰` · `/스폰 <플레이어> [-s]` (spawn) | 서버 스폰으로(흩는 반경) · 남을 | `.spawn.use` (모두, 옛 `inmcmenu.spawn` 도) · 남 `.others` (op) |
| `/스폰 설정` · `처음설정` · `관리` | 서버 스폰 · 첫 스폰 · 관리 화면(부활 순서·접속 무적·흩기) | `.spawn.admin` (op) |

### chat · mail — `docs/chat.md` · `docs/mail.md`

| 명령 (별칭) | 하는 일 | 권한 |
|---|---|---|
| `/귓 <플레이어\|콘솔> <내용>` (msg, tell, w, m, whisper, 귓말, 귓속말) · `/답장 <내용>` (r, reply) | 귓속말 · 답장(바닐라 `/msg` 는 이것으로 돌린다) | `.chat.msg` · `.reply` (모두) |
| `/무시 [플레이어\|all]` · `-p:<이름>` (ignore) | 무시 목록·토글 · 남의 것 | `.chat.ignore` (모두) · 남 `.ignore.others` (op) |
| `/도움요청 <내용>` (helpop) · `/스태프 [내용\|켜기\|끄기]` (staffmsg, sc) · `/스파이 [종류] [켜기\|끄기] [플레이어]` (spy) | 운영진에게 · 스태프 채널·스태프 모드 · 명령/표지판/채팅/귓속말 스파이 | `.helpop` (모두, 받기 `.helpop.receive` op) · `.staff` · `.spy` (op) |
| `/공지 <내용>` (broadcast, bc) · `/타이틀 <플레이어\|all> <제목 \n 부제목>` (titlemsg) · `/채팅끄기` (silence) | 공지(`-w:` `-r:` `-c:` `!`) · 제목 · 내 화면의 공개 채팅 끄기 | `.broadcast` · `.title` (op) · `.silence` (모두) |
| `/우편` · `받기` · `읽기 [번호\|#id]` · `지우기 [번호\|읽은것\|전체]` · `도움` (mail) | 우편함 화면 · 보상 전부 받기 · 편지 읽기·지우기 | `.mail.use` (모두) |
| `/우편 보내기 <플레이어> <내용>` (send) · `임시 <플레이어> <기간> <내용>` (sendtemp) | 편지 · 기간 뒤 지워지는 편지 | `.mail.send` (모두) |
| `/우편 첨부 <플레이어> [내용]` (attach) · `보기 <플레이어>` (view) · `비우기 <플레이어> 편지\|전체 [확인]` (purge) | 손의 아이템 붙이기 · 남의 우편함 · 비우기 | `.mail.attach` · `.mail.others` (op) |
| `/우편전체 <내용>` · `/우편전체 회수` (mailall) | 최근 N일 접속자 모두에게 · 마지막 묶음 거두기 | `.mail.all` (op) |

### player · afk — `docs/player.md` · `docs/afk.md`

| 명령 (별칭) | 하는 일 | 권한 |
|---|---|---|
| `/게임모드 <모드> [대상] [-s]` (gm) | 모드 바꾸기(CMI 순서도) | `.player.gamemode` (op) · `.others` |
| `/경험치 [대상] [더하기\|설정\|빼기\|초기화 <양>]` (exp) · `/최대체력 [설정\|더하기\|빼기\|초기화] [대상] [수치]` (maxhp) | 보기(모두)·바꾸기 | `.player.exp.check`·`.maxhp.check` (모두) · `.exp`·`.maxhp` (op) |
| `/회복 [대상\|all] [양\|%] [-nofeed] [-ignoreeffects] [-dontextinguish]` (heal) · `/배고픔 [대상\|all] [양]` (feed, hunger) · `/효과 <대상\|all> <효과\|지우기> [초] [세기]` (peffect) | 회복 · 배고픔 · 포션 효과 | `.player.heal`·`.feed`·`.effect` (op) · `.others` |
| `/처치 <대상> [피해원인] [-forced] [-lightning]` · `/자살 [대상]` (suicide) | 죽이기(무적이면 막힘, `-forced` 는 넘는다) · 즉사 | `.player.kill` (op) · `.suicide` (모두, 남 op) |
| `/속도 [대상] [값]` (speed) · `/걷기속도` (walkspeed) · `/비행속도` (flyspeed) | 속도(상한 `.speed.max.<1~10>`) | `.player.speed` (op) · `.others` |
| `/무적 [대상] [켜기\|끄기]` (god) · `/시간제무적 [대상] [시간\|+\|-\|0\|끄기]` (tgod) · `/비행` (fly) · `/시간제비행` (tfly) · `/충돌` (playercollision) | 무적·비행·충돌(저장됨) · 시간제(나갔다 들어와도 남음, `0` = 다시 접속할 때까지) | `.player.god`·`.tgod`·`.fly`·`.tfly`·`.collision` (op) · `.others` |
| `/토템 [보스바]` (totemtoggle) | 가방 속 토템 자동 사용·보스바(개인 설정) | `.player.totem` (모두) |
| `/타기 [플레이어\|목록\|허용]` (mount) · `/내리기 [대상]` (shakeitoff) · `/모자 [대상]` (hat) · `/앉기 [대상] [-persistent]` · `좌표 <x y z>` (sit) | 타기(종류 권한) · 내리기 · 모자 · 앉기 | `.player.ride` (op) · `.shakeitoff`·`.hat`·`.sit` (모두, 남·persistent op) |
| `/근처 [거리]` · `/근처 가리키기 <플레이어>` (near) · `/핑 [대상]` (ping) | 근처 사람(대략 거리, `.near.exact` 는 정확히) · 핑 | `.player.near`·`.ping` (모두) · 남 `.ping.others` (op) |
| `/잠수 [사유] [-s]` · `-p:<플레이어>` (afk) · `/잠수확인 [대상\|all]` (afkcheck) | 잠수 켜고 끄기(싸운 직후는 못 함) · 상태 보기 | `.afk.afk` · `.afk.check` (모두) · 남 `.afk.others` (op) |

### items · inventory · kits — `docs/items.md` · `docs/inventory.md` · `docs/kits.md`

| 명령 (별칭) | 하는 일 | 권한 |
|---|---|---|
| `/지급 <대상\|*\|**> <아이템…> [개수]` (cgive, i) · `/전원지급 <아이템…> [개수] [offline]` (giveall) | 지급(나간 사람·넘친 것은 우편). `*` 접속자 · `**` 최근 N일 | `.items.give` · `.giveall` (op) |
| `/머리 [이름\|텍스처] [받을사람] [개수]` (head, skull) · `/몹머리 <몹> [받을사람] [개수]` (mobhead) | 플레이어·텍스처 머리 · 몹 머리 | `.items.head`·`.mobhead` (op) |
| `/장식 [플레이어] [지우기]` (trim) · `/이름 [-p:] <이름\|지우기>` (itemname) · `/설명 [-p:] …` (itemlore) · `/염색 [플레이어] [값]` (dye) | 갑옷 장식 · 아이템 이름·설명 · 가죽 염색 | `.items.trim`·`.itemname`·`.itemlore` (op) · `.dye` (모두, 움직이는 색 `.dye.dynamic` op) |
| `/비우기 [대상] [옵션…]` · `/검색 <조건…>` · `결과` · `취소` · `지우기` (search) | 가방 비우기 · 모든 플레이어(오프라인 포함)의 가방·엔더·셜커에서 찾기·지우기 | `.items.clear` · `.search` (op) |
| `/작업대` (workbench, wb, craft) · `/모루` · `/숫돌` · `/베틀` · `/석재절단기` · `/대장장이` · `/지도제작대` · `/거래창 <직업> [레벨]` (merchant) | 가상 작업 창(`<플레이어> [-s]` 로 남에게) · 주민 없는 거래창 | 각각 `.items.<station>` · `.merchant` (op) · 남 `.workstation.others` |
| `/인벤 <대상> [보는사람]` (inv, invsee) · `/엔더 [대상]` (ender, ec) · `/엔더비우기 <대상>` (clearender) | 54칸 창(고치기 `.inv.edit`) · 엔더 · 비우기 — 나간 사람은 **대기 편집**(다음 접속 때) | `.inventory.inv`·`.ender`·`.clearender` (op) · 오프라인 `.inv.offline` |
| `/인벤저장 [대상\|*\|** [일]]` (invsave) · `/인벤목록 [주인] [번호\|#id]` (invlist) · `/인벤불러오기 <주인> [번호\|last\|#id] [받을사람]` (invload) · `/인벤삭제 <주인> <번호\|all>` (invremove) | 저장·목록(미리보기)·되돌리기(지금 가방은 백업)·지우기 | `.inventory.save`·`.list`·`.load`·`.remove` (op) |
| `/몰래열기 [켜기\|끄기]` (silentchest) | 조용히 상자 열기(투명 중 자동 · 보호 구역·무덤·urb 상자는 안 열림) | `.inventory.silentchest` (op) · 고치기 `.silentchest.edit` |
| `/킷` · `/킷 <이름\|그룹>` · `/킷 <이름> 미리보기` · `/킷 <이름> <대상\|all> [-skipusage]` (kit, kits) | 목록 화면 · 받기(`inmcessentials.kits.kit.<이름>`) · 미리보기 · 남에게 | `.kits.use` (모두) · 남 `.others` (op) |
| `/킷편집` (kiteditor) · `/킷초기화 <킷\|all> <플레이어\|all> [쿨타임\|횟수]` (kitreset) | 편집 화면 · 되돌리기(나간 사람도) | `.kits.edit` · `.reset` (op) |

### world · decor · portals · holograms — `docs/world.md` · `docs/decor.md` · `docs/portals.md` · `docs/holograms.md`

| 명령 (별칭) | 하는 일 | 권한 |
|---|---|---|
| `/바이옴찾기 <생물군계> [-r:반경] [-좌표]` · `멈춤` · `전부멈춤` (findbiome) | 가까운 생물군계 찾아 이동(타이틀포지 이름·한국어·id) | `.world.findbiome` (op) · `.stopall` |
| `/게임규칙 [월드] [규칙] [값] [all]` | 보기·바꾸기(한국어 규칙 이름) | `.world.gamerule` (op) |
| `/청크복구 [w 월드] [r 반경\|g] [c x:z]` · `상태·일시정지·계속·멈춤·속도·정보·복구` (fixchunk) · `/조명수정 [범위] [플레이어]` · `멈춤` (lfix) | 못 읽는 청크 찾기·복구(**건축이 사라진다**) · 빛 다시 계산 | `.world.fixchunk` · `.lfix` (op) |
| `/몹제거 [깃발…]` (killall) | 반경 안 이름 없는 몬스터(깃발로 종류·반경·전부) — 주운 장비(2.0)만 떨군다 | `.world.killall` (op) · `-force` `.killall.force` |
| `/날씨 [맑음\|비\|폭풍] [고정\|시간] [월드\|all]` · `고정` · `고정해제` · `/sun` · `/rain` · `/storm` | 날씨(시간 `10m`·`1h`·`30분`) | `.world.weather` (op) · 보기 `.weather.check` |
| `/시간 [시각\|낮\|밤\|아침\|저녁] [월드\|all] [-smooth]` · `더하기\|빼기 <양>` · `정지\|재개` · `실제` · `자동실제 <시작\|정지>` · `/day` · `/night` | 시각·프리셋·멈춤·현실 시간 | `.world.time` (op) |
| `/갑옷거치대` · `가까이` · `마지막` · `템플릿 <이름>` · `템플릿목록` · `템플릿삭제` (armorstand) | 거치대 편집기(웅크리고 우클릭도) · 템플릿 | `.decor.armorstand` (모두) · 템플릿 `.armorstand.template.*` |
| `/표지판편집 <줄> [글]` (se) · `/표지판복사 [플레이어] [한면]` (signcopy) · `/동적표지판 [생성\|편집\|삭제\|목록]` (dsign) · `/액자 <투명\|고정\|무적\|발광\|전부\|정보> [켜기\|끄기] [주변 [반경] [강제]]` (itemframe) | 표지판 글 · 복사 붙이기 · 플레이스홀더 표지판 · 액자 | `.decor.sign.command` (모두) · `.sign.copy`·`.dsign`·`.itemframe` (op) · 바닐라 편집 화면도 `.decor.sign.edit` |
| `/포탈 [목록]` · `위치1` · `위치2` · `만들기 <이름>` · `영역` · `목적지` · `안전위치` · `켜기\|끄기` · `가까이` · `정보` · `편집` · `삭제` · `이동` · `명령 <이름> [추가\|삭제\|비우기]` · `다시읽기` (portals) | 포탈 전부(영역은 WorldEdit 선택 또는 위치1·2) | `.portals.admin` (op) · 쓰기 `.portals.use` (모두) · 우회 `.bypass` · 콘솔 명령 넣기 `.console` (op) |
| `/홀로그램 [목록\|가까이]` · `만들기` · `삭제` · `편집` · `여기로` · `이동` · `줄추가` · `줄수정` · `줄끼우기` · `줄삭제` · `쪽` · `동작` · `다시읽기` (hologram, holo) | 홀로그램 관리(아이콘 줄 `icon:…`, 새 쪽 `!nextpage!`) | `.holograms.admin` (op) · 콘솔 명령 `.console` (op) |
| `/홀로그램 넘기기 <이름> [다음\|이전\|번호]` · `/홀로그램 보기` | 내 화면의 쪽 · 켜고 끄기(개인 설정) | `.holograms.pages` · `.toggle` (모두) · 남 `.pages.others` (op) |

### commands · server — `docs/commands.md` · `docs/server.md`

| 명령 (별칭) | 하는 일 | 권한 |
|---|---|---|
| `/별칭` · `/별칭 목록` (aliaseditor) | 별칭 편집 화면(`commands/aliases.yml` — 여러 낱말 별칭·인자·`;;` 여러 명령·콘솔로 돌리기는 편집자 권한) | `.commands.aliaseditor` (op) |
| `/일정` · `실행 <이름>` · `켜기\|끄기 <이름>` · `타이머 <이름>` (schedules) | 일정(`commands/schedules.yml` — 간격·시각·접속자 조건) | `.commands.schedule` (op) |
| (명령 없음) 대기·쿨타임·비용 | `settings/commands.yml rules` — 명령 이름마다. `commit-on-success` 는 우리 이동 명령에만 | 우회 `inmcessentials.commands.bypass.{cooldown,warmup,cost}` · 규칙마다 `.bypass.rule.<id>` |
| `/서버상태` · `틱` · `월드` (status, lag) | TPS·MSPT·CPU·메모리·월드·청크 | `.server.status` (op) |
| `/점검 [켜기 [사유]\|끄기]` (maintenance) · `/최대인원 [수]` (maxplayers) · `/서버소개 [글\|초기화]` (setmotd) | 점검 모드(우회 `inmcessentials.server.maintenance.bypass`) · 최대 인원 · MOTD | `.server.maintenance`·`.maxplayers`·`.motd` (op) |
| `/플레이스홀더` · `목록 <식별자>` · `값 <글>` · `보기 <플레이어> <글>` (placeholders) | PAPI 확장·값 보기 | `.server.placeholders` (op) |
| `/책 [<이름> [대상\|all]]` · `열기 <이름>` · `저자` · `제목` · `풀기` (getbook) · `/텍스트 [<이름> [대상] [쪽]]` · `편집 <이름>` (ctext) · `/서식메시지 <대상\|all> <글>` (ctellraw) | 텍스트 책 · 커스텀 텍스트(`text/`) · MiniMessage 글 | `.server.book`·`.book.open`·`.text`·`.tellraw` |
| `/유틸 좌석정리` · `대상확인` (cutil) | 남은 좌석 지우기 · 바라보는 것 보기 | `.server.util` (op) |

### moderation · admininfo — `docs/moderation.md` · `docs/admininfo.md`

| 명령 (별칭) | 하는 일 | 권한(`inmcessentials.moderation.*` / `.admininfo.*`, 전부 op 가 기본인 것은 "(op)") |
|---|---|---|
| `/밴 [플레이어] (사유) (-s)` · `/기간밴 [플레이어] [시간]` (tempban) · `/아이피밴` · `/기간아이피밴` (tempipban) · `/밴해제` (unban) · `/밴목록 [아이피]` · `/밴확인 [플레이어\|IP]` (checkban) · `/추방 [플레이어\|all]` | 차단(Bukkit BanList) · 추방 | `.ban`·`.tempban`(최대 길이 `.tempban.max.<초>` 또는 `.tempban.max.unlimited`)·`.ipban`·`.unban`·`.banlist`·`.checkban`·`.kick` (op) · 보호 `.ipban.bypass`·`.kick.bypass` |
| `/뮤트 [플레이어] (시간) (사유)` (mute) · `/그림자뮤트` (shadowmute) · `/뮤트해제` (unmute) · `/채팅잠금 (시간)` (mutechat) · `/채팅잠금해제` | 채팅 막기(그림자 = 본인만 봄, 디스코드에도 안 감) · 전체 잠금 | `.mute`·`.shadowmute`·`.unmute`·`.mutechat` (op) |
| `/감옥 [플레이어] (시간) (감옥) (감방) (r:사유)` (jail) · `/석방` (unjail) · `/감옥목록` (jaillist) · `/감옥편집 [만들기\|삭제\|영역\|석방위치\|감방추가\|감방삭제\|보기\|이동]` (jailedit) | 감옥(`jails.yml`, 영역은 WorldEdit 선택) | `.jail`·`.unjail`·`.jaillist`·`.jailedit` (op) |
| `/경고 [플레이어] (분류) (사유)` (warn) · `/경고목록 (플레이어)` (warnings) · `/경고편집 [플레이어]` (editwarnings) | 경고(분류마다 점수·만료, 문턱 명령) | `.warn`·`.editwarnings` (op) · `.warnings` (모두, 남 `.others`) |
| `/수갑 [플레이어] (true\|false)` (cuff) · `/경보 [추가\|삭제\|목록\|하루추가] [플레이어]` (alert) · `/메모 (플레이어) [추가\|삭제\|비우기\|목록]` (note) · `/부계정 (이름\|IP)` (checkaccount) · `/같은아이피` (sameip) · `/아이피잠금 (플레이어) [추가\|삭제\|목록\|비우기]` (lockip) · `/순찰` (patrol) · `/대신실행 [플레이어] [명령\|c:채팅]` (sudo) · `/유저메타 [플레이어] [추가\|삭제\|비우기\|목록\|증가]` (usermeta) | 수갑 · 접속 경보 · 메모 · 부계정·같은 IP · IP 잠금 · 순찰 · 대신 실행 · 키·값 | 각각 같은 이름 (op) · `.checkaccount.showip` |
| `/데이터교환 [원본] [대상] (확인)` (switchplayerdata) · `/유저삭제 [이름\|uuid\|중복] (확인)` (removeuser) · `/열초기화 [표.열] (확인)` (resetdbfields) · `/미접속정리 (일수) (-파일) (확인)` (purge) · `/유저가져오기` (importoldusers) | 데이터 도구(예약 → 다음 시작에 적용, 500명씩 조각) | 각각 같은 이름 (op) |
| `/투명 [대상] [켜기\|끄기] [-s]` · `목록` (vanish, v) · `/투명설정 [대상] [옵션] [켜기\|끄기]` (vanishedit) | 투명(상태의 주인 — 타이틀포지·디스코드·목록이 core `Vanish` 로 본다) · 옵션 | `.admininfo.vanish`·`.vanishedit` (op) · 남 `.others` · 볼 권한 `inmc.vanish.see` |
| `/플레이시간 [플레이어]` (playtime) · `/플레이시간순위` (playtimetop) · `/상세플레이시간` (cplaytime) · `/플레이시간편집 <플레이어> <더하기\|빼기\|설정> <시간>` (editplaytime) · `/플레이보상 [플레이어\|편집]` (prewards) | 플레이 시간·순위·상세·편집·보상(한 번·반복, 권한 보상은 `.prewards.reward.<id>`) | `.playtime`·`.playtime.top`·`.playtime.detail`·`.prewards` (모두) · `.playtime.edit` (op) |
| `/투표 [플레이어]` (votes) · `/투표순위` (votetop) · `/투표편집 <플레이어> [더하기\|빼기\|설정\|초기화] [수]` · `보상` (voteedit) | 투표(Votifier)·순위·편집·보상(투표마다·누적 단계) | `.votes`·`.votes.top` (모두) · `.votes.edit` (op) |
| `/정보 [플레이어]` (info, whois) · `/마지막접속 <플레이어\|uuid>` (seen) · `/최근접속 [쪽]` (lastonline) · `/실명 <닉네임>` (realname) · `/목록` (list) · `/오피목록` (oplist) · `/권한확인 <플레이어> <권한>` (haspermission) · `/통계 [플레이어]` (stats) · `/통계편집 …` (statsedit) | 종합 정보(IP `.info.ip`, 단추 `.info.toggle`) · 접속 기록 · 목록(바닐라 `/list` 를 덮는다) | `.info`·`.lastonline`·`.oplist`·`.haspermission`·`.stats.edit` (op) · `.seen`·`.realname`·`.list`·`.stats` (모두) |

## 메시지·설정 고치기

- `messages/common.yml`(공통)과 `messages/<기능>.yml`. MiniMessage, 자리표시는 `{플레이어}`·`{값}`·`{개수}` … (영문 `{player}` 도 같다).
  값을 `''` 로 비우면 그 메시지를 끈다. 지운 키는 기본값으로 돈다.
- `settings/<기능>.yml` 은 기능 19개 모두 있다(주석에 CMI 의 어느 키였는지). 지운 키는 배포본의 값으로 돈다.
- 고친 뒤 `/에센셜 리로드`. 저장소(`storage`)·기능 켜고 끄기(`modules`)는 재시작.

## 보상

킷·플레이 시간 보상·투표 보상·지급은 가방이 차면 **우편으로** 간다(`config.yml rewards.drop-when-full: false`). 우편 기능이 꺼져 있으면 가방에 안 들어간
것은 **발밑에 떨어진다**(꺼낼 곳이 없으므로). 접속하지 않은 사람에게 가는 보상은 이 플러그인의 보관함(`data/mailbox.yml`)에 남았다가 우편 기능을
켜면 우편으로 넘어간다. 다른 플러그인(커스텀아이템 `/커스텀아이템 지급`, 낚시·인첸트 보상 …)도 core `Mail` 로 같은 우편함에 넣는다.

## 바꾸는 날 — CMI → inmc-essentials 점검표

`../CMI-대체-범위.md` 4절을 작업 결과로 채운 것. 테섭 파일 작업은 스크립트(`py -I switch_day.py plan|apply|rollback`, 작업 스크래치에 있음 — `plan` 이
할 일만 보여 준다)로 한다. **순서가 중요하다** — 가져오기는 CMI 가 아직 깔려 있을 때, 명령 겹침 때문에 CMI 와 함께 켜 두는 시간은 가져오기 동안뿐.

1. **빌드** — `gradle build`(워크스페이스, `--max-workers=2`)와 `inmc-core` 의 `build` 를 **같은 묶음으로**. core 의 `CustomItemHook.ids()`·`makeOnlyNamespaces()`·`ChatVisibility`·`Mail`·`Vanish` 가
   essentials·fishing·enchants·shop·discord·titleforge 와 짝이므로 core jar 를 모듈 jar 와 같이 넣는다(옛 core 와 섞으면 `NoSuchMethodError`, 옛 모듈과 섞으면 `ids overrides nothing`).
2. **백업** — `plugins/` 의 inmc·InMc-TitleForge·CMI·CMILib jar 와 `plugins/CMI`(데이터) · `server.properties` · 타이틀포지·디스코드 `config.yml`(스크립트가 `.prev-jars-<태그>/`·`.bak-<태그>` 로).
3. **CMI 가 켜진 채로 한 번 켜서 가져오기** — 새 jar 전부(essentials 포함)를 넣고 CMI 는 **아직 남긴 채** 서버를 켠다.
   `/에센셜 가져오기 cmi` → 요약(유저·홈·워프·우편·킷·홀로그램·포탈·감옥·일정·별칭 수)을 보고 → `/에센셜 가져오기 cmi 확인`.
   보고서 `plugins/inmc-essentials/import/cmi-<시각>.txt` 의 "못 읽음" 을 전부 읽는다(홈·우편이 소리 없이 사라지는 것이 가장 나쁜 결과). 알아 둘 것:
   - 가져온 **이벤트 명령·일정은 CMI 가 돌고 있는 동안 꺼진 채** 들어온다(두 번 돌지 않게) — CMI 를 뺀 뒤 `/일정 켜기`·별칭 화면에서 켠다(보고서에 적힘).
   - 플레이 시간 자체는 옮기지 않는다(바닐라 통계를 쓴다 — 결정). CMI `TotalPlayTime` 과 통계 중 큰 값으로 한 번 보상의 받은 기록을 맞춘다(`playtimereward.onetime` 이 있으면 그대로).
   - 홈 이름 `Home` 은 `집`(`default-name`)으로 — 다른 자리의 `집` 이 이미 있으면 `Home` 그대로. 같은 이름의 홈·워프는 건너뛴다(재가져오기 안전).
   - 잔액·닉네임·밴·저장 인벤·반복 보상 기록은 옮기지 않는다(잔액은 inmc-economy, 닉네임은 타이틀포지, 밴은 Bukkit `banned-players.json` 이 이미 진실, 인벤은 CMI 직렬화라 `/인벤저장` 으로 다시).
   - 우편의 보관 기간(sendtemp)·그림자 뮤트·감옥(영역 밖 감방은 "빼고 보고")·경고·메모·유저메타·무시·tFly/tGod·투표 수·투명 상태·킷 사용·뒤로/사망 자리·마지막 접속·나간 자리는 옮긴다.
4. **서버를 끄고 CMI·CMILib jar 를 뺀다**(`plugins/CMI` 데이터 폴더는 남겨 둔다 — 다시 가져올 수 있게). `plugins/update/` 에 남은 옛 inmc jar 도 치운다.
5. **설정 치환** — 타이틀포지 `config.yml`(두 곳)·디스코드 `config.yml`(네 곳)의 `%cmi_user_prefix%`→`%luckperms_prefix%`, `%cmi_user_suffix%`→`%luckperms_suffix%`,
   `%cmi_user_balance_formatted%`→`%inmceco_formatted%`. 스코어보드·TAB·BetterHud 등 **다른 설정의 `%cmi_…%`** 도 찾아 바꾼다(`/플레이스홀더 값 <글>` 로 확인) — 대응: `cmi_user_afk`→`%inmcplayer_…%` 가 아니라 잠수는 PAPI 가 없다(목록 `[잠수]` 표시로), `cmi_user_meta_<키>`→`%inmcmod_meta_<키>%`, `cmi_user_muted`→`%inmcmod_muted%`, `cmi_user_cuffed`→`%inmcmod_cuffed%`, 비행·무적·충돌→`%inmcplayer_flying|canfly|god|collision%`, 채팅 거리→`%inmcchat_range%`.
6. **LuckPerms** — `minecraft.command.msg`·`minecraft.command.tell`·`minecraft.command.w` 를 `false` 로(바닐라 귓속말이 우리 `/귓` 보다 먼저 잡히지 않게 — chat 기능이 LOWEST 에서 돌리긴 하지만 막아 두는 것이 안전). `cmi.*` 권한 묶음은 `inmcessentials.*` 로 다시 짠다(기본값: 플레이어용은 모두, 관리자용은 op — 위 표).
   첫 접속 킷·권한 보상·`inmcessentials.homes.limit.<n>`·`inmcessentials.warps.warp.<이름>`·`inmcessentials.kits.kit.<이름>`·`inmcessentials.moderation.tempban.max.<초>` 처럼 설정에서 짓는 노드는 선언되지 않으므로 LuckPerms 에서 직접 준다.
7. **Multiverse → inmc-worlds**(2026-10-15) — Multiverse 3종은 빼고 inmc-worlds 를 쓴다. 모드가 있으면 스폰은 그 사람 모드의 스폰(`/스폰 설정` = 선 곳의 모드 스폰),
   채팅은 채팅 묶음이 다른 모드끼리 안 보이고, 오프라인 가방 편집은 가방 칸(모드)이 같을 때 얹는다. `/에센셜 검증` 의 "다른 플러그인이 부활 자리를 덮는가" 는 inmc-worlds 를 허용한다.
8. **inmc-dungeon** — 던전 안 막는 명령 목록은 코드 기본(essentials 이동 명령 전부 — 영타·별칭 포함)에 `config.yml` 의 목록을 **더한다**(옛 config 를 그대로 둬도 된다).
9. **inmc-menu** — 스폰이 essentials 로 옮겨갔다(`screen: spawn` 버튼은 `player: 스폰` 으로 읽힌다). 메뉴의 `player:` 명령은 "친 것으로" 돌아가므로 `/스폰` 대기(`settings/commands.yml rules`)가 메뉴에서도 먹는다. 스폰 대기를 쓰려면 `rules` 에 `commands: [스폰]` 규칙을 켠다(기본 예제는 꺼져 있음).
10. **켜고 확인** — `/에센셜 모듈`(19개 ●), `/에센셜 검증`(실패 0 — 검증 중 좌석·투명 더미는 되돌려진다), `/우편`(core `Mail` 공급처 = mail), `/투명`(타이틀포지 이름표·디스코드 목록이 함께 숨는지), `/귓`·`/답장`, `/홈`·`/워프`·`/스폰`, `/킷`(첫 접속 킷은 spawn 이 첫 스폰 뒤에 — spawn 을 끄면 kits 가 접속 때 직접), `/잠수`, `/인벤 <나간 사람>`(대기 편집), `/일정`·`/별칭`, `/밴목록`·`/감옥목록`, `/플레이보상`·`/투표`.
    디스코드: 그림자 뮤트 글이 중계되지 않는지, 접속·퇴장 문구가 투명·silent 때 안 올라가는지.
11. **문서** — `ARCHITECTURE.md` 의 스폰 담당·CMI 스폰 미러·표시 이름 짝 문단(스폰은 2026-10-10 반영됨), 각 플러그인 GUIDE·CHANGELOG(타이틀포지의 "디스코드는 투명만 거른다" 문장은 거짓이 됨 — 아래 CHANGELOG 2026-10-14 참고).
12. **되돌리기** — 스크립트 `rollback`(백업 jar·설정을 되돌린다). `plugins/inmc-essentials/` 와 `data/essentials.db` 는 그대로 두어도 CMI 와 충돌하지 않는다(명령은 겹친다 — 둘 다 켜 두지 말 것).

### CMI 와 달라진 것(전체 — 기능별 자세한 것은 `docs/<기능>.md` "CMI 와 다른 점")

- 명령은 전부 한글 주 이름 + 영어 별칭(`/cmi …` 는 없다). `/list` 는 우리 것이 바닐라를 덮는다. `/tpa`·`/tpahere` 의 금지 아이템 종류는 `TPA` 하나.
- 잔액·화폐(inmc-economy) · 닉네임·칭호·접속 메시지(타이틀포지) · 디스코드(inmc-discord) · 스코어보드/TAB/BetterHud 는 다른 플러그인 몫.
- BungeeCord 서버 이동·투명 레벨·포탈 `cancelWarmup`·홀로그램 `Double`/`Fill`/order · 갑옷 거치대 템플릿 파일 가져오기 · 저장 인벤 가져오기는 없다.
- 플레이 시간은 바닐라 통계가 기준(CMI 의 분·시간 표는 안 옮김). 반복 보상 기록은 가져오지 않는다(다음 받기부터).
- 그림자 뮤트한 사람의 외침(`!`)도 비용이 빠진다(일부러 — 본인은 보낸 줄 안다).
- 잠수 중 다른 플러그인이 띄운 제목은 잠수를 풀 때 같이 지워질 수 있다(API 로 가릴 수 없음).
- 던전 막는 목록의 기본 명령은 config 로 풀 수 없다(`inmc-dungeon`).
- 이동: 같은 사람이 두 번 접속한 경우 옛 접속이 남긴 쓰기 순서는 보장하지 않는다 · `/월드전원소환 취소` 는 `/소환` 으로 걸어 둔 것까지 거둔다 · CMI `acceptingTPA` 의 실제 저장 형식은 확인하지 못해 기본값과 다를 때만 옮긴다.
- 잠수: 귓속말 알림에 잠수 사유는 붙지 않는다 · `/근처 가리키기` 는 정확한 방향(CMI 의 어림 방향 아님).
- 포탈: 탈것이 아닌 것(남의 등)에 탄 채로는 포탈을 못 느낀다 · 입자 크기·500ms 입자 없음.
- 관리자 정보: `/stop` 하는 순간의 바닐라 퇴장 문구는 가리지 못한다 · 줍기·사망 메시지·받기 순서 검증기는 없다.
- 제재: 한 조각(500명)의 DB 읽기가 15초를 넘는 아주 느린 DB 면 켜기에 실패한다.
- 명령: 영타 인자 제안은 동기 완성일 때만 뜬다. 킷: CMI 예제 킷의 색만 바꾼 것은 "예제 그대로" 로 보아 가져오지 않는다.
- 검증기는 두 사람이 필요한 것(귓속말→투명)은 건너뛴다.

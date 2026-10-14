# inmc-essentials 변경 기록

---

## 미배포 — 생물군계 이름은 core 창구로 · 인벤 직렬화는 core 로(2026-10-15)

- `/바이옴찾기` 의 타이틀포지 이름을 core `PlaceNames` 로 읽는다(전에는 `biomeNameOf` 리플렉션). 보이는 것·찾는 규칙은 그대로.
- `inventory/InvData` 의 칸·효과 직렬화는 core `ItemCodec` 을 부른다(inmc-worlds 가 같은 것을 쓴다) — 저장 모양은 그대로.

## 미배포 — 손에 든 아이템 대신 올리기·Shift+클릭 · 포탈 입자·색·효과 고르기(2026-10-14)

- 사용자 요청(모든 INMC 관리 화면). 워프 아이콘 · 키트 아이콘 · 홀로그램 아이콘 줄 바꾸기·더하기는 core `ItemInputMenu`(아이템 올리기) — 손에 들지 않는다.
  홀로그램 편집 화면은 **가방의 아이템을 Shift+클릭**하면 그 쪽 끝에 아이콘 줄이 바로 붙는다(사본). 손에 든 아이템을 고치는 명령(`/아이템`·`/염색` 등)은 원래 용도라 그대로.
- 포탈: 입자 종류는 core `ParticlePickMenu`(한국어 이름 · 우클릭 미리보기, 자료가 필요한 것은 DUST 만 받는 규칙 그대로) · 먼지 색은 `ColorPickMenu`(우클릭) ·
  효과는 새 `PortalEffectsMenu`(물약 고르기로 더하기 · 눌러서 초·단계 · Shift+우클릭 빼기 · 소리 칸). 저장 모양(`효과:초:단계`)은 그대로.

## 2026-10-14 — 통합(검토 2차 반영 뒤)

- **빌드·시험**: `:essentials:build` EXIT=0 — 시험 443개(실패 0). 워크스페이스 전체 `build`·`inmc-core build` 도 EXIT=0. 검증기 검사 ~290개(인게임은 전환일에).
- **공용 변경 요청 반영**
  - `command/Args.offline` — **정확한 이름이 먼저**: 보이는 접속자의 정확한 이름 → 기록의 정확한 이름 → 접속자 부분 이름 → 닉네임. 전에는 접속 중인 `Luuk666` 이
    `/밴 Luuk`(나간 `Luuk`)를 가로챘다(B7·B8b 요청).
  - `service/CmiImport.kt` — `CmiMail.keepForSeconds`(sendtemp·mailall 의 4번째 칸, 초). `CmiFormats.mail` 이 읽고(숫자가 아니면 알림) mail 기능이 `보낸 시각 + keepFor` 를 만료로
    쓴다 — CMI 와 같다. 전에는 모든 우편에 설정 일수를 매겼다(B3 요청). DB 위치 형식 주석을 사실(`world:x:y:z:pitch:yaw`)대로.
  - `server/CmiReader` — CMI `playtimereward` 표(`player_id`·`repeatable`·`onetime`)를 읽어 유저 extra `playtimeRewardOnetime`(줄이 있으면 빈 값이라도)·`playtimeRewardRepeat` 로 넘긴다.
    admininfo 가 **받은 한 번 보상 기록을 그대로** 옮긴다(전에는 "형식을 몰라" 로 버리고 시간을 넘은 사람을 받은 것으로 추정했다 — 표가 없거나 못 읽을 때만 그 추정)(B8b 요청).
  - **접점 `ess.abilities`**(`service/Abilities.kt` — isGod·setGod·wantsFlight·timedGodLeft·timedFlightLeft): 주인은 player 기능. world 의 무적 금지 월드·비행 돌려주기가
    player 기능의 메서드를 **이름으로 부르던 리플렉션**(없으면 콘솔 `/무적 … 끄기 -s`)을, admininfo `/정보` 가 `%inmcplayer_…%` 로 읽던 것을 이것으로 바꿨다(B6a·B8b 요청).
    켜다 실패한 기능 되돌리기(`EssentialsPlugin.rollback`)에도 포함. world 검증기 "접점이 끼워져 있다" 로 교체. 메시지 `admininfo-until-relog`.
  - admininfo `/정보` 의 뮤트 표시가 **그림자 뮤트**를 가른다(`<dark_red>그림자 뮤트</dark_red>`)(B8a 요청).
  - inmc-dungeon 기본 막기 목록에 `소환`·`tphere`(던전 안에서 남을 끌어들이기)(B1 요청의 나머지). 거절(`tpno`)은 막지 않는다 — 아무도 옮기지 않는다.
  - `CLAUDE.md` 10(채팅 순서) 을 사실대로: LOW 한 처리기(스태프 모드 → 도배 → 외침·비용) · HIGH viewers+링크 · MONITOR 비용·스파이 · core `ChatVisibility`. 6 에 `abilities`.
- **하지 않은 요청(까닭)**: `Feature.beforeLoad(sql)` 단계(moderation 예약 적용의 30초 한도 — 15초 예산 + 500명 조각으로 완화됐고 극단적으로 느린 DB 만 남은 위험) ·
  `TeleportKind.TP`(teleport 안의 `EssTeleporter.go(…, command)` 로 충분) · `AfkState.reason`(chat 의 잠수 안내에 사유 — 선택) · `CmiData.dynamicSigns`(decor 가 파일을 직접 읽고 보고 줄은 동기라 잃지 않음) ·
  `CmiData` 에 CMI `config.yml`(server 의 `Extras` 로 이미 건넨다) · 업적 `holding` 의 만들기 전용 참조 경고(inmc-achievements 몫).
- 문서: `README.md`(설계·기능 표·접점·표·PDC) · `GUIDE.md`(명령 전부·권한·전환일 점검표·CMI 와 달라진 것) · `docs/mail.md`·`admininfo.md`·`world.md` 갱신 · `ARCHITECTURE.md`(리플렉션 행 제거 · 접점 문단 · PAPI `inmcplayer`·`inmcchat`).

## 2026-10-13 — 검토 2차 반영 (기능별)

- **teleport**: 던전 월드로의 tpa 거절(`tpa.excluded-worlds`) · `OTHER` = 시스템 이동(금지 아이템 무시, 안전 자리 없으면 그대로)과 플레이어 자기 이동 `TP` 종류 분리 ·
  `/월드전원소환` 의 나간 사람은 DB 에만(`tp.pending-expire-seconds`·`tpallworld-offline-days`), `/소환 취소 <이름>`·`/월드전원소환 취소` · 대기 뒤 감옥·수갑·월드 재확인 ·
  JAIL·BACK 이동은 `/뒤로` 에 안 남음 · 탈것·동승자 늘 기억해 실패 때 되돌림 · 각도 CMI 순서(pitch yaw, ±90 밖이면 바닐라) · 종류별 효과 `effects.TP|TPHERE|…` ·
  설정 문제 경고(`TeleportSettings.problems`) · 요청 실패를 상대에게도 알림. 남은 것: 같은 사람의 중복 접속에서 옛 세션의 쓰기 순서.
- **homes·warps·spawn(+inmc-menu)**: 화면의 명령은 **친 것으로**(`asTyped` — `player.chat` 은 채팅 숨김 손님에게 무시됨) · `insertIfAbsent` 가 그 홈만 바꿈 · CMI `Home` 이 다른 자리의
  `집` 과 겹치면 이름 그대로 · 리로드의 `warps.yml`·`spawn.yml` 읽기를 I/O 워커로(그 사이 바뀌었으면 버림) · `/스폰` 자리의 위 두 칸에 불·가루눈·물·마름 장미가 있으면 피함.
- **chat·mail**: 공용 DB 의 보상 두 번 받기 막음 · CMI "모두 무시" 를 all 로 · `/우편 읽기 #<id>` 가 편지만 고른다(보상은 화면에서) · `/귓` 의 잠수 안내 ·
  외침 비용은 MONITOR 에서(취소되면 풀기) · PAPI `inmcchat_range`.
- **player·afk**: 토템 허공 구조는 이동 성공 뒤에만 토템 소비 · 대기 → 쿨타임 순서(`Totems.decide`) · 피해로 일어서기 MONITOR · 잠수 귓속말 안내 중복 제거 ·
  `/근처 가리키기` 의 숨김 규칙 · 공용 DB 5초 저장·접속 3초 뒤 재조정 · 제목은 우리가 띄운 것만 지움 · 낚시 활동 · `/처치` 무적 거절·`-forced` · `{idle}` 토큰 ·
  `TimedMath.next` · PAPI `inmcplayer_*` · `hp-bar.skip-custom-mobs`.
- **items·inventory·kits**: `/인벤` 창 복제 막음 · `/검색 지우기` 범위 · 몰래 열기가 무덤·urb 상자·보호 구역을 안 염 · `/i stone 64`·`Stoneheart` 구분(`knownPlayer`) ·
  spawn 이 꺼져 있을 때 첫 접속 킷은 kits 가 직접 · 오프라인 인벤 편집(대기 편집 — 다음 접속 때 적용, MV-Inv 그룹 기억) · 킷 "CMI 예제 그대로" 판정(색·옛 인첸트 이름 무시) ·
  CoreProtect 에 몰래 열기 기록.
- **world·decor**: 갑옷 거치대 편집기는 모두 · 그림 한 칸씩 · 가져오기 보고에 월드·장식 줄 · `/몹제거` 반경 · 잠자기 가속이 멈춘 시간·고정 날씨를 안 깸 ·
  표지판 편집 설정이 바닐라 편집 화면(`PlayerOpenSignEvent`)도 가름(`.decor.sign.edit` 없으면 못 염 — CMI 는 못 했다) · `/몹제거` 는 주운 장비(2.0)만 떨구고 플러그인의 반드시 떨구는(1.0) 장비는 안 떨굼.
- **portals·holograms**: 망가진 YAML 은 `.broken-<시각>` 사본 + 잠금(저장·편집·가져오기 거부) · 숨은 홀로그램 클릭 무시 · 이동 중 재진입 막음 · 못 읽는 키는 까닭과 함께 보류 ·
  콘솔 명령 넣기는 `.console` 권한 · `update-seconds` 기본 2 · 탈것 탄 채 포탈(`VehicleMoveEvent`) · CMI 기본값(`interactable=false`·`Inform=false`·입자 10·범위 16·배경 검정 125) javap 로 확인 ·
  `Page.Auto` 뜻 · TextDisplay `alignment`·`line-width`·`text-opacity` · CMI `Loc` 은 바닥(가운데로 보정) · 남은 CMI 변수 보고.
- **commands·server**: 별칭 인자 `;;asConsole!` 끼워 넣기 막음(콘솔 실행은 편집자 권한) · 한글 명령으로 던전 막기 우회 막음(inmc-dungeon 기본 목록 = 코드 ∪ 파일) · 별칭이 대상 명령의
  대기·비용을 거침 · 실패한 이동은 비용 없음 · CMI 가 돌면 가져온 일정·이벤트 명령은 꺼진 채 · DB 이전 중 접속 거절 · **`/에센셜 가져오기 cmi`**(server — 모든 `CmiImportable`) ·
  영타 루트의 인자 제안(`AsyncPlayerSendSuggestionsEvent` HIGH, 취소 풀기).
- **moderation**: 오프라인 석방은 다음 접속 때(`ess_moderation_release`) · 그림자 뮤트 글을 디스코드가 안 보냄(core `ChatVisibility`) · 예약 작업은 한 트랜잭션(교환) · 미접속 정리 500명 조각(15초 예산, 다음 시작에 이어서) ·
  오프라인 op IP 밴 거절 · 수갑이 탈것에서 내림 · 영역 밖·못 읽은 감방은 파일에 보류(영역을 고치면 복귀) · `/밴확인` 인자 없이는 `banlist` 권한 · 밴 목록에 연관 계정 · `/정보` 그림자 뮤트 표시는 통합에서.
- **admininfo**: 한 번 보상 재지급 막음(가져오기 기록) · `/마지막접속`·`/정보` 의 투명 새기 막음(`seen`·`seen_join`) · 정확한 오프라인 이름 우선 · 빈 단계 보상 미기록 ·
  CMI 투명 상태·옵션 가져오기(위치 형식 javap) · 사망 메시지 MONITOR · `/stop` 때 투명 유지 · 공용 DB 증분 쓰기 · 플레이 시간 캐시는 로컬 · `/정보` 의 무적·시간제는 player 기능 것 ·
  투표 `count: false` 는 세기만 끔 · 검증기 더미 투명은 메타데이터만.
- **core·discord·업적·만들기 전용 공급처**: core `Vanish` 거울(다른 스레드) · 디스코드는 접속·퇴장 문구가 null 이면 안 올림 · `RewardBundleEditor.showAnnounce` ·
  `CustomItemHook.ids()`·`makeOnlyNamespaces()`(`inmcfish:`·`inmcenchant:`·`inmcshop:` — 만들기만, `/찾기`·`/비우기` 제안에서 뺌, 업적 `holding` 은 조용히 거짓) · `IntegrationReport` 문구.

## 2026-10-10 — 기능 19개 구현

각 기능의 명령·권한·설정·CMI 와 다른 점은 `docs/<기능>.md`. 명령 이름은 계획 4절 표 그대로(한글 주 이름 + 영어 별칭), 사람 인자는 `ess.args`, 모든 이동은 `ess.teleporter`,
이동 성공 때 `ess.gate?.commit`, 투명은 보이는 사람에게만, 못 받은 보상은 우편. 기능마다 CMI 가져오기(`CmiImportable`)·검증기 검사·순수 시험.

- **teleport** — `/이동`·`/소환`·`/강제이동`·`/강제소환`·`/전원소환`·`/월드전원소환`·`/좌표이동`·`/강제좌표이동`·`/이동요청`·`/소환요청`·`/전원소환요청`·`/수락`·`/거절`·`/요청차단`·`/뒤로`·`/사망지점`·`/뒤로초기화`.
  `EssTeleporter`(뒤로 기록·안전 자리·금지 아이템·효과·이동 뒤 무적·탑승 동반)가 `ess.teleporter` 가 된다. 표 `ess_teleport_{spots,tpa,blocks,pending}`. 권한 28개 · 메시지 75.
- **homes** — `/홈`·`/홈설정`·`/홈삭제`·`/홈목록`. 개수 그룹(`inmcessentials.homes.limit.<n>`)·월드 한도·보호구역(WorldGuard·Lands 리플렉션)·침대 홈·화면·공개/비공개. 표 `ess_homes`(메모리, 공용 DB 면 접속 직전 재읽기). 메시지 45.
- **warps** — `/워프`·`/워프설정`·`/워프삭제`·`/워프편집`·`/워프그룹`. `warps.yml`(원자 저장) · 그룹·권한·아이콘·자동 설명. 메시지 43.
- **spawn** — `/스폰`(설정·처음설정·관리). 접속 자리(`AsyncPlayerSpawnLocationEvent` HIGH)·부활 순서(HIGH — 침대·엔드 귀환은 안 건드림)·첫 접속·접속 무적·흩기 · 홈·워프 창구(`SpawnLinks`) · 첫 스폰 뒤 `ess.kits?.giveFirstJoin`. inmc-menu 의 스폰을 가져왔다(옛 `inmcmenu.spawn` 권한·`screen: spawn` 인정). `spawn.yml`. 메시지 12.
- **chat** — `/귓`·`/답장`·`/무시`·`/도움요청`·`/스태프`·`/스파이`·`/공지`·`/채팅끄기`·`/타이틀`. 거리 채팅·외침 `!`(비용)·도배·링크 축약·바닐라 `/msg` 가로채기(LOWEST)·명령/표지판/채팅/귓속말 스파이. 모양(렌더러)은 타이틀포지 몫. 표 `ess_chat_{ignores,spies}`. 메시지 66.
- **mail** — `/우편`(받기·읽기·보내기·임시·첨부·지우기·보기·비우기·도움)·`/우편전체`(회수). **core `Mail` 공급처** — 모든 플러그인의 못 받은 보상이 여기로. 편지함 한도·보관 일수·공용 DB 두 번 받기 막기. 표 `ess_mail`·`ess_mail_keys`. 메시지 53.
- **player** — 게임모드·경험치·최대체력·회복·배고픔·효과·처치·자살·속도·무적·시간제무적·비행·시간제비행·충돌·토템·타기·내리기·모자·앉기·근처·핑. 저장 상태 `ess_player_state`(무적·비행·충돌·시간제), 체력 보스바, 앉기 좌석(PDC `inmcess:seat`), 개인 설정 `essentials.ride-me`·`totem-*`. 메시지 157.
- **afk** — `/잠수`·`/잠수확인`. 자동 잠수·제목·진입/해제 명령·잠수 중 보호·우회 막기·자동 추방·몹 생성 막기 · `ess.afk`. 메시지 24.
- **items** — `/지급`·`/전원지급`·`/머리`·`/몹머리`·`/장식`·`/이름`·`/설명`·`/염색`·`/비우기`·`/검색`·가상 작업대 7종·`/거래창`·셜커 배낭. 아이템 참조는 core `ItemResolver`(바닐라·MMOItems·커스텀아이템·만들기 전용). 메시지 91.
- **inventory** — `/인벤`·`/엔더`·`/엔더비우기`·`/인벤저장`·`/인벤목록`·`/인벤불러오기`·`/인벤삭제`·`/몰래열기`. 나갈 때 스냅샷·오프라인 대기 편집·MV-Inv 그룹·CoreProtect 기록. 표 `ess_inventory_{snapshots,pending}`·`ess_saved_inventories`. 메시지 54.
- **kits** — `/킷`·`/킷편집`·`/킷초기화`. 쿨타임·횟수·비용·조건·명령·그룹과 무게·첫 접속 킷(`ess.kits`)·화면·CMI `Kits.yml` 가져오기(예제 그대로면 건너뜀). 표 `ess_kits`·`ess_kit_usage`. 메시지 30.
- **world** — `/바이옴찾기`·`/게임규칙`·`/청크복구`·`/조명수정`·`/몹제거`·`/날씨`(sun·rain·storm)·`/시간`(day·night). 월드 제한(게임모드·비행·무적·지붕·겉날개·몹 스폰)·잠자기 가속·현실 시간(PDC `inmcess:time_frozen_by_auto`). 타이틀포지 `biomeNameOf`·서버 내부 조명 엔진 리플렉션. 메시지 116.
- **decor** — `/갑옷거치대`(편집기·템플릿 PDC `inmcess:as_template`)·`/표지판편집`·`/표지판복사`·`/동적표지판`·`/액자`·그림 편집·웅크림 표지판 편집(개인 설정 `essentials.shift-sign-edit`). 메시지 61.
- **portals** — `/포탈`(영역·목적지·안전 위치·권한·명령·입자·쿨타임). `portals.yml`. 메시지 49.
- **holograms** — `/홀로그램`(TextDisplay 줄·아이콘 줄·쪽·누르기 동작·거리·개인 보기 `essentials.holograms`). PDC `inmcess:holo`·`holo_line`. `holograms.yml`. 메시지 42.
- **commands** — `/별칭`·`/일정` · 한/영 자판 변환(`/tmvhs`→`/스폰`, 탭 완성) · 대기·쿨타임·비용(`ess.gate` — "성공해야 쿨타임") · 비슷한 명령 안내 · 이벤트 명령 · 명령 반복 막기 · Bukkit 표에 `inmcalias:`·`inmctext:` 등록. 표 `ess_commands_cooldowns`. 메시지 53.
- **server** — `/서버상태`·`/점검`·`/최대인원`·`/서버소개`·`/플레이스홀더`·`/책`·`/텍스트`·`/서식메시지`·`/유틸` · `/에센셜 데이터 이전`·`/에센셜 가져오기 cmi`(`CmiReader` — SQLite 사본·MySQL·YAML, 모든 `CmiImportable`)·`/에센셜 로그`·`명령찾기`·`권한찾기`. 메시지 94.
- **moderation** — 밴(Bukkit BanList)·추방·뮤트·그림자뮤트·채팅잠금·감옥(`jails.yml`)·경고·수갑·경보·메모·부계정·같은아이피·아이피잠금·순찰·대신실행·유저메타·데이터 도구(교환·삭제·열 초기화·미접속 정리·옛 유저 가져오기). `ess.moderation` · core `ChatVisibility` 공급자 · PAPI `inmcmod`. 표 `ess_moderation_*`. 권한 56 · 메시지 177.
- **admininfo** — `/투명`·`/투명설정`(core `Vanish` 상태의 주인)·플레이 시간(바닐라 통계 기준 · 순위 · 상세 · 편집 · 한 번/반복 보상)·투표(Votifier 리플렉션 · 순위 · 편집 · 보상)·`/정보`·`/마지막접속`·`/최근접속`·`/실명`·`/목록`(바닐라 `/list` 덮음)·`/오피목록`·`/권한확인`·`/통계`·`/통계편집`. 표 `ess_admininfo_*`. 권한 31 · 메시지 113.
- 다른 모듈: inmc-menu 스폰 제거(`player: 스폰`), inmc-dungeon 막기 목록 기본값, 타이틀포지 `biomeNameOf`, core `Mail`·`Vanish`·`Selections`·`ChatVisibility`·`RewardBundleEditor`·`CustomItemHook.ids()`.

---

## 2026-10-10 — 뼈대

- 새 모듈 `inmc-essentials`(`:essentials`, 패키지 `com.inmc.essentials`) — CMI 를 대신할 플러그인의 틀. 사용자 결정: 이름 `inmc-essentials`,
  범위는 `../CMI-대체-범위.md`.
- **기능 틀**(`feature/Feature`) — id · 권한(켤 때 등록) · 메시지 기본값(`<id>-` 접두) · 설정 파일 · 표 만들기 · load/enable/ready/disable · 검사.
  기능 19개의 빈 껍데기(teleport·homes·warps·spawn·chat·mail·player·afk·items·inventory·kits·world·decor·portals·holograms·commands·server·
  moderation·admininfo)와 빈 `messages/<id>.yml`. `config.yml modules` 로 켜고 끈다(재시작).
- **로케이터 `Ess`** — core `RewardHost`(보상·우편함·순위) · 기능 사이 접점 넷: `teleporter`(기본 `teleportAsync`) · `afk`(기본 아무도 잠수 아님) ·
  `kits`(첫 접속 킷) · `gate`(성공해야 쿨타임).
- **저장소** — core `SqlWorker`(SQLite `data/essentials.db`, `storage.type: mysql` 이면 공용 DB) · 덮어쓰기 문장(`REPLACE INTO` 없이) ·
  `ess_users`(온 사람 전부 — 이름·처음/마지막 접속·마지막 IP·나간 자리, 메모리에 들고 있음).
- **명령** — 기능이 모아 두면 Paper `COMMANDS` 사건에 한꺼번에 등록(명령 다시 읽기 때마다 다시). 바닐라·Paper 이름과 기능끼리 겹치는 이름은 빼고 로그.
  사람 인자(`Args`) — 보이는 접속자만 제안(투명), 정확히 → 대소문자 무시 → 앞부분 → 들어 있음 → TitleForge 닉네임, 오프라인은 기록에서.
- **`/에센셜`**(`ess`·`inmcess`) — 리로드 · 검증(`verify/essentials-<시각>.txt`) · 모듈.
- 반복 작업 하나(1초 — 순위·30초 저장·기능의 틱), 메시지 카탈로그 하나(`messages/common.yml` + 기능 파일), core 보상 키(우편함·순위·보상 공지).
- 보상은 가방이 차면 우편으로(`rewards.drop-when-full: false`, 만료 없음 — 사용자 결정 "못 받은 보상은 전부 우편").
- 시험 25개: `ResourceTest` · `FeaturesTest` · `ReflectionPurityTest` · `PureTest`.

### 검토 반영 (같은 날)

- **켜다 실패한 기능은 건 것을 거둔다** — 켜는 동안 들어온 명령·`/에센셜` 하위 명령·리스너·틱·권한에 주인(`ess.owner`)을 적어 두었다가, 그 기능이
  던지면 그 몫을 빼고 바꿔 끼운 접점(`teleporter`·`afk`·`kits`·`gate`)·순위 대상을 되돌리고 `disable()` 을 부른다. 전에는 기능이 ✖ 인데 명령·리스너가
  남아 반쯤 만든 상태를 건드렸다.
- 기능 하나의 **생성자**가 던져도 그 기능만 빠진다(전에는 플러그인째 안 켜졌다).
- `ess.enabled(id)`·`ess.feature(id)` 가 켜는 도중에도 맞다 — 기능이 하나 켜질 때마다 목록이 는다(전에는 다 켜질 때까지 전부 false). `enable()` 안에서는
  앞선 기능만 보인다(기능끼리 엮기는 `ready()` 에서 — CLAUDE.md 15).
- **사람 인자가 한글 닉네임을 받는다** — `ess.args.player()`/`players()` 가 빈칸까지 한 낱말을 읽는다(`word()` 는 한글 앞에서 멈춰 닉네임 찾기가 안 됐다).
  `*`·`전원` 도 들어온다. 손님 입력창에는 여전히 `word()` 로 보여 한글은 빨갛게 보이지만 엔터를 치면 된다.
- **서버를 끌 때 접속 중인 사람의 나간 시각·자리를 적는다** — Paper 는 플러그인을 끈 뒤에 접속자를 내보내서 나감 사건이 우리에게 오지 않았다
  (재시작 때 접속해 있던 사람의 마지막 접속·나간 자리가 지난번 것으로 남았다).
- **공용 DB(mysql)에서 첫 접속 오판** — 메모리에 없는 사람은 접속 직전(비동기)에 DB 에서 그 줄을 한 번 읽는다. 다른 서버에서 먼저 온 사람이 여기서
  첫 접속(첫 스폰·환영 방송·첫 킷)으로 처리되지 않는다.
- **우편 기능이 꺼져 있으면 가방에 안 들어간 보상은 바닥에** — 공급처가 없을 때 core 보관함에 쌓이면 꺼낼 명령이 없었다. 오프라인 지급은 그대로 보관함에
  남았다가 우편 기능을 켜면 넘어간다.
- 시험 추가 `SkeletonRulesTest` — 한글 닉네임 인자 · 이름 색인(넘겨받은 이름은 마지막 접속자 · 바꾼 이름은 놓기 · 지우기) · 명령 이름 거르기
  (바닐라 메인/별칭 · 기능끼리 겹침 · 관리 명령 이름) · 실패한 기능의 명령 거두기. 이름 색인을 `store/UserIndex` 로 뺐다.

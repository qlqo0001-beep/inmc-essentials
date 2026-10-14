package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.verify.Check
import com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent
import kr.inmc.core.integration.PluginClasses
import kr.inmc.core.integration.Vanish
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import org.bukkit.GameEvent
import org.bukkit.Statistic
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.GenericGameEvent
import org.bukkit.permissions.PermissionDefault
import java.io.File

/**
 * 관리 정보 — **투명**(상태의 주인 · core `Vanish`) · **플레이 시간**(바닐라 통계가 원본) · 플레이 시간 보상 · **투표**(Votifier 리플렉션) ·
 * 정보·마지막 접속·최근 접속·실명·목록·OP 목록·권한 확인·통계.
 *
 * CMI 항목: cmd-vanish · cmd-votes · playtime-tracking · playtime-top · sys-playtimerewards · playtime-edit · cmd-seen · cmd-realname ·
 * cmd-list · cmd-oplist · cmd-haspermission · cmd-stats · cmd-info · cmd-lastonline. 운영 문서 `docs/admininfo.md`.
 */
class AdminInfoFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "admininfo"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(VANISH, PermissionDefault.OP, "/투명 — 자기 투명 켜고 끄기(저장된 투명·늘 투명 접속도 이 권한이 있어야)"),
        Perm(VANISH_OTHERS, PermissionDefault.OP, "/투명 <플레이어> — 남의 투명"),
        Perm(VANISH_LIST, PermissionDefault.OP, "/투명 목록"),
        Perm(VANISHEDIT, PermissionDefault.OP, "/투명설정 — 자기 투명 옵션"),
        Perm(VANISHEDIT_OTHERS, PermissionDefault.OP, "/투명설정 <플레이어> — 남의 투명 옵션"),
        Perm(PLAYTIME, PermissionDefault.TRUE, "/플레이시간"),
        Perm(PLAYTIME_OTHERS, PermissionDefault.TRUE, "/플레이시간 <플레이어>"),
        Perm(PLAYTIME_TOP, PermissionDefault.TRUE, "/플레이시간순위"),
        Perm(PLAYTIME_DETAIL, PermissionDefault.TRUE, "/상세플레이시간"),
        Perm(PLAYTIME_DETAIL_OTHERS, PermissionDefault.OP, "/상세플레이시간 <플레이어>"),
        Perm(PLAYTIME_EDIT, PermissionDefault.OP, "/플레이시간편집"),
        Perm(PREWARDS, PermissionDefault.TRUE, "/플레이보상 — 플레이 시간 보상 받기"),
        Perm(PREWARDS_OTHERS, PermissionDefault.OP, "/플레이보상 <플레이어> — 남의 보상 화면 보기"),
        Perm(PREWARDS_EDIT, PermissionDefault.OP, "/플레이보상 편집"),
        Perm(PREWARDS_NOTIFY, PermissionDefault.TRUE, "안 받은 플레이 시간 보상 알림(CMI cmi.prewards.notification)"),
        Perm(VOTES, PermissionDefault.TRUE, "/투표"),
        Perm(VOTES_OTHERS, PermissionDefault.TRUE, "/투표 <플레이어>"),
        Perm(VOTES_TOP, PermissionDefault.TRUE, "/투표순위"),
        Perm(VOTES_EDIT, PermissionDefault.OP, "/투표편집 · /투표편집 보상"),
        Perm(INFO, PermissionDefault.OP, "/정보"),
        Perm(INFO_IP, PermissionDefault.OP, "/정보 에서 IP 보기"),
        Perm(INFO_TOGGLE, PermissionDefault.OP, "/정보 화면의 비행·무적 전환 단추(누르면 /비행·/무적 을 친다 — 그 명령의 권한도 필요)"),
        Perm(SEEN, PermissionDefault.TRUE, "/마지막접속"),
        Perm(LASTONLINE, PermissionDefault.OP, "/최근접속"),
        Perm(REALNAME, PermissionDefault.TRUE, "/실명"),
        Perm(LIST, PermissionDefault.TRUE, "/목록 (/list)"),
        Perm(OPLIST, PermissionDefault.OP, "/오피목록"),
        Perm(HASPERMISSION, PermissionDefault.OP, "/권한확인"),
        Perm(STATS, PermissionDefault.TRUE, "/통계 — 자기 통계"),
        Perm(STATS_OTHERS, PermissionDefault.OP, "/통계 <플레이어>"),
        Perm(STATS_EDIT, PermissionDefault.OP, "/통계편집"),
    )

    override val messageDefaults: Map<String, String> = linkedMapOf(
        // --- 투명 ---
        "admininfo-vanish-on" to "<light_purple>투명이 되었습니다.</light_purple> <gray>볼 권한이 있는 사람만 당신을 봅니다.</gray>",
        "admininfo-vanish-off" to "<yellow>다시 보이게 되었습니다.</yellow>",
        "admininfo-vanish-on-other" to "<light_purple>{대상}님을 투명으로 만들었습니다.</light_purple>",
        "admininfo-vanish-off-other" to "<yellow>{대상}님의 투명을 풀었습니다.</yellow>",
        "admininfo-vanish-already-on" to "<gray>{대상}님은 이미 투명입니다.</gray>",
        "admininfo-vanish-already-off" to "<gray>{대상}님은 투명이 아닙니다.</gray>",
        "admininfo-vanish-staff-on" to "<dark_gray>[투명]</dark_gray> <gray>{플레이어}님이 투명이 되었습니다.</gray>",
        "admininfo-vanish-staff-off" to "<dark_gray>[투명]</dark_gray> <gray>{플레이어}님이 다시 보입니다.</gray>",
        "admininfo-vanish-joined" to "<light_purple>투명 상태로 들어왔습니다.</light_purple> <gray>/투명 으로 풀기</gray>",
        "admininfo-vanish-fake-join" to "<yellow>{플레이어}님이 접속했습니다.</yellow>",
        "admininfo-vanish-fake-quit" to "<yellow>{플레이어}님이 나갔습니다.</yellow>",
        "admininfo-vanish-bossbar" to "<light_purple>투명 상태</light_purple>",
        "admininfo-vanish-no-chat" to "<red>투명인 동안은 공개 채팅을 보낼 수 없습니다.</red> <gray>/투명설정 의 '공개 채팅 막기'</gray>",
        "admininfo-vanish-list-header" to "<gold>투명 {개수}명</gold>",
        "admininfo-vanish-list-online" to "<gray>{순번}.</gray> <white>{플레이어}</white> <green>접속 중</green> <dark_gray>({시간}째)</dark_gray>",
        "admininfo-vanish-list-offline" to "<gray>{순번}.</gray> <white>{플레이어}</white> <dark_gray>오프라인 · 다음 접속 때 투명</dark_gray>",
        "admininfo-vanish-list-empty" to "<gray>투명인 사람이 없습니다.</gray>",
        "admininfo-vanishedit-set" to "<green>{대상}님의 '{이름}' 을(를) {값}(으)로 했습니다.</green>",
        "admininfo-vanishedit-unknown" to "<red>모르는 옵션·값입니다: {값}</red>",
        // --- 플레이 시간 ---
        "admininfo-playtime-total" to "<gold>{대상}</gold> <gray>플레이 시간:</gray> <white>{시간}</white>",
        "admininfo-playtime-joined" to "<gray>첫 접속:</gray> <white>{값}</white>",
        "admininfo-playtime-place" to "<gray>순위:</gray> <white>{순위}위</white>",
        "admininfo-playtime-scanning" to "<gray>통계 파일을 읽는 중이라 일부가 빠졌을 수 있습니다. 잠시 뒤 다시 보세요.</gray>",
        "admininfo-playtime-top-header" to "<gold>━ 플레이 시간 순위</gold> <gray>{쪽}/{전체쪽}쪽 · {개수}명</gray>",
        "admininfo-playtime-top-line" to "<gray>{순위}.</gray> <white>{플레이어}</white> <gray>- {시간}</gray>",
        "admininfo-playtime-top-own" to "<yellow>{순위}. {플레이어} - {시간}</yellow> <gray>(나)</gray>",
        "admininfo-top-empty" to "<gray>아직 기록이 없습니다.</gray>",
        "admininfo-playtime-detail-header" to "<gold>━ {대상} 상세 플레이 시간</gold>",
        "admininfo-playtime-detail-line" to "<gray>{이름}:</gray> <white>{시간}</white>",
        "admininfo-playtime-detail-total" to "<gray>전체(바닐라 통계):</gray> <white>{시간}</white>",
        "admininfo-playtime-edit-usage" to "<red>/플레이시간편집 <플레이어> <더하기|빼기|설정> <시간> [-s]</red> <gray>(시간: 1h 30m · 2일 · 그냥 숫자는 분)</gray>",
        "admininfo-playtime-edit-done" to "<green>{대상}님의 플레이 시간: {전} → {후}</green>",
        "admininfo-playtime-edit-queued" to "<green>{대상}님은 접속 중이 아니라 다음 접속 때 적용합니다.</green> <gray>({전} → {후})</gray>",
        "admininfo-playtime-edit-notify" to "<gray>플레이 시간이 바뀌었습니다: {전} → {후}</gray>",
        // --- 플레이 시간 보상 ---
        "admininfo-prewards-claimed" to "<green>플레이 시간 보상 '{이름}' 을(를) 받았습니다.</green> <gray>x{개수}</gray>",
        "admininfo-prewards-inform" to "<yellow>받지 않은 플레이 시간 보상이 있습니다!</yellow> <gray>/플레이보상</gray>",
        "admininfo-prewards-nothing" to "<gray>지금 받을 보상이 없습니다.</gray>",
        "admininfo-prewards-disabled" to "<gray>플레이 시간 보상이 꺼져 있습니다.</gray>",
        "admininfo-prewards-bad-id" to "<red>id 는 영문 소문자·숫자·_- 로 1~32자: {값}</red>",
        "admininfo-prewards-exists" to "<red>이미 있는 id 입니다: {값}</red>",
        "admininfo-prewards-removed" to "<yellow>보상 {값} 을(를) 지웠습니다.</yellow>",
        // --- 투표 ---
        "admininfo-votes-self" to "<gold>투표 {개수}번</gold> <gray>했습니다.</gray>",
        "admininfo-votes-other" to "<gold>{대상}</gold><gray>님은 투표를</gray> <white>{개수}번</white> <gray>했습니다.</gray>",
        "admininfo-votes-last" to "<gray>마지막 투표:</gray> <white>{값}</white> <gray>· 24시간 안 {오늘}번</gray>",
        "admininfo-votes-wait" to "<gray>다음 투표까지</gray> <white>{시간}</white>",
        "admininfo-votes-ready" to "<green>지금 다시 투표할 수 있습니다!</green>",
        "admininfo-votes-no-votifier" to "<red>Votifier(VotifierPlus·NuVotifier) 가 없어 투표를 받지 못하고 있습니다.</red>",
        "admininfo-votes-broadcast" to "<gold>★</gold> <yellow>{플레이어}</yellow><gray>님이 투표했습니다! ({사이트} · 누적 {개수}번)</gray>",
        "admininfo-votes-thanks" to "<green>투표해 주셔서 고맙습니다!</green> <gray>(누적 {개수}번)</gray>",
        "admininfo-votes-top-header" to "<gold>━ 투표 순위</gold> <gray>{쪽}/{전체쪽}쪽 · {개수}명</gray>",
        "admininfo-votes-top-line" to "<gray>{순위}.</gray> <white>{플레이어}</white> <gray>- {개수}번</gray>",
        "admininfo-votes-top-own" to "<yellow>{순위}. {플레이어} - {개수}번</yellow> <gray>(나)</gray>",
        "admininfo-votes-edit-usage" to "<red>/투표편집 <플레이어> <더하기|빼기|설정|초기화> [수] [-s]</red>",
        "admininfo-votes-edit-done" to "<green>{대상}님의 투표 수: {전} → {개수}</green>",
        "admininfo-votes-edit-notify" to "<gray>투표 수가 {개수}(으)로 바뀌었습니다. ({플레이어})</gray>",
        // --- 정보 ---
        "admininfo-info-header" to "<gold>━━━ {대상} ━━━</gold>",
        "admininfo-info-names" to "<gray>닉네임</gray> <white>{닉네임}</white> <gray>· 그룹</gray> <white>{그룹}</white> <gray>· 접두</gray> {접두}",
        "admininfo-info-uuid" to "<gray>UUID</gray> <white><hover:show_text:'<gray>클릭해서 복사</gray>'><click:copy_to_clipboard:'{uuid}'>{uuid}</click></hover></white>",
        "admininfo-info-ip" to "<gray>IP</gray> <white><hover:show_text:'<gray>클릭해서 복사</gray>'><click:copy_to_clipboard:'{값}'>{값}</click></hover></white> <gray>· 핑</gray> <white>{핑}</white>",
        "admininfo-info-joins" to "<gray>첫 접속</gray> <white>{값}</white> <gray>· 마지막 접속</gray> <white>{시간}</white>",
        "admininfo-info-online" to "<green>접속 중</green> <gray>({시간}째)</gray>",
        "admininfo-info-offline" to "<red>오프라인</red> <gray>({시간} 전에 나감)</gray>",
        "admininfo-info-playtime" to "<gray>플레이 시간</gray> <white>{시간}</white> <gray>· 투표</gray> <white>{개수}번</white>",
        "admininfo-info-money" to "<gray>잔액</gray> <white>{금액}</white>",
        "admininfo-info-flags" to "{OP} {잠수} {투명} {뮤트} {감옥}",
        "admininfo-info-state" to "<gray>게임 모드</gray> <white>{값}</white> <gray>· {비행}{시간제비행}(속도 {비행속도}) · 걷기 속도 {걷기속도} ·</gray> {무적}{시간제무적}",
        "admininfo-info-health" to "<gray>체력</gray> <white>{체력}/{최대}</white> <gray>· 공기 {공기} · 배고픔 {배고픔}/20 · 포만 {포만}</gray>",
        "admininfo-info-exp" to "<gray>경험치</gray> <white>{레벨}레벨</white> <gray>({진행}) · 모은 양 {경험치}</gray>",
        "admininfo-info-location" to "<gray>위치</gray> <white><hover:show_text:'<gray>클릭: /이동 명령 넣기</gray>'><click:suggest_command:'/이동 {대상}'>{월드} {x} {y} {z}</click></hover></white>",
        "admininfo-info-logout" to "<gray>나간 자리</gray> <white>{월드} {x} {y} {z}</white>",
        "admininfo-info-bed" to "<gray>침대·리스폰</gray> <white>{월드} {x} {y} {z}</white>",
        "admininfo-info-death" to "<gray>마지막 죽음</gray> <white>{월드} {x} {y} {z}</white>",
        "admininfo-info-toggle-fly" to "<aqua><hover:show_text:'<gray>/비행 — 클릭</gray>'>[비행 전환]</hover></aqua>",
        "admininfo-info-toggle-god" to "<gold><hover:show_text:'<gray>/무적 — 클릭</gray>'>[무적 전환]</hover></gold>",
        "admininfo-until-relog" to "다시 접속할 때까지",
        // --- 마지막 접속 · 최근 접속 · 실명 ---
        "admininfo-seen-online" to "<gold>{대상}</gold><gray>님은</gray> <green>접속 중</green><gray>입니다. ({시간}째)</gray>",
        "admininfo-seen-offline" to "<gold>{대상}</gold><gray>님은</gray> <white>{시간}</white> <gray>전에 나갔습니다. ({값})</gray>",
        "admininfo-lastonline-summary" to "<gray>최근 접속:</gray> {값}",
        "admininfo-lastonline-window" to "<gray>{시간}</gray> <white>{개수}명</white>",
        "admininfo-lastonline-header" to "<gold>━ 최근에 나간 사람</gold> <gray>{쪽}/{전체쪽}쪽 · {개수}명</gray>",
        "admininfo-lastonline-line" to "<gray>{순번}.</gray> <white>{플레이어}</white> <gray>- {시간} 전</gray>",
        "admininfo-realname" to "<white>{값}</white> <gray>→ 실제 이름</gray> <gold>{대상}</gold>",
        "admininfo-realname-nick" to "<gold>{대상}</gold><gray>님의 닉네임:</gray> <white>{값}</white>",
        "admininfo-realname-none" to "<red>'{값}' 닉네임을 쓰는 사람이 없습니다.</red>",
        // --- 목록 · OP 목록 ---
        "admininfo-list-header" to "<gold>접속 {개수}/{금액}명</gold>{투명수}",
        "admininfo-list-hidden-count" to " <dark_gray>(+투명 {개수})</dark_gray>",
        "admininfo-list-group" to "<yellow>{이름}</yellow> <gray>({개수}):</gray> {값}",
        "admininfo-list-all" to "{값}",
        "admininfo-list-separator" to "<gray>, </gray>",
        "admininfo-list-afk" to "<gray>[잠수]</gray>",
        "admininfo-list-hidden" to "<dark_gray>[투명]</dark_gray>",
        "admininfo-oplist-header" to "<gold>OP {개수}명</gold>",
        "admininfo-oplist-online" to "<gray>{순번}.</gray> <white>{플레이어}</white> <green>●</green>",
        "admininfo-oplist-offline" to "<gray>{순번}.</gray> <white>{플레이어}</white>",
        // --- 권한 확인 ---
        "admininfo-haspermission-usage" to "<red>/권한확인 <플레이어> <권한노드></red>",
        "admininfo-haspermission-title" to "<gold>{대상}</gold> <gray>→</gray> <white>{값}</white>",
        "admininfo-haspermission-hard" to "<gray>직접 정해짐({플러그인}):</gray> {값}",
        "admininfo-haspermission-op" to "<gray>정해지지 않았지만 OP 라서:</gray> {값}",
        "admininfo-haspermission-soft" to "<gray>정해지지 않음 · 선언된 기본값({기본})으로:</gray> {값}",
        "admininfo-haspermission-none" to "<gray>정해지지도 선언되지도 않음 · 결과:</gray> {값}",
        "admininfo-haspermission-minmax" to "<gray>숫자 권한 — 최소</gray> <white>{최소}</white> <gray>· 최대</gray> <white>{최대}</white>",
        "admininfo-haspermission-offline" to "<gray>접속 중이 아님 — LuckPerms 결과:</gray> <white>{값}</white>",
        "admininfo-haspermission-offline-unknown" to "<gray>접속 중이 아니고 LuckPerms 로 확인하지 못했습니다.</gray>",
        // --- 통계 ---
        "admininfo-stats-header" to "<gold>━ {대상} 통계</gold> <gray>· 첫 접속 {값}</gray>",
        "admininfo-stats-cell" to "<gray>{이름}</gray> <white>{값}</white>",
        "admininfo-stats-line" to "{값}",
        "admininfo-stats-no-file" to "<red>{대상}님의 통계 파일이 없습니다.</red>",
        "admininfo-statsedit-usage" to "<red>/통계편집 <플레이어> <더하기|빼기|설정> <통계> [블록·아이템·몹] <값> [-s]</red>",
        "admininfo-statsedit-unknown" to "<red>모르는 통계·종류입니다: {값}</red>",
        "admininfo-statsedit-need-sub" to "<red>이 통계는 종류({값})를 적어야 합니다.</red>",
        "admininfo-statsedit-cant" to "<red>이 통계는 바꿀 수 없습니다: {값}</red>",
        "admininfo-statsedit-done" to "<green>{대상}님의 {이름}({하위}): {전} → {후}</green>",
        "admininfo-statsedit-notify" to "<gray>통계 {이름}({하위}) 이(가) {후}(으)로 바뀌었습니다.</gray>",
    )

    @Volatile
    var settings: AdminSettings = AdminSettings()
        private set

    internal lateinit var vanish: VanishService
    internal lateinit var playtime: PlaytimeService
    internal lateinit var prewards: PlaytimeRewardService
    internal lateinit var votes: VoteService
    private lateinit var info: InfoCommands
    private lateinit var listener: AdminListener

    override fun schema(sql: SqlSource) = AdminStore.schema(sql)

    override fun load(settings: YamlConfiguration) {
        this.settings = AdminSettings.from(settings)
    }

    override fun enable() {
        vanish = VanishService(ess, this).also { it.load() }
        prewards = PlaytimeRewardService(ess, this).also { it.load() }
        playtime = PlaytimeService(ess, this) { player, delta -> prewards.onActive(player, delta) }.also { it.load() }
        votes = VoteService(ess, this).also { it.load() }
        listener = AdminListener(ess, this).also { ess.listen(it) }
        VanishCommands(ess, this).register()
        PlaytimeCommands(ess, this).register()
        VoteCommands(ess, this).register()
        info = InfoCommands(ess, this).also { it.register() }
        StatsCommands(ess, this).register()
        // 주기는 설정에서(리로드로 바뀐다) — 틱은 1초마다 걸고 센다.
        var seconds = 0L
        ess.ticks.add("admininfo", 1) { _ ->
            seconds++
            if (seconds % settings.vanishResyncSeconds == 0L) vanish.resync()
            if (seconds % settings.sampleSeconds == 0L) playtime.sample()
            if (seconds % settings.flushSeconds == 0L) {
                playtime.flush()
                prewards.flush()
            }
        }
        ess.ticks.add("admininfo-inform", 60) { now -> prewards.inform(now) }
        ess.ticks.add("admininfo-prune", 3600) { _ ->
            playtime.prune()
            votes.prune()
        }
    }

    override fun ready() {
        vanish.ready()
        for (player in Bukkit.getOnlinePlayers()) playtime.join(player)
        if (settings.scanStatsOnStart) playtime.scanMissing()
        if (votes.hook() == null) ess.logger.info("투표: Votifier(VotifierPlus·NuVotifier) 가 없어 투표를 받지 않습니다 — /투표 는 저장된 수만 보여 줍니다")
    }

    override fun disable() {
        if (::votes.isInitialized) votes.unhook()
        if (::prewards.isInitialized) prewards.stopping = true
        if (::playtime.isInitialized) {
            runCatching { for (player in Bukkit.getOnlinePlayers()) playtime.sampleOne(player) }
            playtime.flush()
        }
        if (::prewards.isInitialized) prewards.flush()
        if (::vanish.isInitialized) vanish.shutdown()
    }

    // --- CMI 가져오기 -------------------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        // config.yml(작은 파일 하나 — 관리자가 한 번 돌리는 가져오기라 메인에서 읽는다). 켜고 끄기 값이 보상 가져오기에 쓰인다.
        val cfgFile = File(data.folder, "config.yml")
        val cfg: YamlConfiguration? = when {
            !cfgFile.isFile -> null.also { report.note("CMI config.yml 이 없어 투표 명령·순위 제외·켜고 끄기 값을 가져오지 않았습니다(플레이 시간 보상은 꺼진 채로 넣습니다)") }
            else -> try {
                YamlConfiguration().apply { load(cfgFile) }
            } catch (t: Throwable) {
                report.unreadable("CMI config.yml", "${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }

        // 투표 수 — 이미 있으면 건드리지 않는다.
        val importedVotes = mutableListOf<Pair<java.util.UUID, Int>>()
        for (u in data.users) {
            val v = u.votes ?: continue
            report.read("투표 수")
            if (votes.importCount(u.id, u.name, v)) {
                report.imported("투표 수")
                importedVotes += u.id to v
            } else {
                report.skipped("투표 수", "${u.name} — 이미 투표 수가 있습니다(${votes.votes(u.id)})")
            }
        }
        // 플레이 시간 — 결정: 바닐라 통계가 원본, CMI 값은 버린다.
        val withTotal = data.users.count { (it.totalPlayTime ?: 0L) > 0L }
        val withDaily = data.users.count { it.extra.containsKey("playtimeDaily") }
        report.note("플레이 시간은 가져오지 않았습니다(결정 2026-10-10: 바닐라 통계 PLAY_ONE_MINUTE 가 원본, CMI TotalPlayTime·시간대별 playtime 표는 버림) — CMI 값이 있던 사람 ${withTotal}명, 하루별 기록 ${withDaily}명")

        importVanish(data, report)

        // 플레이 시간 보상 정의(Settings/PlayTimeRewards.yml) + 받은 기록.
        val ptEnabled = cfg?.getBoolean("PlaytimeRewards.Enabled", false) ?: false
        val yaml = data.playtimeRewards
        if (yaml == null) {
            report.note("CMI PlayTimeRewards.yml: 비었거나 jar 기본값과 같아 가져올 것이 없습니다")
        } else {
            val oneTime = importPlaytimeRewards(yaml, ptEnabled, report)
            importClaims(data, oneTime, report)
        }

        if (cfg != null) {
            importVoteCommands(cfg, report)
            cmiSettingNotes(cfg, report)
        }
        // 가져온 투표 수 아래의 누적 단계는 CMI 가 이미 줬다.
        val marked = importedVotes.sumOf { (id, n) -> votes.markMilestonesUpTo(id, n) }
        if (marked > 0) report.note("가져온 투표 수 아래의 누적 단계 ${marked}개를 '받음' 으로 적었습니다(다시 주지 않게)")
        // 가져오기로 새로 생긴 사용자 기록의 플레이 시간 사본(통계 파일 — 비동기).
        playtime.scanMissing()
    }

    /** CMI users 표의 `Vanish` 열 — 투명 상태와 옵션. 이 플러그인에 이미 줄이 있는 사람은 건너뛴다. */
    private fun importVanish(data: CmiData, report: ImportReport) {
        val levels = mutableListOf<String>()
        val elsewhere = mutableListOf<String>()
        for (u in data.users) {
            val raw = u.extra["Vanish"] ?: continue
            report.read("투명 설정")
            val v = CmiConvert.parseVanish(raw)
            if (v == null) {
                report.unreadable("투명 설정", "${u.name} — 형식을 모릅니다: ${raw.take(120)}")
                continue
            }
            val options = v.values.mapNotNull { (k, b) -> VanishOption.entries.firstOrNull { it.cmiKey == k }?.let { it to b } }.toMap()
            if (vanish.importCmi(u.id, v.vanished, options)) report.imported("투명 설정")
            else report.skipped("투명 설정", "${u.name} — 이미 이 플러그인의 투명 설정이 있습니다")
            if ((v.level ?: 0) > 0) levels += "${u.name}(${v.level})"
            val other = v.values.filter { it.key in CmiConvert.VANISH_ELSEWHERE && it.value }.keys
            if (other.isNotEmpty()) elsewhere += "${u.name}: ${other.joinToString(",")}"
        }
        if (levels.isNotEmpty()) report.skipped("투명 레벨", "투명 레벨은 없습니다(볼 권한 inmc.vanish.see 하나) — ${levels.joinToString(", ")}")
        if (elsewhere.isNotEmpty()) {
            report.note(
                "CMI 투명 옵션 중 다른 기능의 몫(noisyChest → inventory silent-chest.auto-when-vanished · afkcommands → afk commands.when-vanished · " +
                    "PrivateMessages → chat)을 켜 둔 사람: ${elsewhere.joinToString(" / ")} — 사람마다가 아니라 그 기능의 설정 하나로 정합니다",
            )
        }
    }

    /** 보상 정의. 넣은 한 번(PayFor) 보상의 CMI 이름(소문자) → 정의. */
    private fun importPlaytimeRewards(yaml: YamlConfiguration, cmiEnabled: Boolean, report: ImportReport): Map<String, PlaytimeReward> {
        val oneTime = LinkedHashMap<String, PlaytimeReward>()
        for (key in yaml.getKeys(false)) {
            report.read("플레이 시간 보상")
            val s = yaml.getConfigurationSection(key) ?: run { report.unreadable("플레이 시간 보상", "$key — 항목이 아닙니다: ${yaml.get(key)}"); null } ?: continue
            val id = CmiConvert.rewardId(key)
            if (!PlaytimeReward.validId(id)) { report.unreadable("플레이 시간 보상", "$key — id 로 쓸 수 없습니다"); continue }
            if (prewards.get(id) != null) { report.skipped("플레이 시간 보상", "$id — 이미 있습니다"); continue }
            val every = s.getLong("PayEvery", 0)
            val once = s.getLong("PayFor", 0)
            if (every <= 0 && once <= 0) { report.unreadable("플레이 시간 보상", "$key — PayEvery·PayFor 가 없습니다"); continue }
            val converted = CmiConvert.commands(s.getStringList("Commands"))
            for (line in converted.rejected) report.unreadable("CMI 명령", "플레이 시간 보상 $key — 콘솔 명령이 아닌 특수 명령은 옮기지 않습니다: $line")
            val def = PlaytimeReward(
                id = id,
                name = CmiConvert.legacyToMini(s.getString("DisplayName") ?: id),
                description = s.getStringList("Description").map { CmiConvert.legacyToMini(it) }.toMutableList(),
                repeat = every > 0,
                seconds = if (every > 0) every else once,
                autoClaim = s.getBoolean("AutoClaim", false),
                stack = s.getBoolean("StackRewards", true),
                permission = s.getBoolean("Permission", false),
                enabled = cmiEnabled,
                bundle = commandBundle(converted.commands),
            )
            prewards.save(def)
            report.imported("플레이 시간 보상")
            if (!def.repeat) oneTime[key.lowercase()] = def
            if (converted.cmi.isNotEmpty()) report.note("플레이 시간 보상 $id 의 CMI 명령 ${converted.cmi.size}개는 CMI 를 빼면 동작하지 않습니다 — /플레이보상 편집 에서 바꾸세요: ${converted.cmi}")
            if (def.permission) report.note("플레이 시간 보상 $id 는 권한이 필요합니다: ${def.permissionNode} (선언하지 않은 노드라 op 기본 — LuckPerms 로 줄 사람에게 주세요. CMI 노드 cmi.prewards.$key 는 옮겨지지 않습니다)")
        }
        if (!cmiEnabled) report.note("CMI PlaytimeRewards.Enabled 가 꺼져 있어(또는 config.yml 을 못 읽어) 가져온 플레이 시간 보상을 전부 '꺼짐' 으로 넣었습니다 — /플레이보상 편집 에서 켜세요")
        return oneTime
    }

    /**
     * 한 번(PayFor) 보상의 받은 기록. CMI 는 `playtimereward.onetime` 에 둔다 — server 기능의 읽기(`CmiReader`)가 그 열을 유저 extra
     * `playtimeRewardOnetime` 로 넘긴다(2026-10-14 부터 — 줄이 있으면 빈 값이라도). 그 열쇠가 하나라도 있으면 그대로 옮기고, 하나도 없으면(표가 없거나
     * 비었거나 못 읽음) 같은 일을 막으려고 **이미 그 시간을 넘은 사람은 받은 것으로** 적는다(바닐라 통계 사본과 CMI TotalPlayTime 중 큰 값) — 두 번
     * 주는 것보다 덜 나쁘다. 보고서에 적는다.
     */
    private fun importClaims(data: CmiData, oneTime: Map<String, PlaytimeReward>, report: ImportReport) {
        if (oneTime.isEmpty()) return
        val known = data.users.any { it.extra.containsKey(CMI_ONETIME_KEY) }
        val rows = mutableListOf<Pair<java.util.UUID, String>>()
        if (known) {
            for (u in data.users) {
                for (name in CmiConvert.oneTimeClaims(u.extra[CMI_ONETIME_KEY])) {
                    val def = oneTime[name] ?: continue
                    rows += u.id to def.id
                }
            }
            val n = prewards.markClaimed(rows)
            report.imported("플레이 시간 보상 받은 기록", n)
            return
        }
        for (u in data.users) {
            val ticks = maxOf(playtime.ticks(u.id) ?: 0L, (u.totalPlayTime ?: 0L) / 50)
            for (def in oneTime.values) if (ticks >= def.periodTicks) rows += u.id to def.id
        }
        val n = prewards.markClaimed(rows)
        report.imported("플레이 시간 보상 받은 기록", n)
        report.note(
            "CMI 의 한 번 보상 받은 기록(playtimereward.onetime)을 읽지 못해(server 기능의 읽기가 아직 넘기지 않음) 대신 그 시간을 이미 넘은 사람 ${rows.map { it.first }.distinct().size}명의 " +
                "한 번 보상 ${n}개를 '받음' 으로 적었습니다 — CMI 에서 안 받고 둔 사람은 못 받습니다. 플레이 시간을 모르는 사람은 다음 접속 뒤 받을 수 있습니다",
        )
    }

    private fun importVoteCommands(y: YamlConfiguration, report: ImportReport) {
        val onVote = y.getStringList("Votifier.CommandsOnVote")
        if (onVote.isNotEmpty()) {
            report.read("투표 보상", onVote.size)
            when {
                !y.getBoolean("Votifier.PerformCommands", true) -> report.skipped("투표 보상", "CMI Votifier.PerformCommands 가 꺼져 있어 CommandsOnVote 를 넣지 않았습니다")
                !votes.perVote.isEmpty() -> report.skipped("투표 보상", "투표마다 보상이 이미 있습니다")
                else -> {
                    val converted = CmiConvert.commands(onVote)
                    for (line in converted.rejected) report.unreadable("CMI 명령", "CommandsOnVote — 콘솔 명령이 아닌 특수 명령은 옮기지 않습니다: $line")
                    votes.savePerVote(commandBundle(converted.commands))
                    report.imported("투표 보상", converted.commands.size)
                    if (converted.cmi.isNotEmpty()) report.note("투표 보상의 CMI 명령은 CMI 를 빼면 동작하지 않습니다(방송은 votes.broadcast 가 대신) — /투표편집 보상 에서 바꾸세요: ${converted.cmi}")
                }
            }
        }
        val extra = y.getConfigurationSection("Votifier.ExtraRewards") ?: return
        val enabled = y.getBoolean("Votifier.ExtraRewardsEnabled", false)
        for (key in extra.getKeys(false)) {
            val n = key.toIntOrNull()?.takeIf { it > 0 } ?: run { report.unreadable("투표 누적 보상", "$key — 숫자가 아닙니다"); null } ?: continue
            report.read("투표 누적 보상")
            if (!enabled) { report.skipped("투표 누적 보상", "${n}회 — CMI 에서 ExtraRewardsEnabled 가 꺼져 있었습니다(jar 예시일 수 있음)"); continue }
            if (votes.milestones[n] != null) { report.skipped("투표 누적 보상", "${n}회 — 이미 있습니다"); continue }
            val converted = CmiConvert.commands(extra.getStringList(key))
            for (line in converted.rejected) report.unreadable("CMI 명령", "ExtraRewards.$key — 콘솔 명령이 아닌 특수 명령은 옮기지 않습니다: $line")
            votes.saveMilestone(n, commandBundle(converted.commands))
            report.imported("투표 누적 보상")
            if (converted.cmi.isNotEmpty()) report.note("투표 ${n}회 보상의 CMI 명령은 CMI 를 빼면 동작하지 않습니다: ${converted.cmi}")
        }
    }

    /** CMI config 의 값 중 이 기능에 같은 뜻의 설정이 있는 것 — 다르면 보고서에(설정 파일은 고치지 않는다). */
    private fun cmiSettingNotes(y: YamlConfiguration, report: ImportReport) {
        val s = settings
        val ptExclude = y.getStringList("Optimizations.PlaytimeTopExclude").filterNot { it in setOf("Some", "Names", "Here") }
        val ptOffline = y.getInt("Optimizations.PlaytimeTopOffline", 0)
        val voteExclude = y.getStringList("Votifier.ExcludeList").filterNot { it.equals("None", true) }
        if (ptExclude.isNotEmpty() || ptOffline > 0 || voteExclude.isNotEmpty()) {
            report.note("순위 제외는 설정 파일로 옮겨 적으세요(자동으로 고치지 않음) — settings/admininfo.yml playtime.top-exclude: $ptExclude · playtime.top-offline-days: $ptOffline · votes.top-exclude: $voteExclude")
        }
        val pr = y.getConfigurationSection("PlaytimeRewards")
        if (pr != null) {
            val diffs = listOfNotNull(
                "ExcludeAfk=${pr.getBoolean("ExcludeAfk")} → playtime-rewards.exclude-afk".takeIf { pr.getBoolean("ExcludeAfk") != s.rewardsExcludeAfk },
                "RewardInform=${pr.getInt("RewardInform")} → playtime-rewards.inform-minutes".takeIf { pr.getInt("RewardInform", 15) != s.rewardsInformMinutes },
                "OneTimeAmount=${pr.getInt("OneTimeAmount")} → playtime-rewards.one-time-shown".takeIf { pr.getInt("OneTimeAmount", 2) != s.rewardsOneTimeShown },
                "RequiresPermission=${pr.getBoolean("RequiresPermission")} → playtime-rewards.require-permission".takeIf { pr.getBoolean("RequiresPermission") != s.rewardsRequirePermission },
            )
            report.note(
                "CMI PlaytimeRewards.Enabled=${pr.getBoolean("Enabled")} — 여기서는 보상마다 켜고 끄기로 옮겼습니다(전체 스위치 playtime-rewards.enabled 는 그대로)" +
                    if (diffs.isEmpty()) "" else " · 다른 값: ${diffs.joinToString(" · ")}",
            )
        }
        val v = y.getConfigurationSection("Votifier")
        if (v != null) {
            val diffs = listOfNotNull(
                "CountVotes=${v.getBoolean("CountVotes", true)} → votes.count".takeIf { v.getBoolean("CountVotes", true) != s.countVotes },
                "MaxVotesInADay=${v.getInt("MaxVotesInADay")} → votes.max-per-ip-per-day".takeIf { v.getInt("MaxVotesInADay", 0) != s.maxVotesPerIpPerDay },
                "GeneralCooldown=${v.getLong("GeneralCooldown")} → votes.cooldown-seconds".takeIf { v.getLong("GeneralCooldown", 0) != s.voteCooldownSeconds },
                "PerformCommands=${v.getBoolean("PerformCommands", true)} → votes.rewards".takeIf { v.getBoolean("PerformCommands", true) != s.voteRewards },
                "ExtraRewardsEnabled=${v.getBoolean("ExtraRewardsEnabled")} → votes.milestones".takeIf { v.getBoolean("ExtraRewardsEnabled", false) != s.voteMilestones },
            )
            if (diffs.isNotEmpty()) report.note("CMI Votifier 설정이 이 플러그인과 다릅니다(자동으로 고치지 않음): ${diffs.joinToString(" · ")}")
        }
        val vd = y.getConfigurationSection("Vanish.Defaults")
        if (vd != null) {
            val diffs = VanishOption.entries.mapNotNull { opt ->
                val cmi = vd.getBoolean(opt.cmiKey, opt.cmiDefault)
                if (cmi != s.vanishDefault(opt)) "${opt.cmiKey}=$cmi → vanish.defaults.${opt.key}(지금 ${s.vanishDefault(opt)})" else null
            }
            if (diffs.isNotEmpty()) report.note("CMI Vanish.Defaults 가 이 플러그인의 기본값과 다릅니다(자동으로 고치지 않음): ${diffs.joinToString(" · ")}")
            val other = CmiConvert.VANISH_ELSEWHERE.filter { vd.getBoolean(it, false) }
            if (other.isNotEmpty()) report.note("CMI Vanish.Defaults 의 $other 는 다른 기능의 설정입니다(noisyChest → inventory silent-chest.auto-when-vanished · afkcommands → afk commands.when-vanished · PrivateMessages → chat)")
        }
        val timers = y.getConfigurationSection("Optimizations.Commands.lastonline.Timers")?.let { sec -> sec.getKeys(false).map { sec.getLong(it) }.filter { it > 0 } }
        if (timers != null && timers.isNotEmpty() && timers != s.lastOnlineTimers) {
            report.note("CMI lastonline.Timers $timers 가 이 플러그인의 lastonline.timers ${s.lastOnlineTimers} 와 다릅니다(자동으로 고치지 않음)")
        }
        if (y.isBoolean("Optimizations.Commands.List.ASCOrder") && !y.getBoolean("Optimizations.Commands.List.ASCOrder")) {
            report.note("CMI List.ASCOrder=false — /목록 의 그룹 순서는 list.highest-weight-first(무게 큰 그룹이 위, 지금 ${s.listHighestFirst})로 정합니다")
        }
    }

    private fun commandBundle(commands: List<String>): RewardBundle =
        RewardBundle(entries = if (commands.isEmpty()) mutableListOf() else mutableListOf(RewardEntry(chance = 100.0, commands = commands.toMutableList(), giveItem = false)))

    // --- 검증기 ---------------------------------------------------------------------------------------------

    /** [player] 를 메모리에서만 투명으로 두고 [block]. 원래 상태로 되돌린다(가리기·효과·알림·core 감시자 없이 — [VanishService.dryRun]). */
    private fun dryVanish(player: Player, options: Map<VanishOption, Boolean> = emptyMap(), block: () -> String?): String? {
        val was = vanish.isActive(player)
        val saved = options.keys.associateWith { vanish.putOptionMemory(player.uniqueId, it, options[it]) }
        vanish.dryRun = true
        try {
            if (!was) vanish.apply(player, true, announce = false)
            return block()
        } finally {
            if (!was) vanish.apply(player, false, announce = false)
            for ((opt, prev) in saved) vanish.putOptionMemory(player.uniqueId, opt, prev)
            vanish.dryRun = false
        }
    }

    @Suppress("DEPRECATION") // 사건 생성자는 내부용 표시 — 검증기에서 쏘기만 한다
    override fun checks(): List<Check> = listOf(
        Check("투명을 켜면 core Vanish 표식이 서고 끄면 풀린다") { p ->
            dryVanish(p) { if (Vanish.isVanished(p) && vanish.isActive(p)) null else "켰는데 Vanish.isVanished 가 거짓입니다" }
                ?: if (vanish.isActive(p) || !Vanish.isVanished(p)) null else "되돌렸는데 표식이 남았습니다"
        },
        Check("투명 표식은 접속 LOWEST 에서 걸고, 퇴장은 MONITOR 에서 처리한다(타이틀포지·디스코드 계약)") { _ ->
            val l = listener
            val join = PlayerJoinEvent.getHandlerList().registeredListeners.filter { it.listener === l }.map { it.priority }
            val quit = PlayerQuitEvent.getHandlerList().registeredListeners.filter { it.listener === l }.map { it.priority }
            when {
                EventPriority.LOWEST !in join -> "접속 사건에 LOWEST 가 없습니다: $join"
                EventPriority.MONITOR !in quit -> "퇴장 사건에 MONITOR 가 없습니다: $quit"
                else -> null
            }
        },
        Check("보는 사람을 모르면(null) 투명인 사람이 목록에서 빠진다") { p ->
            dryVanish(p) { if (Vanish.visibleOnline(null).none { it == p }) null else "visibleOnline(null) 에 투명인 사람이 있습니다" }
        },
        Check("투명으로 가리는 사람의 마지막 접속은 투명을 켜기 전 값(지금이 아님)") { p ->
            val now = System.currentTimeMillis()
            val fake = (now - 3 * 86_400_000L) to (now - 2 * 86_400_000L)
            val saved = vanish.putSeenMemory(p.uniqueId, fake)
            try {
                val seen = info.lastSeen(p.uniqueId)
                if (seen == fake.second) null else "마지막 접속이 ${seen?.let { now - it }}ms 전 — 투명을 켜기 전 값이어야 합니다"
            } finally {
                vanish.putSeenMemory(p.uniqueId, saved)
            }
        },
        Check("투명 + 피해 받기 꺼짐 → 피해 사건 취소") { p ->
            dryVanish(p, mapOf(VanishOption.PLAYER_DAMAGE to false)) {
                val event = EntityDamageEvent(p, EntityDamageEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), 0.0)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "피해 사건이 취소되지 않았습니다"
            }
        },
        Check("투명 + 몹이 노림 꺼짐 → 노림 사건 취소") { p ->
            dryVanish(p, mapOf(VanishOption.MOB_AGGRO to false)) {
                val event = EntityTargetEvent(p, p, EntityTargetEvent.TargetReason.CLOSEST_PLAYER)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "노림 사건이 취소되지 않았습니다"
            }
        },
        Check("투명 + 남에게 피해 주기 꺼짐 → 때린 피해 취소") { p ->
            // 진짜로 쏘지 않는다 — 때리는 사건은 다른 플러그인(인챈트·커스텀아이템 능력)이 먼저 받아 효과를 낸다. 이 기능의 처리기만 부른다.
            dryVanish(p, mapOf(VanishOption.DAMAGE_TO_ENTITY to false)) {
                val source = DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(p).withDirectEntity(p).build()
                val event = EntityDamageByEntityEvent(p, p, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 0.0)
                listener.onAttack(event)
                if (event.isCancelled) null else "때린 피해가 취소되지 않았습니다"
            }
        },
        Check("투명 + 밟기 상호작용 꺼짐 → 압력판 거부") { p ->
            dryVanish(p, mapOf(VanishOption.INTERACTION to false)) {
                val block = p.location.block.getRelative(BlockFace.DOWN)
                val event = PlayerInteractEvent(p, Action.PHYSICAL, null, block, BlockFace.SELF)
                Bukkit.getPluginManager().callEvent(event)
                if (event.useInteractedBlock() == Event.Result.DENY) null else "밟기 상호작용이 거부되지 않았습니다"
            }
        },
        Check("투명이면 발걸음·진동 게임 사건이 취소된다") { p ->
            dryVanish(p) {
                val event = GenericGameEvent(GameEvent.STEP, p.location, p, 16, false)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "진동 사건이 취소되지 않았습니다"
            }
        },
        Check("투명 + 몹 생성 꺼짐 → 주변 자연 생성 취소") { p ->
            dryVanish(p, mapOf(VanishOption.MOB_SPAWNING to false)) {
                val event = PlayerNaturallySpawnCreaturesEvent(p, 8)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) null else "자연 생성 사건이 취소되지 않았습니다"
            }
        },
        Check("플레이 시간 표본이 오늘 칸에 더해진다") { p ->
            if (vanish.stopsPlaytime(p)) return@Check null // 검증하는 사람이 투명 + 멈춤 — 아래 검사가 본다
            val saved = playtime.memory(p.uniqueId)
            playtime.dryRun = true
            try {
                val stat = playtime.statOf(p)
                if (stat < 200) return@Check null
                playtime.setLastMemory(p.uniqueId, stat - 200)
                val before = playtime.pendingToday(p.uniqueId)
                playtime.sampleOne(p)
                val added = playtime.pendingToday(p.uniqueId) - before
                if (added >= 200) null else "오늘 칸에 ${added}틱만 더해졌습니다(200 이상이어야)"
            } finally {
                playtime.restoreMemory(p.uniqueId, saved)
                playtime.dryRun = false
            }
        },
        Check("투명 + 플레이 시간 멈춤 → 통계가 늘지 않는다") { p ->
            val original = playtime.statOf(p)
            if (original < 200) return@Check null
            val saved = playtime.memory(p.uniqueId)
            playtime.dryRun = true
            try {
                dryVanish(p, mapOf(VanishOption.STOP_PLAYTIME to true)) {
                    playtime.setLastMemory(p.uniqueId, original - 200)
                    val delta = playtime.sampleOne(p)
                    val now = playtime.statOf(p)
                    if (delta == 0L && now == original - 200) null else "멈추지 않았습니다(늘어난 $delta 틱 · 통계 $now)"
                }
            } finally {
                p.setStatistic(Statistic.PLAY_ONE_MINUTE, original.toInt())
                playtime.restoreMemory(p.uniqueId, saved)
                playtime.dryRun = false
            }
        },
        Check("반복 플레이 시간 보상이 주기마다 쌓인다") { p ->
            if (!settings.rewardsEnabled) return@Check null
            val def = PlaytimeReward(VERIFY_REWARD, repeat = true, seconds = 1, stack = true, autoClaim = false)
            val savedState = prewards.stateOrNull(p.uniqueId, VERIFY_REWARD)?.copy()
            prewards.putMemory(def, VERIFY_REWARD)
            prewards.dryRun = true
            try {
                prewards.advance(p, def, 45)
                val s = prewards.stateOrNull(p.uniqueId, VERIFY_REWARD)
                if (s != null && s.pending == 2 && s.progress == 5L) null else "쌓인 수 ${s?.pending} · 진행 ${s?.progress} (2 · 5 여야)"
            } finally {
                prewards.putMemory(null, VERIFY_REWARD)
                prewards.restoreState(p.uniqueId, VERIFY_REWARD, savedState)
                prewards.dryRun = false
            }
        },
        Check("투표: Votifier 사건에 걸려 있고, 투표 하나가 수를 1 늘린다") { p ->
            // 진짜 VotifierEvent 는 쏘지 않는다 — 다른 투표 플러그인이 그 사건으로 진짜 보상을 준다. 걸림만 보고 받는 길은 직접 부른다.
            val hooked = votes.hookedPlugin
            if (hooked != null) {
                val handlers = PluginClasses.find(hooked, VoteService.EVENT_CLASS)?.getMethod("getHandlerList")?.invoke(null) as? org.bukkit.event.HandlerList
                if (handlers == null || handlers.registeredListeners.none { it.plugin === ess.plugin }) return@Check "$hooked 의 투표 사건에 걸려 있지 않습니다"
            }
            if (!settings.countVotes) return@Check null
            val snap = votes.snapshot(p.uniqueId)
            val before = votes.votes(p.uniqueId)
            val tag = "verify-${System.nanoTime()}"
            votes.dryRun = true
            try {
                val outcome = votes.received(p.name, tag, tag)
                val after = votes.votes(p.uniqueId)
                if (after == before + 1) null else "투표 수가 $before → $after ($outcome)"
            } finally {
                votes.restore(p.uniqueId, snap)
                votes.dryRun = false
            }
        },
        Check("/목록 그룹은 무게 큰 것부터") { _ ->
            val groups = ListGrouping.group(listOf("a", "b", "c"), { if (it == "a") "admin" else "default" }, { if (it == "admin") "관리자" to 100 else "기본" to 0 }, true) { it }
            if (groups.map { it.key } == listOf("admin", "default")) null else "순서: ${groups.map { it.key }}"
        },
        Check("목록 인원수는 볼 수 있는 사람만 센다") { p ->
            dryVanish(p) {
                val seen = Bukkit.getOnlinePlayers().count { Vanish.canSee(null, it) }
                if (seen == Bukkit.getOnlinePlayers().size - Bukkit.getOnlinePlayers().count { Vanish.isVanished(it) }) null else "투명인 사람이 인원수에 섞였습니다"
            }
        },
    )

    companion object {
        const val VANISH = "inmcessentials.admininfo.vanish"
        const val VANISH_OTHERS = "inmcessentials.admininfo.vanish.others"
        const val VANISH_LIST = "inmcessentials.admininfo.vanish.list"
        const val VANISHEDIT = "inmcessentials.admininfo.vanishedit"
        const val VANISHEDIT_OTHERS = "inmcessentials.admininfo.vanishedit.others"
        const val PLAYTIME = "inmcessentials.admininfo.playtime"
        const val PLAYTIME_OTHERS = "inmcessentials.admininfo.playtime.others"
        const val PLAYTIME_TOP = "inmcessentials.admininfo.playtime.top"
        const val PLAYTIME_DETAIL = "inmcessentials.admininfo.playtime.detail"
        const val PLAYTIME_DETAIL_OTHERS = "inmcessentials.admininfo.playtime.detail.others"
        const val PLAYTIME_EDIT = "inmcessentials.admininfo.playtime.edit"
        const val PREWARDS = "inmcessentials.admininfo.prewards"
        const val PREWARDS_OTHERS = "inmcessentials.admininfo.prewards.others"
        const val PREWARDS_EDIT = "inmcessentials.admininfo.prewards.edit"
        const val PREWARDS_NOTIFY = "inmcessentials.admininfo.prewards.notify"
        const val VOTES = "inmcessentials.admininfo.votes"
        const val VOTES_OTHERS = "inmcessentials.admininfo.votes.others"
        const val VOTES_TOP = "inmcessentials.admininfo.votes.top"
        const val VOTES_EDIT = "inmcessentials.admininfo.votes.edit"
        const val INFO = "inmcessentials.admininfo.info"
        const val INFO_IP = "inmcessentials.admininfo.info.ip"
        const val INFO_TOGGLE = "inmcessentials.admininfo.info.toggle"
        const val SEEN = "inmcessentials.admininfo.seen"
        const val LASTONLINE = "inmcessentials.admininfo.lastonline"
        const val REALNAME = "inmcessentials.admininfo.realname"
        const val LIST = "inmcessentials.admininfo.list"
        const val OPLIST = "inmcessentials.admininfo.oplist"
        const val HASPERMISSION = "inmcessentials.admininfo.haspermission"
        const val STATS = "inmcessentials.admininfo.stats"
        const val STATS_OTHERS = "inmcessentials.admininfo.stats.others"
        const val STATS_EDIT = "inmcessentials.admininfo.stats.edit"

        /** 검증기의 임시 보상 id(저장하지 않는다). */
        private const val VERIFY_REWARD = "__verify__"

        /**
         * CMI `playtimereward.onetime`(받은 한 번 보상 이름 `;` 목록)을 server 기능의 읽기가 넘겨 주는 유저 extra 열쇠(`CmiReader.EXTRA_PLAYTIME_REWARD_ONETIME` 과 같은 글자 —
         * 기능끼리 import 하지 않아 이름으로). 있으면 그대로 옮기고, 없으면 "이미 넘은 사람은 받음" 으로 적는다([importClaims]).
         */
        const val CMI_ONETIME_KEY = "playtimeRewardOnetime"
    }
}

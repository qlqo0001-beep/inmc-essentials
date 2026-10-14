package com.inmc.essentials.decor

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.util.Ph
import com.inmc.essentials.verify.Check
import io.papermc.paper.event.player.PlayerOpenSignEvent
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.util.AtomicFiles
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.GlowItemFrame
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Painting
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.io.File

/**
 * 갑옷 거치대·그림·액자·표지판 — `/갑옷거치대` · `/표지판편집` · `/표지판복사` · `/동적표지판` · `/액자`, 명령 없는 그림 바꾸기(웅크려 우클릭)·
 * 표지판 다시 편집(웅크려 우클릭)·표지판 색.
 *
 * CMI 항목: deco-armorstand-editor · mod-paintingeditor · cmd-itemframe · cmd-dsign · mod-signedit. 운영 문서 `docs/decor.md`.
 * DB 는 쓰지 않는다 — 템플릿·동적 표지판은 `plugins/inmc-essentials/decor/` 의 yml 두 개(워커에서 원자적으로).
 */
class DecorFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "decor"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(ARMORSTAND, PermissionDefault.TRUE, "갑옷 거치대 편집기 열기(웅크려 우클릭 · /갑옷거치대)"),
        Perm(ARMORSTAND_POSE, PermissionDefault.TRUE, "편집기 — 자세·몸 방향"),
        Perm(ARMORSTAND_MOVE, PermissionDefault.TRUE, "편집기 — 위치 옮기기(연 자리에서 max-move 블록까지)"),
        Perm(ARMORSTAND_MOVE_UNLIMITED, PermissionDefault.OP, "편집기 — 거리 제한 없이 옮기기"),
        Perm(ARMORSTAND_SCALE, PermissionDefault.TRUE, "편집기 — 크기(설정의 min~max)"),
        Perm(ARMORSTAND_SCALE_UNLIMITED, PermissionDefault.OP, "편집기 — 크기 0.0625~16"),
        Perm(ARMORSTAND_TOGGLE, PermissionDefault.TRUE, "편집기 — 받침대·작게·보이기·팔·중력·발광·장비 잠그기(풀기는 .any)"),
        Perm(ARMORSTAND_INVULNERABLE, PermissionDefault.OP, "편집기 — 무적"),
        Perm(ARMORSTAND_NAME, PermissionDefault.TRUE, "편집기 — 이름·이름 보이기"),
        Perm(ARMORSTAND_EQUIP, PermissionDefault.TRUE, "편집기 — 머리·몸·다리·발 장비"),
        Perm(ARMORSTAND_HANDS, PermissionDefault.TRUE, "편집기 — 주 손 장비"),
        Perm(ARMORSTAND_OFFHAND, PermissionDefault.TRUE, "편집기 — 보조 손 장비"),
        Perm(ARMORSTAND_COPY, PermissionDefault.TRUE, "편집기 — 모양 복사·붙여넣기"),
        Perm(ARMORSTAND_TEMPLATE_SAVE, PermissionDefault.OP, "템플릿 저장·삭제"),
        Perm(ARMORSTAND_TEMPLATE_GIVE, PermissionDefault.TRUE, "/갑옷거치대 템플릿 <이름> — 가방의 거치대 하나를 템플릿 거치대로"),
        Perm(ARMORSTAND_TEMPLATE_FREE, PermissionDefault.OP, "템플릿 거치대를 거치대 없이 받기"),
        Perm(ARMORSTAND_ANY, PermissionDefault.OP, "보호 구역 확인 없이 · 장비 잠금 풀기·무시"),
        Perm(PAINTING, PermissionDefault.TRUE, "그림 웅크려 우클릭·휠로 바꾸기"),
        Perm(ITEMFRAME, PermissionDefault.OP, "/액자 — 바라보는 액자"),
        Perm(ITEMFRAME_AREA, PermissionDefault.OP, "/액자 … 주변 <반경>"),
        Perm(ITEMFRAME_ANY, PermissionDefault.OP, "/액자 — 보호 구역 확인 없이"),
        Perm(SIGN_EDIT, PermissionDefault.TRUE, "표지판 웅크려 우클릭 다시 편집(바닐라 우클릭 편집 화면도 이 권한이 없으면 열리지 않는다)"),
        Perm(SIGN_EDIT_WAXED, PermissionDefault.OP, "밀랍칠한 표지판도 편집(입력창으로 — 밀랍은 그대로)"),
        Perm(SIGN_COMMAND, PermissionDefault.TRUE, "/표지판편집"),
        Perm(SIGN_COPY, PermissionDefault.OP, "/표지판복사"),
        Perm(SIGN_COPY_OTHERS, PermissionDefault.OP, "/표지판복사 <플레이어>"),
        Perm(SIGN_BLACKLIST_BYPASS, PermissionDefault.OP, "첫 줄 금지 목록(상점 표지판) 무시"),
        Perm(SIGN_ANY, PermissionDefault.OP, "표지판 — 보호 구역 확인 없이"),
        Perm(COLOR, PermissionDefault.TRUE, "표지판·거치대 이름에 색(&a · <red> — 클릭 사건은 안 됨)"),
        Perm(FORMAT, PermissionDefault.OP, "표지판·거치대 이름에 MiniMessage 전부(클릭·마우스 올리기)"),
        Perm(DSIGN, PermissionDefault.OP, "/동적표지판"),
    )

    override val messageDefaults: Map<String, String> = linkedMapOf(
        "decor-on" to "<green>켬</green>",
        "decor-off" to "<red>끔</red>",
        // --- 갑옷 거치대 ---
        "decor-as-usage" to "<red>/갑옷거치대</red> <gray>[가까이 | 마지막 | 템플릿 <이름> | 템플릿목록 | 템플릿삭제 <이름>] — 웅크리고 우클릭해도 열립니다</gray>",
        "decor-as-look" to "<red>갑옷 거치대를 바라보세요.</red>",
        "decor-as-busy" to "<red>다른 사람이 이 거치대를 편집하고 있습니다.</red>",
        "decor-as-protected" to "<red>이곳의 거치대는 만질 수 없습니다.</red>",
        "decor-as-foreign" to "<red>다른 플러그인이 쓰는 거치대(NPC·상점·가구·마커)는 편집할 수 없습니다.</red>",
        "decor-as-move-protected" to "<red>그쪽은 거치대를 놓을 수 없는 곳이라 옮기지 않았습니다.</red>",
        "decor-as-too-far" to "<red>처음 자리에서 {값}블록 넘게는 옮길 수 없습니다.</red>",
        "decor-as-too-far-away" to "<red>거치대가 너무 멀리 있습니다.</red>",
        "decor-as-locked" to "<red>장비가 잠긴 거치대입니다.</red>",
        "decor-as-copied" to "<green>모양을 복사했습니다.</green> <gray>다른 거치대의 편집기에서 붙여넣기</gray>",
        "decor-as-nothing-copied" to "<red>복사한 모양이 없습니다.</red>",
        "decor-as-updated" to "<green>거치대를 바꿨습니다.</green>",
        "decor-as-none-near" to "<gray>{값}블록 안에 거치대가 없습니다.</gray>",
        "decor-as-list" to "<gray>{개수}.</gray> <white>{값}m</white> <gray>{장비}</gray> <click:run_command:'/갑옷거치대 편집 {id}'><hover:show_text:'<gray>눌러서 편집'><yellow>[편집]</yellow></hover></click>",
        "decor-as-no-last" to "<gray>최근에 편집한 거치대가 없습니다.</gray>",
        "decor-as-template-bad-name" to "<red>템플릿 이름은 소문자 영문·숫자·한글·_ - 32자까지입니다: {값}</red>",
        "decor-as-template-saved" to "<green>템플릿 {이름} 을(를) 저장했습니다.</green> <gray>/갑옷거치대 템플릿 {이름}</gray>",
        "decor-as-template-none" to "<gray>저장한 템플릿이 없습니다.</gray>",
        "decor-as-template-unknown" to "<red>템플릿 '{이름}' 이(가) 없습니다.</red>",
        "decor-as-template-removed" to "<green>템플릿 {이름} 을(를) 지웠습니다.</green>",
        "decor-as-template-list" to "<gold>템플릿 {개수}개:</gold> <white>{값}</white>",
        "decor-as-template-given" to "<green>템플릿 {이름} 거치대를 받았습니다 — 놓으면 그 모양이 됩니다.</green>",
        "decor-as-template-need-stand" to "<red>가방에 갑옷 거치대가 하나 있어야 합니다(그것을 템플릿 거치대로 바꿉니다).</red>",
        "decor-as-template-item" to "<gold>갑옷 거치대 템플릿:</gold> <white>{이름}</white>",
        // --- 그림 ---
        "decor-painting-changed" to "<white>{이름}</white> <gray>({값}) · {개수}/{전체} · 웅크린 채 휠로 넘기기</gray>",
        "decor-painting-only-one" to "<gray>이 크기의 그림은 하나뿐입니다.</gray>",
        "decor-painting-protected" to "<red>이곳의 그림은 바꿀 수 없습니다.</red>",
        // --- 표지판 ---
        "decor-sign-usage" to "<red>/표지판편집 <줄 1-4> [글]</red> <gray>— \\n 으로 다음 줄까지 · 글을 비우면 그 줄을 지웁니다</gray>",
        "decor-sign-look" to "<red>표지판을 바라보세요.</red>",
        "decor-sign-blacklisted" to "<red>'{값}' 표지판은 고칠 수 없습니다.</red>",
        "decor-sign-protected" to "<red>이곳의 표지판은 고칠 수 없습니다.</red>",
        "decor-sign-waxed" to "<red>밀랍칠한 표지판입니다.</red>",
        "decor-sign-is-dynamic" to "<red>동적 표지판입니다 — /동적표지판 으로 고치세요.</red>",
        "decor-sign-updated" to "<green>표지판을 고쳤습니다.</green>",
        "decor-sign-copy-punch" to "<yellow>붙여 넣을 표지판을 치세요.</yellow> <gray>표지판이 아닌 블록을 치면 끝납니다.</gray>",
        "decor-sign-copy-given" to "<green>{대상}님에게 표지판 붙여넣기를 맡겼습니다.</green>",
        "decor-sign-copy-cancelled" to "<gray>표지판 복사를 끝냈습니다.</gray>",
        "decor-sign-copy-updated" to "<green>표지판에 붙여 넣었습니다.</green>",
        // --- 동적 표지판 ---
        "decor-dsign-usage" to "<red>/동적표지판 [생성 | 편집 | 삭제 | 목록]</red> <gray>— 표지판을 바라보고</gray>",
        "decor-dsign-how-to" to "<red>동적 표지판이 아닙니다.</red> <gray>만들려면 /동적표지판 생성</gray>",
        "decor-dsign-limit" to "<red>동적 표지판은 {개수}개까지입니다.</red>",
        "decor-dsign-created" to "<green>동적 표지판 #{개수} 을(를) 만들었습니다.</green>",
        "decor-dsign-removed" to "<gray>동적 표지판을 껐습니다.</gray>",
        "decor-dsign-none" to "<gray>동적 표지판이 없습니다.</gray>",
        "decor-dsign-line" to "<gray>#{개수}</gray> <white>{월드} {x} {y} {z}</white> <gray>— {값}</gray>",
        // --- 액자 ---
        "decor-frame-usage" to "<red>/액자 <투명|고정|무적|발광|전부|정보> [켜기|끄기] [주변 <반경> [강제]]</red>",
        "decor-frame-look" to "<red>아이템 액자를 바라보세요.</red>",
        "decor-frame-protected" to "<red>이곳의 액자는 만질 수 없습니다.</red>",
        "decor-frame-failed" to "<red>액자를 바꾸지 못했습니다.</red>",
        "decor-frame-done" to "<green>액자 {값}: {상태}</green>",
        "decor-frame-area" to "<green>주변 액자 {개수}개 — {값}: {상태}</green>",
        "decor-frame-area-skipped" to "<gray>건너뜀: 보호 구역 {보호}개 · 다른 플러그인의 액자 {남의것}개(강제 를 붙이면 바꿉니다)</gray>",
        "decor-frame-info" to "<gray>투명</gray> {투명} <gray>· 고정</gray> {고정} <gray>· 무적</gray> {무적} <gray>· 발광</gray> {발광}",
        "decor-frame-mode-invisible" to "투명",
        "decor-frame-mode-fixed" to "고정",
        "decor-frame-mode-invulnerable" to "무적",
        "decor-frame-mode-glow" to "발광",
        "decor-frame-mode-all" to "투명·고정·무적",
        "decor-frame-mode-info" to "정보",
    )

    @Volatile
    var settings: DecorSettings = DecorSettings()
        private set

    internal lateinit var probe: Probe
    internal lateinit var stands: ArmorStands
    internal lateinit var paintings: Paintings
    internal lateinit var dynamic: DynamicSigns
    internal lateinit var signs: SignEdit

    override fun load(settings: YamlConfiguration) {
        this.settings = DecorSettings.from(settings)
    }

    override fun enable() {
        probe = Probe()
        ess.listen(probe)
        stands = ArmorStands(ess, { settings }, probe).also { it.load() }
        paintings = Paintings(ess, { settings }, probe)
        dynamic = DynamicSigns(ess) { settings }.also { it.load() }
        signs = SignEdit(ess, { settings }, dynamic)
        ess.listen(paintings)
        ess.listen(signs)
        ess.listen(Listen())
        DecorCommands(ess, this).register()
        PlayerSettings.register(
            PlayerSettings.Setting(SignEdit.SETTING_SHIFT_EDIT, SETTINGS_OWNER, "웅크려 우클릭 표지판 편집", Material.OAK_SIGN,
                listOf("켜 두면 웅크리고 맨손으로 표지판을 우클릭해 다시 고칩니다."), PlayerSettings.Toggle(true), SIGN_EDIT),
        )
        ess.ticks.add("decor", 1) { now -> dynamic.tick(now) }
    }

    override fun disable() {
        if (::signs.isInitialized) signs.clear()
        if (::stands.isInitialized) stands.clear()
        if (::paintings.isInitialized) paintings.clear()
        PlayerSettings.unregisterAll(SETTINGS_OWNER)
    }

    /** 웅크려 우클릭 편집기 · 템플릿 거치대 놓기 · 나감. */
    private inner class Listen : Listener {

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        fun onStand(event: PlayerInteractAtEntityEvent) {
            if (stands.probing) return
            val stand = event.rightClicked as? ArmorStand ?: return
            val player = event.player
            if (event.hand != EquipmentSlot.HAND || !player.isSneaking || !settings.armorStand.shiftClick || !player.hasPermission(ARMORSTAND)) return
            // 다른 플러그인의 거치대(NPC·상점·가구)는 그 플러그인이 클릭을 다루게 둔다.
            if (stands.offLimits(player, stand)) return
            event.isCancelled = true
            stands.open(player, stand)
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onPlace(event: EntityPlaceEvent) {
            if (stands.probing) return
            val stand = event.entity as? ArmorStand ?: return
            val player = event.player ?: return
            val stack = player.inventory.getItem(event.hand)
            val name = stands.templateOf(stack) ?: return
            val template = stands.templates[name] ?: return
            // 놓는 사람의 권한으로 칸마다 거른다(자세·켜고 끄기·이름·무적·크기 — ArmorStands.apply) — 편집기로 못 만드는 거치대를 템플릿으로 만들지 못하게.
            stands.apply(stand, template, player)
            ess.messages.send(player, "decor-as-updated")
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onQuit(event: PlayerQuitEvent) {
            val id = event.player.uniqueId
            stands.quit(id)
            paintings.quit(id)
            signs.quit(id)
        }
    }

    // --- CMI 가져오기 --------------------------------------------------------------------------------

    /**
     * CMI 의 동적 표지판(`Saves/DynamicSigns.yml`)과 표지판·거치대 설정(config `Signs` · `Optimizations.SignEdit` · `Optimizations.ArmorStands`)을
     * 옮기고, 사람마다의 CMI 개인 옵션 `shiftSignEdit`(`users.Options` 2번째 칸)을 core 개인 설정 [SignEdit.SETTING_SHIFT_EDIT] 로 옮긴다.
     *
     * **읽기와 보고서 줄은 여기서(메인) 바로** — `CmiImportable` 계약대로. server 기능은 importCmi 들이 돌아오자마자 보고서 파일·요약을 만든다
     * (워커로 미루면 그 줄들이 보고서에 들어가지 않았다 — 2026-10-10 리뷰). 작은 YAML 몇 개라 관리자가 한 번 치는 명령에서 메인을 오래 막지 않는다.
     * 우리 설정 파일 쓰기만 `ess.io` 로. 같은 자리의 동적 표지판이 이미 있으면 건너뛴다.
     * 거치대 템플릿(`armorStands.yml`)은 형식을 jar 로 확인하지 못해 옮기지 않는다 — 있으면 `unreadable` 로 남긴다.
     */
    override fun importCmi(data: CmiData, report: ImportReport) {
        val folder = data.folder
        val ours = ess.io.file("settings", "$id.yml")
        val config = File(folder, "config.yml").takeIf { it.isFile }?.let { YamlConfiguration.loadConfiguration(it) }
        val y = YamlConfiguration.loadConfiguration(ours)
        val modules = File(folder, "Settings/Modules.yml").takeIf { it.isFile }?.let { YamlConfiguration.loadConfiguration(it) }
        if (config != null) CmiDecorImport.settings(config, y, report, modules)
        else report.skipped(CmiDecorImport.SETTINGS, "CMI config.yml 이 없습니다")
        val dsFile = File(folder, "Saves/DynamicSigns.yml")
        val dynamicOff = modules != null && modules.contains("dynamicSigns") && !modules.getBoolean("dynamicSigns", true)
        val existing = if (::dynamic.isInitialized) dynamic.signs.values.toList() else emptyList()
        val imported = when {
            !dsFile.isFile || dsFile.length() == 0L -> emptyList()
            dynamicOff -> emptyList<DynamicSigns.DynSign>().also {
                report.skipped(CmiDecorImport.DSIGN, "Saves/DynamicSigns.yml — CMI Modules.yml dynamicSigns=false 라 옮기지 않았습니다")
            }
            else -> CmiDecorImport.dynamicSigns(YamlConfiguration.loadConfiguration(dsFile), existing, report)
        }
        for (name in listOf("Saves/armorStands.yml", "armorStands.yml", "Saves/ArmorStands.yml")) {
            val f = File(folder, name)
            if (f.isFile && f.length() > 0) report.unreadable("갑옷 거치대 템플릿", "${f.name} — 형식을 확인하지 못해 옮기지 않았습니다(${f.length()}바이트). 편집기의 '템플릿으로 저장' 으로 다시 만드세요")
        }
        importShiftSignEdit(data, report)
        val text = y.saveToString()
        ess.io.asyncRun { AtomicFiles.write(ours, text) }
        load(y)
        if (::dynamic.isInitialized) for (d in imported) dynamic.adopt(d)
    }

    /** CMI `users.Options` 의 `shiftSignEdit`(2번째 칸, `1`/`0`) — 끈 사람만 core 개인 설정에 끈다(기본값 켬과 같은 것은 쓰지 않는다). */
    private fun importShiftSignEdit(data: CmiData, report: ImportReport) {
        for (user in data.users) {
            val raw = user.extra["Options"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            when (CmiDecorImport.shiftSignEdit(raw)) {
                null -> report.unreadable(CmiDecorImport.SHIFT_EDIT, "${user.name} — users.Options 의 shiftSignEdit 칸을 읽지 못했습니다(원문 ${raw.take(120)})")
                true -> Unit
                false -> {
                    report.read(CmiDecorImport.SHIFT_EDIT)
                    if (PlayerSettings.set(user.id, SignEdit.SETTING_SHIFT_EDIT, false)) report.imported(CmiDecorImport.SHIFT_EDIT)
                    else report.unreadable(CmiDecorImport.SHIFT_EDIT, "${user.name} — shiftSignEdit=끔 을 개인 설정 ${SignEdit.SETTING_SHIFT_EDIT} 에 넣지 못했습니다")
                }
            }
        }
    }

    // --- 검증기 ---------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun checks(): List<Check> = listOf(
        Check("표지판 금지 목록은 대소문자를 가리지 않는다") { _ ->
            val rule = DecorSettings.Sign()
            if (rule.blocked("[shop]") && rule.blocked(" [CHESTSHOP] ") && !rule.blocked("상점 아님") && !rule.blocked("")) null else "금지 목록 판정이 틀렸습니다"
        },
        Check("표지판 색: 색 권한은 클릭 사건을 못 넣는다") { _ ->
            val colored = DecorText.parse("&a초록 <click:run_command:'/op me'>눌러</click>", DecorText.Level.COLOR)
            when {
                colored.clickEvent() != null || colored.children().any { it.clickEvent() != null } -> "색 권한으로 클릭 사건이 들어갔습니다"
                !DecorText.plain(colored).contains("초록") -> "글이 사라졌습니다: ${DecorText.plain(colored)}"
                else -> null
            }
        },
        Check("동적 표지판: 넷 넘는 줄은 굴리고 긴 줄은 흐른다") { _ ->
            val w = SignFrames.window(listOf("a", "b", "c", "d", "e"), 1)
            val m = SignFrames.marquee("abcdefghij", 4, 2)
            if (w == listOf("b", "c", "d", "e") && m == "cdef") null else "장면 계산이 틀렸습니다: $w / $m"
        },
        Check("그림: 같은 크기 그림을 돈다") { _ ->
            val all = paintings.allArts()
            val one = all.firstOrNull { it.blockWidth == 1 && it.blockHeight == 1 } ?: return@Check "1×1 그림이 레지스트리에 없습니다"
            if (paintings.sameSize(one).size >= 2) null else "1×1 그림이 하나뿐입니다(${all.size}개 중)"
        },
        Check("갑옷 거치대: 웅크려 우클릭하면 편집기, 모양 복사가 왕복") { p ->
            val at = p.location.clone().add(p.location.direction.setY(0).normalize().multiply(1.5))
            var stand: ArmorStand? = null
            var other: ArmorStand? = null
            val sneaking = p.isSneaking
            try {
                stand = p.world.spawn(at, ArmorStand::class.java) { it.setGravity(false); it.isPersistent = false; it.setArms(true) }
                other = p.world.spawn(at, ArmorStand::class.java) { it.setGravity(false); it.isPersistent = false }
                val s = stand!!
                p.isSneaking = true
                val event = PlayerInteractAtEntityEvent(p, s, Vector(0.0, 1.0, 0.0), EquipmentSlot.HAND)
                org.bukkit.Bukkit.getPluginManager().callEvent(event)
                val opened = p.openInventory.topInventory.holder is ArmorStandMenu
                s.headPose = ArmorStands.rad(StandTemplate.Angles(30.0, -45.0, 10.0))
                s.isSmall = true
                stands.apply(other!!, stands.capture(s), p)
                val same = stands.capture(other!!).let { it.head == StandTemplate.Angles(30.0, -45.0, 10.0) && it.small }
                when {
                    !event.isCancelled -> "우클릭 사건이 취소되지 않았습니다(장비가 바뀔 수 있음)"
                    !opened && p.hasPermission(ARMORSTAND) -> "편집기가 열리지 않았습니다"
                    !same -> "복사한 모양이 같지 않습니다: ${stands.capture(other!!)}"
                    else -> null
                }
            } finally {
                p.isSneaking = sneaking
                if (p.openInventory.topInventory.holder is ArmorStandMenu) p.closeInventory()
                stand?.let { stands.closed(p, it); it.remove() }
                other?.remove()
            }
        },
        Check("표지판 /표지판편집 줄 나누기") { _ ->
            val parts = DecorText.signLines(3, "가\\n나\\n다")
            if (parts == listOf(2 to "가", 3 to "나")) null else "줄 나누기가 틀렸습니다: $parts"
        },
        Check("갑옷 거치대: 장비 잠금 풀기는 .any 만") { _ ->
            when {
                ArmorStands.lockToggleAllowed(currentlyLocked = true, canToggle = true, any = false) -> "켜고 끄기 권한만으로 잠금이 풀립니다"
                !ArmorStands.lockToggleAllowed(currentlyLocked = false, canToggle = true, any = false) -> "켜고 끄기 권한으로 잠그지 못합니다"
                !ArmorStands.lockToggleAllowed(currentlyLocked = true, canToggle = false, any = true) -> ".any 로 풀지 못합니다"
                else -> null
            }
        },
        Check("표지판: 바닐라 화면 저장이 남의 글자 <click> 을 살리지 않는다") { p ->
            withTempSign(p) { block ->
                val literal = Component.text("<click:run_command:'/say 검증'>a</click>")
                (block.state as Sign).also { it.getSide(Side.FRONT).line(0, literal); it.update(true, false) }
                val event = SignChangeEvent(block, p, mutableListOf<Component>(literal, Component.text("&a색"), Component.text("<click:run_command:'/say 검증'>b</click>"), Component.empty()), Side.FRONT)
                Bukkit.getPluginManager().callEvent(event)
                val lines = (0 until 4).map { event.line(it) ?: Component.empty() }
                when {
                    DecorText.hasClick(lines[0]) -> "고치지 않은 줄의 글자 <click> 이 살아났습니다"
                    DecorText.hasClick(lines[2]) -> "바닐라 화면으로 쓴 <click> 이 클릭 사건이 됐습니다(색까지만이어야 합니다)"
                    DecorFeature.textLevel(p) != DecorText.Level.PLAIN && DecorText.plain(lines[1]) != "색" -> "&a 색이 읽히지 않았습니다: ${DecorText.plain(lines[1])}"
                    else -> null
                }
            }
        },
        Check("표지판 복사: 붙여넣기는 .sign.any 없이도 된다") { p ->
            withTempSign(p) { block ->
                signs.ignoreBypass = true
                try {
                    val copied = SignEdit.Copied(listOf(Component.text("복사"), Component.empty(), Component.empty(), Component.empty()), List(4) { Component.empty() },
                        null, null, false, false, null)
                    signs.setCopied(p.uniqueId, copied)
                    kr.inmc.core.input.Clicks.forget(p.uniqueId)
                    val event = PlayerInteractEvent(p, Action.LEFT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND)
                    Bukkit.getPluginManager().callEvent(event)
                    val text = DecorText.plain((block.state as Sign).getSide(Side.FRONT).line(0))
                    if (text == "복사") null else "붙여 넣지 못했습니다(첫 줄 '$text')"
                } finally {
                    signs.ignoreBypass = false
                    signs.setCopied(p.uniqueId, null)
                }
            }
        },
        Check("동적 표지판: 갱신이 블록에 쓰고, 고친 글은 다음 틱에 다시 그린다") { p ->
            withTempSign(p) { block ->
                val temp = DynamicSigns.DynSign(-1, block.world.name, block.x, block.y, block.z, listOf("검증", "<gold>둘"), 5, 16, false)
                try {
                    dynamic.putTemporary(temp)
                    dynamic.tick(System.currentTimeMillis())
                    val drawn = DecorText.plain((block.state as Sign).getSide(Side.FRONT).line(0))
                    (block.state as Sign).also { it.getSide(Side.FRONT).line(0, Component.text("낙서")); it.update(true, false) }
                    Bukkit.getPluginManager().callEvent(SignChangeEvent(block, p, mutableListOf<Component>(Component.text("낙서"), Component.empty(), Component.empty(), Component.empty()), Side.FRONT))
                    dynamic.tick(System.currentTimeMillis())
                    val redrawn = DecorText.plain((block.state as Sign).getSide(Side.FRONT).line(0))
                    when {
                        drawn != "검증" -> "동적 표지판이 그려지지 않았습니다('$drawn')"
                        redrawn != "검증" -> "고친 동적 표지판이 다시 그려지지 않았습니다('$redrawn')"
                        else -> null
                    }
                } finally {
                    dynamic.removeTemporary(temp.id)
                }
            }
        },
        Check("액자: 발광 바꾸기가 아이템을 옮긴다(복사·잃음 없이)") { p ->
            val stone = p.location.block.getRelative(0, 3, 0)
            val air = stone.getRelative(BlockFace.UP)
            val stoneState = stone.state
            val airState = air.state
            var frame: ItemFrame? = null
            try {
                stone.setType(Material.STONE, false)
                air.setType(Material.AIR, false)
                val first = p.world.spawn(air.location, ItemFrame::class.java) { it.setFacingDirection(BlockFace.UP, true); it.setItem(ItemStack(Material.DIAMOND, 1), false) }
                frame = first
                val glow = ItemFrames.swapGlow(first, true)
                frame = glow ?: frame
                val back = glow?.let { ItemFrames.swapGlow(it, false) }
                frame = back ?: frame
                val drops = air.world.getNearbyEntities(air.location.add(0.5, 0.5, 0.5), 2.0, 2.0, 2.0) { it is org.bukkit.entity.Item }.size
                when {
                    glow !is GlowItemFrame -> "발광 액자로 바뀌지 않았습니다"
                    back == null || back is GlowItemFrame -> "보통 액자로 돌아오지 않았습니다"
                    back.item.type != Material.DIAMOND || back.item.amount != 1 -> "아이템이 옮겨지지 않았습니다(${back.item})"
                    first.isValid || glow.isValid -> "옛 액자가 남았습니다(겹친 액자)"
                    drops > 0 -> "바꾸는 동안 아이템이 떨어졌습니다($drops)"
                    else -> null
                }
            } finally {
                frame?.let { it.setItem(null, false); it.remove() }
                airState.update(true, false)
                stoneState.update(true, false)
            }
        },
    ) + reviewChecks()

    /** 2026-10-10 리뷰 뒤 더한 검사들 — 명령 경로의 보호 확인 · 그림 한 번에 한 칸 · 바닐라 편집 화면 규칙 · 동적 표지판 클릭 · 남의 거치대 · 템플릿 노드. */
    private fun reviewChecks(): List<Check> = listOf(
        Check("갑옷 거치대: 명령 경로의 보호 확인이 보호 없는 곳에서 허락한다") { p ->
            var stand: ArmorStand? = null
            try {
                stand = p.world.spawn(p.location, ArmorStand::class.java) { it.setGravity(false); it.isPersistent = false }
                // .any 를 건너뛰고 확인 사건을 직접 — 남의 보호 구역에 서 있으면 실패할 수 있다(그때는 그 자리 탓).
                if (probe.mayTouchStand(p, stand!!)) null else "보호 확인이 막았습니다 — 보호 구역 밖에서 다시 해 보세요(늘 막히면 확인 사건을 두 번 받는 것)"
            } finally {
                stand?.remove()
            }
        },
        Check("갑옷 거치대: 마커·다른 플러그인 PDC 거치대는 남의 것") { p ->
            var marker: ArmorStand? = null
            var plain: ArmorStand? = null
            val otherKey = NamespacedKey("ess-verify-other", "x")
            try {
                marker = p.world.spawn(p.location, ArmorStand::class.java) { it.setGravity(false); it.isPersistent = false; it.isMarker = true }
                val s = p.world.spawn(p.location, ArmorStand::class.java) { it.setGravity(false); it.isPersistent = false }
                plain = s
                val plainForeign = ArmorStands.foreign(s)
                s.persistentDataContainer.set(otherKey, PersistentDataType.BYTE, 1)
                val foreignPdc = ArmorStands.foreign(s)
                s.persistentDataContainer.remove(otherKey)
                when {
                    !ArmorStands.foreign(marker!!) -> "마커 거치대를 편집할 수 있습니다"
                    !foreignPdc -> "다른 플러그인 PDC 가 붙은 거치대를 편집할 수 있습니다"
                    plainForeign -> "보통 거치대를 남의 것으로 봅니다"
                    else -> null
                }
            } finally {
                marker?.remove()
                plain?.remove()
            }
        },
        Check("갑옷 거치대: 템플릿마다의 권한이 선언된 노드와 겹치지 않는다") { _ ->
            val declared = permissions.map { it.node }.toSet()
            val clash = listOf("save", "give", "free", "use").map { ArmorStands.templateNode(it) }.filter { it in declared }
            if (clash.isEmpty()) null else "겹침: $clash"
        },
        Check("그림: 웅크려 우클릭 한 번(At + 보통 사건)에 한 칸만 넘어간다") { p ->
            if (!p.hasPermission(PAINTING) || !settings.painting.enabled) return@Check null // 꺼져 있으면 볼 것이 없다
            val wall = p.location.block.getRelative(0, 3, 0)
            val front = wall.getRelative(BlockFace.SOUTH)
            val wallState = wall.state
            val frontState = front.state
            val sneaking = p.isSneaking
            var painting: Painting? = null
            try {
                wall.setType(Material.STONE, false)
                front.setType(Material.AIR, false)
                val art = paintings.allArts().firstOrNull { it.blockWidth == 1 && it.blockHeight == 1 } ?: return@Check "1×1 그림이 레지스트리에 없습니다"
                val same = paintings.sameSize(art)
                val pt = runCatching {
                    p.world.spawn(front.location, Painting::class.java) { it.setFacingDirection(BlockFace.SOUTH, true); it.setArt(art, true) }
                }.getOrElse { return@Check "임시 그림을 걸지 못했습니다(${it.javaClass.simpleName})" }
                painting = pt
                val before = same.indexOf(pt.art)
                p.isSneaking = true
                Bukkit.getPluginManager().callEvent(PlayerInteractAtEntityEvent(p, pt, Vector(0.5, 0.5, 0.0), EquipmentSlot.HAND))
                Bukkit.getPluginManager().callEvent(PlayerInteractEntityEvent(p, pt, EquipmentSlot.HAND))
                val after = same.indexOf(pt.art)
                when {
                    after == before -> "그림이 바뀌지 않았습니다(보호 구역 안이면 그 탓 — 아니면 두 칸 돌아 제자리)"
                    after != Math.floorMod(before + 1, same.size) -> "한 번 클릭에 ${Math.floorMod(after - before, same.size)}칸 넘어갔습니다"
                    else -> null
                }
            } finally {
                p.isSneaking = sneaking
                paintings.quit(p.uniqueId)
                painting?.remove()
                frontState.update(true, false)
                wallState.update(true, false)
            }
        },
        Check("표지판: 바닐라 편집 화면(우클릭)도 .sign.edit 권한·금지 목록·웅크림·개인 설정을 따른다") { p ->
            val rule = settings.sign
            if (!rule.enabled) return@Check null
            withTempSign(p) { block ->
                val sneaking = p.isSneaking
                signs.ignoreBypass = true
                var attachment: org.bukkit.permissions.PermissionAttachment? = null
                try {
                    // 권한 없는 사람 — 웅크렸고 개인 설정이 켜 있어도 막아야 한다(.sign.any 는 ignoreBypass 가 뺀다)
                    (block.state as Sign).also { it.getSide(Side.FRONT).line(0, Component.text("검증")); it.update(true, false) }
                    attachment = p.addAttachment(ess.plugin, SIGN_EDIT, false)
                    p.recalculatePermissions()
                    p.isSneaking = true
                    val unpermitted = PlayerOpenSignEvent(p, block.state as Sign, Side.FRONT, PlayerOpenSignEvent.Cause.INTERACT)
                    Bukkit.getPluginManager().callEvent(unpermitted)
                    p.removeAttachment(attachment)
                    attachment = null
                    p.recalculatePermissions()
                    if (!p.hasPermission(SIGN_EDIT)) return@withTempSign null // 검증하는 사람이 원래 권한이 없다 — 아래는 뜻이 없다
                    p.isSneaking = false
                    val first = rule.blacklist.firstOrNull() ?: "[Shop]"
                    (block.state as Sign).also { it.getSide(Side.FRONT).line(0, Component.text(first)); it.update(true, false) }
                    val blocked = PlayerOpenSignEvent(p, block.state as Sign, Side.FRONT, PlayerOpenSignEvent.Cause.INTERACT)
                    Bukkit.getPluginManager().callEvent(blocked)
                    (block.state as Sign).also { it.getSide(Side.FRONT).line(0, Component.text("검증")); it.update(true, false) }
                    val plain = PlayerOpenSignEvent(p, block.state as Sign, Side.FRONT, PlayerOpenSignEvent.Cause.INTERACT)
                    Bukkit.getPluginManager().callEvent(plain)
                    val ours = PlayerOpenSignEvent(p, block.state as Sign, Side.FRONT, PlayerOpenSignEvent.Cause.PLUGIN)
                    Bukkit.getPluginManager().callEvent(ours)
                    val toggle = PlayerSettings.enabled(p, SignEdit.SETTING_SHIFT_EDIT, true)
                    val plainAllowed = SignEdit.vanillaOpenAllowed(permitted = true, toggle, rule.requireSneaking, sneaking = false, blacklisted = false, dynamicLocked = false)
                    when {
                        !unpermitted.isCancelled -> ".sign.edit 권한이 없는데 바닐라 편집 화면이 열립니다"
                        rule.blacklist.isNotEmpty() && !blocked.isCancelled -> "금지 목록 표지판인데 바닐라 편집 화면이 열립니다"
                        plain.isCancelled == plainAllowed -> "보통 표지판의 바닐라 화면 판정이 설정과 다릅니다(웅크림 필요 ${rule.requireSneaking} · 개인 설정 $toggle)"
                        ours.isCancelled -> "우리가 여는 화면(PLUGIN)까지 막았습니다"
                        else -> null
                    }
                } finally {
                    attachment?.let { p.removeAttachment(it); p.recalculatePermissions() }
                    signs.ignoreBypass = false
                    p.isSneaking = sneaking
                }
            }
        },
        Check("동적 표지판: 플레이스홀더 값이 넣은 클릭 사건은 벗긴다") { _ ->
            val injected = MiniMessage.miniMessage().deserialize("<gold>순위 <click:run_command:'/op me'>이름</click>")
            val stripped = DecorText.withoutInjectedClicks("<gold>순위 %some_name%", injected)
            val kept = DecorText.withoutInjectedClicks("<click:run_command:'/spawn'>스폰</click>", injected)
            when {
                DecorText.hasClick(stripped) -> "플레이스홀더가 넣은 클릭이 남았습니다"
                DecorText.plain(stripped) != DecorText.plain(injected) -> "글이 바뀌었습니다: ${DecorText.plain(stripped)}"
                !DecorText.hasClick(kept) -> "관리자가 직접 쓴 클릭까지 벗겼습니다"
                else -> null
            }
        },
    )

    /** 검증기 — 머리 위 두 칸에 임시 표지판을 세우고 [body] 뒤 원래 블록으로 되돌린다. */
    private fun withTempSign(p: Player, body: (Block) -> String?): String? {
        val block = p.location.block.getRelative(0, 2, 0)
        val original = block.state
        return try {
            block.setType(Material.OAK_SIGN, false)
            if (block.state !is Sign) return "임시 표지판을 세우지 못했습니다"
            body(block)
        } finally {
            original.update(true, false)
        }
    }

    companion object {
        const val SETTINGS_OWNER = "inmc-essentials/decor"

        const val ARMORSTAND = "inmcessentials.decor.armorstand"
        const val ARMORSTAND_POSE = "inmcessentials.decor.armorstand.pose"
        const val ARMORSTAND_MOVE = "inmcessentials.decor.armorstand.move"
        const val ARMORSTAND_MOVE_UNLIMITED = "inmcessentials.decor.armorstand.move.unlimited"
        const val ARMORSTAND_SCALE = "inmcessentials.decor.armorstand.scale"
        const val ARMORSTAND_SCALE_UNLIMITED = "inmcessentials.decor.armorstand.scale.unlimited"
        const val ARMORSTAND_TOGGLE = "inmcessentials.decor.armorstand.toggle"
        const val ARMORSTAND_INVULNERABLE = "inmcessentials.decor.armorstand.invulnerable"
        const val ARMORSTAND_NAME = "inmcessentials.decor.armorstand.name"
        const val ARMORSTAND_EQUIP = "inmcessentials.decor.armorstand.equip"
        const val ARMORSTAND_HANDS = "inmcessentials.decor.armorstand.hands"
        const val ARMORSTAND_OFFHAND = "inmcessentials.decor.armorstand.offhand"
        const val ARMORSTAND_COPY = "inmcessentials.decor.armorstand.copy"
        const val ARMORSTAND_TEMPLATE_SAVE = "inmcessentials.decor.armorstand.template.save"
        const val ARMORSTAND_TEMPLATE_GIVE = "inmcessentials.decor.armorstand.template.give"
        const val ARMORSTAND_TEMPLATE_FREE = "inmcessentials.decor.armorstand.template.free"
        /**
         * 템플릿마다(설정 template-permission) `…armorstand.template.use.<이름>` 의 앞부분 — 이름으로 짓는 노드라 선언하지 않는다(선언 안 된 노드 = op 기본,
         * 지뢰 11 — 여기서는 그 뜻 그대로). 선언된 `.template.*` 와 다른 가지(`use.`)라 이름이 그 노드들과 겹치지 않는다([ArmorStands.templateNode]).
         */
        val ARMORSTAND_TEMPLATE_USE_PREFIX = ARMORSTAND_TEMPLATE_SAVE.removeSuffix("save") + "use."
        const val ARMORSTAND_ANY = "inmcessentials.decor.armorstand.any"
        const val PAINTING = "inmcessentials.decor.painting"
        const val ITEMFRAME = "inmcessentials.decor.itemframe"
        const val ITEMFRAME_AREA = "inmcessentials.decor.itemframe.area"
        const val ITEMFRAME_ANY = "inmcessentials.decor.itemframe.any"
        const val SIGN_EDIT = "inmcessentials.decor.sign.edit"
        const val SIGN_EDIT_WAXED = "inmcessentials.decor.sign.edit.waxed"
        const val SIGN_COMMAND = "inmcessentials.decor.sign.command"
        const val SIGN_COPY = "inmcessentials.decor.sign.copy"
        const val SIGN_COPY_OTHERS = "inmcessentials.decor.sign.copy.others"
        const val SIGN_BLACKLIST_BYPASS = "inmcessentials.decor.sign.blacklist.bypass"
        const val SIGN_ANY = "inmcessentials.decor.sign.any"
        const val COLOR = "inmcessentials.decor.color"
        const val FORMAT = "inmcessentials.decor.format"
        const val DSIGN = "inmcessentials.decor.dsign"

        fun textLevel(player: Player): DecorText.Level = when {
            player.hasPermission(FORMAT) -> DecorText.Level.FORMAT
            player.hasPermission(COLOR) -> DecorText.Level.COLOR
            else -> DecorText.Level.PLAIN
        }
    }
}

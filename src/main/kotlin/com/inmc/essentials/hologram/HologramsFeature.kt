package com.inmc.essentials.hologram

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.util.Ph
import com.inmc.essentials.verify.Check
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.permissions.PermissionDefault
import org.bukkit.persistence.PersistentDataType

/**
 * 홀로그램(2026-10-10) — 카탈로그 web:wiki-holograms(CMI `/cmi hologram` · `/cmi hologrampages` · config `Holograms` · `Saves/Holograms.yml` ·
 * 개인 옵션 visibleHolograms). 여러 줄 글(MiniMessage·PAPI)·아이콘 줄·쪽(자동·누르기)·줄/쪽마다 누르기 명령·권한/조건·사람마다 글.
 * 저장은 `holograms.yml`(원자적), 설정은 `settings/holograms.yml`, 운영 문서 `docs/holograms.md`. DB 는 쓰지 않는다.
 * 엔티티는 저장하지 않는다 — [HologramEngine].
 */
class HologramsFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "holograms"

    override val hasSettings = true

    override val permissions = listOf(
        Perm(ADMIN, PermissionDefault.OP, "/홀로그램 — 만들기·고치기·지우기·편집 화면(콘솔 누르기 명령을 더하고 고치는 것은 .console 이 더 있어야)"),
        Perm(CONSOLE, PermissionDefault.OP, "홀로그램 누르기 동작에 콘솔 명령 더하기·고치기 — 콘솔 권한과 같다(빌더에게 주지 말 것)"),
        Perm(TOGGLE, PermissionDefault.TRUE, "/홀로그램 보기 — 내 화면의 홀로그램 켜고 끄기"),
        Perm(PAGES, PermissionDefault.TRUE, "/홀로그램 넘기기 <이름> [다음|이전|번호] — 내 쪽 넘기기"),
        Perm(PAGES_OTHERS, PermissionDefault.OP, "/홀로그램 넘기기 <이름> <플레이어> … — 남의 쪽 넘기기"),
        Perm(CLICK, PermissionDefault.TRUE, "홀로그램 누르기(명령·쪽 넘기기)"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: HologramSettings = HologramSettings()
        private set

    internal lateinit var store: HologramStore
    internal lateinit var engine: HologramEngine

    private var settingRegistered = false

    override fun load(settings: YamlConfiguration) {
        this.settings = HologramSettings.from(settings)
    }

    override fun enable() {
        store = HologramStore(ess)
        store.loadNow()
        engine = HologramEngine(ess, this, store)
        store.onChange = { engine.sync() }
        ess.listen(HologramListener(engine))
        HologramCommands(ess, this, engine).register()
        PlayerSettings.register(
            PlayerSettings.Setting(
                SETTING_VISIBLE, SETTINGS_OWNER, "홀로그램 보기", Material.NAME_TAG,
                listOf("끄면 서버의 홀로그램이 내 화면에 안 보입니다.", "/홀로그램 보기 와 같습니다."), PlayerSettings.Toggle(true),
            ),
        )
        settingRegistered = true
        PlayerSettings.listen(SETTINGS_OWNER) { _, key -> if (key == SETTING_VISIBLE && ::engine.isInitialized) engine.refreshAll() }
        ess.ticks.add("holograms", 1) { _ -> engine.tick() }
    }

    override fun ready() {
        val removed = engine.sweepAll()
        if (removed > 0) ess.logger.info("남아 있던 홀로그램 엔티티 ${removed}개를 지웠습니다")
    }

    override fun disable() {
        if (::store.isInitialized) store.onChange = null
        if (::engine.isInitialized) engine.shutdown()
        if (settingRegistered) {
            PlayerSettings.unregisterAll(SETTINGS_OWNER)
            PlayerSettings.unlisten(SETTINGS_OWNER)
        }
        settingRegistered = false
    }

    /** 고칠 수 있나 — holograms.yml 이 깨져 잠겼으면 알리고 false(저장하면 손으로 고치던 파일을 덮는다). */
    fun editable(sender: CommandSender): Boolean {
        val why = store.locked ?: return true
        ess.messages.send(sender, "holograms-locked", Ph.of().value(why))
        return false
    }

    /**
     * 콘솔 명령을 더하거나 바꿀 수 있나 — [CONSOLE] 이 없으면 `player:` 명령·쪽 넘기기만. [before] 에 이미 있던 콘솔 명령은 그대로 두거나 지울 수 있다.
     * 안 되면 알리고 false.
     */
    fun mayAddConsole(sender: CommandSender, before: List<String>, after: List<String>): Boolean {
        if (sender.hasPermission(CONSOLE)) return true
        val had = before.filter(HoloRules::isConsoleAction).map { it.trim() }.toMutableList()
        val added = after.filter(HoloRules::isConsoleAction).map { it.trim() }.filter { !had.remove(it) }
        if (added.isEmpty()) return true
        ess.messages.send(sender, "holograms-console-denied", Ph.of().value(added.joinToString(" | ")))
        return false
    }

    /** 새 홀로그램·옮기기의 자리 — 눈높이 조금 위, 블록 가운데가 아니라 선 자리 그대로. */
    fun spawnPoint(player: Player): Location = player.eyeLocation.clone().add(0.0, settings.createHeight, 0.0).apply {
        yaw = 0f
        pitch = 0f
    }

    // --- CMI 가져오기 ---------------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        if (!::store.isInitialized) return report.skipped(KIND, "홀로그램 기능이 켜지지 않았습니다")
        val yaml = data.holograms ?: return report.note("CMI Saves/Holograms.yml 이 비어 있어 가져올 홀로그램이 없습니다")
        store.locked?.let { return report.skipped(KIND, "holograms.yml 이 깨져 있어(YAML 오류 — $it) 가져오지 않습니다. 파일을 고치고 /홀로그램 다시읽기 뒤 다시 가져오세요") }
        val read = HologramCodec.fromCmi(yaml)
        for (line in read.unreadable) report.unreadable(KIND, line)
        for (line in read.notes) report.note(line)
        val added = ArrayList<Hologram>()
        for (h in read.holograms) {
            report.read(KIND)
            if (store.get(h.name) != null || added.any { it.key == h.key }) {
                report.skipped(KIND, "홀로그램 '${h.name}' 이(가) 이미 있습니다")
                continue
            }
            store.rejectedName(h.name)?.let {
                report.skipped(KIND, "홀로그램 '${h.name}' — holograms.yml 에 같은 이름의 못 읽은 칸 '$it' 이(가) 있습니다(덮지 않습니다)")
                continue
            }
            val cmiCommands = h.pages.flatMap { p -> p.actions.map.values.flatten() + p.lines.flatMap { it.actions.map.values.flatten() } }
                .filter { c ->
                    HoloRules.parseCommand(c)?.second?.lowercase()?.startsWith("cmi ") == true || c.lowercase().let { it.contains("!cmi ") || it.contains("! cmi ") || it.contains("! /cmi ") }
                }
            if (cmiCommands.isNotEmpty()) report.note("홀로그램 '${h.name}' 의 클릭 명령에 CMI 명령이 있습니다(${cmiCommands.size}개) — CMI 를 빼면 실패합니다. 편집 화면에서 고치세요")
            // 아이콘 줄이 지금 서버에서 아이템으로 풀리는가 — CMI 만의 모양(`head:<base64>` · `%itemInHand%` · CMI 아이템 별칭)은 못 푼다.
            // 줄은 그대로 넣고(편집 화면에서 손에 든 것으로 바꿀 수 있게) 못 읽음으로 알린다 — 조용히 방벽이 되지 않게.
            for ((p, page) in h.pages.withIndex()) for ((l, line) in page.lines.withIndex()) {
                if (!line.isIcon || line.snapshot != null) continue
                val built = runCatching { engine.buildIcon(line) }.getOrNull()
                if (built == null) {
                    report.unreadable(KIND, "홀로그램 '${h.name}' ${p + 1}:${l + 1} 줄의 아이콘 '${line.icon}' 을(를) 아이템으로 풀지 못했습니다 — 방벽으로 보입니다. /홀로그램 편집 ${h.name} 에서 손에 든 아이템으로 바꾸세요")
                }
            }
            if (Bukkit.getWorld(h.world) == null) report.note("홀로그램 '${h.name}' 의 월드 '${h.world}' 가 지금 없습니다 — 열리면 보입니다")
            added += h
            report.imported(KIND)
        }
        store.putAll(added)
    }

    // --- 검증기 ----------------------------------------------------------------------------------------

    /** 검사자 앞 3블록, 눈높이 — 메모리에만 넣는다. */
    private fun probe(p: Player, pages: List<HoloPage>, change: (Hologram) -> Hologram = { it }): Hologram {
        val dir = p.location.direction.setY(0).let { if (it.lengthSquared() < 1e-6) it.setX(1) else it.normalize() }
        val at = p.eyeLocation.clone().add(dir.multiply(3))
        return change(Hologram(PROBE, p.world.name, at.x, at.y + 0.5, at.z, pages = pages, clickPages = false))
    }

    /** 검증기 — 설정을 메모리에서만 잠깐 바꾼다(finally 에서 되돌린다). */
    private fun <T> withSettings(change: (HologramSettings) -> HologramSettings, block: () -> T): T {
        val before = settings
        try {
            settings = change(before)
            return block()
        } finally {
            settings = before
        }
    }

    private fun <T> withProbe(h: Hologram, block: () -> T): T {
        val before = store.data
        try {
            engine.capture = ArrayList()
            store.swap(before.copy(holograms = before.holograms.filter { it.key != h.key } + h))
            engine.refresh(store.get(h.name)!!)
            return block()
        } finally {
            engine.capture = null
            store.swap(before) // sync() 가 검증용 묶음을 거둔다
        }
    }

    override fun checks(): List<Check> = listOf(
        Check("holograms.yml 을 다 읽었다") { _ ->
            val rejected = store.data.rejected
            when {
                store.locked != null -> "holograms.yml 이 YAML 로 깨져 있습니다(저장 잠김 — 홀로그램이 안 서거나 옛것입니다): ${store.locked}"
                rejected.isNotEmpty() -> "읽지 못한 홀로그램 ${rejected.size} 칸(파일에는 남아 있습니다): " +
                    rejected.keys.joinToString("; ") { "$it — ${store.data.reasons[it] ?: "?"}" }
                else -> null
            }
        },
        Check("보는 사람에게만 세우고 보인다 · 조건이 틀리면 안 보인다(메모리만)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null // 검사자가 홀로그램을 꺼 두었다
            val shown = probe(p, listOf(HoloPage(listOf(HoloLine("<gold>검증</gold>"), HoloLine("둘째 줄")))))
            val first = withProbe(shown) {
                val entities = engine.entitiesFor(p, PROBE)
                when {
                    entities.size != 2 -> "엔티티 ${entities.size}개(기대 2 — 줄마다 글 하나, 누를 것 없음)"
                    entities.any { it.isPersistent } -> "저장되는 엔티티가 섞였습니다(isPersistent 는 false 여야 합니다)"
                    entities.any { it.persistentDataContainer.get(engine.tag, PersistentDataType.STRING) != shown.key } -> "PDC 표시(inmcess:holo)가 없습니다"
                    entities.any { !p.canSee(it) } -> "검사자에게 안 보입니다(showEntity)"
                    entities.any { it.isVisibleByDefault } -> "기본으로 모두에게 보입니다(isVisibleByDefault 는 false 여야 합니다)"
                    else -> null
                }
            }
            if (first != null) return@Check first
            val hidden = probe(p, listOf(HoloPage(listOf(HoloLine("안 보여야 함"))))) { it.copy(condition = "1 == 2") }
            withProbe(hidden) {
                if (engine.liveCount(PROBE) != 0) "조건이 틀렸는데 엔티티를 세웠습니다" else null
            }
        },
        Check("사람마다의 글은 그 사람의 묶음으로 그린다(메모리만)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            val h = probe(p, listOf(HoloPage(listOf(HoloLine("안녕 {player}")))))
            withProbe(h) {
                val text = engine.entitiesFor(p, PROBE).filterIsInstance<TextDisplay>().firstOrNull()?.let { Text.plain(it.text()) }
                when {
                    engine.personalFor(p, PROBE) != true -> "{player} 가 있는데 공용 묶음입니다"
                    text == null -> "글 엔티티가 없습니다"
                    !text.contains(p.name) -> "글에 검사자 이름이 없습니다: $text"
                    else -> null
                }
            }
        },
        Check("누르기 — 진짜 상호작용·공격 사건으로 줄의 명령(명령은 실행하지 않고 모은다)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            if (!p.hasPermission(CLICK)) return@Check null
            val line = HoloLine("누르기", actions = HoloActions(mapOf(HoloClick.RIGHT to listOf("player:ess-verify-holo {player}"), HoloClick.LEFT to listOf("ess-verify-left"))))
            val h = probe(p, listOf(HoloPage(listOf(line))))
            withProbe(h) {
                val interaction = engine.entitiesFor(p, PROBE).filterIsInstance<Interaction>().firstOrNull()
                    ?: return@withProbe "누를 동작이 있는데 Interaction 이 없습니다"
                // 웅크린 채여도 Shift 칸이 비어 있으니 같은 쪽 클릭으로 물러난다 — 기대값은 같다.
                engine.resetClickCooldown(p)
                val right = PlayerInteractEntityEvent(p, interaction, EquipmentSlot.HAND)
                Bukkit.getPluginManager().callEvent(right)
                val afterRight = engine.capture.orEmpty().toList()
                engine.capture?.clear()
                engine.resetClickCooldown(p) // 같은 틱의 두 번째 누르기 — 클릭 간격 막기를 비운다
                Bukkit.getPluginManager().callEvent(PrePlayerAttackEntityEvent(p, interaction, true))
                val afterLeft = engine.capture.orEmpty().toList()
                engine.resetClickCooldown(p)
                when {
                    !right.isCancelled -> "상호작용 사건을 취소하지 않았습니다(HologramListener.onInteract)"
                    afterRight != listOf("player:ess-verify-holo ${p.name}") -> "우클릭 명령이 다릅니다: $afterRight"
                    afterLeft != listOf("console:ess-verify-left") -> "좌클릭 명령이 다릅니다: $afterLeft"
                    else -> null
                }
            }
        },
        Check("누르면 쪽이 넘어가고(빈 쪽은 건너뜀) [prevPage] 로 돌아온다 — 진짜 상호작용 사건(메모리만)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            if (!p.hasPermission(CLICK)) return@Check null
            val pages = listOf(HoloPage(listOf(HoloLine("1쪽"))), HoloPage(), HoloPage(listOf(HoloLine("3쪽"))))
            val h = probe(p, pages) { it.copy(clickPages = true) }
            withProbe(h) {
                val first = engine.entitiesFor(p, PROBE)
                val interaction = first.filterIsInstance<Interaction>().firstOrNull()
                    ?: return@withProbe "쪽이 여럿인데 누를 Interaction 이 없습니다(누르면 넘기기)"
                engine.resetClickCooldown(p)
                val right = PlayerInteractEntityEvent(p, interaction, EquipmentSlot.HAND)
                Bukkit.getPluginManager().callEvent(right)
                val after = engine.entitiesFor(p, PROBE)
                val page = engine.pageOf(p, store.get(PROBE)!!)
                engine.resetClickCooldown(p)
                engine.run(p, store.get(PROBE)!!, listOf("[prevPage]"))
                val back = engine.pageOf(p, store.get(PROBE)!!)
                when {
                    !right.isCancelled -> "상호작용 사건을 취소하지 않았습니다"
                    page != 2 -> "우클릭 뒤 쪽이 ${page + 1}쪽입니다(기대 3쪽 — 빈 2쪽은 건너뛴다)"
                    after.isEmpty() || after.any { it in first } -> "쪽이 바뀌었는데 보이는 엔티티 묶음이 그대로입니다"
                    first.any { it.isValid && p.canSee(it) } -> "앞 쪽의 엔티티가 아직 보입니다"
                    back != 0 -> "[prevPage] 뒤 쪽이 ${back + 1}쪽입니다(기대 1쪽)"
                    else -> null
                }
            }
        },
        Check("보기를 끄면 그 사람에게서 엔티티를 거둔다 — 개인 보기 끔(메모리만)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            val h = probe(p, listOf(HoloPage(listOf(HoloLine("보기 끔 검사")))))
            withProbe(h) {
                val before = engine.entitiesFor(p, PROBE)
                if (before.isEmpty()) return@withProbe "보기를 끄기 전에 엔티티가 없습니다"
                try {
                    engine.forceHidden += p.uniqueId // PlayerSettings 를 DB 에 쓰지 않고 visibleTo 의 같은 길을 탄다
                    engine.refreshAll()
                    when {
                        engine.entitiesFor(p, PROBE).isNotEmpty() -> "보기를 껐는데 엔티티가 남아 있습니다"
                        before.any { it.isValid && p.canSee(it) } -> "보기를 껐는데 앞의 엔티티가 보입니다(hideEntity)"
                        else -> null
                    }
                } finally {
                    engine.forceHidden -= p.uniqueId
                    engine.refreshAll()
                }
            }
        },
        Check("상한을 넘은 사람의 공용 글에 {player} 가 글자 그대로 보이지 않는다(메모리만)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            val h = probe(p, listOf(HoloPage(listOf(HoloLine("안녕 {player} %player_name%")))))
            withSettings({ it.copy(maxPersonalViewers = 0, personalFallback = "?") }) {
                withProbe(h) {
                    val text = engine.entitiesFor(p, PROBE).filterIsInstance<TextDisplay>().firstOrNull()?.let { Text.plain(it.text()) }
                    when {
                        engine.personalFor(p, PROBE) != false -> "상한 0 인데 사람마다의 묶음입니다"
                        text == null -> "글 엔티티가 없습니다"
                        text.contains("{player}") || text.contains("%player_name%") -> "공용 글에 사람 토큰이 그대로입니다: $text"
                        text != "안녕 ? ?" -> "공용 글이 기대와 다릅니다: $text"
                        else -> null
                    }
                }
            }
        },
        Check("청크가 내려가면 그 청크의 홀로그램 엔티티를 거둔다(메모리만 — 다른 플러그인이 듣는 청크 사건은 쏘지 않는다)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            val h = probe(p, listOf(HoloPage(listOf(HoloLine("청크 검사")))))
            withProbe(h) {
                val before = engine.entitiesFor(p, PROBE)
                if (before.isEmpty()) return@withProbe "내리기 전에 엔티티가 없습니다"
                val probeDef = store.get(PROBE)!!
                engine.chunkUnloaded(probeDef.world, kotlin.math.floor(probeDef.x).toInt() shr 4, kotlin.math.floor(probeDef.z).toInt() shr 4)
                val left = engine.liveCount(PROBE)
                engine.refreshAll() // 같은 청크의 다른 홀로그램을 곧바로 다시 세운다
                when {
                    left != 0 -> "청크를 내렸는데 엔티티 ${left}개가 남았습니다"
                    before.any { it.isValid } -> "거둔 엔티티가 아직 세상에 있습니다"
                    else -> null
                }
            }
        },
        Check("남은(우리 것이 아닌) 표시 엔티티를 치운다 — 진짜 엔티티 올라오기 사건") { p ->
            val stray = p.world.spawn(p.location.clone().add(0.0, 3.0, 0.0), TextDisplay::class.java) { d ->
                d.isPersistent = false
                d.isVisibleByDefault = false
                d.persistentDataContainer.set(engine.tag, PersistentDataType.STRING, PROBE)
            }
            try {
                Bukkit.getPluginManager().callEvent(EntitiesLoadEvent(p.location.chunk, listOf(stray)))
                if (stray.isValid) "표시가 달린 남은 엔티티를 지우지 않았습니다(HologramListener.onEntitiesLoad)" else null
            } finally {
                if (stray.isValid) stray.remove()
            }
        },
        Check("권한이 없으면 안 보인다 · 있으면 보인다 — 검사자의 권한을 메모리에서 잠깐 뺀다(붙임)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            val node = HologramCodec.HOLO_SEE_PREFIX + PROBE
            val h = probe(p, listOf(HoloPage(listOf(HoloLine("권한 검사"))))) { it.copy(permission = node) }
            val attachment = p.addAttachment(ess.plugin)
            try {
                attachment.setPermission(node, false)
                val hidden = withProbe(h) { engine.liveCount(PROBE) }
                attachment.setPermission(node, true)
                val shown = withProbe(h) { engine.entitiesFor(p, PROBE).size }
                when {
                    hidden != 0 -> "보려면 권한($node)이 필요한데 권한 없는 검사자에게 엔티티 ${hidden}개를 세웠습니다"
                    shown == 0 -> "권한($node)이 있는데 보이지 않습니다"
                    else -> null
                }
            } finally {
                p.removeAttachment(attachment)
            }
        },
        Check("아이콘 줄은 ItemDisplay 로 세운다(메모리만)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            val h = probe(p, listOf(HoloPage(listOf(HoloLine("아이콘 검사"), HoloLine(icon = "minecraft:diamond")))))
            withProbe(h) {
                val items = engine.entitiesFor(p, PROBE).filterIsInstance<ItemDisplay>()
                when {
                    items.size != 1 -> "아이콘 줄 하나에 ItemDisplay ${items.size}개(기대 1)"
                    items[0].itemStack.type != Material.DIAMOND -> "아이콘이 기대(DIAMOND)와 다릅니다: ${items[0].itemStack.type}"
                    else -> null
                }
            }
        },
        Check("자동 넘김 주기가 되면 쪽이 넘어간다 — 1초 일(step)을 이 홀로그램에만(메모리만)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            val h = probe(p, listOf(HoloPage(listOf(HoloLine("1쪽"))), HoloPage(listOf(HoloLine("2쪽"))))) { it.copy(pageSeconds = 2) }
            withProbe(h) {
                val def = store.get(PROBE)!!
                val first = engine.pageOf(p, def)
                engine.step(def, 1) // 2초 주기의 1초째 — 넘기지 않는다
                val notYet = engine.pageOf(p, def)
                engine.step(def, 2)
                val after = engine.pageOf(p, def)
                val text = engine.entitiesFor(p, PROBE).filterIsInstance<TextDisplay>().firstOrNull()?.let { Text.plain(it.text()) }
                when {
                    notYet != first -> "주기(2초)가 되기 전에 쪽이 넘어갔습니다"
                    after == first -> "주기가 됐는데 쪽이 그대로입니다(${first + 1}쪽)"
                    text != "${after + 1}쪽" -> "쪽은 ${after + 1}쪽인데 보이는 글이 다릅니다: $text"
                    else -> null
                }
            }
        },
        Check("안 보이는 홀로그램은 누르기 패킷이 와도 명령을 돌리지 않는다 — 진짜 상호작용 사건(메모리만)") { p ->
            if (!PlayerSettings.enabled(p, SETTING_VISIBLE, true)) return@Check null
            if (!p.hasPermission(CLICK)) return@Check null
            val line = HoloLine("숨김 누르기", actions = HoloActions(mapOf(HoloClick.ANY to listOf("player:ess-verify-holo-hidden {player}"))))
            val h = probe(p, listOf(HoloPage(listOf(line))))
            withProbe(h) {
                val interaction = engine.entitiesFor(p, PROBE).filterIsInstance<Interaction>().firstOrNull()
                    ?: return@withProbe "누를 동작이 있는데 Interaction 이 없습니다"
                try {
                    engine.forceHidden += p.uniqueId // 개인 보기 끔과 같은 길 — 이 사람에게서 묶음을 거둔다
                    engine.refreshAll()
                    engine.resetClickCooldown(p)
                    val event = PlayerInteractEntityEvent(p, interaction, EquipmentSlot.HAND) // 고친 클라이언트가 옛 엔티티 번호로 보낸 것
                    Bukkit.getPluginManager().callEvent(event)
                    when {
                        engine.capture.orEmpty().isNotEmpty() -> "안 보이는(맡기지 않은) 엔티티를 눌렀는데 명령이 돌았습니다: ${engine.capture}"
                        !event.isCancelled -> "우리 엔티티의 상호작용 사건을 취소하지 않았습니다"
                        else -> null
                    }
                } finally {
                    engine.forceHidden -= p.uniqueId
                    engine.resetClickCooldown(p)
                    engine.refreshAll()
                }
            }
        },
    )

    companion object {
        const val ADMIN = "inmcessentials.holograms.admin"
        const val TOGGLE = "inmcessentials.holograms.toggle"
        const val PAGES = "inmcessentials.holograms.pages"
        const val PAGES_OTHERS = "inmcessentials.holograms.pages.others"
        const val CLICK = "inmcessentials.holograms.click"
        const val CONSOLE = "inmcessentials.holograms.console"

        /** 개인 설정 열쇠(CMI visibleHolograms) — 플레이어 메뉴 `/설정` 에 저절로 뜬다. */
        const val SETTING_VISIBLE = "essentials.holograms"
        private const val SETTINGS_OWNER = "홀로그램"

        private const val KIND = "홀로그램"
        private const val PROBE = "ess-verify-holo"

        val MESSAGES: Map<String, String> = linkedMapOf(
            "holograms-usage" to listOf(
                "<gold>/홀로그램</gold> <gray>— 목록 화면</gray> · <gold>/홀로그램 가까이 [반경]</gold>",
                "<gold>/홀로그램 만들기 <이름> [첫 줄]</gold> · <gold>삭제 · 편집 · 여기로 · 이동 <이름></gold>",
                "<gold>/홀로그램 줄추가 <이름> <글></gold> <gray>— icon:손 · icon:minecraft:diamond 는 아이콘 줄, !nextpage! 는 새 쪽</gray>",
                "<gold>/홀로그램 줄수정 · 줄끼우기 <이름> <줄> <글></gold> · <gold>줄삭제 <이름> <줄></gold> <gray>— 줄: 3 또는 2:3(2쪽 3번째)</gray>",
                "<gold>/홀로그램 쪽 <이름> [추가|삭제 <번호>]</gold> · <gold>동작 <이름> <줄|쪽|쪽:N> <좌|우|shift좌|shift우|아무> [명령]</gold>",
                "<gold>/홀로그램 넘기기 <이름> [플레이어] [다음|이전|번호]</gold> · <gold>보기</gold> <gray>— 내 화면에서 켜고 끄기</gray>",
                "<gold>/홀로그램 다시읽기</gold> <gray>— holograms.yml 을 다시 읽기</gray>",
            ).joinToString("\n"),
            "holograms-player-usage" to "<gold>/홀로그램 보기</gold> <gray>— 홀로그램 켜고 끄기 ·</gray> <gold>/홀로그램 넘기기 <이름> [다음|이전|번호]</gold>",
            "holograms-invalid-name" to "<red>홀로그램 이름은 한글·영문·숫자·_- 32자까지입니다: {홀로그램}</red>",
            "holograms-exists" to "<red>'{홀로그램}' 홀로그램이 이미 있습니다.</red>",
            "holograms-not-found" to "<red>'{홀로그램}' 홀로그램이 없습니다.</red>",
            "holograms-created" to "<green>홀로그램 <white>{홀로그램}</white> 을(를) 만들었습니다.</green> <click:run_command:'/홀로그램 편집 {홀로그램}'><yellow>[편집]</yellow></click>",
            "holograms-removed" to "<green>홀로그램 {홀로그램} 을(를) 지웠습니다.</green>",
            "holograms-none" to "<gray>홀로그램이 없습니다.</gray> <gray>/홀로그램 만들기 <이름> [첫 줄]</gray>",
            "holograms-none-near" to "<gray>{값}블록 안에 홀로그램이 없습니다.</gray>",
            "holograms-list-header" to "<gold>홀로그램 {개수}개:</gold>",
            "holograms-near-header" to "<gold>{값}블록 안 홀로그램 {개수}개:</gold>",
            "holograms-list-entry" to "<click:run_command:'/홀로그램 편집 {홀로그램}'><hover:show_text:'<gray>눌러서 편집'><white>{홀로그램}</white></hover></click> <gray>{월드} {좌표} · {쪽}쪽 {개수}줄 {값}</gray>",
            "holograms-hand-empty" to "<red>손에 아이콘으로 쓸 아이템을 드세요.</red>",
            "holograms-icon-invalid" to "<red>'{값}' 은(는) 아이템이 아닙니다.</red> <gray>(icon:손 · icon:minecraft:diamond · icon:mmoitems:종류:아이디)</gray>",
            "holograms-line-added" to "<green>{홀로그램} 에 줄을 더했습니다({줄}).</green>",
            "holograms-line-set" to "<green>{홀로그램} 의 {줄} 줄을 고쳤습니다.</green>",
            "holograms-line-inserted" to "<green>{홀로그램} 의 {줄} 자리에 줄을 끼웠습니다.</green>",
            "holograms-line-removed" to "<green>{홀로그램} 의 {줄} 줄을 지웠습니다.</green>",
            "holograms-line-invalid" to "<red>{홀로그램} 에 '{값}' 줄이 없습니다.</red> <gray>(3 · 2:3 = 2쪽 3번째)</gray>",
            "holograms-moved" to "<green>홀로그램 {홀로그램} 을(를) 여기로 옮겼습니다.</green>",
            "holograms-world-missing" to "<red>홀로그램 {홀로그램} 의 월드({월드})가 없습니다.</red>",
            "holograms-teleported" to "<green>홀로그램 {홀로그램} 앞으로 왔습니다.</green>",
            "holograms-pages-info" to "<gold>{홀로그램}</gold> <gray>— {쪽}쪽: {값}</gray>",
            "holograms-page-added" to "<green>{홀로그램} 에 {쪽}쪽을 더했습니다.</green>",
            "holograms-page-added-empty" to "<green>{홀로그램} 에 빈 {쪽}쪽을 더했습니다.</green> <gray>줄추가 가 이 쪽에 들어갑니다 — 줄이 생기기 전에는 넘겨도 건너뜁니다.</gray>",
            "holograms-page-empty" to "<yellow>{홀로그램} 의 {쪽}쪽이 비었습니다 — 줄을 더하기 전에는 넘겨도 건너뜁니다.</yellow> <gray>(/홀로그램 쪽 {홀로그램} 삭제 {쪽})</gray>",
            "holograms-changed" to "<red>그사이 다른 사람이 {홀로그램} 을(를) 고쳤습니다 — 다시 그렸으니 다시 골라 주세요.</red>",
            "holograms-page-removed" to "<green>{홀로그램} 의 {쪽}쪽을 지웠습니다.</green>",
            "holograms-page-invalid" to "<red>{홀로그램} 은(는) {쪽}쪽까지입니다(마지막 한 쪽은 못 지웁니다).</red>",
            "holograms-click-invalid" to "<red>'{값}' 은(는) 누르는 종류가 아닙니다.</red> <gray>(좌 · 우 · shift좌 · shift우 · 아무)</gray>",
            "holograms-action-added" to "<green>{홀로그램} {줄} 의 {클릭} 명령을 더했습니다:</green> <white>{값}</white>",
            "holograms-action-cleared" to "<yellow>{홀로그램} {줄} 의 {클릭} 명령을 비웠습니다.</yellow>",
            "holograms-single-page" to "<gray>{홀로그램} 은(는) 쪽이 하나뿐입니다.</gray>",
            "holograms-page-turned" to "<green>{대상}님의 {홀로그램} 을(를) {쪽}쪽으로 넘겼습니다.</green>",
            "holograms-visible-on" to "<green>홀로그램이 보입니다.</green>",
            "holograms-visible-off" to "<yellow>홀로그램을 숨겼습니다.</yellow> <gray>/홀로그램 보기 로 다시 켭니다</gray>",
            "holograms-reloaded" to "<green>holograms.yml 을 다시 읽었습니다 — 홀로그램 {개수}개</green> <gray>(못 읽은 칸 {값})</gray>",
            "holograms-condition-invalid" to "<red>조건을 읽지 못했습니다 — 아무에게도 안 보입니다: {값}</red> <gray>(%플레이스홀더% >= 10 · == · != · 포함)</gray>",
            "holograms-reload-failed" to "<red>holograms.yml 을 YAML 로 읽지 못했습니다 — 지금 홀로그램 {개수}개를 그대로 쓰고, 고칠 때까지 저장하지 않습니다.</red> <gray>{값}</gray>",
            "holograms-locked" to "<red>holograms.yml 이 깨져 있어(YAML 오류) 고칠 수 없습니다 — 저장하면 파일을 덮습니다.</red> <gray>파일을 고치고 /홀로그램 다시읽기 · {값}</gray>",
            "holograms-rejected-exists" to "<red>holograms.yml 에 같은 이름의 못 읽은 홀로그램 '{홀로그램}' 이(가) 있습니다 — 파일을 먼저 고치세요(서버 로그에 까닭).</red>",
            "holograms-console-denied" to "<red>콘솔 명령을 더하거나 바꿀 권한이 없습니다(inmcessentials.holograms.console) — player: 를 붙이세요:</red> <white>{값}</white>",
        )
    }
}

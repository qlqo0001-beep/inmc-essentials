package com.inmc.essentials.hologram

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.Locale

/**
 * `holograms.yml` 한 벌. [rejected] = 읽지 못한 칸의 원문 — 지우지 않고 되써 넣는다(지뢰 13). [reasons] = 그 까닭(이름 → 한국어, 로그·검증기용 —
 * 저장하지 않는다).
 */
data class HologramFile(
    val holograms: List<Hologram> = emptyList(),
    val rejected: Map<String, Any?> = emptyMap(),
    val reasons: Map<String, String> = emptyMap(),
)

/** CMI 에서 읽은 것 — [unreadable] 은 원문 조각과 함께 보고서로, [notes] 는 옮기지 못한 속성. */
data class CmiHolograms(val holograms: List<Hologram>, val unreadable: List<String>, val notes: List<String>)

/**
 * 홀로그램 YAML 읽기·쓰기 + CMI `Saves/Holograms.yml` 판독. 서버 없이 돈다(`HologramCodecTest`).
 *
 * ```
 * holograms:
 *   <이름>:
 *     location: "world;x;y;z"
 *     enabled · view-range · update-seconds · page-seconds · permission · condition · billboard · yaw · scale · background · shadow ·
 *     see-through · click-pages · click-width · alignment · line-width · text-opacity
 *     pages:
 *       - lines:
 *           - '<gold>글 한 줄'                       # 글만이면 문자열
 *           - { text: '…', actions: { left: [..], right: [..] } }
 *           - { icon: 'minecraft:diamond', material: DIAMOND, snapshot: base64 }
 *         actions: { any: ['[nextPage]'] }        # 쪽의 동작(줄에 없으면 이것)
 * ```
 */
object HologramCodec {

    const val ROOT = "holograms"

    fun read(yaml: YamlConfiguration): HologramFile {
        val root = yaml.getConfigurationSection(ROOT) ?: return HologramFile()
        val holograms = ArrayList<Hologram>()
        val rejected = LinkedHashMap<String, Any?>()
        val reasons = LinkedHashMap<String, String>()
        for (name in root.getKeys(false)) {
            val section = root.getConfigurationSection(name)
            val hologram = try {
                if (section == null) throw Bad("섹션이 아닙니다") else readOne(name, section)
            } catch (e: Bad) {
                reasons[name] = e.message.orEmpty()
                null
            } catch (e: RuntimeException) {
                reasons[name] = "읽다가 오류: $e"
                null
            }
            if (hologram != null && holograms.any { it.key == hologram.key }) reasons[name] = "같은 이름(대소문자만 다름)이 이미 있습니다"
            if (hologram == null || holograms.any { it.key == hologram.key }) {
                rejected[name] = plain(root.get(name))
                continue
            }
            holograms += hologram
        }
        return HologramFile(holograms, rejected, reasons)
    }

    /** 못 읽은 까닭(한국어) — [read] 가 받아 [HologramFile.reasons] 에 적는다. */
    private class Bad(message: String) : Exception(message)

    private fun readOne(name: String, s: ConfigurationSection): Hologram {
        if (!HoloRules.NAME.matches(name)) throw Bad("이름에 못 쓰는 글자가 있습니다")
        val locRaw = s.getString("location") ?: throw Bad("location 이 없습니다")
        val loc = locRaw.split(';').map { it.trim() }
        fun bad(): Nothing = throw Bad("location 을 읽지 못했습니다: '$locRaw' (월드;x;y;z)")
        if (loc.size < 4 || loc[0].isEmpty()) bad()
        val x = loc[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: bad()
        val y = loc[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: bad()
        val z = loc[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: bad()
        val pages = when (val pagesRaw = s.get("pages")) {
            null -> emptyList()
            is List<*> -> pagesRaw.mapIndexed { i, page ->
                readPage(page as? Map<*, *> ?: throw Bad("pages ${i + 1}번째가 쪽 모양(lines·actions)이 아닙니다: $page"), i)
            }
            else -> throw Bad("pages 가 목록이 아닙니다: $pagesRaw")
        }
        return Hologram(
            name = name, world = loc[0], x = x, y = y, z = z,
            pages = pages.ifEmpty { listOf(HoloPage()) },
            enabled = s.getBoolean("enabled", true),
            viewRange = if (s.isInt("view-range")) s.getInt("view-range").coerceIn(1, 256) else null,
            updateSeconds = if (s.isInt("update-seconds")) s.getInt("update-seconds").coerceIn(0, 86_400) else null,
            updateRange = if (s.isInt("update-range")) s.getInt("update-range").coerceIn(0, 256) else null,
            pageSeconds = if (s.isInt("page-seconds")) s.getInt("page-seconds").coerceIn(0, 86_400) else null,
            permission = s.getString("permission")?.trim()?.takeIf { it.isNotEmpty() },
            condition = s.getString("condition")?.trim()?.takeIf { it.isNotEmpty() },
            billboard = s.getString("billboard")?.trim()?.uppercase()?.takeIf { it in BILLBOARDS } ?: "CENTER",
            yaw = s.getDouble("yaw", 0.0).toFloat(),
            scale = s.getDouble("scale", 1.0).takeIf { it.isFinite() && it > 0 }?.coerceIn(0.1, 10.0) ?: 1.0,
            background = s.getString("background")?.trim()?.takeIf { it.isNotEmpty() } ?: "default",
            shadow = s.getBoolean("shadow", true),
            seeThrough = s.getBoolean("see-through", false),
            clickPages = s.getBoolean("click-pages", true),
            clickWidth = s.getDouble("click-width", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 16.0) ?: 0.0,
            alignment = s.getString("alignment")?.trim()?.uppercase()?.takeIf { it in Hologram.ALIGNMENTS } ?: "CENTER",
            lineWidth = s.getInt("line-width", Hologram.DEFAULT_LINE_WIDTH).coerceIn(1, 10_000),
            textOpacity = s.getInt("text-opacity", 255).coerceIn(0, 255),
        )
    }

    private fun readPage(map: Map<*, *>, index: Int): HoloPage {
        val where = "pages ${index + 1}쪽"
        val lines = when (val raw = map["lines"]) {
            null -> emptyList()
            is List<*> -> raw.mapIndexed { j, line -> readLine(line, "$where ${j + 1}번째 줄") ?: throw Bad("$where ${j + 1}번째 줄의 모양이 이상합니다: $line") }
            else -> throw Bad("$where 의 lines 가 목록이 아닙니다: $raw")
        }
        return HoloPage(lines, readActions(map["actions"], "$where 의 actions"))
    }

    private fun readLine(raw: Any?, where: String): HoloLine? = when (raw) {
        is String -> HoloLine(text = raw)
        is Number, is Boolean -> HoloLine(text = raw.toString())
        is Map<*, *> -> {
            val icon = raw["icon"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val snapshot = raw["snapshot"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            HoloLine(
                text = raw["text"]?.toString() ?: "",
                icon = icon,
                material = raw["material"]?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                snapshot = snapshot,
                actions = readActions(raw["actions"], "$where 의 actions"),
            )
        }
        else -> null
    }

    /** 누르기 동작 — 모르는 클릭 종류·모양이 이상한 값은 못 읽음으로(조용히 버리면 다음 저장에서 명령이 사라진다). */
    private fun readActions(raw: Any?, where: String): HoloActions {
        val map = when (raw) {
            null -> return HoloActions.NONE
            is ConfigurationSection -> raw.getValues(false)
            is Map<*, *> -> raw
            else -> throw Bad("$where 가 '클릭 종류: 명령 목록' 모양이 아닙니다: $raw")
        }
        val out = LinkedHashMap<HoloClick, List<String>>()
        for ((k, v) in map) {
            val click = HoloClick.parse(k?.toString()) ?: throw Bad("$where 의 '$k' 은(는) 클릭 종류가 아닙니다(left · right · shift-left · shift-right · any)")
            val list = when (v) {
                null -> emptyList()
                is List<*> -> v.map { item ->
                    if (item is Map<*, *> || item is List<*> || item is ConfigurationSection) throw Bad("$where.$k 의 명령 모양이 이상합니다: $item")
                    item?.toString()?.trim().orEmpty()
                }.filter { it.isNotEmpty() }
                is Map<*, *>, is ConfigurationSection -> throw Bad("$where.$k 의 명령 모양이 이상합니다: $v")
                else -> listOf(v.toString().trim()).filter { it.isNotEmpty() }
            }
            if (list.isNotEmpty()) out[click] = list
        }
        return HoloActions(out)
    }

    fun write(file: HologramFile): YamlConfiguration {
        val yaml = YamlConfiguration()
        val root = yaml.createSection(ROOT)
        for (h in file.holograms.sortedBy { it.key }) {
            val s = root.createSection(h.name)
            s.set("location", listOf(h.world, num(h.x), num(h.y), num(h.z)).joinToString(";"))
            s.set("enabled", h.enabled)
            h.viewRange?.let { s.set("view-range", it) }
            h.updateSeconds?.let { s.set("update-seconds", it) }
            h.updateRange?.let { s.set("update-range", it) }
            h.pageSeconds?.let { s.set("page-seconds", it) }
            h.permission?.let { s.set("permission", it) }
            h.condition?.let { s.set("condition", it) }
            s.set("billboard", h.billboard)
            if (h.yaw != 0f) s.set("yaw", h.yaw.toDouble())
            s.set("scale", h.scale)
            s.set("background", h.background)
            s.set("shadow", h.shadow)
            s.set("see-through", h.seeThrough)
            s.set("click-pages", h.clickPages)
            if (h.clickWidth > 0) s.set("click-width", h.clickWidth)
            if (h.alignment != "CENTER") s.set("alignment", h.alignment)
            if (h.lineWidth != Hologram.DEFAULT_LINE_WIDTH) s.set("line-width", h.lineWidth)
            if (h.textOpacity != 255) s.set("text-opacity", h.textOpacity)
            s.set("pages", h.pages.map { page ->
                val out = LinkedHashMap<String, Any>()
                out["lines"] = page.lines.map { writeLine(it) }
                if (!page.actions.isEmpty) out["actions"] = writeActions(page.actions)
                out
            })
        }
        for ((name, raw) in file.rejected) {
            if (root.contains(name)) continue
            if (raw is Map<*, *>) root.createSection(name, raw) else root.set(name, raw)
        }
        return yaml
    }

    private fun writeLine(line: HoloLine): Any {
        if (!line.isIcon && line.actions.isEmpty) return line.text
        val out = LinkedHashMap<String, Any>()
        if (line.isIcon) {
            line.icon?.let { out["icon"] = it }
            line.material?.let { out["material"] = it }
            line.snapshot?.let { out["snapshot"] = it }
        } else out["text"] = line.text
        if (!line.actions.isEmpty) out["actions"] = writeActions(line.actions)
        return out
    }

    private fun writeActions(actions: HoloActions): Map<String, List<String>> =
        actions.map.filterValues { it.isNotEmpty() }.entries.associate { (k, v) -> k.id to v }

    fun plain(value: Any?): Any? = when (value) {
        is ConfigurationSection -> value.getKeys(false).associateWith { plain(value.get(it)) }
        else -> value
    }

    private fun num(v: Double): String = "%.3f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')

    val BILLBOARDS = setOf("CENTER", "VERTICAL", "HORIZONTAL", "FIXED")

    // --- CMI ------------------------------------------------------------------------------------------

    /**
     * 섹션 하나를 읽으며 쓴 열쇠를 적는다 — 다 읽은 뒤 [leftovers] 가 옮기지 않은 열쇠를 값과 함께 돌려준다(조용히 버리지 않게).
     * 열쇠는 대소문자를 가리지 않는다.
     */
    private class Keys(val s: ConfigurationSection?, val path: String) {
        private val used = HashSet<String>()
        private val skipped = LinkedHashMap<String, Any?>()

        private fun actual(key: String): String? = s?.getKeys(false)?.firstOrNull { it.equals(key, ignoreCase = true) }

        fun get(key: String): Any? {
            val k = actual(key) ?: return null
            used += k
            return s!!.get(k)
        }

        fun section(key: String): ConfigurationSection? = get(key) as? ConfigurationSection

        fun has(key: String): Boolean = actual(key) != null

        /** 알지만 옮기지 않는 열쇠 — 값과 함께 알림으로. */
        fun skip(vararg keys: String) {
            for (key in keys) {
                val k = actual(key) ?: continue
                used += k
                skipped["$path$k"] = plain(s!!.get(k))
            }
        }

        fun skippedNotes(): Map<String, Any?> = skipped

        /** 모르는 열쇠(값과 함께). */
        fun leftovers(): Map<String, Any?> =
            s?.getKeys(false)?.filter { it !in used }?.associate { "$path$it" to plain(s.get(it)) }.orEmpty()
    }

    /**
     * CMI 9.8.9.8 `Saves/Holograms.yml` — jar 의 `CMIHologram.serialize/deserialize` 와 하위 클래스(`CMIHologramSettings` · `CMIHologramTextSettings` ·
     * `CMIHologramPageSettings` · `CMIHologramInteraction` · `CMIHologramIconSettings`)의 `deserialize` 로 확인한 열쇠(테섭 파일은 비어 있어 **실제 표본 없이**).
     *
     * 새 모양(지금 CMI 가 쓰는 것): `Loc` · `Enabled` · `ArmorStand` · `Lines` ·
     * `Settings{Bill, Group, LOS, Perm, Save, Light, Dir, Offset, Scale("w;h"), Range{Visual, Update}, Interval(틱)}` ·
     * `Text{Align, BColor, BAlpha, TAlpha, Double, Shadow, See, Line, Fill}` · `Page{Change(틱), Auto}` ·
     * `Interaction{Enabled, Dimension, Offset, Particle{…}, Commands, BasePrefix, HoverPrefix}` · `Icon{Scale, Dir, Offset, Bill}` · `Animation` · `Board`.
     *
     * 옛 모양(섹션이 없을 때 CMI 가 읽는 것): 맨 위 `Interval`(초) · `Group` · `RangeExtra` · `Range` · `RequiresPermission` · `PageInterval`(초) · `Filler` ·
     * `Interactable` · `Commands` · `ShowParticles` · `HoverParticles` · `NewDisplay`, 그리고 `ND{Bill, SL, BL, Yaw, Pitch, ScaleW, ScaleH, Align, TAlpha,
     * BAlpha, Shadow, Double, BColor, LineW, AutoPage}`.
     *
     * 옮기는 것: 위치(CMI 는 아래 기준 — 가운데를 맞춘다) · 켜짐 · 줄(쪽) · 보이는/갱신 거리 · 갱신 주기 · 자동 넘김 주기 · 쪽 넘김 버튼(→ 누르면 넘기기) ·
     * 권한 필요 · 방향 · 크기 · 그림자 · 벽 너머 · 배경(CMI 기본 검정 125) · 정렬 · 감기 너비 · 글자 투명도 · 누르기(켜짐 — CMI 기본 꺼짐 · 명령).
     * 나머지 아는 열쇠는 값과 함께 [CmiHolograms.notes] 로, 모르는 열쇠는 [CmiHolograms.unreadable] 로 — 섹션 속 열쇠까지 전부.
     */
    fun fromCmi(yaml: YamlConfiguration): CmiHolograms {
        val holograms = ArrayList<Hologram>()
        val unreadable = ArrayList<String>()
        val notes = ArrayList<String>()
        for (key in yaml.getKeys(false)) {
            val outer = yaml.getConfigurationSection(key)
            if (outer == null) {
                unreadable += "'$key' 은(는) 섹션이 아닙니다: ${yaml.get(key)}"
                continue
            }
            // CMI 는 항목이 하나뿐이고 그것이 섹션이면 한 겹 더 들어간다(deserialize 앞부분).
            val s = outer.getKeys(false).singleOrNull()?.let { outer.getConfigurationSection(it) }?.takeIf { it.contains("Loc") } ?: outer
            val top = Keys(s, "")
            val rawName = (top.get("Name") as? String)?.takeIf { it.isNotBlank() } ?: key
            val name = rawName.replace(Regex("[^A-Za-z0-9_\\-가-힣]"), "_").take(32)
            if (name != rawName) notes += "홀로그램 '$rawName' 의 이름을 '$name' 으로 바꿔 넣습니다"
            val locRaw = top.get("Loc")?.toString()
            val loc = locRaw?.split(';', ':')?.map { it.trim() }
            val world = loc?.getOrNull(0)?.takeIf { it.isNotEmpty() }
            val xyz = loc?.drop(1)?.take(3)?.map { it.toDoubleOrNull()?.takeIf { d -> d.isFinite() } }
            if (world == null || xyz == null || xyz.size < 3 || xyz.any { it == null }) {
                unreadable += "홀로그램 '$rawName' 의 위치(Loc='$locRaw')를 읽지 못했습니다"
                continue
            }
            val lines = when (val raw = top.get("Lines")) {
                is List<*> -> raw.map { it?.toString() ?: "" }
                is String -> raw.split("\n")
                null -> emptyList()
                else -> {
                    unreadable += "홀로그램 '$rawName' 의 줄(Lines)을 읽지 못했습니다: $raw"
                    continue
                }
            }
            if (lines.any { it.contains("[playerDisplayName]") }) notes += "홀로그램 '$name' 의 [playerDisplayName] 은 {player}(이름)로 바꿉니다 — 표시 이름(닉네임)이 아닙니다"
            var pages = HoloRules.splitPages(lines).map { page ->
                HoloPage(page.map { line ->
                    val (converted, lineNotes) = HoloRules.fromCmiLine(line)
                    lineNotes.forEach { notes += "홀로그램 '$name': $it" }
                    converted
                })
            }

            val readers = ArrayList<Keys>()
            fun sub(parent: Keys, key: String, path: String): Keys = Keys(parent.section(key), path).also { readers += it }
            val settings = sub(top, "Settings", "Settings.")
            val text = sub(top, "Text", "Text.")
            val page = sub(top, "Page", "Page.")
            val interaction = sub(top, "Interaction", "Interaction.")
            val icon = sub(top, "Icon", "Icon.")
            val nd = sub(top, "ND", "ND.")
            val particle = sub(interaction, "Particle", "Interaction.Particle.")
            val hover = sub(particle, "Hover", "Interaction.Particle.Hover.")
            val range = sub(settings, "Range", "Settings.Range.")
            val legacyHover = sub(top, "HoverParticles", "HoverParticles.")

            // 보이는 거리 · 갱신 거리 — 새: Settings.Range{Visual, Update} / 옛: RangeExtra(보이는) · Range(갱신, 보이는 거리는 둘 중 큰 것).
            var viewRange = intOf(range.get("Visual"))
            var updateRange = intOf(range.get("Update"))
            if (settings.s == null) {
                val extra = intOf(top.get("RangeExtra"))
                val legacy = intOf(top.get("Range"))
                if (extra != null || legacy != null) viewRange = maxOf(extra ?: 0, legacy ?: 0)
                if (legacy != null) updateRange = legacy
            }
            // 갱신 주기 — 새: Settings.Interval(틱) / 옛: 맨 위 Interval(초).
            val intervalSeconds = numberOf(settings.get("Interval"))?.div(20.0) ?: if (settings.s == null) numberOf(top.get("Interval")) else null
            // 자동 넘김 — 새: Page{Change(틱), Auto} / 옛: 맨 위 PageInterval(초) · ND.AutoPage.
            val pageSecondsRaw = numberOf(page.get("Change"))?.div(20.0) ?: if (page.s == null) numberOf(top.get("PageInterval")) else null
            // Page.Auto · ND.AutoPage 는 자동 넘김이 아니라 "쪽 넘김 버튼 줄"(CMIHologramPageSettings.autoPagination — 편집기 embeddedAutoButton,
            // 쪽마다 `<<<< [prevPage] >>>> [nextPage]` 줄을 붙인다). 자동 넘김은 Change(PageInterval) > 0 뿐.
            val pageButtons = boolOf(page.get("Auto")) ?: if (page.s == null) boolOf(nd.get("AutoPage")) else null
            // 권한 — 새: Settings.Perm / 옛: RequiresPermission.
            val perm = boolOf(settings.get("Perm")) ?: (if (settings.s == null) boolOf(top.get("RequiresPermission")) else null) ?: false
            // 방향 · 크기 · 각도 — 새: Settings.Bill · Scale("w;h") / 옛: ND.Bill · ScaleW · ScaleH · Yaw.
            val bill = (settings.get("Bill") ?: nd.get("Bill"))?.toString()?.trim()?.uppercase()
            val scaleText = settings.get("Scale")?.toString()
            val scaleW = scaleText?.split(';')?.getOrNull(0)?.trim()?.toDoubleOrNull() ?: numberOf(nd.get("ScaleW"))
            val scaleH = scaleText?.split(';')?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: numberOf(nd.get("ScaleH"))
            if (scaleW != null && scaleH != null && scaleW != scaleH) notes += "홀로그램 '$name' 의 크기 가로 $scaleW · 세로 $scaleH 중 가로만 씁니다(우리 크기는 하나)"
            val yaw = numberOf(nd.get("Yaw"))
            // 글 — 새: Text{Shadow, See, BColor, BAlpha, Align, Line, TAlpha} / 옛: ND{Shadow, BColor, BAlpha, Align, LineW, TAlpha}.
            val shadow = boolOf(text.get("Shadow")) ?: boolOf(nd.get("Shadow"))
            val seeThrough = boolOf(text.get("See"))
            val alignRaw = (text.get("Align") ?: nd.get("Align"))?.toString()?.trim()?.uppercase()
            val alignment = alignRaw?.takeIf { it in Hologram.ALIGNMENTS }
            if (alignRaw != null && alignment == null) notes += "홀로그램 '$name' 의 정렬 '$alignRaw' 을(를) 몰라 가운데로 넣습니다"
            val lineWidth = intOf(text.get("Line")) ?: intOf(nd.get("LineW"))
            val textAlpha = intOf(text.get("TAlpha")) ?: intOf(nd.get("TAlpha"))
            // 배경 — CMIHologramTextSettings 기본은 검정 · 투명도 125 이고 serialize 는 기본값이면 쓰지 않는다. 그래서 없는 쪽은 그 기본으로 채운다.
            val bColorRaw = (text.get("BColor") ?: nd.get("BColor"))?.toString()?.trim()
            val bAlpha = intOf(text.get("BAlpha")) ?: intOf(nd.get("BAlpha"))
            val armorStand = boolOf(top.get("ArmorStand")) == true
            val background = if (armorStand && bColorRaw == null && bAlpha == null) "none" // 갑옷 거치대 이름에는 배경이 없었다
            else {
                val alpha = (bAlpha ?: CMI_DEFAULT_BACKGROUND_ALPHA).coerceIn(0, 255)
                val hex = bColorRaw?.let(HoloRules::cmiColorHex) ?: CMI_DEFAULT_BACKGROUND
                if (bColorRaw != null && HoloRules.cmiColorHex(bColorRaw) == null) {
                    notes += "홀로그램 '$name' 의 배경색 '$bColorRaw' 을(를) 알아보지 못해 검정으로 넣습니다 — 편집 화면에서 #AARRGGBB 로 고치세요"
                }
                if (alpha == 0) "none" else "#%02X%s".format(alpha, hex)
            }
            // 누르기 — 새: Interaction{Enabled, Commands} / 옛: Interactable · 맨 위 Commands(CMI 는 늘 맨 위 Commands 도 읽는다).
            // CMIHologramInteraction 의 기본은 interactable=false 이고 serialize 는 켜졌을 때만 Enabled 를 쓴다 — 없으면 꺼짐.
            val interactable = boolOf(interaction.get("Enabled")) ?: boolOf(top.get("Interactable")) ?: CMI_DEFAULT_INTERACTABLE
            val commandsRaw = interaction.get("Commands") ?: top.get("Commands")
            val commands = when (commandsRaw) {
                is List<*> -> commandsRaw.mapNotNull { it?.toString()?.trim()?.takeIf { c -> c.isNotEmpty() } }.map(HoloRules::convertCommand)
                null -> emptyList()
                is String -> listOf(HoloRules.convertCommand(commandsRaw)).filter { it.isNotBlank() }
                else -> {
                    unreadable += "홀로그램 '$rawName' 의 클릭 명령(Commands)을 읽지 못했습니다: $commandsRaw"
                    emptyList()
                }
            }
            for (command in commands) {
                HoloRules.cmiSpecialPrefix(command)?.let { prefix ->
                    notes += "홀로그램 '$name' 의 클릭 명령 '$command' 의 CMI 접두 '$prefix' 는 우리가 모릅니다 — 콘솔 명령으로 그대로 돌아 실패합니다. 편집 화면에서 고치세요"
                }
                HoloRules.cmiLeftovers(command).takeIf { it.isNotEmpty() }?.let { left ->
                    notes += "홀로그램 '$name' 의 클릭 명령 '$command' 에 CMI 만의 자리 ${left.joinToString(" ")} 이(가) 남습니다 — CMI 를 빼면 글자 그대로 들어갑니다"
                }
            }
            if (!interactable && commands.isNotEmpty()) {
                notes += "홀로그램 '$name' 은(는) CMI 에서 누르기가 꺼져 있어 클릭 명령을 붙이지 않았습니다: ${commands.joinToString(" | ")}"
            }
            if (!interactable) {
                // CMI 는 누르기가 꺼진 홀로그램의 CText 명령도 돌리지 않았다 — 줄의 명령을 떼고 알린다.
                val dropped = pages.flatMap { pg -> pg.lines.flatMap { it.actions.map.values.flatten() } }
                if (dropped.isNotEmpty()) {
                    notes += "홀로그램 '$name' 은(는) CMI 에서 누르기가 꺼져 있어 줄의 명령(<C>)을 붙이지 않았습니다: ${dropped.joinToString(" | ")}"
                    pages = pages.map { pg -> pg.copy(lines = pg.lines.map { it.copy(actions = HoloActions.NONE) }) }
                }
            }
            val pagesWithActions = if (commands.isEmpty() || !interactable) pages
            else pages.map { it.copy(actions = HoloActions(mapOf(HoloClick.ANY to commands))) }
            if (pageButtons == true && pages.size > 1) {
                notes += "홀로그램 '$name' 의 쪽 넘김 버튼 줄(<<<< >>>>) 대신 '누르면 쪽 넘기기'(우클릭 다음 · 좌클릭 이전)를 켭니다"
            }
            if (armorStand) notes += "홀로그램 '$name' 은(는) CMI 에서 갑옷 거치대 방식이었습니다 — Display 엔티티로 세웁니다"
            top.get("NewDisplay")

            // 알지만 옮기지 않는 열쇠 — 값과 함께 알린다.
            settings.skip("Group", "LOS", "Save", "Light", "Dir", "Offset")
            text.skip("Double", "Fill")
            interaction.skip("Dimension", "Offset", "BasePrefix", "HoverPrefix")
            particle.skip("ShowClick", "Spacing", "Count", "Effect")
            hover.skip("Dimension", "Offset")
            icon.skip("Scale", "Dir", "Offset", "Bill")
            nd.skip("SL", "BL", "Pitch", "Double")
            legacyHover.skip("ShowHover", "Count", "ExtraHeight", "ExtraWidth", "OffsetX", "OffsetY", "Effect", "Spacing")
            val hasDownOrder = top.has("DownOrner")
            top.skip("Group", "Filler", "ShowParticles", "ShowHover", "Count", "ExtraHeight", "ExtraWidth", "OffsetX", "OffsetY", "Effect", "Spacing",
                "Animation", "Board", "DownOrner")
            if (top.has("Icon") && icon.s == null) top.skip("Icon")
            for (reader in listOf(top) + readers) {
                for ((path, value) in reader.skippedNotes()) notes += "홀로그램 '$name' 의 $path=$value 은(는) 옮기지 않았습니다(같은 기능이 없습니다)"
                val unknown = reader.leftovers()
                if (unknown.isNotEmpty()) unreadable += "홀로그램 '$rawName' 의 모르는 속성 ${unknown.entries.joinToString(", ") { "${it.key}=${it.value}" }}"
            }

            if (intervalSeconds != null && intervalSeconds > 0 && intervalSeconds < 1) notes += "홀로그램 '$name' 의 갱신 주기 ${intervalSeconds}초는 1초로 올립니다(우리 반복 작업은 1초 단위)"
            if (pageSecondsRaw != null && pageSecondsRaw > 0 && pageSecondsRaw < 1) notes += "홀로그램 '$name' 의 쪽 넘김 주기 ${pageSecondsRaw}초는 1초로 올립니다"
            if (perm) notes += "홀로그램 '$name' 은(는) 권한이 필요합니다 — ${HOLO_SEE_PREFIX}${HoloRules.keyOf(name)} 를 주세요"
            val pageSeconds = pageSecondsRaw?.let { if (it <= 0) 0 else kotlin.math.ceil(it).toInt().coerceAtLeast(1) }
            val scale = scaleW?.takeIf { it > 0 }?.coerceIn(0.1, 10.0) ?: 1.0
            val finalPages = pagesWithActions.ifEmpty { listOf(HoloPage()) }
            // 높이 — CMI 의 Loc 은 홀로그램의 **아래**다(getCenterLocation = Loc + 0.25 + 높이/2, 높이 = 첫 쪽 줄 수 × 0.25). 옛 모양(섹션 없음)은
            // CMI 가 읽을 때 Loc 을 높이 × 세로 크기만큼 내린다(DownOrner 가 없을 때). 우리 위치는 첫 줄의 윗자리라 가운데를 맞춘다.
            val legacy = settings.s == null
            val cmiHeight = finalPages.first().lines.size * CMI_LINE_HEIGHT
            val cmiLoc = if (legacy && !hasDownOrder) xyz[1]!! - cmiHeight * (scaleH ?: 1.0) else xyz[1]!!
            val ours = HologramSettings().let { d -> finalPages.first().lines.sumOf { if (it.isIcon) d.iconHeight else d.lineHeight } } * scale
            val topY = cmiLoc + CMI_LINE_HEIGHT + cmiHeight / 2 + ours / 2
            holograms += Hologram(
                name = name, world = world, x = xyz[0]!!, y = topY, z = xyz[2]!!,
                pages = finalPages,
                enabled = boolOf(top.get("Enabled")) ?: true,
                viewRange = viewRange?.takeIf { it > 0 }?.coerceIn(1, 256),
                updateSeconds = intervalSeconds?.let { if (it <= 0) 0 else kotlin.math.ceil(it).toInt().coerceAtLeast(1) },
                updateRange = updateRange?.coerceIn(0, 256),
                pageSeconds = pageSeconds,
                permission = if (perm) HOLO_SEE_PREFIX + HoloRules.keyOf(name) else null,
                billboard = bill?.takeIf { it in BILLBOARDS } ?: "CENTER",
                yaw = yaw?.toFloat() ?: 0f,
                scale = scale,
                background = background,
                shadow = shadow ?: true,
                seeThrough = seeThrough ?: false,
                clickPages = interactable || pageButtons == true,
                alignment = alignment ?: "CENTER",
                lineWidth = lineWidth?.coerceIn(1, 10_000) ?: Hologram.DEFAULT_LINE_WIDTH,
                textOpacity = textAlpha?.coerceIn(0, 255) ?: 255,
            )
        }
        return CmiHolograms(holograms, unreadable, notes)
    }

    /** CMI 기본값(javap — 기본값이면 serialize 가 쓰지 않으므로 없는 열쇠는 이것): `CMIHologramInteraction.interactable` · `CMIHologramTextSettings` 배경. */
    const val CMI_DEFAULT_INTERACTABLE = false
    const val CMI_DEFAULT_BACKGROUND = "000000"
    const val CMI_DEFAULT_BACKGROUND_ALPHA = 125

    /** CMI 한 줄 높이(`CMIHologram.getLineHeight` · `CMIHologramPages.getHeight` 의 줄마다 0.25). */
    const val CMI_LINE_HEIGHT = 0.25

    /** CMI 의 "보려면 권한 필요" 를 옮길 때의 노드(선언 안 된 노드 — op 는 저절로 가진다). */
    const val HOLO_SEE_PREFIX = "inmcessentials" + ".holograms.see."

    private fun numberOf(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }

    private fun intOf(v: Any?): Int? = numberOf(v)?.toInt()

    private fun boolOf(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is String -> v.trim().lowercase().toBooleanStrictOrNull()
        else -> null
    }
}

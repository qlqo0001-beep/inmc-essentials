package com.inmc.essentials.portal

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `portals.yml` 한 벌. [rejected] = 읽지 못한 칸의 원문(이름 → 값) — 지우지 않고 되써 넣는다(지뢰 13). [reasons] = 그 까닭(이름 → 한국어, 로그용 —
 * 저장하지 않는다).
 */
data class PortalFile(
    val portals: List<Portal> = emptyList(),
    val rejected: Map<String, Any?> = emptyMap(),
    val reasons: Map<String, String> = emptyMap(),
)

/** CMI 에서 읽은 것 — [unreadable] 은 원문 조각과 함께 보고서로, [notes] 는 옮기지 못한 속성. */
data class CmiPortals(val portals: List<Portal>, val unreadable: List<String>, val notes: List<String>)

/**
 * 포탈 YAML 읽기·쓰기 + CMI `Saves/Portals.yml` 판독. 서버 없이 돈다(`PortalCodecTest`).
 *
 * 우리 모양:
 * ```
 * portals:
 *   <이름>:
 *     world: world
 *     area: "x1;y1;z1;x2;y2;z2"
 *     target: "world;x;y;z;yaw;pitch"   # 없으면 명령만
 *     safe: …  enabled · permission · kick-back · inform-permission · commands-without-target · commands · effects · cooldown · sound
 *     particles: { enabled, type, color, amount, origin, shrink, range, without-permission }
 * ```
 */
object PortalCodec {

    const val ROOT = "portals"

    /**
     * [maxVolume] 를 넘거나 좌표가 월드 밖인 영역은 읽지 않는다(못 읽은 칸으로 남긴다) — 손으로 자릿수를 하나 더 치면 청크 색인이 메인에서
     * 수십억 칸을 짓다 서버가 멈추므로.
     */
    fun read(yaml: YamlConfiguration, maxVolume: Long = PortalSettings().maxVolume): PortalFile {
        val root = yaml.getConfigurationSection(ROOT) ?: return PortalFile()
        val portals = ArrayList<Portal>()
        val rejected = LinkedHashMap<String, Any?>()
        val reasons = LinkedHashMap<String, String>()
        for (name in root.getKeys(false)) {
            val section = root.getConfigurationSection(name)
            val portal = try {
                if (section == null) throw Bad("섹션이 아닙니다") else readOne(name, section, maxVolume)
            } catch (e: Bad) {
                reasons[name] = e.message.orEmpty()
                null
            } catch (e: RuntimeException) {
                reasons[name] = "읽다가 오류: $e"
                null
            }
            if (portal != null && portals.any { it.key == portal.key }) reasons[name] = "같은 이름(대소문자만 다름)이 이미 있습니다"
            if (portal == null || portals.any { it.key == portal.key }) {
                rejected[name] = root.get(name)?.let(::plain)
                continue
            }
            portals += portal
        }
        return PortalFile(portals, rejected, reasons)
    }

    private class Bad(message: String) : Exception(message)

    private fun readOne(name: String, s: ConfigurationSection, maxVolume: Long): Portal {
        if (!PortalRules.NAME.matches(name)) throw Bad("이름에 못 쓰는 글자가 있습니다")
        val world = s.getString("world")?.trim()?.takeIf { it.isNotEmpty() } ?: throw Bad("world 가 없습니다")
        val box = PortalBox.parse(world, s.getString("area")) ?: throw Bad("area 를 읽지 못했습니다: ${s.getString("area")}")
        boxProblem(box, maxVolume)?.let { throw Bad(it) }
        val p = s.getConfigurationSection("particles")
        val base = Portal.Particles()
        return Portal(
            name = name,
            box = box,
            target = spot(s, "target"),
            safe = spot(s, "safe"),
            enabled = s.getBoolean("enabled", true),
            permission = s.getBoolean("permission", false),
            kickBack = s.getBoolean("kick-back", true),
            informPermission = s.getBoolean("inform-permission", true),
            commandsWithoutTarget = s.getBoolean("commands-without-target", true),
            commands = s.getStringList("commands").filter { it.isNotBlank() },
            effects = s.getStringList("effects").filter { it.isNotBlank() },
            cooldown = s.getInt("cooldown", 0).coerceIn(0, 86_400),
            sound = s.getString("sound")?.trim()?.takeIf { it.isNotEmpty() },
            particles = if (p == null) base else Portal.Particles(
                enabled = p.getBoolean("enabled", base.enabled),
                type = p.getString("type")?.trim()?.takeIf { it.isNotEmpty() } ?: base.type,
                color = PortalRules.parseColor(p.getString("color")),
                amount = p.getInt("amount", base.amount).coerceIn(1, 500),
                origin = ParticleOrigin.parse(p.getString("origin")),
                shrink = p.getInt("shrink", base.shrink).coerceIn(0, 90),
                range = p.getInt("range", base.range).coerceIn(1, 128),
                withoutPermission = p.getBoolean("without-permission", base.withoutPermission),
            ),
        )
    }

    /** 있는데 못 읽는 자리는 못 읽은 칸으로(조용히 "없음" 이 되어 다음 저장에서 사라지지 않게). 비었으면 없음. */
    private fun spot(s: ConfigurationSection, key: String): PortalSpot? {
        val raw = s.getString(key)?.takeIf { it.isNotBlank() } ?: return null
        return PortalSpot.parse(raw) ?: throw Bad("$key 를 읽지 못했습니다: '$raw' (월드;x;y;z[;yaw;pitch])")
    }

    fun write(file: PortalFile): YamlConfiguration {
        val yaml = YamlConfiguration()
        val root = yaml.createSection(ROOT)
        for (portal in file.portals.sortedBy { it.key }) {
            val s = root.createSection(portal.name)
            s.set("world", portal.box.world)
            s.set("area", portal.box.text())
            portal.target?.let { s.set("target", it.text()) }
            portal.safe?.let { s.set("safe", it.text()) }
            s.set("enabled", portal.enabled)
            s.set("permission", portal.permission)
            s.set("kick-back", portal.kickBack)
            s.set("inform-permission", portal.informPermission)
            s.set("commands-without-target", portal.commandsWithoutTarget)
            s.set("commands", portal.commands)
            s.set("effects", portal.effects)
            s.set("cooldown", portal.cooldown)
            portal.sound?.let { s.set("sound", it) }
            val p = s.createSection("particles")
            p.set("enabled", portal.particles.enabled)
            p.set("type", portal.particles.type)
            portal.particles.color?.let { p.set("color", "#%06X".format(it)) }
            p.set("amount", portal.particles.amount)
            p.set("origin", portal.particles.origin.name)
            p.set("shrink", portal.particles.shrink)
            p.set("range", portal.particles.range)
            p.set("without-permission", portal.particles.withoutPermission)
        }
        for ((name, raw) in file.rejected) {
            if (root.contains(name)) continue
            if (raw is Map<*, *>) root.createSection(name, raw) else root.set(name, raw)
        }
        return yaml
    }

    /** 섹션을 중첩 Map 으로(되써 넣기용 — 다른 설정의 MemorySection 을 그대로 꽂지 않는다). */
    fun plain(value: Any?): Any? = when (value) {
        is ConfigurationSection -> value.getKeys(false).associateWith { plain(value.get(it)) }
        else -> value
    }

    /** 영역을 받을 수 없는 까닭(한국어) — 받아도 되면 null. */
    fun boxProblem(box: PortalBox, maxVolume: Long): String? = when (PortalRules.check(box, maxVolume)) {
        PortalRules.BoxCheck.OK -> null
        PortalRules.BoxCheck.TOO_BIG -> "영역이 ${box.volume}블록으로 max-volume($maxVolume)을 넘습니다: ${box.describe()}"
        PortalRules.BoxCheck.OUT_OF_BOUNDS -> "영역이 월드 경계(±${PortalRules.MAX_COORD}, 높이 ${PortalRules.MIN_Y}~${PortalRules.MAX_Y}) 밖이거나 " +
            "청크 ${box.chunkCount}개(최대 ${PortalRules.MAX_CHUNKS})에 걸칩니다: ${box.describe()}"
    }

    // --- CMI ------------------------------------------------------------------------------------

    /**
     * CMI 9.8.9.8 `Saves/Portals.yml`(jar `PortalManager.save` 로 확인): `<월드>.<포탈>` 아래 `Loc`(x1:y1:z1:x2:y2:z2) · `world` · `enabled` ·
     * `effect`(파티클 이름) · `color`(RGB 정수) · `size` · `showParticles` · `safeLoc` · `Tp` · `kickBack` · `cancelWarmup` · `particlesByPermission` ·
     * `requiresPerm` · `informOnMissingPerm` · `particleOrigin` · `particleAmount` · `particleHide` · `activationRange` · `commandsWithoutTp` ·
     * `commands` · (번지) `bungeeServer`·`bungeeLocation`·`exactBungeeLocation`. 위치 문자열은 `:`·`;` 둘 다 받는다.
     */
    /**
     * CMI 가 **기본값이면 쓰지 않는** 열쇠의 그 기본값(`CMIPortal.<init>` — javap): `PortalManager.save` 는 `informOnMissingPerm` 을 true 일 때만,
     * `particleAmount` 를 10 이 아닐 때만, `activationRange` 를 16 이 아닐 때만 쓴다. 없으면 우리 기본이 아니라 이것으로 읽어야 CMI 그대로다.
     */
    const val CMI_DEFAULT_INFORM = false
    const val CMI_DEFAULT_PARTICLE_AMOUNT = 10
    const val CMI_DEFAULT_ACTIVATION_RANGE = 16

    fun fromCmi(yaml: YamlConfiguration, maxVolume: Long = PortalSettings().maxVolume, particles: Collection<String>? = null): CmiPortals {
        val portals = ArrayList<Portal>()
        val unreadable = ArrayList<String>()
        val notes = ArrayList<String>()
        for (outer in yaml.getKeys(false)) {
            val outerSection = yaml.getConfigurationSection(outer)
            if (outerSection == null) {
                unreadable += "'$outer' 은(는) 섹션이 아닙니다: ${yaml.get(outer)}"
                continue
            }
            // 보통 <월드>.<포탈> 이지만, 혹시 포탈이 맨 위에 있으면(Loc 이 바로 있음) 그것 하나로 읽는다.
            val entries = if (outerSection.contains("Loc")) listOf(outer to outerSection)
            else outerSection.getKeys(false).mapNotNull { name -> outerSection.getConfigurationSection(name)?.let { name to it } }
            if (entries.isEmpty()) unreadable += "'$outer' 아래에 포탈이 없습니다: ${plain(outerSection)}"
            for ((name, s) in entries) {
                val world = s.getString("world")?.trim()?.takeIf { it.isNotEmpty() } ?: if (s !== outerSection) outer else null
                val box = world?.let { PortalBox.parse(it, s.getString("Loc")) }
                if (box == null) {
                    unreadable += "포탈 '$name' 의 영역(Loc='${s.getString("Loc")}', world='${s.getString("world")}')을 읽지 못했습니다"
                    continue
                }
                val problem = boxProblem(box, maxVolume)
                if (problem != null) {
                    unreadable += "포탈 '$name' 을(를) 넣지 않았습니다 — $problem (Loc='${s.getString("Loc")}')"
                    continue
                }
                val safeName = name.replace(Regex("[^A-Za-z0-9_\\-가-힣]"), "_").take(32)
                if (safeName != name) notes += "포탈 '$name' 의 이름을 '$safeName' 으로 바꿔 넣습니다(점·빈칸 등은 이름에 못 씁니다)"
                val tp = s.getString("Tp")?.takeIf { it.isNotBlank() }
                val target = tp?.let(PortalSpot::parse)
                if (tp != null && target == null) unreadable += "포탈 '$name' 의 목적지(Tp='$tp')를 읽지 못해 목적지 없이 넣습니다"
                val safeRaw = s.getString("safeLoc")?.takeIf { it.isNotBlank() }
                val safe = safeRaw?.let(PortalSpot::parse)
                if (safeRaw != null && safe == null) unreadable += "포탈 '$name' 의 안전 위치(safeLoc='$safeRaw')를 읽지 못했습니다"
                val base = Portal.Particles()
                // CMI 기본 명령 `cmi effect [playerName] blindness 2 1 -s` 는 CMI 를 빼면 실패한다 — 효과 한 줄로 바꾼다.
                val effects = ArrayList<String>()
                val commands = ArrayList<String>()
                val rawCommands = s.getStringList("commands")
                if (rawCommands.any { it.contains("[playerDisplayName]") }) {
                    notes += "포탈 '$name' 의 명령 속 [playerDisplayName] 은 {player}(이름)로 바꿉니다 — 표시 이름(닉네임)이 아닙니다"
                }
                for (raw in rawCommands.map { convertCmiCommand(it) }.filter { it.isNotBlank() }) {
                    PortalRules.cmiLeftovers(raw).takeIf { it.isNotEmpty() }?.let { left ->
                        notes += "포탈 '$name' 의 명령 '$raw' 에 CMI 만의 자리 ${left.joinToString(" ")} 이(가) 남습니다 — CMI 를 빼면 글자 그대로 들어갑니다. /포탈 명령 $safeName 으로 고치세요"
                    }
                    val effect = PortalRules.cmiEffectCommand(raw)
                    if (effect != null) {
                        effects += effect
                        notes += "포탈 '$name' 의 명령 '$raw' 을(를) 효과 '$effect' 로 바꿔 넣습니다(CMI 없이 돈다)"
                        continue
                    }
                    val prefix = PortalRules.cmiSpecialPrefix(raw)
                    if (prefix != null) {
                        notes += "포탈 '$name' 의 명령 '$raw' 의 CMI 접두 '$prefix' 는 우리 포탈이 모릅니다 — 콘솔 명령으로 그대로 돌아 실패합니다. /포탈 명령 $safeName 으로 고치세요"
                    }
                    commands += raw
                }
                val effectRaw = s.getString("effect")?.trim()?.takeIf { it.isNotEmpty() }
                val particleType = when {
                    effectRaw == null -> base.type
                    particles == null -> effectRaw.substringBefore(';').trim().replace(' ', '_').uppercase()
                    else -> PortalRules.particleName(effectRaw, particles) ?: base.type.also {
                        notes += "포탈 '$name' 의 파티클 '$effectRaw' 을(를) 알아보지 못해 ${base.type} 로 넣습니다 — 편집 화면에서 고르세요"
                    }
                }
                if (s.getString("bungeeServer")?.isNotBlank() == true) notes += "포탈 '$name' 의 번지 서버 이동(bungeeServer=${s.getString("bungeeServer")})은 옮기지 않습니다"
                if (s.getBoolean("cancelWarmup", false)) notes += "포탈 '$name' 의 '나가면 명령 대기 취소'(cancelWarmup)는 없습니다"
                if (s.contains("size")) notes += "포탈 '$name' 의 파티클 크기(size=${s.get("size")})는 옮기지 않습니다"
                val known = setOf(
                    "loc", "world", "enabled", "effect", "color", "size", "showparticles", "safeloc", "tp", "kickback", "cancelwarmup",
                    "particlesbypermission", "requiresperm", "informonmissingperm", "particleorigin", "particleamount", "particlehide",
                    "activationrange", "commandswithouttp", "commands", "bungeeserver", "bungeelocation", "exactbungeelocation",
                )
                val unknown = s.getKeys(false).filter { it.lowercase() !in known }
                if (unknown.isNotEmpty()) unreadable += "포탈 '$name' 의 모르는 속성 ${unknown.joinToString(", ") { "$it=${s.get(it)}" }}"
                portals += Portal(
                    name = safeName,
                    box = box,
                    target = target,
                    safe = safe,
                    enabled = s.getBoolean("enabled", true),
                    permission = s.getBoolean("requiresPerm", false),
                    kickBack = s.getBoolean("kickBack", true),
                    informPermission = s.getBoolean("informOnMissingPerm", CMI_DEFAULT_INFORM),
                    commandsWithoutTarget = s.getBoolean("commandsWithoutTp", true),
                    commands = commands,
                    effects = effects,
                    particles = Portal.Particles(
                        enabled = s.getBoolean("showParticles", true),
                        type = particleType,
                        color = s.get("color")?.toString()?.toIntOrNull()?.and(0xFFFFFF),
                        amount = (if (s.contains("particleAmount")) s.getInt("particleAmount") else PortalRules.particleAmount(effectRaw) ?: CMI_DEFAULT_PARTICLE_AMOUNT).coerceIn(1, 500),
                        origin = ParticleOrigin.parse(s.getString("particleOrigin")),
                        shrink = s.getInt("particleHide", 0).coerceIn(0, 90),
                        range = s.getInt("activationRange", CMI_DEFAULT_ACTIVATION_RANGE).coerceIn(1, 128),
                        withoutPermission = !s.getBoolean("particlesByPermission", false),
                    ),
                )
            }
        }
        return CmiPortals(portals, unreadable, notes)
    }

    /** CMI 명령 → 우리 명령: `[playerName]` → `{player}`, `asPlayer!`/`asConsole!` 접두는 [PortalRules.parseCommand] 가 그대로 읽는다. */
    fun convertCmiCommand(raw: String): String =
        raw.trim().replace("[playerName]", "{player}").replace("[playerDisplayName]", "{player}")
}

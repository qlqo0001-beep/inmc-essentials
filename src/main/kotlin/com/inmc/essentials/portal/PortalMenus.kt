package com.inmc.essentials.portal

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.service.TeleportKind
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

/** `/포탈` · `/포탈 목록` — 가까운 순. 클릭 = 편집, Shift+우클릭 = 포탈로 이동. */
class PortalListMenu(ess: Ess, viewer: Player, private val service: PortalService, private var page: Int = 0) :
    Menu(ess, viewer, SIZE, "<dark_gray>포탈</dark_gray>") {

    override fun draw() {
        clear()
        val portals = service.byDistance(viewer.location)
        page = Paging.clamp(page, portals.size)
        for ((index, portal) in Paging.slice(portals, page).withIndex()) {
            val material = when {
                !portal.enabled -> Material.GRAY_DYE
                portal.target != null -> Material.ENDER_PEARL
                else -> Material.COMMAND_BLOCK
            }
            val lore = listOf(
                "<gray>${portal.box.describe()}</gray>",
                "<gray>목적지: <white>${portal.target?.describe() ?: "없음(명령만)"}</white></gray>",
                "<gray>명령 ${portal.commands.size}개 · 쿨타임 ${portal.cooldown}초 · ${if (portal.permission) "권한 필요" else "누구나"}</gray>",
                "",
                "<yellow>▶ 클릭: 편집</yellow>",
                "<yellow>▶ Shift+우클릭: 포탈로 이동</yellow>",
            )
            set(index, Icon.of(material, (if (portal.enabled) "<aqua>" else "<gray>") + portal.name, lore)) { event ->
                if (event.isShiftClick && event.isRightClick) {
                    viewer.closeInventory()
                    val at = service.center(portal) ?: return@set
                    ess.teleporter.teleport(viewer, at, TeleportKind.ADMIN)
                } else {
                    PortalEditMenu(ess, viewer, service, portal.name, back = { PortalListMenu(ess, viewer, service, page).show() }).show()
                }
            }
        }
        if (portals.isEmpty()) set(SLOT_EMPTY, Icon.of(Material.PAPER, "<gray>포탈이 없습니다</gray>", "<gray>/포탈 만들기 <이름></gray>"))
        for (slot in Paging.PER_PAGE until SIZE) set(slot, Icon.FILLER)
        pager(page, portals.size) { page = it; refresh() }
        set(SLOT_INFO, Icon.of(Material.BOOK, "<gold>포탈 ${portals.size}개</gold>",
            "<gray>/포탈 위치1 · 위치2 로 찍거나(먼저) WorldEdit 선택</gray>", "<gray>/포탈 만들기 <이름></gray>"))
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    companion object {
        const val SIZE = 54
        const val SLOT_EMPTY = 22
        const val SLOT_INFO = 49
    }
}

/** 포탈 편집 — CMI 포탈 편집기의 항목(권한·밀어내기·알림·파티클·범위·명령·목적지·안전 위치·영역 다시 정하기·이동·지우기). 바꾸면 바로 저장. */
/** 효과 한 줄(`blindness:2:1`)을 사람이 읽는 글로 — 한국어 효과 이름 · 초 · 단계. */
private fun effectLabel(raw: String): String {
    val spec = PortalRules.parseEffect(raw) ?: return raw
    return kr.inmc.core.util.PotionEffects.lang(spec.type) + " " + spec.seconds + "초 · " + spec.level + "단계"
}

/**
 * 포탈의 효과·소리 — 효과는 고르는 화면으로 더하고(한국어 이름 · 우클릭 미리보기), 칸을 눌러 초·단계, Shift+우클릭으로 뺀다(사용자 요청 2026-10-14).
 * 저장 모양은 그대로(`효과:초:단계` 줄).
 */
class PortalEffectsMenu(
    ess: Ess,
    viewer: Player,
    private val service: PortalService,
    private val name: String,
    override val back: (() -> Unit)? = null,
) : Menu(ess, viewer, 54, "<dark_gray>포탈 효과 — $name</dark_gray>") {

    private fun current(): Portal? = service.store.get(name)

    private fun save(change: (Portal) -> Portal) {
        val portal = current() ?: return viewer.closeInventory()
        if (!service.editable(viewer)) return
        service.store.put(change(portal))
    }

    override fun draw() {
        clear()
        val portal = current() ?: return set(PortalEditMenu.SLOT_INFO, Icon.of(Material.BARRIER, "<red>지워진 포탈입니다</red>"))
        for ((index, raw) in portal.effects.withIndex().take(SLOT_ADD)) {
            val spec = PortalRules.parseEffect(raw) ?: continue
            set(index, Icon.of(Material.POTION, "<yellow>" + kr.inmc.core.util.PotionEffects.lang(spec.type) + "</yellow>", listOf(
                "<gray>" + spec.seconds + "초 · " + spec.level + "단계</gray>", "<dark_gray>" + raw + "</dark_gray>", "",
                "<yellow>▶ 클릭: 초·단계 바꾸기</yellow>", "<red>▶ Shift+우클릭: 빼기</red>",
            ))) { event ->
                if (event.isShiftClick && event.isRightClick) {
                    save { it.copy(effects = it.effects.filterIndexed { j, _ -> j != index }) }
                    refresh()
                    return@set
                }
                ask(DialogForm("<yellow>" + spec.type + "</yellow>").long("seconds", "초", spec.seconds.toLong(), 1, 86_400).long("level", "단계", spec.level.toLong(), 1, 255)) { v ->
                    val line = spec.type + ":" + (v.long("seconds") ?: spec.seconds.toLong()) + ":" + (v.long("level") ?: spec.level.toLong())
                    save { it.copy(effects = it.effects.mapIndexed { j, old -> if (j == index) line else old }) }
                }
            }
        }
        set(SLOT_ADD, Icon.of(Material.BREWING_STAND, "<green>효과 더하기</green>", listOf("<gray>모든 효과를 한국어 이름으로 고릅니다(2초 · 1단계).</gray>", "<gray>더한 뒤 그 칸을 눌러 초·단계를 바꿉니다.</gray>"))) {
            kr.inmc.core.gui.PotionPickMenu(ess, viewer, "포탈 효과 더하기", back = { show() }) { type ->
                save { it.copy(effects = it.effects + (kr.inmc.core.util.PotionEffects.id(type) + ":2:1")) }
                show()
            }.show()
        }
        set(SLOT_SOUND, valueIcon(Material.NOTE_BLOCK, "소리", portal.sound ?: "없음", "<gray>예: entity.enderman.teleport · 비우면 없음</gray>")) {
            ask(DialogForm("<yellow>소리</yellow>").text("sound", "소리(비우면 없음)", portal.sound.orEmpty(), 128)) { v ->
                save { it.copy(sound = v.text("sound").trim().takeIf { s -> s.isNotEmpty() }) }
            }
        }
        navigation()
    }

    companion object {
        /** 효과 칸은 0~44(위 다섯 줄) — 그 뒤가 버튼 줄. */
        const val SLOT_ADD = 48
        const val SLOT_SOUND = 50
    }
}

class PortalEditMenu(
    ess: Ess,
    viewer: Player,
    private val service: PortalService,
    private val name: String,
    override val back: (() -> Unit)? = null,
) : Menu(ess, viewer, SIZE, "<dark_gray>포탈 편집 — $name</dark_gray>") {

    private val store get() = service.store

    private fun current(): Portal? = store.get(name)

    private fun save(change: (Portal) -> Portal) {
        val portal = current() ?: return viewer.closeInventory()
        if (!service.editable(viewer)) return
        store.put(change(portal))
        refresh()
    }

    override fun draw() {
        clear()
        val portal = current() ?: return set(SLOT_INFO, Icon.of(Material.BARRIER, "<red>지워진 포탈입니다</red>"))
        val p = portal.particles
        fillEmpty(Icon.FILLER)

        set(SLOT_ENABLED, toggleIcon("켜짐", portal.enabled, "<gray>꺼진 포탈은 그냥 영역입니다.</gray>")) { save { it.copy(enabled = !it.enabled) } }
        set(SLOT_PERMISSION, toggleIcon("권한 필요", portal.permission, "<gray>${PortalRules.node(portal)}</gray>")) { save { it.copy(permission = !it.permission) } }
        set(SLOT_KICKBACK, toggleIcon("권한 없으면 밀어내기", portal.kickBack, "<gray>안전 위치가 있으면 그리로 보냅니다.</gray>")) { save { it.copy(kickBack = !it.kickBack) } }
        set(SLOT_INFORM, toggleIcon("권한 없음 알리기", portal.informPermission)) { save { it.copy(informPermission = !it.informPermission) } }
        set(SLOT_WITHOUT_TARGET, toggleIcon("목적지 없이 명령", portal.commandsWithoutTarget, "<gray>목적지가 없을 때도 명령·효과를 돕니다.</gray>")) {
            save { it.copy(commandsWithoutTarget = !it.commandsWithoutTarget) }
        }
        set(SLOT_COOLDOWN, valueIcon(Material.CLOCK, "쿨타임", "${portal.cooldown}초")) {
            ask(DialogForm("<yellow>쿨타임</yellow>").long("v", "초(0 = 없음)", portal.cooldown.toLong(), 0, 86_400)) { v ->
                save { it.copy(cooldown = (v.long("v") ?: 0).toInt()) }
            }
        }
        set(SLOT_EFFECTS, valueIcon(Material.POTION, "효과·소리", "${portal.effects.size}개 · ${portal.sound ?: "소리 없음"}",
            *portal.effects.map { "<gray>- " + effectLabel(it) + "</gray>" }.toTypedArray())) {
            // 효과는 고르는 화면으로(한국어 이름 — 사용자 요청 2026-10-14). 소리는 그 화면의 칸에서.
            PortalEffectsMenu(ess, viewer, service, name, back = { show() }).show()
        }

        set(SLOT_PARTICLES, toggleIcon("파티클", p.enabled)) { save { it.copy(particles = it.particles.copy(enabled = !it.particles.enabled)) } }
        // 입자는 한국어 이름 고르기(미리보기), 먼지 색은 색 고르기 — 이름·#RRGGBB 를 치던 것 대신(사용자 요청 2026-10-14).
        set(SLOT_PARTICLE_TYPE, valueIcon(Material.BLAZE_POWDER, "파티클 종류", kr.inmc.core.util.Particles.name(p.type) + (p.color?.let { " <color:#%06X>■</color>".format(it) } ?: ""),
            "<gray>좌클릭: 입자 고르기 · 우클릭: DUST(먼지) 색 고르기</gray>")) { event ->
            if (event.isRightClick) {
                kr.inmc.core.gui.ColorPickMenu(ess, viewer, "포탈 먼지 색", current = p.color?.let { "#%06X".format(it) }, allowNone = true, back = { show() }) { hex ->
                    save { it.copy(particles = it.particles.copy(color = PortalRules.parseColor(hex))) }
                    show()
                }.show()
                return@set
            }
            kr.inmc.core.gui.ParticlePickMenu(ess, ess.plugin, viewer, "포탈 입자", current = p.type, back = { show() }) { particle ->
                // 자료가 필요한 입자는 먼지(DUST)만 받는다(뿌릴 때 몰래 PORTAL 로 바뀌지 않게) — 옛 값을 그대로 둔다.
                val type = PortalRules.particleName(particle.name, PortalService.usableParticles())
                if (type == null) ess.messages.send(viewer, "portals-particle-invalid", Ph.of().value(kr.inmc.core.util.Particles.name(particle)))
                else save { it.copy(particles = it.particles.copy(type = type)) }
                show()
            }.show()
        }
        set(SLOT_PARTICLE_AMOUNT, valueIcon(Material.GLOWSTONE_DUST, "파티클 수", "${p.amount}", "<gray>1초에 보는 사람마다.</gray>")) {
            ask(DialogForm("<yellow>파티클 수</yellow>").long("v", "수", p.amount.toLong(), 1, 500)) { v ->
                save { it.copy(particles = it.particles.copy(amount = (v.long("v") ?: 20).toInt())) }
            }
        }
        set(SLOT_PARTICLE_ORIGIN, valueIcon(Material.COMPASS, "파티클 자리", p.origin.label)) {
            save { it.copy(particles = it.particles.copy(origin = it.particles.origin.next())) }
        }
        set(SLOT_PARTICLE_SHRINK, valueIcon(Material.SHEARS, "면 줄이기", "${p.shrink}%")) {
            ask(DialogForm("<yellow>면 줄이기</yellow>").long("v", "%", p.shrink.toLong(), 0, 90)) { v ->
                save { it.copy(particles = it.particles.copy(shrink = (v.long("v") ?: 0).toInt())) }
            }
        }
        set(SLOT_PARTICLE_RANGE, valueIcon(Material.SPYGLASS, "보이는 거리", "${p.range}블록")) {
            ask(DialogForm("<yellow>보이는 거리</yellow>").long("v", "블록", p.range.toLong(), 1, 128)) { v ->
                save { it.copy(particles = it.particles.copy(range = (v.long("v") ?: 8).toInt())) }
            }
        }
        set(SLOT_PARTICLE_PERMISSION, toggleIcon("권한 없어도 파티클", p.withoutPermission)) {
            save { it.copy(particles = it.particles.copy(withoutPermission = !it.particles.withoutPermission)) }
        }

        set(SLOT_COMMANDS, valueIcon(Material.COMMAND_BLOCK, "명령", "${portal.commands.size}개",
            *(portal.commands.take(8).map { "<gray>- $it</gray>" } + listOf("<dark_gray>player: 로 시작 = 그 사람으로 · {player}</dark_gray>")).toTypedArray())) {
            ask(DialogForm("<yellow>명령</yellow>").line("<gray>한 줄에 하나. 앞에 player: 를 붙이면 그 사람이, 아니면 콘솔이 칩니다. {player} · PAPI</gray>")
                .text("commands", "명령", portal.commands.joinToString("\n"), 4096, multiline = true)) { v ->
                val lines = v.text("commands").lines().map { l -> l.trim() }.filter { l -> PortalRules.parseCommand(l) != null }
                // 콘솔 권한이 없으면 콘솔 명령을 새로 넣거나 바꿀 수 없다(지우기·순서 바꾸기는 된다) — 지금 저장된 것과 견준다.
                if (!service.mayAddConsole(viewer, current()?.commands.orEmpty(), lines)) return@ask
                save { it.copy(commands = lines) }
            }
        }
        set(SLOT_TARGET, Icon.of(Material.ENDER_EYE, "<yellow>목적지: </yellow><white>${portal.target?.describe() ?: "없음"}</white>",
            "", "<yellow>▶ 클릭: 지금 선 자리로</yellow>", "<red>▶ 우클릭: 지우기</red>")) { event ->
            save { it.copy(target = if (event.isRightClick) null else PortalSpot.of(viewer.location)) }
        }
        set(SLOT_SAFE, Icon.of(Material.SHIELD, "<yellow>안전 위치: </yellow><white>${portal.safe?.describe() ?: "없음 — 밀어내기"}</white>",
            "<gray>권한이 없을 때 보낼 자리.</gray>", "", "<yellow>▶ 클릭: 지금 선 자리로</yellow>", "<red>▶ 우클릭: 지우기</red>")) { event ->
            save { it.copy(safe = if (event.isRightClick) null else PortalSpot.of(viewer.location)) }
        }
        set(SLOT_AREA, Icon.of(Material.WOODEN_AXE, "<yellow>영역 다시 정하기</yellow>", "<gray>${portal.box.describe()}</gray>",
            "<gray>/포탈 위치1 · 위치2 로 찍은 두 모서리(먼저) · 없으면 WorldEdit 선택</gray>", "", "<yellow>▶ 클릭: 지금 선택으로</yellow>")) {
            if (!service.editable(viewer)) return@set
            val selected = service.selection(viewer) ?: return@set ess.messages.send(viewer, "portals-no-selection")
            val box = selected.box
            if (!PortalCommands.fits(ess, service, viewer, box, name)) return@set
            save { it.copy(box = box) }
            service.clearCorners(viewer)
            service.mark(viewer, viewer.location)
            ess.messages.send(viewer, "portals-redefined", Ph.of().raw("{포탈}", name).value(box.describe()).raw("{출처}", service.sourceLabel(selected)))
        }
        set(SLOT_GO_PORTAL, Icon.of(Material.ENDER_PEARL, "<yellow>포탈로 이동</yellow>")) { go(service.center(portal)) }
        set(SLOT_GO_TARGET, Icon.of(Material.ENDER_PEARL, "<yellow>목적지로 이동</yellow>")) { go(portal.target?.toLocation()) }
        set(SLOT_INFO, Icon.of(Material.BOOK, "<gold>${portal.name}</gold>",
            "<gray>${portal.box.describe()} · ${portal.box.volume}블록</gray>",
            "<gray>권한 노드: ${PortalRules.node(portal)}</gray>"))
        set(SLOT_DELETE, Icon.of(Material.LAVA_BUCKET, "<red>포탈 지우기</red>")) {
            ConfirmMenu(ess, "<red>포탈 ${portal.name} 을(를) 지울까요?</red>", onConfirm = {
                if (!service.editable(viewer)) show()
                else {
                    store.remove(portal.name)
                    ess.messages.send(viewer, "portals-removed", Ph.of().raw("{포탈}", portal.name))
                    back?.invoke() ?: viewer.closeInventory()
                }
            }, onCancel = { show() }).open(viewer)
        }
        navigation()
    }

    private fun go(where: Location?) {
        if (where == null) return ess.messages.send(viewer, "portals-target-missing", Ph.of().raw("{포탈}", name).world("?"))
        viewer.closeInventory()
        ess.teleporter.teleport(viewer, where, TeleportKind.ADMIN)
    }

    companion object {
        const val SIZE = 54
        const val SLOT_ENABLED = 10
        const val SLOT_PERMISSION = 11
        const val SLOT_KICKBACK = 12
        const val SLOT_INFORM = 13
        const val SLOT_WITHOUT_TARGET = 14
        const val SLOT_COOLDOWN = 15
        const val SLOT_EFFECTS = 16
        const val SLOT_PARTICLES = 19
        const val SLOT_PARTICLE_TYPE = 20
        const val SLOT_PARTICLE_AMOUNT = 21
        const val SLOT_PARTICLE_ORIGIN = 22
        const val SLOT_PARTICLE_SHRINK = 23
        const val SLOT_PARTICLE_RANGE = 24
        const val SLOT_PARTICLE_PERMISSION = 25
        const val SLOT_COMMANDS = 28
        const val SLOT_TARGET = 29
        const val SLOT_SAFE = 30
        const val SLOT_AREA = 31
        const val SLOT_GO_PORTAL = 32
        const val SLOT_GO_TARGET = 33
        const val SLOT_INFO = 4
        const val SLOT_DELETE = 49
    }
}

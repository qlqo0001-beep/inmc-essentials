package com.inmc.essentials.decor

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import com.inmc.essentials.util.Ph
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.PickMenu
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.EulerAngle

/**
 * 갑옷 거치대 편집기(CMI armorstand 편집 화면). 자세는 좌클릭 +, 우클릭 −, Shift 와 함께 10배, Q(버리기 키) 0 으로.
 * 장비 칸은 커서에 든 것과 맞바꾼다(바닐라와 같은 한 번의 교환 — 한 틱 뒤에 커서와 칸이 그대로이고 이 화면이 아직 열려 있는지 다시 보고 바꾼다).
 * 바꾸기 전에 바닐라와 같은 `PlayerArmorStandManipulateEvent` 를 쏜다 — 보호 구역(Lands)·기록(CoreProtect)이 보고, 막으면 안 바꾼다.
 */
class ArmorStandMenu(
    ess: Ess,
    viewer: Player,
    private val service: ArmorStands,
    private val stand: ArmorStand,
    private val settings: () -> DecorSettings,
) : Menu(ess, viewer, SIZE, "<dark_gray>갑옷 거치대 편집</dark_gray>") {

    private enum class Part(val label: String, val material: Material) {
        HEAD("머리", Material.LEATHER_HELMET), BODY("몸통", Material.LEATHER_CHESTPLATE), LEFT_ARM("왼팔", Material.STICK), RIGHT_ARM("오른팔", Material.BLAZE_ROD),
        LEFT_LEG("왼다리", Material.LEATHER_LEGGINGS), RIGHT_LEG("오른다리", Material.CHAINMAIL_LEGGINGS),
    }

    private fun has(node: String) = viewer.hasPermission(node)

    override fun draw() {
        clear()
        if (!stand.isValid) {
            viewer.closeInventory()
            return
        }
        // 입력창·고르기 화면을 다녀오면(닫힘 → 다시 열림) 편집 자리를 다시 잡는다.
        service.touch(viewer, stand)
        for (slot in 0 until SIZE) set(slot, Icon.FILLER)
        val rot = settings().armorStand.rotateStep
        Part.entries.forEachIndexed { i, part ->
            val base = PART_SLOTS[i]
            val angles = ArmorStands.deg(get(part))
            listOf("X" to angles.x, "Y" to angles.y, "Z" to angles.z).forEachIndexed { axis, (name, value) ->
                set(base + axis, Icon.of(part.material, "<yellow>${part.label} $name: </yellow><white>${fmt(value)}°</white>", poseLore(rot))) { e ->
                    if (!has(DecorFeature.ARMORSTAND_POSE)) return@set deny()
                    val now = ArmorStands.deg(get(part))
                    val d = if (e.click == ClickType.DROP || e.click == ClickType.CONTROL_DROP) null else Pose.delta(rot, e.isRightClick, e.isShiftClick)
                    val v = listOf(now.x, now.y, now.z).toMutableList()
                    v[axis] = if (d == null) 0.0 else Pose.wrap(v[axis] + d)
                    put(part, StandTemplate.Angles(v[0], v[1], v[2]))
                    refresh()
                }
            }
        }

        set(SLOT_ROTATE, Icon.of(Material.COMPASS, "<yellow>몸 방향: </yellow><white>${fmt(Pose.wrap(stand.location.yaw.toDouble()))}°</white>", poseLore(rot))) { e ->
            if (!has(DecorFeature.ARMORSTAND_POSE)) return@set deny()
            val loc = stand.location
            loc.yaw = if (e.click == ClickType.DROP) 0f else Pose.wrap(loc.yaw + Pose.delta(rot, e.isRightClick, e.isShiftClick)).toFloat()
            stand.setRotation(loc.yaw, loc.pitch)
            refresh()
        }

        val step = settings().armorStand.moveStep
        listOf(Triple("X", Material.RED_CONCRETE, 0), Triple("Y", Material.LIME_CONCRETE, 1), Triple("Z", Material.LIGHT_BLUE_CONCRETE, 2)).forEachIndexed { i, (name, mat, axis) ->
            val value = when (axis) { 0 -> stand.location.x; 1 -> stand.location.y; else -> stand.location.z }
            val lore = listOf("<gray>좌클릭 +${fmt(step)} · 우클릭 −${fmt(step)}</gray>", "<gray>Shift 와 함께 10배</gray>") +
                (if (axis == 1 && stand.hasGravity()) listOf("<red>중력을 끄면 위아래로 옮길 수 있습니다</red>") else emptyList())
            set(SLOT_MOVE + i, Icon.of(mat, "<yellow>위치 $name: </yellow><white>${fmt(value)}</white>", lore)) { e ->
                if (!has(DecorFeature.ARMORSTAND_MOVE)) return@set deny()
                val d = step * (if (e.isRightClick) -1 else 1) * (if (e.isShiftClick) 10 else 1)
                when (service.move(viewer, stand, if (axis == 0) d else 0.0, if (axis == 1) d else 0.0, if (axis == 2) d else 0.0)) {
                    ArmorStands.Move.TOO_FAR -> ess.messages.send(viewer, "decor-as-too-far", Ph.of().value(fmt(settings().armorStand.maxMove)))
                    ArmorStands.Move.PROTECTED -> ess.messages.send(viewer, "decor-as-move-protected")
                    ArmorStands.Move.OK -> Unit
                }
                refresh()
            }
        }

        val range = service.scaleRange(viewer)
        set(SLOT_SCALE, Icon.of(Material.SLIME_BALL, "<yellow>크기: </yellow><white>${fmt(service.scaleOf(stand))}</white>",
            "<gray>좌클릭 +0.05 · 우클릭 −0.05 · Shift 10배 · Q 1로</gray>", "<dark_gray>${fmt(range.start)} ~ ${fmt(range.endInclusive)}</dark_gray>")) { e ->
            if (!has(DecorFeature.ARMORSTAND_SCALE)) return@set deny()
            val now = service.scaleOf(stand)
            val next = if (e.click == ClickType.DROP) 1.0 else now + 0.05 * (if (e.isRightClick) -1 else 1) * (if (e.isShiftClick) 10 else 1)
            service.setScale(stand, Pose.scale(next, range.start, range.endInclusive))
            refresh()
        }

        // --- 켜고 끄기 ---
        toggle(SLOT_TOGGLES + 0, "받침대", stand.hasBasePlate(), DecorFeature.ARMORSTAND_TOGGLE) { stand.setBasePlate(!stand.hasBasePlate()) }
        toggle(SLOT_TOGGLES + 1, "작게", stand.isSmall, DecorFeature.ARMORSTAND_TOGGLE) { stand.isSmall = !stand.isSmall }
        toggle(SLOT_TOGGLES + 2, "보이기", stand.isVisible, DecorFeature.ARMORSTAND_TOGGLE) { stand.isVisible = !stand.isVisible }
        toggle(SLOT_TOGGLES + 3, "팔", stand.hasArms(), DecorFeature.ARMORSTAND_TOGGLE) { stand.setArms(!stand.hasArms()) }
        toggle(SLOT_TOGGLES + 4, "중력", stand.hasGravity(), DecorFeature.ARMORSTAND_TOGGLE) { stand.setGravity(!stand.hasGravity()) }
        toggle(SLOT_TOGGLES + 5, "발광", stand.isGlowing, DecorFeature.ARMORSTAND_TOGGLE) { stand.isGlowing = !stand.isGlowing }
        toggle(SLOT_TOGGLES + 6, "무적", stand.isInvulnerable, DecorFeature.ARMORSTAND_INVULNERABLE) { stand.isInvulnerable = !stand.isInvulnerable }
        toggle(SLOT_TOGGLES + 7, "이름 보이기", stand.isCustomNameVisible, DecorFeature.ARMORSTAND_NAME) { stand.isCustomNameVisible = !stand.isCustomNameVisible }
        // 장비 잠금 — 잠그기는 켜고 끄기 권한, 풀기는 .any 만(관리자가 잠근 전시용 거치대).
        val locked = service.isLocked(stand)
        set(SLOT_TOGGLES + 8, toggleIcon("장비 잠금", locked)) {
            val now = service.isLocked(stand)
            if (!ArmorStands.lockToggleAllowed(now, has(DecorFeature.ARMORSTAND_TOGGLE), has(DecorFeature.ARMORSTAND_ANY))) return@set deny()
            service.setLocked(stand, !now)
            refresh()
        }

        // --- 장비 ---
        equip(SLOT_HELMET, EquipmentSlot.HEAD, "머리", DecorFeature.ARMORSTAND_EQUIP)
        equip(SLOT_CHEST, EquipmentSlot.CHEST, "몸", DecorFeature.ARMORSTAND_EQUIP)
        equip(SLOT_LEGS, EquipmentSlot.LEGS, "다리", DecorFeature.ARMORSTAND_EQUIP)
        equip(SLOT_FEET, EquipmentSlot.FEET, "발", DecorFeature.ARMORSTAND_EQUIP)
        equip(SLOT_HAND, EquipmentSlot.HAND, "주 손", DecorFeature.ARMORSTAND_HANDS)
        equip(SLOT_OFFHAND, EquipmentSlot.OFF_HAND, "보조 손", DecorFeature.ARMORSTAND_OFFHAND)

        // --- 아래 줄 ---
        set(SLOT_COPY, Icon.of(Material.PAPER, "<aqua>모양 복사</aqua>", "<gray>자세·켜고 끄기·크기를 기억합니다(장비 빼고).</gray>")) {
            if (!has(DecorFeature.ARMORSTAND_COPY)) return@set deny()
            service.copy(viewer, stand)
            ess.messages.send(viewer, "decor-as-copied")
        }
        val clip = service.clipboardOf(viewer)
        set(SLOT_PASTE, Icon.of(if (clip != null) Material.MAP else Material.GRAY_DYE, "<aqua>모양 붙여넣기</aqua>",
            if (clip != null) "<gray>복사한 모양을 이 거치대에 입힙니다.</gray>" else "<dark_gray>먼저 다른 거치대에서 복사하세요.</dark_gray>")) {
            if (!has(DecorFeature.ARMORSTAND_COPY)) return@set deny()
            val t = service.clipboardOf(viewer) ?: return@set ess.messages.send(viewer, "decor-as-nothing-copied")
            service.apply(stand, t, viewer)
            ess.messages.send(viewer, "decor-as-updated")
            refresh()
        }
        set(SLOT_TEMPLATE_SAVE, Icon.of(Material.WRITABLE_BOOK, "<gold>템플릿으로 저장</gold>", "<gray>이 모양을 이름 붙여 저장합니다.</gray>")) {
            if (!has(DecorFeature.ARMORSTAND_TEMPLATE_SAVE)) return@set deny()
            ask(DialogForm("<gold>템플릿 저장</gold>").line("<gray>소문자 영문·숫자·한글·_ - (32자)</gray>").text("name", "이름", "", 32)) { v ->
                val name = v.text("name").trim().lowercase()
                if (!StandTemplate.NAME.matches(name)) return@ask ess.messages.send(viewer, "decor-as-template-bad-name", Ph.of().value(name))
                service.saveTemplate(name, stand)
                ess.messages.send(viewer, "decor-as-template-saved", Ph.of().raw("{이름}", name))
            }
        }
        set(SLOT_TEMPLATE_LIST, Icon.of(Material.BOOKSHELF, "<gold>템플릿</gold> <gray>(${service.templates.size})</gray>",
            "<gray>클릭: 이 거치대에 입히기 · 꺼내기는 /갑옷거치대 템플릿 <이름></gray>")) {
            // 템플릿 입히기는 붙여넣기와 같다 — 같은 권한(.copy). 칸마다의 권한은 apply 가 거른다.
            if (!has(DecorFeature.ARMORSTAND_COPY)) return@set deny()
            if (service.templates.isEmpty()) return@set ess.messages.send(viewer, "decor-as-template-none")
            val names = service.templates.keys.filter { service.mayUseTemplate(viewer, it) }.sorted()
            PickMenu(ess, viewer, "<gold>템플릿 고르기</gold>", names, { Icon.of(Material.ARMOR_STAND, "<white>$it</white>") }, back = { show() }) { name ->
                service.templates[name]?.let { service.apply(stand, it, viewer) }
                ess.messages.send(viewer, "decor-as-updated")
                show()
            }.show()
        }
        set(SLOT_NAME, Icon.of(Material.NAME_TAG, "<yellow>이름</yellow>", "<gray>지금: </gray>" + (stand.customName()?.let { DecorText.toMini(it) } ?: "<dark_gray>없음</dark_gray>"),
            "<gray>클릭: 바꾸기 (비우면 지움)</gray>")) {
            if (!has(DecorFeature.ARMORSTAND_NAME)) return@set deny()
            ask(DialogForm("<yellow>거치대 이름</yellow>").text("name", "이름", stand.customName()?.let { DecorText.toMini(it) }.orEmpty(), 128)) { v ->
                val raw = v.text("name").trim()
                stand.customName(if (raw.isEmpty()) null else DecorText.parse(raw, DecorFeature.textLevel(viewer)))
                if (raw.isNotEmpty()) stand.isCustomNameVisible = true
            }
        }
        set(SLOT_RESET, Icon.of(Material.BARRIER, "<red>자세 초기화</red>", "<gray>모든 자세를 기본으로</gray>")) {
            if (!has(DecorFeature.ARMORSTAND_POSE)) return@set deny()
            val d = StandTemplate()
            for ((part, angles) in listOf(Part.HEAD to d.head, Part.BODY to d.body, Part.LEFT_ARM to d.leftArm, Part.RIGHT_ARM to d.rightArm,
                Part.LEFT_LEG to d.leftLeg, Part.RIGHT_LEG to d.rightLeg)) put(part, angles)
            refresh()
        }
        set(SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    override fun onClose(event: InventoryCloseEvent) {
        service.closed(viewer, stand)
    }

    private fun deny() {
        ess.messages.send(viewer, "no-permission")
    }

    private fun poseLore(step: Double) = listOf("<gray>좌클릭 +${fmt(step)}° · 우클릭 −${fmt(step)}°</gray>", "<gray>Shift 와 함께 10배 · Q 0으로</gray>")

    private fun toggle(slot: Int, name: String, value: Boolean, node: String, flip: () -> Unit) {
        set(slot, toggleIcon(name, value)) {
            if (!has(node)) return@set deny()
            flip()
            refresh()
        }
    }

    private fun equip(slot: Int, which: EquipmentSlot, label: String, node: String) {
        val current = stand.getItem(which)
        val icon = if (current.type.isAir) Icon.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>$label 칸 — 비어 있음</gray>", "<gray>커서에 아이템을 들고 클릭: 넣기</gray>")
        else Icon.annotate(current.clone(), null, listOf("", "<gray>$label 칸 · 클릭: 커서와 맞바꾸기</gray>"))
        set(slot, icon) { e -> swap(e, which, node) }
    }

    /** 커서 ↔ 장비 칸. 클릭은 이미 취소됐다 — 한 틱 뒤에 둘이 그대로일 때만 바꾼다(그 사이 바뀌었으면 아무것도 안 한다 — 복사 막기). */
    private fun swap(e: InventoryClickEvent, which: EquipmentSlot, node: String) {
        if (!has(node)) return deny()
        if (stand.isSlotDisabled(which) && !has(DecorFeature.ARMORSTAND_ANY)) return ess.messages.send(viewer, "decor-as-locked")
        val cursor = e.cursor.clone()
        val old = stand.getItem(which).clone()
        viewer.scheduler.run(ess.plugin, { _ ->
            if (!stand.isValid || !viewer.isOnline) return@run
            // 같은 틱에 화면을 닫았으면 바꾸지 않는다 — 닫힌 뒤의 커서에 둔 아이템은 보이지 않는 채로 남아 서버가 꺼지면 사라진다.
            if (viewer.openInventory.topInventory.holder !== this@ArmorStandMenu) return@run
            if (viewer.itemOnCursor != cursor || stand.getItem(which) != old) return@run
            val event = PlayerArmorStandManipulateEvent(viewer, stand, cursor.clone(), old.clone(), which, EquipmentSlot.HAND)
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) return@run ess.messages.send(viewer, "decor-as-protected")
            stand.setItem(which, cursor.takeUnless { it.type.isAir })
            viewer.setItemOnCursor(old.takeUnless { it.type.isAir })
            refresh()
        }, null)
    }

    private fun get(part: Part): EulerAngle = when (part) {
        Part.HEAD -> stand.headPose
        Part.BODY -> stand.bodyPose
        Part.LEFT_ARM -> stand.leftArmPose
        Part.RIGHT_ARM -> stand.rightArmPose
        Part.LEFT_LEG -> stand.leftLegPose
        Part.RIGHT_LEG -> stand.rightLegPose
    }

    private fun put(part: Part, a: StandTemplate.Angles) {
        val e = ArmorStands.rad(a)
        when (part) {
            Part.HEAD -> stand.headPose = e
            Part.BODY -> stand.bodyPose = e
            Part.LEFT_ARM -> stand.leftArmPose = e
            Part.RIGHT_ARM -> stand.rightArmPose = e
            Part.LEFT_LEG -> stand.leftLegPose = e
            Part.RIGHT_LEG -> stand.rightLegPose = e
        }
    }

    private fun fmt(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e7) v.toLong().toString() else "%.2f".format(v)

    private companion object {
        const val SIZE = 54
        val PART_SLOTS = intArrayOf(0, 3, 9, 12, 18, 21) // 머리·몸통 / 왼팔·오른팔 / 왼다리·오른다리 — 각 X·Y·Z 세 칸
        const val SLOT_ROTATE = 7
        const val SLOT_HELMET = 8
        const val SLOT_HAND = 16
        const val SLOT_CHEST = 17
        const val SLOT_OFFHAND = 25
        const val SLOT_LEGS = 26
        const val SLOT_MOVE = 27 // 27·28·29
        const val SLOT_SCALE = 31
        const val SLOT_FEET = 35
        const val SLOT_TOGGLES = 36 // 36..44
        const val SLOT_COPY = 45
        const val SLOT_PASTE = 46
        const val SLOT_TEMPLATE_SAVE = 47
        const val SLOT_TEMPLATE_LIST = 48
        const val SLOT_NAME = 49
        const val SLOT_RESET = 50
        const val SLOT_CLOSE = 53
    }
}

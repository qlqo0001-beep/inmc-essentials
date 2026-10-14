package com.inmc.essentials.decor

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.integration.Mail
import kr.inmc.core.util.AtomicFiles
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.EulerAngle
import java.util.UUID

/**
 * 갑옷 거치대 편집(CMI armorstand) — 한 거치대는 한 사람만 편집한다(CMI cantClick). 편집기를 연 자리에서 [DecorSettings.ArmorStand.maxMove]
 * 블록 넘게는 못 옮긴다(조금씩 밀어 남의 땅에 넣기를 막는다). 복사한 모양은 사람마다 메모리에, 템플릿은 `decor/armorstand-templates.yml`.
 *
 * 명령으로 열 때(`/갑옷거치대`·가까이·편집·마지막)는 `.armorstand.any` 가 없으면 **늘** 보호 구역을 확인한다([Probe]) — 바라보기는 벽을 뚫고
 * 가까이 목록은 64블록까지라 확인을 끄면 남의 땅 거치대의 장비를 가져갈 수 있었다(CMI CheckBlockPlace=false 를 옮기던 것 — 2026-10-10 리뷰).
 */
class ArmorStands(private val ess: Ess, private val settings: () -> DecorSettings, private val probe: Probe) {

    /** 편집을 연 자리 — Location 은 월드를 붙들어(내린 던전 월드가 남는다) 이름·좌표만. */
    private data class Origin(val world: String, val x: Double, val y: Double, val z: Double)

    private val editors = HashMap<UUID, UUID>() // 거치대 → 편집하는 사람
    /** 거치대 → 편집을 연 자리. 오래된 것부터 [MAX_ORIGINS] 개까지만 기억한다(서버가 오래 켜져 있어도 자라지 않게). */
    private val origins = object : LinkedHashMap<UUID, Origin>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Origin>?): Boolean = size > MAX_ORIGINS
    }
    private val last = HashMap<UUID, UUID>() // 사람 → 마지막으로 편집한 거치대
    private val clipboard = HashMap<UUID, StandTemplate>()

    @Volatile
    var templates: Map<String, StandTemplate> = emptyMap()
        private set

    /** 우리가 보호 확인으로 쏜 사건 — 우리 리스너가 그것을 받아 편집기를 다시 열지 않게. */
    val probing: Boolean get() = probe.probing

    private val file get() = ess.io.file("decor", "armorstand-templates.yml")

    val templateKey = NamespacedKey(Ess.NAMESPACE, "as_template")

    fun load() {
        val f = file
        ess.io.async({
            if (!f.isFile) emptyMap() else {
                val y = YamlConfiguration.loadConfiguration(f)
                y.getKeys(false).mapNotNull { name -> y.getConfigurationSection(name)?.let { name to StandTemplate.read(it) } }.toMap()
            }
        }) { loaded -> templates = loaded }
    }

    private fun save() {
        val y = YamlConfiguration()
        for ((name, t) in templates) t.write(y.createSection(name))
        val text = y.saveToString()
        val f = file
        ess.io.asyncRun {
            f.parentFile.mkdirs()
            AtomicFiles.write(f, text)
        }
    }

    // --- 열기 ----------------------------------------------------------------------------------------

    /** 보호 구역 확인 — 그 거치대를 만지는 사건을 쏴 보고 막히면 false(Lands·WorldGuard 가 막는 사건, [Probe]). */
    fun mayTouch(player: Player, stand: ArmorStand): Boolean =
        player.hasPermission(DecorFeature.ARMORSTAND_ANY) || probe.mayTouchStand(player, stand)

    /** 다른 플러그인의 거치대이거나 마커 — `.armorstand.any` 가 아니면 편집기를 열지 않는다(가까이 목록에서도 뺀다). */
    fun offLimits(player: Player, stand: ArmorStand): Boolean = foreign(stand) && !player.hasPermission(DecorFeature.ARMORSTAND_ANY)

    fun open(player: Player, stand: ArmorStand) {
        if (offLimits(player, stand)) return ess.messages.send(player, "decor-as-foreign")
        val editor = editors[stand.uniqueId]
        if (editor != null && editor != player.uniqueId && Bukkit.getPlayer(editor)?.isOnline == true) {
            return ess.messages.send(player, "decor-as-busy")
        }
        editors[stand.uniqueId] = player.uniqueId
        if (stand.uniqueId !in origins) stand.location.let { origins[stand.uniqueId] = Origin(it.world.name, it.x, it.y, it.z) }
        last[player.uniqueId] = stand.uniqueId
        ArmorStandMenu(ess, player, this, stand, settings).show()
    }

    /** 화면을 다시 그릴 때 — 아무도 안 쥐었으면 다시 쥔다. */
    fun touch(player: Player, stand: ArmorStand) {
        if (editors[stand.uniqueId] == null) editors[stand.uniqueId] = player.uniqueId
    }

    fun closed(player: Player, stand: ArmorStand) {
        // 연 자리(origins)는 남긴다 — 닫았다 다시 열어 거리 제한을 새로 시작하지 못하게. 서버가 다시 켜지면 잊는다. 거치대가 없어졌으면 지운다.
        if (editors[stand.uniqueId] == player.uniqueId) editors.remove(stand.uniqueId)
        if (!stand.isValid) origins.remove(stand.uniqueId)
    }

    fun lastOf(player: Player): ArmorStand? = last[player.uniqueId]?.let { Bukkit.getEntity(it) as? ArmorStand }?.takeIf { it.isValid }

    fun quit(id: UUID) {
        editors.entries.removeIf { it.value == id }
        clipboard.remove(id)
        last.remove(id)
    }

    // --- 바꾸기 ---------------------------------------------------------------------------------------

    enum class Move { OK, TOO_FAR, PROTECTED }

    /**
     * 자리 옮기기 — 연 자리에서 최대 거리를 넘으면 [Move.TOO_FAR]. 다른 블록으로 넘어갈 때는 그 블록에 거치대를 **놓을 수 있는지**([Probe.mayPlace])도
     * 본다 — 영지 경계에서 이웃 영지로 밀어 넣지 못하게(2026-10-10 리뷰). `.any` 는 건너뛴다.
     * 거치대 자체의 짧은 이동이라 `ess.teleporter` 를 지나지 않는다(그것은 사람의 이동 — 뒤로 기록·쿨타임이 붙는다).
     */
    fun move(player: Player, stand: ArmorStand, dx: Double, dy: Double, dz: Double): Move {
        val target = stand.location.clone().add(dx, dy, dz)
        val origin = origins[stand.uniqueId]?.takeIf { it.world == target.world.name }
            ?: Origin(target.world.name, stand.location.x, stand.location.y, stand.location.z)
        if (!player.hasPermission(DecorFeature.ARMORSTAND_MOVE_UNLIMITED) &&
            !Pose.withinMove(target.x - origin.x, target.y - origin.y, target.z - origin.z, settings().armorStand.maxMove)
        ) return Move.TOO_FAR
        val crosses = target.blockX != stand.location.blockX || target.blockY != stand.location.blockY || target.blockZ != stand.location.blockZ
        if (crosses && !player.hasPermission(DecorFeature.ARMORSTAND_ANY) && !probe.mayPlace(player, stand, target.block)) return Move.PROTECTED
        stand.teleport(target)
        return Move.OK
    }

    fun scaleRange(player: Player): ClosedFloatingPointRange<Double> =
        if (player.hasPermission(DecorFeature.ARMORSTAND_SCALE_UNLIMITED)) 0.0625..16.0 else settings().armorStand.minScale..settings().armorStand.maxScale

    fun scaleOf(stand: ArmorStand): Double = stand.getAttribute(Attribute.SCALE)?.baseValue ?: 1.0

    fun setScale(stand: ArmorStand, value: Double) {
        stand.getAttribute(Attribute.SCALE)?.baseValue = value
    }

    fun isLocked(stand: ArmorStand): Boolean = SLOTS.all { stand.isSlotDisabled(it) }

    fun setLocked(stand: ArmorStand, locked: Boolean) {
        if (locked) stand.setDisabledSlots(*SLOTS.toTypedArray()) else stand.setDisabledSlots()
    }

    fun capture(stand: ArmorStand): StandTemplate = StandTemplate(
        head = deg(stand.headPose), body = deg(stand.bodyPose), leftArm = deg(stand.leftArmPose), rightArm = deg(stand.rightArmPose),
        leftLeg = deg(stand.leftLegPose), rightLeg = deg(stand.rightLegPose),
        basePlate = stand.hasBasePlate(), small = stand.isSmall, visible = stand.isVisible, arms = stand.hasArms(), gravity = stand.hasGravity(),
        glowing = stand.isGlowing, invulnerable = stand.isInvulnerable, nameVisible = stand.isCustomNameVisible, locked = isLocked(stand),
        scale = scaleOf(stand),
    )

    /**
     * 모양을 입힌다(붙여넣기 · 템플릿 고르기 · 템플릿 거치대 놓기). [player] 가 있으면 **편집기 버튼과 같은 권한으로** 칸마다 거른다 — 자세는 `.pose`,
     * 받침대·작게·보이기·팔·중력·발광은 `.toggle`, 이름 보이기는 `.name`, 무적은 `.invulnerable`, 크기는 `.scale`(범위 안으로), 잠금 풀기는 `.any`.
     * 권한이 없는 칸은 거치대의 지금 값을 그대로 둔다(켜고 끄기 권한을 뺀 그룹이 템플릿·붙여넣기로 투명·무중력 거치대를 만들지 못하게 — 2026-10-10 리뷰).
     * null = 콘솔.
     */
    fun apply(stand: ArmorStand, t: StandTemplate, player: Player?) {
        fun may(node: String) = player == null || player.hasPermission(node)
        if (may(DecorFeature.ARMORSTAND_POSE)) {
            stand.headPose = rad(t.head)
            stand.bodyPose = rad(t.body)
            stand.leftArmPose = rad(t.leftArm)
            stand.rightArmPose = rad(t.rightArm)
            stand.leftLegPose = rad(t.leftLeg)
            stand.rightLegPose = rad(t.rightLeg)
        }
        if (may(DecorFeature.ARMORSTAND_TOGGLE)) {
            stand.setBasePlate(t.basePlate)
            stand.isSmall = t.small
            stand.isVisible = t.visible
            stand.setArms(t.arms)
            stand.setGravity(t.gravity)
            stand.isGlowing = t.glowing
        }
        if (may(DecorFeature.ARMORSTAND_INVULNERABLE)) stand.isInvulnerable = t.invulnerable
        if (may(DecorFeature.ARMORSTAND_NAME)) stand.isCustomNameVisible = t.nameVisible
        val locked = isLocked(stand)
        // 잠긴 거치대에 잠기지 않은 모양을 붙여 넣어 푸는 길도 막는다(풀기는 .any 만 — lockToggleAllowed).
        if (player == null || locked == t.locked ||
            lockToggleAllowed(locked, player.hasPermission(DecorFeature.ARMORSTAND_TOGGLE), player.hasPermission(DecorFeature.ARMORSTAND_ANY))
        ) setLocked(stand, t.locked)
        if (may(DecorFeature.ARMORSTAND_SCALE)) {
            val range = player?.let { scaleRange(it) } ?: (settings().armorStand.minScale..settings().armorStand.maxScale)
            setScale(stand, Pose.scale(t.scale, range.start, range.endInclusive))
        }
    }

    fun copy(player: Player, stand: ArmorStand) {
        clipboard[player.uniqueId] = capture(stand)
    }

    fun clipboardOf(player: Player): StandTemplate? = clipboard[player.uniqueId]

    // --- 템플릿 ---------------------------------------------------------------------------------------

    fun saveTemplate(name: String, stand: ArmorStand) {
        templates = templates + (name to capture(stand))
        save()
    }

    fun removeTemplate(name: String): Boolean {
        if (name !in templates) return false
        templates = templates - name
        save()
        return true
    }

    fun mayUseTemplate(player: Player, name: String): Boolean =
        !settings().armorStand.templatePermission || player.hasPermission(templateNode(name))

    /**
     * 템플릿 거치대 아이템을 준다 — 가방의 갑옷 거치대 하나를 바꾼다(없는 것을 만들어 주지 않는다). 크리에이티브·`…template.free` 는 그냥 준다.
     */
    fun giveTemplate(player: Player, name: String): Boolean {
        val free = player.gameMode == GameMode.CREATIVE || player.hasPermission(DecorFeature.ARMORSTAND_TEMPLATE_FREE)
        if (!free) {
            val slot = player.inventory.contents.indexOfFirst { it != null && it.type == Material.ARMOR_STAND && !it.persistentDataContainer.has(templateKey) }
            if (slot < 0) {
                ess.messages.send(player, "decor-as-template-need-stand")
                return false
            }
            val stack = player.inventory.getItem(slot)!!
            stack.amount -= 1
            player.inventory.setItem(slot, stack.takeIf { it.amount > 0 })
        }
        val item = ItemStack(Material.ARMOR_STAND)
        item.editMeta { meta ->
            meta.displayName(Text.renderFlat(ess.messages.raw("decor-as-template-item"), Ph.of().raw("{이름}", name)))
            meta.persistentDataContainer.set(templateKey, PersistentDataType.STRING, name)
        }
        val left = player.inventory.addItem(item).values.toList()
        if (left.isNotEmpty()) {
            // 가방이 찼으면 우편으로(CLAUDE.md 9) — 우편이 없으면 발밑에. 둘 중 하나만.
            val mailed = Mail.available && Mail.send(player.uniqueId, origin = "inmc-essentials", source = "갑옷 거치대 템플릿 '$name'", stacks = left)
            if (!mailed) for (rest in left) player.world.dropItemNaturally(player.location, rest)
        }
        return true
    }

    fun templateOf(stack: ItemStack?): String? = stack?.takeIf { it.type == Material.ARMOR_STAND }?.persistentDataContainer?.get(templateKey, PersistentDataType.STRING)

    fun clear() {
        editors.clear()
        origins.clear()
        last.clear()
        clipboard.clear()
    }

    companion object {
        const val MAX_ORIGINS = 4096

        /**
         * 장비 잠금 단추 — 잠그기는 켜고 끄기 권한이면 되지만 **풀기는 `.armorstand.any`** 만(관리자가 잠근 전시용 거치대를 아무나 풀고 장비를
         * 가져가지 못하게 — 2026-10-10 리뷰).
         */
        fun lockToggleAllowed(currentlyLocked: Boolean, canToggle: Boolean, any: Boolean): Boolean = if (currentlyLocked) any else canToggle || any

        /**
         * 템플릿마다의 권한 노드(설정 `template-permission`) — `…armorstand.template.use.<이름>`. 선언된 `.template.save/.give/.free` 와 **다른 가지**라
         * 'give'·'save'·'free' 라는 이름의 템플릿이 그 노드와 겹치지 않는다(겹치면 기본 모두인 `.give` 로 누구나 꺼냈다 — 2026-10-10 리뷰).
         * 이름에는 점이 없다([StandTemplate.NAME]). 이름으로 짓는 노드라 선언하지 않는다(= op 기본, 지뢰 11 — 여기서는 그 뜻 그대로).
         */
        fun templateNode(name: String): String = DecorFeature.ARMORSTAND_TEMPLATE_USE_PREFIX + name

        /**
         * 다른 플러그인의 거치대 — 마커(맞출 수 없는 표시용), Citizens NPC·Shopkeepers 상인(메타데이터), 또는 PDC 에 우리 것·바닐라 것이 아닌 열쇠가
         * 붙은 것(가구·모델·홀로그램 플러그인). 편집기로 자세·장비·잠금을 바꾸면 그 플러그인이 꼬인다(2026-10-10 리뷰 — 액자 [ItemFrames.foreign] 와 같은 규칙).
         */
        fun foreign(stand: ArmorStand): Boolean =
            stand.isMarker || stand.hasMetadata("NPC") || stand.hasMetadata("shopkeeper") ||
                stand.persistentDataContainer.keys.any { it.namespace != "minecraft" && it.namespace != Ess.NAMESPACE }

        val SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)

        fun deg(a: EulerAngle) = StandTemplate.Angles(Pose.wrap(Math.toDegrees(a.x)), Pose.wrap(Math.toDegrees(a.y)), Pose.wrap(Math.toDegrees(a.z)))

        fun rad(a: StandTemplate.Angles) = EulerAngle(Math.toRadians(a.x), Math.toRadians(a.y), Math.toRadians(a.z))
    }
}

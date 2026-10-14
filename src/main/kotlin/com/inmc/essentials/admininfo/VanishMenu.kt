package com.inmc.essentials.admininfo

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import kr.inmc.core.gui.Icon
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/** `/투명설정` — 한 사람의 투명 옵션(바꾼 것은 저장, "기본값으로" 는 설정의 기본값을 따르게). 36칸. */
internal class VanishMenu(
    ess: Ess,
    private val feature: AdminInfoFeature,
    viewer: Player,
    private val target: UUID,
    private val targetName: String,
) : Menu(ess, viewer, SIZE, "<dark_gray>투명 설정 · $targetName</dark_gray>") {

    private val vanish get() = feature.vanish

    override fun draw() {
        clear()
        val online = Bukkit.getPlayer(target)
        val active = online != null && vanish.isActive(online)
        val canToggle = online != null && (if (online == viewer) viewer.hasPermission(AdminInfoFeature.VANISH) else viewer.hasPermission(AdminInfoFeature.VANISH_OTHERS))
        set(
            SLOT_STATE,
            Icon.of(
                if (active) Material.ENDER_EYE else Material.ENDER_PEARL,
                "<yellow>투명: </yellow>" + Icon.toggle(active),
                buildList {
                    add(if (online == null) "<gray>접속 중이 아님</gray>" else "<gray>${AdminText.escape(online.name)}</gray>")
                    if (canToggle) {
                        add("")
                        add("<gray>클릭해서 바꾸기</gray>")
                    }
                },
            ),
        ) {
            if (online != null && canToggle && online.isOnline) {
                val on = !vanish.isActive(online)
                vanish.set(online, on, silent = false)
                ess.messages.send(online, if (on) "admininfo-vanish-on" else "admininfo-vanish-off")
                refresh()
            }
        }
        for ((i, option) in VanishOption.entries.withIndex()) {
            val value = vanish.option(target, option)
            val mark = if (vanish.overridden(target, option)) "<aqua>(바꿈)</aqua>" else "<dark_gray>(기본값)</dark_gray>"
            val icon = Icon.of(
                if (value) MATERIALS[option] ?: Material.LIME_DYE else Material.GRAY_DYE,
                "<yellow>${option.label}: </yellow>" + Icon.toggle(value) + " " + mark,
                listOf("<gray>${option.help}</gray>", "<dark_gray>CMI: ${option.cmiKey}</dark_gray>", "", "<gray>클릭해서 바꾸기</gray>"),
            )
            set(FIRST_OPTION + i, icon) {
                vanish.setOption(target, option, !vanish.option(target, option))
                refresh()
            }
        }
        set(SLOT_RESET, Icon.of(Material.BUCKET, "<yellow>기본값으로</yellow>", "<gray>바꾼 옵션을 모두 지우고 설정 파일의 기본값을 따릅니다</gray>")) {
            vanish.resetOptions(target)
            refresh()
        }
        navigation(backSlot = SLOT_BACK, closeSlot = SLOT_CLOSE)
    }

    companion object {
        const val SIZE = 36
        const val SLOT_STATE = 4
        const val FIRST_OPTION = 9
        const val SLOT_RESET = 31
        const val SLOT_BACK = 27
        const val SLOT_CLOSE = 35

        private val MATERIALS: Map<VanishOption, Material> = mapOf(
            VanishOption.DAMAGE_TO_ENTITY to Material.IRON_SWORD,
            VanishOption.PLAYER_DAMAGE to Material.SHIELD,
            VanishOption.ITEM_PICKUP to Material.HOPPER,
            VanishOption.MOB_AGGRO to Material.ZOMBIE_HEAD,
            VanishOption.INTERACTION to Material.STONE_PRESSURE_PLATE,
            VanishOption.INFORM_ON_LEAVE to Material.OAK_DOOR,
            VanishOption.INFORM_ON_JOIN to Material.SPRUCE_DOOR,
            VanishOption.NIGHT_VISION to Material.GOLDEN_CARROT,
            VanishOption.BOSSBAR to Material.PURPLE_BANNER,
            VanishOption.RELOG_DISABLE to Material.CLOCK,
            VanishOption.NO_MESSAGES to Material.WRITABLE_BOOK,
            VanishOption.FAKE_JOIN_LEAVE to Material.NAME_TAG,
            VanishOption.MOB_SPAWNING to Material.SPAWNER,
            VanishOption.STOP_PLAYTIME to Material.COMPASS,
            VanishOption.SLEEP_IGNORE to Material.RED_BED,
            VanishOption.JOIN_VANISHED to Material.ENDER_CHEST,
            VanishOption.DEATH_MESSAGES to Material.SKELETON_SKULL,
            VanishOption.HOOK_PLAYERS to Material.FISHING_ROD,
        )
    }
}

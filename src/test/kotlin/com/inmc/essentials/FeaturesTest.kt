package com.inmc.essentials

import com.inmc.essentials.feature.Features
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 기능 목록 — 계획(~/.claude/plans/inmc-essentials.md 4절)의 19개, 이름·패키지·클래스까지. 여러 작업자가 나눠 채우는 자리라 못박는다. */
class FeaturesTest {

    private val expected = linkedMapOf(
        "teleport" to "com.inmc.essentials.teleport.TeleportFeature",
        "homes" to "com.inmc.essentials.home.HomesFeature",
        "warps" to "com.inmc.essentials.warp.WarpsFeature",
        "spawn" to "com.inmc.essentials.spawn.SpawnFeature",
        "chat" to "com.inmc.essentials.chat.ChatFeature",
        "mail" to "com.inmc.essentials.mail.MailFeature",
        "player" to "com.inmc.essentials.player.PlayerFeature",
        "afk" to "com.inmc.essentials.afk.AfkFeature",
        "items" to "com.inmc.essentials.item.ItemsFeature",
        "inventory" to "com.inmc.essentials.inventory.InventoryFeature",
        "kits" to "com.inmc.essentials.kit.KitsFeature",
        "world" to "com.inmc.essentials.world.WorldFeature",
        "decor" to "com.inmc.essentials.decor.DecorFeature",
        "portals" to "com.inmc.essentials.portal.PortalsFeature",
        "holograms" to "com.inmc.essentials.hologram.HologramsFeature",
        "commands" to "com.inmc.essentials.commands.CommandsFeature",
        "server" to "com.inmc.essentials.server.ServerFeature",
        "moderation" to "com.inmc.essentials.moderation.ModerationFeature",
        "admininfo" to "com.inmc.essentials.admininfo.AdminInfoFeature",
    )

    @Test
    fun `기능 이름은 겹치지 않고 계획의 목록 그대로다`() {
        assertEquals(Features.IDS.size, Features.IDS.toSet().size, "겹친 이름: ${Features.IDS.groupBy { it }.filterValues { it.size > 1 }.keys}")
        assertEquals(expected.keys.toList(), Features.IDS)
    }

    @Test
    fun `기능 클래스와 패키지가 정해진 자리다`() {
        val actual = TestEss.features.associate { it.id to it.javaClass.name }
        assertEquals(expected, LinkedHashMap(actual))
    }

    @Test
    fun `권한 노드는 inmcessentials 접두이고 기능끼리 겹치지 않는다`() {
        val nodes = TestEss.features.flatMap { f -> f.permissions.map { f.id to it.node } }
        for ((id, node) in nodes) assertTrue(node.startsWith("inmcessentials."), "$id 의 권한 $node 는 inmcessentials. 로 시작해야 합니다")
        val dup = nodes.groupBy { it.second }.filterValues { it.size > 1 }
        assertTrue(dup.isEmpty(), "두 번 선언된 권한: ${dup.mapValues { (_, v) -> v.map { it.first } }}")
        assertTrue(nodes.none { it.second == Ess.ADMIN }, "${Ess.ADMIN} 은 paper-plugin.yml 에 있습니다 — 기능이 다시 선언하지 않습니다")
    }
}

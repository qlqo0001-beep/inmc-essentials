package com.inmc.essentials.feature

import com.inmc.essentials.Ess

/**
 * 기능 목록 — **고정 순서**. 켜는 순서이자(거꾸로) 끄는 순서다. 새 기능은 여기에 한 줄을 더하고 `config.yml modules` ·
 * `messages/<id>.yml` 도 같이(`FeaturesTest`·`ResourceTest`).
 *
 * 순서가 뜻을 갖는 곳: 서비스를 바꿔 끼우는 기능(teleport·afk·kits·commands)이 그것을 쓰는 기능보다 먼저 켜질 필요는 없다 —
 * 접점은 부를 때마다 [Ess] 에서 읽는다. 그러니 순서는 "로그가 읽기 쉬운 순서"일 뿐이다.
 */
object Features {

    class Entry(val id: String, val create: (Ess) -> Feature)

    val ALL: List<Entry> = listOf(
        Entry("teleport") { com.inmc.essentials.teleport.TeleportFeature(it) },
        Entry("homes") { com.inmc.essentials.home.HomesFeature(it) },
        Entry("warps") { com.inmc.essentials.warp.WarpsFeature(it) },
        Entry("spawn") { com.inmc.essentials.spawn.SpawnFeature(it) },
        Entry("chat") { com.inmc.essentials.chat.ChatFeature(it) },
        Entry("mail") { com.inmc.essentials.mail.MailFeature(it) },
        Entry("player") { com.inmc.essentials.player.PlayerFeature(it) },
        Entry("afk") { com.inmc.essentials.afk.AfkFeature(it) },
        Entry("items") { com.inmc.essentials.item.ItemsFeature(it) },
        Entry("inventory") { com.inmc.essentials.inventory.InventoryFeature(it) },
        Entry("kits") { com.inmc.essentials.kit.KitsFeature(it) },
        Entry("world") { com.inmc.essentials.world.WorldFeature(it) },
        Entry("decor") { com.inmc.essentials.decor.DecorFeature(it) },
        Entry("portals") { com.inmc.essentials.portal.PortalsFeature(it) },
        Entry("holograms") { com.inmc.essentials.hologram.HologramsFeature(it) },
        Entry("commands") { com.inmc.essentials.commands.CommandsFeature(it) },
        Entry("server") { com.inmc.essentials.server.ServerFeature(it) },
        Entry("moderation") { com.inmc.essentials.moderation.ModerationFeature(it) },
        Entry("admininfo") { com.inmc.essentials.admininfo.AdminInfoFeature(it) },
    )

    val IDS: List<String> = ALL.map { it.id }

    /** 전부 만든다(꺼진 것도 — 꺼진 기능은 표만 만든다). 하나라도 던지면 던진다(시험용 — 플러그인은 [create] 하나씩 감싼다). */
    fun create(ess: Ess): List<Feature> = ALL.map { create(it, ess) }

    /** 하나를 만든다. 만든 기능의 [Feature.id] 가 목록의 이름과 다르면 던진다. */
    fun create(entry: Entry, ess: Ess): Feature =
        entry.create(ess).also { check(it.id == entry.id) { "기능 이름이 어긋났습니다: 목록 ${entry.id} / 클래스 ${it.id}" } }
}

package com.inmc.essentials.inventory

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration

/** `settings/inventory.yml` 의 불변 스냅샷(CMI Optimizations.Commands.Inv · config.yml inv · Modules silentChest). */
data class InventorySettings(
    /** 열 수 없는 사람(이름, 소문자) — op 는 우회(CMI Inv.BlackList). */
    val blacklist: Set<String> = emptySet(),
    /** 접속하지 않은 사람의 인벤·엔더 보기를 막는다(CMI Inv.DisableOffline). */
    val disableOffline: Boolean = false,
    /** 나갈 때(그리고 서버를 끌 때) 가방·엔더를 DB 에 남긴다 — 접속하지 않은 사람 보기·고치기의 근거. */
    val snapshotOnQuit: Boolean = true,
    /** 오프라인 편집을 접속(·월드 이동) 뒤 몇 틱 기다렸다 적용할까 — 모드 공급처(inmc-worlds)가 몸을 먼저 다 맞추도록(1~100). */
    val applyDelayTicks: Int = 2,
    val saved: SavedSettings = SavedSettings(),
    /** 투명 중이면 조용히 열기가 저절로 켜진다(CMI Vanish noisyChest 기본 false 와 같은 뜻). */
    val silentWhenVanished: Boolean = true,
) {
    companion object {
        fun from(y: YamlConfiguration): InventorySettings {
            val d = InventorySettings()
            val ds = d.saved
            return InventorySettings(
                blacklist = y.getStringList("inv.blacklist").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                disableOffline = y.getBoolean("inv.disable-offline", d.disableOffline),
                snapshotOnQuit = y.getBoolean("inv.snapshot-on-quit", d.snapshotOnQuit),
                applyDelayTicks = y.getInt("inv.offline-apply-delay-ticks", d.applyDelayTicks).coerceIn(1, 100),
                saved = SavedSettings(
                    saveOnDeath = y.getBoolean("saved.save-on-death", ds.saveOnDeath),
                    ignoreEmpty = y.getBoolean("saved.ignore-empty", ds.ignoreEmpty),
                    requiresPermission = y.getBoolean("saved.requires-permission", ds.requiresPermission),
                    keep = y.getInt("saved.keep-per-player", ds.keep).coerceIn(1, 1000),
                    backupBeforeLoad = y.getBoolean("saved.backup-before-load", ds.backupBeforeLoad),
                    restoreHp = y.getBoolean("saved.restore.hp", ds.restoreHp),
                    restoreXp = y.getBoolean("saved.restore.xp", ds.restoreXp),
                    restoreFood = y.getBoolean("saved.restore.food", ds.restoreFood),
                    restoreSaturation = y.getBoolean("saved.restore.saturation", ds.restoreSaturation),
                    restorePotions = y.getBoolean("saved.restore.potions", ds.restorePotions),
                    restoreItems = y.getBoolean("saved.restore.items", ds.restoreItems),
                    blacklist = y.getStringList("saved.blacklisted-items").mapNotNull { Material.matchMaterial(it.trim()) }.toSet(),
                ),
                silentWhenVanished = y.getBoolean("silent-chest.auto-when-vanished", d.silentWhenVanished),
            )
        }
    }
}

/** 저장 인벤(CMI config.yml inv). */
data class SavedSettings(
    val saveOnDeath: Boolean = false,
    val ignoreEmpty: Boolean = false,
    /** true 면 `inmcessentials.inventory.saveondeath` 가 있는 사람만 죽을 때 저장. */
    val requiresPermission: Boolean = false,
    /** 한 사람당 남기는 수(넘으면 오래된 것부터 지움) — CMI SavedInventories. */
    val keep: Int = 5,
    /** 불러오기 전에 지금 가방을 한 번 저장한다(덮어쓰기로 잃지 않게 — CMI 에 없는 안전장치). */
    val backupBeforeLoad: Boolean = true,
    val restoreHp: Boolean = true,
    val restoreXp: Boolean = true,
    val restoreFood: Boolean = true,
    val restoreSaturation: Boolean = true,
    val restorePotions: Boolean = true,
    val restoreItems: Boolean = true,
    /** 저장하지 않는 재질(CMI restore.BlackListedItems). */
    val blacklist: Set<Material> = emptySet(),
)

package com.inmc.essentials.inventory

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** inventory 기능의 순수 규칙 — 저장 인벤 번호 · 설정 읽기. */
class InventoryRulesTest {

    @Test
    fun `저장 인벤 번호 - 1 이 가장 새것 · last`() {
        assertEquals(0, SavedInventories.index("last"))
        assertEquals(0, SavedInventories.index("1"))
        assertEquals(4, SavedInventories.index("5"))
        assertNull(SavedInventories.index("0"))
        assertNull(SavedInventories.index("x"))
    }

    @Test
    fun `설정 - 값이 이상하면 기본값 · 이름은 소문자`() {
        val y = YamlConfiguration()
        y.loadFromString(
            """
            inv:
              blacklist: [Zrips, ' Notch ']
              disable-offline: true
            saved:
              keep-per-player: -3
              restore:
                hp: false
            silent-chest:
              auto-when-vanished: false
            """.trimIndent(),
        )
        val s = InventorySettings.from(y)
        assertEquals(setOf("zrips", "notch"), s.blacklist)
        assertTrue(s.disableOffline)
        assertEquals(1, s.saved.keep)
        assertFalse(s.saved.restoreHp)
        assertTrue(s.saved.restoreItems)
        assertFalse(s.silentWhenVanished)
    }
}

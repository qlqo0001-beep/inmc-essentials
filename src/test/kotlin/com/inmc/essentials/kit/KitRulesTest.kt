package com.inmc.essentials.kit

import kr.inmc.core.reward.GiveMode
import kr.inmc.core.reward.RewardBundle
import kr.inmc.core.reward.RewardEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 킷 규칙 — 쿨타임 · 횟수 · 무게 뽑기 · id · YAML 왕복 · CMI 기본 예제 알아보기 · CMI 명령 변수. */
class KitRulesTest {

    @Test
    fun `쿨타임은 초 단위로 올림 · 기록이 없으면 0`() {
        val kit = Kit("a", cooldown = 60)
        assertEquals(0L, KitRules.cooldownLeft(kit, null, 1000))
        assertEquals(60L, KitRules.cooldownLeft(kit, KitUsage(10_000, 1), 10_000))
        assertEquals(1L, KitRules.cooldownLeft(kit, KitUsage(10_000, 1), 69_500))
        assertEquals(0L, KitRules.cooldownLeft(kit, KitUsage(10_000, 1), 70_000))
        assertEquals(0L, KitRules.cooldownLeft(Kit("b"), KitUsage(10_000, 1), 10_000))
        // 초기화(쿨타임만)는 lastUsed 를 0 으로 — 그러면 바로 받는다.
        assertEquals(0L, KitRules.cooldownLeft(kit, KitUsage(0, 3), 10))
    }

    @Test
    fun `횟수 - 0 은 무제한 · 1 은 한 번`() {
        assertFalse(KitRules.usedUp(Kit("a", uses = 0), KitUsage(1, 99)))
        assertFalse(KitRules.usedUp(Kit("a", uses = 1), null))
        assertTrue(KitRules.usedUp(Kit("a", uses = 1), KitUsage(1, 1)))
        assertFalse(KitRules.usedUp(Kit("a", uses = 3), KitUsage(1, 2)))
    }

    @Test
    fun `무게 뽑기`() {
        val items = listOf("a" to 1, "b" to 3)
        assertEquals("a", KitRules.weighted(items, { it.second }, 0.0)?.first)
        assertEquals("a", KitRules.weighted(items, { it.second }, 0.24)?.first)
        assertEquals("b", KitRules.weighted(items, { it.second }, 0.25)?.first)
        assertEquals("b", KitRules.weighted(items, { it.second }, 0.999)?.first)
        assertNull(KitRules.weighted(emptyList<Pair<String, Int>>(), { it.second }, 0.5))
    }

    @Test
    fun `id 다듬기`() {
        assertEquals("starter_kit", Kit.sanitize("Starter Kit"))
        assertEquals("ad1", Kit.sanitize("Ad1"))
        assertNull(Kit.sanitize("초보자"))
        assertTrue(Kit.validId("vip-2"))
        assertFalse(Kit.validId("VIP"))
    }

    @Test
    fun `킷 정의 YAML 왕복`() {
        val kit = Kit("vip", name = "<gold>VIP</gold>", description = mutableListOf("줄1"), requirePermission = false, cooldown = 3600, uses = 2,
            conditions = mutableListOf("a.b"), money = 100.0, currency = "gem", exp = 30, preview = false, firstJoin = true, group = "res", weight = 4, hidden = true, slot = 7)
        kit.pools[0].entries += RewardEntry(commands = mutableListOf("say {player}"), giveItem = false)
        kit.pools += RewardBundle(GiveMode.ROLL_N, 3)
        val again = Kit.fromYaml("vip", kit.toYaml())
        assertEquals(kit.toYaml(), again.toYaml())
        assertEquals("gem", again.currency)
        assertEquals(GiveMode.ROLL_N, again.pools[1].mode)
        assertEquals(3, again.pools[1].rollCount)
        assertEquals(listOf("say {player}"), again.pools[0].entries.single().commands)
        assertEquals(KitsFeature.ALL_KITS + ".vip", again.permission)
    }

    @Test
    fun `CMI 기본 예제와 명령 변수`() {
        // 내용까지 견주는 판정은 KitImportRulesTest — 여기서는 예제 그대로면 참, 조건을 바꾸면 거짓만.
        val defaults = org.bukkit.configuration.file.YamlConfiguration().apply { loadFromString(KitRules.CMI_DEFAULT_KITS) }
        assertTrue(KitRules.isCmiDefault(defaults))
        assertFalse(KitRules.isCmiDefault(defaults.apply { set("Newbie.Conditions", emptyList<String>()) }))
        assertEquals("msg! {player} &eYou just got &6{KITNAME} &ekit!", KitRules.cmiCommand("asConsole! msg! [playerName] &eYou just got &6{KITNAME} &ekit!"))
        assertEquals("give {player} stone", KitRules.cmiCommand("give {USERNAME} stone"))
    }
}

package com.inmc.essentials.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** player 기능의 순수 계산 — 서버 없이. */
class PlayerMathTest {

    // --- 꼬리 · 켜기/끄기 · 시간 ---

    @Test
    fun `꼬리는 낱말과 깃발과 옵션으로 나뉘고 음수는 낱말이다`() {
        val tail = Tail.parse("50% -nofeed -S -p:홍길동 -10")
        assertEquals(listOf("50%", "-10"), tail.words)
        assertEquals(setOf("-nofeed", "-s"), tail.flags)
        assertEquals(mapOf("p" to "홍길동"), tail.options)
        assertTrue(tail.silent)
        assertEquals(Tail.EMPTY, Tail.parse("  "))
    }

    @Test
    fun `앞에 낱말을 끼운다`() {
        val tail = Tail.parse("-s").prepend("30m")
        assertEquals(listOf("30m"), tail.words)
        assertTrue(tail.silent)
    }

    @Test
    fun `켜기 끄기 낱말`() {
        assertEquals(true, Toggle.parse("켜기"))
        assertEquals(false, Toggle.parse("OFF"))
        assertNull(Toggle.parse("모름"))
        assertNull(Toggle.parse(null))
    }

    @Test
    fun `시간 인자 - 초 단위 한글 더하기 빼기`() {
        assertEquals(TimeArg.Parsed(30, 0), TimeArg.parse("30"))
        assertEquals(TimeArg.Parsed(5400, 0), TimeArg.parse("1h30m"))
        assertEquals(TimeArg.Parsed(1800, 1), TimeArg.parse("+30m"))
        assertEquals(TimeArg.Parsed(300, -1), TimeArg.parse("-5m"))
        assertEquals(TimeArg.Parsed(5400, 0), TimeArg.parse("1시간30분"))
        assertEquals(TimeArg.Parsed(2 * 86400 + 10, 0), TimeArg.parse("2일10초"))
        assertNull(TimeArg.parse("abc"))
        assertNull(TimeArg.parse("10x"))
        assertNull(TimeArg.parse("m10"))
        assertNull(TimeArg.parse(""))
    }

    // --- 경험치 ---

    @Test
    fun `레벨 경계의 총 경험치가 바닐라와 같다`() {
        assertEquals(352, ExpMath.totalAt(16))
        assertEquals(394, ExpMath.totalAt(17))
        assertEquals(1395, ExpMath.totalAt(30))
        assertEquals(1507, ExpMath.totalAt(31))
        assertEquals(1628, ExpMath.totalAt(32))
        for (level in 0..60) assertEquals(ExpMath.totalAt(level + 1), ExpMath.totalAt(level) + ExpMath.needed(level), "레벨 $level")
    }

    @Test
    fun `총 경험치와 레벨은 오간다`() {
        for (total in listOf(0L, 6L, 7L, 352L, 400L, 1395L, 1628L, 50_000L)) {
            val (level, progress) = ExpMath.fromTotal(total)
            assertTrue(kotlin.math.abs(ExpMath.total(level, progress) - total) <= 1, "총 $total → $level/$progress")
        }
    }

    @Test
    fun `양 읽기`() {
        val fixed: (Long, Long) -> Long = { a, _ -> a }
        assertEquals(ExpMath.Amount(10, false), ExpMath.parseAmount("10", 0, 0, fixed))
        assertEquals(ExpMath.Amount(10, true), ExpMath.parseAmount("10L", 0, 0, fixed))
        assertEquals(ExpMath.Amount(10, false), ExpMath.parseAmount("10-50", 0, 0, fixed))
        assertEquals(ExpMath.Amount(100, false), ExpMath.parseAmount("10%", 1000, 0, fixed))
        assertEquals(ExpMath.Amount(3, true), ExpMath.parseAmount("10%L", 0, 30, fixed))
        assertEquals(ExpMath.Amount(50, false), ExpMath.parseAmount("10%[5-50]", 1000, 0, fixed))
        assertEquals(ExpMath.Amount(5, false), ExpMath.parseAmount("10%[5-50]", 10, 0, fixed))
        assertEquals(ExpMath.Amount(100, false), ExpMath.parseAmount("10-20%", 1000, 0, fixed))
        assertNull(ExpMath.parseAmount("열", 0, 0, fixed))
    }

    @Test
    fun `더하기 설정 빼기 초기화`() {
        assertEquals(10 to 0f, ExpMath.apply(ExpMath.Op.SET, ExpMath.Amount(10, true), 3, 0.5f))
        assertEquals(13 to 0.5f, ExpMath.apply(ExpMath.Op.ADD, ExpMath.Amount(10, true), 3, 0.5f))
        assertEquals(0 to 0.5f, ExpMath.apply(ExpMath.Op.TAKE, ExpMath.Amount(10, true), 3, 0.5f))
        assertEquals(0 to 0f, ExpMath.apply(ExpMath.Op.CLEAR, null, 30, 0.5f))
        val (level, _) = ExpMath.apply(ExpMath.Op.ADD, ExpMath.Amount(42, false), 16, 0f)
        assertEquals(17, level) // 16 → 17 은 42
        assertEquals(0 to 0f, ExpMath.apply(ExpMath.Op.TAKE, ExpMath.Amount(1_000_000, false), 5, 0f))
    }

    // --- 최대 체력 ---

    @Test
    fun `최대 체력은 기본값에서 계산하고 범위를 지킨다`() {
        assertEquals(30.0, MaxHpMath.newBase(MaxHpMath.Op.SET, 20.0, 30.0, 20.0, 1000.0))
        assertEquals(24.0, MaxHpMath.newBase(MaxHpMath.Op.ADD, 20.0, 4.0, 20.0, 1000.0))
        assertEquals(1.0, MaxHpMath.newBase(MaxHpMath.Op.TAKE, 20.0, 40.0, 20.0, 1000.0))
        assertEquals(20.0, MaxHpMath.newBase(MaxHpMath.Op.CLEAR, 55.0, 0.0, 20.0, 1000.0))
        assertEquals(1000.0, MaxHpMath.newBase(MaxHpMath.Op.SET, 20.0, 5000.0, 20.0, 1000.0))
    }

    @Test
    fun `체력은 줄면 자르고 늘면 늘어난 만큼 채운다`() {
        assertEquals(10.0, MaxHpMath.healthAfter(15.0, 20.0, 10.0))
        assertEquals(25.0, MaxHpMath.healthAfter(15.0, 20.0, 30.0))
        assertEquals(30.0, MaxHpMath.healthAfter(29.0, 20.0, 30.0))
    }

    @Test
    fun `수정자 묶음과 숫자 모양`() {
        assertEquals(MaxHpMath.Group.GEAR, MaxHpMath.group("inmc", "ruby_sword_max-health"))
        assertEquals(MaxHpMath.Group.SET, MaxHpMath.group("inmc", "set_max-health"))
        assertEquals(MaxHpMath.Group.TITLE, MaxHpMath.group("titleforge", "stat_max_health"))
        assertEquals(MaxHpMath.Group.DUNGEON, MaxHpMath.group("inmcdungeon", "buff_max_health"))
        assertEquals(MaxHpMath.Group.OTHER, MaxHpMath.group("minecraft", "armor.helmet"))
        assertEquals("20", MaxHpMath.format(20.0))
        assertEquals("20.5", MaxHpMath.format(20.5))
    }

    // --- 속도 · 근처 ---

    @Test
    fun `속도는 CMI 단위와 Bukkit 값을 오간다`() {
        assertEquals(0.2f, SpeedMath.toBukkit(1.0, fly = false))
        assertEquals(0.1f, SpeedMath.toBukkit(1.0, fly = true))
        assertEquals(1.0f, SpeedMath.toBukkit(9.0, fly = false))
        assertEquals(2.0, SpeedMath.fromBukkit(0.4f, fly = false), 1e-6)
        assertEquals(3.0, SpeedMath.cap(listOf(1.0, 3.0), 5.0))
        assertEquals(5.0, SpeedMath.cap(emptyList(), 5.0))
        assertEquals(5.0, SpeedMath.cap(listOf(8.0), 5.0))
    }

    @Test
    fun `방향과 대략 거리`() {
        assertEquals(0, NearMath.direction(0.0, -10.0)) // 북
        assertEquals(2, NearMath.direction(10.0, 0.0)) // 동
        assertEquals(4, NearMath.direction(0.0, 10.0)) // 남
        assertEquals(6, NearMath.direction(-10.0, 0.0)) // 서
        assertEquals(1, NearMath.direction(10.0, -10.0)) // 북동
        assertEquals(10, NearMath.approximate(3.0))
        assertEquals(40, NearMath.approximate(37.0))
        assertEquals(150, NearMath.approximate(160.0))
        assertEquals(700, NearMath.approximate(680.0))
    }

    // --- 모자 · 효과 이름 ---

    @Test
    fun `모자 규칙`() {
        val c = PlayerConfig()
        fun item(block: Boolean = false, headwear: Boolean = false, mobHead: Boolean = false, lore: Boolean = false, ench: Boolean = false, mat: String = "x") =
            HatRule.Item(mat, headwear, block, mobHead, lore, ench)
        assertEquals(HatRule.Verdict.OK, HatRule.check(item(block = true), c))
        assertEquals(HatRule.Verdict.OK, HatRule.check(item(headwear = true, lore = true), c))
        assertEquals(HatRule.Verdict.NOT_HAT, HatRule.check(item(), c))
        assertEquals(HatRule.Verdict.ENCHANTED, HatRule.check(item(ench = true), c.copy(hatBlockNoneHat = false)))
        assertEquals(HatRule.Verdict.OK, HatRule.check(item(), c.copy(hatBlockNoneHat = false, hatBlockNoneHatEnchanted = false)))
        assertEquals(HatRule.Verdict.LORE, HatRule.check(item(block = true, lore = true), c.copy(hatBlockWithLore = true)))
        assertEquals(HatRule.Verdict.NOT_WHITELISTED, HatRule.check(item(block = true, mat = "dirt"), c.copy(hatWhitelist = setOf("glass"))))
        assertEquals(HatRule.Verdict.OK, HatRule.check(item(block = true, mat = "glass"), c.copy(hatWhitelist = setOf("glass"))))
    }

    @Test
    fun `옛 효과 이름`() {
        assertEquals("night_vision", EffectNames.key("nightvision"))
        assertEquals("slowness", EffectNames.key("SLOW"))
        assertEquals("speed", EffectNames.key("minecraft:speed"))
    }
}

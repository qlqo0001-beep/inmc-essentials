package com.inmc.essentials.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 시간제 값 · 토템 판단 · 타기·근처·비행 유지 규칙 — 서버 없이(2026-10-10 리뷰 고침). */
class PlayerTimedRulesTest {

    private fun t(raw: String) = TimeArg.parse(raw)!!

    @Test
    fun `끄기 off 0 더하기 빼기는 사람 이름이 아니라 값이다`() {
        for (w in listOf("끄기", "끝", "off", "OFF", "end", "0", "+10m", "-5m", "30", "1시간", "-s")) assertTrue(TimedMath.looksLikeValue(w), w)
        for (w in listOf("Steve", "Officer", "홍길동")) assertFalse(TimedMath.looksLikeValue(w), w)
    }

    @Test
    fun `시간제 다음 값`() {
        assertEquals(TimedMath.Next.To(300), TimedMath.next(null, t("5m"), end = false))
        assertEquals(TimedMath.Next.To(PlayerRecord.UNTIL_RELOG), TimedMath.next(100, t("0"), end = false))
        assertEquals(TimedMath.Next.To(700), TimedMath.next(100, t("+10m"), end = false))
        assertEquals(TimedMath.Next.To(600), TimedMath.next(null, t("+10m"), end = false))
        assertEquals(TimedMath.Next.To(null), TimedMath.next(100, t("-5m"), end = false))
        assertEquals(TimedMath.Next.To(200), TimedMath.next(500, t("-5m"), end = false))
        assertEquals(TimedMath.Next.To(null), TimedMath.next(100, TimeArg.Parsed(0, 0), end = true))
    }

    @Test
    fun `없는 것을 끄거나 빼면 바꾸지 않는다`() {
        assertEquals(TimedMath.Next.NotSet, TimedMath.next(null, TimeArg.Parsed(0, 0), end = true))
        assertEquals(TimedMath.Next.NotSet, TimedMath.next(null, t("-5m"), end = false))
    }

    @Test
    fun `다시 접속할 때까지에는 더하거나 빼지 않는다 - 그냥 시간은 바꾼다`() {
        assertEquals(TimedMath.Next.UntilRelog, TimedMath.next(PlayerRecord.UNTIL_RELOG, t("+10m"), end = false))
        assertEquals(TimedMath.Next.UntilRelog, TimedMath.next(PlayerRecord.UNTIL_RELOG, t("-1m"), end = false))
        assertEquals(TimedMath.Next.To(60), TimedMath.next(PlayerRecord.UNTIL_RELOG, t("1m"), end = false))
        assertEquals(TimedMath.Next.To(null), TimedMath.next(PlayerRecord.UNTIL_RELOG, TimeArg.Parsed(0, 0), end = true))
    }

    @Test
    fun `공용 DB 다시 읽기는 남은 초를 작은 쪽으로`() {
        assertEquals(541L, TimedMath.reconcile(597, 541))
        assertEquals(500L, TimedMath.reconcile(500, 541))
        assertEquals(500L, TimedMath.reconcile(500, null))
        assertNull(TimedMath.reconcile(null, 541))
        assertEquals(PlayerRecord.UNTIL_RELOG, TimedMath.reconcile(PlayerRecord.UNTIL_RELOG, 30))
    }

    @Test
    fun `토템 판단 - 웜업이 쿨타임보다 먼저`() {
        val now = 10_000L
        assertEquals(Totems.Decision.ALLOW_WARMUP, Totems.decide(true, false, false, false, now, now + 60_000, now + 5_000))
        assertEquals(Totems.Decision.ALLOW_WARMUP, Totems.decide(false, true, false, false, now, now + 60_000, now + 5_000))
        assertEquals(Totems.Decision.CANCEL_COOLDOWN, Totems.decide(true, false, false, false, now, now + 60_000, now - 1))
        assertEquals(Totems.Decision.CANCEL_COOLDOWN, Totems.decide(false, true, false, false, now, now + 60_000, null))
        assertEquals(Totems.Decision.HAND, Totems.decide(true, false, false, false, now, now - 1, null))
    }

    @Test
    fun `토템 판단 - 남이 살리거나 막은 부활에는 손대지 않는다`() {
        val now = 10_000L
        assertEquals(Totems.Decision.IGNORE, Totems.decide(false, false, false, false, now, null, null))
        assertEquals(Totems.Decision.IGNORE, Totems.decide(true, true, false, false, now, null, null))
        assertEquals(Totems.Decision.IGNORE, Totems.decide(false, false, true, false, now, null, null))
        assertEquals(Totems.Decision.CANCEL, Totems.decide(true, false, true, false, now, null, null))
        assertEquals(Totems.Decision.IGNORE, Totems.decide(true, false, false, true, now, now + 1000, null))
        assertEquals(Totems.Decision.TRY_INVENTORY, Totems.decide(false, true, false, false, now, null, null))
    }

    @Test
    fun `타기 종류 규칙`() {
        assertFalse(RideRule.allowed("wither", setOf("wither"), hasAll = true, hasType = true))
        assertTrue(RideRule.allowed("horse", emptySet(), hasAll = true, hasType = false))
        assertTrue(RideRule.allowed("horse", emptySet(), hasAll = false, hasType = true))
        assertFalse(RideRule.allowed("horse", emptySet(), hasAll = false, hasType = false))
    }

    @Test
    fun `근처 목록과 가리키기의 숨김 규칙`() {
        assertFalse(NearMath.listed(spectator = true, invisible = false, hide = false, obfuscate = false, forPoint = false))
        assertFalse(NearMath.listed(spectator = true, invisible = false, hide = false, obfuscate = false, forPoint = true))
        assertTrue(NearMath.listed(spectator = false, invisible = true, hide = false, obfuscate = false, forPoint = true))
        assertFalse(NearMath.listed(spectator = false, invisible = true, hide = true, obfuscate = false, forPoint = false))
        assertTrue(NearMath.listed(spectator = false, invisible = true, hide = true, obfuscate = true, forPoint = false))
        assertFalse(NearMath.listed(spectator = false, invisible = true, hide = true, obfuscate = true, forPoint = true))
    }

    @Test
    fun `비행 유지 규칙`() {
        assertTrue(FlightRule.restoreOnGameMode(keepSetting = true, survivalLike = true, wantsFlight = true))
        assertFalse(FlightRule.restoreOnGameMode(keepSetting = true, survivalLike = false, wantsFlight = true))
        assertFalse(FlightRule.restoreOnGameMode(keepSetting = true, survivalLike = true, wantsFlight = false))
        assertTrue(FlightRule.restoreOnRespawn(flewBeforeDeath = true, wantsFlight = false))
        assertFalse(FlightRule.restoreOnRespawn(flewBeforeDeath = false, wantsFlight = false))
    }
}

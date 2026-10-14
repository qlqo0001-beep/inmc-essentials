package com.inmc.essentials.afk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 활동 종류 판단 — 서버 없이(2026-10-10 리뷰 고침). */
class AfkActivityTest {

    @Test
    fun `낚싯대 우클릭은 낚시 활동이고 기본으로 세지 않는다`() {
        assertEquals(AfkConfig.Activity.FISHING, AfkListener.interactActivity(fishingRod = true))
        assertEquals(AfkConfig.Activity.INTERACT, AfkListener.interactActivity(fishingRod = false))
        assertFalse(AfkConfig().counts(AfkListener.interactActivity(fishingRod = true)))
    }
}

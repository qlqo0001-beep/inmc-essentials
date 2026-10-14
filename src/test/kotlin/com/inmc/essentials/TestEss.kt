package com.inmc.essentials

import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Features

/**
 * 서버 없이 기능을 만들기 위한 가짜 [Ess] — 생성자를 부르지 않고 객체만 만든다(JavaPlugin 을 만들 수 없다). 필드는 전부 비어 있다.
 * 그래서 기능은 생성자·프로퍼티 초기화에서 ess 의 서비스를 만지면 안 된다(CLAUDE.md) — 만지면 여기서 NPE 로 드러난다.
 */
object TestEss {

    val ess: Ess by lazy {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Ess::class.java) as Ess
    }

    val features: List<Feature> by lazy { Features.create(ess) }
}

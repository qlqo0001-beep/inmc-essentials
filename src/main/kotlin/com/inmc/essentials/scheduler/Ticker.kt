package com.inmc.essentials.scheduler

import com.inmc.essentials.Ess
import kr.inmc.core.scheduler.TickerBase
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 이 플러그인의 **유일한** 반복 작업, 1초마다(가이드 5.3 — 반복 태스크는 플러그인당 하나). 기능은 따로 타이머를 만들지 않고
 * `ess.ticks.add(이름, 주기초) { now -> }` 로 여기에 건다. 단계마다 격리된다 — 한 기능이 던져도 다른 기능과 저장은 돈다.
 *
 * 준비([Ess.ready]) 전에는 돌지 않는다. 메인 스레드다 — 파일·DB 는 `ess.io`·`ess.db.run` 으로 넘긴다.
 */
class Ticker(private val ess: Ess) : TickerBase(ess.plugin) {

    override val periodTicks: Long = 20L

    private class Task(val name: String, val everySeconds: Int, val block: (Long) -> Unit, val owner: String?)

    private val tasks = CopyOnWriteArrayList<Task>()

    private var count = 0L

    /** [everySeconds] 초마다 [block](이번 회차의 시각, 밀리초). 같은 이름을 다시 걸면 교체. `enable()` 에서 건다. */
    fun add(name: String, everySeconds: Int, block: (now: Long) -> Unit) {
        tasks.removeAll { it.name == name }
        tasks += Task(name, everySeconds.coerceAtLeast(1), block, ess.owner)
    }

    fun remove(name: String) {
        tasks.removeAll { it.name == name }
    }

    /** 켜다 실패한 기능이 건 작업을 뗀다(`ess.owner` 로 적힌 것). */
    internal fun removeOwner(owner: String) {
        tasks.removeAll { it.owner == owner }
    }

    override fun ready(): Boolean = ess.ready

    override fun tick(now: Long) {
        count++
        step("순위") { ess.ranks.tick(now) }
        if (count % SAVE_EVERY == 0L) {
            step("순위 저장") { ess.ranks.flush() }
            step("우편함 저장") { ess.mailbox.flush() }
        }
        if (count % PURGE_EVERY == 0L && ess.config.mailboxExpireSeconds > 0) step("우편함 만료") { ess.mailbox.purgeExpired(now) }
        for (task in tasks) {
            if (count % task.everySeconds == 0L) step(task.name) { task.block(now) }
        }
    }

    private companion object {
        const val SAVE_EVERY = 30L
        const val PURGE_EVERY = 300L
    }
}

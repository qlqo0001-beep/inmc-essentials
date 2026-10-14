package com.inmc.essentials.verify

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.AtomicFiles
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 검사 하나. [run] 은 검증하는 사람을 받아 **실패 까닭**(통과면 null)을 돌려준다. 메인 스레드에서 돈다 — DB 를 기다리지 않는다.
 * 진짜 사건을 쏘고(`Bukkit.getPluginManager().callEvent`), 바꾼 것은 메모리에서만 바꿨다가 `finally` 에서 되돌린다(파일·DB 에 안 쓴다).
 */
class Check(val name: String, val run: (Player) -> String?)

/**
 * `/에센셜 검증` — 뼈대의 검사 + 켜진 기능마다 [com.inmc.essentials.feature.Feature.checks] 를 돌린다(드랍·메뉴 검증기와 같은 방식).
 * 결과는 채팅(실패만)과 `verify/essentials-<시각>.txt`.
 */
class Verifier(private val ess: Ess) {

    data class Result(val name: String, val failure: String?)

    fun run(player: Player) {
        val checks = BASE + ess.features.flatMap { feature ->
            runCatching { feature.checks() }.getOrElse { t -> listOf(Check("검사 목록") { "checks() 가 던졌습니다: $t" }) }
                .map { Check("[${feature.id}] ${it.name}", it.run) }
        }
        val results = checks.map { check ->
            val failure = try {
                check.run(player)
            } catch (t: Throwable) {
                "검증기 오류: " + t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
            }
            Result(check.name, failure)
        }
        val failures = results.filter { it.failure != null }
        ess.messages.send(player, "verify-done", Ph.of().amount((results.size - failures.size).toString()).count(failures.size))
        for (failure in failures) ess.messages.send(player, "verify-failure", Ph.of().value("${failure.name} — ${failure.failure}"))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = ess.io.file("verify", "essentials-$stamp.txt")
        val text = buildString {
            appendLine("# inmc-essentials 검증 - ${LocalDateTime.now()} - ${player.name}")
            for (r in results) appendLine((if (r.failure == null) "PASS " else "FAIL ") + r.name + (r.failure?.let { " — $it" } ?: ""))
        }
        ess.io.asyncRun { AtomicFiles.write(file, text) }
    }

    /** 뼈대 자체의 검사 — 기능이 없어도 도는 것. */
    private val BASE: List<Check> = listOf(
        Check("DB 가 열려 있다") { _ -> if (ess.db.isOpen) null else "DB 를 열지 못했습니다 — 켤 때의 로그를 보세요" },
        Check("접속 기록이 있다") { p -> if (ess.users.get(p.uniqueId) == null) "검증하는 사람의 ess_users 기록이 없습니다 — 접속 리스너를 보세요" else null },
        Check("켜다 실패한 기능이 없다") { _ -> if (ess.failed.isEmpty()) null else "실패: " + ess.failed.joinToString(", ") },
        Check("명령이 서버에 올라가 있다") { _ ->
            val known = Bukkit.getCommandMap().knownCommands.keys
            val missing = (ess.commands.all().map { it.node.literal } + com.inmc.essentials.command.AdminCommand.NAME + com.inmc.essentials.command.HelpCommand.NAME).filter { it !in known }
            if (missing.isEmpty()) null else "없음: " + missing.joinToString(", ") { "/$it" } + " — 다른 플러그인이 같은 이름을 덮었거나 등록 사건 뒤에 들어왔습니다"
        },
    )
}

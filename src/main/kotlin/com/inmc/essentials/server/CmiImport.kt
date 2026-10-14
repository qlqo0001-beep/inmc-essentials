package com.inmc.essentials.server

import com.inmc.essentials.Ess
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.store.LogoutSpot
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.AtomicFiles
import org.bukkit.command.CommandSender
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Level

/**
 * `/에센셜 가져오기 cmi` — CMI 를 빼기 **전에** 한 번. 먼저 읽어서 무엇이 있는지 보이고(`확인` 없이), `확인` 을 붙이면 넣는다.
 *
 * 1. I/O 스레드: [CmiReader] 가 `plugins/CMI` 를 읽어 [CmiData] 를 만든다(못 읽은 줄은 보고서에).
 * 2. 메인: 유저 기본 기록(이름·마지막 접속·나간 시각·나간 자리)을 `ess.users` 에 — 이미 있는 사람은 빈 칸만 채운다.
 * 3. 메인: 켜진 기능 중 [CmiImportable] 을 구현한 것을 차례로(홈·워프·킷·뒤로·뮤트 …). 한 기능이 던져도 나머지는 돈다.
 * 4. 주인이 없는 것(잔고·닉네임·저장 인벤·CMI 쿨타임/대기/비용 규칙 …)은 숫자와 함께 보고서에 — 조용히 버리지 않는다.
 * 5. `import/cmi-<시각>.txt` 에 요약과 못 읽은 줄 전부. CMI 폴더는 고치지 않는다.
 */
class CmiImport(private val ess: Ess, private val server: ServerFeature) {

    private class Prepared(val data: CmiData, val report: ImportReport, val extras: CmiReader.Extras, val at: Long)

    private var prepared: Prepared? = null

    val folder: File get() = File(ess.plugin.dataFolder.parentFile, "CMI")

    fun start(sender: CommandSender, confirm: Boolean) {
        val dir = folder
        if (!dir.isDirectory) return ess.messages.send(sender, "server-import-no-folder", Ph.of().value(dir.path))
        val ready = prepared?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }
        if (confirm && ready != null) {
            prepared = null
            apply(sender, ready)
            return
        }
        ess.messages.send(sender, "server-import-reading", Ph.of().value(dir.path))
        val temp = ess.io.file("import", "tmp")
        ess.io.async({
            val report = ImportReport()
            val reader = CmiReader(dir, temp) { ess.logger.info(it) }
            val data = try {
                reader.read(report)
            } catch (t: Throwable) {
                ess.logger.log(Level.SEVERE, "CMI 읽기 실패", t)
                report.unreadable("전체", "읽다가 멈췄습니다: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            Triple(data, report, reader.extras)
        }) { (data, report, extras) ->
            if (data == null) return@async ess.messages.send(sender, "server-import-failed")
            val p = Prepared(data, report, extras, System.currentTimeMillis())
            if (confirm) apply(sender, p) else {
                prepared = p
                preview(sender, p)
            }
        }
    }

    private fun preview(sender: CommandSender, p: Prepared) {
        val d = p.data
        ess.messages.send(
            sender, "server-import-preview",
            Ph.of().count(d.users.size)
                .raw("{홈}", d.users.sumOf { it.homes.size }.toString())
                .raw("{우편}", d.users.sumOf { it.mail.size }.toString())
                .raw("{워프}", d.warps.size.toString())
                .raw("{파일}", listOfNotNull(
                    d.kits?.let { "킷" }, d.holograms?.let { "홀로그램" }, d.portals?.let { "포탈" }, d.jails?.let { "감옥" }, d.schedules?.let { "일정" },
                    d.playtimeRewards?.let { "플레이 보상" }, d.customAliases?.let { "별칭" }, d.eventCommands?.let { "이벤트 명령" },
                ).joinToString(", ").ifEmpty { "-" })
                .raw("{못읽음}", p.report.details().count { it.startsWith("못 읽음") }.toString()),
        )
        val importers = ess.features.filter { it is CmiImportable }.joinToString(", ") { it.id }
        ess.messages.send(sender, "server-import-confirm", Ph.of().value(importers.ifEmpty { "-" }).time((FRESH_MS / 60000).toString()))
    }

    private fun apply(sender: CommandSender, p: Prepared) {
        val report = p.report
        val data = p.data
        users(data, report)
        // server 기능의 몫(점검 상태·커스텀 텍스트·책 기본값)은 CmiData 에 칸이 없어 읽은 것을 먼저 건넨다 — 아래 차례에서 server.importCmi 가 쓴다.
        server.cmiExtras = p.extras
        for (feature in ess.features) {
            if (feature !is CmiImportable) continue
            try {
                feature.importCmi(data, report)
            } catch (t: Throwable) {
                ess.logger.log(Level.SEVERE, "CMI 가져오기 — 기능 ${feature.id} 실패", t)
                report.unreadable("기능 ${feature.id}", "가져오다 멈췄습니다: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        server.cmiExtras = null
        val missing = listOf("homes", "warps", "kits", "teleport", "spawn", "mail", "moderation", "admininfo", "chat", "portals", "holograms", "commands", "server")
            .filter { id -> ess.features.none { it.id == id && it is CmiImportable } }
        if (missing.isNotEmpty()) report.note("가져오기를 하지 않은(꺼졌거나 구현이 없는) 기능: ${missing.joinToString(", ")} — 그 자료는 넣지 않았습니다")
        extras(p.extras, report)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = ess.io.file("import", "cmi-$stamp.txt")
        val text = buildString {
            appendLine("# CMI 가져오기 — ${LocalDateTime.now()} — ${sender.name}")
            appendLine("# 원본: ${folder.path} (고치지 않음)")
            appendLine()
            appendLine("## 요약")
            report.summary().forEach { appendLine(it) }
            appendLine()
            appendLine("## 자세히(건너뜀 · 못 읽음 · 알림)")
            report.details().forEach { appendLine(it) }
        }
        ess.io.asyncRun { AtomicFiles.write(file, text) }
        ess.messages.send(sender, "server-import-done", Ph.of().value("plugins/inmc-essentials/import/${file.name}"))
        for (line in report.summary()) ess.messages.send(sender, "server-import-summary-line", Ph.of().value(line))
        val bad = report.details().count { it.startsWith("못 읽음") }
        if (bad > 0) ess.messages.send(sender, "server-import-unreadable", Ph.of().count(bad))
        // CMI 가 아직 돌면 가져온 이벤트 명령·일정은 꺼 두었다(commands 기능) — 두 번 도는 일이 없게. 켜는 법은 보고서에.
        if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("CMI") && (data.eventCommands != null || data.schedules != null)) {
            ess.messages.send(sender, "server-import-cmi-running")
        }
    }

    /** 유저 기본 기록 — 없으면 만들고, 있으면 빈 칸(나간 시각·나간 자리)만 채운다. */
    private fun users(data: CmiData, report: ImportReport) {
        for (u in data.users) {
            val logout = u.logout?.let { LogoutSpot(it.world, it.x, it.y, it.z, it.yaw, it.pitch) }
            val existing = ess.users.get(u.id)
            if (existing == null) {
                val last = u.lastLogin ?: u.lastLogoff ?: 0L
                // CMI 에는 첫 접속 시각이 없다 — 마지막 접속으로 둔다(보고서에 적는다).
                ess.users.save(UserRecord(u.id, u.name, firstJoin = last, lastJoin = last, lastQuit = u.lastLogoff, logout = logout))
                report.imported("유저 기록")
            } else {
                val filled = existing.copy(lastQuit = existing.lastQuit ?: u.lastLogoff, logout = existing.logout ?: logout)
                if (filled != existing) {
                    ess.users.save(filled)
                    report.imported("유저 기록")
                } else {
                    report.skipped("유저 기록", "${u.name} — 이미 기록이 있습니다")
                }
            }
        }
        if (data.users.isNotEmpty()) report.note("새로 만든 유저 기록의 첫 접속 시각은 CMI 에 없어 마지막 접속 시각으로 두었습니다")
    }

    private fun extras(x: CmiReader.Extras, report: ImportReport) {
        if (x.balances > 0) report.skipped("잔고", "${x.balances}명의 CMI Balance — inmc-economy 가 돈의 진실이라 옮기지 않습니다")
        if (x.nicknames.isNotEmpty()) report.note("닉네임 ${x.nicknames.size}개는 옮기지 않습니다(타이틀포지가 닉네임의 주인 — 직접 다시 정하세요): ${x.nicknames.joinToString(", ")}")
        if (x.bans > 0) report.note("CMI 밴 기록 ${x.bans}명 — 유저의 extra(BannedUntil·BanReason …)로 넘겼습니다. moderation 이 읽지 않으면 바닐라 /ban 으로")
        if (x.playtimeRows > 0) report.note("CMI playtime 표 ${x.playtimeRows}줄 — 날짜별 합을 유저 extra 의 playtimeDaily 로 넘겼습니다")
        if (x.playtimeRewardRows > 0) report.note("CMI playtimereward 표 ${x.playtimeRewardRows}줄 — 받은 한 번 보상 기록은 admininfo 가 옮겼습니다(반복 보상 기록 repeatable 은 옮기지 않는다 — 다음 받기부터 새로 셈)")
        if (x.inventoryRows > 0) report.unreadable("저장 인벤", "CMI inventories 표 ${x.inventoryRows}줄 — CMI 직렬화 아이템이라 옮기지 않습니다(/인벤저장 으로 다시)")
        for (r in x.cooldownRules) report.skipped("CMI 쿨타임 규칙", "$r — 명령 이름이 CMI 것입니다. settings/commands.yml rules 에 우리 명령 이름으로 다시 적으세요")
        for (r in x.warmupRules) report.skipped("CMI 대기 규칙", "$r — 명령 이름이 CMI 것입니다. settings/commands.yml rules 에 다시 적으세요")
        for (r in x.costRules) report.skipped("CMI 비용 규칙", "$r — 명령 이름이 CMI 것입니다. settings/commands.yml rules 에 다시 적으세요")
    }

    companion object {
        const val FRESH_MS = 5 * 60_000L
    }
}

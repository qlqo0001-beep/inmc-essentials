package com.inmc.essentials.moderation

import com.google.gson.JsonParser
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiUser
import com.inmc.essentials.service.ImportReport
import java.util.Date

/**
 * CMI → moderation. 뮤트·감옥(정의 + 갇힌 사람)·경고·메모·유저 메타·수갑·경보·IP 기록·IP 잠금·밴(→ Bukkit BanList, 이미 있으면 건너뜀).
 * 메인 스레드 — 넣기는 메모리 + `ModStore`(쓰기 스레드). 이미 있는 것은 건너뛰고, 모르는 모양은 [ImportReport.unreadable] 로(조용히 버리지 않는다).
 *
 * 형식(CMI 9.8.9.8 jar 로 확인): 경고 열 = JSON `{주었던 때(ms): [준 사람, (분류), (사유)]}` · 메모 = JSON `{때: [쓴 사람, 내용]}` 또는
 * `쓴 사람:때:내용` · `Ips`/`LockedIps` = `1_2_3_4`(점 대신 밑줄) 목록 · 밴 = `BannedUntil`·`BannedAt`·`BannedBy`·`BanReason` 열.
 */
class CmiModImport(private val mod: Moderation, private val jails: JailService, private val bans: Bans) {

    private val state get() = mod.state
    private val store get() = mod.store

    fun run(data: CmiData, report: ImportReport) {
        importJails(data, report)
        val now = System.currentTimeMillis()
        for (user in data.users) {
            mute(user, report, now)
            jail(user, report, now)
            warnings(user, report, now)
            notes(user, report)
            meta(user, report)
            extras(user, report, now)
        }
    }

    private fun importJails(data: CmiData, report: ImportReport) {
        val yaml = data.jails ?: return
        val found = JailCodec.fromCmi(yaml) { report.unreadable(JAIL_DEF, it) }
        for (jail in found) {
            report.read(JAIL_DEF)
            if (jails.jail(jail.name) != null) {
                report.skipped(JAIL_DEF, "감옥 '${jail.name}' 이(가) 이미 있습니다")
                continue
            }
            if (!ModRules.validName(jail.name)) report.note("감옥 이름 '${jail.name}' 은(는) 지금 이름 규칙에 맞지 않지만 그대로 가져옵니다")
            jails.replace(jail)
            report.imported(JAIL_DEF)
        }
    }

    private fun mute(user: CmiUser, report: ImportReport, now: Long) {
        val m = user.mute ?: return
        report.read(MUTE)
        if (state.activeMute(user.id, now) != null) return report.skipped(MUTE, "${user.name} — 이미 뮤트되어 있습니다")
        if (m.until != null && m.until <= now) return report.skipped(MUTE, "${user.name} — 이미 끝난 뮤트")
        val mute = Mute(user.id, m.until, m.reason ?: mod.settings.muteDefaultReason, "CMI", m.shadow, now)
        state.mutes[user.id] = mute
        store.putMute(mute)
        report.imported(MUTE)
    }

    private fun jail(user: CmiUser, report: ImportReport, now: Long) {
        val j = user.jail ?: return
        report.read(JAILED)
        if (state.isJailed(user.id)) return report.skipped(JAILED, "${user.name} — 이미 갇혀 있습니다")
        val jail = j.jail?.let { jails.jail(it) } ?: jails.jails.firstOrNull()
            ?: return report.unreadable(JAILED, "${user.name} — 감옥 '${j.jail}' 이(가) 없습니다(CMI Jails.yml 을 못 가져왔거나 비었습니다)")
        val remaining = when {
            j.until == null -> {
                report.note("${user.name} — CMI 에 풀릴 때가 없어 기본 시간(${mod.settings.jailDefaultSeconds}초)으로 가둡니다")
                mod.settings.jailDefaultSeconds * 1000
            }
            j.until <= now -> return report.skipped(JAILED, "${user.name} — 이미 끝난 감옥")
            else -> j.until - now
        }
        val cell = j.cell?.takeIf { jail.cell(it) != null }
        val jailed = Jailed(user.id, jail.name, cell, remaining, now, j.reason ?: mod.settings.jailDefaultReason, "CMI", now, null)
        state.jailed[user.id] = jailed
        store.putJailed(jailed)
        report.imported(JAILED)
    }

    private fun warnings(user: CmiUser, report: ImportReport, now: Long) {
        for (raw in user.warnings) {
            val parsed = parseWarnings(raw) ?: run {
                report.unreadable(WARN, "${user.name} — ${raw.take(200)}")
                null
            } ?: continue
            for (w in parsed) {
                report.read(WARN)
                // 둘째 칸 하나뿐이면 분류인지 사유인지 모른다 — 분류 이름과 맞으면 분류로.
                val asCategory = if (w.category == null) w.reason?.let { mod.settings.category(it) } else null
                val category = asCategory ?: mod.settings.category(w.category ?: "") ?: mod.settings.warnDefault
                val reason = if (asCategory != null) null else w.reason
                val expires = category.lifeSeconds.takeIf { it > 0 }?.let { w.at + it * 1000 }
                if (expires != null && expires <= now) {
                    report.skipped(WARN, "${user.name} — 만료된 경고(${mod.dateText(w.at)})")
                    continue
                }
                if (state.warnings[user.id].orEmpty().any { it.created == w.at }) {
                    report.skipped(WARN, "${user.name} — 같은 때의 경고가 이미 있습니다")
                    continue
                }
                val warning = Warning(state.serial.incrementAndGet(), user.id, category.name, category.points, reason ?: category.reason, w.by ?: "CMI", w.at, expires)
                state.warnings.compute(user.id) { _, list -> list.orEmpty() + warning }
                store.insertWarning(warning)
                report.imported(WARN)
            }
        }
    }

    private fun notes(user: CmiUser, report: ImportReport) {
        val now = System.currentTimeMillis()
        val days = mod.settings.notesExpireDays
        for (raw in user.notes) {
            val parsed = parseNotes(raw)
            if (parsed.isEmpty()) {
                report.unreadable(NOTE, "${user.name} — ${raw.take(200)}")
                continue
            }
            for (n in parsed) {
                report.read(NOTE)
                if (state.notes[user.id].orEmpty().any { it.text == n.text }) {
                    report.skipped(NOTE, "${user.name} — 같은 메모가 이미 있습니다")
                    continue
                }
                val at = n.at?.let { normalizeMillis(it) } ?: now
                // 보관 기간(notes.expire-days)이 지난 메모는 들여와도 10초 뒤 만료 틱이 지운다 — 들여오지 않고 건너뛴 것으로 남긴다.
                if (days > 0 && at <= now - days * 86_400_000L) {
                    report.skipped(NOTE, "${user.name} — 보관 기간(${days}일)이 지난 메모(${mod.dateText(at)}): ${n.text.take(60)}")
                    continue
                }
                val note = Note(state.serial.incrementAndGet(), user.id, n.text, n.by ?: "CMI", at)
                state.notes.compute(user.id) { _, list -> list.orEmpty() + note }
                store.insertNote(note)
                report.imported(NOTE)
            }
        }
    }

    private fun meta(user: CmiUser, report: ImportReport) {
        for ((rawKey, value) in user.userMeta) {
            report.read(META)
            // /유저메타 가 받는 키 규칙(ModRules.validName — 한글·영문·숫자·_- · 32자)과 같게, 소문자로 — 가져온 뒤 명령으로 고치고 지울 수 있게.
            val key = rawKey.trim().lowercase()
            if (!ModRules.validName(key)) {
                report.unreadable(META, "${user.name} — 키가 이름 규칙(한글·영문·숫자·_- · 32자)에 맞지 않습니다: ${rawKey.take(80)} = ${value.take(40)}")
                continue
            }
            if (state.meta[user.id]?.containsKey(key) == true) {
                report.skipped(META, "${user.name} — 메타 '$key' 이(가) 이미 있습니다")
                continue
            }
            state.meta.compute(user.id) { _, map -> LinkedHashMap(map.orEmpty()).apply { put(key, value) } }
            store.putMeta(user.id, key, value)
            report.imported(META)
        }
    }

    private fun extras(user: CmiUser, report: ImportReport, now: Long) {
        val x = user.extra
        if (x["Cuffed"]?.let { it == "1" || it.equals("true", true) } == true) {
            report.read(CUFF)
            if (state.isCuffed(user.id)) report.skipped(CUFF, "${user.name} — 이미 수갑")
            else {
                val cuff = Cuff(user.id, "CMI", now)
                state.cuffs[user.id] = cuff
                store.putCuff(cuff)
                report.imported(CUFF)
            }
        }
        x["AlertUntil"]?.toLongOrNull()?.let { until ->
            report.read(ALERT)
            if (until <= now) return@let report.skipped(ALERT, "${user.name} — 끝난 경보")
            if (state.activeAlert(user.id, now) != null) return@let report.skipped(ALERT, "${user.name} — 이미 경보가 있습니다")
            val alert = Alert(user.id, x["AlertReason"], "CMI", until)
            state.alerts[user.id] = alert
            store.putAlert(alert)
            report.imported(ALERT)
        }
        x["Ips"]?.let { raw ->
            val ips = ModRules.cmiIps(raw) { report.unreadable(IPS, "${user.name} — $it") }
            for ((ip, times) in ips) {
                report.read(IPS)
                if (state.ips[user.id].orEmpty().any { it.ip == ip }) {
                    report.skipped(IPS, "${user.name} — $ip 이미 기록됨")
                    continue
                }
                val seen = IpSeen(user.id, ip, user.lastLogin ?: now, user.lastLogin ?: now, times.coerceAtLeast(1))
                state.ips.compute(user.id) { _, list -> list.orEmpty() + seen }
                store.putIp(seen)
                report.imported(IPS)
            }
        }
        x["LockedIps"]?.let { raw ->
            val ips = ModRules.cmiIps(raw) { report.unreadable(LOCK, "${user.name} — $it") }
            for (ip in ips.keys) {
                report.read(LOCK)
                if (state.lockIps[user.id]?.contains(ip) == true) {
                    report.skipped(LOCK, "${user.name} — $ip 이미 있음")
                    continue
                }
                state.lockIps.compute(user.id) { _, set -> LinkedHashSet(set.orEmpty()).apply { add(ip) } }
                store.putLockIp(user.id, ip)
                report.imported(LOCK)
            }
        }
        ban(user, report, now)
    }

    private fun ban(user: CmiUser, report: ImportReport, now: Long) {
        val x = user.extra
        val until = x["BannedUntil"]?.toLongOrNull()
        val reason = x["BanReason"]
        val at = x["BannedAt"]?.toLongOrNull()
        if (until == null && reason == null && at == null) return
        report.read(BAN)
        if (bans.entry(user.id, user.name) != null) return report.skipped(BAN, "${user.name} — Bukkit 밴 목록에 이미 있습니다")
        val expires: Date? = when {
            until == null -> return report.unreadable(BAN, "${user.name} — 풀릴 때(BannedUntil)가 없어 영구인지 이미 풀렸는지 알 수 없습니다. 사유: $reason · 때: $at")
            until < 0 -> null
            until <= now -> return report.skipped(BAN, "${user.name} — 이미 끝난 밴")
            else -> Date(until)
        }
        val entry = bans.profiles.addBan(bans.profile(user.id, user.name), reason ?: mod.settings.banDefaultReason, expires, x["BannedBy"] ?: "CMI")
        at?.let { runCatching { entry?.created = Date(it); entry?.save() } }
        report.imported(BAN)
    }

    // --- 형식 ------------------------------------------------------------------------------------

    data class CmiWarning(val at: Long, val by: String?, val category: String?, val reason: String?)

    data class CmiNote(val at: Long?, val by: String?, val text: String)

    companion object {
        const val JAIL_DEF = "감옥"
        const val JAILED = "갇힌 사람"
        const val MUTE = "뮤트"
        const val WARN = "경고"
        const val NOTE = "메모"
        const val META = "유저메타"
        const val CUFF = "수갑"
        const val ALERT = "경보"
        const val IPS = "IP 기록"
        const val LOCK = "IP 잠금"
        const val BAN = "밴"

        /** CMI 의 때는 밀리초지만, 초 단위로 적힌 값(1e11 미만 — 1973년 이전의 밀리초는 없다)이면 밀리초로 올린다. */
        fun normalizeMillis(at: Long): Long = if (at in 1..99_999_999_999L) at * 1000 else at

        /** 경고 열 하나 — JSON `{때: [준 사람, 분류?, 사유?]}`. 모양이 다르면 null. */
        fun parseWarnings(raw: String): List<CmiWarning>? {
            val text = raw.trim()
            if (!text.startsWith("{")) return null
            val root = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject } ?: return null
            val out = ArrayList<CmiWarning>()
            for ((key, value) in root.asJsonObject.entrySet()) {
                val at = key.toLongOrNull() ?: return null
                val parts = when {
                    value.isJsonArray -> value.asJsonArray.map { if (it.isJsonNull) null else it.asString }
                    value.isJsonPrimitive -> listOf(value.asString)
                    else -> return null
                }
                // [준 사람] · [준 사람, 사유] · [준 사람, 분류, 사유] — 분류는 기본이 아닐 때만 들어 있다.
                val (category, reason) = when (parts.size) {
                    0, 1 -> null to null
                    2 -> null to parts[1]
                    else -> parts[1] to parts.drop(2).joinToString(" ")
                }
                out += CmiWarning(at, parts.getOrNull(0), category, reason)
            }
            return out
        }

        /** 메모 하나 — JSON `{때: [쓴 사람, 내용]}`, `쓴 사람:때:내용`, 그 밖은 내용 그대로. */
        fun parseNotes(raw: String): List<CmiNote> {
            val text = raw.trim()
            if (text.isEmpty()) return emptyList()
            if (text.startsWith("{")) {
                val root = runCatching { JsonParser.parseString(text) }.getOrNull()
                if (root != null && root.isJsonObject) {
                    val out = ArrayList<CmiNote>()
                    for ((key, value) in root.asJsonObject.entrySet()) {
                        val parts = when {
                            value.isJsonArray -> value.asJsonArray.map { if (it.isJsonNull) "" else it.asString }
                            value.isJsonPrimitive -> listOf("", value.asString)
                            else -> continue
                        }
                        val note = parts.drop(1).joinToString(" ").ifBlank { parts.firstOrNull().orEmpty() }
                        if (note.isNotBlank()) out += CmiNote(key.toLongOrNull(), parts.firstOrNull()?.takeIf { parts.size > 1 && it.isNotBlank() }, note)
                    }
                    return out
                }
            }
            val p = text.split(':', limit = 3)
            if (p.size == 3 && p[1].trim().toLongOrNull() != null) return listOf(CmiNote(p[1].trim().toLong(), p[0].ifBlank { null }, p[2]))
            return listOf(CmiNote(null, null, text))
        }
    }
}

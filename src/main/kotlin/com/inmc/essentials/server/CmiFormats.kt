package com.inmc.essentials.server

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.inmc.essentials.service.CmiHome
import com.inmc.essentials.service.CmiJail
import com.inmc.essentials.service.CmiLocation
import com.inmc.essentials.service.CmiMail
import com.inmc.essentials.service.CmiMute
import java.util.UUID
import kotlin.math.abs

/**
 * CMI 9.8.9.8 의 저장 형식 읽기(순수 — 시험이 서버 없이 돈다). CMI jar(`PlayerManager`·`CMIJson`·`CMILocation`)를 javap 로 본 것과
 * 테섭 `cmi.sqlite.db` 표본으로 맞췄다. **모르는 모양은 [Bad] 로 던진다** — 부른 쪽이 보고서의 "못 읽음" 에 원문과 함께 적는다.
 *
 * 위치: DB 열(`LogOutLocation`·`TeleportLocation`·`DeathLocation`)은 `world:x:y:z:pitch:yaw` — CMI 가 읽을 때 `invertLoc` 로 둘을 바꾼다
 * (표본 `spawn:-24.14:81.0:162.8:15.34:-100.95` 의 -100.95 는 pitch 일 수 없다). config·YAML 은 `world;x;y;z;yaw;pitch`.
 * 홈 안의 위치는 `locationToString`(yaw 다음 pitch) — 그래서 어느 쪽인지 값으로 한 번 더 본다([location]).
 */
object CmiFormats {

    class Bad(message: String) : Exception(message)

    enum class Order { PITCH_YAW, YAW_PITCH }

    /**
     * 위치 하나. `:` 와 `;` 둘 다 받는다(소수점 `,` 도 — CMI 가 `,` → `.` 로 바꿔 읽는다). 4칸(방향 없음)도 받는다.
     * 방향 두 칸 중 하나가 ±90 을 넘으면 그것이 yaw — 아니면 [order] 대로.
     */
    fun location(raw: String?, order: Order): CmiLocation? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sep = if (text.contains(';')) ';' else ':'
        val parts = text.split(sep)
        if (parts.size < 4) throw Bad("위치 칸이 모자랍니다: $text")
        fun num(i: Int): Double = parts[i].trim().replace(',', '.').toDoubleOrNull() ?: throw Bad("위치 숫자가 아닙니다(${i + 1}번째): $text")
        val world = parts[0].trim().ifEmpty { throw Bad("월드가 비었습니다: $text") }
        val x = num(1)
        val y = num(2)
        val z = num(3)
        if (parts.size < 6) return CmiLocation(world, x, y, z)
        val a = num(4).toFloat()
        val b = num(5).toFloat()
        val (yaw, pitch) = when {
            abs(a) > 90f && abs(b) <= 90f -> a to b
            abs(b) > 90f && abs(a) <= 90f -> b to a
            order == Order.PITCH_YAW -> b to a
            else -> a to b
        }
        return CmiLocation(world, x, y, z, yaw, pitch)
    }

    private fun json(raw: String): JsonElement =
        try {
            JsonParser.parseString(raw)
        } catch (e: Exception) {
            throw Bad("JSON 이 아닙니다: ${e.message}")
        }

    /**
     * 홈 — `CMIJson.serialize(Map<이름, List>)`: `{"이름":["위치","p",슬롯,"재료","f","b"]}`(위치 다음은 있는 것만, 순서 무관 표식).
     * 옛 형식(키 `이름$-p$-슬롯$-재료`, 값 위치 문자열)도 받는다. 값이 문자열이면 위치로 본다.
     */
    fun homes(raw: String?, problem: (String) -> Unit): List<CmiHome> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "{}" } ?: return emptyList()
        val root = json(text)
        if (!root.isJsonObject) throw Bad("홈이 JSON 객체가 아닙니다: ${text.take(120)}")
        val out = mutableListOf<CmiHome>()
        for ((key, value) in root.asJsonObject.entrySet()) {
            try {
                val nameParts = key.split("\$-")
                var name = nameParts[0]
                var private = nameParts.drop(1).any { it == "p" }
                var icon: String? = nameParts.drop(1).firstOrNull { it.isNotEmpty() && it != "p" && it != "f" && it != "b" && it.toIntOrNull() == null }
                val loc: CmiLocation? = when {
                    value.isJsonPrimitive -> location(value.asString, Order.YAW_PITCH)
                    value.isJsonArray -> {
                        val arr = value.asJsonArray
                        if (arr.size() == 0) throw Bad("빈 목록")
                        for (i in 1 until arr.size()) {
                            val e = arr[i]
                            if (!e.isJsonPrimitive) continue
                            val s = e.asString
                            when {
                                s == "p" -> private = true
                                s == "f" || s == "b" -> {}
                                s.toIntOrNull() != null -> {}
                                s.isNotBlank() -> icon = s
                            }
                        }
                        location(arr[0].asString, Order.YAW_PITCH)
                    }
                    value.isJsonObject -> {
                        val o = value.asJsonObject
                        val l = listOf("l", "loc", "location", "Location").firstNotNullOfOrNull { k -> o.get(k)?.takeIf { it.isJsonPrimitive }?.asString }
                            ?: throw Bad("위치 키(l·loc)가 없습니다")
                        if (o.get("p")?.let { it.isJsonPrimitive && it.asString.toBooleanStrictOrNull() == true } == true) private = true
                        icon = o.get("material")?.takeIf { it.isJsonPrimitive }?.asString ?: o.get("m")?.takeIf { it.isJsonPrimitive }?.asString ?: icon
                        location(l, Order.YAW_PITCH)
                    }
                    else -> throw Bad("모르는 값")
                }
                if (name.isBlank()) name = "home"
                out += CmiHome(name, loc ?: throw Bad("위치가 없습니다"), icon, private)
            } catch (e: Bad) {
                problem("홈 '$key' — ${e.message}: ${value.toString().take(160)}")
            } catch (e: Exception) {
                problem("홈 '$key' — ${e.javaClass.simpleName}: ${value.toString().take(160)}")
            }
        }
        return out
    }

    /**
     * 우편 — `CMIJson.serializeList`: `[["보낸이","시각","내용","보관"]]`. 옛 형식(줄마다 `보낸이:시각:내용`)도.
     * 4번째 칸은 보관 기간(초 — CMI `PlayerMail.getKeepFor`, `time + keepFor*1000` 에 지운다). 없거나 `0`·빈 칸이면 null(CMI 기본 만료), 숫자가 아니면 알리고 null.
     */
    fun mail(raw: String?, problem: (String) -> Unit): List<CmiMail> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "[]" } ?: return emptyList()
        if (!text.startsWith("[")) {
            return text.split('\n').filter { it.isNotBlank() }.map { line ->
                val p = line.split(':', limit = 3)
                if (p.size == 3) CmiMail(p[0], p[1].toLongOrNull(), p[2]) else CmiMail(null, null, line)
            }
        }
        val root = json(text)
        if (!root.isJsonArray) throw Bad("우편이 JSON 목록이 아닙니다")
        val out = mutableListOf<CmiMail>()
        for (e in root.asJsonArray) {
            when {
                e.isJsonArray -> {
                    val a: JsonArray = e.asJsonArray
                    fun s(i: Int): String? = if (i < a.size() && a[i].isJsonPrimitive) a[i].asString else null
                    val message = s(2)
                    if (message == null) {
                        problem("우편 — 내용 칸이 없습니다: $e")
                        continue
                    }
                    out += CmiMail(s(0), s(1)?.toLongOrNull(), message, keepFor(s(3), problem))
                }
                e.isJsonObject -> {
                    val o: JsonObject = e.asJsonObject
                    val message = listOf("message", "msg", "m").firstNotNullOfOrNull { o.get(it)?.asString }
                    if (message == null) {
                        problem("우편 — 내용 키가 없습니다: $e")
                        continue
                    }
                    out += CmiMail(
                        listOf("sender", "s").firstNotNullOfOrNull { o.get(it)?.asString },
                        listOf("time", "t").firstNotNullOfOrNull { o.get(it)?.asString?.toLongOrNull() },
                        message,
                        keepFor(listOf("keepFor", "keep", "k").firstNotNullOfOrNull { o.get(it)?.asString }, problem),
                    )
                }
                e.isJsonPrimitive -> out += CmiMail(null, null, e.asString)
                else -> problem("우편 — 모르는 모양: $e")
            }
        }
        return out
    }

    /** 우편 보관 칸 → 초. 없음·빈 칸·`0` 이하는 null, 숫자가 아니면 [problem] 에 알리고 null. */
    fun keepFor(raw: String?, problem: (String) -> Unit): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = text.toLongOrNull()
        if (n == null) {
            problem("우편 — 보관 기간 칸을 읽지 못했습니다(기본 만료로 넣습니다): $text")
            return null
        }
        return n.takeIf { it > 0 }
    }

    /** 킷 사용 — `{"킷":{"t":마지막 시각,"u":횟수}}`. 옛 형식 `킷-X-시각;…` 도. */
    fun kits(raw: String?, problem: (String) -> Unit): Map<String, Long> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "{}" } ?: return emptyMap()
        if (!text.startsWith("{")) {
            val out = LinkedHashMap<String, Long>()
            for (part in text.split(';').filter { it.isNotBlank() }) {
                val p = part.split("-X-")
                val t = p.getOrNull(1)?.trim()?.toLongOrNull()
                if (t == null) problem("킷 — 옛 형식을 읽지 못했습니다: $part") else out[p[0]] = t
            }
            return out
        }
        val root = json(text)
        if (!root.isJsonObject) throw Bad("킷이 JSON 객체가 아닙니다")
        val out = LinkedHashMap<String, Long>()
        for ((kit, value) in root.asJsonObject.entrySet()) {
            val t = when {
                value.isJsonObject -> value.asJsonObject.get("t")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
                value.isJsonPrimitive -> value.asString.toLongOrNull()
                else -> null
            }
            if (t == null) problem("킷 '$kit' — 시각(t)이 없습니다: $value") else out[kit] = t
        }
        return out
    }

    /** 무시 목록 — UUID 를 `;` 로 이은 것. */
    fun ignores(raw: String?, problem: (String) -> Unit): List<UUID> =
        raw?.split(';', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.mapNotNull { s ->
            runCatching { UUID.fromString(s) }.getOrNull() ?: null.also { problem("무시 목록 — UUID 가 아닙니다: $s") }
        }.orEmpty()

    /** 뮤트 — `해제시각:사유`(해제시각 0·음수 = 영구). */
    fun mute(raw: String?): CmiMute? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val until = text.substringBefore(':').trim().toLongOrNull() ?: throw Bad("뮤트 해제 시각이 숫자가 아닙니다: $text")
        val reason = text.substringAfter(':', "").takeIf { it.isNotBlank() && it != "null" }
        if (until in 1 until System.currentTimeMillis()) return null // 이미 풀림
        return CmiMute(until.takeIf { it > 0 }, reason)
    }

    /** 감옥 — `감옥이름:셀번호:가둔사람UUID` + 따로 해제 시각·사유. */
    fun jail(raw: String?, until: Long?, reason: String?): CmiJail? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val p = text.split(':')
        return CmiJail(p.getOrNull(0)?.takeIf { it.isNotBlank() }, p.getOrNull(1)?.toIntOrNull(), until?.takeIf { it > 0 }, reason?.takeIf { it.isNotBlank() })
    }

    /** JSON 지도(UserMeta) → 문자열 지도. */
    fun meta(raw: String?): Map<String, String> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "{}" } ?: return emptyMap()
        val root = json(text)
        if (!root.isJsonObject) throw Bad("UserMeta 가 JSON 객체가 아닙니다")
        return root.asJsonObject.entrySet().associate { (k, v) -> k to (if (v.isJsonPrimitive) v.asString else v.toString()) }
    }

    /** 목록 열(경고·메모) — JSON 목록이면 그 원소, 아니면 줄·`;;` 로 나눈다. */
    fun list(raw: String?): List<String> {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() && it != "[]" } ?: return emptyList()
        if (text.startsWith("[")) {
            val root = runCatching { JsonParser.parseString(text) }.getOrNull()
            if (root != null && root.isJsonArray) return root.asJsonArray.map { if (it.isJsonPrimitive) it.asString else it.toString() }
        }
        return text.split("\n", ";;").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 시간제 비행·무적 — 끝나는 시각(밀리초)이면 남은 초로, 작은 수면 그대로 초. 끝났으면 null. */
    fun remainingSeconds(value: Long?, now: Long): Long? {
        if (value == null || value <= 0) return null
        return if (value > 1_000_000_000_000L) ((value - now) / 1000).takeIf { it > 0 } else value
    }
}

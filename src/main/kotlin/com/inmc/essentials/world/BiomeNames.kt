package com.inmc.essentials.world

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.integration.PlaceNames
import kr.inmc.core.util.VanillaNames
import org.bukkit.block.Biome

/**
 * 생물군계 이름 — 타이틀포지 이름(`places.yml`) → 공식 한국어 이름(core [VanillaNames]) → id 순으로 보여 주고, **셋 다** 입력으로 받는다
 * (사용자 결정 2026-10-10, research '# findbiome'). 데이터팩 생물군계도 서버 레지스트리에 함께 있으므로 그대로 들어온다.
 *
 * 색인은 부를 때마다 새로 만든다 — 생물군계가 100개 남짓이라 싸고, 타이틀포지 GUI 로 이름을 바꾸면 바로 반영된다.
 * 같은 이름이 둘 이상(기본값의 '관목 지대' = brushland·shrubland, 네임스페이스만 다른 데이터팩 바이옴)이면 **전부** 찾는다.
 */
class BiomeNames {

    data class Entry(val biome: Biome, val key: String, val titleForge: String?, val korean: String?) {
        val display: String get() = titleForge ?: korean ?: key
        val path: String get() = key.substringAfter(':')
    }

    fun entries(): List<Entry> {
        val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
        return registry.map { biome ->
            @Suppress("DEPRECATION") // Biome.getKey — 레지스트리에서 꺼낸 것은 늘 열쇠가 있다
            val key = biome.key.asString()
            Entry(biome, key, titleForgeName(key), runCatching { VanillaNames.ofKey(biome.translationKey()) }.getOrNull())
        }.sortedWith(compareBy({ !it.key.startsWith("minecraft:") }, { it.key }))
    }

    fun displayOf(biome: Biome): String {
        @Suppress("DEPRECATION")
        val key = biome.key.asString()
        return titleForgeName(key) ?: runCatching { VanillaNames.ofKey(biome.translationKey()) }.getOrNull() ?: key
    }

    /** 입력에 맞는 생물군계들. [Match.exact] 가 비면 [Match.candidates](부분 일치) 중에서 고르게 알린다. */
    fun resolve(input: String, within: Collection<Entry> = entries()): Match = match(input, within)

    // --- 타이틀포지 (core 장소 이름 창구 — 2026-10-15 전에는 biomeNameOf 리플렉션) ----------------------------------

    /** 타이틀포지 이름(평문). 타이틀포지가 없거나 이름을 안 바꿨으면 null — 공식 이름으로 물러난다. */
    fun titleForgeName(key: String): String? = PlaceNames.biome(key)?.let(PlaceNames::plain)?.takeIf { it.isNotEmpty() }

    data class Match(val exact: List<Entry>, val candidates: List<Entry>)

    companion object {

        /** 비교용 — 소문자, 빈칸·밑줄·하이픈 없음. `해바라기 평원` = `해바라기평원`, `sunflower_plains` = `Sunflower Plains`. */
        fun normalize(text: String): String = text.lowercase().filterNot { it.isWhitespace() || it == '_' || it == '-' }

        /** 이 항목을 부르는 이름들(정규화). */
        fun aliases(entry: Entry): Set<String> = aliasesOf(entry.titleForge, entry.korean, entry.key)

        /**
         * 순수 판정 — 정확히 맞는 것(이름 셋·id 둘 중 하나) 전부, 없으면 들어 있는 것(부분 일치) 전부를 후보로.
         * 부분 일치가 **이름 하나**로만 모이면(같은 이름의 바이옴 둘) 그것도 정확한 것으로 본다.
         */
        fun match(input: String, within: Collection<Entry>): Match =
            matchBy(input, within, ::aliases) { it.display }.let { (exact, candidates) -> Match(exact, candidates) }

        /** [match] 의 알맹이 — 서버 없이 시험하려고 항목 종류를 열어 둔다(`Biome` 은 서버 레지스트리가 있어야 만들어진다). */
        fun <T> matchBy(input: String, within: Collection<T>, aliases: (T) -> Set<String>, display: (T) -> String): Pair<List<T>, List<T>> {
            val want = normalize(input)
            if (want.isEmpty()) return emptyList<T>() to emptyList()
            val exact = within.filter { want in aliases(it) }
            if (exact.isNotEmpty()) return exact to emptyList()
            val partial = within.filter { e -> aliases(e).any { it.contains(want) } }
            val names = partial.map { normalize(display(it)) }.toSet()
            return if (partial.isNotEmpty() && names.size == 1) partial to emptyList() else emptyList<T>() to partial
        }

        /** 이름들(정규화) — 순수. */
        fun aliasesOf(titleForge: String?, korean: String?, key: String): Set<String> = listOfNotNull(titleForge, korean, key, key.substringAfter(':'))
            .map(::normalize).filter { it.isNotEmpty() }.toSet()

        /** 동굴(지하) 생물군계 — 세로로도 여러 높이를 보고, 찾으면 지표가 아니라 그 높이 근처로 보낸다. 데이터팩의 `cave/…`·`…_caves` 도. */
        fun isCave(key: String): Boolean {
            val path = key.substringAfter(':').lowercase()
            return path == "deep_dark" || path.endsWith("caves") || path.endsWith("_cave") || path.startsWith("cave/") || path.contains("/cave") ||
                path.contains("underground") || path.contains("cavern")
        }

        fun isDeepDark(key: String): Boolean = key.substringAfter(':').lowercase() == "deep_dark"
    }
}

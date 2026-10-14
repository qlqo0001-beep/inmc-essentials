package com.inmc.essentials.server

import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.PlaceholderAPIPlugin
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.java.JavaPlugin

/**
 * PlaceholderAPI 의 등록된 확장 보기(`/플레이스홀더`). PAPI 클래스를 부르는 곳은 이 객체뿐 — **PlaceholderAPI 가 켜져 있을 때만** 부른다
 * (없는 서버에서 이 클래스가 읽히지 않게 — 컴파일 의존은 PAPI 하나지만 실행에는 없을 수 있다).
 *
 * 묶음은 확장 클래스를 실은 플러그인(`JavaPlugin.getProvidingPlugin` — `getPlugin()` 은 거의 전부 null). PAPI 확장 폴더의 jar 에서 온 것은
 * "PlaceholderAPI(확장 파일)". 목록은 확장의 `getPlaceholders()`(inmc 확장 8개는 2026-10-10 부터 다 낸다).
 */
object PapiView {

    data class Expansion(val identifier: String, val plugin: String, val author: String, val version: String, val placeholders: List<String>)

    fun expansions(): List<Expansion> =
        PlaceholderAPIPlugin.getInstance().localExpansionManager.expansions.map { exp ->
            val owner = runCatching { JavaPlugin.getProvidingPlugin(exp.javaClass).name }.getOrNull() ?: "PlaceholderAPI(확장 파일)"
            Expansion(
                identifier = exp.identifier,
                plugin = owner,
                author = runCatching { exp.author }.getOrNull().orEmpty(),
                version = runCatching { exp.version }.getOrNull().orEmpty(),
                placeholders = runCatching { exp.placeholders.orEmpty() }.getOrDefault(emptyList()),
            )
        }.sortedWith(compareBy({ it.plugin.lowercase() }, { it.identifier.lowercase() }))

    fun parse(player: OfflinePlayer?, text: String): String = PlaceholderAPI.setPlaceholders(player, text)
}

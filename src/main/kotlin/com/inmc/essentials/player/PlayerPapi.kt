package com.inmc.essentials.player

import com.inmc.essentials.Ess
import kr.inmc.core.util.Durations
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

/**
 * `%inmcplayer_…%` — CMI 의 `%cmi_user_flying%` · `%cmi_user_tfly%` · `%cmi_user_god%` 를 같은 뜻으로(cmd-fly · cmd-tfly).
 * PlaceholderAPI 클래스는 compileOnly 라 **켜져 있을 때만** [setup] 이 이 파일의 클래스를 만진다(moderation `ModPapi` 와 같은 틀).
 */
class PlayerPapi(private val ess: Ess, private val feature: PlayerFeature) {

    private var expansion: PlaceholderExpansion? = null

    fun setup() {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return
        try {
            expansion = PlayerExpansion(ess, feature).also { it.register() }
        } catch (t: Throwable) {
            ess.logger.warning("PlaceholderAPI(inmcplayer) 연결 실패: ${t.message}")
            expansion = null
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
    }
}

private class PlayerExpansion(private val ess: Ess, private val feature: PlayerFeature) : PlaceholderExpansion() {

    override fun getIdentifier(): String = ID
    override fun getAuthor(): String = "INMC"
    override fun getVersion(): String = ess.plugin.pluginMeta.version
    override fun persist(): Boolean = true

    override fun getPlaceholders(): List<String> = KEYS.map { "%${ID}_$it%" }

    /** 어느 스레드에서나 불린다 — 메모리 표(ConcurrentHashMap)와 접속자의 능력 값만 읽는다. 접속 안 한 사람은 빈 값. */
    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val id = player?.uniqueId ?: return ""
        val online = player.player
        val record = feature.states.get(id)
        return when (params.lowercase()) {
            "flying" -> (online?.isFlying == true).toString()
            "canfly" -> (online?.allowFlight == true).toString()
            "god" -> (online != null && feature.isGod(online)).toString()
            "tfly_left" -> left(record.tflyLeft)
            "tgod_left" -> left(record.tgodLeft)
            "tfly_seconds" -> seconds(record.tflyLeft)
            "tgod_seconds" -> seconds(record.tgodLeft)
            "collision" -> record.collision.toString()
            else -> null
        }
    }

    /** 남은 시간 글(없으면 빈 값 · 다시 접속할 때까지면 그 말). */
    private fun left(value: Long?): String = when {
        value == null -> ""
        value == PlayerRecord.UNTIL_RELOG -> ess.messages.raw("player-until-relog")
        else -> Durations.formatShort(value)
    }

    /** 남은 초(없으면 0 · 다시 접속할 때까지면 -1). */
    private fun seconds(value: Long?): String = (value ?: 0L).toString()

    companion object {
        const val ID = "inmcplayer"
        val KEYS = listOf("flying", "canfly", "god", "tfly_left", "tgod_left", "tfly_seconds", "tgod_seconds", "collision")
    }
}

package com.inmc.essentials.chat

import com.inmc.essentials.Ess
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

/**
 * `%inmcchat_range%` — CMI `%cmi_chat_range%`(지금 공개 채팅이 닿는 거리)의 대신. 거리 채팅이 꺼졌거나 동적 거리가 0 이면 메시지
 * `chat-papi-unlimited`(기본 "무제한"). PlaceholderAPI 클래스는 compileOnly 라 **켜져 있을 때만** [setup] 이 이 파일의 클래스를 만진다(ModPapi 와 같은 모양).
 */
class ChatPapi(private val ess: Ess, private val feature: ChatFeature) {

    private var expansion: PlaceholderExpansion? = null

    fun setup() {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return
        try {
            expansion = ChatExpansion(ess, feature).also { it.register() }
        } catch (t: Throwable) {
            ess.logger.warning("PlaceholderAPI(inmcchat) 연결 실패: ${t.message}")
            expansion = null
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
    }
}

private class ChatExpansion(private val ess: Ess, private val feature: ChatFeature) : PlaceholderExpansion() {

    override fun getIdentifier(): String = ID
    override fun getAuthor(): String = "INMC"
    override fun getVersion(): String = ess.plugin.pluginMeta.version
    override fun persist(): Boolean = true

    override fun getPlaceholders(): List<String> = listOf("%${ID}_range%")

    /** 어느 스레드에서나 불린다 — 설정 스냅샷과 접속 인원만 읽는다. */
    override fun onRequest(player: OfflinePlayer?, params: String): String? = when (params.lowercase()) {
        "range" -> ChatRules.generalRange(feature.settings, Bukkit.getOnlinePlayers().size)?.toInt()?.toString()
            ?: ess.messages.raw("chat-papi-unlimited")
        else -> null
    }

    companion object {
        const val ID = "inmcchat"
    }
}

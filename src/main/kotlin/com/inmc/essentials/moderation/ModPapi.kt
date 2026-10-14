package com.inmc.essentials.moderation

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

/**
 * `%inmcmod_…%` — CMI 의 `%cmi_user_meta_<키>%` · `%cmi_user_metaint_<키>%` · `%cmi_user_muted%` · `%cmi_user_cuffed%` · 감옥·경고를 같은 뜻으로.
 * PlaceholderAPI 클래스는 compileOnly 라 **켜져 있을 때만** [setup] 이 이 파일의 클래스를 만진다.
 */
class ModPapi(private val mod: Moderation) {

    private var expansion: PlaceholderExpansion? = null

    fun setup() {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return
        try {
            expansion = ModExpansion(mod).also { it.register() }
        } catch (t: Throwable) {
            mod.ess.logger.warning("PlaceholderAPI(inmcmod) 연결 실패: ${t.message}")
            expansion = null
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
    }
}

private class ModExpansion(private val mod: Moderation) : PlaceholderExpansion() {

    override fun getIdentifier(): String = ID
    override fun getAuthor(): String = "INMC"
    override fun getVersion(): String = mod.ess.plugin.pluginMeta.version
    override fun persist(): Boolean = true

    override fun getPlaceholders(): List<String> = KEYS.map { "%${ID}_$it%" }

    /** 어느 스레드에서나 불린다 — 메모리 표만 읽는다. */
    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val state = mod.state
        val now = System.currentTimeMillis()
        val p = params.lowercase()
        // 감옥별 인원(동적 표지판·홀로그램) — 사람이 없어도 된다.
        if (p.startsWith("jail_count_")) return mod.feature.jails.occupants(params.substring("jail_count_".length)).size.toString()
        if (p == "chat_locked") return (state.lockChat(now) != null).toString()
        val id = player?.uniqueId ?: return ""
        if (p.startsWith("meta_")) return state.meta[id]?.get(p.substring(5)).orEmpty()
        if (p.startsWith("metaint_")) return state.meta[id]?.get(p.substring(8))?.toDoubleOrNull()?.toLong()?.toString() ?: "0"
        return when (p) {
            "muted" -> (state.activeMute(id, now)?.let { !it.shadow } == true).toString()
            "mute_time" -> state.activeMute(id, now)?.takeIf { !it.shadow }?.let { mod.timeText(it.remainingSeconds(now)) }.orEmpty()
            "mute_reason" -> state.activeMute(id, now)?.takeIf { !it.shadow }?.reason.orEmpty()
            "cuffed" -> state.isCuffed(id).toString()
            "jailed" -> state.isJailed(id).toString()
            "jail_name" -> state.jailed[id]?.jail.orEmpty()
            "jail_cell" -> state.jailed[id]?.cell?.toString().orEmpty()
            "jail_time" -> state.jailed[id]?.let { JailService.remainingText(mod, it) }.orEmpty()
            "warnings" -> state.activeWarnings(id, now).size.toString()
            "warning_points" -> state.points(id, now).toString()
            "notes" -> state.activeNotes(id).size.toString()
            else -> null
        }
    }

    companion object {
        const val ID = "inmcmod"
        val KEYS = listOf(
            "meta_<키>", "metaint_<키>", "muted", "mute_time", "mute_reason", "cuffed", "jailed", "jail_name", "jail_cell", "jail_time",
            "jail_count_<감옥>", "warnings", "warning_points", "notes", "chat_locked",
        )
    }
}

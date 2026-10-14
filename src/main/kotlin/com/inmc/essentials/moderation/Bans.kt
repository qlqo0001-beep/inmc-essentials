package com.inmc.essentials.moderation

import com.destroystokyo.paper.profile.PlayerProfile
import com.inmc.essentials.store.UserRecord
import com.inmc.essentials.util.Ph
import io.papermc.paper.ban.BanListType
import net.kyori.adventure.text.Component
import org.bukkit.BanEntry
import org.bukkit.Bukkit
import org.bukkit.ban.IpBanList
import org.bukkit.ban.ProfileBanList
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerKickEvent
import java.net.InetAddress
import java.util.Date
import java.util.UUID

/**
 * 밴 — **Bukkit 의 BanList(프로필·IP)** 위에 만든다(계획 4절). 바닐라 `/ban`·`/pardon`·`/banlist` 와 같은 목록이라 둘이 어긋나지 않는다.
 * 기간밴 = 만료 시각이 있는 항목. 목록 파일(`banned-players.json`)은 바닐라가 쓴다 — 바닐라 `/ban` 처럼 메인에서 부른다(한 줄 쓰기).
 */
class Bans(private val mod: Moderation) {

    private val ess get() = mod.ess

    val profiles: ProfileBanList get() = Bukkit.getBanList(BanListType.PROFILE)
    val ips: IpBanList get() = Bukkit.getBanList(BanListType.IP)

    fun profile(id: UUID, name: String?): PlayerProfile = Bukkit.createProfile(id, name)

    fun entry(id: UUID, name: String? = null): BanEntry<PlayerProfile>? = profiles.getBanEntry(profile(id, name))

    fun ipEntry(ip: String): BanEntry<InetAddress>? = address(ip)?.let { ips.getBanEntry(it) }

    fun profileEntries(): List<BanEntry<PlayerProfile>> {
        val set: Set<BanEntry<PlayerProfile>> = profiles.getEntries()
        val now = System.currentTimeMillis()
        return set.filter { e -> e.expiration?.time?.let { it > now } ?: true }.sortedByDescending { it.created?.time ?: 0L }
    }

    fun ipEntries(): List<BanEntry<InetAddress>> {
        val set: Set<BanEntry<InetAddress>> = ips.getEntries()
        val now = System.currentTimeMillis()
        return set.filter { e -> e.expiration?.time?.let { it > now } ?: true }.sortedByDescending { it.created?.time ?: 0L }
    }

    /** 사람 밴(기간밴이면 [seconds]). */
    fun ban(sender: CommandSender, user: UserRecord, seconds: Long?, reason: String?, silent: Boolean) {
        if (!mod.canTarget(sender, user.id, user.name, ModerationFeature.BAN_BYPASS)) return
        val why = reason?.takeIf { it.isNotBlank() } ?: mod.settings.banDefaultReason
        val expires = seconds?.let { Date(System.currentTimeMillis() + it * 1000) }
        profiles.addBan(profile(user.id, user.name), why, expires, mod.who(sender))
        val time = mod.timeText(seconds)
        val ph = Ph.of().player(user.name).reason(why).time(time).raw("{관리자}", mod.who(sender))
        val online = Bukkit.getPlayer(user.id)
        online?.kick(ess.messages.component("moderation-ban-screen", ph), PlayerKickEvent.Cause.BANNED)
        mod.announce(sender, if (seconds == null) "moderation-ban-broadcast" else "moderation-tempban-broadcast", ph, online, silent, mod.settings.banBroadcast)
        if (sender is Player) ess.messages.send(sender, "moderation-ban-done", ph)
        mod.runCommands(mod.settings.onBan, user.name, why, time)
    }

    /**
     * IP 밴 — [input] 은 IP 또는 사람(접속 중이면 그 주소, 아니면 마지막 IP). 보호: 이름을 쳤으면 그 사람(오프라인이면 op) · 그 IP 로 접속 중인
     * 보호 권한자 · 그 IP 를 쓴 적 있는 보호 대상(오프라인 op) — 하나라도 있으면 거절.
     */
    fun ipBan(sender: CommandSender, input: String, seconds: Long?, reason: String?, silent: Boolean) {
        val (ip, label, user) = resolveIp(sender, input) ?: return
        if (user != null && !mod.canTarget(sender, user.id, user.name, ModerationFeature.IPBAN_BYPASS)) return
        val holders = Bukkit.getOnlinePlayers().filter { it.address?.address?.hostAddress == ip }
        if (sender is Player && holders.any { it == sender }) return ess.messages.send(sender, "moderation-self")
        holders.firstOrNull { it.hasPermission(ModerationFeature.IPBAN_BYPASS) }?.let {
            return ess.messages.send(sender, "moderation-protected", Ph.of().player(it.name))
        }
        mod.state.usersOf(ip).firstOrNull { it != user?.id && Bukkit.getPlayer(it) == null && mod.isProtected(it, ModerationFeature.IPBAN_BYPASS) }?.let {
            return ess.messages.send(sender, "moderation-protected", Ph.of().player(ess.users.get(it)?.name ?: it.toString()))
        }
        val address = address(ip) ?: return ess.messages.send(sender, "moderation-lockip-invalid", Ph.of().value(ip))
        val why = reason?.takeIf { it.isNotBlank() } ?: mod.settings.banDefaultReason
        val expires = seconds?.let { Date(System.currentTimeMillis() + it * 1000) }
        ips.addBan(address, why, expires, mod.who(sender))
        val time = mod.timeText(seconds)
        val ph = Ph.of().player(label).reason(why).time(time).raw("{관리자}", mod.who(sender)).raw("{아이피}", ip).count(holders.size)
        val screen = ess.messages.component("moderation-ipban-screen", ph)
        for (p in holders) p.kick(screen, PlayerKickEvent.Cause.IP_BANNED)
        // IP 는 공개 방송에 싣지 않는다 — 이름만(사람을 쳤을 때). IP 를 쳤으면 운영진에게만.
        val public = mod.settings.banBroadcast && label != ip
        mod.announce(sender, if (seconds == null) "moderation-ipban-broadcast" else "moderation-tempipban-broadcast", ph, holders.firstOrNull(), silent, public)
        if (sender is Player) ess.messages.send(sender, "moderation-ipban-done", ph)
        mod.runCommands(mod.settings.onBan, label, why, time)
    }

    /** 밴 해제 — IP 면 IP, 아니면 사람. */
    fun unban(sender: CommandSender, input: String, silent: Boolean) {
        if (ModRules.isIp(input)) {
            val address = address(input) ?: return
            if (!ips.isBanned(address)) return ess.messages.send(sender, "moderation-unban-not-banned", Ph.of().player(input))
            ips.pardon(address)
            val ph = Ph.of().player(input).raw("{관리자}", mod.who(sender))
            mod.announce(sender, "moderation-unban-broadcast", ph, null, silent = true)
            ess.messages.send(sender, "moderation-unban-done", ph)
            return
        }
        val user = mod.user(sender, input) ?: return
        val profile = profile(user.id, user.name)
        if (profiles.getBanEntry(profile) == null) return ess.messages.send(sender, "moderation-unban-not-banned", Ph.of().player(user.name))
        profiles.pardon(profile)
        val ph = Ph.of().player(user.name).raw("{관리자}", mod.who(sender))
        mod.announce(sender, "moderation-unban-broadcast", ph, null, silent, mod.settings.banBroadcast)
        if (sender is Player) ess.messages.send(sender, "moderation-unban-done", ph)
        mod.runCommands(mod.settings.onUnban, user.name)
    }

    /** 접속 거부 화면(우리 문구). */
    fun screen(entry: BanEntry<*>, ip: Boolean): Component {
        val now = System.currentTimeMillis()
        val left = entry.expiration?.time?.let { ((it - now) / 1000).coerceAtLeast(1) }
        val ph = Ph.of().reason(entry.reason ?: mod.settings.banDefaultReason).time(mod.timeText(left)).raw("{관리자}", entry.source ?: "?")
        return ess.messages.component(if (ip) "moderation-ipban-screen" else "moderation-ban-screen", ph)
    }

    /** IP 를 친 것이면 그대로, 사람이면 접속 주소 → 마지막 IP. (ip, 공지에 쓸 이름, 친 사람(IP 를 쳤으면 null)). */
    private fun resolveIp(sender: CommandSender, input: String): Triple<String, String, UserRecord?>? {
        if (ModRules.isIp(input)) return Triple(input, input, null)
        val user = mod.user(sender, input) ?: return null
        val ip = Bukkit.getPlayer(user.id)?.address?.address?.hostAddress ?: user.lastIp
            ?: mod.state.ips[user.id]?.maxByOrNull { it.lastSeen }?.ip
        if (ip == null) {
            ess.messages.send(sender, "moderation-ip-unknown", Ph.of().player(user.name))
            return null
        }
        return Triple(ip, user.name, user)
    }

    /** 글자 그대로의 IP 만(이름 찾기 — DNS — 를 하지 않는다). */
    fun address(ip: String): InetAddress? = if (ModRules.isIp(ip)) runCatching { InetAddress.getByName(ip) }.getOrNull() else null

    /** 이름·uuid 로 쓰는 프로필 밴 대상의 이름. */
    fun nameOf(entry: BanEntry<PlayerProfile>): String =
        entry.banTarget.name ?: entry.banTarget.id?.let { ess.users.get(it)?.name } ?: entry.banTarget.id?.toString() ?: "?"

    /** 목록에 보일 대상 글(사람 이름 · IP). */
    fun label(entry: BanEntry<*>): String = when (val t = entry.banTarget) {
        is PlayerProfile -> t.name ?: t.id?.let { ess.users.get(it)?.name } ?: t.id?.toString() ?: "?"
        is InetAddress -> t.hostAddress
        else -> t?.toString() ?: "?"
    }

    /** /밴해제 에 넘길 글(사람은 uuid · IP 는 주소). */
    fun key(entry: BanEntry<*>): String = when (val t = entry.banTarget) {
        is PlayerProfile -> t.id?.toString() ?: t.name ?: "?"
        is InetAddress -> t.hostAddress
        else -> t?.toString() ?: "?"
    }
}

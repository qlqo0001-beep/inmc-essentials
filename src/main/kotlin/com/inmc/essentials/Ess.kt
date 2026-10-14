package com.inmc.essentials

import com.inmc.essentials.command.Args
import com.inmc.essentials.command.CommandRegistry
import com.inmc.essentials.config.EssConfig
import com.inmc.essentials.config.Messages
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.scheduler.Ticker
import com.inmc.essentials.service.Abilities
import com.inmc.essentials.service.AfkState
import com.inmc.essentials.service.CommandGate
import com.inmc.essentials.service.KitsApi
import com.inmc.essentials.service.Teleporter
import com.inmc.essentials.store.EssDb
import com.inmc.essentials.store.UserService
import com.inmc.essentials.util.Ph
import kr.inmc.core.config.ConfigService
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.EconomyHook
import kr.inmc.core.integration.Mail
import kr.inmc.core.integration.MMOItemsHook
import kr.inmc.core.item.ItemMatcher
import kr.inmc.core.item.ItemResolver
import kr.inmc.core.rank.RankService
import kr.inmc.core.rank.Rankable
import kr.inmc.core.reward.Mailbox
import kr.inmc.core.reward.RewardHost
import kr.inmc.core.reward.RewardService
import kr.inmc.core.reward.RewardSettings
import kr.inmc.core.util.Placeholders
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 플러그인을 엮는 서비스 로케이터. 기능은 이것 하나만 받는다(`Feature(ess)`).
 *
 * [RewardHost] 를 구현한다 — core 의 보상([rewards])·우편함([mailbox])·순위([ranks])를 기능이 그대로 쓰게(킷·플레이 시간 보상·투표 보상).
 * 리로드는 [config]·[messages] 스냅샷만 바꿔 끼우고 서비스는 다시 만들지 않는다.
 *
 * 기능끼리의 접점은 여기 있는 여섯뿐이다 — [teleporter]·[afk]·[kits]·[gate]·[moderation]·[abilities]. CMI 가져오기는 [com.inmc.essentials.service.CmiImportable]. 주인 기능이 `enable()` 에서 바꿔 끼우고, 쓰는 쪽은 **부를 때마다**
 * 여기서 읽는다(붙들어 두지 않는다 — 켜는 순서와 무관하게).
 */
class Ess(override val plugin: JavaPlugin) : RewardHost {

    val logger: java.util.logging.Logger = plugin.logger

    override val io = ConfigService(plugin)

    // --- 설정 (리로드로 통째 교체) --------------------------------------------------
    @Volatile
    var config: EssConfig = EssConfig()

    @Volatile
    var messages: Messages = Messages.build(emptyList(), emptyList())

    /**
     * 가방이 차서 남는 보상은 우편으로(`rewards.drop-when-full: false`) — 단 **우편 공급처가 있을 때만**. mail 기능이 꺼졌거나 켜다 실패해
     * [Mail.available] 이 아니면 core 우편함(`data/mailbox.yml`)에 쌓여도 꺼낼 화면이 없으므로 바닥에 떨군다(지금 하던 대로 — 둘 중 하나만).
     */
    override val rewardSettings: RewardSettings
        get() = config.let { if (it.dropWhenInventoryFull || Mail.available) it else it.copy(dropWhenInventoryFull = true) }

    override fun tell(target: CommandSender, key: String, ph: Placeholders?) = messages.send(target, key, ph as? Ph)

    override fun messageComponent(key: String, ph: Placeholders?): Component = messages.component(key, ph as? Ph)

    /** core 가 쓰는 뜻 이름(subject·player·item·rank·season·count)을 우리 토큰으로. */
    override fun placeholders(vararg pairs: Pair<String, String>): Placeholders {
        val ph = Ph.of()
        for ((name, value) in pairs) when (name) {
            "subject" -> ph.subject(value)
            "player" -> ph.player(value)
            "item" -> ph.item(value)
            "rank" -> ph.rank(value)
            "season" -> ph.season(value)
            "count" -> ph.count(value.toLongOrNull() ?: 0L)
            else -> ph.raw(name, value)
        }
        return ph
    }

    // --- 연동 (전부 선택) --------------------------------------------------------
    val mmoItems = MMOItemsHook(logger)
    val customItems = CustomItemHook(logger)
    override val economy = EconomyHook(logger)
    override val itemResolver = ItemResolver(mmoItems, customItems, logger)
    val itemMatcher = ItemMatcher(mmoItems, customItems)

    // --- core 의 보상·순위 ----------------------------------------------------------
    override val mailbox = Mailbox(this)
    override val rewards = RewardService(this)
    override val ranks = RankService(this)

    /** 순위를 붙일 대상 — 기능이 `enable()` 에서 더한다(플레이 시간·투표 순위 …). */
    override val rankables: MutableList<Rankable> = CopyOnWriteArrayList()

    // --- 뼈대 ---------------------------------------------------------------------
    val db = EssDb(logger)
    val users = UserService(this)
    val commands = CommandRegistry(this)
    val ticks = Ticker(this)
    val args = Args(this)

    // --- 기능 사이 접점 (주인 기능이 바꿔 끼운다) ----------------------------------------
    /** 모든 이동이 지나는 곳 — teleport 기능이 바꿔 끼운다. */
    @Volatile
    var teleporter: Teleporter = Teleporter.PLAIN

    /** 잠수 상태 — afk 기능이 바꿔 끼운다. */
    @Volatile
    var afk: AfkState = AfkState.NEVER

    /** 첫 접속 킷 — kits 기능이 넣는다(꺼져 있으면 null). */
    @Volatile
    var kits: KitsApi? = null

    /** "성공해야 쿨타임" — commands 기능이 넣는다(꺼져 있으면 null). */
    @Volatile
    var gate: CommandGate? = null

    /** 뮤트·감옥·수갑 상태 — moderation 기능이 바꿔 끼운다(꺼져 있으면 아무도 묶이지 않은 것으로). */
    @Volatile
    var moderation: com.inmc.essentials.service.ModerationState = com.inmc.essentials.service.ModerationState.NONE

    /** 무적·비행·시간제 상태 — player 기능이 바꿔 끼운다(꺼져 있으면 아무도 무적이 아니고 바꾸는 것은 아무 일도 하지 않는다). world·admininfo 가 본다. */
    @Volatile
    var abilities: Abilities = Abilities.NONE

    // --- 기능 -----------------------------------------------------------------------
    /**
     * 켜져 돌고 있는 기능(목록 순서). 켜다 실패한 것은 [failed]. 켜는 동안에는 **하나 켜질 때마다** 늘어난다 — 어떤 기능의 `enable()` 안에서
     * 보이는 것은 목록에서 그보다 앞서 켜진 기능뿐이다.
     */
    @Volatile
    var features: List<Feature> = emptyList()
        internal set

    /** 켜다(load·enable) 던진 기능의 id. */
    @Volatile
    var failed: Set<String> = emptySet()
        internal set

    /**
     * 그 기능이 지금 돌고 있나(설정에서 켰고 켜다 실패하지 않았다).
     *
     * **`enable()` 안에서는 목록에서 앞선 기능만 true** 다(뒤 기능은 아직 안 켜졌다). 다른 기능을 보고 무엇을 엮을지는 [Feature.ready] 에서
     * 정하거나(그때는 전부 켜져 있다), 부를 때마다 이것을 본다. 설정에서 켰는지만 알면 되면 `ess.config.enabled(id)`.
     */
    fun enabled(id: String): Boolean = features.any { it.id == id }

    /** 돌고 있는 기능. 기능끼리 import 하지 않는 규칙은 그대로다 — 켜짐 확인·관리 화면용. */
    fun feature(id: String): Feature? = features.firstOrNull { it.id == id }

    /**
     * 지금 켜고 있는 기능의 id(메인 스레드, 켜는 동안만). 그동안 들어온 리스너·명령·틱·권한에 주인으로 적힌다 — 그 기능이 켜다 던지면
     * 그 몫만 되돌린다(`EssentialsPlugin`).
     */
    @Volatile
    var owner: String? = null
        internal set

    private val listenersByOwner = ConcurrentHashMap<String, MutableList<Listener>>()

    /** 리스너 등록(이 플러그인 몫으로 — 꺼질 때 Bukkit 이 함께 뗀다). */
    fun listen(listener: Listener) {
        plugin.server.pluginManager.registerEvents(listener, plugin)
        owner?.let { listenersByOwner.computeIfAbsent(it) { CopyOnWriteArrayList() } += listener }
    }

    /** 켜다 실패한 기능이 [listen] 으로 건 리스너를 뗀다. */
    internal fun unlisten(owner: String) {
        listenersByOwner.remove(owner)?.forEach { HandlerList.unregisterAll(it) }
    }

    /** DB·순위·우편함을 다 읽고 기능의 `ready()` 가 끝나면 true. 그 전에는 틱이 돌지 않는다. */
    @Volatile
    var ready: Boolean = false
        private set

    internal fun markReady() {
        ready = true
    }

    companion object {

        /** 관리 루트 권한(`paper-plugin.yml` 에 선언 — 기능 권한은 런타임 등록). */
        const val ADMIN = "inmcessentials.admin"

        /** PDC 네임스페이스 — 상수(플러그인 이름을 쓰지 않는다). `NamespacedKey(NAMESPACE, "…")`. */
        const val NAMESPACE = "inmcess"

        /** core `Realms` 기능 목록(inmc-worlds 의 기능 토글 화면)의 묶음 이름 — 기능마다 `Realms.register`/`unregisterFeature`. */
        const val REALM_GROUP = "에센셜"
    }
}

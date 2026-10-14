package com.inmc.essentials

import com.inmc.essentials.config.EssConfig
import com.inmc.essentials.config.Messages
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Features
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.logging.Level

/**
 * 배선만. 켜는 순서: config → 메시지 → DB(모든 기능의 표) → 기능 load(설정) → enable → 명령 등록 → 틱 → (첫 틱에 순위·우편함을 읽고) ready → 준비 끝.
 * 끄는 순서: 기능 disable(거꾸로) → 틱 → 권한 → 순위·우편함 → DB → I/O.
 *
 * 기능 하나가 만들다·켜다 던지면 그 기능만 빠진다(`ess.failed`, `/에센셜 모듈` 에 ✖) — 나머지는 돈다. 켜다 던진 기능은 그때까지 건 것을
 * 되돌린다([rollback]: 그 기능의 `disable()` · 명령 · 리스너 · 틱 · 권한 · 바꿔 끼운 접점 · 순위 대상).
 */
class EssentialsPlugin : JavaPlugin() {

    private lateinit var ess: Ess

    /** 우리가 core `Text` 에 건 PlaceholderAPI 연결 — 내려갈 때 그것이 우리 것일 때만 뗀다. */
    private var papi: ((Player?, String) -> String)? = null

    /** 우리가 올린 기능 권한 — 끌 때 내린다(`/reload` 뒤 다시 올릴 때 "이미 있음" 으로 던지지 않게). */
    private val addedPermissions = mutableListOf<String>()

    /** 켜기 전 접점·순위 대상 — 켜다 던진 기능이 바꿔 끼운 것을 되돌릴 때. */
    private class Before(ess: Ess) {
        val teleporter = ess.teleporter
        val afk = ess.afk
        val kits = ess.kits
        val gate = ess.gate
        val moderation = ess.moderation
        val abilities = ess.abilities
        val rankables = ess.rankables.toList()
    }

    /** 순위·우편함을 다 읽었나 — 읽기 전에 쓰면 빈 상태로 파일을 덮는다. */
    private var rewardsLoaded = false

    override fun onEnable() {
        ess = Ess(this)
        ess.io.copyDefault("config.yml", ess.io.file("config.yml"))
        ess.config = EssConfig.from(ess.io.load(ess.io.file("config.yml")))

        // 기능 하나의 생성자가 던져도 나머지는 산다 — 그 기능은 표도 만들지 않는다.
        val failed = mutableSetOf<String>()
        val all = Features.ALL.mapNotNull { entry ->
            try {
                Features.create(entry, ess)
            } catch (t: Throwable) {
                logger.log(Level.SEVERE, "기능 ${entry.id} 만들기 실패", t)
                if (ess.config.enabled(entry.id)) failed += entry.id
                null
            }
        }
        val active = all.filter { ess.config.enabled(it.id) }
        ess.messages = Messages.build(active, messageFiles(active).map { ess.io.load(it) })

        ess.mmoItems.setup()
        ess.customItems.setup()
        ess.economy.setup()

        try {
            val schemas = listOf<Pair<String, (kr.inmc.core.store.SqlSource) -> Unit>>("users" to ess.users::schema) +
                all.map { feature -> feature.id to { source: kr.inmc.core.store.SqlSource -> feature.schema(source) } }
            ess.db.open(ess.io.file("data", "essentials.db"), ess.config.storage, schemas)
            ess.users.loadAll()
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "DB 를 열지 못했습니다 — inmc-essentials 를 끕니다", t)
            server.pluginManager.disablePlugin(this)
            return
        }

        ess.listen(kr.inmc.core.listener.MenuListener(ess))
        ess.listen(ess.users)

        val running = mutableListOf<Feature>()
        for (feature in active) {
            val before = Before(ess)
            ess.owner = feature.id
            val ok = try {
                guard(feature, "켜기") {
                    registerPermissions(feature)
                    feature.load(settingsOf(feature))
                    feature.enable()
                }
            } finally {
                ess.owner = null
            }
            if (ok) {
                running += feature
                // 하나 켜질 때마다 — 뒤 기능의 enable() 에서 ess.enabled(앞 기능) 이 맞게.
                ess.features = running.toList()
            } else {
                failed += feature.id
                rollback(feature, before)
            }
        }
        ess.failed = failed

        ess.commands.hook()
        ess.ticks.start()

        // 순위·우편함 파일은 워커가 읽고 첫 틱에 돌아온다 — 그 뒤에 기능의 ready(이미 접속한 사람 다시 읽기).
        ess.ranks.load {
            ess.mailbox.load {
                rewardsLoaded = true
                // PlaceholderAPI 는 우리보다 늦게 켜질 수 있다(load: OMIT) — 모든 플러그인이 켜진 첫 틱에 잇는다.
                hookPlaceholders()
                ess.users.loadOnline()
                for (feature in running) guard(feature, "준비") { feature.ready() }
                ess.markReady()
                logger.info("inmc-essentials 활성화 완료 - 기능 ${running.size}/${Features.IDS.size}" + if (failed.isEmpty()) "" else " (실패: ${failed.joinToString(", ")})")
            }
        }
    }

    override fun onDisable() {
        if (!::ess.isInitialized) return
        for (feature in ess.features.asReversed()) guard(feature, "끄기") { feature.disable() }
        // 서버를 끌 때 Paper 는 플러그인을 먼저 끄고 그 뒤에 접속자를 내보낸다 — 그 나감 사건은 우리에게 안 온다. 여기서 나간 시각·자리를 적는다.
        if (ess.db.isOpen) ess.users.quitAll()
        ess.ticks.stop()
        for (node in addedPermissions) server.pluginManager.removePermission(node)
        addedPermissions.clear()
        if (rewardsLoaded) ess.ranks.flushBlocking()
        ess.mailbox.flushBlocking()
        ess.db.shutdown()
        if (papi != null && Text.papiResolver === papi) Text.papiResolver = null
        ess.io.shutdown()
    }

    /**
     * `/에센셜 리로드`. 파일은 워커에서 읽고 반영은 메인에서. 저장소·기능 켜고 끄기는 재시작해야 바뀐다(안내만).
     * 열린 우리 화면은 닫는다 — 버려질 설정을 계속 고치지 않게.
     */
    fun reload(sender: CommandSender) {
        for (player in Bukkit.getOnlinePlayers()) {
            val holder = player.openInventory.topInventory.holder
            if (holder is kr.inmc.core.gui.Menu && holder.owner === ess) player.closeInventory()
        }
        val running = ess.features
        ess.io.async({
            val config = ess.io.load(ess.io.file("config.yml"))
            val messages = messageFiles(running).map { ess.io.load(it) }
            val settings = running.associate { it.id to settingsOf(it) }
            Triple(config, messages, settings)
        }) { (config, messages, settings) ->
            val before = ess.config
            val after = EssConfig.from(config)
            ess.config = after
            ess.messages = Messages.build(running, messages)
            for (feature in running) guard(feature, "다시 읽기") { feature.load(settings.getValue(feature.id)) }
            if (after.storage != before.storage || after.modules != before.modules) ess.messages.send(sender, "reload-restart-needed")
            ess.messages.send(sender, "reloaded", Ph.of().count(running.size))
        }
    }

    /** `messages/common.yml` + 기능마다 `messages/<id>.yml` — 없으면 배포본을 복사한다(덮어쓰지 않음). */
    private fun messageFiles(features: List<Feature>): List<File> =
        (listOf("common") + features.map { it.id }).map { name ->
            val path = "messages/$name.yml"
            ess.io.file("messages", "$name.yml").also { ess.io.copyDefault(path, it) }
        }

    /**
     * 기능 설정 — [Feature.hasSettings] 면 첫 부팅에 `settings/<id>.yml` 을 복사해 읽고, 그 아래에 배포본을 기본값으로 깐다
     * (관리자가 지운 키는 배포 값으로 돈다). 설정이 없는 기능은 빈 YAML. 파일을 만지므로 켤 때(메인)와 리로드의 I/O 워커에서만.
     */
    private fun settingsOf(feature: Feature): YamlConfiguration {
        if (!feature.hasSettings) return YamlConfiguration()
        val file = ess.io.file("settings", "${feature.id}.yml")
        ess.io.copyDefault("settings/${feature.id}.yml", file)
        val yaml = if (file.isFile) ess.io.load(file) else YamlConfiguration()
        ess.io.readResource("settings/${feature.id}.yml")?.let { yaml.setDefaults(it) }
        return yaml
    }

    /**
     * 켜다 던진 기능이 그때까지 건 것을 되돌린다 — 반쯤 켜진 기능의 명령·리스너·틱이 남아 lateinit 을 건드리지 않게. `disable()` 도 부른다
     * (core 창구 `Mail`·`Vanish` 공급처 떼기 같은 것은 기능의 몫 — 반쯤 켜진 상태에서도 던지지 않게 쓰는 것은 기능의 규칙).
     */
    private fun rollback(feature: Feature, before: Before) {
        guard(feature, "되돌리기(끄기)") { feature.disable() }
        val commands = ess.commands.removeOwner(feature.id)
        ess.unlisten(feature.id)
        ess.ticks.removeOwner(feature.id)
        val manager = server.pluginManager
        for (perm in feature.permissions) {
            if (addedPermissions.remove(perm.node)) manager.removePermission(perm.node)
        }
        if (ess.teleporter !== before.teleporter) ess.teleporter = before.teleporter
        if (ess.afk !== before.afk) ess.afk = before.afk
        if (ess.kits !== before.kits) ess.kits = before.kits
        if (ess.gate !== before.gate) ess.gate = before.gate
        if (ess.moderation !== before.moderation) ess.moderation = before.moderation
        if (ess.abilities !== before.abilities) ess.abilities = before.abilities
        ess.rankables.retainAll(before.rankables.toSet())
        logger.warning("기능 ${feature.id} 을(를) 뺐습니다 — 명령 ${commands}개를 거두었습니다")
    }

    private fun registerPermissions(feature: Feature) {
        val manager = server.pluginManager
        for (perm in feature.permissions) {
            manager.getPermission(perm.node)?.let { manager.removePermission(it) }
            manager.addPermission(Permission(perm.node, perm.description, perm.default))
            addedPermissions += perm.node
        }
    }

    /** 한 기능의 한 단계를 격리한다 — 던지면 로그를 남기고 false. */
    private inline fun guard(feature: Feature, what: String, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (t: Throwable) {
            logger.log(Level.SEVERE, "기능 ${feature.id} $what 실패", t)
            false
        }

    /** 메시지·화면의 PlaceholderAPI. 다른 플러그인이 이미 걸었으면 그대로 둔다. */
    private fun hookPlaceholders() {
        if (!server.pluginManager.isPluginEnabled("PlaceholderAPI") || Text.papiResolver != null) return
        val resolver: (Player?, String) -> String = { player, text -> me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text) }
        Text.papiResolver = resolver
        papi = resolver
    }
}

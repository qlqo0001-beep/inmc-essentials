package com.inmc.essentials.config

import com.inmc.essentials.feature.Features
import kr.inmc.core.reward.RewardSettings
import org.bukkit.configuration.file.YamlConfiguration

/** 저장소 — `sqlite`(이 서버의 `data/essentials.db`) 또는 `mysql`(여러 서버가 같이 쓰는 DB). 주소가 비었으면 mysql 이라도 sqlite. */
data class StorageConfig(
    val type: String = "sqlite",
    val url: String = "",
    val user: String = "",
    val password: String = "",
) {
    val mysql: Boolean get() = type == "mysql" && url.isNotBlank()
}

/**
 * `config.yml` 의 불변 스냅샷 — 리로드는 통째로 바꿔 끼운다(`ess.config`). 기능의 설정은 여기가 아니라 `settings/<id>.yml`.
 *
 * [RewardSettings] 다섯을 내놓는다 — core 보상·우편함·순위(킷·플레이 시간 보상·투표 보상이 쓴다). 사용자 결정(2026-10-10): 못 받은 보상은
 * 바닥이 아니라 우편으로 — 그래서 [dropWhenInventoryFull] 기본 false, 만료 없음.
 */
data class EssConfig(
    val storage: StorageConfig = StorageConfig(),
    /** 기능 id → 켬. 목록에 없는 기능은 켠다. 바꾸면 재시작해야 반영된다(리로드는 안내만). */
    val modules: Map<String, Boolean> = Features.IDS.associateWith { true },
    override val mailboxLimit: Int = 200,
    override val mailboxExpireSeconds: Long = 0L,
    override val dropWhenInventoryFull: Boolean = false,
    override val broadcastRewards: Boolean = true,
    override val seasonArchiveLimit: Int = 10,
) : RewardSettings {

    fun enabled(id: String): Boolean = modules[id] ?: true

    companion object {

        /** 값이 이상하면 기본값으로 — 던지지 않는다. */
        fun from(config: YamlConfiguration): EssConfig {
            val d = EssConfig()
            val modules = config.getConfigurationSection("modules")
            return EssConfig(
                storage = StorageConfig(
                    type = config.getString("storage.type", "sqlite")!!.trim().lowercase().takeIf { it == "sqlite" || it == "mysql" } ?: "sqlite",
                    url = config.getString("storage.mysql.url", "")!!.trim(),
                    user = config.getString("storage.mysql.user", "")!!,
                    password = config.getString("storage.mysql.password", "")!!,
                ),
                modules = Features.IDS.associateWith { id -> modules?.getBoolean(id, true) ?: true },
                mailboxLimit = config.getInt("rewards.mailbox-limit", d.mailboxLimit).coerceAtLeast(1),
                mailboxExpireSeconds = config.getLong("rewards.mailbox-expire-seconds", d.mailboxExpireSeconds).coerceAtLeast(0L),
                dropWhenInventoryFull = config.getBoolean("rewards.drop-when-full", d.dropWhenInventoryFull),
                broadcastRewards = config.getBoolean("rewards.broadcast", d.broadcastRewards),
                seasonArchiveLimit = config.getInt("rewards.season-archive-limit", d.seasonArchiveLimit).coerceAtLeast(0),
            )
        }
    }
}

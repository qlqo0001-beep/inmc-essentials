package com.inmc.essentials.item

import com.destroystokyo.paper.profile.PlayerProfile
import com.destroystokyo.paper.profile.ProfileProperty
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.Base64
import java.util.UUID

/** 머리 아이템 — 플레이어 프로필 또는 텍스처(해시·주소·base64 값)로. */
object Heads {

    const val TEXTURE_URL = "http://textures.minecraft.net/texture/"

    private val HEX = Regex("^[0-9a-fA-F]{40,}$")
    private val NAME = Regex("^[A-Za-z0-9_]{1,16}$")

    /**
     * 입력이 텍스처면 프로필 `textures` 값(base64), 사람 이름처럼 보이면 null — 순수.
     * 받는 것: 해시(`textures.minecraft.net/texture/<해시>` 의 해시) · 그 주소 · base64 값(`eyJ…`).
     */
    fun textureValue(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (NAME.matches(text) && !HEX.matches(text)) return null
        if (HEX.matches(text)) return encode(text)
        val at = text.indexOf("/texture/")
        if (at >= 0) return text.substring(at + "/texture/".length).takeIf { HEX.matches(it) }?.let { encode(it) }
        if (text.startsWith("eyJ") && text.length > 40) return text
        return null
    }

    fun encode(hash: String): String =
        Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"$TEXTURE_URL$hash\"}}}".toByteArray(Charsets.UTF_8))

    /** 텍스처 머리. 같은 텍스처는 같은 uuid 라 서로 겹친다. */
    fun fromTexture(value: String, name: String?): ItemStack {
        val profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)), "inmc")
        profile.setProperty(ProfileProperty("textures", value))
        return fromProfile(profile, name)
    }

    fun fromProfile(profile: PlayerProfile, name: String?): ItemStack =
        ItemStack(Material.PLAYER_HEAD).apply {
            editMeta(SkullMeta::class.java) { meta ->
                meta.playerProfile = profile
                if (name != null) meta.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            }
        }
}

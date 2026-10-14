package com.inmc.essentials.server

import kr.inmc.core.integration.PluginClasses
import java.lang.reflect.Method
import java.util.UUID

/**
 * 아직 `Player` 가 없을 때(접속 직전 · 가득 참 검사)의 권한 — LuckPerms 를 **리플렉션으로**(규칙 14, ARCHITECTURE "리플렉션 진입점").
 * LuckPerms 는 `AsyncPlayerPreLoginEvent` LOW 에서 그 사람의 자료를 읽어 두므로 그 뒤(HIGH)에는 캐시에서 답한다.
 * 없거나 그 사람이 안 읽혔거나 실패하면 null(모름) — 부르는 쪽이 op·허용 목록으로 물러난다.
 */
object OfflinePerms {

    private const val PLUGIN = "LuckPerms"

    private class Calls(
        val get: Method,
        val userManager: Method,
        val getUser: Method,
        val cachedData: Method,
        val permissionData: Method,
        val check: Method,
        val asBoolean: Method,
    )

    @Volatile
    private var calls: Calls? = null

    @Volatile
    private var failed = false

    private fun calls(): Calls? {
        calls?.let { return it }
        if (failed || !PluginClasses.isEnabled(PLUGIN)) return null
        return try {
            fun cls(name: String) = PluginClasses.require(PLUGIN, name)
            Calls(
                get = cls("net.luckperms.api.LuckPermsProvider").getMethod("get"),
                userManager = cls("net.luckperms.api.LuckPerms").getMethod("getUserManager"),
                getUser = cls("net.luckperms.api.model.user.UserManager").getMethod("getUser", UUID::class.java),
                cachedData = cls("net.luckperms.api.model.PermissionHolder").getMethod("getCachedData"),
                permissionData = cls("net.luckperms.api.cacheddata.CachedDataManager").getMethod("getPermissionData"),
                check = cls("net.luckperms.api.cacheddata.CachedPermissionData").getMethod("checkPermission", String::class.java),
                asBoolean = cls("net.luckperms.api.util.Tristate").getMethod("asBoolean"),
            ).also { calls = it }
        } catch (t: Throwable) {
            failed = true
            null
        }
    }

    /** LuckPerms 가 그 사람을 읽어 둔 상태에서의 답. 모르면 null. */
    fun has(id: UUID, node: String): Boolean? {
        val c = calls() ?: return null
        return try {
            val api = c.get.invoke(null)
            val user = c.getUser.invoke(c.userManager.invoke(api), id) ?: return null
            val data = c.permissionData.invoke(c.cachedData.invoke(user))
            c.asBoolean.invoke(c.check.invoke(data, node)) as? Boolean
        } catch (t: Throwable) {
            null
        }
    }
}

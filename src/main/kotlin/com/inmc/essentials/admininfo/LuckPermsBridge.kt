package com.inmc.essentials.admininfo

import kr.inmc.core.integration.PluginClasses
import java.lang.reflect.Method
import java.util.OptionalInt
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * LuckPerms 를 **리플렉션으로**(규칙 14 — ARCHITECTURE "리플렉션 진입점"에 적을 것). 쓰는 곳: `/목록` 의 주 그룹 묶기, `/정보` 의 그룹·접두,
 * `/권한확인` 의 접속하지 않은 사람. 없거나 실패하면 null(부르는 쪽이 그룹 없이·"모름" 으로).
 *
 * 부르는 것(LuckPerms API 5.x): `LuckPermsProvider.get()` · `LuckPerms.getUserManager()/getGroupManager()` · `UserManager.getUser(UUID)/loadUser(UUID)` ·
 * `User.getPrimaryGroup()` · `PermissionHolder.getCachedData()` · `CachedDataManager.getPermissionData()/getMetaData()` ·
 * `CachedPermissionData.checkPermission(String)` → `Tristate.name()` · `CachedMetaData.getPrefix()` · `GroupManager.getGroup(String)` ·
 * `Group.getDisplayName()/getWeight()`.
 */
internal object LuckPermsBridge {

    private const val PLUGIN = "LuckPerms"

    private class Calls(
        val get: Method,
        val userManager: Method,
        val groupManager: Method,
        val getUser: Method,
        val loadUser: Method,
        val primaryGroup: Method,
        val cachedData: Method,
        val permissionData: Method,
        val metaData: Method,
        val check: Method,
        val prefix: Method,
        val getGroup: Method,
        val groupDisplay: Method,
        val groupWeight: Method,
    )

    @Volatile
    private var calls: Calls? = null

    @Volatile
    private var failed = false

    val available: Boolean get() = calls() != null

    private fun calls(): Calls? {
        calls?.let { return it }
        if (failed || !PluginClasses.isEnabled(PLUGIN)) return null
        return try {
            fun cls(name: String) = PluginClasses.require(PLUGIN, name)
            val holder = cls("net.luckperms.api.model.PermissionHolder")
            Calls(
                get = cls("net.luckperms.api.LuckPermsProvider").getMethod("get"),
                userManager = cls("net.luckperms.api.LuckPerms").getMethod("getUserManager"),
                groupManager = cls("net.luckperms.api.LuckPerms").getMethod("getGroupManager"),
                getUser = cls("net.luckperms.api.model.user.UserManager").getMethod("getUser", UUID::class.java),
                loadUser = cls("net.luckperms.api.model.user.UserManager").getMethod("loadUser", UUID::class.java),
                primaryGroup = cls("net.luckperms.api.model.user.User").getMethod("getPrimaryGroup"),
                cachedData = holder.getMethod("getCachedData"),
                permissionData = cls("net.luckperms.api.cacheddata.CachedDataManager").getMethod("getPermissionData"),
                metaData = cls("net.luckperms.api.cacheddata.CachedDataManager").getMethod("getMetaData"),
                check = cls("net.luckperms.api.cacheddata.CachedPermissionData").getMethod("checkPermission", String::class.java),
                prefix = cls("net.luckperms.api.cacheddata.CachedMetaData").getMethod("getPrefix"),
                getGroup = cls("net.luckperms.api.model.group.GroupManager").getMethod("getGroup", String::class.java),
                groupDisplay = cls("net.luckperms.api.model.group.Group").getMethod("getDisplayName"),
                groupWeight = cls("net.luckperms.api.model.group.Group").getMethod("getWeight"),
            ).also { calls = it }
        } catch (t: Throwable) {
            failed = true
            null
        }
    }

    private fun api(c: Calls): Any = c.get.invoke(null)

    /** 읽어 둔(접속 중인) 사람의 주 그룹 이름. */
    fun primaryGroup(id: UUID): String? {
        val c = calls() ?: return null
        return runCatching {
            val user = c.getUser.invoke(c.userManager.invoke(api(c)), id) ?: return null
            c.primaryGroup.invoke(user) as? String
        }.getOrNull()
    }

    /** 접두(메타 prefix) — 읽어 둔 사람만. */
    fun prefix(id: UUID): String? {
        val c = calls() ?: return null
        return runCatching {
            val user = c.getUser.invoke(c.userManager.invoke(api(c)), id) ?: return null
            c.prefix.invoke(c.metaData.invoke(c.cachedData.invoke(user))) as? String
        }.getOrNull()
    }

    /** 그룹의 보이는 이름(없으면 이름 그대로)과 무게(없으면 0). */
    fun group(name: String): Pair<String, Int> {
        val c = calls() ?: return name to 0
        return runCatching {
            val group = c.getGroup.invoke(c.groupManager.invoke(api(c)), name) ?: return name to 0
            val display = (c.groupDisplay.invoke(group) as? String)?.takeIf { it.isNotBlank() } ?: name
            val weight = (c.groupWeight.invoke(group) as? OptionalInt)?.orElse(0) ?: 0
            display to weight
        }.getOrDefault(name to 0)
    }

    /**
     * 접속하지 않은 사람도 — LuckPerms 가 비동기로 읽는다. 결과는 `TRUE`·`FALSE`·`UNDEFINED`(LuckPerms Tristate 이름) 또는 실패면 null.
     * 완료는 LuckPerms 의 스레드에서 온다 — 부르는 쪽이 메인으로 넘길 것.
     */
    fun checkOffline(id: UUID, node: String): CompletableFuture<String?> {
        val c = calls() ?: return CompletableFuture.completedFuture(null)
        return try {
            @Suppress("UNCHECKED_CAST")
            val future = c.loadUser.invoke(c.userManager.invoke(api(c)), id) as CompletableFuture<Any?>
            future.thenApply { user ->
                if (user == null) null
                else runCatching { (c.check.invoke(c.permissionData.invoke(c.cachedData.invoke(user)), node) as? Enum<*>)?.name }.getOrNull()
            }.exceptionally { null }
        } catch (t: Throwable) {
            CompletableFuture.completedFuture(null)
        }
    }

    /** 접속하지 않은 사람의 (주 그룹, 접두) — 비동기(LuckPerms 의 스레드에서 끝난다). 실패면 null. */
    fun offlineInfo(id: UUID): CompletableFuture<Pair<String?, String?>?> {
        val c = calls() ?: return CompletableFuture.completedFuture(null)
        return try {
            @Suppress("UNCHECKED_CAST")
            val future = c.loadUser.invoke(c.userManager.invoke(api(c)), id) as CompletableFuture<Any?>
            future.thenApply<Pair<String?, String?>?> { user ->
                if (user == null) null
                else {
                    val group = runCatching { c.primaryGroup.invoke(user) as? String }.getOrNull()
                    val prefix = runCatching { c.prefix.invoke(c.metaData.invoke(c.cachedData.invoke(user))) as? String }.getOrNull()
                    group to prefix
                }
            }.exceptionally { null }
        } catch (t: Throwable) {
            CompletableFuture.completedFuture(null)
        }
    }
}

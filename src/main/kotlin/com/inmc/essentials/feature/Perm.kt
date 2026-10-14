package com.inmc.essentials.feature

import org.bukkit.permissions.PermissionDefault

/**
 * 기능 하나가 내놓는 권한 노드. 켤 때 `pluginManager.addPermission` 으로 올리고 끌 때 내린다 — `paper-plugin.yml` 에 적지 않는다
 * (여러 작업자가 한 파일을 같이 고치지 않게). **선언하지 않은 노드는 op 기본이다**(지뢰 11) — 코드가 쓰는 `"inmcessentials.…"` 는 전부
 * 여기 있어야 하고 `ResourceTest` 가 본다.
 */
data class Perm(val node: String, val default: PermissionDefault, val description: String)

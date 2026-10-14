package com.inmc.essentials.inventory

import com.inmc.essentials.Ess
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.verify.Check
import kr.inmc.core.store.SqlSource
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionDefault
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.io.File

/**
 * 인벤토리(2026-10-10) — 남의 가방·엔더 보기/고치기 · 엔더 비우기 · 저장 인벤 · 조용히 상자 열기.
 * 카탈로그 cmd3:cmd-inv · cmd2:cmd-ender · cmd1:inv-clearender · mod:sys-savedinv · cmd7:cmd-silentchest. 운영 문서 `docs/inventory.md`.
 */
class InventoryFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "inventory"

    override val hasSettings = true

    override val permissions = listOf(
        Perm(INV, PermissionDefault.OP, "/인벤 <대상> — 남의 가방 보기"),
        Perm(INV_EDIT, PermissionDefault.OP, "/인벤 으로 연 남의 가방 고치기(없으면 보기만)"),
        Perm(INV_OFFLINE, PermissionDefault.OP, "접속하지 않은 사람의 가방·엔더(나갈 때 모습) 보기 — 편집 권한이 있으면 고치기(다음 접속 때 덮어씀)·불러오기·엔더 비우기"),
        Perm(INV_OTHERS, PermissionDefault.OP, "/인벤·/엔더 <대상> <보는사람> — 다른 사람에게 열어 주기"),
        Perm(INV_LOCATION, PermissionDefault.OP, "/인벤 으로 열 때 대상의 위치 보기"),
        Perm(ENDER, PermissionDefault.OP, "/엔더 — 자기 엔더 상자 어디서나"),
        Perm(ENDER_OTHERS, PermissionDefault.OP, "/엔더 <대상> — 남의 엔더 보기"),
        Perm(ENDER_EDIT, PermissionDefault.OP, "남의 엔더 고치기(없으면 보기만)"),
        Perm(CLEAR_ENDER, PermissionDefault.OP, "/엔더비우기"),
        Perm(SAVE, PermissionDefault.OP, "/인벤저장 — 자기 것"),
        Perm(SAVE_OTHERS, PermissionDefault.OP, "/인벤저장 <대상|*|**>"),
        Perm(LOAD, PermissionDefault.OP, "/인벤불러오기"),
        Perm(LIST, PermissionDefault.OP, "/인벤목록 · 미리보기"),
        Perm(REMOVE, PermissionDefault.OP, "/인벤삭제"),
        Perm(SAVE_ON_DEATH, PermissionDefault.FALSE, "죽을 때 가방 저장(설정 saved.requires-permission 일 때만 따짐)"),
        Perm(SILENT, PermissionDefault.OP, "/몰래열기 · 투명 중 조용히 상자 열기"),
        Perm(SILENT_EDIT, PermissionDefault.OP, "조용히 연 상자 고치기(없으면 보기만)"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: InventorySettings = InventorySettings()
        private set

    internal lateinit var store: InventoryStore
    internal lateinit var inv: InvCommands
    internal lateinit var view: InvView
    internal lateinit var saved: SavedInventories
    internal lateinit var silent: SilentChest
    internal lateinit var offline: OfflineEdits

    override fun schema(sql: SqlSource) {
        InventoryStore(ess).schema(sql)
    }

    override fun load(settings: YamlConfiguration) {
        this.settings = InventorySettings.from(settings)
    }

    override fun enable() {
        store = InventoryStore(ess)
        inv = InvCommands(ess, this)
        view = InvView(ess)
        saved = SavedInventories(ess, this)
        silent = SilentChest(ess, this)
        offline = OfflineEdits(ess, this)
        offline.load()
        ess.listen(inv)
        ess.listen(view)
        ess.listen(saved)
        ess.listen(silent)
        ess.listen(offline)
        inv.register()
        saved.register()
        silent.register()
    }

    override fun disable() {
        if (::silent.isInitialized) silent.closeAll()
        if (::view.isInitialized) view.closeAll()
        if (::offline.isInitialized) offline.closeAll() // 열린 오프라인 편집 창은 저장하고 닫는다
        // 서버를 끌 때는 플러그인이 먼저 꺼져 나가기 사건을 못 받는다 — 지금 있는 사람의 모습을 남긴다(DB 워커는 이 뒤에 닫힌다).
        // 오프라인 보기·고치기의 바탕이 된다(나간 뒤 크래시 없이 꺼졌다면 다음 접속 때 지문이 맞는다).
        if (::saved.isInitialized && settings.snapshotOnQuit) for (p in Bukkit.getOnlinePlayers()) runCatching { saved.snapshot(p) }
    }

    // --- CMI 가져오기 --------------------------------------------------------------------------------

    /**
     * CMI 의 저장 인벤은 `cmi.sqlite.db` 의 `inventories` 표(longtext)에 있다 — [CmiData] 에 실리지 않고 형식도 jar 로 확인하지 못했다.
     * 조용히 버리지 않게 그 사실과 행 수를 적는다(테섭은 0행).
     */
    override fun importCmi(data: CmiData, report: ImportReport) {
        val db = File(data.folder, "cmi.sqlite.db")
        report.note("저장 인벤: CMI 의 inventories 표는 형식을 확인하지 못해 가져오지 않습니다" + if (db.isFile) " — 필요하면 CMI 를 빼기 전에 /cmi invload 로 되돌린 뒤 /인벤저장 하세요" else "")
        report.skipped(KIND, "CMI 저장 인벤(inventories 표)은 가져오기 대상이 아닙니다")
    }

    // --- 검증기 --------------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("가방 바이트 — 저장·되읽기가 같다(빈 칸 포함 41칸)") { p ->
            val data = InvData.capture(p)
            val again = InvData.decode(InvData.encode(data.items), InvData.INVENTORY_SIZE)
            val diff = (0 until InvData.INVENTORY_SIZE).count { i -> !SilentChest.same(data.items[i], again[i]) }
            if (diff != 0) "되읽은 가방이 ${diff}칸 다릅니다" else null
        },
        Check("효과 글 — 저장·되읽기") { _ ->
            val effects = listOf(PotionEffect(PotionEffectType.SPEED, 200, 1), PotionEffect(PotionEffectType.REGENERATION, 40, 0, true, false, false))
            val again = InvData.decodeEffects(InvData.encodeEffects(effects))
            if (again != effects) "되읽은 효과가 다릅니다: $again" else null
        },
        Check("남의 엔더 — 편집 권한이 없으면 클릭을 막는다(진짜 클릭 사건)") { p -> inv.probeReadOnly(p) },
        Check("/인벤 창 — 54칸 사본에 갑옷·왼손(36~40)이 보이고 칸막이 클릭은 막는다") { p ->
            if (!p.inventory.itemInOffHand.type.isAir) return@Check null // 왼손을 쓰고 있으면 건드리지 않는다
            if (p.openInventory.topInventory.type != org.bukkit.event.inventory.InventoryType.CRAFTING) return@Check null
            try {
                p.inventory.setItemInOffHand(ItemStack(Material.DIRT, 3))
                val session = view.open(p, p, editable = true) ?: return@Check "창이 열리지 않았습니다(다른 플러그인이 막음?)"
                val top = p.openInventory.topInventory
                val armor = (36..39).all { t -> SilentChest.same(top.getItem(InvView.toView(t)), p.inventory.getItem(t)) }
                val filler = InventoryClickEvent(p.openInventory, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, 44,
                    org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL)
                Bukkit.getPluginManager().callEvent(filler)
                when {
                    top.holder !== session || top.size != InvView.SIZE -> "54칸 사본 창이 아닙니다"
                    top.getItem(InvView.toView(40))?.type != Material.DIRT -> "왼손(40)이 창의 ${InvView.toView(40)}칸에 없습니다"
                    !armor -> "갑옷 칸(36~39)이 창과 다릅니다"
                    !filler.isCancelled -> "칸막이 클릭이 막히지 않았습니다"
                    else -> null
                }
            } finally {
                p.closeInventory()
                p.inventory.setItemInOffHand(null)
            }
        },
        Check("/인벤 창 — 바뀐 칸을 쓰고, 한 칸이라도 어긋나면 하나도 쓰지 않으며, 사본 안에서 옮긴 것은 맞비긴다") { p ->
            if (p.openInventory.topInventory.type != org.bukkit.event.inventory.InventoryType.CRAFTING) return@Check null
            val slot = (9..35).firstOrNull { p.inventory.getItem(it).let { i -> i == null || i.type.isAir } } ?: return@Check null
            val saved = p.inventory.contents.map { it?.clone() }.toTypedArray()
            try {
                val session = view.open(p, p, editable = true) ?: return@Check "창이 열리지 않았습니다(다른 플러그인이 막음?)"
                session.copy.setItem(InvView.toView(slot), ItemStack(Material.DIRT, 5))
                view.flush(p, session) // 다음 틱을 기다리지 않고 쓰기
                val wrote = p.inventory.getItem(slot)?.let { it.type == Material.DIRT && it.amount == 5 } == true
                // 전부 아니면 무: 두 칸을 바꿨는데 한 칸은 대상 쪽도 바뀌었다.
                val base = arrayOfNulls<ItemStack>(3)
                val copy = arrayOf<ItemStack?>(ItemStack(Material.STONE, 2), ItemStack(Material.STONE, 3), null)
                val live = arrayOf<ItemStack?>(null, ItemStack(Material.GRAVEL), null)
                val partial = InvView.writable(listOf(0, 1), live, base, copy)
                val clean = InvView.writable(listOf(0, 1), arrayOfNulls(3), base, copy)
                // 사본 안에서 X → Y 로 옮긴 것: 되돌릴 때 보는 사람에게서 아무것도 빼거나 주지 않는다.
                val before = p.inventory.contents.map { it?.clone() }
                val missing = Reclaim.undoAll(p, listOf(ItemStack(Material.COBWEB, 4) to null, null to ItemStack(Material.COBWEB, 4)))
                val after = p.inventory.contents.toList()
                when {
                    !wrote -> "사본 칸의 흙 5개가 대상에 써지지 않았습니다"
                    partial -> "대상 쪽도 바뀐 칸이 있는데 쓸 수 있다고 봅니다(칸마다 쓰면 복제)"
                    !clean -> "어긋남이 없는데 쓰지 않는다고 봅니다"
                    missing != 0 || before.indices.any { !SilentChest.same(before[it], after[it]) } -> "사본 안에서 옮긴 것을 맞비기지 않았습니다"
                    else -> null
                }
            } finally {
                p.closeInventory()
                p.inventory.contents = saved
            }
        },
        Check("오프라인 편집 — 바뀌지 않은 가방은 고친 내용으로 통째로 덮는다(지문 같음)") { p ->
            offlineCheck(p) { slot, base ->
                val edited = base.copyOf().also { it[slot] = ItemStack(Material.DIRT, 7) }
                offline.putMemory(memoryEdit(p, base, edited, group = null))
                val out = offline.applyNow(p, PendingKind.INVENTORY, null, persist = false)
                when {
                    out?.mode != OfflineEdits.Mode.OVERWRITE -> "방식이 ${out?.mode} — 덮어써야 합니다"
                    p.inventory.getItem(slot)?.amount != 7 -> "고친 내용(흙 7개)이 적용되지 않았습니다"
                    else -> null
                }
            }
        },
        Check("오프라인 편집 — 그새 바뀐 가방은 칸별로 얹고 못 뺀 것은 충돌 줄로") { p ->
            offlineCheck(p) { slot, current ->
                val other = (9..35).firstOrNull { it != slot && current[it] == null } ?: return@offlineCheck null
                // 바탕: 지금 가방 + (실제로는 없는) 표시한 돌 5개. 관리자는 그 돌을 빼고 흙 3개를 넣었다.
                val marker = ItemStack(Material.STONE, 5).apply { editMeta { it.displayName(net.kyori.adventure.text.Component.text("ess-verify-offline")) } }
                val base = current.copyOf().also { it[other] = marker }
                val edited = current.copyOf().also { it[slot] = ItemStack(Material.DIRT, 3) }
                offline.putMemory(memoryEdit(p, base, edited, group = null))
                val out = offline.applyNow(p, PendingKind.INVENTORY, null, persist = false)
                when {
                    out?.mode != OfflineEdits.Mode.DELTA -> "방식이 ${out?.mode} — 칸별이어야 합니다"
                    out.lines.none { it.startsWith("빼지 못함") } -> "없는 돌을 빼지 못한 것이 충돌 줄에 없습니다: ${out.lines}"
                    p.inventory.getItem(slot)?.let { it.type == Material.DIRT && it.amount == 3 } != true -> "넣은 흙 3개가 그 칸에 없습니다"
                    else -> null
                }
            }
        },
        Check("오프라인 편집 — 다른 MV 그룹이면 미루고 기록을 남긴다") { p ->
            offlineCheck(p) { slot, base ->
                val edited = base.copyOf().also { it[slot] = ItemStack(Material.DIRT, 2) }
                offline.putMemory(memoryEdit(p, base, edited, group = "ess-verify-a"))
                val out = offline.applyNow(p, PendingKind.INVENTORY, "ess-verify-b", persist = false)
                when {
                    out?.mode != OfflineEdits.Mode.DEFERRED -> "방식이 ${out?.mode} — 미뤄야 합니다"
                    offline.pendingFor(p.uniqueId, PendingKind.INVENTORY) == null -> "미뤘는데 기록이 사라졌습니다"
                    p.inventory.getItem(slot) != null -> "다른 그룹인데 가방을 고쳤습니다"
                    else -> null
                }
            }
        },
        Check("오프라인 편집 — 두 번 적용하지 않는다(적용하면 지우고, 같은 기록이 다시 와도 이미 적용됨)") { p ->
            offlineCheck(p) { slot, base ->
                val edited = base.copyOf().also { it[slot] = ItemStack(Material.DIRT, 4) }
                val rec = memoryEdit(p, base, edited, group = null)
                offline.putMemory(rec)
                val first = offline.applyNow(p, PendingKind.INVENTORY, null, persist = false)
                val second = offline.applyNow(p, PendingKind.INVENTORY, null, persist = false)
                offline.putMemory(rec) // 크래시로 DB 에 남았던 것처럼 같은 기록이 다시 온다
                val third = offline.applyNow(p, PendingKind.INVENTORY, null, persist = false)
                when {
                    first?.mode != OfflineEdits.Mode.OVERWRITE -> "처음 적용이 ${first?.mode}"
                    second != null -> "적용한 뒤에도 기록이 남아 다시 적용했습니다(${second.mode})"
                    third?.mode != OfflineEdits.Mode.ALREADY -> "같은 기록이 다시 왔는데 ${third?.mode} — 이미 적용으로 봐야 합니다"
                    p.inventory.getItem(slot)?.amount != 4 -> "흙이 ${p.inventory.getItem(slot)?.amount}개 — 4개여야 합니다(두 번 들어감?)"
                    else -> null
                }
            }
        },
        Check("가방 칸 열쇠 — 모드가 있으면 core Realms 의 worlds.inventory scope") { p ->
            val key = InvGroups.of(p)
            when {
                !kr.inmc.core.integration.Realms.available -> if (key != null) "모드가 없는데 열쇠가 $key" else null
                key != kr.inmc.core.integration.Realms.scope(InvGroups.INVENTORY_KEY, p) -> "열쇠 $key 가 Realms 와 다릅니다"
                else -> null
            }
        },
        Check("조용히 열기 — 복사본 대조(바뀐 칸만 · 같은 것은 같다)") { _ ->
            val a = arrayOf<ItemStack?>(ItemStack(Material.DIRT, 3), null, ItemStack(Material.STONE))
            val b = arrayOf<ItemStack?>(ItemStack(Material.DIRT, 3), ItemStack(Material.AIR), ItemStack(Material.STONE))
            val c = arrayOf<ItemStack?>(ItemStack(Material.DIRT, 2), null, ItemStack(Material.STONE))
            when {
                !SilentChest.same(a, b) -> "공기와 빈 칸을 다르게 봅니다"
                SilentChest.same(a, c) -> "개수가 다른데 같다고 봅니다"
                else -> null
            }
        },
        Check("조용히 열기 — 토글이 켜고 끈다(메모리)") { p ->
            if (!p.hasPermission(SILENT)) return@Check null
            val before = silent.isToggled(p.uniqueId)
            try {
                silent.toggle(p, true)
                if (!silent.active(p)) "켰는데 조용히 열기가 아닙니다" else null
            } finally {
                silent.toggle(p, before)
            }
        },
        Check("CoreProtect 연동(있을 때만)") { _ -> CoreProtectLog.probe() },
    )

    /**
     * 오프라인 편집 검사의 틀 — 진짜 대기 편집이 있으면 건드리지 않고 건너뛴다. 빈 칸 하나와 지금 가방(41칸)을 주고, 끝나면 가방과
     * 메모리 기록을 되돌린다(DB·파일에 쓰지 않는다 — persist = false).
     */
    private fun offlineCheck(p: org.bukkit.entity.Player, body: (Int, Array<ItemStack?>) -> String?): String? {
        if (PendingKind.entries.any { offline.pendingFor(p.uniqueId, it) != null }) return null
        val slot = (9..35).firstOrNull { p.inventory.getItem(it).let { i -> i == null || i.type.isAir } } ?: return null
        val saved = p.inventory.contents.map { it?.clone() }.toTypedArray()
        return try {
            body(slot, InvData.capture(p).items)
        } finally {
            offline.removeMemory(p.uniqueId, PendingKind.INVENTORY)
            p.inventory.contents = saved
        }
    }

    private fun memoryEdit(p: org.bukkit.entity.Player, base: Array<ItemStack?>, edited: Array<ItemStack?>, group: String?): PendingEdit =
        PendingEdit(
            p.uniqueId, p.name, PendingKind.INVENTORY, InvData.encode(base), InvHash.of(base), InvData.encode(edited),
            p.world.name, group, p.uniqueId, "검증기", System.currentTimeMillis(), claimed = false,
        )

    companion object {
        const val INV = "inmcessentials.inventory.inv"
        const val INV_EDIT = "inmcessentials.inventory.inv.edit"
        const val INV_OFFLINE = "inmcessentials.inventory.inv.offline"
        const val INV_OTHERS = "inmcessentials.inventory.inv.others"
        const val INV_LOCATION = "inmcessentials.inventory.inv.location"
        const val ENDER = "inmcessentials.inventory.ender"
        const val ENDER_OTHERS = "inmcessentials.inventory.ender.others"
        const val ENDER_EDIT = "inmcessentials.inventory.ender.edit"
        const val CLEAR_ENDER = "inmcessentials.inventory.clearender"
        const val SAVE = "inmcessentials.inventory.save"
        const val SAVE_OTHERS = "inmcessentials.inventory.save.others"
        const val LOAD = "inmcessentials.inventory.load"
        const val LIST = "inmcessentials.inventory.list"
        const val REMOVE = "inmcessentials.inventory.remove"
        const val SAVE_ON_DEATH = "inmcessentials.inventory.saveondeath"
        const val SILENT = "inmcessentials.inventory.silentchest"
        const val SILENT_EDIT = "inmcessentials.inventory.silentchest.edit"

        private const val KIND = "저장 인벤"

        val MESSAGES: Map<String, String> = linkedMapOf(
            // 인벤 · 엔더
            "inventory-inv-usage" to "<yellow>/인벤 [대상] [보는사람]</yellow> <gray>— 접속자는 지금 가방 · 갑옷 · 왼손, 오프라인은 나갈 때 모습(고치기 권한이 있으면 고칠 수 있음 — 오프라인은 다음 접속 때 덮어씀)</gray>",
            "inventory-inv-resync" to "<yellow>가방이 그새 바뀌어 화면을 다시 맞췄습니다 — 다시 해 주세요.</yellow>",
            "inventory-inv-undone" to "<yellow>{개수}칸은 쓰지 못해 되돌렸습니다</yellow> <gray>(그새 바뀐 칸 · 맞지 않는 갑옷 칸 · 열린 셜커 배낭)</gray>",
            "inventory-inv-target-died" to "<yellow>{플레이어}님이 죽어서 창을 닫았습니다.</yellow>",
            "inventory-inv-self" to "<gray>자기 가방은 E 키로 여세요.</gray>",
            "inventory-ender-self" to "<gray>자기 엔더는 /엔더 로 여세요.</gray>",
            "inventory-blacklisted" to "<red>{플레이어}님의 가방은 열 수 없습니다.</red>",
            "inventory-mode-edit" to "고치기",
            "inventory-mode-view" to "보기만",
            "inventory-inv-opened" to "<green>{플레이어}님의 가방을 열었습니다.</green> <gray>({편집})</gray>",
            "inventory-ender-opened" to "<green>{플레이어}님의 엔더 상자를 열었습니다.</green> <gray>({편집})</gray>",
            "inventory-location" to "<gray>{플레이어}님 위치: {월드} {값}</gray> <click:run_command:'/이동 {플레이어}'><hover:show_text:'<gray>눌러서 가기</gray>'><aqua>[가기]</aqua></hover></click>",
            "inventory-no-snapshot" to "<red>{플레이어}님의 나갈 때 모습이 없습니다(이 플러그인을 넣은 뒤 나간 적이 없음).</red>",
            "inventory-offline-opened" to "<gray>{플레이어}님은 접속 중이 아니라 {시간} 에 나갈 때의 모습(기다리는 편집이 있으면 그것)을 엽니다(보기만).</gray>",
            "inventory-target-left" to "<yellow>{플레이어}님이 나가서 창을 닫았습니다.</yellow>",
            "inventory-clearender-usage" to "<yellow>/엔더비우기 [대상] [-s]</yellow>",
            "inventory-clearender-offline" to "<red>'{값}' 은(는) 접속 중이 아닙니다 — 접속하지 않은 사람의 엔더 비우기는 inmcessentials.inventory.inv.offline 권한이 있어야 합니다.</red>",
            // 오프라인 편집
            "inventory-kind-inv" to "가방",
            "inventory-kind-ender" to "엔더 상자",
            "inventory-offline-editing" to "<green>{플레이어}님의 {종류}를 고칩니다(접속 중이 아님)</green> <gray>— 고친 것은 바로 저장되고 다음 접속 때 덮어씁니다. 기준: {시간} 나갈 때 모습</gray>",
            "inventory-offline-locked" to "<yellow>{값}님이 {플레이어}님의 {종류}를 고치는 중이라 보기만 엽니다.</yellow>",
            "inventory-offline-busy" to "<red>{값}님이 {플레이어}님의 {종류}를 고치는 중입니다 — 그 창이 닫힌 뒤 다시 하세요.</red>",
            "inventory-offline-now-online" to "<yellow>{플레이어}님이 그새 접속했습니다 — 다시 해 주세요(접속 중이면 바로 고칩니다).</yellow>",
            "inventory-offline-target-joined" to "<yellow>{플레이어}님이 접속해 {종류} 편집 창을 저장하고 닫았습니다 — 곧 적용됩니다.</yellow>",
            "inventory-offline-staged" to "<green>{플레이어}님은 접속 중이 아니라 다음 접속 때 {종류}에 적용합니다.</green> <gray>(아이템만 — 체력·경험치·효과는 접속해 있을 때)</gray>",
            "inventory-offline-applied" to "<green>{플레이어}님이 접속해 오프라인 편집({종류})을 적용했습니다.</green> <gray>({값})</gray>",
            "inventory-offline-applied-target" to "<yellow>접속하지 않은 동안 관리자가 {종류}를 고쳤습니다.</yellow>",
            "inventory-offline-conflict" to "<red>{플레이어}님의 오프라인 편집({종류}) 중 일부를 적용하지 못했습니다:</red> <white>plugins/inmc-essentials/inventory/{값}</white>",
            "inventory-clearender-done" to "<green>{플레이어}님의 엔더 상자를 비웠습니다.</green> <gray>({개수}칸)</gray>",
            "inventory-clearender-target" to "<yellow>{플레이어}님이 엔더 상자를 비웠습니다.</yellow>",
            // 저장 인벤
            "inventory-saved" to "<green>{플레이어}님의 가방을 저장했습니다.</green>",
            "inventory-save-failed" to "<red>{플레이어}님의 가방을 저장하지 못했습니다 — 서버 로그를 보세요.</red>",
            "inventory-saved-many" to "<green>가방 저장 — 접속자 {개수}명 · 오프라인(나갈 때 모습) {금액}명</green>",
            "inventory-save-offline" to "<red>'{값}' 은(는) 접속 중이 아닙니다 — 오프라인은 /인벤저장 ** 로(나갈 때 모습을 저장).</red>",
            "inventory-list-usage" to "<yellow>/인벤목록 [주인] [번호]</yellow>",
            "inventory-list-empty" to "<gray>{플레이어}님의 저장 인벤이 없습니다.</gray>",
            "inventory-list-header" to "<gold>{플레이어}님의 저장 인벤 {개수}개</gold> <gray>(새것부터)</gray>",
            "inventory-list-entry" to "<gray>{순위}.</gray> <white>{시간}</white> <gray>{사유} · {값} · {개수}칸 · #{아이디}</gray> <click:run_command:'/인벤목록 {플레이어} #{아이디}'><aqua>[보기]</aqua></click> <click:run_command:'/인벤불러오기 {플레이어} #{아이디}'><green>[불러오기]</green></click> <click:suggest_command:'/인벤삭제 {플레이어} #{아이디}'><red>[지우기]</red></click>",
            "inventory-bad-number" to "<red>번호가 이상합니다: {값}</red> <gray>(1 = 가장 새것 · last · #id · all)</gray>",
            "inventory-not-found" to "<red>{플레이어}님의 저장 인벤 '{값}' 이(가) 없습니다.</red>",
            "inventory-load-usage" to "<yellow>/인벤불러오기 [주인] [번호|last] [받을사람]</yellow> <gray>— 받는 사람의 지금 가방은 먼저 저장됩니다</gray>",
            "inventory-load-offline" to "<red>{플레이어}님이 접속 중이 아닙니다 — 접속하지 않은 사람에게 불러오려면 inmcessentials.inventory.inv.offline 권한이 있어야 하고 아이템만 되돌립니다(settings saved.restore.items).</red>",
            "inventory-loaded" to "<green>{플레이어}님에게 {값}님의 저장 인벤({시간})을 되돌렸습니다.</green>",
            "inventory-loaded-target" to "<yellow>{플레이어}님이 저장된 가방을 되돌렸습니다.</yellow>",
            "inventory-backup-failed" to "<red>{플레이어}님의 이전 가방을 DB 에 백업하지 못해 파일로 남겼습니다:</red> <white>inventory-backups/{값}</white>",
            "inventory-remove-usage" to "<yellow>/인벤삭제 [주인] [번호|last|all]</yellow>",
            "inventory-removed" to "<green>{플레이어}님의 저장 인벤 {개수}개를 지웠습니다.</green>",
            // 조용히 열기
            "inventory-silent-on" to "<green>조용히 상자 열기를 켰습니다.</green> <gray>(소리·뚜껑 없이 복사본 창)</gray>",
            "inventory-silent-off" to "<yellow>조용히 상자 열기를 껐습니다.</yellow>",
            "inventory-silent-still-vanished" to "<gray>투명 중에는 여전히 조용히 열립니다(settings/inventory.yml silent-chest.auto-when-vanished).</gray>",
            "inventory-silent-loot" to "<red>전리품이 아직 생기지 않은 상자라 조용히 열지 않습니다.</red>",
            "inventory-silent-readonly" to "<gray>보기만 할 수 있습니다(고칠 권한이 없음).</gray>",
            "inventory-silent-resync" to "<yellow>상자가 그새 바뀌어 화면을 다시 맞췄습니다 — 다시 해 주세요.</yellow>",
            "inventory-silent-gone" to "<yellow>상자가 사라져 창을 닫았습니다.</yellow>",
        )
    }
}

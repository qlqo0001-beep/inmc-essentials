package com.inmc.essentials.commands

import com.inmc.essentials.Ess
import com.inmc.essentials.gui.Menu
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * `/별칭` 편집기 — 별칭 목록(쪽) · 누르면 입력창으로 고치기 · 우클릭 켜고 끄기 · Shift+우클릭 지우기(확인창) · 아래 "새 별칭".
 * 저장은 바로 반영되고 파일은 원자 쓰기(AliasService.save).
 */
class AliasMenu(ess: Ess, viewer: Player, private val service: AliasService, private val page: Int = 0) :
    Menu(ess, viewer, 54, "<dark_aqua>별칭 편집</dark_aqua>") {

    override fun draw() {
        clear()
        val list = service.defs.sortedBy { it.name.lowercase() }
        val p = Paging.clamp(page, list.size)
        for ((i, def) in Paging.slice(list, p).withIndex()) {
            val lore = buildList {
                add("<gray>명령:</gray>")
                def.commands.take(6).forEach { add("<white> /$it</white>") }
                if (def.commands.size > 6) add("<dark_gray> …${def.commands.size - 6}줄 더</dark_gray>")
                add("<gray>권한:</gray> <white>${def.node() ?: "누구나"}</white>")
                if (def.exact) add("<yellow>이름만 쳤을 때만(exact)</yellow>")
                if (def.description.isNotBlank()) add("<gray>${def.description}</gray>")
                add("")
                add("<yellow>클릭</yellow> <gray>고치기 ·</gray> <yellow>우클릭</yellow> <gray>켜기/끄기 ·</gray> <red>Shift+우클릭</red> <gray>지우기</gray>")
            }
            val icon = Icon.of(if (def.enabled) Material.NAME_TAG else Material.PAPER, (if (def.enabled) "<green>" else "<dark_gray>") + "/" + def.name, lore)
            set(i, icon) { event ->
                when {
                    event.isShiftClick && event.isRightClick -> confirmDelete(def)
                    event.isRightClick -> {
                        service.save(service.defs.map { if (it === def) it.copy(enabled = !it.enabled) else it })
                        refresh()
                    }
                    else -> edit(def)
                }
            }
        }
        pager(p, list.size) { AliasMenu(ess, viewer, service, it).show() }
        set(SLOT_NEW, Icon.of(Material.EMERALD, "<green>+ 새 별칭</green>", "<gray>이름과 명령을 입력창에 적습니다</gray>")) { edit(null) }
        set(SLOT_HELP, Icon.of(Material.BOOK, "<yellow>쓰는 법</yellow>", AliasService.HEADER.drop(1).map { "<gray>$it</gray>" }))
        navigation()
    }

    private fun confirmDelete(def: AliasDef) {
        ConfirmMenu(ess, "<red>/${def.name} 지우기</red>", def.commands.map { "<gray>/$it</gray>" }, onConfirm = {
            service.save(service.defs.filter { it !== def })
            AliasMenu(ess, viewer, service, page).show()
        }, onCancel = { show() }).open(viewer)
    }

    private fun edit(def: AliasDef?) {
        val form = DialogForm(if (def == null) "새 별칭" else "/${def.name} 고치기")
            .line("명령은 한 줄에 하나(앞 / 없이). \$1 · \$1- · delay! 3 · asConsole! … — 책 아이콘을 보세요")
            .text("name", "이름(빈칸 = 하위 명령, ? = 아무 낱말)", def?.name.orEmpty(), 48)
            .text("commands", "명령", def?.commands?.joinToString("\n").orEmpty(), 4000, multiline = true)
            .text("permission", "권한('' 누구나 · true · 노드)", def?.permission.orEmpty(), 128)
            .text("description", "설명", def?.description.orEmpty(), 128)
            .text("usage", "인자가 모자랄 때 보일 글", def?.usage.orEmpty(), 256)
            .text("tabs", "탭 완성(인자 자리마다 한 줄)", def?.tabs?.joinToString("\n").orEmpty(), 1000, multiline = true)
            .toggle("tab", "탭 완성 켜기", def?.tab ?: true)
            .toggle("exact", "이름만 쳤을 때만(exact)", def?.exact ?: false)
            .toggle("enabled", "켜기", def?.enabled ?: true)
        ask(form) { v ->
            val name = v.text("name").trim().replace(Regex("\\s+"), " ")
            val problem = AliasDef.nameProblem(name)
            val commands = v.text("commands").lines().map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
            when {
                problem != null -> ess.messages.send(viewer, "commands-alias-bad-name", com.inmc.essentials.util.Ph.of().value(name).reason(problem))
                commands.isEmpty() -> ess.messages.send(viewer, "commands-alias-no-commands")
                service.defs.any { it !== def && it.name.equals(name, true) } -> ess.messages.send(viewer, "commands-alias-exists", com.inmc.essentials.util.Ph.of().value(name))
                else -> {
                    val next = AliasDef(
                        name = name, commands = commands, permission = v.text("permission").trim(), description = v.text("description").trim(),
                        tabs = v.text("tabs").lines().map { it.trim() }.filter { it.isNotEmpty() }, tab = v.bool("tab"), exact = v.bool("exact"),
                        enabled = v.bool("enabled"), usage = v.text("usage").trim(),
                    )
                    if (next.selfCalls()) {
                        ess.messages.send(viewer, "commands-alias-self-call", com.inmc.essentials.util.Ph.of().value("/$name"))
                        return@ask
                    }
                    service.save(if (def == null) service.defs + next else service.defs.map { if (it === def) next else it })
                    ess.messages.send(viewer, "commands-alias-saved", com.inmc.essentials.util.Ph.of().value("/$name"))
                }
            }
        }
    }

    companion object {
        const val SLOT_NEW = 49
        const val SLOT_HELP = 51
    }
}

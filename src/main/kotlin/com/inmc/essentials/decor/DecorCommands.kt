package com.inmc.essentials.decor

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.util.Ph
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.block.Sign
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.GlowItemFrame
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import java.util.UUID

private typealias DCtx = CommandContext<CommandSourceStack>

/**
 * decor 기능의 명령 — 계획 4절 표 그대로: `/갑옷거치대`(armorstand) · `/표지판편집`(se) · `/표지판복사`(signcopy — sc 는 staffmsg 와 겹친다) ·
 * `/동적표지판`(dsign) · `/액자`(itemframe). 그림 편집은 명령 없이 웅크려 우클릭.
 */
internal class DecorCommands(private val ess: Ess, private val feature: DecorFeature) {

    fun register() {
        ess.commands.add(root("갑옷거치대", DecorFeature.ARMORSTAND, ::armorStand, ::suggestArmorStand), "갑옷 거치대 편집기(바라보는 것 · 가까이 · 마지막 · 템플릿)", listOf("armorstand"))
        ess.commands.add(root("표지판편집", DecorFeature.SIGN_COMMAND, ::signEdit, ::suggestSignEdit), "바라보는 표지판의 줄 바꾸기(\\n 으로 다음 줄)", listOf("se"))
        ess.commands.add(root("표지판복사", DecorFeature.SIGN_COPY, ::signCopy, ::suggestSignCopy), "바라보는 표지판 글을 다른 표지판에 붙이기(치면 붙음)", listOf("signcopy"))
        ess.commands.add(root("동적표지판", DecorFeature.DSIGN, ::dynamicSign, ::suggestDynamic), "플레이스홀더가 갱신되는 표지판", listOf("dsign"))
        ess.commands.add(root("액자", DecorFeature.ITEMFRAME, ::itemFrame, ::suggestItemFrame), "아이템 액자 투명·고정·무적·발광", listOf("itemframe"))
    }

    private fun root(name: String, perm: String, run: (DCtx, String) -> Unit, suggest: (DCtx, List<String>) -> List<String>) = Commands.literal(name)
        .requires { it.sender.hasPermission(perm) }
        .executes { ctx -> run(ctx, ""); 1 }
        .then(Commands.argument("내용", StringArgumentType.greedyString()).suggests(tail(suggest))
            .executes { ctx -> run(ctx, StringArgumentType.getString(ctx, "내용")); 1 })
        .build()

    private fun tail(suggest: (DCtx, List<String>) -> List<String>): SuggestionProvider<CommandSourceStack> = SuggestionProvider { ctx, builder ->
        val text = builder.remaining
        val start = text.lastIndexOf(' ') + 1
        val typed = text.substring(start).lowercase()
        val before = text.substring(0, start).split(' ').filter { it.isNotEmpty() }
        val b = builder.createOffset(builder.start + start)
        runCatching { suggest(ctx, before) }.getOrDefault(emptyList()).distinct().filter { it.lowercase().startsWith(typed) }.take(100).forEach { b.suggest(it) }
        b.buildFuture()
    }

    private fun player(ctx: DCtx): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun lookedAt(player: Player, filter: (Entity) -> Boolean): Entity? =
        player.world.rayTraceEntities(player.eyeLocation, player.eyeLocation.direction, REACH, 0.3) { it != player && filter(it) }?.hitEntity

    // --- /갑옷거치대 -------------------------------------------------------------------------------------

    private fun armorStand(ctx: DCtx, raw: String) {
        val p = player(ctx) ?: return
        val words = Args.split(raw)
        val stands = feature.stands
        when (words.firstOrNull()?.lowercase()) {
            null -> {
                val stand = lookedAt(p) { it is ArmorStand } as? ArmorStand ?: return ess.messages.send(p, "decor-as-look")
                tryOpen(p, stand)
            }
            "가까이", "near" -> {
                val r = feature.settings.armorStand.nearRadius.toDouble()
                // 마커·다른 플러그인의 거치대는 목록에서도 뺀다(.any 제외) — 열 수 없는 것의 id 를 보이지 않게.
                val near = p.getNearbyEntities(r, r, r).filterIsInstance<ArmorStand>().filter { !stands.offLimits(p, it) }.sortedBy { it.location.distanceSquared(p.location) }.take(NEAR_MAX)
                if (near.isEmpty()) return ess.messages.send(p, "decor-as-none-near", Ph.of().value(r.toInt().toString()))
                near.forEachIndexed { i, stand ->
                    val items = ArmorStands.SLOTS.map { stand.getItem(it) }.filter { !it.type.isAir }.joinToString(", ") { it.type.key.key }
                    ess.messages.send(p, "decor-as-list", Ph.of().count(i + 1).value("%.1f".format(stand.location.distance(p.location)))
                        .raw("{장비}", items.ifEmpty { "-" }).raw("{id}", stand.uniqueId.toString()))
                }
            }
            "마지막", "last" -> {
                val stand = stands.lastOf(p) ?: return ess.messages.send(p, "decor-as-no-last")
                tryOpen(p, stand)
            }
            "편집", "edit" -> {
                val id = words.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return ess.messages.send(p, "decor-as-look")
                val stand = Bukkit.getEntity(id) as? ArmorStand ?: return ess.messages.send(p, "decor-as-look")
                if (stand.world != p.world || stand.location.distanceSquared(p.location) > 64.0 * 64.0) return ess.messages.send(p, "decor-as-too-far-away")
                tryOpen(p, stand)
            }
            "템플릿", "template" -> {
                val name = words.getOrNull(1)?.lowercase() ?: return listTemplates(p)
                if (!p.hasPermission(DecorFeature.ARMORSTAND_TEMPLATE_GIVE)) return ess.messages.send(p, "no-permission")
                if (name !in stands.templates) return ess.messages.send(p, "decor-as-template-unknown", Ph.of().raw("{이름}", name))
                if (!stands.mayUseTemplate(p, name)) return ess.messages.send(p, "no-permission")
                if (stands.giveTemplate(p, name)) ess.messages.send(p, "decor-as-template-given", Ph.of().raw("{이름}", name))
            }
            "템플릿삭제", "templateremove" -> {
                if (!p.hasPermission(DecorFeature.ARMORSTAND_TEMPLATE_SAVE)) return ess.messages.send(p, "no-permission")
                val name = words.getOrNull(1)?.lowercase() ?: return listTemplates(p)
                ess.messages.send(p, if (stands.removeTemplate(name)) "decor-as-template-removed" else "decor-as-template-unknown", Ph.of().raw("{이름}", name))
            }
            "템플릿목록", "templates" -> listTemplates(p)
            else -> ess.messages.send(p, "decor-as-usage")
        }
    }

    private fun listTemplates(p: Player) {
        val names = feature.stands.templates.keys.sorted()
        if (names.isEmpty()) return ess.messages.send(p, "decor-as-template-none")
        ess.messages.send(p, "decor-as-template-list", Ph.of().count(names.size).value(names.joinToString(", ")))
    }

    private fun tryOpen(p: Player, stand: ArmorStand) {
        if (feature.stands.offLimits(p, stand)) return ess.messages.send(p, "decor-as-foreign")
        if (!feature.stands.mayTouch(p, stand)) return ess.messages.send(p, "decor-as-protected")
        feature.stands.open(p, stand)
    }

    private fun suggestArmorStand(ctx: DCtx, before: List<String>): List<String> = when (before.size) {
        0 -> listOf("가까이", "마지막", "템플릿", "템플릿목록", "템플릿삭제")
        1 -> if (before[0].lowercase() in setOf("템플릿", "template", "템플릿삭제", "templateremove")) feature.stands.templates.keys.sorted() else emptyList()
        else -> emptyList()
    }

    // --- /표지판편집 ·/표지판복사 ------------------------------------------------------------------------

    private fun signEdit(ctx: DCtx, raw: String) {
        val p = player(ctx) ?: return
        val line = raw.trim().substringBefore(' ').toIntOrNull()
        if (line == null || line !in 1..4) return ess.messages.send(p, "decor-sign-usage")
        val text = raw.trim().substringAfter(' ', "")
        feature.signs.editLine(p, line, text)
    }

    private fun suggestSignEdit(ctx: DCtx, before: List<String>): List<String> {
        if (before.isNotEmpty()) return emptyList()
        val p = ctx.source.sender as? Player ?: return listOf("1", "2", "3", "4")
        val sign = p.getTargetBlockExact(SignEdit.REACH)?.state as? Sign ?: return listOf("1", "2", "3", "4")
        val lines = sign.getSide(sign.getInteractableSideFor(p)).lines()
        // 지금 글을 제안해 고치기 쉽게(`1 지금글`).
        return (1..4).map { n -> "$n " + DecorText.toMini(lines.getOrNull(n - 1) ?: net.kyori.adventure.text.Component.empty()) }.map { it.trimEnd() } + listOf("1", "2", "3", "4")
    }

    private fun signCopy(ctx: DCtx, raw: String) {
        val p = player(ctx) ?: return
        var target = p
        var oneSide = false
        for (w in Args.split(raw)) {
            if (w.lowercase() in setOf("한면", "oneside", "one")) {
                oneSide = true
                continue
            }
            if (!p.hasPermission(DecorFeature.SIGN_COPY_OTHERS)) return ess.messages.send(p, "no-permission")
            target = ess.args.online(p, w) ?: return ess.messages.send(p, "player-not-found", Ph.of().value(w))
        }
        feature.signs.startCopy(p, target, oneSide)
    }

    private fun suggestSignCopy(ctx: DCtx, before: List<String>): List<String> =
        listOf("한면") + (if (ctx.source.sender.hasPermission(DecorFeature.SIGN_COPY_OTHERS)) Vanish.visibleNames(ctx.source.sender).toList() else emptyList())

    // --- /동적표지판 -------------------------------------------------------------------------------------

    private fun dynamicSign(ctx: DCtx, raw: String) {
        val p = player(ctx) ?: return
        val words = Args.split(raw)
        val dyn = feature.dynamic
        when (words.firstOrNull()?.lowercase()) {
            "목록", "list" -> {
                if (dyn.signs.isEmpty()) return ess.messages.send(p, "decor-dsign-none")
                for (d in dyn.signs.values.sortedBy { it.id }) {
                    ess.messages.send(p, "decor-dsign-line", Ph.of().count(d.id).world(d.world).raw("{x}", d.x.toString()).raw("{y}", d.y.toString())
                        .raw("{z}", d.z.toString()).value(d.lines.firstOrNull().orEmpty()))
                }
                return
            }
        }
        val block = p.getTargetBlockExact(SignEdit.REACH)
        val sign = block?.state as? Sign ?: return ess.messages.send(p, "decor-sign-look")
        when (words.firstOrNull()?.lowercase()) {
            "생성", "new", "create" -> {
                if (dyn.at(block) != null) return dyn.openMenu(p, block)
                val created = dyn.create(p, block, sign) ?: return
                ess.messages.send(p, "decor-dsign-created", Ph.of().count(created.id))
                dyn.openMenu(p, block)
            }
            "삭제", "remove", "delete" -> {
                val d = dyn.at(block) ?: return ess.messages.send(p, "decor-dsign-how-to")
                dyn.remove(d.id, restore = true)
                ess.messages.send(p, "decor-dsign-removed")
            }
            null, "편집", "edit" -> dyn.openMenu(p, block)
            else -> ess.messages.send(p, "decor-dsign-usage")
        }
    }

    private fun suggestDynamic(ctx: DCtx, before: List<String>): List<String> = if (before.isEmpty()) listOf("생성", "편집", "삭제", "목록") else emptyList()

    // --- /액자 -------------------------------------------------------------------------------------------

    private enum class FrameMode(val words: Set<String>) {
        INVISIBLE(setOf("투명", "invisible")), FIXED(setOf("고정", "fixed")), INVULNERABLE(setOf("무적", "invulnerable")),
        GLOW(setOf("발광", "glow", "glowing")), ALL(setOf("전부", "all")), INFO(setOf("정보", "info")),
    }

    private fun itemFrame(ctx: DCtx, raw: String) {
        val p = player(ctx) ?: return
        val words = Args.split(raw)
        val mode = words.firstOrNull()?.lowercase()?.let { w -> FrameMode.entries.firstOrNull { w in it.words } } ?: return ess.messages.send(p, "decor-frame-usage")
        var value: Boolean? = null
        var radius: Int? = null
        var force = false
        var i = 1
        while (i < words.size) {
            val w = words[i].lowercase()
            when {
                w in setOf("켜기", "on", "true") -> value = true
                w in setOf("끄기", "off", "false") -> value = false
                w in setOf("강제", "force", "-f") -> force = true
                w in setOf("주변", "near", "radius") -> {
                    radius = words.getOrNull(i + 1)?.toIntOrNull() ?: feature.settings.itemFrame.radius
                    if (words.getOrNull(i + 1)?.toIntOrNull() != null) i++
                }
                else -> return ess.messages.send(p, "decor-frame-usage")
            }
            i++
        }
        if (radius != null) {
            if (!p.hasPermission(DecorFeature.ITEMFRAME_AREA)) return ess.messages.send(p, "no-permission")
            if (mode == FrameMode.INFO) return ess.messages.send(p, "decor-frame-usage")
            val r = radius.coerceIn(1, feature.settings.itemFrame.maxRadius).toDouble()
            val frames = p.getNearbyEntities(r, r, r).filterIsInstance<ItemFrame>()
            val on = value ?: true
            val any = p.hasPermission(DecorFeature.ITEMFRAME_ANY)
            var count = 0
            var protectedCount = 0
            var foreign = 0
            for (frame in frames) {
                // 다른 플러그인이 쓰는 액자(가구·지도 그림 — 자기 PDC 를 붙인다)는 `강제` 없이 건드리지 않는다.
                if (!force && ItemFrames.foreign(frame)) {
                    foreign++
                    continue
                }
                // 주변 바꾸기도 액자마다 보호 구역을 본다 — `.area` 가 `.any` 를 겸하지 않게.
                if (!any && !feature.probe.mayTouch(p, frame)) {
                    protectedCount++
                    continue
                }
                if (apply(frame, mode, on) != null) count++
            }
            ess.messages.send(p, "decor-frame-area", Ph.of().count(count).value(modeName(mode)).raw("{상태}", ess.messages.raw(if (on) "decor-on" else "decor-off")))
            if (protectedCount > 0 || foreign > 0) {
                ess.messages.send(p, "decor-frame-area-skipped", Ph.of().raw("{보호}", protectedCount.toString()).raw("{남의것}", foreign.toString()))
            }
            return
        }
        val frame = lookedAt(p) { it is ItemFrame } as? ItemFrame ?: return ess.messages.send(p, "decor-frame-look")
        if (!p.hasPermission(DecorFeature.ITEMFRAME_ANY) && !feature.probe.mayTouch(p, frame)) return ess.messages.send(p, "decor-frame-protected")
        if (mode == FrameMode.INFO) return info(p, frame)
        val on = value ?: when (mode) {
            FrameMode.INVISIBLE -> frame.isVisible
            FrameMode.FIXED -> !frame.isFixed
            FrameMode.INVULNERABLE -> !frame.isInvulnerable
            FrameMode.GLOW -> frame !is GlowItemFrame
            else -> !(frame.isFixed && frame.isInvulnerable && !frame.isVisible)
        }
        val result = apply(frame, mode, on) ?: return ess.messages.send(p, "decor-frame-failed")
        ess.messages.send(p, "decor-frame-done", Ph.of().value(modeName(mode)).raw("{상태}", ess.messages.raw(if (on) "decor-on" else "decor-off")))
        if (result !== frame) info(p, result)
    }

    private fun info(p: Player, frame: ItemFrame) {
        fun b(v: Boolean) = ess.messages.raw(if (v) "decor-on" else "decor-off")
        ess.messages.send(p, "decor-frame-info", Ph.of().raw("{투명}", b(!frame.isVisible)).raw("{고정}", b(frame.isFixed)).raw("{무적}", b(frame.isInvulnerable))
            .raw("{발광}", b(frame is GlowItemFrame)))
    }

    private fun modeName(mode: FrameMode): String = ess.messages.raw("decor-frame-mode-${mode.name.lowercase()}")

    /** 바꾼 액자(발광은 새 엔티티로 바뀐다). 실패면 null. */
    private fun apply(frame: ItemFrame, mode: FrameMode, on: Boolean): ItemFrame? = when (mode) {
        FrameMode.INVISIBLE -> frame.also { it.isVisible = !on }
        FrameMode.FIXED -> frame.also { it.isFixed = on }
        FrameMode.INVULNERABLE -> frame.also { it.isInvulnerable = on }
        FrameMode.GLOW -> ItemFrames.swapGlow(frame, on)
        FrameMode.ALL -> frame.also {
            it.isVisible = !on
            it.isFixed = on
            it.isInvulnerable = on
        }
        FrameMode.INFO -> frame
    }

    private fun suggestItemFrame(ctx: DCtx, before: List<String>): List<String> = when (before.size) {
        0 -> listOf("투명", "고정", "무적", "발광", "전부", "정보")
        else -> listOf("켜기", "끄기") + (if (ctx.source.sender.hasPermission(DecorFeature.ITEMFRAME_AREA)) listOf("주변", "강제") else emptyList())
    }

    private companion object {
        const val REACH = 6.0
        const val NEAR_MAX = 10
    }
}

package com.inmc.essentials.decor

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import io.papermc.paper.event.player.PlayerOpenSignEvent
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.integration.PlayerSettings
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.DyeColor
import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID

/**
 * 표지판 편집·복사(CMI signEdit · `/cmi se` · `/cmi sc`).
 *
 * - 웅크리고 맨손 우클릭 → 바닐라 표지판 화면을 다시 연다(Paper `Player.openSign`). 바닐라가 하지 않는 것은 하지 않는다: 어드벤처·관전 모드는
 *   손대지 않고, **클릭 명령이 든 면**은 바닐라가 명령을 실행하게 둔다. 금지 목록(상점 표지판)은 우클릭을 막지 않는다(우클릭 상점이 돈다).
 * - **바닐라가 스스로 여는 편집 화면**(1.20+ 맨손 우클릭 — `PlayerOpenSignEvent` 원인 INTERACT)도 같은 규칙으로 막는다([onOpen]): `.sign.edit` 권한이
 *   없거나(`.sign.any` 는 통과 — 2026-10-14 리뷰: 권한을 빼도 바닐라 화면이 열리던 것), 개인 설정을 껐거나, `require-sneaking` 인데 웅크리지 않았거나,
 *   금지 목록이거나, 권한 없이 동적 표지판이면. 막아도 우클릭 자체는 그대로(상점이 돈다). 우리가 여는 화면은
 *   원인 PLUGIN 이라 걸리지 않는다. `sign-edit.enabled: false` 면 바닐라를 그대로 둔다(2026-10-10 리뷰 — 설정·토글이 바닐라 화면에는 듣지 않던 것).
 * - 밀랍칠한 표지판은 권한이 있으면 **입력창(Dialog)** 으로 고친다 — 바닐라 화면은 밀랍칠한 표지판의 글을 받지 않고, 밀랍을 잠깐 벗기면 그 사이
 *   서버가 꺼질 때 벗겨진 채 남는다(2026-10-10 리뷰). 밀랍칠한 것은 `require-sneaking` 과 상관없이 늘 웅크려야 연다. 설정 `sign-edit.dialog` 면 늘 입력창.
 * - 바꾼 글은 늘 `SignChangeEvent` 를 지난다 — 보호 구역(Lands·WorldGuard 가 이 사건을 막는다)·상점·기록 플러그인(CoreProtect)이 보고, 막으면 안 바뀐다.
 *   그래서 `/표지판편집` 은 따로 가짜 클릭을 쏘지 않는다(가짜 클릭이 커스텀 아이템·인첸트를 발동시켰다 — 리뷰).
 * - 색 권한이면 바닐라 화면으로 **새로 쓴 줄**의 `&`·MiniMessage 색을 읽는다([DecorText] — 색·꾸밈까지만. 클릭 사건은 서식 권한이 입력창·
 *   `/표지판편집` 으로 직접 친 글에만). 고치지 않은 줄은 건드리지 않는다 — 남이 글자로 써 둔 `<click…>` 이 관리자가 저장할 때 살아나지 않게.
 * - 첫 줄이 금지 목록이면 편집·복사·붙여넣기 금지(상점 표지판).
 * - 동적 표지판은 `/동적표지판` 권한이 없으면 바닐라 화면으로도 못 고친다(고쳐도 다음 갱신까지 남던 문제).
 * - 개인 설정 `essentials.shift-sign-edit`(웅크려 우클릭 편집, 기본 켬 — CMI Player.Options shiftSignEdit).
 */
class SignEdit(private val ess: Ess, private val settings: () -> DecorSettings, private val dynamic: DynamicSigns) : Listener {

    data class Copied(val front: List<Component>, val back: List<Component>, val frontColor: DyeColor?, val backColor: DyeColor?,
                      val frontGlow: Boolean, val backGlow: Boolean, val oneSide: Side?)

    private val copying = HashMap<UUID, Copied>()

    /** 우리가 [apply] 로 쏜 사건 — 글은 이미 권한대로 지었으니 [onChange] 가 다시 읽지 않는다. */
    private var applying = false

    /** 검증기 — `.sign.any` 우회를 무시한다(검증하는 사람은 관리자다). */
    @Volatile
    internal var ignoreBypass = false

    private fun any(player: Player): Boolean = !ignoreBypass && player.hasPermission(DecorFeature.SIGN_ANY)

    // --- 웅크려 우클릭 · 복사 붙여넣기 -----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock ?: return
        val player = event.player
        if (event.action == Action.LEFT_CLICK_BLOCK) {
            val copied = copying[player.uniqueId] ?: return
            // 취소하면 useInteractedBlock 이 DENY 가 된다 — 보호 플러그인의 판정은 그 전에 읽는다.
            val denied = event.useInteractedBlock() == Event.Result.DENY
            event.isCancelled = true
            val sign = block.state as? Sign
            if (sign == null) {
                copying.remove(player.uniqueId)
                return ess.messages.send(player, "decor-sign-copy-cancelled")
            }
            if (denied && !any(player)) return ess.messages.send(player, "decor-sign-protected")
            paste(player, block, copied)
            return
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val s = settings().sign
        val sign = block.state as? Sign ?: return
        if (!s.enabled || !player.inventory.itemInMainHand.type.isAir) return
        // 바닐라: 어드벤처·관전은 표지판을 못 고친다(mayBuild).
        if (player.gameMode == GameMode.ADVENTURE || player.gameMode == GameMode.SPECTATOR) return
        if (event.useInteractedBlock() == Event.Result.DENY) return
        val side = sign.getInteractableSideFor(player)
        // 바닐라: 클릭 명령이 있으면 명령을 실행하고 화면은 열지 않는다 — 워프·규칙 표지판이 모두에게 편집기로 열리지 않게.
        if (sign.getSide(side).lines().any { DecorText.hasClick(it) }) return
        val sneakNeeded = s.requireSneaking || sign.isWaxed
        if (sneakNeeded && !player.isSneaking) return
        if (dynamic.at(block) != null) {
            if (player.hasPermission(DecorFeature.DSIGN)) {
                event.isCancelled = true
                dynamic.openMenu(player, block)
            }
            return
        }
        if (!player.hasPermission(DecorFeature.SIGN_EDIT) || !PlayerSettings.enabled(player, SETTING_SHIFT_EDIT, true)) return
        // 금지 목록은 그냥 둔다(취소·알림 없음) — 우클릭 상점 표지판이 그대로 돈다.
        if (isBlacklisted(sign) && !player.hasPermission(DecorFeature.SIGN_BLACKLIST_BYPASS)) return
        if (sign.isWaxed && !player.hasPermission(DecorFeature.SIGN_EDIT_WAXED)) return
        event.isCancelled = true
        open(player, block, sign, side)
    }

    /** 바닐라가 우클릭으로 여는 편집 화면(위 KDoc). 붙여넣기·우리 화면(PLUGIN)·놓을 때(PLACE)는 건드리지 않는다. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onOpen(event: PlayerOpenSignEvent) {
        if (event.cause != PlayerOpenSignEvent.Cause.INTERACT) return
        val s = settings().sign
        if (!s.enabled) return
        val player = event.player
        val sign = event.sign
        val allowed = vanillaOpenAllowed(
            permitted = player.hasPermission(DecorFeature.SIGN_EDIT) || any(player),
            toggle = PlayerSettings.enabled(player, SETTING_SHIFT_EDIT, true),
            requireSneaking = s.requireSneaking,
            sneaking = player.isSneaking,
            blacklisted = isBlacklisted(sign) && (ignoreBypass || !player.hasPermission(DecorFeature.SIGN_BLACKLIST_BYPASS)),
            dynamicLocked = dynamic.at(sign.block) != null && !player.hasPermission(DecorFeature.DSIGN),
        )
        if (!allowed) event.isCancelled = true
    }

    private fun isBlacklisted(sign: Sign): Boolean = settings().sign.blocked(DecorText.plain(sign.getSide(Side.FRONT).line(0)))

    /** 명령·붙여넣기용 — 금지 목록이면 알리고 true. */
    private fun blocked(player: Player, sign: Sign): Boolean {
        if (!isBlacklisted(sign) || player.hasPermission(DecorFeature.SIGN_BLACKLIST_BYPASS)) return false
        ess.messages.send(player, "decor-sign-blacklisted", Ph.of().value(DecorText.plain(sign.getSide(Side.FRONT).line(0))))
        return true
    }

    /** 편집 화면 — 바닐라 표지판 화면, 또는 입력창(설정 · 밀랍칠한 표지판). */
    fun open(player: Player, block: Block, sign: Sign, side: Side) {
        if (!settings().sign.dialog && !sign.isWaxed) {
            player.openSign(sign, side)
            return
        }
        val lines = sign.getSide(side).lines()
        val form = DialogForm("<yellow>표지판 편집</yellow>").line("<gray>" + (if (DecorFeature.textLevel(player) != DecorText.Level.PLAIN) "&색 · <색> 을 쓸 수 있습니다" else "글만") + "</gray>")
        for (i in 0 until 4) form.text("line$i", "${i + 1}줄", DecorText.toMini(lines.getOrElse(i) { Component.empty() }), 384)
        form.show(ess.plugin, player) { p, values ->
            val current = (block.state as? Sign) ?: return@show
            if (dynamic.at(block) != null) return@show ess.messages.send(p, "decor-sign-is-dynamic")
            if (current.isWaxed && !p.hasPermission(DecorFeature.SIGN_EDIT_WAXED)) return@show ess.messages.send(p, "decor-sign-waxed")
            val level = DecorFeature.textLevel(p)
            val parsed = (0 until 4).map { DecorText.parse(values.text("line$it"), level) }
            if (apply(p, block, current, side, parsed)) ess.messages.send(p, "decor-sign-updated")
        }
    }

    /**
     * 글 네 줄을 바꾼다 — `SignChangeEvent` 를 지나며, 막히면 그대로. 바뀌었으면 true. 밀랍은 건드리지 않는다(API 로 쓰는 글은 밀랍과 상관없다).
     */
    fun apply(player: Player, block: Block, sign: Sign, side: Side, lines: List<Component>): Boolean {
        val event = SignChangeEvent(block, player, lines.toMutableList(), side)
        applying = true
        try {
            Bukkit.getPluginManager().callEvent(event)
        } finally {
            applying = false
        }
        if (event.isCancelled) {
            ess.messages.send(player, "decor-sign-protected")
            return false
        }
        val fresh = block.state as? Sign ?: return false
        val target = fresh.getSide(side)
        for (i in 0 until 4) target.line(i, event.line(i) ?: Component.empty())
        fresh.update(false, false)
        return true
    }

    /** 동적 표지판은 `/동적표지판` 권한이 있어야 고친다(바닐라 화면으로 고친 글이 다음 갱신까지 남지 않게). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDynamicEdit(event: SignChangeEvent) {
        if (applying || dynamic.at(event.block) == null) return
        if (!event.player.hasPermission(DecorFeature.DSIGN)) event.isCancelled = true
    }

    /**
     * 바닐라 화면으로 바꾼 글에 색 입히기(색 권한). **이 사람이 고친 줄만**(지금 블록의 그 줄과 글이 다른 줄) — 고치지 않은 줄의 글자 `<click…>` 을
     * 관리자가 저장할 때 살리지 않는다. 이 길은 색·꾸밈까지만(서식 권한도) — 클릭·마우스 올리기는 입력창·`/표지판편집` 으로 직접 친 글에만.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChange(event: SignChangeEvent) {
        if (applying) return
        if (DecorFeature.textLevel(event.player) == DecorText.Level.PLAIN) return
        val before = (event.block.state as? Sign)?.getSide(event.side)
        for (i in 0 until 4) {
            val line = event.line(i) ?: continue
            val plain = DecorText.plain(line)
            if (before != null && DecorText.plain(before.line(i)) == plain) continue
            if (DecorText.hasMarkup(plain)) event.line(i, DecorText.parse(plain, DecorText.Level.COLOR))
        }
    }

    /** 동적 표지판을 누가 고쳤으면(권한이 있는 사람) 다음 틱에 다시 그린다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChanged(event: SignChangeEvent) {
        dynamic.invalidate(event.block)
    }

    // --- /표지판편집 ------------------------------------------------------------------------------------

    /** 바라보는 표지판. 없으면 알리고 null. */
    fun target(player: Player): Pair<Block, Sign>? {
        val block = player.getTargetBlockExact(REACH)
        val sign = block?.state as? Sign
        if (block == null || sign == null) {
            ess.messages.send(player, "decor-sign-look")
            return null
        }
        return block to sign
    }

    fun editLine(player: Player, line: Int, text: String) {
        val (block, sign) = target(player) ?: return
        if (dynamic.at(block) != null) return ess.messages.send(player, "decor-sign-is-dynamic")
        if (blocked(player, sign)) return
        if (sign.isWaxed && !player.hasPermission(DecorFeature.SIGN_EDIT_WAXED)) return ess.messages.send(player, "decor-sign-waxed")
        val side = sign.getInteractableSideFor(player)
        val lines = sign.getSide(side).lines().toMutableList()
        while (lines.size < 4) lines += Component.empty()
        val level = DecorFeature.textLevel(player)
        for ((i, part) in DecorText.signLines(line, text)) lines[i] = if (part.isEmpty()) Component.empty() else DecorText.parse(part, level)
        // 보호 구역은 SignChangeEvent 가 본다(apply).
        if (apply(player, block, sign, side, lines)) ess.messages.send(player, "decor-sign-updated")
    }

    // --- /표지판복사 -------------------------------------------------------------------------------------

    fun startCopy(sender: Player, target: Player, oneSide: Boolean) {
        val (_, sign) = target(sender) ?: return
        if (blocked(sender, sign)) return
        val side = sign.getInteractableSideFor(sender)
        val front = sign.getSide(Side.FRONT)
        val back = sign.getSide(Side.BACK)
        copying[target.uniqueId] = Copied(front.lines(), back.lines(), front.color, back.color, front.isGlowingText, back.isGlowingText, if (oneSide) side else null)
        ess.messages.send(target, "decor-sign-copy-punch")
        if (target != sender) ess.messages.send(sender, "decor-sign-copy-given", Ph.of().target(target.name))
    }

    /** 검증기 — 복사해 둔 것을 넣고 뺀다(메모리). */
    internal fun setCopied(id: UUID, copied: Copied?) {
        if (copied == null) copying.remove(id) else copying[id] = copied
    }

    private fun paste(player: Player, block: Block, copied: Copied) {
        val sign = block.state as? Sign ?: return
        if (blocked(player, sign)) return
        if (dynamic.at(block) != null) return ess.messages.send(player, "decor-sign-is-dynamic")
        if (sign.isWaxed && !player.hasPermission(DecorFeature.SIGN_EDIT_WAXED)) return ess.messages.send(player, "decor-sign-waxed")
        val sides = if (copied.oneSide != null) {
            val clicked = sign.getInteractableSideFor(player)
            listOf(Triple(clicked, if (copied.oneSide == Side.FRONT) copied.front else copied.back, (if (copied.oneSide == Side.FRONT) copied.frontColor to copied.frontGlow else copied.backColor to copied.backGlow)))
        } else {
            listOf(Triple(Side.FRONT, copied.front, copied.frontColor to copied.frontGlow), Triple(Side.BACK, copied.back, copied.backColor to copied.backGlow))
        }
        var ok = true
        for ((side, lines, style) in sides) {
            val current = block.state as? Sign ?: break
            if (!apply(player, block, current, side, lines)) {
                ok = false
                break
            }
            val styled = block.state as? Sign ?: break
            style.first?.let { styled.getSide(side).color = it }
            styled.getSide(side).isGlowingText = style.second
            styled.update(false, false)
        }
        if (ok) ess.messages.send(player, "decor-sign-copy-updated")
    }

    // --- 정리 ---------------------------------------------------------------------------------------------

    fun quit(id: UUID) {
        copying.remove(id)
    }

    fun clear() {
        copying.clear()
    }

    companion object {
        const val SETTING_SHIFT_EDIT = "essentials.shift-sign-edit"
        const val REACH = 6

        /**
         * 바닐라가 우클릭으로 여는 편집 화면을 그대로 둘까(순수 — `DecorRulesTest`). [permitted](`.sign.edit` 또는 `.sign.any`)가 없으면 막는다 — 개인 설정은
         * 권한 없는 사람에게 기본값(켬)을 돌려주므로 토글이 권한을 대신하지 못한다. 개인 설정 [toggle] 을 끄면 우클릭으로는 열리지 않는다(명령은 된다).
         * [requireSneaking] 이면 웅크렸을 때만. 금지 목록([blacklisted] — 우회 권한은 부르는 쪽이 뺐다)·권한 없는 동적 표지판([dynamicLocked])은 늘 막는다.
         */
        fun vanillaOpenAllowed(permitted: Boolean, toggle: Boolean, requireSneaking: Boolean, sneaking: Boolean, blacklisted: Boolean, dynamicLocked: Boolean): Boolean =
            permitted && toggle && (!requireSneaking || sneaking) && !blacklisted && !dynamicLocked
    }
}

package com.inmc.essentials.server

import com.inmc.essentials.Ess
import com.inmc.essentials.command.Args
import com.inmc.essentials.feature.Feature
import com.inmc.essentials.feature.Perm
import com.inmc.essentials.service.CmiData
import com.inmc.essentials.service.CmiImportable
import com.inmc.essentials.service.ImportReport
import com.inmc.essentials.util.Ph
import com.inmc.essentials.verify.Check
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import com.destroystokyo.paper.event.server.PaperServerListPingEvent
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.event.player.PlayerServerFullCheckEvent
import kr.inmc.core.gui.DialogForm
import kr.inmc.core.integration.Mail
import kr.inmc.core.integration.Vanish
import kr.inmc.core.util.AtomicFiles
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.permissions.PermissionDefault
import org.bukkit.persistence.PersistentDataType
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID

private typealias Ctx = CommandContext<CommandSourceStack>

/**
 * 서버 관리 — `/서버상태` · `/점검` · `/최대인원` · `/서버소개` · `/플레이스홀더` · `/책` · `/텍스트` · `/서식메시지` · `/유틸`,
 * 그리고 `/에센셜 데이터 이전` · `/에센셜 가져오기 cmi` · `/에센셜 로그`. 리로드는 뼈대의 `/에센셜 리로드`(모든 기능을 다시 읽는다).
 *
 * CMI 항목: cmd-tps · cmd-status · cmd-maintenance · cmd-setmotd · cmd-maxplayers · cmd-reload · cfg-database-storage · filesave-async ·
 * cmd-migratedatabase · cmd-importfrom · misc-logs-folder · cmd-util · wiki-placeholders · cfg-books · wiki-custom-text. 운영 문서 `docs/server.md`.
 *
 * CMI 가져오기에서 자기 몫(점검 상태 · `CustomText` · 접속 글 · 책 기본값)은 [importCmi] 가 넣는다 — CmiData 에 칸이 없어 [CmiImport] 가
 * 읽은 것([cmiExtras])을 건넨다.
 */
class ServerFeature(ess: Ess) : Feature(ess), CmiImportable {

    override val id = "server"

    override val hasSettings = true

    override val permissions: List<Perm> = listOf(
        Perm(STATUS, PermissionDefault.OP, "/서버상태 — TPS·메모리·월드"),
        Perm(MAINTENANCE, PermissionDefault.OP, "/점검 — 점검 모드 켜고 끄기"),
        Perm(MAINTENANCE_BYPASS, PermissionDefault.OP, "점검 중에도 들어오기"),
        Perm(FULL_BYPASS, PermissionDefault.OP, "서버가 가득 차도 들어오기"),
        Perm(MAXPLAYERS, PermissionDefault.OP, "/최대인원"),
        Perm(MOTD, PermissionDefault.OP, "/서버소개 — 서버 목록 글 바꾸기"),
        Perm(PLACEHOLDERS, PermissionDefault.OP, "/플레이스홀더 — 목록·값 보기"),
        Perm(PLACEHOLDERS_OTHERS, PermissionDefault.OP, "/플레이스홀더 보기 <플레이어> — 남 기준 값"),
        Perm(BOOK, PermissionDefault.OP, "/책 <이름> — 텍스트를 쓴 책 받기"),
        Perm(BOOK_OTHERS, PermissionDefault.OP, "/책 <이름> <대상> · /책 열기 <이름> <대상>"),
        Perm(BOOK_OPEN, PermissionDefault.TRUE, "/책 열기 <이름> — 텍스트를 책 화면으로"),
        Perm(BOOK_EDIT, PermissionDefault.OP, "/책 저자·제목·풀기 — 손에 든 쓴 책 고치기"),
        Perm(BOOK_COLOR, PermissionDefault.OP, "책에 서명할 때 & 색 코드가 색이 됨"),
        Perm(BOOK_UNLIMITED, PermissionDefault.OP, "책 쪽 수 제한(books.max-pages) 없음"),
        Perm(TEXT, PermissionDefault.TRUE, "/텍스트 — 커스텀 텍스트 보기"),
        Perm(TEXT_NODE, PermissionDefault.OP, "<ReqPermission> 텍스트는 inmcessentials.server.text.view.<이름> 이 있어야 봄"),
        Perm(TEXT_OTHERS, PermissionDefault.OP, "/텍스트 <이름> <대상|all> — 남에게 보이기"),
        Perm(TEXT_ADMIN, PermissionDefault.OP, "/텍스트 편집 · 숨긴 텍스트·권한 텍스트 보기"),
        Perm(TELLRAW, PermissionDefault.OP, "/서식메시지 — MiniMessage 글 보내기"),
        Perm(UTIL, PermissionDefault.OP, "/유틸 — 좌석 정리·대상 확인"),
    )

    override val messageDefaults: Map<String, String> = MESSAGES

    @Volatile
    var settings: ServerSettings = ServerSettings()
        private set

    @Volatile
    var state: ServerState = ServerState()
        private set

    val sampler = TpsSampler()

    /**
     * 데이터 이전 잠금 — `/에센셜 데이터 이전 … 확인` 이 시작한 때부터 서버를 다시 켤 때까지 접속을 모두 막는다(점검 우회도). 메모리에만 있다
     * (재시작하면 풀린다 — 새 DB 로 켜진 뒤에는 막을 까닭이 없다). 이전이 멈추거나 실패하면 풀고, 그만두려면 `/에센셜 데이터 잠금해제`.
     */
    @Volatile
    var migrationLocked = false
        private set

    /** CMI 가져오기 중에만 — [CmiImport] 가 읽은 server 몫. */
    @Volatile
    internal var cmiExtras: CmiReader.Extras? = null

    internal lateinit var listener: ServerListener
    internal lateinit var texts: TextService
    internal lateinit var books: Books
    internal lateinit var logs: Logs
    private lateinit var status: Status
    private lateinit var cmi: CmiImport
    private lateinit var migration: DbMigration

    private var flushCounter = 0

    override fun load(settings: YamlConfiguration) {
        this.settings = ServerSettings.from(settings)
        if (::texts.isInitialized) {
            texts.loadAsync()
            loadState()
        }
    }

    override fun enable() {
        listener = ServerListener(ess, this)
        texts = TextService(ess, this)
        books = Books(ess, this)
        logs = Logs(ess, this)
        status = Status(ess, this)
        cmi = CmiImport(ess, this)
        migration = DbMigration(ess)
        ess.listen(listener)
        ess.listen(texts)
        ess.listen(books)
        ess.listen(logs)
        ess.ticks.add("server", 1) { now ->
            sampler.record(now, Bukkit.getCurrentTick().toLong())
            if (++flushCounter % 5 == 0) logs.flush()
        }
        registerCommands()
        texts.loadAsync()
        loadState()
    }

    override fun disable() {
        if (::logs.isInitialized) logs.flushNow()
        if (::texts.isInitialized) runCatching { texts.unregisterAliases() }
        if (::listener.isInitialized) runCatching { listener.hideBar() }
    }

    // --- 상태 파일 ---------------------------------------------------------------------------------

    private val stateFile get() = ess.io.file("server", "state.yml")

    private fun loadState() {
        val file = stateFile
        ess.io.async({ if (file.isFile) ServerState.from(ess.io.load(file)) else ServerState() }) { loaded ->
            val before = state
            state = loaded
            loaded.maxPlayers?.let { setMaxPlayers(it) }
            if (loaded.maintenance != before.maintenance || loaded.maintenance) listener.maintenanceChanged(kick = false)
        }
    }

    private fun saveState(next: ServerState) {
        state = next
        val text = next.toYaml()
        val file = stateFile
        ess.io.asyncRun { AtomicFiles.write(file, text) }
    }

    // --- 명령 ----------------------------------------------------------------------------------------

    private fun perm(node: String): (CommandSourceStack) -> Boolean = { it.sender.hasPermission(node) }

    private fun self(ctx: Ctx): Player? =
        (ctx.source.executor as? Player ?: ctx.source.sender as? Player) ?: null.also { ess.messages.send(ctx.source.sender, "player-only") }

    private fun str(ctx: Ctx, name: String): String = StringArgumentType.getString(ctx, name)

    private val textNames = SuggestionProvider<CommandSourceStack> { ctx, b ->
        val typed = b.remainingLowerCase
        texts.listed(ctx.source.sender).map { it.name }.filter { it.lowercase().startsWith(typed) }.forEach { b.suggest(it) }
        b.buildFuture()
    }

    private fun registerCommands() {
        ess.commands.add(statusCommand(), "서버 상태 — TPS·메모리·월드", listOf("status", "lag"))
        ess.commands.add(maintenanceCommand(), "점검 모드", listOf("maintenance"))
        ess.commands.add(maxPlayersCommand(), "최대 접속 인원", listOf("maxplayers"))
        ess.commands.add(motdCommand(), "서버 목록 글(MOTD)", listOf("setmotd"))
        ess.commands.add(placeholdersCommand(), "PlaceholderAPI 목록·값 보기", listOf("placeholders"))
        ess.commands.add(bookCommand(), "텍스트를 쓴 책 · 책 고치기", listOf("getbook"))
        ess.commands.add(textCommand(), "커스텀 텍스트 보기", listOf("ctext"))
        ess.commands.add(tellrawCommand(), "MiniMessage 글 보내기", listOf("ctellraw"))
        ess.commands.add(utilCommand(), "관리 유틸 — 좌석 정리·대상 확인", listOf("cutil"))
        ess.commands.addAdmin(dataCommand())
        ess.commands.addAdmin(importCommand())
        ess.commands.addAdmin(logsCommand())
    }

    private fun statusCommand(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("서버상태").requires(perm(STATUS))
            .executes { ctx -> status.summary(ctx.source.sender); 1 }
            .then(Commands.literal("틱").executes { ctx -> status.ticks(ctx.source.sender); 1 })
            .then(Commands.literal("월드").executes { ctx -> status.busiest(ctx.source.sender); 1 })
            .build()

    // --- 점검 ---

    private fun maintenanceCommand(): LiteralCommandNode<CommandSourceStack> {
        val root = Commands.literal("점검").requires(perm(MAINTENANCE)).executes { ctx -> showMaintenance(ctx.source.sender); 1 }
        for (on in listOf("켜기", "on", "true")) {
            root.then(
                Commands.literal(on).executes { ctx -> setMaintenance(ctx.source.sender, true, ""); 1 }
                    .then(Commands.argument("사유", StringArgumentType.greedyString()).executes { ctx -> setMaintenance(ctx.source.sender, true, str(ctx, "사유")); 1 }),
            )
        }
        for (off in listOf("끄기", "off", "false")) root.then(Commands.literal(off).executes { ctx -> setMaintenance(ctx.source.sender, false, ""); 1 })
        return root.build()
    }

    private fun showMaintenance(sender: CommandSender) {
        val s = state
        if (s.maintenance) ess.messages.send(sender, "server-maintenance-is-on", Ph.of().reason(s.maintenanceReason.ifBlank { "-" }))
        else ess.messages.send(sender, "server-maintenance-is-off")
    }

    private fun setMaintenance(sender: CommandSender, on: Boolean, reason: String) {
        saveState(state.copy(maintenance = on, maintenanceReason = if (on) reason.trim() else ""))
        listener.maintenanceChanged(kick = on)
        ess.messages.send(sender, if (on) "server-maintenance-on" else "server-maintenance-off", Ph.of().reason(reason.ifBlank { "-" }))
    }

    // --- 최대 인원 ---

    private fun maxPlayersCommand(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("최대인원").requires(perm(MAXPLAYERS))
            .executes { ctx ->
                ess.messages.send(ctx.source.sender, "server-maxplayers-show", Ph.of().count(Vanish.visibleOnline(ctx.source.sender).size).value(Bukkit.getMaxPlayers().toString()))
                1
            }
            .then(Commands.argument("인원", IntegerArgumentType.integer(1, 100000)).executes { ctx ->
                val old = Bukkit.getMaxPlayers()
                val next = IntegerArgumentType.getInteger(ctx, "인원")
                if (!setMaxPlayers(next)) {
                    ess.messages.send(ctx.source.sender, "server-maxplayers-failed")
                } else {
                    saveState(state.copy(maxPlayers = next))
                    ess.messages.send(ctx.source.sender, "server-maxplayers-changed", Ph.of().amount(old.toString()).value(next.toString()).count(Vanish.visibleOnline(ctx.source.sender).size))
                }
                1
            })
            .build()

    /** `Server#setMaxPlayers`(26.1.2 에 있다) — 없는 판이면 false(재시작하면 server.properties 값). */
    fun setMaxPlayers(n: Int): Boolean = try {
        Bukkit.getServer().maxPlayers = n
        true
    } catch (e: NoSuchMethodError) {
        false
    } catch (e: UnsupportedOperationException) {
        false
    }

    // --- 서버 소개(MOTD) ---

    private fun motdCommand(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("서버소개").requires(perm(MOTD))
            .executes { ctx ->
                val sender = ctx.source.sender
                val lines = state.motd.ifEmpty { settings.motdLines }
                if (lines.isEmpty()) ess.messages.send(sender, "server-motd-default") else {
                    ess.messages.send(sender, "server-motd-current")
                    for (line in lines) sender.sendMessage(Text.render(line))
                }
                1
            }
            .then(Commands.literal("초기화").executes { ctx ->
                saveState(state.copy(motd = emptyList()))
                ess.messages.send(ctx.source.sender, "server-motd-reset")
                1
            })
            .then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx ->
                val lines = str(ctx, "글").split("\\n", "|").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
                saveState(state.copy(motd = lines))
                ess.messages.send(ctx.source.sender, "server-motd-set")
                for (line in lines) ctx.source.sender.sendMessage(Text.render(line))
                1
            })
            .build()

    // --- 플레이스홀더 ---

    private fun papiReady(sender: CommandSender): Boolean {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return true
        ess.messages.send(sender, "server-papi-missing")
        return false
    }

    private fun placeholdersCommand(): LiteralCommandNode<CommandSourceStack> {
        val ids = SuggestionProvider<CommandSourceStack> { _, b ->
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                val typed = b.remainingLowerCase
                PapiView.expansions().map { it.identifier }.filter { it.lowercase().startsWith(typed) }.forEach { b.suggest(it) }
            }
            b.buildFuture()
        }
        return Commands.literal("플레이스홀더").requires(perm(PLACEHOLDERS))
            .executes { ctx -> listExpansions(ctx.source.sender); 1 }
            .then(
                Commands.literal("목록").then(
                    Commands.argument("식별자", Args.TOKEN).suggests(ids)
                        .executes { ctx -> listPlaceholders(ctx.source.sender, str(ctx, "식별자"), 1); 1 }
                        .then(Commands.argument("쪽", IntegerArgumentType.integer(1)).executes { ctx ->
                            listPlaceholders(ctx.source.sender, str(ctx, "식별자"), IntegerArgumentType.getInteger(ctx, "쪽")); 1
                        }),
                ),
            )
            .then(Commands.literal("값").then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx ->
                val sender = ctx.source.sender
                if (papiReady(sender)) previewValue(sender, sender as? Player, str(ctx, "글"), other = false)
                1
            }))
            .then(Commands.literal("보기").requires(perm(PLACEHOLDERS_OTHERS)).then(ess.args.player("대상").then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx ->
                val sender = ctx.source.sender
                val name = str(ctx, "대상")
                val target = ess.args.offline(sender, name)
                if (target == null) ess.messages.send(sender, "user-not-found", Ph.of().value(name))
                else if (papiReady(sender)) previewValue(sender, Bukkit.getOfflinePlayer(target.id), str(ctx, "글"), other = true)
                1
            })))
            .build()
    }

    private fun listExpansions(sender: CommandSender) {
        if (!papiReady(sender)) return
        val all = PapiView.expansions()
        ess.messages.send(sender, "server-papi-header", Ph.of().count(all.size))
        for ((plugin, list) in all.groupBy { it.plugin }) {
            var line = ess.messages.component("server-papi-plugin", Ph.of().value(plugin), sender as? Player)
            for (exp in list) {
                line = line.append(Component.space()).append(
                    ess.messages.component("server-papi-expansion", Ph.of().value(exp.identifier).count(exp.placeholders.size), sender as? Player)
                        .clickEvent(ClickEvent.runCommand("/플레이스홀더 목록 ${exp.identifier}"))
                        .hoverEvent(HoverEvent.showText(ess.messages.component("server-papi-expansion-hover", Ph.of().value(exp.identifier).raw("{만든이}", exp.author).raw("{판}", exp.version), sender as? Player))),
                )
            }
            sender.sendMessage(line)
        }
    }

    private fun listPlaceholders(sender: CommandSender, identifier: String, page: Int) {
        if (!papiReady(sender)) return
        val exp = PapiView.expansions().firstOrNull { it.identifier.equals(identifier, true) }
            ?: return ess.messages.send(sender, "server-papi-unknown", Ph.of().value(identifier))
        if (exp.placeholders.isEmpty()) return ess.messages.send(sender, "server-papi-no-list", Ph.of().value(exp.identifier))
        val per = 10
        val pages = (exp.placeholders.size + per - 1) / per
        val p = page.coerceIn(1, pages)
        ess.messages.send(sender, "server-papi-list-head", Ph.of().value(exp.identifier).raw("{쪽}", p.toString()).raw("{전체}", pages.toString()).count(exp.placeholders.size))
        val viewer = sender as? Player
        for (raw in exp.placeholders.drop((p - 1) * per).take(per)) {
            val ph = if (raw.startsWith("%")) raw else "%$raw%"
            // 값은 옆에 바로(2026-10-15 사용자 — 눌러서 보던 것을). 매개변수 자리(<…>·{…})가 있으면 값을 낼 수 없다 — 누르면 입력창에 넣어 고쳐 쓰게.
            val line = if (PapiLine.isTemplate(ph)) {
                ess.messages.component("server-papi-entry-template", Ph.of().value(noPapi(ph)), viewer)
                    .clickEvent(ClickEvent.suggestCommand("/플레이스홀더 값 $ph"))
                    .hoverEvent(HoverEvent.showText(ess.messages.component("server-papi-entry-hover-template", null, viewer)))
            } else {
                val shown = PapiLine.shown(ph, PapiView.parse(viewer, ph))
                ess.messages.component(
                    "server-papi-entry",
                    Ph.of().value(noPapi(ph)).raw("{결과}", if (shown == null) ess.messages.raw("server-papi-entry-blank") else escape(shown)),
                    viewer,
                ).clickEvent(ClickEvent.copyToClipboard(ph))
                    .hoverEvent(HoverEvent.showText(ess.messages.component("server-papi-entry-hover", null, viewer)))
            }
            sender.sendMessage(line)
        }
        if (p < pages) sender.sendMessage(ess.messages.component("server-papi-more", Ph.of().value(exp.identifier).raw("{쪽}", (p + 1).toString()), sender as? Player).clickEvent(ClickEvent.runCommand("/플레이스홀더 목록 ${exp.identifier} ${p + 1}")))
    }

    /** 메시지는 PlaceholderAPI 를 한 번 더 지난다 — 보여 줄 자리표시 글이 풀리지 않게 `%` 를 비슷한 글자로. */
    private fun noPapi(text: String): String = text.replace('%', '％')

    /** 값을 MiniMessage 에 넣을 글로 — `%` 는 다시 풀리지 않게, `<` 는 태그로 읽히지 않게. */
    private fun escape(value: String): String = noPapi(value).replace("<", "\\<")

    /** [other] = 남의 값(`보기`) — 그때만 누구 값인지 적는다. 내 값은 표기 없이. */
    private fun previewValue(sender: CommandSender, subject: org.bukkit.OfflinePlayer?, text: String, other: Boolean) {
        val value = PapiView.parse(subject, text)
        val ph = Ph.of().value(noPapi(text)).raw("{결과}", escape(value)).target(subject?.name ?: "-")
        ess.messages.send(sender, if (other) "server-papi-result-other" else "server-papi-result", ph)
    }

    // --- 책 ---

    private fun bookCommand(): LiteralCommandNode<CommandSourceStack> {
        fun give() = Commands.argument("이름", Args.TOKEN).suggests(textNames).requires(perm(BOOK))
            .executes { ctx -> self(ctx)?.let { giveBook(ctx.source.sender, str(ctx, "이름"), listOf(it)) }; 1 }
            .then(ess.args.players("대상").requires(perm(BOOK_OTHERS)).executes { ctx ->
                val sender = ctx.source.sender
                ess.args.targets(sender, str(ctx, "대상"))?.let { giveBook(sender, str(ctx, "이름"), it) } ?: ess.messages.send(sender, "player-not-found", Ph.of().value(str(ctx, "대상")))
                1
            })
        val open = Commands.literal("열기").requires(perm(BOOK_OPEN)).then(
            Commands.argument("이름", Args.TOKEN).suggests(textNames)
                .executes { ctx -> self(ctx)?.let { openBook(ctx.source.sender, str(ctx, "이름"), listOf(it)) }; 1 }
                .then(ess.args.players("대상").requires(perm(BOOK_OTHERS)).executes { ctx ->
                    val sender = ctx.source.sender
                    ess.args.targets(sender, str(ctx, "대상"))?.let { openBook(sender, str(ctx, "이름"), it) } ?: ess.messages.send(sender, "player-not-found", Ph.of().value(str(ctx, "대상")))
                    1
                }),
        )
        return Commands.literal("책").requires { it.sender.hasPermission(BOOK) || it.sender.hasPermission(BOOK_OPEN) || it.sender.hasPermission(BOOK_EDIT) }
            .executes { ctx -> listTexts(ctx.source.sender, "server-book-list"); 1 }
            .then(open)
            .then(Commands.literal("저자").requires(perm(BOOK_EDIT)).then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx ->
                self(ctx)?.let { books.setAuthor(it, str(ctx, "글")) }; 1
            }))
            .then(Commands.literal("제목").requires(perm(BOOK_EDIT)).then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx ->
                self(ctx)?.let { books.setTitle(it, str(ctx, "글")) }; 1
            }))
            .then(Commands.literal("풀기").requires(perm(BOOK_EDIT)).executes { ctx -> self(ctx)?.let { books.unlock(it) }; 1 })
            .then(Commands.literal("받기").requires(perm(BOOK)).then(give()))
            .then(give())
            .build()
    }

    private fun doc(sender: CommandSender, name: String): TextDoc? {
        val doc = texts.get(name)
        if (doc == null || !texts.canSee(sender, doc)) {
            ess.messages.send(sender, "server-text-unknown", Ph.of().value(name))
            return null
        }
        return doc
    }

    private fun giveBook(sender: CommandSender, name: String, targets: List<Player>) {
        val doc = doc(sender, name) ?: return
        for (t in targets) {
            val stack = books.item(doc, t)
            val left = t.inventory.addItem(stack).values.toList()
            if (left.isEmpty()) continue
            // 가방이 찼으면 우편으로(규칙 9) — 우편 기능이 없을 때만 발밑. 둘 중 하나만.
            val mailed = Mail.available && Mail.send(t.uniqueId, origin = "inmc-essentials", source = "책 '${doc.name}'", stacks = left)
            if (mailed) ess.messages.send(t, "server-book-mailed", Ph.of().value(doc.name))
            else left.forEach { t.world.dropItemNaturally(t.location, it) }
        }
        ess.messages.send(sender, "server-book-given", Ph.of().value(doc.name).count(targets.size))
    }

    private fun openBook(sender: CommandSender, name: String, targets: List<Player>) {
        val doc = doc(sender, name) ?: return
        for (t in targets) books.open(doc, t)
    }

    // --- 텍스트 ---

    private fun textCommand(): LiteralCommandNode<CommandSourceStack> {
        val name = Commands.argument("이름", Args.TOKEN).suggests(textNames)
            .executes { ctx -> showText(ctx, str(ctx, "이름"), null, 1); 1 }
            .then(Commands.argument("쪽또는대상", Args.TOKEN).suggests(ess.args.names)
                .executes { ctx ->
                    val second = str(ctx, "쪽또는대상")
                    val page = second.toIntOrNull()
                    if (page != null) showText(ctx, str(ctx, "이름"), null, page) else showText(ctx, str(ctx, "이름"), second, 1)
                    1
                }
                .then(Commands.argument("쪽", IntegerArgumentType.integer(1)).executes { ctx ->
                    showText(ctx, str(ctx, "이름"), str(ctx, "쪽또는대상"), IntegerArgumentType.getInteger(ctx, "쪽")); 1
                }))
        return Commands.literal("텍스트").requires(perm(TEXT))
            .executes { ctx -> listTexts(ctx.source.sender, "server-text-list"); 1 }
            .then(Commands.literal("편집").requires(perm(TEXT_ADMIN)).then(Commands.argument("파일", Args.TOKEN).suggests(textNames).executes { ctx ->
                self(ctx)?.let { editText(it, str(ctx, "파일")) }; 1
            }))
            .then(name)
            .build()
    }

    private fun listTexts(sender: CommandSender, header: String) {
        val list = texts.listed(sender)
        if (list.isEmpty()) return ess.messages.send(sender, "server-text-none")
        var line = ess.messages.component(header, Ph.of().count(list.size), sender as? Player)
        for (doc in list) {
            line = line.append(Component.space()).append(
                ess.messages.component("server-text-entry", Ph.of().value(doc.name), sender as? Player).clickEvent(ClickEvent.runCommand("/텍스트 ${doc.name}")),
            )
        }
        sender.sendMessage(line)
    }

    private fun showText(ctx: Ctx, name: String, target: String?, page: Int) {
        val sender = ctx.source.sender
        val doc = doc(sender, name) ?: return
        if (target == null) {
            texts.show(sender, doc, page)
            return
        }
        if (!sender.hasPermission(TEXT_OTHERS)) return ess.messages.send(sender, "no-permission")
        val targets = ess.args.targets(sender, target) ?: return ess.messages.send(sender, "player-not-found", Ph.of().value(target))
        for (t in targets) texts.show(t, doc, page)
        ess.messages.send(sender, "server-text-sent", Ph.of().value(doc.name).count(targets.size))
    }

    private fun editText(player: Player, name: String) {
        if (!TextDoc.validName(name)) return ess.messages.send(player, "server-text-bad-name", Ph.of().value(name))
        texts.readRaw(name) { raw ->
            DialogForm("텍스트 $name 편집")
                .line("MiniMessage · & 색 · {player} · %PAPI% · <NextPage> 쪽 나눔 · <AutoPage> · <Hidden> · <ReqPermission> · <AutoAlias>")
                .text("body", "내용", raw, 16000, multiline = true)
                .show(ess.plugin, player) { p, v ->
                    texts.save(name, v.text("body")) { ess.messages.send(p, "server-text-saved", Ph.of().value(name)) }
                }
        }
    }

    // --- 서식 메시지 ---

    private fun tellrawCommand(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("서식메시지").requires(perm(TELLRAW))
            .then(ess.args.players("대상").then(Commands.argument("글", StringArgumentType.greedyString()).executes { ctx ->
                val sender = ctx.source.sender
                val targets = ess.args.targets(sender, str(ctx, "대상")) ?: return@executes 1.also { ess.messages.send(sender, "player-not-found", Ph.of().value(str(ctx, "대상"))) }
                val raw = CmiText.toMiniMessage(str(ctx, "글"))
                for (t in targets) t.sendMessage(Text.render(raw, Ph.of().player(t.name), t))
                ess.messages.send(sender, "server-tellraw-sent", Ph.of().count(targets.size))
                1
            }))
            .build()

    // --- 유틸 ---

    private fun utilCommand(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("유틸").requires(perm(UTIL))
            .executes { ctx -> ess.messages.send(ctx.source.sender, "server-util-help"); 1 }
            .then(Commands.literal("좌석정리").executes { ctx ->
                ess.messages.send(ctx.source.sender, "server-util-seats-removed", Ph.of().count(removeSeats()))
                1
            })
            .then(Commands.literal("대상확인").executes { ctx -> self(ctx)?.let { testTarget(it) }; 1 })
            .build()

    /** 탄 사람 없는 좌석(`inmcess:seat` 이 달린 ItemDisplay)을 지운다 — player 기능의 앉기(Seats.kt)가 다는 열쇠. */
    fun removeSeats(): Int {
        var n = 0
        for (world in Bukkit.getWorlds()) {
            for (e in world.getEntitiesByClass(ItemDisplay::class.java)) {
                if (!e.persistentDataContainer.has(SEAT_KEY, PersistentDataType.BYTE) || e.passengers.isNotEmpty()) continue
                e.remove()
                n++
            }
        }
        return n
    }

    private fun testTarget(player: Player) {
        val entity = player.getTargetEntity(64)
        val block = player.getTargetBlockExact(64)
        if (entity == null && block == null) return ess.messages.send(player, "server-util-target-none")
        entity?.let { e ->
            ess.messages.send(
                player, "server-util-target-entity",
                Ph.of().value(e.type.key.key).raw("{이름}", Text.plain(e.name())).raw("{uuid}", e.uniqueId.toString())
                    .raw("{거리}", "%.1f".format(e.location.distance(player.eyeLocation))).raw("{좌표}", "${e.location.blockX} ${e.location.blockY} ${e.location.blockZ}"),
            )
        }
        block?.let { b ->
            ess.messages.send(
                player, "server-util-target-block",
                Ph.of().value(b.blockData.asString).raw("{거리}", "%.1f".format(b.location.add(0.5, 0.5, 0.5).distance(player.eyeLocation))).raw("{좌표}", "${b.x} ${b.y} ${b.z}"),
            )
        }
    }

    // --- /에센셜 데이터 · 가져오기 · 로그 ---

    private fun dataCommand(): LiteralCommandNode<CommandSourceStack> {
        val move = Commands.literal("이전")
        for (to in listOf("mysql", "sqlite")) {
            val mysql = to == "mysql"
            move.then(
                Commands.literal(to).executes { ctx -> migratePlan(ctx.source.sender, mysql); 1 }
                    .then(Commands.literal("확인").executes { ctx -> migrate(ctx.source.sender, mysql, false); 1 }
                        .then(Commands.literal("덮어쓰기").executes { ctx -> migrate(ctx.source.sender, mysql, true); 1 })),
            )
        }
        val unlock = Commands.literal("잠금해제").executes { ctx ->
            val sender = ctx.source.sender
            if (!migrationLocked) {
                ess.messages.send(sender, "server-migrate-not-locked")
            } else {
                migrationLocked = false
                ess.logger.warning("${sender.name} 이(가) 데이터 이전 잠금을 풀었습니다")
                ess.messages.send(sender, "server-migrate-unlocked")
            }
            1
        }
        return Commands.literal("데이터").executes { ctx ->
            ess.messages.send(ctx.source.sender, "server-migrate-status", Ph.of().value(if (ess.db.network != null) "mysql" else "sqlite"))
            if (migrationLocked) ess.messages.send(ctx.source.sender, "server-migrate-locked")
            1
        }.then(move).then(unlock).build()
    }

    private fun currentIsMysql(): Boolean = ess.db.network != null

    private fun migratePlan(sender: CommandSender, toMysql: Boolean) {
        if (problem(sender, toMysql)) return
        ess.messages.send(sender, "server-migrate-plan", Ph.of().value(if (currentIsMysql()) "mysql" else "sqlite").target(if (toMysql) "mysql" else "sqlite"))
    }

    private fun problem(sender: CommandSender, toMysql: Boolean): Boolean {
        if (toMysql == currentIsMysql()) {
            ess.messages.send(sender, "server-migrate-same", Ph.of().value(if (toMysql) "mysql" else "sqlite"))
            return true
        }
        if (toMysql && ess.config.storage.url.isBlank()) {
            ess.messages.send(sender, "server-migrate-no-url")
            return true
        }
        return false
    }

    private fun migrate(sender: CommandSender, toMysql: Boolean, overwrite: Boolean) {
        if (problem(sender, toMysql)) return
        val others = DbMigration.othersOnline((sender as? Player)?.uniqueId)
        if (others > 0) return ess.messages.send(sender, "server-migrate-players-online", Ph.of().count(others))
        if (migrationLocked) return ess.messages.send(sender, "server-migrate-locked")
        // 지금부터 재시작까지 아무도 못 들어온다 — 베끼는 중·끝난 뒤의 기록이 옛 DB 에만 남지 않게.
        migrationLocked = true
        ess.messages.send(sender, "server-migrate-lock-on")
        migration.run(toMysql, overwrite, { key, values ->
            Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ ->
                val ph = Ph.of()
                for ((k, v) in values) ph.raw(k, v)
                ess.messages.send(sender, key, ph)
            }
        }) { ok ->
            if (!ok) {
                Bukkit.getGlobalRegionScheduler().run(ess.plugin) { _ ->
                    migrationLocked = false
                    ess.messages.send(sender, "server-migrate-unlocked")
                }
            }
        }
    }

    private fun importCommand(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("가져오기")
            .executes { ctx -> ess.messages.send(ctx.source.sender, "server-import-usage"); 1 }
            .then(Commands.literal("cmi").executes { ctx -> cmi.start(ctx.source.sender, false); 1 }
                .then(Commands.literal("확인").executes { ctx -> cmi.start(ctx.source.sender, true); 1 }))
            .build()

    private fun logsCommand(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("로그").executes { ctx ->
            val sender = ctx.source.sender
            val s = settings
            ess.io.async({ logs.todaySizes() }) { sizes ->
                ess.messages.send(
                    sender, "server-logs-status",
                    Ph.of().raw("{명령}", ess.messages.raw(if (s.logCommands) "server-on" else "server-off"))
                        .raw("{채팅}", ess.messages.raw(if (s.logChat) "server-on" else "server-off"))
                        .raw("{명령크기}", ((sizes["commands"] ?: 0L) / 1024).toString()).raw("{채팅크기}", ((sizes["chat"] ?: 0L) / 1024).toString())
                        .count(s.logKeepDays).value("plugins/inmc-essentials/logs/"),
                )
            }
            1
        }.build()

    // --- CMI 가져오기(자기 몫) ------------------------------------------------------------------------------

    override fun importCmi(data: CmiData, report: ImportReport) {
        val x = cmiExtras ?: return report.note("server — CMI config·CustomText 를 건네받지 못해 점검 상태·텍스트를 옮기지 않았습니다")
        if (!::texts.isInitialized) return report.skipped(KIND_TEXT, "server 기능이 켜지지 않았습니다")
        // 1. 점검 — CMI 는 재시작해도 점검을 지킨다. 여기서 끄면 CMI 를 뺀 날 서버가 모두에게 열린다.
        if (x.maintenance == true) {
            report.read(KIND_MAINTENANCE)
            if (state.maintenance) {
                report.skipped(KIND_MAINTENANCE, "이미 점검 중입니다")
            } else {
                saveState(state.copy(maintenance = true, maintenanceReason = state.maintenanceReason.ifBlank { "CMI 에서 가져옴" }))
                listener.maintenanceChanged(kick = false)
                report.imported(KIND_MAINTENANCE)
                report.note("CMI 가 점검 중(Optimizations.Maintenance: true)이라 이 플러그인의 점검도 켰습니다 — 열려면 /점검 끄기")
            }
        }
        x.maintenanceAutoKick?.takeIf { it != settings.maintenanceAutoKick }?.let {
            report.note("CMI MaintenanceAutoKick: $it — settings/server.yml maintenance.auto-kick 은 ${settings.maintenanceAutoKick} 입니다(맞추려면 고치고 /에센셜 리로드)")
        }
        x.maintenanceBossBar?.takeIf { it != settings.maintenanceBossBar }?.let {
            report.note("CMI MaintenanceBossBar: $it — settings/server.yml maintenance.bossbar 는 ${settings.maintenanceBossBar} 입니다(맞추려면 고치고 /에센셜 리로드)")
        }
        // 2. 커스텀 텍스트 — 같은 이름이 없을 때만 texts/ 로 베낀다(CMI 표식은 TextDoc 이 그대로 읽는다). 쓰기·다시 읽기는 I/O 스레드.
        val dir = texts.folder
        val write = LinkedHashMap<java.io.File, String>()
        for ((name, text) in x.customTexts) {
            when {
                !TextDoc.validName(name) -> report.unreadable(KIND_TEXT, "$name — 이름에 빈칸·점·콜론·슬래시가 있어 옮기지 않았습니다(texts/ 에 다른 이름으로 직접 넣으세요)")
                texts.get(name) != null || java.io.File(dir, "$name.txt").exists() -> report.skipped(KIND_TEXT, "$name — 같은 이름의 텍스트가 이미 있습니다")
                else -> {
                    write[java.io.File(dir, "$name.txt")] = text
                    report.imported(KIND_TEXT)
                }
            }
        }
        if (write.isNotEmpty()) {
            ess.io.async({
                dir.mkdirs()
                for ((file, text) in write) AtomicFiles.write(file, text)
            }) { texts.loadAsync() }
        }
        // 3. 접속 글(config `Motd:` = CustomText 이름) — 설정 파일은 고치지 않는다(관리자가 고르게).
        x.joinText?.let { name ->
            val exists = texts.get(name) != null || x.customTexts.keys.any { it.equals(name, true) }
            when {
                settings.joinText.equals(name, true) -> Unit
                !exists -> report.note("CMI 접속 글 Motd: $name — 그 이름의 CustomText 가 없어(CMI 에서도 안 보였습니다) 옮기지 않습니다")
                else -> report.note("CMI 접속 글 Motd: $name — settings/server.yml 의 texts.join-text 에 '$name' 을 적고 /에센셜 리로드 하면 접속 때 보입니다(지금: '${settings.joinText}')")
            }
        }
        // 4. 책 기본값
        x.bookAuthor?.takeIf { it != settings.bookAuthor }?.let {
            report.note("CMI Books.DefaultAuthor: $it — settings/server.yml books.default-author 는 '${settings.bookAuthor}' 입니다(맞추려면 고치고 /에센셜 리로드)")
        }
        x.bookAddDate?.takeIf { it != settings.bookAddDate }?.let {
            report.note("CMI Books.AddDate: $it — settings/server.yml books.add-date 는 ${settings.bookAddDate} 입니다")
        }
    }

    // --- 검증기 ----------------------------------------------------------------------------------------

    override fun checks(): List<Check> = listOf(
        Check("서버 목록 — 투명인 사람은 인원수·목록에서 빠지고 보이는 사람은 남는다") { p ->
            if (!settings.motdHideVanished) return@Check null
            val online = Bukkit.getOnlinePlayers().toList()
            val event = PaperServerListPingEvent(PROBE_CLIENT, Component.text("motd"), online.size, Bukkit.getMaxPlayers(), "v", 0, null)
            event.listedPlayers.clear()
            for (o in online) event.listedPlayers += PaperServerListPingEvent.ListedPlayerInfo(o.name, o.uniqueId)
            listener.onPing(event)
            val vanished = online.filter { Vanish.isVanished(it) }
            when {
                settings.motdHover.isEmpty() && event.listedPlayers.any { l -> vanished.any { it.uniqueId == l.id() } } -> "투명인 사람이 서버 목록에 남았습니다"
                settings.motdHover.isEmpty() && !Vanish.isVanished(p) && event.listedPlayers.none { it.id() == p.uniqueId } -> "보이는 검증자가 서버 목록에서 빠졌습니다"
                event.numPlayers != online.size - vanished.size -> "인원수 ${event.numPlayers} — 투명 ${vanished.size}명을 뺀 ${online.size - vanished.size} 이어야 합니다"
                else -> null
            }
        },
        Check("점검 — 켜면 서버 목록 글이 점검 문구로(메모리만)") { _ ->
            val before = state
            try {
                state = before.copy(maintenance = true)
                val event = PaperServerListPingEvent(PROBE_CLIENT, Component.text("원래"), 0, 20, "v", 0, null)
                listener.onPing(event)
                if (settings.maintenanceMotd.isNotEmpty() && Text.plain(event.motd()) == "원래") "점검 중인데 서버 목록 글이 그대로입니다" else null
            } finally {
                state = before
            }
        },
        Check("점검 — 우회 권한이 있는 사람은 통과") { p ->
            if (!p.hasPermission(MAINTENANCE_BYPASS)) return@Check null
            if (listener.bypassesMaintenance(p.uniqueId, p.name) == true) null else "우회 권한이 있는데 막힙니다"
        },
        // 접속 직전 사건은 비동기 사건이라 메인에서 callEvent 할 수 없다 — 우리 리스너에 바로 준다.
        Check("점검 — 처음 보는 사람은 접속 직전에 거절(권한을 모르면 들이고 접속 때 다시 봄, 메모리만)") { _ ->
            val before = state
            try {
                state = before.copy(maintenance = true)
                val id = UUID.randomUUID()
                val event = AsyncPlayerPreLoginEvent(PROBE_NAME, InetAddress.getLoopbackAddress(), id)
                listener.onPreLogin(event)
                val unknown = listener.bypassesMaintenance(id, PROBE_NAME) == null
                when {
                    unknown && event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED -> "권한을 모르는 사람을 접속 직전에 막았습니다(접속 때 다시 봐야 합니다)"
                    !unknown && event.loginResult != AsyncPlayerPreLoginEvent.Result.KICK_OTHER -> "점검 중인데 우회가 아닌 사람을 들였습니다(${event.loginResult})"
                    else -> null
                }
            } finally {
                state = before
            }
        },
        Check("데이터 이전 잠금 — 잠긴 동안은 아무도 못 들어온다(메모리만)") { _ ->
            val before = migrationLocked
            try {
                migrationLocked = true
                val event = AsyncPlayerPreLoginEvent(PROBE_NAME, InetAddress.getLoopbackAddress(), UUID.randomUUID())
                listener.onPreLogin(event)
                if (event.loginResult == AsyncPlayerPreLoginEvent.Result.KICK_OTHER) null else "이전 잠금 중인데 들였습니다(${event.loginResult})"
            } finally {
                migrationLocked = before
            }
        },
        Check("가득 참 우회 — 우회가 아닌 처음 보는 사람은 그대로 거절(메모리만)") { _ ->
            if (!settings.fullBypass) return@Check null
            val event = PlayerServerFullCheckEvent(Bukkit.createProfile(UUID.randomUUID(), PROBE_NAME), Component.text("full"), true)
            listener.onFull(event)
            if (event.isAllowed) "우회가 아닌 사람을 가득 찬 서버에 들였습니다" else null
        },
        Check("TPS 기록이 쌓인다") { _ -> if (sampler.tps(3) != null) null else "1초 틱이 TPS 를 적지 않았습니다(켠 지 3초 안이면 다시)" },
        Check("CMI 표식 → MiniMessage") { _ ->
            val out = CmiText.toMiniMessage("<T>규칙</T><H>보기</H><C>rules</C>")
            if (out == "<hover:show_text:'보기'><click:run_command:'/rules'>규칙</click></hover>") null else "바꾼 결과가 다릅니다: $out"
        },
        Check("텍스트 폴더를 읽었다") { _ -> if (texts.texts.isNotEmpty() || !texts.folder.isDirectory) null else "texts/ 에 파일이 있는데 하나도 읽지 못했습니다" },
    )

    companion object {
        const val STATUS = "inmcessentials.server.status"
        const val MAINTENANCE = "inmcessentials.server.maintenance"
        const val MAINTENANCE_BYPASS = "inmcessentials.server.maintenance.bypass"
        const val FULL_BYPASS = "inmcessentials.server.fullbypass"
        const val MAXPLAYERS = "inmcessentials.server.maxplayers"
        const val MOTD = "inmcessentials.server.motd"
        const val PLACEHOLDERS = "inmcessentials.server.placeholders"
        const val PLACEHOLDERS_OTHERS = "inmcessentials.server.placeholders.others"
        const val BOOK = "inmcessentials.server.book"
        const val BOOK_OTHERS = "inmcessentials.server.book.others"
        const val BOOK_OPEN = "inmcessentials.server.book.open"
        const val BOOK_EDIT = "inmcessentials.server.book.edit"
        const val BOOK_COLOR = "inmcessentials.server.book.color"
        const val BOOK_UNLIMITED = "inmcessentials.server.book.unlimited"
        const val TEXT = "inmcessentials.server.text"
        const val TEXT_NODE = "inmcessentials.server.text.view"
        const val TEXT_OTHERS = "inmcessentials.server.text.others"
        const val TEXT_ADMIN = "inmcessentials.server.text.admin"
        const val TELLRAW = "inmcessentials.server.tellraw"
        const val UTIL = "inmcessentials.server.util"

        private const val KIND_MAINTENANCE = "점검 상태"
        private const val KIND_TEXT = "커스텀 텍스트"
        private const val PROBE_NAME = "essverify"

        /** player 기능의 앉기 좌석 열쇠 — `player/Seats.kt` 의 `SEAT_KEY` 와 같은 값(기능끼리 import 하지 않으므로 이름으로). */
        val SEAT_KEY = NamespacedKey(Ess.NAMESPACE, "seat")

        /** 검증기가 쏘는 서버 목록 사건의 손님(가짜 — 이 서버 자신). */
        private val PROBE_CLIENT = object : com.destroystokyo.paper.network.StatusClient {
            override fun getAddress(): InetSocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
            override fun getProtocolVersion(): Int = -1
            override fun getVirtualHost(): InetSocketAddress? = null
        }

        val MESSAGES: Map<String, String> = linkedMapOf(
            "server-on" to "<green>켬</green>",
            "server-off" to "<red>끔</red>",
            // --- 상태 ---
            "server-status-header" to "<gold>━━ 서버 상태</gold> <gray>{값}</gray>",
            "server-status-tps" to "<gray>TPS(1·5·15분)</gray> {1분} <gray>/</gray> {5분} <gray>/</gray> {15분} <gray>· MSPT</gray> <white>{mspt}</white>",
            "server-status-cpu" to "<gray>CPU 서버</gray> <white>{서버}%</white> <gray>· 전체</gray> <white>{전체}%</white> <gray>· 코어</gray> <white>{코어}</white> <gray>· 스레드</gray> <white>{스레드}</white>",
            "server-status-memory" to "<hover:show_text:'<gray>남음 {남음}MB · 할당 {할당}MB · 최대 {최대}MB</gray>'><gray>메모리</gray> <white>{사용}/{최대}MB</white> <gray>({퍼센트}%)</gray></hover>",
            "server-status-uptime" to "<gray>켜진 지</gray> <white>{시간}</white>",
            "server-status-java" to "<gray>Java</gray> <white>{값}</white> <gray>· {os}</gray>",
            "server-status-disk" to "<gray>디스크</gray> <white>{사용}/{전체}GB</white>",
            "server-status-players" to "<gray>접속</gray> <white>{개수}/{최대}</white> <gray>· 대기 작업</gray> <white>{작업}</white>",
            "server-status-world" to "<yellow>{월드}</yellow> <gray>청크</gray> <white>{청크}</white> <gray>· 엔티티</gray> <white>{엔티티}</white> <gray>· 타일</gray> <white>{타일}</white> <gray>· 사람</gray> <white>{개수}</white>",
            "server-status-busy-entities" to "<gray>  엔티티 많은 청크</gray> <white>{x}, {z}</white> <gray>({개수})</gray>",
            "server-status-busy-tiles" to "<gray>  타일 많은 청크</gray> <white>{x}, {z}</white> <gray>({개수})</gray>",
            "server-status-tps-windows" to "<gray>TPS 3초</gray> {3초} <gray>· 10초</gray> {10초} <gray>· 30초</gray> {30초} <gray>· 1분</gray> {1분} <gray>· 10분</gray> {10분}",
            "server-status-tps-minmax" to "<gray>최근 10분 최저</gray> {최저} <gray>· 최고</gray> {최고}",
            "server-status-tps-no-spikes" to "<gray>최근 10분 동안 TPS 가 {값} 밑으로 내려간 적이 없습니다.</gray>",
            "server-status-tps-spike" to "<gray>  {시간}</gray> <gray>TPS</gray> {tps}",
            // --- 점검 ---
            "server-maintenance-on" to "<red>점검 모드를 켰습니다.</red> <gray>사유: {사유}</gray>",
            "server-maintenance-off" to "<green>점검 모드를 껐습니다.</green>",
            "server-maintenance-is-on" to "<red>지금 점검 중입니다.</red> <gray>사유: {사유} — /점검 끄기</gray>",
            "server-maintenance-is-off" to "<green>점검 중이 아닙니다.</green> <gray>/점검 켜기 [사유]</gray>",
            "server-maintenance-bossbar" to "<red>점검 중</red> <gray>— {사유}</gray>",
            // --- 최대 인원 ---
            "server-maxplayers-show" to "<gray>접속</gray> <white>{개수}/{값}</white> <gray>— /최대인원 <수></gray>",
            "server-maxplayers-changed" to "<green>최대 인원을 {금액} → {값} 으로 바꿨습니다.</green> <gray>(접속 {개수})</gray>",
            "server-maxplayers-failed" to "<red>이 서버에서는 최대 인원을 바꿀 수 없습니다(server.properties 의 max-players 를 고치고 재시작).</red>",
            // --- 서버 소개 ---
            "server-motd-current" to "<gray>지금 서버 목록 글:</gray>",
            "server-motd-default" to "<gray>서버 목록 글을 바꾸지 않았습니다(server.properties 그대로). /서버소개 <글> — 두 줄은 | 로</gray>",
            "server-motd-set" to "<green>서버 목록 글을 바꿨습니다:</green>",
            "server-motd-reset" to "<green>서버 목록 글을 설정(motd.lines)·server.properties 로 되돌렸습니다.</green>",
            // --- 플레이스홀더 ---
            "server-papi-missing" to "<red>PlaceholderAPI 가 없습니다.</red>",
            "server-papi-header" to "<gold>PlaceholderAPI 확장 {개수}개</gold> <gray>— 누르면 목록</gray>",
            "server-papi-plugin" to "<yellow>{값}</yellow><gray>:</gray>",
            "server-papi-expansion" to "<aqua><u>{값}</u></aqua><gray>({개수})</gray>",
            "server-papi-expansion-hover" to "<gray>％{값}_…％ · 만든이 {만든이} · 판 {판}</gray>",
            "server-papi-unknown" to "<red>'{값}' 확장이 없습니다.</red>",
            "server-papi-no-list" to "<yellow>{값} 확장은 자기 목록(getPlaceholders)을 내지 않습니다.</yellow> <gray>/플레이스홀더 값 ％{값}_…％ 로 직접 보세요.</gray>",
            "server-papi-list-head" to "<gold>％{값}_…％</gold> <gray>{개수}개 · {쪽}/{전체}쪽 — 누르면 자리표시 복사</gray>",
            "server-papi-entry" to "<white>{값}</white> <dark_gray>→</dark_gray> <aqua>{결과}</aqua>",
            "server-papi-entry-blank" to "<dark_gray>(값 없음)</dark_gray>",
            "server-papi-entry-template" to "<white>{값}</white> <dark_gray>→ (매개변수 필요)</dark_gray>",
            "server-papi-entry-hover" to "<gray>누르면 자리표시를 복사합니다</gray>",
            "server-papi-entry-hover-template" to "<gray>매개변수가 있는 자리표시입니다 — 누르면 입력창에 넣으니 고쳐 써서 값을 보세요</gray>",
            "server-papi-more" to "<gray><u>다음 쪽 ▶</u></gray>",
            "server-papi-result" to "<gray>{값}</gray> <dark_gray>→</dark_gray> <white>{결과}</white>",
            "server-papi-result-other" to "<gray>{값}</gray> <dark_gray>({대상}) →</dark_gray> <white>{결과}</white>",
            // --- 책 ---
            "server-book-list" to "<gold>책으로 만들 텍스트 {개수}개</gold> <gray>— /책 받기 <이름> · /책 열기 <이름></gray>",
            "server-book-given" to "<green>책 '{값}' 을(를) {개수}명에게 주었습니다.</green>",
            "server-book-mailed" to "<yellow>가방이 가득 차서 책 '{값}' 을(를) 우편으로 보냈습니다.</yellow> <gray>/우편</gray>",
            "server-book-not-held" to "<red>쓴 책(서명한 책)을 손에 들어 주세요.</red>",
            "server-book-author" to "<green>저자를 '{값}' (으)로 바꿨습니다.</green>",
            "server-book-title" to "<green>제목을 '{값}' (으)로 바꿨습니다.</green>",
            "server-book-unlocked" to "<green>책을 다시 쓸 수 있게 풀었습니다.</green>",
            "server-book-too-many-pages" to "<red>책은 {개수}쪽까지만 쓸 수 있습니다.</red>",
            "server-book-date" to "<gray>작성일 {시간}</gray>",
            // --- 텍스트 ---
            "server-text-list" to "<gold>텍스트 {개수}개</gold> <gray>— 누르면 보기</gray>",
            "server-text-entry" to "<aqua><u>{값}</u></aqua>",
            "server-text-none" to "<gray>볼 수 있는 텍스트가 없습니다.</gray> <gray>(plugins/inmc-essentials/texts/)</gray>",
            "server-text-unknown" to "<red>'{값}' 텍스트가 없습니다.</red>",
            "server-text-header" to "<gold>━━ {값}</gold> <gray>{쪽}/{전체}</gray>",
            "server-text-prev" to "<yellow><u>◀ 이전</u></yellow>",
            "server-text-next" to "<yellow><u>다음 ▶</u></yellow>",
            "server-text-sent" to "<green>텍스트 '{값}' 을(를) {개수}명에게 보였습니다.</green>",
            "server-text-bad-name" to "<red>'{값}' 은(는) 텍스트 이름으로 쓸 수 없습니다(빈칸·점·콜론·슬래시 안 됨).</red>",
            "server-text-saved" to "<green>텍스트 '{값}' 을(를) 저장하고 다시 읽었습니다.</green>",
            "server-tellraw-sent" to "<gray>{개수}명에게 보냈습니다.</gray>",
            // --- 유틸 ---
            "server-util-help" to "<gray>/유틸 좌석정리 — 탄 사람 없는 앉기 좌석 지우기 · /유틸 대상확인 — 바라보는 엔티티·블록</gray>",
            "server-util-seats-removed" to "<green>남은 좌석 {개수}개를 지웠습니다.</green>",
            "server-util-target-none" to "<gray>64칸 안에 바라보는 것이 없습니다.</gray>",
            "server-util-target-entity" to "<yellow>엔티티</yellow> <white>{값}</white> <gray>'{이름}' · {좌표} · {거리}칸 ·</gray> <click:copy_to_clipboard:'{uuid}'><gray><u>{uuid}</u></gray></click>",
            "server-util-target-block" to "<yellow>블록</yellow> <white>{값}</white> <gray>· {좌표} · {거리}칸</gray>",
            // --- 데이터 이전 ---
            "server-migrate-status" to "<gray>지금 저장소:</gray> <white>{값}</white> <gray>— /에센셜 데이터 이전 <mysql|sqlite></gray>",
            "server-migrate-same" to "<red>이미 {값} 을(를) 쓰고 있습니다.</red>",
            "server-migrate-no-url" to "<red>config.yml 의 storage.mysql.url(·user·password)을 먼저 적고 /에센셜 리로드 하세요(type 은 아직 sqlite 로).</red>",
            "server-migrate-plan" to "<yellow>{값} → {대상} 으로 ess_ 표를 전부 베낍니다.</yellow> <gray>접속자가 없을 때 /에센셜 데이터 이전 {대상} 확인 — 받는 쪽에 자료가 있으면 멈춥니다(덮어쓰기 를 붙이면 비우고 베낌).</gray>",
            "server-migrate-players-online" to "<red>다른 접속자가 {개수}명 있습니다 — 모두 나간 뒤에 하세요.</red>",
            "server-migrate-schema-failed" to "<yellow>기능 {값} 의 표를 만들지 못했습니다: {사유}</yellow>",
            "server-migrate-target-not-empty" to "<red>받는 쪽 표에 이미 자료가 있어 멈췄습니다:</red> <white>{값}</white> <gray>— 덮어쓰려면 … 확인 덮어쓰기</gray>",
            "server-migrate-started" to "<yellow>{값} → {대상}: 표 {개수}개를 베낍니다…</yellow>",
            "server-migrate-table" to "<gray>  {값}: {개수}줄</gray>",
            "server-migrate-done" to "<green>다 베꼈습니다 — {개수}줄.</green> <yellow>config.yml storage.type 을 {값} 으로 바꾸고 서버를 다시 켜세요.</yellow> <gray>다시 켤 때까지 접속은 막혀 있습니다 — 그 사이 내가 바꾼 것도 옛 DB 에만 남으니 아무것도 하지 말고 재시작하세요.</gray>",
            "server-migrate-lock-on" to "<yellow>이전 잠금을 걸었습니다 — 서버를 다시 켤 때까지 아무도(점검 우회도) 들어올 수 없습니다.</yellow> <gray>그만두려면 /에센셜 데이터 잠금해제</gray>",
            "server-migrate-locked" to "<yellow>데이터 이전 잠금 중입니다 — 서버를 다시 켜거나 /에센셜 데이터 잠금해제</yellow>",
            "server-migrate-unlocked" to "<green>이전 잠금을 풀었습니다 — 다시 들어올 수 있습니다.</green>",
            "server-migrate-not-locked" to "<gray>이전 잠금이 걸려 있지 않습니다.</gray>",
            "server-migrate-login-blocked" to "<red>서버 데이터를 옮기는 중입니다.</red><newline><gray>잠시 뒤에 다시 들어와 주세요.</gray>",
            "server-migrate-failed" to "<red>이전이 실패했습니다: {사유}</red> <gray>(서버 로그를 보세요 — 받는 쪽은 표마다 트랜잭션이라 반쯤 들어간 표는 없습니다)</gray>",
            // --- CMI 가져오기 ---
            "server-import-usage" to "<gray>/에센셜 가져오기 cmi — 먼저 읽어서 보여 주고, 확인 을 붙이면 넣습니다(CMI 폴더는 고치지 않음)</gray>",
            "server-import-no-folder" to "<red>CMI 폴더가 없습니다: {값}</red>",
            "server-import-reading" to "<gray>CMI 를 읽는 중… ({값})</gray>",
            "server-import-failed" to "<red>CMI 를 읽지 못했습니다 — 서버 로그를 보세요.</red>",
            "server-import-preview" to "<gold>CMI 자료</gold> <gray>— 유저 {개수} · 홈 {홈} · 우편 {우편} · 워프 {워프} · 파일: {파일} · 못 읽은 줄 {못읽음}</gray>",
            "server-import-confirm" to "<yellow>넣을 기능: {값}</yellow> <gray>— {시간}분 안에</gray> <white>/에센셜 가져오기 cmi 확인</white> <gray>(이미 있는 것은 건너뜀 · 두 번 해도 겹치지 않음)</gray>",
            "server-import-done" to "<green>CMI 가져오기를 마쳤습니다.</green> <gray>보고서: {값}</gray>",
            "server-import-summary-line" to "<gray>  {값}</gray>",
            "server-import-unreadable" to "<red>못 읽은 줄이 {개수}개 있습니다 — 보고서를 꼭 보세요.</red>",
            "server-import-cmi-running" to "<yellow>CMI 가 아직 켜져 있어 가져온 이벤트 명령·일정은 꺼 두었습니다(두 번 돌지 않게).</yellow> <gray>CMI 를 뺀 뒤 켜세요 — 이름과 켜는 법은 보고서에.</gray>",
            // --- 로그 ---
            "server-logs-status" to "<gold>로그</gold> <gray>명령</gray> {명령} <gray>({명령크기}KB) · 채팅</gray> {채팅} <gray>({채팅크기}KB) · {개수}일 보관 · {값}</gray> <gray>— settings/server.yml logs.*</gray>",
        )
    }
}

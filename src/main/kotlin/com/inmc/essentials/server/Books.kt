package com.inmc.essentials.server

import com.inmc.essentials.Ess
import com.inmc.essentials.util.Ph
import kr.inmc.core.util.Text
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.WritableBookMeta
import java.time.LocalDate

/**
 * 책 — 커스텀 텍스트를 쓴 책을 주거나(`/책 <이름>`) 책 화면으로 바로 열고(`/책 열기`), 손에 든 쓴 책의 저자·제목을 바꾸거나 다시 쓸 수 있게
 * 푼다(`/책 저자·제목·풀기`). CMI `getbook`·`openbook`·`book` 과 `Books.DefaultAuthor`·`AddDate`.
 *
 * 플레이어가 쓰는 책: 서명할 때 `&` 색 코드는 `inmcessentials.server.book.color` 가 있어야 색이 되고, 쪽 수는 `books.max-pages`
 * (`inmcessentials.server.book.unlimited` 면 무제한 — CMI `cmi.book.pages.N` 대신 설정 하나).
 */
class Books(private val ess: Ess, private val feature: ServerFeature) : Listener {

    /** 책 한 쪽은 14줄쯤 — 텍스트 한 쪽을 그만큼씩 나눈다. */
    private fun pages(doc: TextDoc, viewer: Player): List<Component> {
        val ph = Ph.of().player(viewer.name)
        return doc.pages.flatMap { it.chunked(LINES_PER_PAGE) }.map { lines ->
            lines.fold(Component.empty()) { acc, line -> (if (acc == Component.empty()) acc else acc.append(Component.newline())).append(Text.render(line, ph, viewer)) }
        }.ifEmpty { listOf(Component.empty()) }
    }

    fun item(doc: TextDoc, viewer: Player): ItemStack {
        val stack = ItemStack(Material.WRITTEN_BOOK)
        val meta = stack.itemMeta as BookMeta
        meta.title(Component.text(doc.name.take(32)))
        meta.author(Text.render(feature.settings.bookAuthor))
        meta.generation = BookMeta.Generation.ORIGINAL
        meta.addPages(*pages(doc, viewer).toTypedArray())
        if (feature.settings.bookAddDate) meta.lore(listOf(Text.renderFlat(ess.messages.raw("server-book-date"), Ph.of().time(LocalDate.now().toString()))))
        stack.itemMeta = meta
        return stack
    }

    fun open(doc: TextDoc, viewer: Player) {
        viewer.openBook(Book.book(Component.text(doc.name), Text.render(feature.settings.bookAuthor), pages(doc, viewer)))
    }

    /** 손에 든 쓴 책. 없으면 알리고 null. */
    fun held(player: Player): Pair<ItemStack, BookMeta>? {
        val stack = player.inventory.itemInMainHand
        if (stack.type != Material.WRITTEN_BOOK) {
            ess.messages.send(player, "server-book-not-held")
            return null
        }
        return stack to (stack.itemMeta as BookMeta)
    }

    fun setAuthor(player: Player, author: String) {
        val (stack, meta) = held(player) ?: return
        meta.author(Text.render(author))
        stack.itemMeta = meta
        ess.messages.send(player, "server-book-author", Ph.of().value(author))
    }

    fun setTitle(player: Player, title: String) {
        val (stack, meta) = held(player) ?: return
        meta.title(Text.render(title))
        stack.itemMeta = meta
        ess.messages.send(player, "server-book-title", Ph.of().value(title))
    }

    /** 쓴 책 → 책과 깃펜(글은 평문으로). */
    fun unlock(player: Player) {
        val (stack, meta) = held(player) ?: return
        val writable = ItemStack(Material.WRITABLE_BOOK, stack.amount)
        val wm = writable.itemMeta as WritableBookMeta
        wm.setPages(meta.pages().map { Text.plain(it) })
        writable.itemMeta = wm
        player.inventory.setItemInMainHand(writable)
        ess.messages.send(player, "server-book-unlocked")
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEdit(event: PlayerEditBookEvent) {
        val player = event.player
        val meta = event.newBookMeta
        val max = feature.settings.bookMaxPages
        if (meta.pageCount > max && !player.hasPermission(ServerFeature.BOOK_UNLIMITED)) {
            event.isCancelled = true
            ess.messages.send(player, "server-book-too-many-pages", Ph.of().count(max))
            return
        }
        if (event.isSigning && player.hasPermission(ServerFeature.BOOK_COLOR)) {
            for (i in 1..meta.pageCount) {
                val plain = Text.plain(meta.page(i))
                if (!plain.contains('&')) continue
                // 플레이어가 쓴 `<…>` 은 태그로 읽지 않고(태그 주입 막기) PlaceholderAPI 도 풀지 않는다 — `&` 색만 색이 된다.
                meta.page(i, MiniMessage.miniMessage().deserialize(Text.legacyToMiniMessage(plain.replace("<", "\\<"))))
            }
            event.newBookMeta = meta
        }
    }

    companion object {
        const val LINES_PER_PAGE = 14
    }
}

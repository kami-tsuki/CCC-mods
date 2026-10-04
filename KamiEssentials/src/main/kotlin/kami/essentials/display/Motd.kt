package kami.essentials.display

import kami.essentials.Config
import kami.essentials.MotdSettings
import kami.essentials.discord.Bot
import kami.libs.chat.Theme
import kami.libs.claims.ClaimsApi
import kami.libs.discord.DiscordText
import kami.libs.economy.MarketApi
import kami.libs.economy.Quote
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.status.ServerStatus
import net.minecraft.server.MinecraftServer
import java.util.Locale
import kotlin.math.abs

object Motd {
    const val MARKER = "kami:motd:"
    private const val WIDTH = 270
    private val separator = Piece("  ·  ", Theme.MUTED)
    @Volatile private var frame = 0L
    @Volatile private var snapshot: Snapshot? = null

    private class Piece(val text: String, val color: Int)
    private class Snapshot(val frame: Long, val items: Int, val frames: Int, val pages: List<List<Quote>>, val countries: Int)

    fun tick(server: MinecraftServer) {
        val m = Config.s.motd
        if (!m.enabled) return
        val now = System.currentTimeMillis() / (m.frameSeconds * 1000L)
        if (now == frame) return
        frame = now
        server.invalidateStatus()
    }

    fun apply(server: MinecraftServer, status: ServerStatus): ServerStatus {
        val players = runCatching {
            val visible = Bot.visible(server)
            status.players().map { p -> ServerStatus.Players(p.max(), visible.size, p.sample().filter { s -> visible.any { it.uuid == s.id } }) }
        }.getOrDefault(status.players())
        val text = runCatching { description(status) }.getOrDefault(status.description())
        return ServerStatus(text, players, status.version(), status.favicon(), status.enforcesSecureChat(), status.isModded())
    }

    private fun description(status: ServerStatus): Component {
        val m = Config.s.motd
        if (!m.enabled) return status.description()
        val s = current(m)
        return centered(head(m, s)).withStyle { it.withInsertion("$MARKER${m.frameSeconds}") }.append("\n").append(centered(ticker(m, s)))
    }

    private fun current(m: MotdSettings): Snapshot {
        val now = frame
        snapshot?.takeIf { it.frame == now && it.items == m.itemsPerFrame && it.frames == m.frames }?.let { return it }
        return Snapshot(now, m.itemsPerFrame, m.frames, MarketApi.ticker(m.itemsPerFrame * m.frames).chunked(m.itemsPerFrame), ClaimsApi.countries().size).also { snapshot = it }
    }

    private fun text(key: String, vararg args: Any) = DiscordText.get("kami_essentials", DiscordText.FALLBACK, "kami_essentials.motd.$key", *args)

    private fun head(m: MotdSettings, s: Snapshot): List<Piece> =
        listOf(Piece(m.title, Theme.ACCENT), separator, Piece(text(if (s.countries == 1) "countries.one" else "countries.other", s.countries), Theme.TEXT))

    private fun ticker(m: MotdSettings, s: Snapshot): List<Piece> {
        val pages = s.pages
        if (pages.isEmpty()) return listOf(Piece(text("closed"), Theme.MUTED))
        val line = ArrayList<Piece>()
        pages[(s.frame % pages.size).toInt()].forEach { q ->
            val next = (if (line.isEmpty()) emptyList() else listOf(separator)) + quote(q, m)
            if (width(line + next) > WIDTH) return line
            line += next
        }
        return line
    }

    private fun price(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(Locale.ROOT, n / 1_000_000.0)
        n >= 10_000 -> "%.1fk".format(Locale.ROOT, n / 1_000.0)
        else -> n.toString()
    }

    private fun quote(q: Quote, m: MotdSettings): List<Piece> {
        val (arrow, color) = when {
            q.changePermille > 0 -> "▲" to Theme.OK
            q.changePermille < 0 -> "▼" to Theme.BAD
            else -> "■" to Theme.MUTED
        }
        return listOf(
            Piece("${q.name} ", Theme.TEXT),
            Piece(price(q.price) + m.currency, Theme.VALUE),
            Piece(" $arrow" + "%.1f%%".format(Locale.ROOT, abs(q.changePermille) / 10.0), color)
        )
    }

    private fun centered(pieces: List<Piece>) = Component.literal(" ".repeat(((WIDTH - width(pieces)) / 8).coerceAtLeast(0))).also { out ->
        pieces.forEach { p -> out.append(Component.literal(p.text).withColor(p.color)) }
    }

    private fun width(pieces: List<Piece>) = pieces.sumOf { p -> p.text.sumOf(::width) }

    private fun width(c: Char) = when (c) {
        'i', '!', '.', ',', ':', ';', '|', '\'', '·' -> 2
        'l', '`' -> 3
        'I', 't', ' ', '[', ']' -> 4
        'f', 'k', '<', '>', '"', '(', ')', '*' -> 5
        '@', '~' -> 7
        else -> 6
    }
}

package kami.essentials.display

import kami.essentials.Config
import kami.essentials.MotdSettings
import kami.libs.chat.Theme
import kami.libs.claims.ClaimsApi
import kami.libs.economy.Coins
import kami.libs.economy.MarketApi
import kami.libs.economy.Quote
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.status.ServerStatus
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import java.util.Locale
import kotlin.math.abs

object Motd {
    private const val WIDTH = 270
    private const val GLYPH = 9
    private val glyphs = ResourceLocation.fromNamespaceAndPath("kami_libs", "glyphs")
    private val separator = Piece("  ·  ", Theme.MUTED)
    private var frame = 0L

    private class Piece(val text: String, val color: Int, val glyph: Boolean = false)

    fun tick(server: MinecraftServer) {
        val m = Config.s.motd
        if (!m.enabled) return
        val now = System.currentTimeMillis() / (m.frameSeconds * 1000L)
        if (now == frame) return
        frame = now
        server.invalidateStatus()
    }

    fun apply(status: ServerStatus): ServerStatus {
        val m = Config.s.motd
        if (!m.enabled) return status
        val text = centered(head(m)).append("\n").append(centered(ticker(m)))
        return ServerStatus(text, status.players(), status.version(), status.favicon(), status.enforcesSecureChat(), status.isModded())
    }

    private fun head(m: MotdSettings): List<Piece> =
        listOf(Piece(m.title, Theme.ACCENT), separator, Piece(Component.translatable("kami_essentials.motd.countries", ClaimsApi.countries().size).string, Theme.TEXT))

    private fun ticker(m: MotdSettings): List<Piece> {
        val pages = MarketApi.ticker(m.itemsPerFrame * m.frames).chunked(m.itemsPerFrame)
        if (pages.isEmpty()) return listOf(Piece(Component.translatable("kami_essentials.motd.closed").string, Theme.MUTED))
        val line = ArrayList<Piece>()
        pages[(frame % pages.size).toInt()].forEach { q ->
            val next = (if (line.isEmpty()) emptyList() else listOf(separator)) + quote(q)
            if (width(line + next) > WIDTH) return line
            line += next
        }
        return line
    }

    private fun quote(q: Quote): List<Piece> {
        val coin = Coins.compactParts(q.price)
        val (arrow, color) = when {
            q.changePermille > 0 -> "▲" to Theme.OK
            q.changePermille < 0 -> "▼" to Theme.BAD
            else -> "■" to Theme.MUTED
        }
        return listOf(
            Piece("${q.name} ", Theme.TEXT),
            Piece(coin.amount, Theme.VALUE),
            Piece(coin.glyph.toString(), 0xFFFFFF, true),
            Piece(" $arrow" + "%.1f%%".format(Locale.ROOT, abs(q.changePermille) / 10.0), color)
        )
    }

    private fun centered(pieces: List<Piece>) = Component.literal(" ".repeat(((WIDTH - width(pieces)) / 8).coerceAtLeast(0))).also { out ->
        pieces.forEach { p -> out.append(Component.literal(p.text).withStyle { s -> s.withColor(p.color).let { if (p.glyph) it.withFont(glyphs) else it } }) }
    }

    private fun width(pieces: List<Piece>) = pieces.sumOf { p -> if (p.glyph) p.text.length * GLYPH else p.text.sumOf(::width) }

    private fun width(c: Char) = when (c) {
        'i', '!', '.', ',', ':', ';', '|', '\'', '·' -> 2
        'l', '`' -> 3
        'I', 't', ' ', '[', ']' -> 4
        'f', 'k', '<', '>', '"', '(', ')', '*' -> 5
        '@', '~' -> 7
        else -> 6
    }
}

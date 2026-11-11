package kami.claims.press

import kami.libs.discord.Embed
import kami.libs.press.Block
import kami.libs.press.Doc
import kami.libs.press.Ref
import kami.libs.press.Span
import kami.libs.press.toDiscord
import java.time.Instant

object NewsFormat {
    const val LIMIT = 4000
    const val COLOR = 0x3A3A3A
    private const val ROOM_MAX = 1000
    private const val SUMMARY_MAX = 200

    private fun name(id: String) = id.substringAfter(':').split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun readable(ref: Ref, snaps: List<RefSnap>): String {
        val snap = snaps.firstOrNull { it.kind == ref.kind && it.key == ref.id }
        return when (ref) {
            is Ref.Price -> if (snap == null) name(ref.item) else "${name(ref.item)}: ${snap.value} spur"
            is Ref.Flag, is Ref.Head -> snap?.name?.ifEmpty { null } ?: ref.id
            is Ref.Item, is Ref.Icon -> name(ref.id)
        }
    }

    private fun swap(spans: List<Span>, snaps: List<RefSnap>) = spans.map { s -> s.ref?.let { s.copy(text = readable(it, snaps), ref = null) } ?: s }

    fun resolve(doc: Doc, snaps: List<RefSnap>) = Doc(doc.blocks.map { b ->
        when (b) {
            is Block.Heading -> b.copy(spans = swap(b.spans, snaps))
            is Block.Para -> b.copy(spans = swap(b.spans, snaps))
            is Block.Quote -> b.copy(spans = swap(b.spans, snaps))
            is Block.Bullets -> b.copy(items = b.items.map { swap(it, snaps) })
            Block.Rule -> b
        }
    })

    private fun line(text: String, bold: Boolean = false, italic: Boolean = false) = Block.Para(listOf(Span(text, bold = bold, italic = italic)))

    private val escaped = Regex("\\\\.")

    private fun markers(text: String): Pair<Boolean, Boolean> {
        val bare = escaped.replace(text, "")
        return ((bare.split("**").size - 1) % 2 == 1) to (bare.replace("**", "").count { it == '_' } % 2 == 1)
    }

    private fun closers(text: String) = markers(text).let { (bold, italic) -> (if (italic) "_" else "") + (if (bold) "**" else "") }

    private fun reopen(text: String) = markers(text).let { (bold, italic) -> (if (bold) "**" else "") + (if (italic) "_" else "") }

    private fun fit(rest: String, room: Int): Int {
        var end = room.coerceAtLeast(1)
        if (end >= rest.length) return rest.length
        if (rest[end].isLowSurrogate()) end += if (end > 1) -1 else 1
        if (rest.substring(0, end).takeLastWhile { it == '\\' }.length % 2 == 1 && end > 1) end--
        return end
    }

    private fun cut(line: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        var rest = line
        var open = ""
        while (open.length + rest.length > limit) {
            var take = fit(rest, limit - open.length)
            if (closers(open + rest.take(take)).isNotEmpty()) take = fit(rest, limit - open.length - 3)
            val head = open + rest.take(take)
            out += head + closers(head)
            open = reopen(head)
            rest = rest.drop(take)
        }
        out += open + rest
        return out
    }

    fun split(text: String, limit: Int = LIMIT): List<String> {
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        for (raw in text.split('\n')) {
            for (piece in cut(raw, limit)) {
                if (cur.isNotEmpty() && cur.length + 1 + piece.length > limit) {
                    parts += cur.toString()
                    cur.clear()
                }
                if (cur.isNotEmpty()) cur.append('\n')
                cur.append(piece)
            }
        }
        if (cur.isNotBlank()) parts += cur.toString()
        return parts
    }

    fun plain(text: String) = Doc(listOf(line(text))).toDiscord()

    fun embeds(title: String, subtitle: String, body: Doc, footer: String, created: Long, limit: Int = LIMIT): List<Embed> {
        val head = if (subtitle.isBlank()) emptyList() else listOf(line(subtitle, italic = true))
        val parts = split(Doc(head + body.blocks).toDiscord(), limit).ifEmpty { listOf("") }
        return parts.mapIndexed { i, text ->
            val last = i == parts.lastIndex
            Embed(text, COLOR, title = title.takeIf { i == 0 }, footer = footer.takeIf { last }, timestamp = Instant.ofEpochMilli(created).takeIf { last })
        }
    }

    fun room(title: String, subtitle: String, body: Doc): String {
        val first = body.blocks.firstOrNull { it != Block.Rule }?.let { Doc(listOf(it)).toDiscord().replace('\n', ' ').take(SUMMARY_MAX) }.orEmpty()
        val blocks = listOf(line(title, bold = true)) + (if (subtitle.isBlank()) emptyList() else listOf(line(subtitle, italic = true)))
        return (Doc(blocks).toDiscord() + if (first.isBlank()) "" else "\n$first").take(ROOM_MAX)
    }
}

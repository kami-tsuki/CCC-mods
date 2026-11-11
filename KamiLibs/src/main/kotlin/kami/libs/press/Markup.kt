package kami.libs.press

object Markup {
    internal val token = Regex("\\{(?:c:([A-Za-z_]+)|(/c)|(price|flag|head|item|icon):([A-Za-z0-9_.:/-]{1,64}))}")
    private val heading = Regex("^(#{1,6}) +(\\S.*)$")
    private val image = Regex("!\\[([^\\[\\]]*)]\\([^()]*\\)")
    private val link = Regex("\\[([^\\[\\]]*)]\\([^()]*\\)")
    private val html = Regex("</?[A-Za-z][^<>]*>")
    private val junk = Regex("[\\p{Cc}\\p{Cf}§&&[^\\n]]")
    private val lineJunk = Regex("[\\s\\p{Cc}\\p{Cf}§]+")

    fun clean(text: String, maxChars: Int): String = strip(text).trim().take(maxChars)

    fun cleanLine(text: String, maxChars: Int): String = lineJunk.replace(text, " ").trim().take(maxChars).trimEnd()

    fun length(doc: Doc): Int = doc.blocks.sumOf { b -> b.lines().sumOf { l -> l.sumOf { it.text.length } } }

    fun refs(doc: Doc): List<Ref> = doc.blocks.flatMap { b -> b.lines().flatMap { l -> l.mapNotNull { it.ref } } }.distinct()

    fun parse(text: String): Doc {
        val blocks = ArrayList<Block>()
        var items = ArrayList<List<Span>>()
        fun close() {
            if (items.isNotEmpty()) blocks += Block.Bullets(items)
            items = ArrayList()
        }
        for (raw in strip(text).split('\n')) {
            val line = raw.trim()
            val head = heading.matchEntire(line)
            when {
                line.isEmpty() -> close()
                line == "---" -> {
                    close()
                    blocks += Block.Rule
                }
                line.startsWith("- ") || line.startsWith("* ") -> inline(line.drop(2)).takeIf { it.isNotEmpty() }?.let { items += it }
                else -> {
                    close()
                    when {
                        head != null -> inline(head.groupValues[2]).takeIf { it.isNotEmpty() }?.let { blocks += Block.Heading(minOf(head.groupValues[1].length, 2), it) }
                        line.startsWith("> ") -> inline(line.drop(2)).takeIf { it.isNotEmpty() }?.let { blocks += Block.Quote(it) }
                        line.startsWith("^ ") -> inline(line.drop(2)).takeIf { it.isNotEmpty() }?.let { blocks += Block.Para(it, true) }
                        else -> inline(line).takeIf { it.isNotEmpty() }?.let { blocks += Block.Para(it) }
                    }
                }
            }
        }
        close()
        return Doc(blocks)
    }

    private fun strip(text: String) = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').replace(junk, "")

    private fun inline(line: String): List<Span> {
        val s = html.replace(link.replace(image.replace(line) { it.groupValues[1] }) { it.groupValues[1] }, "").trim()
        val out = ArrayList<Span>()
        val buf = StringBuilder()
        val colors = ArrayList<Dye?>()
        var bold = false
        var italic = false
        fun flush() {
            if (buf.isNotEmpty()) out += Span(buf.toString(), bold, italic, colors.lastOrNull { it != null })
            buf.clear()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val m = if (c == '{') token.matchAt(s, i) else null
            when {
                m != null -> {
                    val (name, close, kind, id) = m.destructured
                    when {
                        close.isNotEmpty() -> {
                            flush()
                            colors.removeLastOrNull()
                        }
                        kind.isNotEmpty() -> {
                            flush()
                            out += Span("", bold, italic, colors.lastOrNull { it != null }, ref(kind, id.lowercase()))
                        }
                        else -> {
                            flush()
                            colors += Dye.byId(name)
                        }
                    }
                    i += m.value.length
                }
                s.startsWith("**", i) && (bold || s.indexOf("**", i + 2) >= 0) -> {
                    flush()
                    bold = !bold
                    i += 2
                }
                (c == '*' || c == '_') && (if (italic) i > 0 && !s[i - 1].isWhitespace() else opensItalic(s, i)) -> {
                    flush()
                    italic = !italic
                    i++
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        flush()
        return out
    }

    internal fun opensItalic(s: String, i: Int): Boolean {
        val c = s[i]
        if (i + 1 >= s.length || s[i + 1].isWhitespace() || s[i + 1] == c) return false
        if (c == '_' && i > 0 && s[i - 1].isLetterOrDigit()) return false
        return s.indexOf(c, i + 2) > 0
    }

    private fun ref(kind: String, id: String): Ref = when (kind.lowercase()) {
        "price" -> Ref.Price(id)
        "flag" -> Ref.Flag(id)
        "head" -> Ref.Head(id)
        "item" -> Ref.Item(id)
        else -> Ref.Icon(id)
    }
}

package kami.libs.ui.press

import kami.libs.press.Block
import kami.libs.press.Doc
import kami.libs.press.Dye
import kami.libs.press.Ref
import kami.libs.press.Span

fun interface TextMeasure {
    fun width(text: String, bold: Boolean): Int
}

class FlagLook(val color: Int, val pattern: Int, val emblem: Int, val secondary: Int)

class PriceStamp(val value: String, val date: String)

class NewsData(
    val flag: (String) -> FlagLook? = { null }, val price: (String) -> PriceStamp? = { null }, val head: (String) -> String? = { null },
    val country: (String) -> String? = { null }, val item: (String) -> String? = { null }
)

val NoNewsData = NewsData()

data class Article(
    val title: String = "", val subtitle: String = "", val doc: Doc = Doc(emptyList()), val signature: String = "", val data: NewsData = NoNewsData
)

class Piece(
    val x: Int, val w: Int, val text: String = "", val ref: Ref? = null, val bold: Boolean = false, val italic: Boolean = false,
    val color: Dye? = null, val scale: Int = 1, val muted: Boolean = false, val chip: ChipSpec? = null
)

enum class RowKind { TEXT, RULE, DOUBLE, CAP, BAR, VRULE }

class Row(val kind: RowKind, val x: Int, val y: Int, val w: Int, val h: Int, val pieces: List<Piece> = emptyList())

class Layout(val width: Int, val height: Int, val columns: Int, val rows: List<Row>)

object NewsLayout {
    const val PAD = 8
    const val GAP = 10
    const val LINE = 10
    const val QUOTE_INDENT = 8
    const val BULLET_INDENT = 9
    const val CAP_SCALE = 3
    const val TITLE_SCALE = 2
    private const val MIN_CAP_LENGTH = 60
    private const val CAP_LINES = 3

    fun columnsFor(inner: Int) = when {
        inner >= 420 -> 3
        inner >= 250 -> 2
        else -> 1
    }

    fun refWidth(ref: Ref, data: NewsData, m: TextMeasure): Int = Chips.width(Chips.spec(ref, data), m, 0, true)

    private class Word(
        val text: String, val ref: Ref?, val bold: Boolean, val italic: Boolean, val dye: Dye?, val muted: Boolean,
        val scale: Int, val w: Int, val trimmed: Int, val chip: ChipSpec? = null
    )

    private class Cell(val kind: RowKind, val h: Int, val gap: Int, val pieces: List<Piece> = emptyList(), val cap: Piece? = null, val bar: Boolean = false, val keep: Boolean = false)

    private fun words(spans: List<Span>, m: TextMeasure, data: NewsData, maxW: Int, bold: Boolean = false, italic: Boolean = false, muted: Boolean = false, scale: Int = 1): List<Word> {
        val out = ArrayList<Word>()
        for (s in spans) {
            val b = bold || s.bold
            val i = italic || s.italic
            if (s.ref != null) {
                val spec = Chips.spec(s.ref!!, data)
                val w = (Chips.width(spec, m, 0, true) + 2).coerceAtMost(maxW.coerceAtLeast(1))
                out += Word("", s.ref, b, i, s.color, muted, 1, w, w, spec)
                continue
            }
            for (part in WORD.findAll(s.text)) {
                val t = part.value
                out += Word(t, null, b, i, s.color, muted, scale, m.width(t, b) * scale, m.width(t.trimEnd(), b) * scale)
            }
        }
        return out
    }

    private val WORD = Regex("\\S+\\s*|\\s+")

    private fun fill(words: List<Word>, m: TextMeasure, avail: (Int) -> Int): List<List<Word>> {
        val lines = ArrayList<List<Word>>()
        var cur = ArrayList<Word>()
        var x = 0
        fun next() {
            lines += cur
            cur = ArrayList()
            x = 0
        }
        for (original in words) {
            var word = original
            if (cur.isNotEmpty() && x + word.trimmed > avail(lines.size)) next()
            while (word.ref == null && word.trimmed > avail(lines.size) && word.text.codePointCount(0, word.text.length) > 1) {
                val limit = avail(lines.size)
                var k = word.text.offsetByCodePoints(0, 1)
                while (k < word.text.length) {
                    val next = word.text.offsetByCodePoints(k, 1)
                    if (next >= word.text.length || m.width(word.text.substring(0, next), word.bold) * word.scale > limit) break
                    k = next
                }
                val head = word.text.substring(0, k)
                val tail = word.text.substring(k)
                cur += Word(head, null, word.bold, word.italic, word.dye, word.muted, word.scale, m.width(head, word.bold) * word.scale, m.width(head, word.bold) * word.scale)
                next()
                word = Word(tail, null, word.bold, word.italic, word.dye, word.muted, word.scale, m.width(tail, word.bold) * word.scale, m.width(tail.trimEnd(), word.bold) * word.scale)
            }
            cur += word
            x += word.w
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    private fun pieces(line: List<Word>, m: TextMeasure, indent: Int, avail: Int, center: Boolean): List<Piece> {
        class Acc(val first: Word, var text: String)
        val groups = ArrayList<Any>()
        for (w in line) {
            val last = groups.lastOrNull()
            if (w.ref == null && last is Acc && last.first.ref == null && last.first.bold == w.bold && last.first.italic == w.italic &&
                last.first.dye == w.dye && last.first.muted == w.muted && last.first.scale == w.scale
            ) last.text += w.text
            else groups += if (w.ref == null) Acc(w, w.text) else w
        }
        var x = indent
        val out = ArrayList<Piece>()
        groups.forEachIndexed { i, g ->
            if (g is Acc) {
                val text = if (i == groups.lastIndex) g.text.trimEnd() else g.text
                val w = m.width(text, g.first.bold) * g.first.scale
                out += Piece(x, w, text, null, g.first.bold, g.first.italic, g.first.dye, g.first.scale, g.first.muted)
                x += w
            } else {
                val w = g as Word
                out += Piece(x, w.w, "", w.ref, w.bold, w.italic, w.dye, 1, w.muted, w.chip)
                x += w.w
            }
        }
        val shift = if (center && out.isNotEmpty()) ((avail - (x - indent)) / 2).coerceAtLeast(0) else 0
        return if (shift == 0) out else out.map { Piece(it.x + shift, it.w, it.text, it.ref, it.bold, it.italic, it.color, it.scale, it.muted, it.chip) }
    }

    private fun textCells(
        spans: List<Span>, m: TextMeasure, data: NewsData, width: Int, indent: Int = 0, gap: Int = 4, h: Int = 10, bold: Boolean = false,
        italic: Boolean = false, center: Boolean = false, bar: Boolean = false, bullet: Boolean = false, scale: Int = 1, muted: Boolean = false
    ): List<Cell> {
        val lines = fill(words(spans, m, data, width - indent, bold, italic, muted, scale), m) { width - indent }
        return lines.mapIndexed { i, line ->
            val p = pieces(line, m, indent, width - indent, center)
            val all = if (bullet && i == 0) listOf(Piece(0, m.width("•", false), "•")) + p else p
            Cell(RowKind.TEXT, h, if (i == 0) gap else 0, all, bar = bar)
        }
    }

    private fun body(doc: Doc, colW: Int, m: TextMeasure, data: NewsData): List<Cell> {
        val cells = ArrayList<Cell>()
        doc.blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Heading -> {
                    cells += textCells(block.spans, m, data, colW, gap = 6, bold = true)
                    if (block.level == 1) cells += Cell(RowKind.RULE, 1, 1)
                }
                is Block.Para -> cells += paragraph(block, index == 0, colW, m, data)
                is Block.Quote -> cells += textCells(block.spans, m, data, colW, indent = QUOTE_INDENT, italic = true, bar = true)
                is Block.Bullets -> block.items.forEachIndexed { i, item ->
                    cells += textCells(item, m, data, colW, indent = BULLET_INDENT, gap = if (i == 0) 4 else 2, bullet = true)
                }
                Block.Rule -> cells += Cell(RowKind.DOUBLE, 5, 4)
            }
        }
        return cells
    }

    private fun paragraph(block: Block.Para, first: Boolean, colW: Int, m: TextMeasure, data: NewsData): List<Cell> {
        val head = block.spans.firstOrNull()
        val capChar = head?.takeIf { first && !block.centered && it.ref == null && it.text.firstOrNull()?.isLetter() == true && block.spans.sumOf { s -> s.text.length } >= MIN_CAP_LENGTH }?.text?.first()
        if (capChar == null) return textCells(block.spans, m, data, colW, center = block.centered)
        val capW = m.width(capChar.toString(), true) * CAP_SCALE
        val indent = capW + 2
        val spans = listOf(Span(head.text.drop(1), head.bold, head.italic, head.color)) + block.spans.drop(1)
        val lines = fill(words(spans, m, data, colW - indent), m) { if (it < CAP_LINES) colW - indent else colW }
        if (lines.size < CAP_LINES) return textCells(block.spans, m, data, colW, center = block.centered)
        val cap = Piece(0, capW, capChar.toString(), null, true, false, head.color, CAP_SCALE)
        return lines.mapIndexed { i, line ->
            Cell(RowKind.TEXT, LINE, if (i == 0) 4 else 0, pieces(line, m, if (i < CAP_LINES) indent else 0, colW, false), if (i == 0) cap else null, keep = i < CAP_LINES - 1)
        }
    }

    fun layout(article: Article, width: Int, m: TextMeasure): Layout {
        val inner = (width - PAD * 2).coerceAtLeast(40)
        val cols = columnsFor(inner)
        val colW = (inner - GAP * (cols - 1)) / cols
        val rows = ArrayList<Row>()
        var y = PAD
        fun header(cells: List<Cell>) {
            cells.forEachIndexed { i, c ->
                y += if (i == 0) c.gap else 0
                rows += Row(c.kind, PAD, y, inner, c.h, c.pieces)
                y += c.h
            }
        }
        if (article.title.isNotBlank()) header(textCells(listOf(Span(article.title.trim())), m, article.data, inner, gap = 0, h = LINE * TITLE_SCALE, bold = true, scale = TITLE_SCALE))
        if (article.subtitle.isNotBlank()) header(textCells(listOf(Span(article.subtitle.trim())), m, article.data, inner, gap = 2, italic = true))
        if (article.signature.isNotBlank()) header(textCells(listOf(Span(article.signature.trim())), m, article.data, inner, gap = 2, italic = true, muted = true))
        if (y > PAD) {
            y += 4
            rows += Row(RowKind.DOUBLE, PAD, y, inner, 5)
            y += 5 + 4
        }
        val cells = body(article.doc, colW, m, article.data)
        val total = cells.sumOf { it.h + it.gap }
        val target = (total + cols - 1) / cols
        val top = y
        var col = 0
        var colY = 0
        var tallest = 0
        var held = false
        for (cell in cells) {
            var gap = if (colY == 0) 0 else cell.gap
            if (col < cols - 1 && colY > 0 && !held && colY + gap + cell.h > target) {
                tallest = maxOf(tallest, colY)
                col++
                colY = 0
                gap = 0
            }
            val cy = top + colY + gap
            val cx = PAD + col * (colW + GAP)
            when (cell.kind) {
                RowKind.TEXT -> {
                    rows += Row(RowKind.TEXT, cx, cy, colW, cell.h, cell.pieces)
                    if (cell.bar) rows += Row(RowKind.BAR, cx, cy, 2, cell.h)
                    cell.cap?.let { rows += Row(RowKind.CAP, cx, cy, it.w, it.scale * 8, listOf(it)) }
                }
                else -> rows += Row(cell.kind, cx, cy, colW, cell.h)
            }
            colY += gap + cell.h
            held = cell.keep
        }
        tallest = maxOf(tallest, colY)
        for (i in 1 until cols) rows += Row(RowKind.VRULE, PAD + i * (colW + GAP) - GAP / 2, top, 1, tallest)
        return Layout(width, top + tallest + PAD, cols, rows)
    }
}

package kami.libs.ui.press

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Atom(val start: Int, val end: Int)

object Atoms {
    private val pattern = Regex("\\{(?:price|flag|head|item|icon):[A-Za-z0-9_.:/-]{1,64}}")

    fun find(text: String): List<Atom> = pattern.findAll(text).map { Atom(it.range.first, it.range.last + 1) }.toList()
}

private enum class Kind { NONE, TYPE, DELETE, OTHER }

private const val UNDO_LIMIT = 200

private fun String.fit(n: Int): String {
    val cut = take(n)
    return if (cut.isNotEmpty() && cut.length < length && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

class EditBuffer(initial: String = "", val limit: Int = 5000) {
    var text = initial.fit(limit)
        private set
    var cursor = text.length
        private set
    var anchor = cursor
        private set
    var version = 0
        private set

    private class Snap(val text: String, val cursor: Int, val anchor: Int)

    private val undoStack = ArrayDeque<Snap>()
    private val redoStack = ArrayDeque<Snap>()
    private var lastKind = Kind.NONE
    private var lastCursor = -1
    private var sealed = false
    private var atomsVersion = -1
    private var atomCache = emptyList<Atom>()

    val selStart get() = min(cursor, anchor)
    val selEnd get() = max(cursor, anchor)
    val hasSelection get() = cursor != anchor
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    var atomic = true

    val atoms: List<Atom>
        get() {
            if (!atomic) return emptyList()
            if (atomsVersion != version) {
                atomCache = Atoms.find(text)
                atomsVersion = version
            }
            return atomCache
        }

    fun selected() = text.substring(selStart, selEnd)

    fun set(value: String) {
        text = value.fit(limit)
        cursor = text.length
        anchor = cursor
        undoStack.clear()
        redoStack.clear()
        lastKind = Kind.NONE
        version++
    }

    private fun snap(raw: Int, forward: Boolean): Int {
        val pos = if (raw in 1 until text.length && text[raw - 1].isHighSurrogate() && text[raw].isLowSurrogate()) (if (forward) raw + 1 else raw - 1) else raw
        val atom = atoms.firstOrNull { it.start < pos && pos < it.end } ?: return pos
        return if (forward) atom.end else atom.start
    }

    private fun record(kind: Kind) {
        val merge = (kind == Kind.TYPE || kind == Kind.DELETE) && kind == lastKind && !sealed && cursor == lastCursor && !hasSelection
        if (!merge) {
            undoStack.addLast(Snap(text, cursor, anchor))
            if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        }
        redoStack.clear()
    }

    private fun edit(kind: Kind, from: Int, to: Int, insert: String, caret: Int = from + insert.length, anchorAt: Int = caret) {
        record(kind)
        text = text.substring(0, from) + insert + text.substring(to)
        cursor = caret
        anchor = anchorAt
        version++
        lastKind = kind
        lastCursor = cursor
        sealed = false
    }

    fun insert(s: String, paste: Boolean = false): Boolean {
        val clean = s.replace("\r\n", "\n").replace('\r', '\n').filter { it == '\n' || (it >= ' ' && it != '\u007f') }
        val room = (limit - (text.length - (selEnd - selStart))).coerceAtLeast(0)
        val fit = clean.fit(room)
        if (fit.isEmpty()) return false
        val typed = !paste && '\n' !in fit
        edit(if (typed) Kind.TYPE else Kind.OTHER, selStart, selEnd, fit)
        sealed = fit.last().isWhitespace()
        return true
    }

    fun enter(): Boolean {
        if (hasSelection) return insert("\n")
        val from = lineStart(cursor)
        val line = text.substring(from, lineEnd(cursor))
        val prefix = CONTINUED.firstOrNull { line.startsWith(it) }
        if (prefix == null || cursor < from + prefix.length) return insert("\n")
        if (line == prefix) {
            edit(Kind.OTHER, from, from + prefix.length, "", from)
            return true
        }
        return insert("\n" + prefix)
    }

    fun delete(forward: Boolean, word: Boolean): Boolean {
        if (hasSelection) {
            edit(Kind.OTHER, selStart, selEnd, "")
            return true
        }
        val target = when {
            forward -> atoms.firstOrNull { it.start == cursor }?.end ?: if (word) wordEnd(cursor) else after(cursor)
            else -> atoms.firstOrNull { it.end == cursor }?.start ?: if (word) wordStart(cursor) else before(cursor)
        }
        if (target == cursor) return false
        edit(Kind.DELETE, min(target, cursor), max(target, cursor), "")
        return true
    }

    fun move(to: Int, select: Boolean, forward: Boolean = true) {
        cursor = snap(to.coerceIn(0, text.length), forward)
        if (!select) anchor = cursor
        lastKind = Kind.NONE
    }

    fun step(delta: Int, select: Boolean, word: Boolean) {
        if (!select && hasSelection && !word) return move(if (delta < 0) selStart else selEnd, false)
        val target = if (word) (if (delta < 0) wordStart(cursor) else wordEnd(cursor)) else if (delta < 0) before(cursor) else after(cursor)
        move(target, select, delta > 0)
    }

    private fun after(pos: Int) = if (pos >= text.length) text.length else pos + Character.charCount(text.codePointAt(pos))

    private fun before(pos: Int) = if (pos <= 0) 0 else pos - Character.charCount(text.codePointBefore(pos))

    fun selectAll() {
        anchor = 0
        cursor = text.length
        lastKind = Kind.NONE
    }

    fun selectWord(pos: Int) {
        val p = pos.coerceIn(0, text.length)
        val atom = atoms.firstOrNull { it.start <= p && p < it.end }
        var a = atom?.start ?: p
        var b = atom?.end ?: p
        if (atom == null) {
            while (a > 0 && !text[a - 1].isWhitespace()) a--
            while (b < text.length && !text[b].isWhitespace()) b++
        }
        anchor = a
        cursor = b
        lastKind = Kind.NONE
    }

    fun selectLine(pos: Int) {
        anchor = lineStart(pos)
        cursor = lineEnd(pos)
        lastKind = Kind.NONE
    }

    fun lineStart(pos: Int) = text.lastIndexOf('\n', pos - 1) + 1

    fun lineEnd(pos: Int) = text.indexOf('\n', pos).let { if (it < 0) text.length else it }

    fun wordStart(from: Int): Int {
        var i = from
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return i
    }

    fun wordEnd(from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }

    fun undo(): Boolean = swap(undoStack, redoStack)

    fun redo(): Boolean = swap(redoStack, undoStack)

    private fun swap(from: ArrayDeque<Snap>, to: ArrayDeque<Snap>): Boolean {
        val s = from.removeLastOrNull() ?: return false
        to.addLast(Snap(text, cursor, anchor))
        text = s.text
        cursor = s.cursor
        anchor = s.anchor
        version++
        lastKind = Kind.NONE
        return true
    }

    fun toggleWrap(open: String, close: String): Boolean {
        val a = selStart
        val b = selEnd
        val inner = text.substring(a, b)
        if (inner.length >= open.length + close.length && inner.startsWith(open) && inner.endsWith(close)) {
            val bare = inner.substring(open.length, inner.length - close.length)
            edit(Kind.OTHER, a, b, bare, a + bare.length, a)
            return true
        }
        if (a >= open.length && text.startsWith(open, a - open.length) && text.startsWith(close, b)) {
            edit(Kind.OTHER, a - open.length, b + close.length, inner, a - open.length + inner.length, a - open.length)
            return true
        }
        if (text.length + open.length + close.length > limit) return false
        if (hasSelection) edit(Kind.OTHER, a, b, open + inner + close, a + open.length + inner.length, a + open.length)
        else edit(Kind.OTHER, a, b, open + close, a + open.length)
        return true
    }

    fun toggleLinePrefix(prefix: String): Boolean {
        val from = lineStart(selStart)
        val to = lineEnd(if (hasSelection && text[selEnd - 1] == '\n') selEnd - 1 else selEnd)
        val lines = text.substring(from, to).split('\n')
        val remove = lines.all { it.startsWith(prefix) }
        val out = lines.map { line ->
            val bare = BLOCK_PREFIXES.firstOrNull { line.startsWith(it) }?.let { line.removePrefix(it) } ?: line
            if (remove) bare else prefix + bare
        }
        val joined = out.joinToString("\n")
        if (text.length - (to - from) + joined.length > limit) return false
        if (hasSelection) edit(Kind.OTHER, from, to, joined, from + joined.length, from)
        else edit(Kind.OTHER, from, to, joined, (cursor + out[0].length - lines[0].length).coerceIn(from, from + joined.length))
        return true
    }

    fun insertBlock(block: String): Boolean {
        val piece = (if (selStart == lineStart(selStart)) "" else "\n") + block + "\n"
        if (text.length - (selEnd - selStart) + piece.length > limit) return false
        edit(Kind.OTHER, selStart, selEnd, piece)
        return true
    }

    companion object {
        val CONTINUED = listOf("- ", "* ", "> ")
        val BLOCK_PREFIXES = listOf("###### ", "##### ", "#### ", "### ", "## ", "# ", "> ", "- ", "^ ")
    }
}

class Seg(val start: Int, val end: Int, val soft: Boolean)

object Wrap {
    fun lines(text: String, width: Int, prefix: IntArray, atoms: List<Atom> = emptyList()): List<Seg> {
        val out = ArrayList<Seg>()
        var para = 0
        while (true) {
            val nl = text.indexOf('\n', para)
            val end = if (nl < 0) text.length else nl
            if (end == para) out += Seg(para, para, false)
            var s = para
            while (s < end) {
                val fit = fit(prefix, s, end, width)
                if (fit >= end) {
                    out += Seg(s, end, false)
                    break
                }
                val space = (fit downTo s + 1).firstOrNull { text[it - 1] == ' ' }
                var cut = when {
                    text[fit] == ' ' && fit > s -> fit + 1
                    space != null -> space
                    fit > s -> fit
                    else -> s + 1
                }
                atoms.firstOrNull { it.start < cut && cut < it.end }?.let { cut = if (it.start > s) it.start else it.end }
                if (cut < end && cut > s && text[cut - 1].isHighSurrogate() && text[cut].isLowSurrogate()) cut = if (cut - 1 > s) cut - 1 else cut + 1
                if (cut >= end) {
                    out += Seg(s, end, false)
                    break
                }
                out += Seg(s, cut, true)
                s = cut
            }
            if (nl < 0) break
            para = nl + 1
        }
        return out
    }

    private fun fit(prefix: IntArray, s: Int, end: Int, width: Int): Int {
        var lo = s
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (prefix[mid] - prefix[s] <= width) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun lineOf(segs: List<Seg>, pos: Int): Int {
        var lo = 0
        var hi = segs.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segs[mid].start <= pos) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun xOf(prefix: IntArray, seg: Seg, pos: Int) = prefix[pos.coerceIn(seg.start, seg.end)] - prefix[seg.start]

    fun lastPos(seg: Seg) = if (seg.soft) max(seg.start, seg.end - 1) else seg.end

    fun posAt(prefix: IntArray, seg: Seg, x: Int): Int {
        var best = seg.start
        for (p in seg.start..lastPos(seg)) {
            val here = prefix[p] - prefix[seg.start]
            if (abs(here - x) < abs(prefix[best] - prefix[seg.start] - x)) best = p
            if (here > x) break
        }
        return best
    }

    fun vertical(segs: List<Seg>, prefix: IntArray, pos: Int, delta: Int, goalX: Int): Int {
        val target = lineOf(segs, pos) + delta
        if (target < 0) return 0
        if (target > segs.lastIndex) return lastPos(segs.last())
        return posAt(prefix, segs[target], goalX)
    }
}

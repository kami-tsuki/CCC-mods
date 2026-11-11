package kami.libs.ui.press

import kami.libs.press.Dye
import kami.libs.press.Markup

class Run(val start: Int, val end: Int, val flags: Int, val dye: Dye?) {
    val bold get() = flags and MarkupStyler.BOLD != 0
    val italic get() = flags and MarkupStyler.ITALIC != 0
    val dim get() = flags and MarkupStyler.DIM != 0
    val token get() = flags and MarkupStyler.TOKEN != 0
}

class Active(val bold: Boolean, val italic: Boolean, val heading: Int, val quote: Boolean, val list: Boolean, val center: Boolean, val dye: Dye?)

object PressColors {
    private val table = intArrayOf(
        0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
        0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21
    )

    fun rgb(dye: Dye) = table[dye.ordinal]

    fun argb(dye: Dye) = 0xFF000000.toInt() or rgb(dye)
}

object MarkupStyler {
    const val BOLD = 1
    const val ITALIC = 2
    const val DIM = 4
    const val TOKEN = 8
    const val HEAD = 16
    const val QUOTE = 32

    private val heading = Regex("^#{1,6} +(?=\\S)")

    fun runs(text: String): List<Run> {
        val flags = IntArray(text.length)
        val dyes = arrayOfNulls<Dye>(text.length)
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            line(text, start, end, flags, dyes)
            start = end + 1
        }
        val out = ArrayList<Run>()
        var from = 0
        for (i in 1..text.length) {
            if (i == text.length || flags[i] != flags[from] || dyes[i] != dyes[from]) {
                out += Run(from, i, flags[from], dyes[from])
                from = i
            }
        }
        return out
    }

    fun active(text: String, pos: Int): Active {
        val at = pos.coerceIn(0, text.length)
        val runs = runs(text)
        val run = listOf(at - 1, at).firstNotNullOfOrNull { i -> runs.firstOrNull { i >= it.start && i < it.end && !it.dim && !it.token } }
        val from = text.lastIndexOf('\n', at - 1) + 1
        val line = text.substring(from, text.indexOf('\n', at).let { if (it < 0) text.length else it })
        val level = heading.find(line)?.value?.trim()?.length ?: 0
        return Active(
            run?.bold == true && level == 0, run?.italic == true && !line.startsWith("> "), level, line.startsWith("> "),
            line.startsWith("- ") || line.startsWith("* "), line.startsWith("^ "), run?.dye
        )
    }

    private fun line(text: String, ls: Int, le: Int, flags: IntArray, dyes: Array<Dye?>) {
        val s = text.substring(ls, le)
        var base = 0
        var mark = 0
        when {
            s == "---" -> mark = s.length
            heading.find(s) != null -> { mark = heading.find(s)!!.value.length; base = BOLD or HEAD }
            s.startsWith("> ") -> { mark = 2; base = ITALIC or QUOTE }
            s.startsWith("- ") || s.startsWith("* ") || s.startsWith("^ ") -> mark = 2
        }
        for (k in 0 until mark) flags[ls + k] = DIM
        var bold = false
        var italic = false
        val colors = ArrayList<Dye?>()
        var i = mark
        fun paint(from: Int, to: Int, f: Int, dye: Dye?) {
            for (k in from until to) { flags[ls + k] = f; dyes[ls + k] = dye }
        }
        while (i < s.length) {
            val c = s[i]
            val m = if (c == '{') Markup.token.matchAt(s, i) else null
            when {
                m != null -> {
                    val (name, close, kind, _) = m.destructured
                    when {
                        kind.isNotEmpty() -> paint(i, i + m.value.length, TOKEN, colors.lastOrNull { it != null })
                        close.isNotEmpty() -> {
                            paint(i, i + m.value.length, DIM, colors.lastOrNull { it != null })
                            colors.removeLastOrNull()
                        }
                        else -> {
                            colors += Dye.byId(name)
                            paint(i, i + m.value.length, DIM, colors.last())
                        }
                    }
                    i += m.value.length
                }
                s.startsWith("**", i) && (bold || s.indexOf("**", i + 2) >= 0) -> {
                    paint(i, i + 2, DIM, null)
                    bold = !bold
                    i += 2
                }
                (c == '*' || c == '_') && (if (italic) i > 0 && !s[i - 1].isWhitespace() else Markup.opensItalic(s, i)) -> {
                    paint(i, i + 1, DIM, null)
                    italic = !italic
                    i++
                }
                else -> {
                    paint(i, i + 1, base or (if (bold) BOLD else 0) or (if (italic) ITALIC else 0), colors.lastOrNull { it != null })
                    i++
                }
            }
        }
    }
}

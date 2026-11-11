package kami.libs.ui.press

object Paste {
    private val lineBreak = Regex("(?i)<br\\s*/?>|</(?:p|div|h[1-6]|li|tr)>")
    private val listItem = Regex("(?i)<li[^>]*>")
    private val tag = Regex("</?[A-Za-z][^<>]*>")
    private val image = Regex("!\\[([^\\[\\]]*)]\\([^()]*\\)")
    private val link = Regex("\\[([^\\[\\]]*)]\\([^()]*\\)")
    private val fence = Regex("(?m)^\\s*(?:```|~~~).*$\\n?")
    private val code = Regex("`([^`\\n]+)`")
    private val strike = Regex("~~([^~\\n]+)~~")
    private val underBold = Regex("__([^_\\n]+)__")
    private val bullet = Regex("(?m)^[ ]*(?:[*+•·]|\\d{1,3}[.)])[ ]+(?=\\S)")
    private val rule = Regex("(?m)^[ ]*(?:\\*{3,}|_{3,}|-{3,}|={3,})[ ]*$")
    private val dash = Regex("(?m)^[ ]*-[ ]+(?=\\S)")
    private val spaces = Regex("[ ]{2,}")
    private val blank = Regex("\\n{3,}")
    private val junk = Regex("[\\p{Cc}\\p{Cf}&&[^\\n]]")

    private fun entities(s: String) = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    fun smart(text: String): String {
        var s = text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').replace(' ', ' ')
        s = lineBreak.replace(listItem.replace(s, "\n- "), "\n")
        s = entities(tag.replace(s, ""))
        s = fence.replace(s, "")
        s = link.replace(image.replace(s) { it.groupValues[1] }) { it.groupValues[1] }
        s = strike.replace(code.replace(s) { it.groupValues[1] }) { it.groupValues[1] }
        s = underBold.replace(s) { "**${it.groupValues[1]}**" }
        s = rule.replace(s, "---")
        s = bullet.replace(s, "- ")
        s = dash.replace(s, "- ")
        s = junk.replace(s, "")
        s = s.lines().joinToString("\n") { l -> if (l.startsWith("- ") || l.startsWith("> ") || l.startsWith("#")) l else spaces.replace(l, " ") }
        return blank.replace(s, "\n\n").trim('\n')
    }
}
